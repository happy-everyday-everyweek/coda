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
        // 按 UTF-8 字节而不是 UTF-16 码元（OCR #821）：CJK/emoji 下 length
        // 只有实际字节的 1/3~1/4，内存上界形同虚设。ASCII 快速路径保留。
        // 逐码点数字节（OCR r10）：toByteArray 会在主线程渲染路径上
        // 为量尺寸复制整份 diff（几十 MB），数一遍就够了。
        val byteLen = utf8Length(text)
        if (byteLen <= MAX_DIFF_BYTES) {
            val lineCount = text.count { it == '\n' } + 1
            if (lineCount <= MAX_DIFF_LINES) return TrimmedText(text, false)
            return TrimmedText(text.lineSequence().take(MAX_DIFF_LINES).joinToString("\n"), true)
        }
        val capped = capUtf8(text, MAX_DIFF_BYTES)
        val lines = capped.split('\n')
        if (lines.size <= MAX_DIFF_LINES) return TrimmedText(capped, true)
        return TrimmedText(lines.take(MAX_DIFF_LINES).joinToString("\n"), true)
    }

    /** UTF-8 字节数：不物化字节数组，代理对按一个码点计。 */
    private fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            bytes += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return bytes
    }

    /** 截到字节预算内：逐码点累计，不切代理对、不切多字节字符。 */
    private fun capUtf8(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            i += Character.charCount(cp)
        }
        return text.substring(0, i)
    }

    /** 卡片数量上限：超出的文件不建视图，只报个数。 */
    fun visibleFiles(total: Int): VisibleFiles {
        if (total <= MAX_FILES) return VisibleFiles(total, 0)
        return VisibleFiles(MAX_FILES, total - MAX_FILES)
    }
}
