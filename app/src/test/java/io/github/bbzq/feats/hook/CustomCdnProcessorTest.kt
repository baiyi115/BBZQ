package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCdnProcessorTest {

    @Test
    fun testIsPCdn() {
        // PCDN 網域名稱應被判定為 true
        assertTrue(CustomCdnProcessor.isPCdn("https://szbdyd.bilivideo.com/live-bvc/123.flv"))
        assertTrue(CustomCdnProcessor.isPCdn("http://mcdn.bilivideo.cn:8080/p2p/segment.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://p-node1.bilivideo.com/video.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://m-node2.bilivideo.com/video.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://p1-dx-bilivideo.szbdyd.com/video.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("http://sz-node1-bilivideo.com/video.m4s"))

        // 常規官方/雲廠商 CDN 網域名稱應判定為 false
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/123.mp4"))
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/123.mp4"))
        assertFalse(CustomCdnProcessor.isPCdn("https://cn-hk-eq-bcache-01.bilivideo.com/upgcxcode/123.mp4"))
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-hz-mirrorakam.akamaized.net/upgcxcode/123.mp4"))
    }

    @Test
    fun testIsPCdnMissedPcdnHosts() {
        // HTTP 302 型 PCDN：主機名符合 upos 鏡像形狀，但對不上 PC_DN_REGEX
        assertTrue(CustomCdnProcessor.isPCdn("https://upos-sz-302ppio.bilivideo.com/upgcxcode/1.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://upos-sz-302kodo.bilivideo.com/upgcxcode/1.m4s"))

        // 302 型 PCDN 的真實落點
        assertTrue(CustomCdnProcessor.isPCdn("https://edge.nexusedgeio.com/upgcxcode/1.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://edge.ahdohpiechei.com/upgcxcode/1.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://nexusedgeio.com/upgcxcode/1.m4s"))

        // 偽裝成 upos 鏡像的 PCDN（憑證簽的是 *.bilivideo.cn）
        assertTrue(CustomCdnProcessor.isPCdn("https://upos-sz-mirror14b.bilivideo.com/upgcxcode/1.m4s"))

        // 裸 IPv4 直出的 PCDN 節點
        assertTrue(CustomCdnProcessor.isPCdn("http://1.2.3.4:8080/v1/resource/1.m4s"))
        assertTrue(CustomCdnProcessor.isPCdn("https://10.0.0.1/upgcxcode/1.m4s"))
    }

    @Test
    fun testIsPCdnDoesNotOvermatch() {
        // 補漏規則不得誤傷正常鏡像主機
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-sz-mirroraliov.bilivideo.com/upgcxcode/1.m4s"))
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/1.m4s"))
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-sz-mirror14.bilivideo.com/upgcxcode/1.m4s"))
        assertFalse(CustomCdnProcessor.isPCdn("https://upos-tf-all-hw.bilivideo.com/upgcxcode/1.m4s"))
        assertFalse(CustomCdnProcessor.isPCdn("https://cn-hk-eq-01-01.bilivideo.com/upgcxcode/1.m4s"))
        // 非 CDN 主機
        assertFalse(CustomCdnProcessor.isPCdn("https://www.bilibili.com/video/BV1xx"))
        assertFalse(CustomCdnProcessor.isPCdn(""))
    }

    @Test
    fun testHostOf() {
        assertEquals("upos-sz-mirrorali.bilivideo.com", CustomCdnProcessor.hostOf("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1.m4s?e=1"))
        assertEquals("1.2.3.4", CustomCdnProcessor.hostOf("http://1.2.3.4:8080/v/1"))
        assertEquals("upos-sz-mirrorali.bilivideo.com", CustomCdnProcessor.hostOf("//upos-sz-mirrorali.bilivideo.com/upgcxcode/1.m4s"))
        assertEquals("", CustomCdnProcessor.hostOf(""))
    }

    @Test
    fun testReplaceHost() {
        val targetHost = "upos-sz-mirrorcos.bilivideo.com"

        val originalHttps = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/45/67/12345/12345.m4s?e=123&deadline=456"
        val expectedHttps = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/45/67/12345/12345.m4s?e=123&deadline=456"
        assertEquals(expectedHttps, CustomCdnProcessor.replaceHost(originalHttps, targetHost))

        val originalHttp = "http://upos-sz-mirrorhw.bilivideo.com:8080/upgcxcode/video.mp4"
        val expectedHttp = "http://upos-sz-mirrorcos.bilivideo.com/upgcxcode/video.mp4"
        assertEquals(expectedHttp, CustomCdnProcessor.replaceHost(originalHttp, targetHost))

        val rootUrl = "https://upos-sz-mirrorali.bilivideo.com"
        val expectedRoot = "https://upos-sz-mirrorcos.bilivideo.com"
        assertEquals(expectedRoot, CustomCdnProcessor.replaceHost(rootUrl, targetHost))
    }
}
