package io.github.bbzq.feats.download

/**
 * 多主机并行下载的分片规划。
 *
 * 来自 Make Bilibili Great Than Ever Before 的 thread-ripper：那里是接管播放器的
 * DASH 分片请求做竞速，这里只取它对**下载**有意义的部分 —— 把文件切成互不重叠的
 * 区间、分散到多台 CDN 上并行取。
 *
 * 与流式播放不同，下载没有实时期限，所以不需要它那套 deadline / 紧迫度 / 竞速
 * 抢首字节的机制：区间彼此不重叠，各写各的偏移，最终校验总长度即可。
 *
 * 纯运算，无 Android 依赖，可直接单测。
 */
object PiecePlanning {

    /** 小于这个体积不值得并行：探测与建立多条连接的固定开销占了大头。 */
    const val MIN_PARALLEL_BYTES = 8L * 1024L * 1024L

    /** 单片的目标大小。 */
    const val TARGET_PIECE_BYTES = 2L * 1024L * 1024L

    /** 分片数上限。 */
    const val MAX_PIECES = 16

    data class Piece(val index: Int, val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /**
     * 规划分片。返回 `null` 表示不值得并行，调用方应走单连接的顺序下载。
     *
     * 分片数不会超过主机数的两倍 —— 再多也只是让每台主机多排几个区间，对总耗时
     * 没有帮助，反而增加失败面。
     */
    fun plan(totalBytes: Long, hostCount: Int): List<Piece>? {
        if (hostCount < 2) return null
        if (totalBytes < MIN_PARALLEL_BYTES) return null

        val byTarget = (totalBytes + TARGET_PIECE_BYTES - 1) / TARGET_PIECE_BYTES
        val count = minOf(byTarget, MAX_PIECES.toLong(), hostCount.toLong() * 2L).toInt()
        if (count < 2) return null

        // 边界按整数除法算，保证最后一片精确落在文件末尾，区间无空隙也不重叠
        return (0 until count).map { index ->
            val start = totalBytes * index / count
            val end = totalBytes * (index + 1) / count - 1
            Piece(index, start, end)
        }
    }

    /**
     * 轮流把分片分给各主机：相邻的区间落在不同 CDN 上，避免恰好把连续的一段压给
     * 同一台主机而它又恰好很慢。
     */
    fun assign(pieces: List<Piece>, hostCount: Int): List<List<Piece>> {
        if (hostCount <= 0) return emptyList()
        val buckets = List(hostCount) { mutableListOf<Piece>() }
        pieces.forEach { piece -> buckets[piece.index % hostCount] += piece }
        return buckets.filter { it.isNotEmpty() }
    }
}
