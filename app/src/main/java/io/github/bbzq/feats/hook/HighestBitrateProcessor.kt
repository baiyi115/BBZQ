package io.github.bbzq.feats.hook

import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.setObjectField
import io.github.bbzq.feats.hook.mbgeb.CodecCapability

/** Request/response policy used by the single PlayView hook pipeline. */
internal class HighestBitrateProcessor(
    private val reportFailure: (String, Throwable) -> Unit,
) {
    fun prepareRequest(request: Any?) {
        if (request == null) return
        runCatching {
            enableAllVideoCapabilities(request)
            enableAllVideoCapabilities(request.callMethod("getVod"))
        }.onFailure { reportFailure("request preparation failed at ${request.javaClass.name}", it) }
    }

    fun readStats(response: Any?): VideoStreamStats? {
        if (response == null) return null
        return runCatching {
            val videoInfo = response.callMethod("getVideoInfo")
            val vodInfo = response.callMethod("getVodInfo")
            val viewInfo = response.callMethod("getViewInfo")
            readSelectedStats(vodInfo) ?: readSelectedStats(videoInfo) ?: readSelectedStats(viewInfo)
                ?: readSelectedStats(viewInfo?.callMethod("getVideoInfo"))
        }.onFailure {
            reportFailure("response stats read failed at ${response.javaClass.name}", it)
        }.getOrNull()
    }

    fun preferHighestBitrate(response: Any?, avoidHdrDolby: Boolean = false): VideoStreamStats? {
        if (response == null) return null
        return runCatching {
            val videoInfo = response.callMethod("getVideoInfo")
            val vodInfo = response.callMethod("getVodInfo")
            val viewInfo = response.callMethod("getViewInfo")
            reorderStreams(videoInfo, avoidHdrDolby)
            reorderStreams(vodInfo, avoidHdrDolby)
            reorderStreams(viewInfo, avoidHdrDolby)
            reorderStreams(viewInfo?.callMethod("getVideoInfo"), avoidHdrDolby)
            readSelectedStats(vodInfo) ?: readSelectedStats(videoInfo) ?: readSelectedStats(viewInfo)
                ?: readSelectedStats(viewInfo?.callMethod("getVideoInfo"))
        }.onFailure {
            reportFailure("response selection failed at ${response.javaClass.name}", it)
        }.getOrNull()
    }

    private fun enableAllVideoCapabilities(target: Any?) {
        if (target == null) return
        invokeNumber(target, "setFnval", MAX_FNVAL.toLong())
        invokeOneArg(target, "setFourk", true)
        // Always request the ceiling qn so the server returns 8K/HDR when available;
        // it clamps down to the highest tier the account is entitled to.
        invokeNumber(target, "setQn", HIGHEST_QN)
    }

    private fun reorderStreams(container: Any?, avoidHdrDolby: Boolean) {
        if (container == null) return
        val original = streamsOf(container)

        // Promote the most-preferred available tier as the selected quality. The order mirrors
        // the reference script's QUALITY_ORDER (8K > 杜比视界 > HDR > 4K > 1080P高码率 > 1080P60 > …)
        // rather than a raw numeric max, so e.g. 1080P高码率(112) wins over 1080P60(116). When
        // avoidHdrDolby is set, explicit HDR/Dolby Vision tiers are skipped; QN 127 is unchanged.
        // QN alone is not proof of a stream's colour space or bit depth.
        original.asSequence()
            .filter { dashVideo(it) != null }
            .mapNotNull { stream -> stream.callMethod("getStreamInfo")?.let { number(it, "getQuality") } }
            .filter { !avoidHdrDolby || (it != QN_HDR && it != QN_DOLBY_VISION) }
            .minByOrNull(::preferenceRank)
            ?.let { preferredQuality -> invokeNumber(container, "setQuality", preferredQuality) }

        // Only compare equivalent representations. Comparing AVC and AV1 bandwidth directly,
        // for example, would prefer the less efficient codec rather than the better picture.
        if (original.size < 2) return
        val replacements = original
            .groupBy(::representationKey)
            .filterKeys { it != null }
            .mapValues { (_, streams) -> streams.sortedByDescending(::bandwidth).iterator() }
        if (replacements.values.none { iterator -> iterator.hasNext() }) return

        val reordered = original.map { stream ->
            replacements[representationKey(stream)]?.next() ?: stream
        }
        if (reordered.indices.all { reordered[it] === original[it] }) return
        replaceStreamList(container, original, reordered)
    }

    private fun readSelectedStats(container: Any?): VideoStreamStats? {
        if (container == null) return null
        val selectedQuality = number(container, "getQuality")
        val streams = (container.callMethod("getStreamListList") as? Iterable<*>)
            ?.filterNotNull()
            .orEmpty()
        val stream = streams.firstOrNull { candidate ->
            val info = candidate.callMethod("getStreamInfo") ?: return@firstOrNull false
            dashVideo(candidate) != null &&
                (selectedQuality == null || number(info, "getQuality") == selectedQuality)
        } ?: return null
        val info = stream.callMethod("getStreamInfo") ?: return null
        val video = dashVideo(stream) ?: return null
        val stats = VideoStreamStats(
            quality = number(info, "getQuality") ?: selectedQuality ?: 0,
            bandwidth = number(video, "getBandwidth") ?: 0,
            width = number(video, "getWidth")?.toInt() ?: 0,
            height = number(video, "getHeight")?.toInt() ?: 0,
            codecId = number(video, "getCodecid") ?: number(video, "getCodeId") ?: 0,
            frameRate = stringValue(video, "getFrameRate", "getFrameRateValue", "getFps"),
        )
        return readAudioStats(container, stream, stats)
    }

    private fun readAudioStats(container: Any, stream: Any, stats: VideoStreamStats): VideoStreamStats {
        // Bilibili has shipped several PlayView protobuf layouts. In the common layout
        // audio is a repeated `dash_audio` field on VideoInfo/VodInfo; some versions put
        // it on the selected stream or wrap the actual DashItem in another message.
        val audio = firstListItem(
            container,
            "getDashAudioList",
            "getAudioStreamListList",
            "getAudioDashVideoList",
            "getAudioList",
            "getAudioListList",
            "getAudiosList",
        ) ?: firstValue(
            stream,
            "getDashAudio",
            "getAudioDashVideo",
            "getAudio",
        ) ?: return stats

        val dashAudio = firstValue(
            audio,
            "getDashAudio",
            "getAudioDashVideo",
            "getDashItem",
            "getAudio",
        ) ?: audio
        return stats.copy(
            audioBandwidth = firstNumber(dashAudio, "getBandwidth", "getBandWidth", "getAudioBandwidth") ?: 0,
            audioCodecId = firstNumber(dashAudio, "getCodecid", "getCodeId", "getCodecId", "getAudioCodecid") ?: 0,
            audioSampleRate = firstNumber(
                dashAudio,
                "getSampleRate",
                "getAudioSampleRate",
                "getSamplingRate",
            )?.toInt() ?: 0,
            audioChannels = firstNumber(
                dashAudio,
                "getChannels",
                "getChannelCount",
                "getAudioChannels",
            )?.toInt() ?: 0,
        )
    }

    private fun firstListItem(target: Any, vararg getters: String): Any? =
        getters.asSequence()
            .mapNotNull { getter -> target.callMethod(getter) as? Iterable<*> }
            .mapNotNull { values -> values.firstOrNull { it != null } }
            .firstOrNull()

    private fun firstValue(target: Any, vararg getters: String): Any? =
        getters.firstNotNullOfOrNull { getter -> target.callMethod(getter) }

    private fun firstNumber(target: Any, vararg getters: String): Long? =
        getters.firstNotNullOfOrNull { getter -> number(target, getter) }

    private fun representationKey(stream: Any): RepresentationKey? {
        val info = stream.callMethod("getStreamInfo") ?: return null
        val video = dashVideo(stream) ?: return null
        return RepresentationKey(
            quality = number(info, "getQuality") ?: return null,
            codec = number(video, "getCodecid") ?: number(video, "getCodeId") ?: 0,
            width = number(video, "getWidth") ?: 0,
            height = number(video, "getHeight") ?: 0,
            frameRate = stringValue(video, "getFrameRate", "getFrameRateValue", "getFps"),
        )
    }

    private fun bandwidth(stream: Any): Long {
        val video = dashVideo(stream) ?: return Long.MIN_VALUE
        return number(video, "getBandwidth") ?: Long.MIN_VALUE
    }

    /** Absent protobuf message fields may expose a non-null default instance. */
    private fun dashVideo(stream: Any): Any? {
        if (stream.callMethod("hasDashVideo") == false) return null
        val video = stream.callMethod("getDashVideo") ?: return null
        val url = video.callMethod("getBaseUrl") as? String
        val backup = video.callMethod("getBackupUrlList") as? Iterable<*>
        return video.takeIf { !url.isNullOrBlank() || backup?.any { it is String && it.isNotBlank() } == true }
    }

    private fun streamsOf(container: Any): List<Any> =
        (container.callMethod("getStreamListList") as? Iterable<*>)
            ?.filterNotNull()
            .orEmpty()

    /** Rank equivalent ordinary SDR candidates without deleting any fallback or changing QN. */
    fun preferHardware(response: Any?) {
        if (response == null) return
        val view = response.callMethod("getViewInfo")
        val containers = listOfNotNull(response.callMethod("getVideoInfo"), response.callMethod("getVodInfo"),
            view, view?.callMethod("getVideoInfo"))
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        containers.forEach { container ->
            if (seen.put(container, true) != null) return@forEach
            val original = streamsOf(container)
            val reordered = HardwareStreamPreference.reorder(original, key = { stream ->
                val rep = representationKey(stream)
                val video = dashVideo(stream)
                val rate = rep?.let { HardwareStreamPreference.frameRate(it.frameRate) }
                // Do not infer HDR/bit depth from QN, or move unknown / DRM representations.
                if (rep == null || video == null || rate == null || rep.quality !in SDR_QUALITIES ||
                    rep.width <= 0 || rep.height <= 0 || (number(video, "getDrmType") ?: 0) != 0L) null
                else HardwareKey(rep.quality, rep.width, rep.height, rate)
            }, preferred = { stream ->
                val video = dashVideo(stream)
                video != null && CodecCapability.matchesKnownConstraints(CodecCapability.VideoSpec(
                    codecId = number(video, "getCodecid") ?: number(video, "getCodeId") ?: 0,
                    width = number(video, "getWidth")?.toInt() ?: 0,
                    height = number(video, "getHeight")?.toInt() ?: 0,
                    frameRate = HardwareStreamPreference.frameRate(stringValue(video, "getFrameRate", "getFrameRateValue", "getFps")),
                    bitrate = number(video, "getBandwidth") ?: 0,
                ))
            })
            if (!sameStreams(original, reordered)) replaceStreamList(container, original, reordered)
        }
    }

    private data class HardwareKey(val quality: Long, val width: Long, val height: Long, val rate: Double)

    private fun sameStreams(a: List<Any>, b: List<Any>): Boolean =
        a.size == b.size && a.indices.all { a[it] === b[it] }

    /** Check writes through the getter; do not mistake a non-throwing call for success. */
    private fun replaceStreamList(container: Any, original: List<Any>, reordered: List<Any>): Boolean {
        val exposed = container.callMethod("getStreamListList")
        // Prefer replacing the field atomically. Reflect refuses incompatible specialised list types.
        val fieldValue = container.getObjectField("streamList_")
        if (container.setObjectField("streamList_", reordered)) {
            if (sameStreams(streamsOf(container), reordered)) return true
            container.setObjectField("streamList_", fieldValue)
        }
        val replaced = runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = exposed as MutableList<Any>
            list.clear()
            list.addAll(reordered)
            sameStreams(streamsOf(container), reordered)
        }.getOrDefault(false)
        if (replaced) return true
        // Undo a partial clear/add before trying generated mutators.
        if (!sameStreams(streamsOf(container), original)) runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = exposed as MutableList<Any>
            list.clear()
            list.addAll(original)
        }
        val methods = container.javaClass.allMethods().toList()
        val canClear = methods.any { it.name == "clearStreamList" && it.parameterCount == 0 }
        val canAdd = methods.any { it.name == "addAllStreamList" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(reordered) }
        if (canClear && canAdd && invokeNoArg(container, "clearStreamList")) {
            invokeOneArg(container, "addAllStreamList", reordered)
            if (sameStreams(streamsOf(container), reordered)) return true
            invokeNoArg(container, "clearStreamList")
            invokeOneArg(container, "addAllStreamList", original)
        }
        reportFailure("stream list write/readback failed at ${container.javaClass.name}",
            IllegalStateException("original restored=${sameStreams(streamsOf(container), original)}"))
        return false
    }

    // Lower is more preferred; unknown qn values rank last
    private fun preferenceRank(qn: Long): Int {
        val index = QN_PREFERENCE.indexOf(qn)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun number(target: Any, getter: String): Long? =
        (target.callMethod(getter) as? Number)?.toLong()

    private fun stringValue(target: Any, vararg getters: String): String =
        getters.firstNotNullOfOrNull { getter -> target.callMethod(getter)?.toString() }.orEmpty()

    private fun invokeNoArg(target: Any, name: String): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return false
        return runCatching { method.invoke(target); true }.getOrDefault(false)
    }

    private fun invokeOneArg(target: Any, name: String, value: Any): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { method ->
            method.name == name && method.parameterCount == 1 &&
                method.parameterTypes[0].let { type ->
                    type.isInstance(value) ||
                        (type == Int::class.javaPrimitiveType && value is Int) ||
                        (type == Long::class.javaPrimitiveType && value is Long) ||
                        (type == Boolean::class.javaPrimitiveType && value is Boolean)
                }
        } ?: return false
        return runCatching { method.invoke(target, value); true }.getOrDefault(false)
    }

    private fun invokeNumber(target: Any, name: String, value: Long): Boolean {
        val method = target.javaClass.allMethods().firstOrNull { method ->
            method.name == name && method.parameterCount == 1 && method.parameterTypes[0].let { type ->
                type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType ||
                    type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType
            }
        } ?: return false
        val converted: Any = when (method.parameterTypes[0]) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> value.toInt()
            else -> value
        }
        return runCatching { method.invoke(target, converted); true }.getOrDefault(false)
    }

    private data class RepresentationKey(
        val quality: Long,
        val codec: Long,
        val width: Long,
        val height: Long,
        val frameRate: String,
    )

    private companion object {
        // DASH, HDR, Dolby, 8K and AV1 capability bits used by current Bilibili clients.
        const val MAX_FNVAL = 16 or 64 or 128 or 256 or 512 or 1024 or 2048

        // Ceiling qn requested from the server (8K). The server clamps it down to the highest tier the account/video actually supports.
        const val HIGHEST_QN = 127L
        const val QN_HDR = 125L
        const val QN_DOLBY_VISION = 126L
        val SDR_QUALITIES = setOf(16L, 32L, 64L, 74L, 80L, 112L, 116L, 120L)

        // Quality preference from best to worst, mirroring the reference script's QUALITY_ORDER.
        // 8K(127) > 杜比视界(126) > HDR(125) > 4K(120) > 1080P高码率(112) > 1080P60(116) > 1080P(80) > 720P60(74) > 720P(64) > 480P(32) > 360P(16).
        val QN_PREFERENCE = longArrayOf(127, 126, 125, 120, 112, 116, 80, 74, 64, 32, 16)
    }
}

internal data class VideoStreamStats(
    val quality: Long,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecId: Long,
    val frameRate: String,
    val audioBandwidth: Long = 0,
    val audioCodecId: Long = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
)
