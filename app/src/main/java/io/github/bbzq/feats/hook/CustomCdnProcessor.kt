package io.github.bbzq.feats.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.callMethod
import java.lang.reflect.Modifier

object CustomCdnProcessor {

    private val PC_DN_REGEX = Regex(
        """^(?:upos-)?(p[0-9]*|m[0-9]*|sz|hz|bj|sh|cd|cq|fs|gz|wh|xa|tj|zz|nn)-[a-zA-Z0-9]+-(?:bilivideo|mcdn|szbdyd|p2p)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 子域里含此标记的是 HTTP 302 型 PCDN，例如 `upos-sz-302ppio.bilivideo.com`、
     * `upos-sz-302kodo.bilivideo.com`。这类主机名符合 upos 镜像的形状，但对不上
     * [PC_DN_REGEX]（它要求 `a-b-c` 三段），所以需要单独一条结构规则。
     */
    private const val PCDN_SUBDOMAIN_MARKER = "302"

    /** 302 型 PCDN 的真实落点。它们不出现在 playinfo 里，只作为 302 目标出现。 */
    private val PCDN_SINK_DOMAINS = listOf("nexusedgeio.com", "ahdohpiechei.com")

    /**
     * 伪装成 upos 镜像的 PCDN 主机：走 upgcxcode 路径，但证书签的是 `*.bilivideo.cn`，
     * 说明实际由 PCDN 承载。
     */
    private const val PCDN_MIRROR_HOST_MARKER = "mirror14b"

    /** 裸 IPv4 主机：PCDN 节点常以 IP 直出，例如 `1.2.3.4:8080/v1/resource`。 */
    private val IPV4_HOST_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    fun rewriteResponse(
        response: Any?,
        prefs: SharedPreferences,
        isCellular: Boolean = false,
        log: (String, Throwable?) -> Unit = { _, _ -> },
    ) {
        if (response == null) return
        val hosts = ModuleSettings.getActiveCdnHosts(prefs, isCellular)
        if (hosts.isEmpty()) return
        val audioIndependent = ModuleSettings.isCdnAudioIndependent(prefs)

        runCatching {
            sequenceOf(
                response.callMethod("getVideoInfo"),
                response.callMethod("getVodInfo"),
                response.callMethod("getViewInfo"),
                response,
            ).filterNotNull().distinct().forEach { rewriteVideoInfo(it, hosts, audioIndependent) }
        }.onFailure { log("CustomCdnProcessor: response rewrite failed", it) }
    }

    private fun rewriteVideoInfo(videoInfo: Any, hosts: List<String>, audioIndependent: Boolean) {
        val streams = videoInfo.callMethod("getStreamListList")
            ?: videoInfo.callMethod("getStreamList")
        (streams as? Iterable<*>)?.forEach { stream ->
            stream ?: return@forEach
            listOf("getDashVideo", "getMultiDashVideo", "getSegmentVideo")
                .forEach { getter -> stream.callMethod(getter)?.let { rewriteVideoContent(it, hosts) } }
            stream.callMethod("getContent")?.callMethod("getValue")
                ?.let { rewriteVideoContent(it, hosts) }
            if (!audioIndependent) rewriteAudioLists(stream, hosts)
        }
        if (!audioIndependent) rewriteAudioLists(videoInfo, hosts)
    }

    private fun rewriteVideoContent(content: Any, hosts: List<String>) {
        if (content.callMethod("getBaseUrl") is String || content.callMethod("getUrl") is String) {
            rewriteUrlItem(content, hosts)
        }
        val dashVideos = content.callMethod("getDashVideosList") ?: content.callMethod("getDashVideos")
        (dashVideos as? Iterable<*>)
            ?.forEach { it?.let { item -> rewriteUrlItem(item, hosts) } }
        val segments = content.callMethod("getSegmentList") ?: content.callMethod("getSegment")
        (segments as? Iterable<*>)
            ?.forEach { it?.let { item -> rewriteUrlItem(item, hosts) } }
    }

    private fun rewriteAudioLists(owner: Any, hosts: List<String>) {
        listOf("getDashAudioList", "getDashAudioListList", "getAudioDashVideoList", "getDashAudio")
            .forEach { getter ->
                when (val result = owner.callMethod(getter)) {
                    is Iterable<*> -> result.forEach { it?.let { item -> rewriteUrlItem(item, hosts) } }
                    else -> result?.let { rewriteUrlItem(it, hosts) }
                }
            }
    }

    private fun rewriteUrlItem(item: Any, hosts: List<String>) {
        val baseGetter = when {
            item.callMethod("getBaseUrl") is String -> "getBaseUrl"
            item.callMethod("getUrl") is String -> "getUrl"
            else -> null
        }
        val baseSetter = when (baseGetter) {
            "getBaseUrl" -> "setBaseUrl"
            "getUrl" -> "setUrl"
            else -> null
        }
        val base = (baseGetter?.let { item.callMethod(it) } as? String)
            ?: findHttpStringFieldValue(item)
            ?: return
        if (base.isBlank()) return
        val rawBackups = item.callMethod("getBackupUrlList")
            ?: item.callMethod("getBackupUrl")
            ?: findUrlListFieldValue(item)
        val backups = (rawBackups as? Iterable<*>)
            ?.filterIsInstance<String>().orEmpty()
        val source = listOf(base).plus(backups).firstOrNull { !isPCdn(it) } ?: return

        val rewrittenBase = replaceHost(source, hosts[0])
        val rewrittenBackups = buildList {
            hosts.drop(1).take(4).forEach { h -> add(replaceHost(source, h)) }
            add(source)
        }.filter { it != rewrittenBase }.distinct()

        if (baseSetter == null || !invokeOneArg(item, baseSetter, rewrittenBase)) {
            replaceStoredValue(item, base, rewrittenBase)
        }
        clearAndAddBackups(item, rawBackups, rewrittenBackups)
    }

    private fun findHttpStringFieldValue(target: Any): String? =
        target.javaClass.allFields().mapNotNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.type != String::class.java) null
            else runCatching { field.get(target) as? String }.getOrNull()
        }.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

    private fun findUrlListFieldValue(target: Any): Iterable<*>? =
        target.javaClass.allFields().mapNotNull { field ->
            if (Modifier.isStatic(field.modifiers)) null
            else runCatching { field.get(target) as? Iterable<*> }.getOrNull()
        }.firstOrNull { values ->
            values.all { it is String } && values.any {
                val value = it as String
                value.startsWith("http://") || value.startsWith("https://")
            }
        }

    private fun clearAndAddBackups(item: Any, original: Any?, urls: List<String>) {
        val cleared = invokeNoArg(item, "clearBackupUrl") || invokeNoArg(item, "clearBackupUrlList")
        val added = urls.isEmpty() ||
            invokeOneArg(item, "addAllBackupUrl", urls) ||
            invokeOneArg(item, "addAllBackupUrlList", urls)
        if (!cleared || !added) {
            replaceStoredValue(item, original, urls)
        }
    }

    private fun replaceStoredValue(target: Any, original: Any?, replacement: Any): Boolean {
        if (original == null) return false
        return target.javaClass.allFields().firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && runCatching {
                val value = field.get(target)
                value === original || (original is String && value == original)
            }.getOrDefault(false)
        }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(target, replacement)
                true
            }.getOrDefault(false)
        } ?: false
    }

    private fun invokeNoArg(target: Any, methodName: String): Boolean =
        target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target)
                true
            }.getOrDefault(false)
        } ?: false

    private fun invokeOneArg(target: Any, methodName: String, arg: Any): Boolean =
        target.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(arg.javaClass)
        }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target, arg)
                true
            }.getOrDefault(false)
        } ?: false

    /**
     * 从 URL 里取主机名：小写、去端口、去协议。无法解析时返回空串。
     *
     * 只用字符串操作而非 `android.net.Uri`，这样它可以在纯 JVM 单测里直接跑。
     */
    fun hostOf(url: String): String {
        val afterScheme = when {
            url.contains("://") -> url.substringAfter("://")
            url.startsWith("//") -> url.substring(2)
            else -> url
        }
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
            .lowercase()
    }

    private fun hostMatchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun isPCdn(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        val label = host.substringBefore('.')
        return host.contains("mcdn") ||
            host.contains("szbdyd") ||
            host.contains("p2p") ||
            host.contains(PCDN_MIRROR_HOST_MARKER) ||
            label.contains(PCDN_SUBDOMAIN_MARKER) ||
            PCDN_SINK_DOMAINS.any { hostMatchesDomain(host, it) } ||
            IPV4_HOST_REGEX.matches(host) ||
            host.startsWith("p-") ||
            host.startsWith("m-") ||
            PC_DN_REGEX.containsMatchIn(host)
    }

    fun replaceHost(url: String, newHost: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart == -1) {
            url.substring(0, schemeEnd + 3) + newHost
        } else {
            url.substring(0, schemeEnd + 3) + newHost + url.substring(pathStart)
        }
    }
}
