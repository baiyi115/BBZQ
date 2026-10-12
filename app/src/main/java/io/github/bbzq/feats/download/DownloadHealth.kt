package io.github.bbzq.feats.download

/**
 * 下载的失败记忆：滑窗计数 + 冷却。
 *
 * 来自 Make Bilibili Great Than Ever Before 的 `ErrorCounter` 与主机状态机
 * （`recordOutcome` / `cooldown` / `exclude`）。那里的动机是"出错了别一直重试"，
 * 这里落在两处：
 *
 * - **单个源**：连续失败就冷却一段时间，期间不再优先选用它。某台 CDN 在本机网络上
 *   不通时，没必要每次下载都先撞一遍。
 * - **并行下载**：连续失败就暂时整体关掉。并行要多花一次探测请求并建立多条连接，
 *   在明确不奏效的场景（例如某些文件的服务端忽略 Range）反复尝试纯属浪费。
 *
 * 时间通过可替换的时钟读取，便于单测。
 */
object DownloadHealth {

    /** 最多记住这么多主机的状态。 */
    private const val MAX_TRACKED_HOSTS = 32

    /** 窗口内失败到这个次数就冷却该主机。 */
    private const val HOST_FAILURES_TO_COOLDOWN = 2

    /** 主机冷却时长。 */
    private const val HOST_COOLDOWN_MS = 5L * 60L * 1000L

    /** 窗口内并行失败到这个次数就暂时关掉并行。 */
    private const val PARALLEL_FAILURES_TO_DISABLE = 2

    /** 并行关闭时长，与上游一致取 30 秒。 */
    private const val PARALLEL_OFF_MS = 30L * 1000L

    /** 计数的滑动窗口。 */
    private const val WINDOW_MS = 60L * 1000L

    private var clock: () -> Long = System::currentTimeMillis

    private val hostFailures = BoundedMap<String, ArrayDeque<Long>>(MAX_TRACKED_HOSTS)
    private val hostCooldownUntil = BoundedMap<String, Long>(MAX_TRACKED_HOSTS)
    private val parallelFailures = ArrayDeque<Long>()
    private var parallelOffUntil = 0L

    /** 记一次该主机的下载失败。 */
    @Synchronized
    fun recordHostFailure(host: String) {
        if (host.isEmpty()) return
        val now = clock()
        val window = hostFailures.getOrPut(host) { ArrayDeque() }
        window.addLast(now)
        while (window.isNotEmpty() && now - window.first() > WINDOW_MS) window.removeFirst()
        if (window.size >= HOST_FAILURES_TO_COOLDOWN) {
            hostCooldownUntil[host] = now + HOST_COOLDOWN_MS
            window.clear()
        }
    }

    /** 记一次该主机的下载成功：清掉它的失败记录与冷却。 */
    @Synchronized
    fun recordHostSuccess(host: String) {
        if (host.isEmpty()) return
        hostFailures.remove(host)
        hostCooldownUntil.remove(host)
    }

    @Synchronized
    fun isHostCoolingDown(host: String): Boolean {
        val until = hostCooldownUntil[host] ?: return false
        if (until <= clock()) {
            hostCooldownUntil.remove(host)
            return false
        }
        return true
    }

    /** 候选源重排时用：冷却中的排到最后。 */
    @Synchronized
    fun rankLastCooling(candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        val (cooling, ready) = candidates.partition(::isHostCoolingDown)
        return if (cooling.isEmpty()) candidates else ready + cooling
    }

    @Synchronized
    fun recordParallelFailure() {
        val now = clock()
        parallelFailures.addLast(now)
        while (parallelFailures.isNotEmpty() && now - parallelFailures.first() > WINDOW_MS) {
            parallelFailures.removeFirst()
        }
        if (parallelFailures.size >= PARALLEL_FAILURES_TO_DISABLE) {
            parallelOffUntil = now + PARALLEL_OFF_MS
            parallelFailures.clear()
        }
    }

    /** 并行连续失败后应暂时跳过并行，交给顺序下载。 */
    @Synchronized
    fun shouldSkipParallel(): Boolean = clock() < parallelOffUntil

    /** @return 距离并行恢复还有多少毫秒，`0` 表示可用（仅用于日志）。 */
    @Synchronized
    fun parallelOffRemainingMs(): Long = (parallelOffUntil - clock()).coerceAtLeast(0L)

    @Synchronized
    internal fun reset() {
        hostFailures.clear()
        hostCooldownUntil.clear()
        parallelFailures.clear()
        parallelOffUntil = 0L
        clock = System::currentTimeMillis
    }

    @Synchronized
    internal fun useClock(provider: () -> Long) {
        clock = provider
    }

    /** 按最近最少使用淘汰，避免长会话里无限增长。 */
    private class BoundedMap<K, V>(private val limit: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > limit
    }
}
