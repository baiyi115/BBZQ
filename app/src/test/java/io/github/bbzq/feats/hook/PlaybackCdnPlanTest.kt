package io.github.bbzq.feats.hook

import org.junit.Assert.*
import org.junit.Test

class PlaybackCdnPlanTest {
    private val now = 2_000_000_000L
    private val host = "upos-sz-mirrorcos.bilivideo.com"
    private val base = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/video.m4s?upsig=base&deadline=${now + 3600}"
    private val backup = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/2/video.m4s?upsig=backup&deadline=${now + 7200}"
    private fun plan(b: String = base, bs: List<String> = listOf(backup), hs: List<String> = listOf(host)) =
        PlaybackCdnPlan.plan(b, bs, hs, now)

    @Test fun disabledConfigurationDoesNotTouchOfficialUrls() {
        assertNull(plan(hs = emptyList()))
    }

    @Test fun configuredDirectHostPreservesAllOriginalSignedBackups() {
        val result = plan()!!
        assertEquals(CustomCdnProcessor.replaceHost(base, host), result.base)
        assertEquals(listOf(base, backup), result.backups)
        assertFalse((listOf(result.base) + result.backups).any { it.contains("127.0.0.1") })
    }

    @Test fun configuredOrderIsStableAndDuplicatesDisappear() {
        val second = "upos-sz-mirrorali.bilivideo.com"
        val result = plan(bs = listOf(base, backup, backup), hs = listOf(host, host, second))!!
        assertEquals(listOf(CustomCdnProcessor.replaceHost(base, host), base, backup), listOf(result.base) + result.backups)
        val again = PlaybackCdnPlan.plan(result.base, result.backups, listOf(host, second), now)
        assertEquals(result, again)
    }

    @Test fun expiredOrPcdnBaseUsesFreshNativeBackup() {
        val expired = base.substringBefore("deadline=") + "deadline=${now - 1}"
        val result = plan(b = expired)!!
        assertEquals(CustomCdnProcessor.replaceHost(backup, host), result.base)
        assertEquals(listOf(backup), result.backups)
        val pcdn = "https://upos-sz-302ppio.bilivideo.com/upgcxcode/1/video.m4s"
        assertEquals(result, plan(b = pcdn))
    }

    @Test fun noUsableSourceLeavesHostResponseUntouched() {
        assertNull(plan(b = "https://mcdn.bilivideo.cn/video", bs = emptyList()))
        assertNull(plan(b = base.substringBefore("deadline=") + "deadline=$now", bs = emptyList()))
    }

    @Test fun expiringUrlsAreFallbackOnlyAndNeverCloned() {
        val expiring = base.substringBefore("deadline=") + "deadline=${now + 10}"
        val result = plan(b = expiring)!!
        assertEquals(CustomCdnProcessor.replaceHost(backup, host), result.base)
        assertEquals(listOf(backup, expiring), result.backups)
        assertEquals(PlaybackCdnPlan.Plan(expiring, emptyList()), plan(b = expiring, bs = emptyList()))
    }

    @Test fun akamaiTokensAndNonUposPathsAreNotMovedToOtherHosts() {
        val akamai = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/1/video.m4s?hdnts=exp=${now + 3600}~hmac=token"
        assertEquals(PlaybackCdnPlan.Plan(akamai, emptyList()), plan(b = akamai, bs = emptyList()))
        val foreignPath = "https://example.com/bangumi/video.m4s?upsig=signed"
        assertEquals(PlaybackCdnPlan.Plan(foreignPath, emptyList()), plan(b = foreignPath, bs = emptyList()))
        val fakePathInQuery = "https://example.com/video?path=/upgcxcode/file"
        assertEquals(PlaybackCdnPlan.Plan(fakePathInQuery, emptyList()), plan(b = fakePathInQuery, bs = emptyList()))
        val noPathWithQuery = "https://upos-sz-mirrorali.bilivideo.com?path=/upgcxcode/file"
        assertEquals(PlaybackCdnPlan.Plan(noPathWithQuery, emptyList()), plan(b = noPathWithQuery, bs = emptyList()))
        val foreignHost = "https://example.com/upgcxcode/file?upsig=token"
        assertEquals(PlaybackCdnPlan.Plan(foreignHost, emptyList()), plan(b = foreignHost, bs = emptyList()))
    }

    @Test fun incompatibleConfiguredAkamaiFallsBackToOriginals() {
        assertEquals(PlaybackCdnPlan.Plan(base, listOf(backup)), plan(hs = listOf("upos-hz-mirrorakam.akamaized.net")))
    }
}
