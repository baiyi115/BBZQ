package io.github.bbzq.feats.download

/**
 * 字节区间的切分与分配。
 *
 * 下载与本地媒体代理都要"把一段区间切成互不重叠的片、分散到多个源上并行取"，
 * 差别只在参数：下载是长连接、片大、片少；代理每次只服务一块、片小、片数受可用源
 * 限制。所以切分逻辑只有一份，调用方各自给一套 [Policy]。
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

    /** 本地媒体代理：每次服务一小块，片要小、要快，片数受可用源数限制。 */
    val MEDIA_PROXY = Policy(
        minTotalBytes = MIN_PROXY_PIECE_BYTES,
        targetPieceBytes = MIN_PROXY_PIECE_BYTES,
        maxPieces = 4,
    )

    /**
     * 切分 `[startOffset, startOffset + length - 1]`。
     *
     * 起点可作为参数传入，便于对文件中间的一段（代理每次服务的一块）直接切分。
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

    /** 代理的单片下限：再小的话，建立连接与首个字节的耗时占比过高。 */
    const val MIN_PROXY_PIECE_BYTES = 512L * 1024L
}
