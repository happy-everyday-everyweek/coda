package com.coda.mobileui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * 任务状态标记：行为对齐 React Bits「Status Mark」组件
 * （https://reactbits.dev/c/micro/status-mark ，源码 github.com/DavidHDev/react-bits）。
 *
 * 图形：pending 虚线环 / running 旋转弧 / done 环 + 勾 + 淡底 / failed 环 + 红叉 + 淡底 /
 * cancelled 虚线环 + 灰叉。
 *
 * 状态切换动画（对齐原版）：虚线环与弧之间 300ms 融合（cubic-bezier(0.77,0,0.175,1)）；
 * 弧长 300ms 过渡；旋转弧 1100ms 一圈；勾 / 叉延迟 120ms 后 240ms 描画（ease-out）；
 * 图形颜色 200ms 渐变；底轨与淡底 180~200ms 淡入淡出；离开时勾 / 叉 160ms 擦除。
 */
class StatusMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 当前生命周期状态：pending / running / done / failed / cancelled。 */
    var status: String = "pending"
        private set

    /** 主色（环、弧、叉；running 通常传主题色）。 */
    var inkColor: Int = 0xFF888888.toInt()

    /** done 状态的勾与淡底色。 */
    var doneColor: Int = 0xFF22C55E.toInt()

    /** failed 状态的叉与淡底色。 */
    var errorColor: Int = 0xFFEF4444.toInt()

    // ---------- 当前动画值 ----------
    private var mode = 0f
    private var arc = 1f
    private var travelPx = 0f
    private var ringAlpha = MUTED
    private var trackStroke = 0f
    private var trackFill = 0f
    private var checkP = 0f
    private var crossP = 0f
    private var glyphColor = 0xFF888888.toInt()

    // ---------- 几何（像素） ----------
    private var cxPx = 0f
    private var cyPx = 0f
    private var rPx = 0f
    private var cPx = 0f
    private var pPx = 0f
    private var strokePx = 0f
    private val ringRect = RectF()
    private val checkPath = Path()
    private val crossPath = Path()
    private var checkLenPx = 0f
    private var crossLenPx = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segmentPath = Path()

    // ---------- 缓动（对齐原版曲线） ----------
    private val morphEase: Interpolator = PathInterpolator(0.77f, 0f, 0.175f, 1f)
    private val drawEase: Interpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val uiEase: Interpolator = DecelerateInterpolator()

    private val anims = ArrayList<ValueAnimator>()

    private var spinning = false
    private var lastFrameNs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!spinning) return
            if (lastFrameNs != 0L && cPx > 0f) {
                val dtSec = (frameTimeNanos - lastFrameNs) / 1_000_000_000f
                travelPx -= dtSec * cPx / 1.1f
                if (travelPx < -cPx) travelPx += cPx
            }
            lastFrameNs = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 设置状态。
     *
     * @param value 目标状态：pending / running / done / failed / cancelled
     * @param animate true = 从当前状态平滑过渡（状态变化）；false = 立即跳变（初次渲染）
     */
    fun setStatus(value: String, animate: Boolean = false) {
        if (value == status && !animate) return
        status = value
        val solid = value == "running" || value == "done" || value == "failed"
        val targetArc = if (value == "running") ARC else 1f
        val targetColor = when (value) {
            "done" -> doneColor
            "failed" -> errorColor
            else -> inkColor
        }
        val targetRing = if (solid) 1f else MUTED
        val targetTrackStroke = if (value == "running") TRACK_STROKE else 0f
        val targetTrackFill = if (value == "done" || value == "failed") TRACK_FILL else 0f

        cancelAnims()

        if (!animate) {
            stopSpin()
            mode = if (solid) 1f else 0f
            arc = targetArc
            travelPx = 0f
            glyphColor = targetColor
            ringAlpha = targetRing
            trackStroke = targetTrackStroke
            trackFill = targetTrackFill
            checkP = if (value == "done") 1f else 0f
            crossP = if (value == "failed" || value == "cancelled") 1f else 0f
            if (value == "running") startSpin()
            invalidate()
            return
        }

        // 虚线环 <-> 弧 / 实环 的融合
        if (mode <= 0.001f) arc = targetArc else animateValue(arc, targetArc, 300L, uiEase) { arc = it }
        animateValue(mode, if (solid) 1f else 0f, 300L, morphEase) { mode = it }
        animateValue(ringAlpha, targetRing, 200L, uiEase) { ringAlpha = it }
        animateValue(trackStroke, targetTrackStroke, 200L, uiEase) { trackStroke = it }
        animateValue(trackFill, targetTrackFill, 180L, uiEase) { trackFill = it }
        animateColor(glyphColor, targetColor, 200L)

        if (value == "running") {
            startSpin()
        } else {
            stopSpin()
            // 弧停下：相位滑到最近的对齐点后归零（对齐原版 floor(travel / P) * P + jump(0)）
            val unit = pPx
            val target = if (unit > 0f) Math.floor((travelPx / unit).toDouble()).toFloat() * unit else 0f
            val stop = ValueAnimator.ofFloat(travelPx, target).apply {
                duration = 300L
                interpolator = uiEase
                addUpdateListener {
                    travelPx = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        travelPx = 0f
                        invalidate()
                    }
                })
            }
            anims.add(stop)
            stop.start()
        }

        animateStroke(checkP, value == "done", true)
        animateStroke(crossP, value == "failed" || value == "cancelled", false)
    }

    private fun cancelAnims() {
        for (a in anims) a.cancel()
        anims.clear()
    }

    private fun animateValue(from: Float, to: Float, duration: Long, interp: Interpolator, onUpdate: (Float) -> Unit) {
        if (from == to) return
        val a = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener {
                onUpdate(it.animatedValue as Float)
                invalidate()
            }
        }
        anims.add(a)
        a.start()
    }

    private fun animateColor(from: Int, to: Int, duration: Long) {
        if (from == to) return
        val a = ValueAnimator.ofArgb(from, to).apply {
            this.duration = duration
            interpolator = uiEase
            addUpdateListener {
                glyphColor = it.animatedValue as Int
                invalidate()
            }
        }
        anims.add(a)
        a.start()
    }

    /** 勾 / 叉：进入时延迟 120ms 后 240ms 描画；离开时 160ms 擦除。 */
    private fun animateStroke(from: Float, show: Boolean, isCheck: Boolean) {
        val to = if (show) 1f else 0f
        if (from == to) return
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = if (show) 240L else 160L
            startDelay = if (show) 120L else 0L
            interpolator = if (show) drawEase else uiEase
            addUpdateListener {
                val v = it.animatedValue as Float
                if (isCheck) checkP = v else crossP = v
                invalidate()
            }
        }
        anims.add(a)
        a.start()
    }

    private fun startSpin() {
        if (spinning) return
        spinning = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopSpin() {
        if (!spinning) return
        spinning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (status == "running") startSpin()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSpin()
        cancelAnims()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val side = (15f * density + 1.7f * density * 2f).toInt()
        setMeasuredDimension(
            resolveSize(side + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(side + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availW = (w - paddingLeft - paddingRight).toFloat()
        val availH = (h - paddingTop - paddingBottom).toFloat()
        val side = minOf(availW, availH)
        if (side <= 0f) return
        val s = side / BOX
        cxPx = paddingLeft + availW / 2f
        cyPx = paddingTop + availH / 2f
        rPx = RADIUS * s
        cPx = RING_UNITS * s
        pPx = PERIOD_UNITS * s
        strokePx = STROKE * s
        ringRect.set(cxPx - rPx, cyPx - rPx, cxPx + rPx, cyPx + rPx)
        checkPath.reset()
        checkPath.moveTo(cxPx - 4.5f * s, cyPx + 0.25f * s)
        checkPath.lineTo(cxPx - 1.5f * s, cyPx + 3.25f * s)
        checkPath.lineTo(cxPx + 4.75f * s, cyPx - 3.25f * s)
        checkLenPx = PathMeasure(checkPath, false).length
        crossPath.reset()
        crossPath.moveTo(cxPx - 3.5f * s, cyPx - 3.5f * s)
        crossPath.lineTo(cxPx + 3.5f * s, cyPx + 3.5f * s)
        crossPath.moveTo(cxPx + 3.5f * s, cyPx - 3.5f * s)
        crossPath.lineTo(cxPx - 3.5f * s, cyPx + 3.5f * s)
        crossLenPx = PathMeasure(crossPath, false).length
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rPx <= 0f) return
        strokePaint.strokeWidth = strokePx

        // 1) 底轨：完成态淡底（填充）/ running 浅色底环（描边）
        if (trackFill > 0.001f) {
            fillPaint.color = withAlpha(glyphColor, trackFill)
            canvas.drawCircle(cxPx, cyPx, rPx, fillPaint)
        }
        if (trackStroke > 0.001f) {
            strokePaint.color = withAlpha(glyphColor, trackStroke)
            strokePaint.pathEffect = null
            canvas.drawCircle(cxPx, cyPx, rPx, strokePaint)
        }

        // 2) 主环：8 段虚线 <-> 弧 / 实环（dash 与 gap 随 mode / arc 融合）
        val dash = IDLE_DASH * pPx + (arc * cPx - IDLE_DASH * pPx) * mode
        val gap = (1f - IDLE_DASH) * pPx + ((1f - arc) * cPx - (1f - IDLE_DASH) * pPx) * mode
        strokePaint.color = withAlpha(glyphColor, ringAlpha)
        if (gap <= 0.05f) {
            strokePaint.pathEffect = null
        } else {
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(dash.coerceAtLeast(0.01f), gap), travelPx)
        }
        canvas.drawArc(ringRect, -90f, 359.99f, false, strokePaint)
        strokePaint.pathEffect = null

        // 3) 勾 / 叉（按绘制进度描画）
        if (checkP > 0.001f) drawSegment(canvas, checkPath, checkLenPx, checkP)
        if (crossP > 0.001f) drawSegment(canvas, crossPath, crossLenPx, crossP)
    }

    private fun drawSegment(canvas: Canvas, path: Path, length: Float, progress: Float) {
        strokePaint.color = glyphColor
        strokePaint.pathEffect = null
        if (progress >= 0.999f) {
            canvas.drawPath(path, strokePaint)
            return
        }
        segmentPath.reset()
        PathMeasure(path, false).getSegment(0f, length * progress.coerceIn(0f, 1f), segmentPath, true)
        canvas.drawPath(segmentPath, strokePaint)
    }

    private fun withAlpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private companion object {
        const val BOX = 24f
        const val STROKE = 2f
        const val RADIUS = 9f
        const val IDLE_DASH = 0.3f
        const val ARC = 0.68f
        const val MUTED = 0.55f
        const val TRACK_STROKE = 0.2f
        const val TRACK_FILL = 0.06f
        val RING_UNITS = (2.0 * Math.PI * RADIUS).toFloat()
        val PERIOD_UNITS = RING_UNITS / 8f
    }
}
