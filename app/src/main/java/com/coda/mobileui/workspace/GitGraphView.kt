package com.coda.mobileui.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提交图：自绘泳道、节点与父子连线，并附带提交摘要。
 *
 * 纵向拖动浏览，点击某一行回调该提交。行高与泳道宽按屏幕密度换算。
 */
class GitGraphView(context: Context) : View(context) {

    var commits: List<Git.Commit> = emptyList()
        set(value) {
            field = value
            rebuild()
            scrollY = 0f
            invalidate()
        }

    var selected: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onPick: ((Git.Commit) -> Unit)? = null

    private val rowHeight = dp(62f)
    private val laneWidth = dp(22f)
    private val leftPad = dp(12f)
    private val rightPad = dp(14f)
    private val nodeRadius = dp(5f)
    private val lineWidth = dp(2f)
    private val slop = dp(6f)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13.5f)
        isSubpixelText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        isSubpixelText = true
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        isSubpixelText = true
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD,
        )
    }
    private val rowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val path = Path()

    private val laneColors = intArrayOf(
        colorOf(androidx.appcompat.R.attr.colorPrimary),
        0xFF4C8DF6.toInt(),
        0xFF35B37E.toInt(),
        0xFFE08A2E.toInt(),
        0xFF9B6BE0.toInt(),
        0xFFE0574C.toInt(),
        0xFF2AA8B8.toInt(),
    )

    private var laneOf = IntArray(0)
    private var rowOf = HashMap<String, Int>()
    private var laneCount = 1
    private var scrollY = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val surfaceColor: Int get() = colorOf(com.google.android.material.R.attr.colorSurface)
    private val onSurfaceColor: Int get() = colorOf(com.google.android.material.R.attr.colorOnSurface)
    private val variantColor: Int
        get() = colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val outlineColor: Int
        get() = colorOf(com.google.android.material.R.attr.colorOutlineVariant)
    private val primaryColor: Int get() = colorOf(androidx.appcompat.R.attr.colorPrimary)

    init {
        setBackgroundColor(surfaceColor)
        laneColors[0] = primaryColor
    }

    // ------------------------------------------------------------ 泳道计算

    private fun rebuild() {
        rowOf = HashMap(commits.size)
        for ((index, commit) in commits.withIndex()) rowOf[commit.hash] = index
        val active = ArrayList<String?>()
        val result = IntArray(commits.size)
        for ((index, commit) in commits.withIndex()) {
            var lane = -1
            for (i in active.indices) {
                if (active[i] == commit.hash) {
                    lane = i
                    break
                }
            }
            if (lane < 0) {
                lane = active.indexOfFirst { it == null }
                if (lane < 0) {
                    active.add(null)
                    lane = active.size - 1
                }
            }
            result[index] = lane
            active[lane] = commit.parents.firstOrNull()
            for (parent in commit.parents.drop(1)) {
                if (active.none { it == parent }) {
                    val free = active.indexOfFirst { it == null }
                    if (free >= 0) active[free] = parent else active.add(parent)
                }
            }
            for (i in active.indices) {
                val expected = active[i] ?: continue
                if (!rowOf.containsKey(expected)) active[i] = null
            }
        }
        laneOf = result
        laneCount = ((result.maxOrNull() ?: 0) + 1).coerceAtLeast(1)
    }

    // ---------------------------------------------------------------- 绘制

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentHeight = (commits.size * rowHeight).toInt() + dp(16f).toInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec)
            else -> contentHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun maxScroll(): Float =
        (commits.size * rowHeight + dp(8f) - height).coerceAtLeast(0f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (commits.isEmpty()) return
        val graphWidth = leftPad + laneCount * laneWidth
        val textLeft = graphWidth + dp(10f)
        val textWidth = (width - textLeft - rightPad).coerceAtLeast(dp(40f))
        val first = (scrollY / rowHeight).toInt().coerceIn(0, commits.size - 1)
        val last = ((scrollY + height) / rowHeight).toInt().coerceIn(0, commits.size - 1)

        for (row in first..last) {
            val top = row * rowHeight - scrollY
            rowLinePaint.color = withAlpha(outlineColor, 0.5f)
            canvas.drawLine(textLeft, top, width - rightPad, top, rowLinePaint)
        }

        for (row in first..last) {
            val commit = commits[row]
            val lane = laneOf.getOrElse(row) { 0 }
            val top = row * rowHeight - scrollY
            val midY = top + rowHeight / 2f
            val startX = leftPad + lane * laneWidth + laneWidth / 2f
            val color = laneColors[((commit.hash.hashCode() % laneColors.size) + laneColors.size) % laneColors.size]
            linePaint.color = withAlpha(color, 0.75f)
            for (parent in commit.parents) {
                val parentRow = rowOf[parent] ?: continue
                val parentLane = laneOf.getOrElse(parentRow) { lane }
                val endX = leftPad + parentLane * laneWidth + laneWidth / 2f
                val endY = parentRow * rowHeight - scrollY + rowHeight / 2f
                path.reset()
                path.moveTo(startX, midY)
                val bend = (endY - midY) / 2f
                path.cubicTo(startX, midY + bend, endX, endY - bend, endX, endY)
                canvas.drawPath(path, linePaint)
            }
        }

        for (row in first..last) {
            val commit = commits[row]
            val lane = laneOf.getOrElse(row) { 0 }
            val top = row * rowHeight - scrollY
            val midY = top + rowHeight / 2f
            val centerX = leftPad + lane * laneWidth + laneWidth / 2f
            val color = laneColors[((commit.hash.hashCode() % laneColors.size) + laneColors.size) % laneColors.size]
            nodePaint.color = color
            canvas.drawCircle(centerX, midY, nodeRadius, nodePaint)
            if (commit.hash == selected) {
                ringPaint.color = onSurfaceColor
                canvas.drawCircle(centerX, midY, nodeRadius + dp(3f), ringPaint)
            }

            val titleY = top + rowHeight / 2f - dp(4f)
            titlePaint.color = onSurfaceColor
            val subject = commit.subject.ifEmpty { commit.hash.take(7) }
            canvas.drawText(ellipsize(subject, titlePaint, textWidth), textLeft, titleY, titlePaint)

            var refX = textLeft
            val refY = top + rowHeight / 2f + dp(15f)
            if (commit.refs.isNotEmpty()) {
                refPaint.color = primaryColor
                val refText = ellipsize(commit.refs.replace("HEAD -> ", ""), refPaint, textWidth * 0.55f)
                canvas.drawText(refText, refX, refY, refPaint)
                refX += refPaint.measureText(refText) + dp(6f)
            }
            smallPaint.color = variantColor
            val detail = "${commit.hash.take(7)} · ${commit.author} · ${timeFormat.format(Date(commit.time * 1000L))}"
            val remain = (textWidth - (refX - textLeft)).coerceAtLeast(dp(30f))
            canvas.drawText(ellipsize(detail, smallPaint, remain), refX, refY, smallPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                lastTouchY = event.y
                scrollY = (scrollY - dy).coerceIn(0f, maxScroll())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val movedX = kotlin.math.abs(event.x - downX)
                val movedY = kotlin.math.abs(event.y - downY)
                if (movedX < slop && movedY < slop) {
                    val row = ((event.y + scrollY) / rowHeight).toInt()
                    commits.getOrNull(row)?.let { onPick?.invoke(it) }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 滚到指定提交所在的行。 */
    fun scrollToCommit(hash: String) {
        val row = rowOf[hash] ?: return
        scrollY = (row * rowHeight - height / 2f + rowHeight / 2f).coerceIn(0f, maxScroll())
        invalidate()
    }

    // ---------------------------------------------------------------- 工具

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24 and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun colorOf(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        } else {
            0xFF808080.toInt()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}