package io.github.bbzq.feats.hook.mbgeb

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import java.util.Locale

/** Process-local snapshot; declarations are not proof that a decoder can actually be created. */
object CodecCapability {
    enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

    data class VideoSpec(
        val codecId: Long,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Double? = null,
        val bitrate: Long = 0,
        val profile: Int? = null,
        val level: Int? = null,
    )

    private data class Decoder(
        val name: String,
        val mime: String,
        val capabilities: MediaCodecInfo.CodecCapabilities,
    )
    private data class Snapshot(val decoders: List<Decoder>, val complete: Boolean, val queriedAt: Long)

    private val codecMime = mapOf(
        CodecSelection.CODEC_AVC to "video/avc",
        CodecSelection.CODEC_HEVC to "video/hevc",
        CodecSelection.CODEC_AV1 to "video/av01",
        14L to "video/vvc",
    )
    private val videoMimes = codecMime.values.toSet() + "video/dolby-vision"
    private val softwareMarkers = listOf("omx.google.", "c2.android.", "ffmpeg", ".sw.", ".software.")
    // API 28 has no authoritative hardware flag. Unknown names remain unknown, not hardware.
    private val legacyVendors = listOf("omx.qcom.", "omx.qti.", "omx.mtk.", "omx.exynos.", "omx.hisi.", "omx.amlogic.", "omx.nvidia.", "omx.rk.")
    @Volatile private var cached: Snapshot? = null

    fun hardwareDecodableCodecIds(): Set<Long> {
        val mimes = snapshot().decoders.mapTo(HashSet()) { it.mime }
        return codecMime.filterValues { it in mimes }.keys.toSet()
    }

    fun usableCodecIds(all: Set<Long>): Set<Long> =
        CodecSelection.narrow(all, hardwareDecodableCodecIds())

    /** Check all supplied constraints against the SAME component. Missing profile/level stays Unknown. */
    fun support(spec: VideoSpec): Support {
        val mime = codecMime[spec.codecId] ?: return Support.UNKNOWN
        val state = snapshot()
        val candidates = state.decoders.filter { it.mime == mime }
        val results = candidates.map { supports(it, spec) }
        if (Support.SUPPORTED in results) return Support.SUPPORTED
        if (!state.complete || results.isEmpty() || Support.UNKNOWN in results) return Support.UNKNOWN
        return Support.UNSUPPORTED
    }

    /** Names only for ordinary, non-secure playback. No software/unknown component is substituted. */
    fun decoderName(mime: String, preferred: String? = null): String? {
        val candidates = snapshot().decoders.filter { it.mime.equals(mime, ignoreCase = true) }
        // With no stream format, do not arbitrarily choose between multiple hardware components.
        return CodecSelection.chooseHardwareName(candidates.map { it.name }, preferred)
    }

    /** Partial format matching used to rank candidates, never to delete fallback streams. */
    fun matchesKnownConstraints(spec: VideoSpec): Boolean {
        val mime = codecMime[spec.codecId] ?: return false
        if (spec.width <= 0 || spec.height <= 0) return false
        // The item layer has no dimensions/profile. Only rank a component we can also select
        // unambiguously there; otherwise keep the official order until format binding is available.
        val selected = decoderName(mime) ?: return false
        return snapshot().decoders.any { it.name == selected && it.mime == mime && supports(it, spec) != Support.UNSUPPORTED &&
            runCatching { sizeSupported(it.capabilities, spec) }.getOrDefault(false) }
    }

    private fun supports(decoder: Decoder, spec: VideoSpec): Support = runCatching {
        if (spec.width <= 0 || spec.height <= 0) return@runCatching Support.UNKNOWN
        if (!sizeSupported(decoder.capabilities, spec)) return@runCatching Support.UNSUPPORTED
        val video = decoder.capabilities.videoCapabilities ?: return@runCatching Support.UNKNOWN
        if (spec.bitrate > Int.MAX_VALUE) return@runCatching Support.UNSUPPORTED
        if (spec.bitrate > 0 && !video.bitrateRange.contains(spec.bitrate.toInt())) return@runCatching Support.UNSUPPORTED
        // Android level values are not universally ordered (e.g. AVC level 1b). Do not compare integers.
        if (spec.profile == null || spec.level == null) return@runCatching Support.UNKNOWN
        if (!decoder.capabilities.profileLevels.any { it.profile == spec.profile && it.level == spec.level }) {
            return@runCatching Support.UNKNOWN
        }
        if (spec.frameRate == null) Support.UNKNOWN else Support.SUPPORTED
    }.getOrDefault(Support.UNKNOWN)

    private fun sizeSupported(caps: MediaCodecInfo.CodecCapabilities, spec: VideoSpec): Boolean {
        val video = caps.videoCapabilities ?: return false
        val rate = spec.frameRate?.takeIf { it.isFinite() && it > 0 }
        return if (rate != null) video.areSizeAndRateSupported(spec.width, spec.height, rate)
        else video.isSizeSupported(spec.width, spec.height)
    }

    private fun snapshot(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { it.complete || now - it.queriedAt < RETRY_MILLIS }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.complete || now - it.queriedAt < RETRY_MILLIS } ?: query(now).also { cached = it }
        }
    }

    private fun query(now: Long): Snapshot {
        var complete = true
        val decoders = mutableListOf<Decoder>()
        val infos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }.getOrElse {
            return Snapshot(emptyList(), false, now)
        }
        infos.forEach { info ->
            runCatching {
                if (info.isEncoder || !isHardware(info)) return@runCatching
                if (Build.VERSION.SDK_INT >= 29 && info.isAlias) return@runCatching
                val name = info.name
                if (name.endsWith(".secure", true) || name.endsWith(".tunneled", true)) return@runCatching
                info.supportedTypes.forEach { type ->
                    val mime = type.lowercase(Locale.ROOT)
                    if (mime !in videoMimes) return@forEach
                    runCatching {
                        val caps = info.getCapabilitiesForType(type)
                        if (!caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                            !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                            decoders.add(Decoder(name, mime, caps))
                        }
                    }.onFailure { complete = false }
                }
            }.onFailure { complete = false }
        }
        return Snapshot(decoders.distinctBy { it.name to it.mime }, complete, now)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase(Locale.ROOT)
        if (softwareMarkers.any { it in name }) return false
        return if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else legacyVendors.any { name.startsWith(it) }
    }

    private const val RETRY_MILLIS = 60_000L
}
