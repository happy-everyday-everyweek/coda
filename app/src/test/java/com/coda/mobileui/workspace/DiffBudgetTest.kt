package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * diff 渲染预算。
 *
 * 渲染是同步的，几百个文件或一个巨大的 lock 文件就能让主线程卡住甚至 OOM，
 * 所以先在文本层面按行数与字节数截断，再限制卡片数量。
 */
class DiffBudgetTest {
    @Test
    fun `short diff is kept as is`() {
        val result = DiffBudget.trimText("a\nb\nc")
        assertFalse(result.truncated)
        assertEquals("a\nb\nc", result.text)
    }

    @Test
    fun `too many lines are cut to the line budget`() {
        val text = (1..(DiffBudget.MAX_DIFF_LINES + 500)).joinToString("\n") { "line$it" }
        val result = DiffBudget.trimText(text)
        assertTrue(result.truncated)
        assertEquals(DiffBudget.MAX_DIFF_LINES, result.text.split('\n').size)
    }

    @Test
    fun `too many bytes are cut to the byte budget`() {
        val text = "x".repeat(DiffBudget.MAX_DIFF_BYTES + 1)
        val result = DiffBudget.trimText(text)
        assertTrue(result.truncated)
        assertTrue(result.text.length <= DiffBudget.MAX_DIFF_BYTES)
    }

    @Test
    fun `empty diff is not truncated`() {
        assertFalse(DiffBudget.trimText("").truncated)
    }

    @Test
    fun `file count over budget reports the omitted ones`() {
        val visible = DiffBudget.visibleFiles(DiffBudget.MAX_FILES + 7)
        assertEquals(DiffBudget.MAX_FILES, visible.visible)
        assertEquals(7, visible.omitted)
    }

    @Test
    fun `file count under budget omits nothing`() {
        val visible = DiffBudget.visibleFiles(3)
        assertEquals(3, visible.visible)
        assertEquals(0, visible.omitted)
    }
}