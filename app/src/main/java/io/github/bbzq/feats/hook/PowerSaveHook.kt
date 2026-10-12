package io.github.bbzq.feats.hook

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.View
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.methodOrNull

/**
 * 播放与后台的功耗治理。
 *
 * 这些点全部来自实测采样（simpleperf 线程栈 + 交叉引用），每一项都能指出它在为谁省电：
 *
 * - 后台自杀：`AppStateTracker.c` 在退后台 3 秒后检查堆占用，超过服务端下发的阈值就调度
 *   `RestartJobService` 然后 `System.exit(233)`，等于计划内冷启动。让这个方法不执行即可，
 *   内存回收交还系统 LMK。
 * - 预热服务族：广告 WebView 池（`AdWebViewPreloadService`，池大小 2）、通用 WebView 池
 *   （`WebViewPreloadService`，池大小 5）、商城混合运行时预热（`PreheatService`）、
 *   `MallPreloadService`、保活用的 `SmallAppService`、空壳的 `WebInitService`。它们都只是
 *   把首开变快，拦掉后广告页、专栏页仍然按需创建 WebView。在 `ContextWrapper.startService`
 *   上按组件类名过滤，可以一并避免这些服务所在的进程被拉起。
 * - 启动续传：主页 `StartupFragmentV2` 的启动任务会向下载服务投递
 *   `AutoStartAllTaskIfNeedAction`，自动恢复所有未完成下载。`Ue1.a` 是它的投递入口，
 *   过滤掉这个动作即可，手动进下载页续传不受影响。
 * - 应用列表上传：启动任务里按 `main.risk_control_app_upload_gap`（默认 24 小时）调用
 *   `tv.danmaku.bili.utils.list.b` 上传已安装应用列表，调用方丢弃返回值。
 * - 内推长连接：`BbcClientManager` 启动应用内推送的 TCP 长连接，失败时会走节点轮询重试
 *   （`loop message error, try next node`），是典型的后台耗电源。锁屏通知走厂商通道，不受影响。
 * - 装饰动画：CTC 头像挂件的涟漪动画通过一个回调转发器让整棵 View 树每帧失效，暂停视频后
 *   仍在跑；列表里直播卡片上的徽章图标（布局 id `live_badge_lottie`）由 Lottie 无限播放。
 *   前者吞掉回调转发，后者按视图 id 拦下 `playAnimation`，两者都只让动画定格，图标仍在。
 * - 播放日志：`AbrParamsInterface.GetDdSwitch` 是所有 `ijkplayer.*` 实验开关的统一入口，
 *   其中吞吐日志在播放期间持续记录网络细节。
 *
 * 编码与 P2P 不在这里：它们已由 [io.github.bbzq.feats.hook.mbgeb.CodecSelection] 与
 * [io.github.bbzq.feats.hook.mbgeb.P2pDisableHook] 处理，后者按本机硬解能力筛选，比一律禁用更准。
 */
class PowerSaveHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        // 预热服务既可能由主进程拉起，也可能由 :web 自己拉起（广告 WebView 池的 web 变体），
        // 所以服务过滤在两者都装；其余各点只存在于主进程。
        val isMainProcess = env.processName == env.packageName
        val isWebProcess = env.processName.endsWith(":web")
        if (!isMainProcess && !isWebProcess) return
        if (!ModuleSettings.isPowerSaveEnabled(prefs)) {
            log("startHook: PowerSave disabled")
            return
        }

        var installed = 0
        installed += installPreloadServiceFilter()
        if (!isMainProcess) return
        installed += installSuicideBlock()
        installed += installStartupDownloadFilter()
        installed += installAppListUploadBlock()
        installed += installInnerPushBlock()
        installed += installDecorationFreeze()
        installed += installPlaybackLogFilter()

        if (installed == 0) {
            log("startHook: PowerSave no hook point found")
        } else {
            log("startHook: PowerSave installed $installed hook point(s)")
        }
    }

    /** 退后台的高内存自杀，见 `AppStateTracker.c(AppStateTracker, Context)`。 */
    private fun installSuicideBlock(): Int {
        val tracker = classLoader.findClassOrNull(APP_STATE_TRACKER) ?: return 0
        val method = tracker.methodOrNull("c", tracker, Context::class.java) ?: return 0
        env.hookBefore(method) { param -> param.result = null }
        return 1
    }

    /** 在调用侧拦下预热与预载服务的启动，连它们所在的进程一起避免。 */
    private fun installPreloadServiceFilter(): Int {
        val wrapper = classLoader.findClassOrNull("android.content.ContextWrapper") ?: return 0
        val method = wrapper.methodOrNull("startService", Intent::class.java) ?: return 0
        env.hookBefore(method) { param ->
            val intent = param.args.getOrNull(0) as? Intent ?: return@hookBefore
            val className = intent.component?.className ?: return@hookBefore
            if (className in PRELOAD_SERVICES) {
                param.result = null
            }
        }
        return 1
    }

    /** 启动任务里的自动续传动作，见 `Ue1.a.a` 与 `Ue1.a.b` 的 `ServiceCustomizableAction` 数组。 */
    private fun installStartupDownloadFilter(): Int {
        val dispatcher = classLoader.findClassOrNull(STARTUP_TASK_DISPATCHER) ?: return 0
        var installed = 0
        dispatcher.allMethods()
            .filter { method -> method.name in STARTUP_TASK_METHODS && method.parameterCount == 2 }
            .forEach { method ->
                env.hookBefore(method) { param ->
                    val actions = param.args.getOrNull(1) as? Array<*> ?: return@hookBefore
                    if (actions.any { it?.javaClass?.name == AUTO_START_ALL_TASK_ACTION }) {
                        param.result = null
                    }
                }
                installed++
            }
        return installed
    }

    /** 每日一次的已安装应用列表上传。 */
    private fun installAppListUploadBlock(): Int {
        val uploader = classLoader.findClassOrNull(APP_LIST_UPLOADER) ?: return 0
        val method = uploader.methodOrNull("call") ?: return 0
        env.hookBefore(method) { param -> param.result = null }
        return 1
    }

    /** 应用内推送的 TCP 长连接。 */
    private fun installInnerPushBlock(): Int {
        val manager = classLoader.findClassOrNull(INNER_PUSH_MANAGER) ?: return 0
        var installed = 0
        manager.methodOrNull("a", Context::class.java)?.let { method ->
            env.hookBefore(method) { param -> param.result = null }
            installed++
        }
        classLoader.findClassOrNull("$INNER_PUSH_MANAGER\$a")
            ?.let { callback -> manager.methodOrNull("d", Context::class.java, callback) }
            ?.let { method ->
                env.hookBefore(method) { param -> param.result = null }
                installed++
            }
        return installed
    }

    /** 头像挂件的失效转发与直播徽章的 Lottie 播放。 */
    private fun installDecorationFreeze(): Int {
        var installed = 0

        classLoader.findClassOrNull(CTC_DECORATION_CALLBACK)
            ?.methodOrNull("invalidateDrawable", Drawable::class.java)
            ?.let { method ->
                env.hookBefore(method) { param -> param.result = null }
                installed++
            }

        val badgeId = resolveLiveBadgeLottieId()
        if (badgeId != 0) {
            val lottieDrawable = classLoader.findClassOrNull(LOTTIE_DRAWABLE)
            LOTTIE_PLAY_METHODS.forEach { name ->
                lottieDrawable?.methodOrNull(name)?.let { method ->
                    env.hookBefore(method) { param ->
                        val view = (param.thisObject as? Drawable)?.callback as? View
                        if (view?.id == badgeId) param.result = null
                    }
                    installed++
                }
            }
        }
        return installed
    }

    /** 播放期间的网络日志开关，见 `AbrParamsInterface.GetDdSwitch`。 */
    private fun installPlaybackLogFilter(): Int {
        val params = classLoader.findClassOrNull(ABR_PARAMS) ?: return 0
        val method = params.methodOrNull(GET_DD_SWITCH, String::class.java, java.lang.Boolean.TYPE)
            ?: return 0
        env.hookBefore(method) { param ->
            val key = param.args.getOrNull(0) as? String ?: return@hookBefore
            if (key in PLAYER_LOG_KEYS) param.result = false
        }
        return 1
    }

    /** 按资源名解析徽章图标的 id，避免把某个版本的 id 写死在代码里。 */
    private fun resolveLiveBadgeLottieId(): Int = runCatching {
        env.hostContext.resources.getIdentifier(LIVE_BADGE_LOTTIE_ID_NAME, "id", env.packageName)
    }.getOrDefault(0)

    private companion object {
        const val APP_STATE_TRACKER = "com.bilibili.gripper.memmonitor.AppStateTracker"
        const val STARTUP_TASK_DISPATCHER = "Ue1.a"
        const val APP_LIST_UPLOADER = "tv.danmaku.bili.utils.list.b"
        const val INNER_PUSH_MANAGER = "com.bilibili.comm.bbc.service.BbcClientManager"
        const val CTC_DECORATION_CALLBACK = "com.bilibili.ctc.common.avatar.plugin.live.drawable.a"
        const val LOTTIE_DRAWABLE = "com.airbnb.lottie.LottieDrawable"
        const val ABR_PARAMS = "tv.danmaku.ijk.media.player.AbrParamsInterface"

        const val AUTO_START_ALL_TASK_ACTION =
            "tv.danmaku.bili.services.videodownload.action.AutoStartAllTaskIfNeedAction"
        const val GET_DD_SWITCH = "GetDdSwitch"
        const val LIVE_BADGE_LOTTIE_ID_NAME = "live_badge_lottie"

        val STARTUP_TASK_METHODS = listOf("a", "b")
        val LOTTIE_PLAY_METHODS = listOf("playAnimation", "resumeAnimation")

        /** 播放期间只关日志类的实验开关。 */
        val PLAYER_LOG_KEYS = setOf(
            "ijkplayer.enable-throughput-log",
        )

        /** 只做首开提速、没有功能依赖的服务。 */
        val PRELOAD_SERVICES = setOf(
            "com.bilibili.ad.web.preload.AdWebViewPreloadService",
            "com.bilibili.ad.web.preload.AdWebViewPreloadService4MainProcess",
            "com.bilibili.ad.web.preload.AdWebViewPreloadService4WebProcess",
            "com.bilibili.preload.WebViewPreloadService",
            "com.bilibili.opd.app.bizcommon.hybridruntime.preload.MallPreloadService",
            "com.bilibili.opd.app.bizcommon.hybridruntime.neul.PreheatService",
            "com.bilibili.lib.fasthybrid.SmallAppService",
            "tv.danmaku.bili.ui.webview.service.WebInitService",
        )
    }
}
