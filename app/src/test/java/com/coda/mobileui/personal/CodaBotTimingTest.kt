package com.coda.mobileui.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批 TTL 调度与阻塞等待的超时语义。
 *
 * #158：定时器必须按「最早截止时间」排，而不是按「最近一次请求」排，
 * 否则请求越密集，最早那条越晚被清理。
 * #157：超时不等于失败——底层操作还在跑，必须以「已提交、结果待定」返回，
 * 否则调用方重试会重复发消息、重复切模型。
 */
class CodaBotTimingTest {
    @Test
    fun nextDelayUsesEarliestDeadline() {
        // A 在 t=0 到达、B 在 t=9000 到达，TTL=10000。
        // 在 t=9000 时下一次清理应在 1000ms 后（A 到期），而不是 10000ms 后。
        val delay = CodaBotTiming.nextExpiryDelay(listOf(0L, 9_000L), now = 9_000L, ttlMs = 10_000L)
        assertEquals(1_000L, delay)
    }

    @Test
    fun nextDelayIsZeroWhenAlreadyExpired() {
        assertEquals(0L, CodaBotTiming.nextExpiryDelay(listOf(0L), now = 20_000L, ttlMs = 10_000L))
    }

    @Test
    fun nextDelayIsNullWhenNothingPending() {
        assertNull(CodaBotTiming.nextExpiryDelay(emptyList(), now = 1_000L, ttlMs = 10_000L))
    }

    @Test
    fun nextDelayNeverNegative() {
        val delay = CodaBotTiming.nextExpiryDelay(listOf(-50_000L, 0L), now = 100_000L, ttlMs = 10_000L)
        assertEquals(0L, delay)
    }

    @Test
    fun denseRequestsDoNotStarveTheOldest() {
        // 每 1 秒来一条，最早那条仍必须在 TTL 到点时被清理。
        val arrivals = (0..9).map { it * 1_000L }
        val delay = CodaBotTiming.nextExpiryDelay(arrivals, now = 9_000L, ttlMs = 10_000L)
        assertTrue("最早请求不能被无限推迟", delay <= 1_000L)
    }

    @Test
    fun singleRequestSchedulesFullTtl() {
        assertEquals(10_000L, CodaBotTiming.nextExpiryDelay(listOf(5_000L), now = 5_000L, ttlMs = 10_000L))
    }

    @Test
    fun timeoutIsReportedAsSubmittedNotFailed() {
        val out = CodaBotTiming.waitOutcome(timedOut = true, ok = false, detail = "", action = "send")
        assertFalse("超时不能报成失败，否则调用方会重试", out.ok)
        assertTrue(out.pending)
        assertTrue(out.message.contains("已提交"))
        assertTrue(out.message.contains("不要重试"))
    }

    @Test
    fun completedCallbackIsNotPending() {
        val out = CodaBotTiming.waitOutcome(timedOut = false, ok = true, detail = "已发送", action = "send")
        assertTrue(out.ok)
        assertFalse(out.pending)
        assertEquals("已发送", out.message)
    }

    @Test
    fun realFailureStaysFailureAndIsRetryable() {
        val out = CodaBotTiming.waitOutcome(timedOut = false, ok = false, detail = "会话不存在", action = "send")
        assertFalse(out.ok)
        assertFalse(out.pending)
        assertEquals("会话不存在", out.message)
    }

    @Test
    fun lateCallbackAfterTimeoutIsIgnored() {
        val gate = CodaBotTiming.Gate()
        assertTrue(gate.claim())
        // 超时后关闭闸门：迟到的回调不能再写回结果。
        gate.close()
        assertFalse(gate.claim())
    }

    @Test
    fun onlyFirstClaimWins() {
        val gate = CodaBotTiming.Gate()
        assertTrue(gate.claim())
        assertFalse("重复回调只能生效一次", gate.claim())
    }

    @Test
    fun timeoutMessageNamesTheAction() {
        val out = CodaBotTiming.waitOutcome(timedOut = true, ok = false, detail = "", action = "close")
        assertTrue(out.message.contains("close"))
    }
}