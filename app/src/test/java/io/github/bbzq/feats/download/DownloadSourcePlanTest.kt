package io.github.bbzq.feats.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourcePlanTest {

    private val uposUrl =
        "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/2/3.m4s?upsig=abc&deadline=2000000000"

    @Test
    fun testConfiguredHostsComeFirstAndOriginalIsTheFallback() {
        val hosts = listOf("upos-sz-mirrorcos.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com")
        val plan = DownloadSourcePlan.plan(uposUrl, hosts)

        assertEquals(3, plan.size)
        assertEquals(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3.m4s?upsig=abc&deadline=2000000000",
            plan[0].url,
        )
        assertEquals(
            "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/1/2/3.m4s?upsig=abc&deadline=2000000000",
            plan[1].url,
        )
        assertEquals(uposUrl, plan[2].url)
        assertEquals(DownloadSourcePlan.ORIGINAL_LABEL, plan[2].label)
    }

    @Test
    fun testAkamaiIsNeverAHostSwapTarget() {
        // Akamai 承载不了 upos 签名，必须被过滤掉，否则会稳定 403
        val plan = DownloadSourcePlan.plan(
            uposUrl,
            listOf("upos-hz-mirrorakam.akamaized.net", "upos-sz-mirrorcos.bilivideo.com"),
        )
        assertEquals(2, plan.size)
        assertEquals("upos-sz-mirrorcos.bilivideo.com", hostOf(plan[0].url))
        assertEquals(uposUrl, plan[1].url)
        assertTrue(plan.none { it.url.contains("akamaized.net") })
    }

    @Test
    fun testHostEqualToTheOriginalIsNotDuplicated() {
        val plan = DownloadSourcePlan.plan(uposUrl, listOf("upos-sz-mirrorali.bilivideo.com"))
        assertEquals(1, plan.size)
        assertEquals(uposUrl, plan[0].url)
    }

    @Test
    fun testOnlyUpgcxcodeAddressesAreSwapped() {
        // 没有 /upgcxcode/ 的地址换主机没有意义
        val other = "https://upos-sz-mirrorali.bilivideo.com/live-bvc/123.flv"
        val plan = DownloadSourcePlan.plan(other, listOf("upos-sz-mirrorcos.bilivideo.com"))
        assertEquals(1, plan.size)
        assertEquals(other, plan[0].url)

        // PCDN 地址本来就要丢弃，也不参与换主机
        val pcdn = "https://upos-sz-302ppio.bilivideo.com/upgcxcode/1.m4s"
        val pcdnPlan = DownloadSourcePlan.plan(pcdn, listOf("upos-sz-mirrorcos.bilivideo.com"))
        assertEquals(1, pcdnPlan.size)
        assertEquals(pcdn, pcdnPlan[0].url)
    }

    @Test
    fun testNoConfiguredHostsFallsBackToTheOriginal() {
        assertEquals(1, DownloadSourcePlan.plan(uposUrl, emptyList()).size)
        assertEquals(uposUrl, DownloadSourcePlan.plan(uposUrl, emptyList())[0].url)
        assertTrue(DownloadSourcePlan.plan("", listOf("upos-sz-mirrorcos.bilivideo.com")).isEmpty())
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')
}
