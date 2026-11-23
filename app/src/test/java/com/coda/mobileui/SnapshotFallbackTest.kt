package com.coda.mobileui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预热失败时的空快照（#751）。
 *
 * 只打日志不落结果，hasSnapshot 会永远为 false，
 * ensureSnapshot → prewarm → onDone → renderPage → ensureSnapshot
 * 变成没有退避的死循环，页面却一直停在「正在读取」。
 */
class SnapshotFallbackTest {
    @Test
    fun `providers 失败落空列表`() {
        val (k, v) = SnapshotFallback.emptyFor("providers", null)!!
        assertEquals("providers", k)
        assertTrue((v as List<*>).isEmpty())
    }

    @Test
    fun `assets 失败按原键落空列表`() {
        val (k, v) = SnapshotFallback.emptyFor("assets:SKILL", null)!!
        assertEquals("assets:SKILL", k)
        assertTrue((v as List<*>).isEmpty())
    }

    @Test
    fun `automations 失败落空列表`() {
        val (k, v) = SnapshotFallback.emptyFor("automations", null)!!
        assertEquals("automations", k)
        assertTrue((v as List<*>).isEmpty())
    }

    @Test
    fun `sysprompts 失败落空表`() {
        val (k, v) = SnapshotFallback.emptyFor("sysprompts", null)!!
        assertEquals("sysprompts", k)
        assertTrue((v as Map<*, *>).isEmpty())
    }

    @Test
    fun `hooks 失败按工作区路径落键`() {
        // 键必须与 hasSnapshot 查的一致，否则落了也白落。
        val (k, _) = SnapshotFallback.emptyFor("hooks", "/sdcard/Coda/workspace")!!
        assertEquals("hooks:/sdcard/Coda/workspace", k)
    }

    @Test
    fun `hooks 带路径的键原样保留`() {
        val (k, _) = SnapshotFallback.emptyFor("hooks:/a/b", "/other")!!
        assertEquals("hooks:/a/b", k)
    }

    @Test
    fun `hooks 没有工作区时不落键`() {
        // 没有路径就无从构造键，落一个错的键只会掩盖问题。
        assertNull(SnapshotFallback.emptyFor("hooks", null))
    }

    @Test
    fun `未知键不落快照`() {
        assertNull(SnapshotFallback.emptyFor("unknown", null))
    }

    @Test
    fun `空快照不是 null`() {
        // 落了 null 等于没落：hasSnapshot 仍判 false。
        val (_, v) = SnapshotFallback.emptyFor("providers", null)!!
        assertNotNull(v)
    }

    @Test
    fun `失败原因可查询可清除`() {
        // OCR medium：非 hooks 键的空快照与「真的没配置」不可区分，
        // 页面构建器要靠 reasonFor 渲染错误行。
        SnapshotFallback.emptyFor("providers", null, "JSON 损坏")
        assertEquals("JSON 损坏", SnapshotFallback.reasonFor("providers"))
        SnapshotFallback.clearReason("providers")
        assertNull(SnapshotFallback.reasonFor("providers"))
    }
}
