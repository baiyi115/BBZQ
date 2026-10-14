package io.github.bbzq.feats.hook.mbgeb

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.media.MediaProxyServer

/**
 * 在宿主进程里启动本地媒体代理。
 *
 * 代理启动后，[io.github.bbzq.feats.hook.CustomCdnProcessor] 会把点播的媒体地址 base
 * 改写成指向它，播放器于是来读代理；代理按模块里配好的 CDN 节点并发取数。
 *
 * 只挂在 MAIN 进程：播放发生在这里，其他进程起了也没有请求进来。
 */
class MediaProxyHook(env: RoamingEnv) : BaseRoamingHook(env) {

    override fun startHook() {
        if (!ModuleSettings.isMediaProxyEnabled(prefs)) return

        val port = MediaProxyServer.start(env.hostContext)
        if (port > 0) {
            log("MediaProxy: listening on 127.0.0.1:$port")
        } else {
            log("MediaProxy: failed to start, media URLs stay direct")
        }
    }
}
