package com.coda.mobileui

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.inputmethod.BaseInputConnection
import androidx.appcompat.widget.AppCompatEditText

/**
 * 即时渲染的 Markdown 输入框。
 *
 * 设计取向参考 Nomo（github.com/nomo-md/nomo）：边写边看到最终形态，不在「源码」和
 * 「预览」之间切换。但实现路径不同——Nomo 是 Svelte + Tauri 的富文本文档模型，
 * Android 上照搬会毁掉输入法组词、光标和撤销栈。
 *
 * 这里只加 span、不改文本：框里的字和 send 出去的字符串逐字节相同，永远是原始
 * Markdown。渲染只是给这些字套样式，因此输入法组词不受影响、光标由系统维护、
 * 复制粘贴拿到的是干净的 Markdown 而不是带星号的富文本。
 *
 * 语法标记（`**`、`#`、反引号）不删除，只弱化：缩到 62% 字号、透明度降到 96/255。
 * 删标记就要改文本，改文本就会崩光标位置，这条不能破。
 *
 * 支持粗体、斜体、行内代码、删除线、标题、引用、列表、分隔线、链接、围栏代码块。
 */
class MarkdownEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 关掉就退化成普通输入框。 */
    var renderEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rerender()
        }

    /** 语法标记的相对字号。 */
    private var markScale = 0.62f

    /** 语法标记透明度（0-255）。 */
    private var markAlpha = 96

    /** 组词期间跳过渲染，组词结束后补一次。 */
    private var skippedWhileComposing = false

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = rerender()
    }

    init {
        addTextChangedListener(watcher)
    }

    /** 重算整段样式。文本内容不变，只换 span。 */
    fun rerender() {
        val text = text ?: return
        clearSpans(text)
        if (!renderEnabled) return
        if (isComposing(text)) {
            skippedWhileComposing = true
            return
        }
        MarkdownSpans.apply(text, markScale, markAlpha)
    }

    /**
     * 是否处于输入法组词中。
     *
     * 用 Editable 上的 composing span 判断，而不是问 InputMethodManager 要
     * getComposingText()——后者在部分输入法上与控件不同步，会误判。
     */
    private fun isComposing(text: Editable): Boolean {
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        return start >= 0 && end > start
    }

    private fun clearSpans(text: Editable) {
        if (text.isEmpty()) return
        text.getSpans(0, text.length, MarkdownSpans.Ours::class.java).forEach {
            text.removeSpan(it)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // 组词结束后系统会重置选区，借这个时机补上被跳过的那次渲染。
        if (skippedWhileComposing) {
            val t = text
            if (t == null || !isComposing(t)) {
                skippedWhileComposing = false
                rerender()
            }
        }
    }
}

/**
 * Markdown 到 Spannable 的映射。不持有 View，便于单独验证。
 */
internal object MarkdownSpans {

    /** 自己加的 span 的基类，清理时据此精确识别。 */
    abstract class Ours : CharacterStyle()

    /** 语法标记：缩小 + 降透明度，字符保留。 */
    private class MarkStyle(private val scale: Float, private val alpha: Int) : Ours() {
        private val size = RelativeSizeSpan(scale)

        override fun updateDrawState(tp: TextPaint) {
            size.updateDrawState(tp)
            tp.alpha = alpha
        }
    }

    /** 等宽 + 淡底色，用于行内代码与围栏代码。 */
    private class MonoSpan(private val bg: Int) : Ours() {
        override fun updateDrawState(tp: TextPaint) {
            tp.typeface = Typeface.MONOSPACE
            tp.bgColor = bg
        }
    }

    private val FENCE = Regex("^\\s*(?:```|~~~)")
    private val HEADING = Regex("^(#{1,6})\\s")
    private val QUOTE = Regex("^\\s*>\\s?")
    private val BULLET = Regex("^\\s*[-*+]\\s")
    private val ORDERED = Regex("^\\s*\\d+\\.\\s")
    private val HR = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val BOLD = Regex("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__")
    private val ITALIC = Regex("(?<![*_])\\*([^*\\n]+)\\*(?![*_])|(?<![*_])_([^_\\n]+)_(?![*_])")
    private val STRIKE = Regex("~~([^~\\n]+)~~")
    private val LINK = Regex("\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)")

    private const val CODE_BG = 0x22888888
    private const val QUOTE_FG = 0xFF8A8A8A.toInt()
    private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun apply(text: Editable, markScale: Float, markAlpha: Int) {
        val src = text.toString()
        if (src.isEmpty()) return

        // 快速路径：一个 Markdown 语法字符都没有就直接返回。
        // 日常的短消息大多如此，省掉十几条正则的全量扫描。
        if (!hasAnyMarkup(src)) return

        // 代码块区间只算一次：块级与行内都要用。
        val codeRanges = fencedCodeRanges(src)
        applyBlocks(text, src, codeRanges, markScale, markAlpha)

        fun inCode(s: Int, e: Int) = codeRanges.any { s >= it.first && e <= it.last }

        // 行内代码优先：后面的规则跳过这些区间，否则代码里的 ** 会被当成粗体。
        val inline = mutableListOf<IntRange>()
        for (m in INLINE_CODE.findAll(src)) {
            if (inCode(m.range.first, m.range.last)) continue
            val bodyStart = m.range.first + 1
            val bodyEnd = m.range.last
            if (bodyEnd <= bodyStart) continue
            text.setSpan(MonoSpan(CODE_BG), bodyStart, bodyEnd, SPAN_FLAGS)
            text.setSpan(MarkStyle(markScale, markAlpha), m.range.first, bodyStart, SPAN_FLAGS)
            text.setSpan(MarkStyle(markScale, markAlpha), bodyEnd, m.range.last + 1, SPAN_FLAGS)
            inline.add(m.range)
        }

        fun blocked(s: Int, e: Int) = inCode(s, e) || inline.any { s >= it.first && e <= it.last }

        for (m in STRIKE.findAll(src)) {
            if (blocked(m.range.first, m.range.last)) continue
            val body = m.groups[1] ?: continue
            text.setSpan(StrikethroughSpan(), body.range.first, body.range.last + 1, SPAN_FLAGS)
            markPair(text, m, body, markScale, markAlpha)
        }

        for (m in BOLD.findAll(src)) {
            if (blocked(m.range.first, m.range.last)) continue
            val body = m.groups[1] ?: m.groups[2] ?: continue
            text.setSpan(StyleSpan(Typeface.BOLD), body.range.first, body.range.last + 1, SPAN_FLAGS)
            markPair(text, m, body, markScale, markAlpha)
        }

        for (m in ITALIC.findAll(src)) {
            if (blocked(m.range.first, m.range.last)) continue
            val body = m.groups[1] ?: m.groups[2] ?: continue
            text.setSpan(StyleSpan(Typeface.ITALIC), body.range.first, body.range.last + 1, SPAN_FLAGS)
            markPair(text, m, body, markScale, markAlpha)
        }

        for (m in LINK.findAll(src)) {
            if (blocked(m.range.first, m.range.last)) continue
            val label = m.groups[1] ?: continue
            if (m.groups[2] == null) continue
            // 链接文字正常显示，方括号与 (url) 弱化，文字加下划线。
            text.setSpan(MarkStyle(markScale, markAlpha), m.range.first, label.range.first, SPAN_FLAGS)
            text.setSpan(MarkStyle(markScale, markAlpha), label.range.last + 1, m.range.last + 1, SPAN_FLAGS)
            text.setSpan(UnderlineSpan(), label.range.first, label.range.last + 1, SPAN_FLAGS)
        }
    }

    /** 块级语法：标题、引用、列表、分隔线、围栏。 */
    private fun applyBlocks(
        text: Editable,
        src: String,
        codeRanges: List<IntRange>,
        markScale: Float,
        markAlpha: Int,
    ) {
        fun inBlock(s: Int) = codeRanges.any { s >= it.first && s <= it.last }

        var offset = 0
        for (line in src.split('\n')) {
            val lineStart = offset
            val lineEnd = offset + line.length
            offset = lineEnd + 1
            if (line.isEmpty()) continue

            // 围栏行与块内每行都在 codeRanges 内，统一按代码处理。
            if (inBlock(lineStart)) {
                if (lineEnd > lineStart) {
                    text.setSpan(MonoSpan(CODE_BG), lineStart, lineEnd, SPAN_FLAGS)
                }
                if (FENCE.containsMatchIn(line)) {
                    text.setSpan(MarkStyle(markScale, markAlpha), lineStart, lineEnd, SPAN_FLAGS)
                }
                continue
            }

            if (HR.containsMatchIn(line)) {
                text.setSpan(MarkStyle(markScale, markAlpha), lineStart, lineEnd, SPAN_FLAGS)
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                val bodyStart = lineStart + heading.value.length
                if (lineEnd > bodyStart) {
                    text.setSpan(StyleSpan(Typeface.BOLD), bodyStart, lineEnd, SPAN_FLAGS)
                    text.setSpan(
                        RelativeSizeSpan(headingScale(heading.groupValues[1].length)),
                        bodyStart,
                        lineEnd,
                        SPAN_FLAGS,
                    )
                }
                text.setSpan(MarkStyle(markScale, markAlpha), lineStart, bodyStart, SPAN_FLAGS)
                continue
            }

            val quote = QUOTE.find(line)
            if (quote != null) {
                val bodyStart = lineStart + quote.value.length
                if (lineEnd > bodyStart) {
                    text.setSpan(ForegroundColorSpan(QUOTE_FG), bodyStart, lineEnd, SPAN_FLAGS)
                }
                text.setSpan(MarkStyle(markScale, markAlpha), lineStart, bodyStart, SPAN_FLAGS)
                continue
            }

            val bullet = BULLET.find(line)
            if (bullet != null) {
                text.setSpan(MarkStyle(markScale, markAlpha), lineStart, lineStart + bullet.value.length, SPAN_FLAGS)
                continue
            }

            val ordered = ORDERED.find(line)
            if (ordered != null) {
                text.setSpan(MarkStyle(markScale, markAlpha), lineStart, lineStart + ordered.value.length, SPAN_FLAGS)
            }
        }
    }

    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.42f
        2 -> 1.28f
        3 -> 1.16f
        else -> 1.08f
    }

    /**
     * 是否含有任何 Markdown 语法字符。
     *
     * 用单次遍历判断，比跑十几条正则便宜得多。全部语法都要依赖这些字符中的至少一个，
     * 所以「没有这些字符」就等于「没有语法」。
     */
    private fun hasAnyMarkup(src: String): Boolean {
        for (ch in src) {
            when (ch) {
                '`', '*', '_', '~', '#', '>', '-', '[', '\\', '+' -> return true
            }
            // 有序列表：数字后跟点，交给后面的正则，这里只兜住「有数字」的情况太宽，
            // 交给 ORDERED 判断更准，所以数字不在此列。
        }
        return false
    }

    /** 把成对标记（如 `**`）的两侧弱化。 */
    private fun markPair(
        text: Editable,
        whole: MatchResult,
        body: MatchGroup,
        scale: Float,
        alpha: Int,
    ) {
        val bodyStart = body.range.first
        val bodyEnd = body.range.last + 1
        if (bodyStart > whole.range.first) {
            text.setSpan(MarkStyle(scale, alpha), whole.range.first, bodyStart, SPAN_FLAGS)
        }
        if (whole.range.last + 1 > bodyEnd) {
            text.setSpan(MarkStyle(scale, alpha), bodyEnd, whole.range.last + 1, SPAN_FLAGS)
        }
    }

    /** 所有围栏代码块覆盖的字符范围；未闭合的算到文末。 */
    private fun fencedCodeRanges(src: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var open = -1
        var offset = 0
        for (line in src.split('\n')) {
            val lineEnd = offset + line.length
            if (FENCE.containsMatchIn(line)) {
                if (open < 0) {
                    open = offset
                } else {
                    out.add(open..lineEnd)
                    open = -1
                }
            }
            offset = lineEnd + 1
        }
        if (open >= 0) out.add(open..src.length - 1)
        return out
    }
}