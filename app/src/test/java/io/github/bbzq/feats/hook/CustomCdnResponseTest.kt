package io.github.bbzq.feats.hook

import org.junit.Assert.*
import org.junit.Test

/** 普通 JVM 宿主模型，不需要 Android 或真实 protobuf 依赖。 */
class CustomCdnResponseTest {
    private val now = 2_000_000_000L
    private val host = "upos-sz-mirrorcos.bilivideo.com"
    private val base = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/video.m4s?upsig=a&deadline=${now + 3600}"
    private val backup = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/2/video.m4s?upsig=b&deadline=${now + 3600}"

    class Item(private var base: String, private val backups: MutableList<String>) {
        var writes = 0
        fun getBaseUrl() = base
        fun setBaseUrl(value: String) { base = value; writes++ }
        fun getBackupUrlList(): List<String> = backups
        fun clearBackupUrl() { backups.clear() }
        fun addAllBackupUrl(values: Iterable<String>) { backups.addAll(values) }
    }
    class Stream(private val video: Any) { fun getDashVideo() = video }
    class VideoInfo(private val streams: List<Stream>, private val audio: List<Item>) {
        fun getStreamListList() = streams
        fun getDashAudioList() = audio
    }
    class Response(private val info: VideoInfo) {
        fun getVideoInfo() = info
        fun getVodInfo() = info // 两个别名返回同一个对象。
    }
    class FieldItem(@JvmField var url_: String, @JvmField var backup_: List<String>)
    class ReadOnlyBackups(private var base: String, private val stored: Array<String>) {
        fun getBaseUrl() = base
        fun setBaseUrl(value: String) { base = value }
        fun getBackupUrlList() = stored.toList() // 返回快照，无法直接匹配内部集合字段。
    }
    class UrlItem(private var url: String, private val backups: MutableList<String>) {
        fun getUrl() = url
        fun setUrl(value: String) { url = value }
        fun getBackupUrl() = backups
        fun clearBackupUrlList() { backups.clear() }
        fun addAllBackupUrlList(values: Iterable<String>) { backups.addAll(values) }
    }
    class SegmentContainer(private val parts: List<UrlItem>) { fun getSegmentList() = parts }
    class SegmentStream(private val segments: SegmentContainer) { fun getSegmentVideo() = segments }
    class SegmentResponse(private val streams: List<SegmentStream>) { fun getStreamList() = streams }

    @Test fun responseAliasesAreDeduplicatedAndNativeBackupsSurvive() {
        val video = Item(base, mutableListOf(backup))
        val audio = Item(base, mutableListOf(backup))
        val response = Response(VideoInfo(listOf(Stream(video)), listOf(audio)))
        CustomCdnProcessor.rewriteResponse(response, listOf(host), false, now)
        assertEquals(CustomCdnProcessor.replaceHost(base, host), video.getBaseUrl())
        assertEquals(listOf(base, backup), video.getBackupUrlList())
        assertEquals(1, video.writes)
        assertEquals(1, audio.writes)
        CustomCdnProcessor.rewriteResponse(response, listOf(host), false, now)
        assertEquals(1, video.writes) // 同步返回/异步回调重复处理必须幂等。
        assertEquals(listOf(base, backup), video.getBackupUrlList())
    }

    @Test fun independentAudioKeepsItsOriginalPrimaryAndBackups() {
        val video = Item(base, mutableListOf(backup))
        val audio = Item(base, mutableListOf(backup))
        CustomCdnProcessor.rewriteResponse(Response(VideoInfo(listOf(Stream(video)), listOf(audio))), listOf(host), true, now)
        assertEquals(1, video.writes)
        assertEquals(base, audio.getBaseUrl())
        assertEquals(listOf(backup), audio.getBackupUrlList())
        assertEquals(0, audio.writes)
    }

    @Test fun obfuscatedStorageCanBeReplacedWithoutScanningUnrelatedResponseUrls() {
        val item = FieldItem(base, listOf(backup))
        val response = Response(VideoInfo(listOf(Stream(item)), emptyList()))
        CustomCdnProcessor.rewriteResponse(response, listOf(host), false, now)
        assertEquals(CustomCdnProcessor.replaceHost(base, host), item.url_)
        assertEquals(listOf(base, backup), item.backup_)
    }

    @Test fun segmentUrlAliasesAreSupported() {
        val item = UrlItem(base, mutableListOf(backup))
        CustomCdnProcessor.rewriteResponse(
            SegmentResponse(listOf(SegmentStream(SegmentContainer(listOf(item))))), listOf(host), false, now,
        )
        assertEquals(CustomCdnProcessor.replaceHost(base, host), item.getUrl())
        assertEquals(listOf(base, backup), item.getBackupUrl())
    }

    @Test fun readOnlyBackupStorageRollsBackPrimaryRewrite() {
        val item = ReadOnlyBackups(base, arrayOf(backup))
        val response = Response(VideoInfo(listOf(Stream(item)), emptyList()))
        CustomCdnProcessor.rewriteResponse(response, listOf(host), false, now)
        assertEquals(base, item.getBaseUrl())
        assertEquals(listOf(backup), item.getBackupUrlList())
    }

    @Test fun noConfigurationDoesNotMutateAnything() {
        val item = Item(base, mutableListOf(backup))
        CustomCdnProcessor.rewriteResponse(Response(VideoInfo(listOf(Stream(item)), emptyList())), emptyList(), false, now)
        assertEquals(base, item.getBaseUrl())
        assertEquals(0, item.writes)
    }
}
