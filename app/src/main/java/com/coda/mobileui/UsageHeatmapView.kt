package com.coda.mobileui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.coda.mobileui.core.CodaExtras
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import java.util.Calendar

/**
 * 活跃度热力图。
 *
 * 内核的 `usage/stats` 已经把数据算好了：`heatmap.weeks[].days[]`，每格带 `level`（0..4）、
 * `totalTokens`、`turnCount`、`toolCallCount`，且首尾可能为 null（补齐整周的空格）。
 * 所以这里不做统计，只负责把格子画出来，并在缺字段时自己兜一层。
 *
 * 布局是日历式：一列一周，一行一周里的一天。列数由数据决定，行固定 7 行，
 * 因此宽度自适应、高度固定按 7 行算，控件可以直接放进 ScrollView 横向滚动。
 */
class UsageHeatmapView(context: Context) : View(context) {

    init {
        // 要接点击，必须显式声明可点，否则父容器不会把触摸派发过来。
        isClickable = true
        isFocusable = true
    }

    /** 一格的数据。null 表示这一格不属于统计区间（周首尾补齐用）。 */
    internal data class Cell(
        val level: Int,
        val tokens: Long,
        val turns: Int,
        val date: String,
    )

    /** 列 = 周。每列固定 7 个位置，null 表示空位。 */
    private var weeks: List<List<Cell?>> = emptyList()

    /** 悬停/点击时显示的那一格。 */
    private var selected: Cell? = null

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attr, fallback)

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f).toFloat()
        typeface = Typeface.DEFAULT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f).toFloat()
        color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    }

    /** 格子边长；留出左侧星期标签与底部月份标签的空间。 */
    private val cellSize = dp(11f)
    private val gap = dp(2.5f)
    private val leftPad = dp(16f)
    private val topPad = dp(14f)
    private val bottomPad = dp(14f)
    private val rowGap = dp(2.5f)

    /** 五档颜色由浅到深。用主色的透明度叠加，深浅主题都能看清。 */
    private fun levelColor(level: Int): Int {
        val base = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF2E7D32.toInt())
        val alpha = when (level) {
            0 -> 0x1A
            1 -> 0x4D
            2 -> 0x80
            3 -> 0xB3
            else -> 0xFF
        }
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    private val emptyColor: Int
        get() = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY) and 0x1AFFFFFF

    /**
     * 塞入数据。
     *
     * 优先用内核给的 `heatmap`；没有就退回 `dailyModelUsage` 按日累加后自己定级
     * （按分位数切四档，避免某一个高峰把其余全压成最浅色）。
     */
    fun bind(root: JSONObject?) {
        selected = null
        val heat = root?.optJSONObject("heatmap")
        weeks = if (heat != null) parseHeatmap(heat) else fallbackFromDaily(root)
        requestLayout()
        invalidate()
    }

    private fun parseHeatmap(heat: JSONObject): List<List<Cell?>> {
        val arr = heat.optJSONArray("weeks") ?: return emptyList()
        val out = mutableListOf<List<Cell?>>()
        for (i in 0 until arr.length()) {
            val week = arr.optJSONObject(i) ?: continue
            val days = week.optJSONArray("days")
            val col = mutableListOf<Cell?>()
            if (days == null) {
                repeat(7) { col += null }
            } else {
                for (d in 0 until days.length()) {
                    val cell = days.optJSONObject(d)
                    if (cell == null) {
                        col += null
                    } else {
                        col += Cell(
                            level = cell.optInt("level", 0).coerceIn(0, 4),
                            tokens = cell.optLong("totalTokens", 0),
                            turns = cell.optInt("turnCount", 0),
                            date = cell.optString("date"),
                        )
                    }
                }
                // 内核理应给满 7 天，短了就补空位，避免画歪。
                while (col.size < 7) col += null
            }
            out += col
        }
        return out
    }

    /** 没有 heatmap 时，从 dailyModelUsage 自行聚合并定级。 */
    private fun fallbackFromDaily(root: JSONObject?): List<List<Cell?>> {
        val daily = root?.optJSONArray("dailyModelUsage") ?: return emptyList()
        val byDate = linkedMapOf<String, Long>()
        for (i in 0 until daily.length()) {
            val day = daily.optJSONObject(i) ?: continue
            val date = day.optString("date")
            if (date.isEmpty()) continue
            var sum = 0L
            day.optJSONArray("models")?.let { ms ->
                for (j in 0 until ms.length()) {
                    sum += ms.optJSONObject(j)?.optLong("totalTokens", 0) ?: 0L
                }
            }
            byDate[date] = (byDate[date] ?: 0L) + sum
        }
        if (byDate.isEmpty()) return emptyList()

        // 用非零值的三分位切 1/2/3 档，最高档给 4，避免单日尖峰压平其余。
        val positive = byDate.values.filter { it > 0 }.sorted()
        fun threshold(q: Double): Long =
            if (positive.isEmpty()) 0L else positive[((positive.size - 1) * q).toInt()]

        val t1 = threshold(0.25)
        val t2 = threshold(0.5)
        val t3 = threshold(0.75)
        fun levelOf(v: Long): Int = when {
            v <= 0 -> 0
            v <= t1 -> 1
            v <= t2 -> 2
            v <= t3 -> 3
            else -> 4
        }

        // 按日历补齐：从第一天的所在周开始，到最后的日期结束。
        val first = byDate.keys.minOrNull() ?: return emptyList()
        val last = byDate.keys.maxOrNull() ?: return emptyList()
        val cal = Calendar.getInstance()
        val start = parseDate(first, cal) ?: return emptyList()
        // 退到那一周的周日，保证第一列从周日开始。
        val begin = start.clone() as Calendar
        begin.add(Calendar.DAY_OF_MONTH, -(begin.get(Calendar.DAY_OF_WEEK) - 1))
        val end = parseDate(last, cal) ?: return emptyList()

        val out = mutableListOf<List<Cell?>>()
        var col = mutableListOf<Cell?>()
        val cursor = begin.clone() as Calendar
        while (!cursor.after(end) || col.isNotEmpty()) {
            val key = formatDate(cursor)
            val v = byDate[key] ?: 0L
            col += Cell(levelOf(v), v, 0, key)
            if (col.size == 7) {
                out += col
                col = mutableListOf()
                // 整周都在统计区间之后就可以收尾。
                if (cursor.after(end)) break
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (col.isNotEmpty()) {
            while (col.size < 7) col += null
            out += col
        }
        return out
    }

    private fun parseDate(s: String, cal: Calendar): Calendar? = runCatching {
        val parts = s.split("-")
        if (parts.size != 3) return@runCatching null
        (cal.clone() as Calendar).apply {
            clear()
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
    }.getOrNull()

    private fun formatDate(cal: Calendar): String {
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "${cal.get(Calendar.YEAR)}-${if (m < 10) "0$m" else "$m"}-${if (d < 10) "0$d" else "$d"}"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cols = weeks.size.coerceAtLeast(1)
        val w = leftPad + cols * (cellSize + gap) + dp(4f)
        val h = topPad + 7 * (cellSize + rowGap) + bottomPad
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weeks.isEmpty()) {
            canvas.drawText("暂无活跃数据", leftPad.toFloat(), (topPad + cellSize).toFloat(), textPaint)
            return
        }

        // 左侧星期标签：只标周一、周三、周五，避免拥挤。
        val weekLabels = mapOf(1 to "一", 3 to "三", 5 to "五")
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        for ((row, text) in weekLabels) {
            val y = topPad + row * (cellSize + rowGap) + cellSize * 0.85f
            canvas.drawText(text, dp(2f).toFloat(), y, labelPaint)
        }

        val rect = RectF()
        var lastMonth = -1
        weeks.forEachIndexed { ci, week ->
            val x = leftPad + ci * (cellSize + gap)
            week.forEachIndexed { ri, cell ->
                if (ri >= 7) return@forEachIndexed
                val y = topPad + ri * (cellSize + rowGap)
                rect.set(
                    x.toFloat(),
                    y.toFloat(),
                    (x + cellSize).toFloat(),
                    (y + cellSize).toFloat(),
                )
                val paint = if (cell == null) {
                    cellPaint.apply { color = emptyColor }
                } else {
                    cellPaint.apply {
                        color = if (cell === selected) {
                            themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
                        } else {
                            levelColor(cell.level)
                        }
                    }
                }
                canvas.drawRoundRect(rect, dp(2f).toFloat(), dp(2f).toFloat(), paint)

                // 列首格的日期用作月份标签，同月只标一次。
                if (cell != null) {
                    val month = cell.date.split("-").getOrNull(1)?.toIntOrNull() ?: -1
                    if (month > 0 && month != lastMonth && ri == 0) {
                        lastMonth = month
                        canvas.drawText("${month}月", x.toFloat(), (topPad - dp(4f)).toFloat(), labelPaint)
                    }
                }
            }
        }
    }

    /**
     * 点格子看详情。用在统计页里比 tooltip 直接，点一下就在下方文字里显示。
     *
     * @return 被点中的那格；点在空位上返回 null。
     */
    internal fun cellAt(x: Float, y: Float): Cell? {
        if (weeks.isEmpty()) return null
        val ci = ((x - leftPad) / (cellSize + gap)).toInt()
        val ri = ((y - topPad) / (cellSize + rowGap)).toInt()
        if (ci < 0 || ci >= weeks.size || ri < 0 || ri >= 7) return null
        val cell = weeks[ci].getOrNull(ri) ?: return null
        selected = cell
        invalidate()
        return cell
    }

    /** 给外部读出某格的描述文字。 */
    internal fun describe(cell: Cell?): String? {
        if (cell == null) return null
        val tokens = CodaExtras.formatCount(cell.tokens)
        return if (cell.tokens <= 0) {
            "${cell.date} · 无活动"
        } else {
            "${cell.date} · $tokens Token" + if (cell.turns > 0) " · ${cell.turns} 回合" else ""
        }
    }

    /** 当前选中的那格，供页面显示。 */
    internal fun selectedCell(): Cell? = selected
}