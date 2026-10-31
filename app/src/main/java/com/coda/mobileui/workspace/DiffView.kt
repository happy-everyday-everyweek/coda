package com.coda.mobileui.workspace

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.coda.mobileui.R
import com.google.android.material.card.MaterialCardView

/**
 * diff 的展示视图。
 *
 * 不再是一段没有任何标记的等宽文本，而是：顶部给一行统计（文件数、增删行数），
 * 下面按文件各给一张与列表同形的卡片，文件名单独一行，内容按行着色——
 * 新增行绿、删除行红、hunk 头用主色、文件头弱化。
 *
 * 冲突文件也走这里（[Mode.CONFLICT]）：整段取一边之后仍要能看清冲突标记在哪，
 * 所以 `<<<<<<<` 红、`=======` 主色、`>>>>>>>` 绿。
 */
internal class DiffView(
    private val activity: Activity,
) : LinearLayout(activity) {
    enum class Mode { DIFF, CONFLICT }

    private class Group(
        val name: String,
        val lines: List<String>,
    )

    private val margin = resources.getDimensionPixelSize(R.dimen.settings_card_margin)

    private val green = 0xFF35B37E.toInt()
    private val red = 0xFFE0574C.toInt()

    init {
        orientation = VERTICAL
    }

    fun render(
        text: String,
        mode: Mode = Mode.DIFF,
    ) {
        removeAllViews()
        if (text.isBlank()) {
            addView(hint("没有内容"))
            return
        }
        val lines = text.split('\n')
        if (mode == Mode.DIFF) addView(statLine(lines))
        val groups = groupFiles(lines)
        for (group in groups) addView(fileCard(group, mode))
    }

    // ------------------------------------------------------------ 统计

    private fun statLine(lines: List<String>): View {
        var files = 0
        var added = 0
        var removed = 0
        for (line in lines) {
            when {
                line.startsWith("diff --git ") -> files++
                line.startsWith("+") && !line.startsWith("+++") -> added++
                line.startsWith("-") && !line.startsWith("---") -> removed++
            }
        }
        val summary =
            buildString {
                if (files > 0) append("$files 个文件 · ")
                append("+$added")
                append(" −$removed")
            }
        return TextView(activity).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(margin + dp(4f), dp(2f), margin, dp(8f))
        }
    }

    // ------------------------------------------------------------ 分组

    /** 按 `diff --git` 切开；前面若有提交头之类的行，单独成一组。 */
    private fun groupFiles(lines: List<String>): List<Group> {
        val groups = mutableListOf<Group>()
        var current = mutableListOf<String>()
        var name = ""
        for (line in lines) {
            if (line.startsWith("diff --git ")) {
                if (current.isNotEmpty()) groups.add(Group(name, current.toList()))
                current = mutableListOf()
                // "diff --git a/lib/x.kt b/lib/x.kt" 取后半段，路径更好读。
                val tail = line.removePrefix("diff --git ").trim()
                name =
                    tail
                        .split(' ')
                        .lastOrNull()
                        ?.removePrefix("b/")
                        .orEmpty()
            }
            current.add(line)
        }
        if (current.isNotEmpty()) groups.add(Group(name, current.toList()))
        return groups
    }

    private fun fileCard(
        group: Group,
        mode: Mode,
    ): View {
        val column =
            LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(margin, dp(10f), margin, dp(12f))
            }
        if (group.name.isNotEmpty()) {
            column.addView(
                TextView(activity).apply {
                    text = group.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(colorOf(androidx.appcompat.R.attr.colorPrimary))
                    maxLines = 2
                },
            )
        }
        // 单文件过长时截断：一张卡片塞几千行只会卡住滚动。
        val limit = 600
        val shown = if (group.lines.size > limit) group.lines.take(limit) else group.lines
        column.addView(
            TextView(activity).apply {
                text = styled(shown, mode)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(6f), 0, 0)
                setLineSpacing(dp(1f).toFloat(), 1f)
            },
        )
        if (group.lines.size > limit) {
            column.addView(
                TextView(activity).apply {
                    text = "（还有 ${group.lines.size - limit} 行未显示）"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(0, dp(6f), 0, 0)
                },
            )
        }
        return card(column)
    }

    /** 与设置页卡片同形：底色 surfaceContainerLow、圆角 settings_card_corner、零描边零阴影。 */
    private fun card(content: View): MaterialCardView {
        val corner = resources.getDimension(R.dimen.settings_card_corner)
        return MaterialCardView(activity).apply {
            layoutParams =
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = margin
                    rightMargin = margin
                    bottomMargin = dp(8f)
                }
            setCardBackgroundColor(colorOf(com.google.android.material.R.attr.colorSurfaceContainerLow))
            cardElevation = 0f
            strokeWidth = 0
            radius = corner
            addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ------------------------------------------------------------ 着色

    private fun styled(
        lines: List<String>,
        mode: Mode,
    ): CharSequence {
        val variant = colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = colorOf(androidx.appcompat.R.attr.colorPrimary)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = sb.length
            sb.append(line)
            val color =
                when {
                    mode == Mode.CONFLICT && line.startsWith("<<<<<<<") -> red
                    mode == Mode.CONFLICT && line.startsWith(">>>>>>>") -> green
                    mode == Mode.CONFLICT && line.startsWith("=======") -> primary
                    mode == Mode.CONFLICT -> null
                    line.startsWith("@@") -> primary
                    line.startsWith("+++") || line.startsWith("---") -> variant
                    line.startsWith("+") -> green
                    line.startsWith("-") -> red
                    line.startsWith("diff --git") -> variant
                    else -> null
                }
            if (color != null) {
                sb.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (index != lines.lastIndex) sb.append('\n')
        }
        return sb
    }

    private fun hint(text: String): TextView =
        TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(margin + dp(4f), dp(8f), margin, dp(8f))
        }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun colorOf(attr: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(activity, value.resourceId) else value.data
        } else {
            0xFF808080.toInt()
        }
    }
}
