package io.github.bbzq.feats.hook.mbgeb

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.findClassOrNull
import io.github.bbzq.feats.getObjectField
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.methodOrNull
import io.github.bbzq.feats.setBooleanField
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/** Item-local IJK policy. Never intercepts global MediaCodec creation or disables native fallback. */
class HardwareDecoderHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val convertingOrdinaryItem = ThreadLocal<Boolean>()

    override fun startHook() {
        if (env.processName != env.packageName) return
        val itemClass = classLoader.findClassOrNull(ITEM_CLASS) ?: return
        val options = itemClass.methodOrNull("setItemOptions") ?: return
        val names = itemClass.methodOrNull("getCodecName", String::class.java) ?: return
        val conversion = itemClass.methodOrNull("mediaAssetToJson") ?: return
        if (options.returnType != Void.TYPE || names.returnType != String::class.java ||
            conversion.returnType != Array<String>::class.java) return

        // Stream admission happens BEFORE setItemOptions in the initial message queue. Scope the
        // helper override to this conversion only, not global fnval/DRM/soft-activation decisions.
        runCatching {
            env.hookRegistrations.install(conversion, "hardwareConversion", this, "item-stream-admission") {
                conversion.isAccessible = true
                xposed.hook(conversion)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val previous = convertingOrdinaryItem.get()
                        val eligible = runCatching {
                            val item = chain.getThisObject()
                            enabled() && item != null && ordinaryItem(item).also { ordinary ->
                                if (ordinary) prepareConfig(item)
                            }
                        }.getOrDefault(false)
                        convertingOrdinaryItem.set(eligible)
                        try {
                            // Exactly once, including when the original throws; no retry wrapper.
                            chain.proceed()
                        } finally {
                            if (previous == null) convertingOrdinaryItem.remove()
                            else convertingOrdinaryItem.set(previous)
                        }
                    }
            }
        }.onFailure { log("HardwareDecoder: conversion registration failed", it) }

        val helper = classLoader.findClassOrNull("tv.danmaku.ijk.media.player.IjkCodecHelper")
        listOf("isAv1SupportInternal" to "video/av01", "isH266SupportHard" to "video/vvc").forEach { (name, mime) ->
            helper?.methodOrNull(name)?.takeIf {
                Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                env.hookAfter(method) { param ->
                    if (convertingOrdinaryItem.get() == true && CodecCapability.decoderName(mime) != null) {
                        param.result = true
                    }
                }
            }
        }
        env.hookBefore(options) { param ->
            if (!enabled()) return@hookBefore
            val item = param.thisObject ?: return@hookBefore
            if (ordinaryItem(item)) prepareConfig(item)
        }
        env.hookAfter(names) { param ->
            if (!enabled()) return@hookAfter
            val item = param.thisObject ?: return@hookAfter
            if (!ordinaryItem(item)) return@hookAfter
            val mime = param.args.firstOrNull() as? String ?: return@hookAfter
            // Retain an official hardware choice. For an expanded choice with no stream format,
            // require one unambiguous component rather than guessing the fastest by list order.
            CodecCapability.decoderName(mime, param.result as? String)?.let { param.result = it }
        }
        // Do NOT suppress UpdateSoftActivationType: it controls which software fallback streams
        // survive mediaAssetToJson, not merely which decoder wins an optional race.
        log("HardwareDecoder: item policy registration attempted; software activation and native fallback retained")
    }

    private fun enabled() = ModuleSettings.isPreferHardwareDecoderEnabled(prefs)

    private fun prepareConfig(item: Any) {
        val config = item.getObjectField("mIjkMediaConfigParams") ?: return
        val asset = item.getObjectField("mMediaAsset") ?: return
        val streams = asset.callMethod("getStreamList") as? Iterable<*> ?: return
        val types = streams.filterNotNull().filter {
            it.callMethod("getStreamType")?.toString() == "DASH_VIDEO"
        }.mapNotNull { it.callMethod("getVideoCodecType")?.toString() }.toSet()
        val available = listOf("H264" to "video/avc", "H265" to "video/hevc", "AV1" to "video/av01", "H266" to "video/vvc")
            .filter { (type, mime) -> type in types && CodecCapability.decoderName(mime) != null }
        if (available.isEmpty()) return
        config.setBooleanField("mEnableHwCodec", true)
        if (available.any { it.first == "H265" }) config.setBooleanField("mEnableH265Codec", true)
        // Leave mEnableDecodeSwitch/race and all error-recovery options unchanged.
    }

    private fun ordinaryItem(item: Any): Boolean {
        val config = item.getObjectField("mIjkMediaConfigParams") ?: return false
        val asset = item.getObjectField("mMediaAsset") ?: return false
        val flags = listOf("mEnableAlphaLayer", "mGetFrameMode", "mEnableRawData", "mEnableDolbyVisionCodec", "mAudioOnlyPreload")
        if (flags.any { config.getObjectField(it) != false }) return false
        if ((config.getObjectField("mHdrVideoType") as? Number)?.toInt() != 0) return false
        return (asset.callMethod("getDrmType") as? Number)?.toInt() == 0
    }

    private companion object {
        const val ITEM_CLASS = "tv.danmaku.ijk.media.player.IjkMediaPlayerItem"
    }
}
