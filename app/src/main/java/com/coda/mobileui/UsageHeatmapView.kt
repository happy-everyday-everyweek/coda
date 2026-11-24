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
class UsageHeatmapView(
    context: Context,
) : View(context) {
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

    private fun themeColor(
        attr: Int,
        fallback: Int,
    ): Int = MaterialColors.getColor(this, attr, fallback)

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(9f).toFloat()
            typeface = Typeface.DEFAULT
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    // 主题色在 bind/初始化时解析一次：onDraw 里逐格查主题属性是 ~7×N 次/帧的开销。
    private val levelColors = IntArray(5)
    private var emptyColorCached = 0
    private var selectedColorCached = 0
    private var labelColorCached = 0
    private val cornerPx = dp(2f).toFloat()

    /** 色阶透明度：常量而非每次 cacheDrawColors 现建数组。 */
    private val LEVEL_ALPHAS = intArrayOf(0x1A, 0x4D, 0x80, 0xB3, 0xFF)
    private val labelXPx = dp(2f).toFloat()

    private fun cacheDrawColors() {
        val base = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF2E7D32.toInt())
        for (i in 0..4) levelColors[i] = (base and 0x00FFFFFF) or (LEVEL_ALPHAS[i] shl 24)
        emptyColorCached = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY) and 0x1AFFFFFF
        selectedColorCached = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        labelColorCached = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        // 空状态文字的画笔也要跟着刷新（OCR low #827）：它原来只在构造期取一次色，
        // 主题换了而视图复用（没有重建）时，只有这一处还是旧颜色。
        textPaint.color = labelColorCached
    }

    init {
        cacheDrawColors()
    }

    /**
     * 塞入数据。
     *
     * 优先用内核给的 `heatmap`；没有就退回 `dailyModelUsage` 按日累加后自己定级
     * （按分位数切四档，避免某一个高峰把其余全压成最浅色）。
     */
    fun bind(root: JSONObject?) {
        selected = null
        val heat = root?.optJSONObject("heatmap")
        // heatmap 存在但 weeks 缺失/为空时也要回退：有可用的 daily 数据就不该显示「暂无」。
        val parsed = if (heat != null) parseHeatmap(heat) else emptyList()
        weeks = parsed.ifEmpty { fallbackFromDaily(root) }
        cacheDrawColors()
        requestLayout()
        invalidate()
    }

    private fun parseHeatmap(heat: JSONObject): List<List<Cell?>> {
        val arr = heat.optJSONArray("weeks") ?: return emptyList()
        val out = mutableListOf<List<Cell?>>()
        for (i in 0 until arr.length()) {
            // 坏周不能整列丢弃：后续列会左移一格，格子与星期行、月份标签全部错位。
            // 用空列占位保持日历几何。
            val week = arr.optJSONObject(i)
            if (week == null) {
                out += List(7) { null }
                continue
            }
            val days = week.optJSONArray("days")
            val col = mutableListOf<Cell?>()
            if (days == null) {
                repeat(7) { col += null }
            } else {
                // 截断到 7：超长的 days 会画出可见但点不中的死格子。
                for (d in 0 until minOf(days.length(), 7)) {
                    val cell = days.optJSONObject(d)
                    if (cell == null) {
                        col += null
                    } else {
                        col +=
                            Cell(
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
            // 日期键归一化（OCR low #827）：数据里可能出现 2026-1-5 这种不补零写法，
            // 它与 2026-01-05 按字符串比较时大小关系是错的，会让统计区间内的日期
            // 变成空洞。这里统一过一遍 parseDate/formatDate。
            val key = parseDate(date, Calendar.getInstance())?.let { formatDate(it) } ?: date
            byDate[key] = (byDate[key] ?: 0L) + sum
        }
        if (byDate.isEmpty()) return emptyList()

        // 用非零值的三分位切 1/2/3 档，最高档给 4，避免单日尖峰压平其余。
        val positive = byDate.values.filter { it > 0 }.sorted()

        fun threshold(q: Double): Long = if (positive.isEmpty()) 0L else positive[((positive.size - 1) * q).toInt()]

        val t1 = threshold(0.25)
        val t2 = threshold(0.5)
        val t3 = threshold(0.75)

        val levelOf = quantileLeveler(positive, t1, t2, t3)

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
            // 首周从周日开始、末周补齐到周六：这两段里有日期根本不在统计范围内。
            // 发 null 而不是 level=0 的格子，否则点上去会显示「无活动」。
            col += heatCellFor(key, first, last, v, levelOf)
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

    private fun parseDate(
        s: String,
        cal: Calendar,
    ): Calendar? =
        runCatching {
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

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
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
        labelPaint.color = labelColorCached
        for ((row, text) in weekLabels) {
            val y = topPad + row * (cellSize + rowGap) + cellSize * 0.85f
            canvas.drawText(text, labelXPx, y, labelPaint)
        }

        val rect = RectF()
        var lastMonth = -1
        weeks.forEachIndexed { ci, week ->
            val x = leftPad + ci * (cellSize + gap)
            // 列内第一个非空格子（OCR low #827）：数据从周中开始时，第 0 行是补齐出来的
            // null，只认 ri == 0 会让首月标签整体右移一周，单周数据则完全不画。
            val firstCell = week.firstOrNull { it != null }
            week.forEachIndexed { ri, cell ->
                if (ri >= 7) return@forEachIndexed
                val y = topPad + ri * (cellSize + rowGap)
                rect.set(
                    x.toFloat(),
                    y.toFloat(),
                    (x + cellSize).toFloat(),
                    (y + cellSize).toFloat(),
                )
                val paint =
                    if (cell == null) {
                        cellPaint.apply { color = emptyColorCached }
                    } else {
                        cellPaint.apply {
                            color = if (cell === selected) selectedColorCached else levelColors[cell.level]
                        }
                    }
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                // 列首格的日期用作月份标签，同月只标一次。
                if (cell != null) {
                    val month =
                        cell.date
                            .split("-")
                            .getOrNull(1)
                            ?.toIntOrNull() ?: -1
                    if (month > 0 && month != lastMonth && cell === firstCell) {
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
    internal fun cellAt(
        x: Float,
        y: Float,
    ): Cell? {
        val cell = hitCell(weeks, x, y, leftPad, topPad, cellSize, gap, rowGap)
        // miss 也要清选中并重绘：否则旧格子的高亮永远留着，与详情文字互相矛盾。
        if (selected !== cell) {
            selected = cell
            invalidate()
        }
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

/**
 * 生成热图的一格；不在 [first]..[last] 区间内的日期返回 null。
 *
 * 首周从周日开始、末周补齐到周六，这两段里有日期根本不在统计范围内。之前给它们
 * 发的是 level=0 的真实格子：颜色与「无活动」相同，点上去还说「无活动」——
 * 把「没有数据」说成了「统计过但没使用」。ISO 日期字符串可以直接比大小。
 */
internal fun heatCellFor(
    key: String,
    first: String,
    last: String,
    tokens: Long,
    levelOf: (Long) -> Int,
): UsageHeatmapView.Cell? =
    if (key < first || key > last) {
        null
    } else {
        UsageHeatmapView.Cell(levelOf(tokens), tokens, 0, key)
    }

/**
 * 分位定级器；正样本不同值少于 4 个时三分位会塌缩（t1==t2==t3），
 * 唯一活跃日只能拿到最浅档。退化时按值排名分档，保证最大值能到 4 档。
 */
internal fun quantileLeveler(
    positive: List<Long>,
    t1: Long,
    t2: Long,
    t3: Long,
): (Long) -> Int {
    // 自行排序（OCR low #827）：排名映射只有在升序时才与「值越大档越高」一致，
    // 不能依赖调用方一定传排好序的列表。
    val distinct = positive.distinct().sorted()
    if (distinct.size < 4) {
        // 按不同值的排名映射到 1..4：1 个值→4，2 个值→2/4，3 个值→2/3/4。
        val rank = distinct.withIndex().associate { (i, v) -> v to i }
        return { v ->
            when {
                v <= 0 -> 0
                distinct.size == 1 -> 4
                else -> {
                    val i = rank[v] ?: 0
                    (2 + i * 2 / (distinct.size - 1)).coerceIn(2, 4)
                }
            }
        }
    }
    return { v ->
        when {
            v <= 0 -> 0
            v <= t1 -> 1
            v <= t2 -> 2
            v <= t3 -> 3
            else -> 4
        }
    }
}

/**
 * 命中判定：floor 取整（toInt 向零截断会把标签区的点击算进第 0 行/列），
 * 且点必须落在格子本体内——格间隙（gap/rowGap）不算命中。
 */
internal fun hitCell(
    weeks: List<List<UsageHeatmapView.Cell?>>,
    x: Float,
    y: Float,
    leftPad: Int,
    topPad: Int,
    cellSize: Int,
    gap: Int,
    rowGap: Int,
): UsageHeatmapView.Cell? {
    if (weeks.isEmpty()) return null
    if (x < leftPad || y < topPad) return null
    val dx = x - leftPad
    val dy = y - topPad
    val ci = kotlin.math.floor(dx / (cellSize + gap)).toInt()
    val ri = kotlin.math.floor(dy / (cellSize + rowGap)).toInt()
    if (ci < 0 || ci >= weeks.size || ri < 0 || ri >= 7) return null
    // 落在格间隙里不算命中。
    if (dx - ci * (cellSize + gap) >= cellSize) return null
    if (dy - ri * (cellSize + rowGap) >= cellSize) return null
    return weeks[ci].getOrNull(ri)
}
