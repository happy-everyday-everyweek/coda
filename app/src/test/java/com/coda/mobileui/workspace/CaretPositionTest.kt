package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 光标行列与逻辑行数的统计（#514）。
 *
 * 状态栏按 `\n` 数算逻辑行，不能用 EditText.lineCount（那是自动换行后的视觉行）。
 * 抽成纯函数后，边界（空文本、光标在换行符上、末尾换行）都能直接断言。
 */
class CaretPositionTest {
    @Test
    fun emptyTextIsLineOneColumnOne() {
        val p = CaretPosition.of("", 0)
        assertEquals(1, p.line)
        assertEquals(1, p.column)
        assertEquals(1, p.totalLines)
    }

    @Test
    fun singleLineNoNewline() {
        val p = CaretPosition.of("hello", 3)
        assertEquals(1, p.line)
        assertEquals(4, p.column)
        assertEquals(1, p.totalLines)
    }

    @Test
    fun caretAfterNewlineStartsNextLine() {
        val p = CaretPosition.of("ab\ncd", 3)
        assertEquals(2, p.line)
        assertEquals(1, p.column)
        assertEquals(2, p.totalLines)
    }

    @Test
    fun caretRightBeforeNewlineStaysOnCurrentLine() {
        val p = CaretPosition.of("ab\ncd", 2)
        assertEquals(1, p.line)
        assertEquals(3, p.column)
    }

    @Test
    fun trailingNewlineCountsAsAnExtraLine() {
        // 与「转到行」的口径一致：末尾换行后还有一个空行。
        assertEquals(2, CaretPosition.of("ab\n", 0).totalLines)
        // 光标落在末尾换行之后，就是那个空行的行首。
        assertEquals(2, CaretPosition.of("ab\n", 3).line)
        assertEquals(1, CaretPosition.of("ab\n", 3).column)
    }

    @Test
    fun multipleLines() {
        val text = "a\nbb\nccc\nd"
        val p = CaretPosition.of(text, 7)
        assertEquals(3, p.line)
        assertEquals(3, p.column)
        assertEquals(4, p.totalLines)
    }

    @Test
    fun negativeCaretIsClamped() {
        val p = CaretPosition.of("abc", -5)
        assertEquals(1, p.line)
        assertEquals(1, p.column)
    }

    @Test
    fun caretBeyondLengthIsClamped() {
        val p = CaretPosition.of("abc", 999)
        assertEquals(1, p.line)
        assertEquals(4, p.column)
    }

    @Test
    fun consecutiveNewlinesCountEachLine() {
        val p = CaretPosition.of("\n\n\n", 2)
        assertEquals(3, p.line)
        assertEquals(1, p.column)
        assertEquals(4, p.totalLines)
    }

    @Test
    fun crlfCountsLogicalLinesByNewline() {
        // 只按 \n 计：与 gotoLine 保持同一口径，列号里会含 \r，这是既有行为。
        val p = CaretPosition.of("a\r\nb", 3)
        assertEquals(2, p.line)
        assertEquals(1, p.column)
        assertEquals(2, p.totalLines)
    }
}