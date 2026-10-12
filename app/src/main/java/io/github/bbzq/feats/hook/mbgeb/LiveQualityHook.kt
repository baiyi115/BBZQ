package io.github.bbzq.feats.hook.mbgeb

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.MethodHookParam
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.methodOrNull

/**
 * 直播强制原画：把直播 playurl 请求的 `qn` 抬到原画档位。
 *
 * 为什么不改写流地址：Web 端只能对 URL 做正则（剥画质后缀、剥 hevc 变体、换镜像），
 * 而移动端可以直接改**请求参数**：直播 playurl 请求带 `qn`
 * （`RoomApiService.getRoomPlayInfoV2` 的 `@Query("qn")`），把档位抬上去，服务端就返回
 * 原画流。少一层 URL 解析，也没有后缀格式变化导致正则失效的风险。
 *
 * 为什么不 hook 那个接口方法：它是 Retrofit 的抽象方法，实际调用由动态代理分发，
 * 挂在抽象方法上的 hook 不会被执行。所以落在 `okhttp3.Request$Builder.build()`，
 * 再按路径后缀精确匹配，这样无论 app 走的是哪一份 RoomApiService 声明都能覆盖。
 *
 * `build()` 是全局热路径，所以判断顺序按代价从低到高：先取路径字符串做一次
 * `endsWith` 比较，只有直播 playurl 才继续做反射调用。
 */
class LiveQualityHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (!ModuleSettings.isLiveOriginalQualityEnabled(prefs)) return

        val builderClass = runCatching { classLoader.loadClass(REQUEST_BUILDER_CLASS) }.getOrNull()
        if (builderClass == null) {
            log("LiveQuality: $REQUEST_BUILDER_CLASS not found, skipped")
            return
        }
        val build = builderClass.methodOrNull("build")
        if (build == null) {
            log("LiveQuality: Request.Builder.build not found, skipped")
            return
        }

        env.hookAfter(build) { param -> raiseToOriginalQuality(param) }
        log("LiveQuality: hooked $REQUEST_BUILDER_CLASS.build")
    }

    private fun raiseToOriginalQuality(param: MethodHookParam) {
        runCatching {
            val request = param.result ?: return
            val url = request.callMethod("url") ?: return
            val path = url.callMethod("encodedPath") as? String ?: return
            if (!LiveQualityPolicy.isLivePlayInfoPath(path)) return

            val current = (url.callMethod("queryParameter", QN) as? String)?.toIntOrNull()
            val target = LiveQualityPolicy.raiseToOriginal(current) ?: return
            val rewritten = withQueryParameter(request, url, QN, target.toString()) ?: return
            param.result = rewritten
            log("LiveQuality: qn ${current ?: "unset"} -> $target")
        }.onFailure {
            log("LiveQuality: failed to raise live quality", it)
        }
    }

    /** 复制这个请求，只换掉一个查询参数。任何一步失败都返回 `null`，保持原请求。 */
    private fun withQueryParameter(request: Any, url: Any, name: String, value: String): Any? {
        val urlBuilder = url.callMethod("newBuilder") ?: return null
        urlBuilder.callMethod("setQueryParameter", name, value)
        val newUrl = urlBuilder.callMethod("build") ?: return null
        val requestBuilder = request.callMethod("newBuilder") ?: return null
        requestBuilder.callMethod("url", newUrl)
        return requestBuilder.callMethod("build")
    }

    private companion object {
        private const val REQUEST_BUILDER_CLASS = "okhttp3.Request\$Builder"
        private const val QN = "qn"
    }
}
