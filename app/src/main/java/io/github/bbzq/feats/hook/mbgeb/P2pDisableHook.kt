package io.github.bbzq.feats.hook.mbgeb

import android.content.Context
import android.os.Bundle
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.methodOrNull

/**
 * 关闭 IJK 播放器的 P2P / PCDN。
 *
 * App 自带这个开关：`P2P.parseConfig(Context, Bundle)` 的第一件事就是读
 * `ijkplayer.p2p-disable-whitelist`，为真时直接 `return null`。返回 null 之后：
 *
 * - 构造函数把 `mConfigs` 置 null，`mEnable` 保持 false（有显式判空）
 * - `mEnable` 全仓只有一个读取点 `P2P.getInstance()`，它在 false 时返回 null
 * - `applyNewConfig` 的两处 null 敏感调用（`isConfigEqual` / `notifyConfigChange`）
 *   不会被触发：`mApplyConfigs` 恒为 null，`isConfigEqual(null, null)` 判定相等
 *
 * 所以这里不是"制造"一个被禁用的状态，而是让 App 既有的一条分支成立。相比
 * 去 mock P2P SDK 的类，这条路不依赖除类名以外的任何符号。
 *
 * `parseConfig` 是 private static，只有两个调用方（构造函数与 `applyNewConfig`），
 * 都在 `P2P` 内部，因此一个 hook 点覆盖全部入口。
 */
class P2pDisableHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (!ModuleSettings.isP2pDisableEnabled(prefs)) return

        // 这是 ijkplayer 的类，不在混淆范围，跨版本稳定
        val p2pClass = runCatching { classLoader.loadClass(P2P_CLASS) }.getOrNull()
        if (p2pClass == null) {
            log("P2pDisable: $P2P_CLASS not found, skipped")
            return
        }

        val parseConfig = p2pClass.methodOrNull("parseConfig", Context::class.java, Bundle::class.java)
        if (parseConfig == null) {
            log("P2pDisable: parseConfig not found, skipped")
            return
        }

        // hookAfter 而非 replace：让原方法照常执行（保留其内部副作用），只丢弃结果。
        // parseConfig 只在创建播放器时调一次，这点开销可以忽略。
        env.hookAfter(parseConfig) { param ->
            param.result = null
        }
        log("P2pDisable: hooked ${P2P_CLASS}.parseConfig")
    }

    private companion object {
        private const val P2P_CLASS = "tv.danmaku.ijk.media.player.P2P"
    }
}
