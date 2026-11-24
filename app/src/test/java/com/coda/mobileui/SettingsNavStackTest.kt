package com.coda.mobileui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置子页返回栈的判定（#612）。
 *
 * 之前 goBack() 只认 providers_kind 一层，其他页内前进（如 Coda Bot → 供应商列表）
 * 按返回会直接 finish 整个设置页。这里把「回哪一层」抽成纯逻辑先钉住行为。
 */
class SettingsNavStackTest {
    @Test
    fun emptyStackMeansRoot() {
        val nav = SettingsNavStack("system")
        assertTrue(nav.atRoot)
        assertNull(nav.pop())
    }

    @Test
    fun pushThenPopReturnsPreviousPage() {
        val nav = SettingsNavStack("coda_bot_chat")
        nav.push("providers")
        assertFalse(nav.atRoot)
        assertEquals("providers", nav.current)
        assertEquals("coda_bot_chat", nav.pop())
        assertTrue(nav.atRoot)
    }

    @Test
    fun multiLevelPopUnwindsInOrder() {
        val nav = SettingsNavStack("system")
        nav.push("providers")
        nav.push("providers_kind")
        assertEquals("providers", nav.pop())
        assertEquals("system", nav.pop())
        assertNull(nav.pop())
        assertEquals("system", nav.current)
    }

    @Test
    fun pushingSamePageDoesNotGrowStack() {
        val nav = SettingsNavStack("providers")
        nav.push("providers")
        // 同页重入（比如刷新后再渲染一次）不该造出一层假的历史。
        assertTrue(nav.atRoot)
        assertNull(nav.pop())
    }


    @Test
    fun popOnRootKeepsCurrentUnchanged() {
        val nav = SettingsNavStack("system")
        assertNull(nav.pop())
        assertEquals("system", nav.current)
    }

    @Test
    fun restoreRebuildsStackFromSavedTrail() {
        val nav = SettingsNavStack("system")
        nav.push("providers")
        nav.push("providers_kind")
        val saved = nav.trail()
        val restored = SettingsNavStack.restore(saved, fallback = "system")
        assertEquals("providers_kind", restored.current)
        assertEquals("providers", restored.pop())
        assertEquals("system", restored.pop())
    }

    @Test
    fun restoreOfEmptyTrailFallsBack() {
        val restored = SettingsNavStack.restore(emptyList(), fallback = "system")
        assertEquals("system", restored.current)
        assertTrue(restored.atRoot)
    }

    @Test
    fun restoreDropsConsecutiveDuplicates() {
        // 旧版本存过的串可能带重复项，恢复时不该把它们当成真实层级。
        val restored = SettingsNavStack.restore(listOf("system", "system", "providers"), fallback = "system")
        assertEquals("providers", restored.current)
        assertEquals("system", restored.pop())
        assertTrue(restored.atRoot)
    }

    @Test
    fun trailRoundTripsThroughString() {
        val nav = SettingsNavStack("system")
        nav.push("providers")
        nav.push("providers_kind")
        val encoded = SettingsNavStack.encode(nav.trail())
        val decoded = SettingsNavStack.decode(encoded)
        assertEquals(nav.trail(), decoded)
    }

    @Test
    fun decodeOfBlankIsNullSafe() {
        assertTrue(SettingsNavStack.decode(null).isEmpty())
        assertTrue(SettingsNavStack.decode("").isEmpty())
    }
}
