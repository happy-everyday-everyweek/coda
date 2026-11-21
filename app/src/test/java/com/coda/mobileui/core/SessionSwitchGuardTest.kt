package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话切换的代际校验。
 *
 * 场景：当前打开 A，用户点冷会话 B（要先恢复），恢复期间用户又点了热会话 C。
 * B 的订阅回调晚到，不能把界面切回 B。
 */
class SessionSwitchGuardTest {
    @Test
    fun `latest open wins`() {
        val guard = SessionSwitchGuard()
        val b = guard.begin("B")
        val c = guard.begin("C")
        assertFalse(guard.isCurrent(b))
        assertTrue(guard.isCurrent(c))
        assertEquals("C", guard.currentTarget())
    }

    @Test
    fun `stale messages for the previous session are rejected`() {
        val guard = SessionSwitchGuard()
        guard.begin("B")
        guard.begin("C")
        assertFalse(guard.accepts("B"))
        assertTrue(guard.accepts("C"))
    }

    @Test
    fun `reopening the same session invalidates the earlier attempt`() {
        val guard = SessionSwitchGuard()
        val first = guard.begin("A")
        val second = guard.begin("A")
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `before any open everything is accepted`() {
        val guard = SessionSwitchGuard()
        assertTrue(guard.accepts("A"))
        assertEquals(null, guard.currentTarget())
    }
}