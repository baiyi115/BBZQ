package io.github.bbzq.feats.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.allFieldsList
import io.github.bbzq.feats.allMethodsList
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

object CustomCdnProcessor {
    private val PC_DN_REGEX = Regex(
        """^(?:upos-)?(p[0-9]*|m[0-9]*|sz|hz|bj|sh|cd|cq|fs|gz|wh|xa|tj|zz|nn)-[a-zA-Z0-9]+-(?:bilivideo|mcdn|szbdyd|p2p)""",
        RegexOption.IGNORE_CASE,
    )
    private val PCDN_SINK_DOMAINS = listOf("nexusedgeio.com", "ahdohpiechei.com")
    private val IPV4_HOST_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    /** 正/负查找都按宿主类缓存；每个 DASH 条目不再扫描 methods/fields。 */
    private class Accessors(clazz: Class<*>) {
        val methods = clazz.allMethodsList().filterNot { Modifier.isStatic(it.modifiers) }
        val getters = methods.filter { it.parameterCount == 0 }.associateBy { it.name }
        val fields = clazz.allFieldsList().filterNot { Modifier.isStatic(it.modifiers) }
        fun get(target: Any, name: String): Any? =
            getters[name]?.let { runCatching { it.invoke(target) }.getOrNull() }
        private val mutators = methods.filter { it.parameterCount == 1 }.groupBy { it.name }
        fun mutator(name: String, value: Any): Method? = mutators[name]?.firstOrNull {
            it.parameterTypes[0].isInstance(value)
        }
    }
    private val accessors = ConcurrentHashMap<Class<*>, Accessors>()
    private fun access(target: Any): Accessors = accessors.getOrPut(target.javaClass) { Accessors(target.javaClass) }

    fun rewriteResponse(
        response: Any?, prefs: SharedPreferences, isCellular: Boolean = false,
        log: (String, Throwable?) -> Unit = { _, _ -> },
    ) {
        if (response == null) return
        val hosts = ModuleSettings.getActiveCdnHosts(prefs, isCellular)
        if (hosts.isEmpty()) return
        runCatching {
            rewriteResponse(response, hosts, ModuleSettings.isCdnAudioIndependent(prefs))
        }.onFailure { log("CustomCdnProcessor: response rewrite failed", it) }
    }

    /** 同一响应内按对象身份去重，避免别名 getter 或重复容器反复处理。 */
    internal fun rewriteResponse(
        response: Any, hosts: List<String>, audioIndependent: Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ) {
        if (hosts.isEmpty()) return
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(target: Any, depth: Int, mediaItem: Boolean) {
            if (depth > 8 || !seen.add(target)) return
            if (target is Iterable<*>) {
                target.forEach { if (it != null) visit(it, depth + 1, mediaItem) }
                return
            }
            val a = access(target)
            if (mediaItem) rewriteUrlItem(target, a, hosts, nowSeconds)
            VIDEO_GETTERS.forEach { name -> a.get(target, name)?.let { visit(it, depth + 1, name in MEDIA_GETTERS) } }
            if (!audioIndependent) {
                AUDIO_GETTERS.forEach { name -> a.get(target, name)?.let { visit(it, depth + 1, true) } }
            }
        }
        visit(response, 0, false)
    }

    private fun rewriteUrlItem(item: Any, a: Accessors, hosts: List<String>, nowSeconds: Long) {
        val getter = when {
            a.getters["getBaseUrl"]?.returnType == String::class.java -> "getBaseUrl"
            a.getters["getUrl"]?.returnType == String::class.java -> "getUrl"
            else -> null
        }
        val base = (getter?.let { a.get(item, it) } as? String)
            ?: a.fields.asSequence().filter { it.type == String::class.java }
                .mapNotNull { read(it, item) as? String }.firstOrNull(::isHttpUrl)
            ?: return
        val rawBackups = a.get(item, "getBackupUrlList") ?: a.get(item, "getBackupUrl")
            ?: a.fields.asSequence().mapNotNull { read(it, item) as? Iterable<*> }
                .firstOrNull { list -> list.any { it is String && isHttpUrl(it) } && list.all { it is String } }
        val backups = (rawBackups as? Iterable<*>)?.filterIsInstance<String>().orEmpty()
        val plan = PlaybackCdnPlan.plan(base, backups, hosts, nowSeconds) ?: return
        if (plan.base == base && plan.backups == backups) return
        val setter = when (getter) { "getBaseUrl" -> "setBaseUrl"; "getUrl" -> "setUrl"; else -> null }
        if (plan.base != base && !setBase(item, a, setter, base, plan.base)) return
        if (plan.backups != backups && !replaceBackups(item, a, rawBackups, plan.backups)) {
            // 宿主版本不支持更新备源时，不留下“换了主源却丢掉原主源”的半次改写。
            if (plan.base != base) setBase(item, a, setter, plan.base, base)
        }
    }

    private fun setBase(item: Any, a: Accessors, setter: String?, old: String, value: String): Boolean {
        val method = setter?.let { a.mutator(it, value) }
        if (method != null && runCatching { method.invoke(item, value) }.isSuccess) return true
        return replaceField(item, a, old, value)
    }

    private fun replaceBackups(item: Any, a: Accessors, original: Any?, urls: List<String>): Boolean {
        // 能直接赋值时先替换字段，不清空原集合；失败的 setter 不应破坏原生回退。
        if (replaceField(item, a, original, urls)) return true
        val clear = a.getters["clearBackupUrl"] ?: a.getters["clearBackupUrlList"]
        val add = a.mutator("addAllBackupUrl", urls) ?: a.mutator("addAllBackupUrlList", urls)
        if (clear == null || add == null) return false
        val old = (original as? Iterable<*>)?.filterIsInstance<String>().orEmpty()
        if (runCatching { clear.invoke(item); add.invoke(item, urls) }.isSuccess) return true
        runCatching { clear.invoke(item); add.invoke(item, old) }
        return false
    }

    private fun replaceField(item: Any, a: Accessors, original: Any?, value: Any): Boolean {
        if (original == null) return false
        val field = a.fields.firstOrNull {
            it.type.isInstance(value) && read(it, item).let { stored ->
                stored === original || (original is String && stored == original)
            }
        } ?: return false
        return runCatching { field.set(item, value) }.isSuccess
    }
    private fun read(field: Field, target: Any): Any? = runCatching { field.get(target) }.getOrNull()
    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")

    private val VIDEO_GETTERS = listOf(
        "getVideoInfo", "getVodInfo", "getViewInfo", "getStreamListList", "getStreamList",
        "getDashVideo", "getMultiDashVideo", "getSegmentVideo", "getContent", "getValue",
        "getDashVideosList", "getDashVideos", "getSegmentList", "getSegment",
    )
    private val MEDIA_GETTERS = setOf(
        "getDashVideo", "getMultiDashVideo", "getSegmentVideo", "getValue",
        "getDashVideosList", "getDashVideos", "getSegmentList", "getSegment",
    )
    private val AUDIO_GETTERS = listOf("getDashAudioList", "getDashAudioListList", "getAudioDashVideoList", "getDashAudio")

    fun hostOf(url: String): String {
        val afterScheme = when {
            url.contains("://") -> url.substringAfter("://")
            url.startsWith("//") -> url.substring(2)
            else -> url
        }
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    }

    private fun hostMatchesDomain(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    fun isPCdn(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        return host.contains("mcdn") || host.contains("szbdyd") || host.contains("p2p") ||
            host.contains("mirror14b") || host.substringBefore('.').contains("302") ||
            PCDN_SINK_DOMAINS.any { hostMatchesDomain(host, it) } || IPV4_HOST_REGEX.matches(host) ||
            host.startsWith("p-") || host.startsWith("m-") || PC_DN_REGEX.containsMatchIn(host)
    }

    fun replaceHost(url: String, newHost: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart == -1) url.substring(0, schemeEnd + 3) + newHost
        else url.substring(0, schemeEnd + 3) + newHost + url.substring(pathStart)
    }
}
