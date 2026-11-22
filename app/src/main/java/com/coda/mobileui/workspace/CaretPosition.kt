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
        /** 一次遍历同时得到总行数与光标所在行列；caret 越界会被夹到合法范围。 */
        fun of(
            text: CharSequence,
            caret: Int,
        ): CaretPosition {
            val pos = caret.coerceIn(0, text.length)
            var line = 1
            var lineStart = 0
            var total = 1
            var i = 0
            while (i < text.length) {
                if (text[i] == '\n') {
                    total++
                    if (i < pos) {
                        line++
                        lineStart = i + 1
                    }
                }
                i++
            }
            return CaretPosition(line, pos - lineStart + 1, total)
        }
    }
}