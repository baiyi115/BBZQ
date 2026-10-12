package io.github.bbzq.feats.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnAddressPolicyTest {

    private val uposUrl =
        "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/2/3.m4s?upsig=abc123&deadline=2000000000&e=1"
    private val akamaiTokenUrl =
        "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/1/2/3.m4s?hdnts=exp=2000000000~acl=/*~hmac=deadbeef"

    @Test
    fun testSignatureFamily() {
        assertEquals(CdnAddressPolicy.SignatureFamily.Upos, CdnAddressPolicy.signatureFamilyOf(uposUrl))
        assertEquals(CdnAddressPolicy.SignatureFamily.Akamai, CdnAddressPolicy.signatureFamilyOf(akamaiTokenUrl))
        // Akamai 主机上没有 upsig 也没有 hdnts：按家族算 Akamai
        assertEquals(
            CdnAddressPolicy.SignatureFamily.Akamai,
            CdnAddressPolicy.signatureFamilyOf("https://upos-hz-mirrorakam.akamaized.net/upgcxcode/1.m4s"),
        )
        // Akamai 主机上带 upsig：仍是 upos 签名
        assertEquals(
            CdnAddressPolicy.SignatureFamily.Upos,
            CdnAddressPolicy.signatureFamilyOf("https://upos-hz-mirrorakam.akamaized.net/upgcxcode/1.m4s?upsig=abc"),
        )
    }

    @Test
    fun testCanHostServe() {
        val akamaiHost = "upos-hz-mirrorakam.akamaized.net"
        val uposHost = "upos-sz-mirrorcos.bilivideo.com"

        // 核心规则：upos 地址不能换到 Akamai（会 403）
        assertFalse(CdnAddressPolicy.canHostServe(akamaiHost, uposUrl))
        // Akamai 承载 Akamai 地址没问题
        assertTrue(CdnAddressPolicy.canHostServe(akamaiHost, akamaiTokenUrl))
        // 其它主机一律放行
        assertTrue(CdnAddressPolicy.canHostServe(uposHost, uposUrl))
        assertTrue(CdnAddressPolicy.canHostServe(uposHost, akamaiTokenUrl))
    }

    @Test
    fun testUsableHostsFiltersAkamai() {
        val hosts = listOf(
            "upos-hz-mirrorakam.akamaized.net",
            "upos-sz-mirrorcos.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
        )
        assertEquals(
            listOf("upos-sz-mirrorcos.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com"),
            CdnAddressPolicy.usableHosts(hosts, uposUrl),
        )
        assertEquals(hosts, CdnAddressPolicy.usableHosts(hosts, akamaiTokenUrl))
    }

    @Test
    fun testDeadlineOf() {
        // upos 的 deadline 参数
        assertEquals(2000000000L, CdnAddressPolicy.deadlineOf(uposUrl))
        // Akamai 的 hdnts exp 字段
        assertEquals(2000000000L, CdnAddressPolicy.deadlineOf(akamaiTokenUrl))
        // 两者都有时取更早的那个
        assertEquals(
            1500000000L,
            CdnAddressPolicy.deadlineOf(
                "https://a.bilivideo.com/x.m4s?deadline=2000000000&hdnts=exp=1500000000~hmac=1",
            ),
        )
        assertEquals(
            1500000000L,
            CdnAddressPolicy.deadlineOf(
                "https://a.bilivideo.com/x.m4s?deadline=1500000000&hdnts=exp=2000000000~hmac=1",
            ),
        )
        // 都没有则未知
        assertEquals(0L, CdnAddressPolicy.deadlineOf("https://a.bilivideo.com/x.m4s?e=1"))
        // 非法值不应被当成有效时间
        assertEquals(0L, CdnAddressPolicy.deadlineOf("https://a.bilivideo.com/x.m4s?deadline=abc"))
        assertEquals(0L, CdnAddressPolicy.deadlineOf("https://a.bilivideo.com/x.m4s?deadline=0"))
    }

    @Test
    fun testExpiry() {
        val now = 1_700_000_000L

        assertFalse(CdnAddressPolicy.isExpired("https://a.bilivideo.com/x.m4s?deadline=1700001000", now))
        assertTrue(CdnAddressPolicy.isExpired("https://a.bilivideo.com/x.m4s?deadline=1699999000", now))
        assertTrue(CdnAddressPolicy.isExpired("https://a.bilivideo.com/x.m4s?deadline=1700000000", now))

        // 未知失效时间：不视为过期，也不视为即将过期
        assertFalse(CdnAddressPolicy.isExpired("https://a.bilivideo.com/x.m4s", now))
        assertFalse(CdnAddressPolicy.isExpiringSoon("https://a.bilivideo.com/x.m4s", now))
        assertTrue(CdnAddressPolicy.isUsable("https://a.bilivideo.com/x.m4s", now))

        // 余量阈值
        assertTrue(CdnAddressPolicy.isExpiringSoon("https://a.bilivideo.com/x.m4s?deadline=1700000030", now))
        assertFalse(CdnAddressPolicy.isExpiringSoon("https://a.bilivideo.com/x.m4s?deadline=1700000900", now))
        assertTrue(CdnAddressPolicy.isUsable("https://a.bilivideo.com/x.m4s?deadline=1700000900", now))
        assertFalse(CdnAddressPolicy.isUsable("https://a.bilivideo.com/x.m4s?deadline=1700000030", now))
    }
}
