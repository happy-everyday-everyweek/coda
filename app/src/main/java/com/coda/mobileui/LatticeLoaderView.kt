package com.coda.mobileui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Lattice 点阵脉冲指示器：3x3 网格按 orbit 图案（外圈顺时针、中心为洞）级联点亮。
 * 动画参数复刻 react-bits 的 LatticeLoader：单格错相 108ms、全程 864ms，
 * 关键帧 0/100% 为 idle、18/42% 为峰值、62% 回到 idle，缓动 cubic-bezier(0.77, 0, 0.175, 1)。
 */
class LatticeLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // orbit 图案：按行主序的每格序号，-1 为洞（不参与动画）。
    private val cells = intArrayOf(0, 1, 2, 7, -1, 3, 6, 5, 4)
    private val dMs = 90f * 1.2f
    private val cycleMs = 8f * dMs
    private val idle = 0.15f

    private val density = resources.displayMetrics.density
    private val cellPx = 6f * density
    private val gapPx = 2f * density
    private val radiusPx = (cellPx * 0.25f).coerceAtLeast(1f)
    private val rect = RectF()

    private var color = 0xFF888888.toInt()
    private var offsetMs = 0L
    private var startAt = 0L
    private var animating = false
    private var lastTickDs = -1L

    /** 每 0.1 秒回调一次已用时长（毫秒）。 */
    var onTick: ((Long) -> Unit)? = null

    fun setIndicatorColor(c: Int) {
        color = c
        invalidate()
    }

    /** 开始动画；offset 为已流逝时长，用于重建视图后恢复相位与计时。 */
    fun start(offset: Long) {
        offsetMs = offset
        startAt = SystemClock.uptimeMillis()
        if (!animating) {
            animating = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        if (!animating) return
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun elapsed(): Long = offsetMs + (SystemClock.uptimeMillis() - startAt)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            invalidate()
            val t = elapsed()
            val ds = t / 100
            if (ds != lastTickDs) {
                lastTickDs = ds
                onTick?.invoke(t)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (3 * cellPx + 2 * gapPx).toInt()
        setMeasuredDimension(
            resolveSize(side + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(side + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = elapsed().toFloat()
        val ox = paddingLeft.toFloat()
        val oy = paddingTop.toFloat()
        var idx = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val unit = cells[idx]
                idx++
                val alpha = if (unit < 0) idle * 0.47f else cellAlpha(t, unit)
                if (alpha <= 0.004f) continue
                paint.color = color
                paint.alpha = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                val left = ox + col * (cellPx + gapPx)
                val top = oy + row * (cellPx + gapPx)
                rect.set(left, top, left + cellPx, top + cellPx)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            }
        }
    }

    private fun cellAlpha(t: Float, unit: Int): Float {
        val ti = t - unit * dMs
        if (ti < 0f) return idle
        return pulse(ti % cycleMs / cycleMs)
    }

    /** 关键帧 0/100% idle、18/42% peak、62% idle。 */
    private fun pulse(p: Float): Float = when {
        p < 0.18f -> idle + (1f - idle) * ease(p / 0.18f)
        p < 0.42f -> 1f
        p < 0.62f -> 1f - (1f - idle) * ease((p - 0.42f) / 0.2f)
        else -> idle
    }

    /** cubic-bezier(0.77, 0, 0.175, 1)：给定进度 x 返回对应插值。 */
    private fun ease(x: Float): Float {
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(16) {
            t = (lo + hi) / 2f
            if (bezX(t) < x) lo = t else hi = t
        }
        return bezY(t)
    }

    private fun bezX(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * 0.77f + 3f * u * t * t * 0.175f + t * t * t
    }

    private fun bezY(t: Float): Float {
        val u = 1f - t
        return 3f * u * t * t + t * t * t
    }
}
