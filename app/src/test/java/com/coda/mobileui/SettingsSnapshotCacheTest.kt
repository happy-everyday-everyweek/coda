package com.coda.mobileui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页读缓存的行为（#615）。
 *
 * 页面构建器改成只读快照后，缓存的「新鲜/过期/作废」判定就成了正确性关键：
 * 判错会让用户在增删供应商后仍看到旧列表。
 */
class SettingsSnapshotCacheTest {
    @Test
    fun missReturnsNull() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        assertNull(cache.get<String>("providers", now = 0))
    }

    @Test
    fun hitWithinTtl() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("providers", listOf("a"), now = 100)
        assertEquals(listOf("a"), cache.get<List<String>>("providers", now = 500))
    }

    @Test
    fun exactlyAtTtlIsStillFresh() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("k", "v", now = 0)
        assertEquals("v", cache.get<String>("k", now = 1000))
    }

    @Test
    fun pastTtlIsStale() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("k", "v", now = 0)
        assertNull(cache.get<String>("k", now = 1001))
    }

    @Test
    fun putRefreshesTimestamp() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("k", "old", now = 0)
        cache.put("k", "new", now = 900)
        assertEquals("new", cache.get<String>("k", now = 1500))
    }

    @Test
    fun invalidateDropsEntry() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("k", "v", now = 0)
        cache.invalidate("k")
        assertNull(cache.get<String>("k", now = 1))
    }

    @Test
    fun invalidatePrefixDropsWholeFamily() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("providers", "root", now = 0)
        cache.put("providers_kind:LLM", "llm", now = 0)
        cache.put("providers_kind:ASR", "asr", now = 0)
        cache.put("hooks", "h", now = 0)
        cache.invalidatePrefix("providers")
        assertNull(cache.get<String>("providers", now = 1))
        assertNull(cache.get<String>("providers_kind:LLM", now = 1))
        assertNull(cache.get<String>("providers_kind:ASR", now = 1))
        // 不同前缀不受影响。
        assertEquals("h", cache.get<String>("hooks", now = 1))
    }

    @Test
    fun invalidatePrefixWithNoMatchIsSafe() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("hooks", "h", now = 0)
        cache.invalidatePrefix("nothing")
        assertEquals("h", cache.get<String>("hooks", now = 1))
    }

    @Test
    fun clearDropsEverything() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("a", 1, now = 0)
        cache.put("b", 2, now = 0)
        cache.clear()
        assertNull(cache.get<Int>("a", now = 1))
        assertNull(cache.get<Int>("b", now = 1))
    }

    @Test
    fun keysAreIndependent() {
        val cache = SettingsSnapshotCache(ttlMs = 1000)
        cache.put("assets:SKILL", listOf("s"), now = 0)
        cache.put("assets:COMMAND", listOf("c"), now = 0)
        assertEquals(listOf("s"), cache.get<List<String>>("assets:SKILL", now = 1))
        assertEquals(listOf("c"), cache.get<List<String>>("assets:COMMAND", now = 1))
    }

    @Test
    fun defaultTtlIsPositive() {
        assertTrue(SettingsSnapshotCache.DEFAULT_TTL_MS > 0)
    }
}