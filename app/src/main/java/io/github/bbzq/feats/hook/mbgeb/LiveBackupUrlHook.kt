package io.github.bbzq.feats.hook.mbgeb

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.methodOrNull

/**
 * 让直播播放器使用 playurl 给的备用 CDN 地址。
 *
 * 直播播放器本来就有这套机制，只是被服务端的 DD 配置门控着：
 * `LivePlayerFFUtils.useBackupUrlIJKP2P()` 读 `dd.live_backup_url_v1`，
 * `useBackupUrlInline()` 读 `dd.live_inline_backup_url_enable`。这与关闭 P2P 用的是
 * 同一个套路 —— 让 App 既有的一条分支成立，而不是自己造一套。
 *
 * 配合 [io.github.bbzq.feats.hook.CustomCdnProcessor] 已经做的事：它会把用户配置的
 * CDN 主机（最多 4 台）连同原始地址一起写进 `backupUrl`。本开关把播放器打开到会读
 * `backupUrl`，于是"多 CDN 播放"就成立了 —— 主线路慢或失败时自动换一台。
 *
 * 为什么不做 Web 端那样的多主机竞速抢首字节：Android 端没有可用的 Java 层拦截点。
 * IJK 的媒体拉流在 native（`IAndroidIO` 这个 Java 回调接口存在，但全仓调用方为 0；
 * `IMediaDataSource` 的指令引用也是 0），media3 又只打包了 Asset/Content/File/
 * RawResource/Udp，没有 HTTP 数据源实现类。拦不到读取，就无从竞速。
 *
 * 注意：这两个开关原本由服务端按机型灰度，强制打开会改变播放器的取流行为。最坏的
 * 情况是它换到了另一条 CDN —— 那正是本开关的目的。
 */
class LiveBackupUrlHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (!ModuleSettings.isLiveBackupUrlEnabled(prefs)) return

        val ffClass = runCatching { classLoader.loadClass(FF_CLASS) }.getOrNull()
        if (ffClass == null) {
            log("LiveBackupUrl: $FF_CLASS not found, skipped")
            return
        }

        var hooked = 0
        BACKUP_URL_FLAGS.forEach { name ->
            val method = ffClass.methodOrNull(name)
            if (method == null) {
                log("LiveBackupUrl: $name not found, skipped")
                return@forEach
            }
            // 返回类型是 boolean 基本类型，赋 true 即可让调用方走"启用"分支
            env.hookAfter(method) { param -> param.result = true }
            hooked++
        }
        log("LiveBackupUrl: hooked $hooked/${BACKUP_URL_FLAGS.size} flags")
    }

    private companion object {
        private const val FF_CLASS = "com.bilibili.bililive.infra.util.ff.LivePlayerFFUtils"

        /**
         * 两个都是"使用备用地址"的变体，各自被不同的 DD 键门控。只开其中一个时，
         * 若实际路径走的是另一个就完全没有效果，所以两个都打开。
         */
        private val BACKUP_URL_FLAGS = listOf(
            "useBackupUrlIJKP2P",
            "useBackupUrlInline",
        )
    }
}
