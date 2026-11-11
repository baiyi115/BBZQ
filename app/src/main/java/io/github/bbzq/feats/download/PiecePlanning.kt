package io.github.bbzq.feats.download

/**
 * 字节区间的切分与分配。
 *
 * 仅用于离线下载：把文件区间切成互不重叠的片，分散到多个源上并行取。
 * 在线播放不经此处拆片，由官方播放器直接管理取流与缓冲。
 *
 * 纯运算，无 Android 依赖，可直接单测。
 */
object PiecePlanning {

    data class Piece(val index: Int, val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /**
     * 切分参数。
     *
     * [minTotalBytes] 以下不并行：探测与多建连接的固定开销占了大头。
     * [targetPieceBytes] 是单片目标大小，最终片数由它、[maxPieces] 与源数共同决定。
     */
    data class Policy(
        val minTotalBytes: Long,
        val targetPieceBytes: Long,
        val maxPieces: Int,
    )

    /** 下载：文件整体切分，片大一些，减少连接数与失败面。 */
    val DOWNLOAD = Policy(
        minTotalBytes = 8L * MIB,
        targetPieceBytes = 2L * MIB,
        maxPieces = 16,
    )

    /**
     * 切分 `[startOffset, startOffset + length - 1]`。
     *
     * 起点可作为参数传入，便于切分断点续传的剩余区间。
     * 返回 `null` 表示不值得并行，调用方走单连接。
     *
     * 片数不超过源数的两倍：再多也只是让每个源多排几片，对总耗时没有帮助，反而增加
     * 失败面。
     */
    fun plan(startOffset: Long, length: Long, sourceCount: Int, policy: Policy): List<Piece>? {
        if (sourceCount < 2) return null
        if (length < policy.minTotalBytes) return null
        if (length < policy.targetPieceBytes * 2) return null

        val byTarget = (length + policy.targetPieceBytes - 1) / policy.targetPieceBytes
        val count = minOf(byTarget, policy.maxPieces.toLong(), sourceCount.toLong() * 2L).toInt()
        if (count < 2) return null

        // 边界用整数除法算，保证最后一片精确落在区间末尾，片间无空隙也不重叠
        return (0 until count).map { index ->
            Piece(
                index = index,
                start = startOffset + length * index / count,
                end = startOffset + length * (index + 1) / count - 1,
            )
        }
    }

    /**
     * 轮流把片分给各个源：相邻的片落到不同的源，避免恰好把连续的一段压给同一个源。
     */
    fun assign(pieces: List<Piece>, sourceCount: Int): List<List<Piece>> {
        if (sourceCount <= 0) return emptyList()
        val buckets = List(sourceCount) { mutableListOf<Piece>() }
        pieces.forEach { piece -> buckets[piece.index % sourceCount] += piece }
        return buckets.filter { it.isNotEmpty() }
    }

    private const val MIB = 1024L * 1024L

}
