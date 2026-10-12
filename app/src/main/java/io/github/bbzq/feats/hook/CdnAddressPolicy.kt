package io.github.bbzq.feats.hook

/**
 * CDN 地址的可用性策略：签名家族、失效时间、以及"这台主机能不能承载这个地址"。
 *
 * 移植自 Make Bilibili Great Than Ever Before 的 `signatureFamilyOf` /
 * `signatureDeadlineOf` / `CandidateTier.Foreign` / `DEADLINE_MARGIN_MS`。
 *
 * 背景：upos 与 Akamai 用的是两套签名。upos 地址带 `upsig`，Akamai 地址带 `hdnts`
 * 令牌。Akamai 只认自己的 `hdnts`，把一个 upos 签名的地址换到 `*.akamaized.net`
 * 上会被 403 —— 而 `upos-hz-mirrorakam.akamaized.net` 恰好长得像合法的 upos 镜像，
 * 所以它能被选进 CDN 列表，然后稳定地失败。
 *
 * 全部用字符串操作而非 `android.net.Uri`，以便纯 JVM 单测可直接覆盖。
 */
object CdnAddressPolicy {

    /** Akamai 的落点域名后缀。 */
    private const val AKAMAI_HOST_SUFFIX = ".akamaized.net"

    /**
     * Akamai 令牌 `hdnts` 里的过期时间，形如
     * `hdnts=exp=1730000000~acl=/...~hmac=...`。它可能排在其它 `~` 分隔字段之前或之后。
     */
    private val AKAMAI_TOKEN_EXPIRY_REGEX = Regex("""(?:^|~)exp=(\d+)""")

    /**
     * 签名至少要还剩这么久才值得使用。低于这个余量时，请求很可能在到达前就过期。
     */
    const val DEADLINE_MARGIN_SECONDS = 60L

    /** 签名家族。两套签名互不通用。 */
    enum class SignatureFamily {
        /** upos 签名：带 `upsig`，或既无 `hdnts` 也不在 Akamai 主机上。 */
        Upos,

        /** Akamai 令牌签名：带 `hdnts`，或 Akamai 主机上无 `upsig` 的地址。 */
        Akamai,
    }

    fun isAkamaiHost(host: String): Boolean = host.lowercase().endsWith(AKAMAI_HOST_SUFFIX)

    /**
     * 地址属于哪套签名。
     *
     * 判定顺序与上游一致：显式带 `hdnts` 即为 Akamai；否则只有在 Akamai 主机上、
     * 且没有 `upsig` 时才算 Akamai —— 因为 Akamai 主机上也可能是回源到 upos 的地址。
     */
    fun signatureFamilyOf(url: String): SignatureFamily {
        val hasHdntsToken = queryParam(url, "hdnts") != null
        if (hasHdntsToken) return SignatureFamily.Akamai
        val hasUposSignature = queryParam(url, "upsig") != null
        val onAkamai = isAkamaiHost(CustomCdnProcessor.hostOf(url))
        return if (onAkamai && !hasUposSignature) SignatureFamily.Akamai else SignatureFamily.Upos
    }

    /**
     * 这台主机能否承载这个地址。
     *
     * Akamai 只能承载 Akamai 签名的地址；其余主机放行（upos 签名搬到别的 upos 主机
     * 是 B 站 CDN 切换的常规做法）。反方向（Akamai 签名搬到 upos 主机）未经验证，
     * 这里选择放行，因为它不影响主流程 —— 改写目标默认是 upos 主机。
     */
    fun canHostServe(host: String, sourceUrl: String): Boolean {
        if (!isAkamaiHost(host)) return true
        return signatureFamilyOf(sourceUrl) == SignatureFamily.Akamai
    }

    /** 过滤掉承载不了 [sourceUrl] 的主机，并保持原有顺序。 */
    fun usableHosts(hosts: List<String>, sourceUrl: String): List<String> =
        hosts.filter { canHostServe(it, sourceUrl) }

    /**
     * 地址的失效时间（unix 秒）。`0` 表示未知。
     *
     * upos 用 `deadline`，Akamai 用 `hdnts` 里的 `exp=`。两者都存在时取更早的那个：
     * 任一先失效，地址就不可用了。
     */
    fun deadlineOf(url: String): Long {
        val deadline = unixSeconds(queryParam(url, "deadline"))
        val token = queryParam(url, "hdnts")
        val tokenExpiry = if (token == null) {
            0L
        } else {
            unixSeconds(AKAMAI_TOKEN_EXPIRY_REGEX.find(token)?.groupValues?.get(1))
        }
        if (deadline == 0L) return tokenExpiry
        if (tokenExpiry == 0L) return deadline
        return minOf(deadline, tokenExpiry)
    }

    /** 地址是否已经失效。 */
    fun isExpired(url: String, nowSeconds: Long = currentUnixSeconds()): Boolean {
        val deadline = deadlineOf(url)
        return deadline != 0L && deadline <= nowSeconds
    }

    /**
     * 地址是否即将失效（剩余不足 [marginSeconds]）。未知失效时间时返回 `false`。
     */
    fun isExpiringSoon(
        url: String,
        nowSeconds: Long = currentUnixSeconds(),
        marginSeconds: Long = DEADLINE_MARGIN_SECONDS,
    ): Boolean {
        val deadline = deadlineOf(url)
        return deadline != 0L && deadline - nowSeconds <= marginSeconds
    }

    /** 地址是否仍值得使用：未失效且余量充足。 */
    fun isUsable(
        url: String,
        nowSeconds: Long = currentUnixSeconds(),
        marginSeconds: Long = DEADLINE_MARGIN_SECONDS,
    ): Boolean = !isExpiringSoon(url, nowSeconds, marginSeconds)

    private fun currentUnixSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun unixSeconds(value: String?): Long =
        value?.toLongOrNull()?.takeIf { it > 0L } ?: 0L

    /**
     * 取查询参数。与 `Uri.getQueryParameter` 不同，这里不做百分号解码 ——
     * 用到的参数（`deadline` / `upsig` / `hdnts`）都不需要，而未编码的 `hdnts`
     * 值里含有 `=` 与 `~`，解码反而会破坏它。
     */
    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            if (pair.substring(0, separator) == name) return pair.substring(separator + 1)
        }
        return null
    }
}
