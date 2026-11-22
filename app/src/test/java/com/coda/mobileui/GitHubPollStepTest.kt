package com.coda.mobileui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 设备码轮询的下一步判定（#608）。
 *
 * 原来 slow_down 只加大间隔、不递减次数，attempts 永远耗不尽，
 * 于是「正在等待授权」可以无限转下去，绕过 expiresIn。
 * 另外回调返回时页面可能已销毁，需要一个显式的存活判定。
 */
class GitHubPollStepTest {
    @Test
    fun pendingDecrementsAndSchedules() {
        val s = GitHubPollStep.of("pending", attempts = 3, delayMs = 5000, alive = true)
        assertEquals(2, s.attempts)
        assertEquals(5000L, s.delayMs)
        assertTrue(s.schedule)
        assertFalse(s.stop)
    }

    @Test
    fun slowDownAlsoDecrements() {
        val s = GitHubPollStep.of("slow_down", attempts = 3, delayMs = 5000, alive = true)
        // 关键回归点：slow_down 必须消耗一次配额，否则永远不会超时。
        assertEquals(2, s.attempts)
        assertEquals(10000L, s.delayMs)
        assertTrue(s.schedule)
    }

    @Test
    fun lastAttemptStopsInsteadOfScheduling() {
        val s = GitHubPollStep.of("pending", attempts = 1, delayMs = 5000, alive = true)
        assertEquals(0, s.attempts)
        assertTrue(s.stop)
        assertFalse(s.schedule)
        assertTrue(s.timedOut)
    }

    @Test
    fun slowDownOnLastAttemptStops() {
        val s = GitHubPollStep.of("slow_down", attempts = 1, delayMs = 5000, alive = true)
        assertTrue(s.stop)
        assertFalse(s.schedule)
        assertTrue(s.timedOut)
    }

    @Test
    fun deadPollerNeverReschedules() {
        // 页面已销毁 / 用户已取消：即使还有配额也不能再挂回 Handler。
        val s = GitHubPollStep.of("pending", attempts = 9, delayMs = 5000, alive = false)
        assertTrue(s.stop)
        assertFalse(s.schedule)
        assertFalse(s.timedOut)
    }

    @Test
    fun deadPollerOnSlowDownDoesNotReschedule() {
        val s = GitHubPollStep.of("slow_down", attempts = 9, delayMs = 5000, alive = false)
        assertFalse(s.schedule)
        assertTrue(s.stop)
    }

    @Test
    fun okStopsWithoutTimeout() {
        val s = GitHubPollStep.of("ok", attempts = 5, delayMs = 5000, alive = true)
        assertTrue(s.stop)
        assertFalse(s.schedule)
        assertFalse(s.timedOut)
    }

    @Test
    fun errorStopsWithoutTimeout() {
        val s = GitHubPollStep.of("error", attempts = 5, delayMs = 5000, alive = true)
        assertTrue(s.stop)
        assertFalse(s.timedOut)
    }

    @Test
    fun unknownStatusStops() {
        val s = GitHubPollStep.of("weird", attempts = 5, delayMs = 5000, alive = true)
        assertTrue(s.stop)
        assertFalse(s.schedule)
    }

    @Test
    fun attemptsNeverGoNegative() {
        val s = GitHubPollStep.of("pending", attempts = 0, delayMs = 5000, alive = true)
        assertEquals(0, s.attempts)
        assertTrue(s.stop)
    }

    @Test
    fun slowDownDelayGrowsByFiveSeconds() {
        val s = GitHubPollStep.of("slow_down", attempts = 4, delayMs = 12000, alive = true)
        assertEquals(17000L, s.delayMs)
    }

    @Test
    fun repeatedSlowDownEventuallyTimesOut() {
        // 端到端：一直 slow_down 也必须在有限步内停下来。
        var attempts = 4
        var delay = 5000L
        var steps = 0
        while (steps < 50) {
            val s = GitHubPollStep.of("slow_down", attempts, delay, alive = true)
            attempts = s.attempts
            delay = s.delayMs
            steps++
            if (s.stop) break
        }
        assertEquals(4, steps)
        assertEquals(0, attempts)
    }
}