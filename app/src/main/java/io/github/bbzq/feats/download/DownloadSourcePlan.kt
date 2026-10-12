package io.github.bbzq.feats.download

import io.github.bbzq.feats.hook.CdnAddressPolicy
import io.github.bbzq.feats.hook.CustomCdnProcessor

/**
 * 下载源的规划：把 playurl 给出的一个地址，按用户配置的 CDN 展开成一串候选。
 *
 * 与 [VideoDownloadManager] 分开是因为后者在初始化时就要 `Looper.getMainLooper()`，
 * 纯 JVM 单测碰不了它；这里的逻辑没有任何 Android 依赖，可以直接覆盖。
 */
object DownloadSourcePlan {

    data class Source(val url: String, val label: String)

    /**
     * 候选顺序：用户配置的 CDN 依次在前，playurl 原始地址兜底。
     *
     * 只有 upgcxcode 地址才换主机 —— 签名带在路径与 query 里，换主机后依然有效，
     * 这正是播放页换 CDN 的做法。非 upgcxcode 的地址换主机没有意义。PCDN 地址也
     * 不参与换主机：它本来就要被丢弃。Akamai 承载不了 upos 签名，由
     * [CdnAddressPolicy.usableHosts] 过滤掉。
     */
    fun plan(originalUrl: String, hosts: List<String>): List<Source> {
        if (originalUrl.isBlank()) return emptyList()
        val original = Source(originalUrl, ORIGINAL_LABEL)
        if (hosts.isEmpty()) return listOf(original)
        if (!originalUrl.contains(UPGCXCODE_PATH)) return listOf(original)
        if (CustomCdnProcessor.isPCdn(originalUrl)) return listOf(original)

        val configured = CdnAddressPolicy.usableHosts(hosts, originalUrl).mapNotNull { host ->
            val swapped = CustomCdnProcessor.replaceHost(originalUrl, host)
            if (swapped == originalUrl) null else Source(swapped, host)
        }
        return configured + original
    }

    /**
     * 取 `Content-Range: bytes a-b/total` 里的总长度。
     *
     * 已下载区间之后是总长度；`*`（服务端未知）或无法解析时返回 `null`。
     */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    const val ORIGINAL_LABEL = "playurl 源站"

    private const val UPGCXCODE_PATH = "/upgcxcode/"
}
