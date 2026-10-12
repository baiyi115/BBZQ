package io.github.bbzq.feats.hook.mbgeb

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethodsList
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 拦掉 B 站自研埋点 SDK（InfoEyes）的事件上报。
 *
 * App 的埋点走 `com.bilibili.lib.infoeyes`：`data.bilibili.com/vv/app` 与
 * `data.bilibili.com/log/mobile` 这两个上传器（`Zb0.b` / `ac0.a`）分别是它的
 * `infoeyes.b` / `infoeyes.n` 子类。在 SDK 的事件 API 上拦比按 URL 拦更合适：
 *
 * - **与底层 HTTP 栈无关。** B 站有两套 HTTP 栈：OkHttp，以及自研的 `ktor-ignet`
 *   （`com.bilibili.ktor.KtorClientKt.ktorEngineName()` 返回 `"ktor-ignet"`，
 *   即 Ktor 走的是自己的引擎而不是 OkHttp）。按 URL 在 OkHttp 层拦，会漏掉走
 *   ignet 的那一半，而且不报错 —— 只是静默无效。
 * - **事件在进入队列前就被丢掉**，连落盘、压缩、重试都省了，省的不只是流量。
 *
 * 明确不碰风控：`com.bilibili.gripper.riskcontrol.*`（`ExClimbCongLing` /
 * `ExClimbKunLun`）与 `kntr.base.utils.fingerprint.FingerprintReporter`（采集
 * 机型 / buvid 后 RSA 加密上报）是另一套东西。拦它有账号级风险，本模块不做。
 *
 * 副作用：拦掉后本机的播放行为不再计入 `vv/app` 统计。
 */
class TelemetryBlockHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (!ModuleSettings.isBlockTelemetryEnabled(prefs)) return

        val manager = runCatching { classLoader.loadClass(MANAGER_CLASS) }.getOrNull()
        if (manager == null) {
            log("TelemetryBlock: $MANAGER_CLASS not found, skipped")
            return
        }

        // 按名字挂上全部重载：SDK 有几个 report/report2 变体，逐个写签名容易在版本
        // 变化时漏掉，按名字覆盖更稳
        val candidates = manager.allMethodsList().filter { method ->
            method.name in BLOCKED_METHOD_NAMES && Modifier.isPublic(method.modifiers)
        }
        if (candidates.isEmpty()) {
            log("TelemetryBlock: no report entry point found, skipped")
            return
        }

        var hooked = 0
        candidates.forEach { method ->
            if (env.hookBefore(method) { param -> param.result = null }) hooked++
        }
        log("TelemetryBlock: hooked $hooked/${candidates.size} entry points: ${candidates.map(::signature)}")
    }

    private fun signature(method: Method): String =
        "${method.name}/${method.parameterCount}"

    private companion object {
        private const val MANAGER_CLASS = "com.bilibili.lib.infoeyes.InfoEyesManager"

        /**
         * 事件入口。`report` 与 `report2` 是实例方法，`feedEvent` 是静态的
         * （当前版本是个空实现，一并挂上以防将来被填上）。
         *
         * 不拦 `initialize`：那会让 SDK 根本不启动，而 App 别处可能依赖它已经初始化。
         * 只丢事件，影响面最小。
         */
        private val BLOCKED_METHOD_NAMES = setOf("report", "report2", "feedEvent")
    }
}
