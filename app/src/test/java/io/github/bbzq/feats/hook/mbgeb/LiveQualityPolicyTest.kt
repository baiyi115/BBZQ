package io.github.bbzq.feats.hook.mbgeb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQualityPolicyTest {

    @Test
    fun testPathMatching() {
        assertTrue(
            LiveQualityPolicy.isLivePlayInfoPath("/xlive/app-room/v2/index/getRoomPlayInfo"),
        )
        assertFalse(LiveQualityPolicy.isLivePlayInfoPath("/xlive/app-room/v1/index/getRoomPlayInfo"))
        assertFalse(LiveQualityPolicy.isLivePlayInfoPath("/x/player/playurl"))
        assertFalse(LiveQualityPolicy.isLivePlayInfoPath(""))
    }

    @Test
    fun testRaisesLowerTiersToOriginal() {
        // 流畅 / 高清 / 超清 / 蓝光 都低于原画
        assertEquals(LiveQualityPolicy.ORIGINAL_QN, LiveQualityPolicy.raiseToOriginal(80))
        assertEquals(LiveQualityPolicy.ORIGINAL_QN, LiveQualityPolicy.raiseToOriginal(150))
        assertEquals(LiveQualityPolicy.ORIGINAL_QN, LiveQualityPolicy.raiseToOriginal(250))
        assertEquals(LiveQualityPolicy.ORIGINAL_QN, LiveQualityPolicy.raiseToOriginal(400))
    }

    @Test
    fun testDoesNotLowerHigherTiers() {
        // 用户已经选了原画 / 4K / 杜比：不动
        assertNull(LiveQualityPolicy.raiseToOriginal(LiveQualityPolicy.ORIGINAL_QN))
        assertNull(LiveQualityPolicy.raiseToOriginal(20000))
        assertNull(LiveQualityPolicy.raiseToOriginal(30000))
    }

    @Test
    fun testMissingQnIsRaised() {
        // 请求里没有 qn：正是最需要补上的情况
        assertEquals(LiveQualityPolicy.ORIGINAL_QN, LiveQualityPolicy.raiseToOriginal(null))
    }
}
