package io.github.bbzq.feats.hook.mbgeb

/**
 * 直播画质的取值策略。
 *
 * Web 端的做法（Make Bilibili Great Than Ever Before 的 enhance-live）是在拿到流地址
 * 之后用正则改写 URL：剥掉 `(live-bvc/N/live_M_N)_xxx` 的画质后缀、剥掉 `_minihevc` /
 * `_prohevc`、把 smtcdns 镜像换回原站。那是在浏览器里只能用 URL 的无奈之举。
 *
 * 移动端不需要这样：直播 playurl 请求本身带一个 `qn` 查询参数
 * （`RoomApiService.getRoomPlayInfoV2` 的 `@Query("qn")`），直接把请求的档位抬到原画，
 * 服务端就会返回原画流。不用解析 URL，也没有后缀正则失效的风险。
 *
 * 直播档位数值即序：流畅 80 < 高清 150 < 超清 250 < 蓝光 400 < **原画 10000**
 * < 4K 20000 < 杜比 30000。因此"抬到原画"是 `max(当前, 10000)`，只升不降 ——
 * 用户已经选了 4K / 杜比时不动它。
 */
object LiveQualityPolicy {

    /** 原画档位。 */
    const val ORIGINAL_QN = 10000

    /** 直播 playurl 接口的路径后缀。 */
    const val PLAY_INFO_PATH_SUFFIX = "/xlive/app-room/v2/index/getRoomPlayInfo"

    fun isLivePlayInfoPath(path: String): Boolean = path.endsWith(PLAY_INFO_PATH_SUFFIX)

    /**
     * 应当改写成哪个档位；`null` 表示不改。
     *
     * [currentQn] 为 `null` 表示请求里没有 `qn` 参数 —— 那正是最需要补上的情况，
     * 返回原画档位。
     */
    fun raiseToOriginal(currentQn: Int?): Int? {
        if (currentQn == null) return ORIGINAL_QN
        return if (currentQn >= ORIGINAL_QN) null else ORIGINAL_QN
    }
}
