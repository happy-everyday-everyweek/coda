package com.coda.mobileui.workspace

/**
 * 光标在文本里的逻辑位置。
 *
 * 从 FilesPage 的状态栏逻辑抽出来：只按 `\n` 计行，与「转到行」同一口径，
 * 不能用 EditText.lineCount（自动换行后那是视觉行数）。
 */
data class CaretPosition(
    val line: Int,
    val column: Int,
    val totalLines: Int,
) {
    companion object {
        /**
         * 光标所在行列；caret 越界会被夹到合法范围。
         *
         * [totalLines] 由调用方提供（OCR #821）：每次按键/移动光标都全文
         * 扫描只为算总行数，2MB 文件上是 O(n) 卡顿。调用方在文本变化时
         * 增量维护总行数，光标移动只扫到 caret（O(caret)）。
         */
        fun of(
            text: CharSequence,
            caret: Int,
            totalLines: Int,
        ): CaretPosition {
            val pos = caret.coerceIn(0, text.length)
            var line = 1
            var lineStart = 0
            var i = 0
            while (i < pos) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
                i++
            }
            return CaretPosition(line, pos - lineStart + 1, totalLines)
        }

        /** 低频便捷版：全文扫一次算总行数。热路径（每次按键）用三参版。 */
        fun of(
            text: CharSequence,
            caret: Int,
        ): CaretPosition {
            var total = 1
            for (ch in text) if (ch == '\n') total++
            return of(text, caret, total)
        }
    }
}