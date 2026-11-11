package io.github.bbzq.feats.hook

import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethodsList
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import java.util.Collections
import java.util.WeakHashMap

/** 只在播放地址响应边界改写；媒体正文仍由宿主播放器直连。 */
class CustomCdnHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val hookedHandlers = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    override fun startHook() {
        val methods = env.symbols?.tryFreeQuality?.restore(classLoader)?.playViewMethods.orEmpty()
        var installed = 0
        methods.forEach { method ->
            runCatching {
                // 异步 PlayView 通过 onNext 交付，不能依赖画质增强开关顺带处理 CDN。
                env.hookBefore(method) { param ->
                    param.args.getOrNull(1)?.let { hookHandler(it.javaClass) }
                }
                env.hookAfter(method) { param -> rewrite(param.result) }
                installed++
            }.onFailure { log("CustomCdn: failed to hook ${method.declaringClass.name}.${method.name}", it) }
        }
        if (installed == 0) log("CustomCdn: no compatible PlayView method found")
        else log("CustomCdn: hooked $installed response method(s)")
    }

    private fun hookHandler(clazz: Class<*>) = synchronized(hookedHandlers) {
        if (clazz in hookedHandlers) return@synchronized
        val methods = clazz.allMethodsList().filter { it.name == "onNext" && it.parameterCount == 1 }
        if (methods.isEmpty()) return@synchronized
        var complete = true
        methods.forEach { method ->
            val installed = env.hookBefore(method) { param -> rewrite(param.args.firstOrNull()) }
            complete = installed && complete
        }
        if (complete) hookedHandlers.add(clazz)
    }

    private fun rewrite(response: Any?) {
        if (response == null) return
        val isCellular = env.hostContext?.let { NetworkTypeDetector.isCellular(it) } ?: false
        CustomCdnProcessor.rewriteResponse(response, prefs, isCellular, ::log)
    }
}
