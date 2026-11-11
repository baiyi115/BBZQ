package io.github.bbzq.feats.hook

/** 只规划直连地址，不发探测请求，不接管播放器的 HTTP、缓存或重试。 */
internal object PlaybackCdnPlan {
    data class Plan(val base: String, val backups: List<String>)

    fun plan(
        base: String,
        backups: List<String>,
        hosts: List<String>,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Plan? {
        if (hosts.isEmpty()) return null
        val originals = (listOf(base) + backups).filter {
            (it.startsWith("https://") || it.startsWith("http://")) &&
                !CustomCdnProcessor.isPCdn(it) && !CdnAddressPolicy.isExpired(it, nowSeconds)
        }.distinct()
        if (originals.isEmpty()) return null // 不因模块策略清空宿主唯一的回源路径。
        val fresh = originals.filter { CdnAddressPolicy.isUsable(it, nowSeconds) }
        val source = fresh.firstOrNull { canSwapHost(it) }
        val ordered = LinkedHashSet<String>()
        if (source != null) {
            hosts.forEach { host ->
                // 配置入口也验证域名；这里仅做签名/路径约束，不假装验证节点可达性。
                if (CdnAddressPolicy.canHostServe(host, source)) {
                    ordered += CustomCdnProcessor.replaceHost(source, host)
                }
            }
        }
        // 保留每条原始 URL 自己的路径、签名和顺序，不用 source 重建原生备源。
        ordered.addAll(fresh)
        ordered.addAll(originals)
        val urls = ordered.toList()
        return Plan(urls.first(), urls.drop(1))
    }

    private fun canSwapHost(url: String): Boolean {
        val host = CustomCdnProcessor.hostOf(url)
        if (listOf("bilivideo.com", "bilivideo.cn", "bilivideo.net").none {
                host == it || host.endsWith(".$it")
            }) return false
        // 先隔离查询串，不能把查询参数中的 /upgcxcode/ 当作媒体路径。
        val location = url.substringBefore('?').substringBefore('#')
        val pathStart = location.indexOf('/', location.indexOf("://") + 3)
        if (pathStart < 0) return false
        val path = location.substring(pathStart)
        return path.startsWith("/upgcxcode/") &&
            CdnAddressPolicy.signatureFamilyOf(url) == CdnAddressPolicy.SignatureFamily.Upos
    }
}
