package com.coda.mobileui.workspace

/**
 * diff 渲染预算。
 *
 * diff 视图是同步构建的：先 `split` 整个文本，再为每个文件建一张卡片。
 * 几百个文件、或一个巨大的生成文件没有 `diff --git` 分隔时，会在主线程一次性铺满视图树。
 * 这里先按行数与字节数截断文本，再限制卡片数量，保证单次渲染有上界。
 */
internal object DiffBudget {
    /** 单次渲染最多处理的 diff 行数。 */
    const val MAX_DIFF_LINES = 4000

    /** 单次渲染最多处理的字节数：避免把一个几十 MB 的 diff 读进内存再切分。 */
    const val MAX_DIFF_BYTES = 1_500_000

    /** 单次渲染最多生成的文件夹卡片数。 */
    const val MAX_FILES = 60

    /** 截断后的文本，以及是否发生了截断。 */
    data class TrimmedText(
        val text: String,
        val truncated: Boolean,
    )

    /** 可渲染的文件数与被省略的文件数。 */
    data class VisibleFiles(
        val visible: Int,
        val omitted: Int,
    )

    /** 把文本截到预算内：先看字节，再看行数，两者任一超限都算截断。 */
    fun trimText(text: String): TrimmedText {
        if (text.length <= MAX_DIFF_BYTES) {
            val lineCount = text.count { it == '\n' } + 1
            if (lineCount <= MAX_DIFF_LINES) return TrimmedText(text, false)
            return TrimmedText(text.lineSequence().take(MAX_DIFF_LINES).joinToString("\n"), true)
        }
        val capped = text.substring(0, MAX_DIFF_BYTES)
        val lines = capped.split('\n')
        if (lines.size <= MAX_DIFF_LINES) return TrimmedText(capped, true)
        return TrimmedText(lines.take(MAX_DIFF_LINES).joinToString("\n"), true)
    }

    /** 卡片数量上限：超出的文件不建视图，只报个数。 */
    fun visibleFiles(total: Int): VisibleFiles {
        if (total <= MAX_FILES) return VisibleFiles(total, 0)
        return VisibleFiles(MAX_FILES, total - MAX_FILES)
    }
}
