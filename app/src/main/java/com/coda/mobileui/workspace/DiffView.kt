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

    // 增删色走资源，浅色/深色各有一套；写死 ARGB 在浅色主题下对比度不足（#501）。
    private val green = ContextCompat.getColor(activity, R.color.diff_added)
    private val red = ContextCompat.getColor(activity, R.color.diff_removed)

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
        // 先截到预算内：后面的 split 与逐文件建卡片都在这个上界之内进行。
        val trimmed = DiffBudget.trimText(text)
        val lines = trimmed.text.split('\n')
        if (mode == Mode.DIFF) addView(statLine(lines))
        val grouped = groupFiles(lines)
        // preamble（提交头等非 diff 组）不占文件卡预算（#777）：total 只数
        // diff --git 头，groups 却可能多一个前导组，按 take(visible) 切会
        // 静默丢掉最后一个真实文件组且 omitted=0 不提示。
        val hasPreamble = grouped.groups.size > grouped.total
        val preamble = if (hasPreamble) grouped.groups.first() else null
        val fileGroups = if (hasPreamble) grouped.groups.drop(1) else grouped.groups
        val visible = DiffBudget.visibleFiles(grouped.total)
        preamble?.let { addView(fileCard(it, mode)) }
        for (group in fileGroups.take(visible.visible)) addView(fileCard(group, mode))
        val omitted = grouped.total - minOf(visible.visible, fileGroups.size)
        if (omitted > 0) addView(hint("还有 $omitted 个文件未渲染，先用文件列表查看"))
        if (trimmed.truncated) addView(hint("diff 过长，已截断显示"))
    }

    /** 分组结果：只保留前 [DiffBudget.MAX_FILES] 组内容，[total] 仍是真实文件数。 */
    private class Grouped(
        val groups: List<Group>,
        val total: Int,
    )

    // ------------------------------------------------------------ 统计

    private fun statLine(lines: List<String>): View {
        // 统计交给 DiffLines：它维护 hunk 状态，hunk 内的 ---/+++ 内容行不会被漏计（#499）。
        val stat = DiffLines.stat(lines)
        val files = stat.files
        val added = stat.added
        val removed = stat.removed
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
    private fun groupFiles(lines: List<String>): Grouped {
        val groups = mutableListOf<Group>()
        var current: MutableList<String>? = mutableListOf()
        var name = ""
        var total = 0
        for (line in lines) {
            if (line.startsWith("diff --git ")) {
                total++
                current?.let { if (it.isNotEmpty()) groups.add(Group(name, it)) }
                // "diff --git a/lib/x.kt b/lib/x.kt" 取后半段，路径更好读。
                name = parseDiffName(line.removePrefix("diff --git ").trim())
                // 卡片数到上限后不再累积内容：后面的文件只计数，不保存行，也不建视图。
                // 预算按文件组数（total）算，preamble 不占额（#777）：按 groups.size
                // 判会在有前导组时提前一个文件停止收集。
                current = if (total <= DiffBudget.MAX_FILES) mutableListOf() else null
            }
            current?.add(line)
        }
        current?.let { if (it.isNotEmpty()) groups.add(Group(name, it)) }
        // 没有 diff 头的纯文本也会成一组，此时 total 至少按实际组数算。
        return Grouped(groups, total.coerceAtLeast(groups.size))
    }

    /**
     * 从 `diff --git` 的尾部取目标路径。
     *
     * 路径含空格时 git 会给两端加引号，按空格切分会得到 `file.txt"` 这样的残片；
     * 这里先按带引号的完整形式匹配，再退回无引号形式。
     */
    /** 文件名解析统一走 DiffLines，避免两份实现漂移。 */
    private fun parseDiffName(tail: String): String = DiffLines.fileName(tail)

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
        // 分类交给 DiffLines（有单测）：hunk 内以 @@/+++/---/diff --git 开头的内容行
        // 不再被误当成头部着色（#499）。
        val kinds = DiffLines.classify(lines, conflict = mode == Mode.CONFLICT)
        lines.forEachIndexed { index, line ->
            val start = sb.length
            sb.append(line)
            val color =
                when (kinds[index]) {
                    DiffLineKind.CONFLICT_OURS -> red
                    DiffLineKind.CONFLICT_THEIRS -> green
                    DiffLineKind.CONFLICT_SEPARATOR -> primary
                    DiffLineKind.HUNK_HEADER -> primary
                    DiffLineKind.FILE_HEADER -> variant
                    DiffLineKind.ADDED -> green
                    DiffLineKind.REMOVED -> red
                    DiffLineKind.CONTEXT -> null
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
