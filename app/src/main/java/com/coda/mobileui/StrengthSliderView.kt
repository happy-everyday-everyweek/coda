package com.coda.mobileui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 模型强度滑杆，5 档：轻度 / 中 / 高 / 极高 / Max。
 *
 * 轨道左端到滑块为渐变填充（深主色 -> 主色），中间 5 个刻度点，滑块是白色圆；
 * 到 Max 档时滑块周围扩散粒子。颜色全部取自主题，深色模式与自定义主色都会跟着变。
 */
class StrengthSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var level: Int = 0
        private set

    var onLevelChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val knobRadius = dp(18f)
    private val trackHeight = dp(30f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000 }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePath = Path()

    /** 星点整体透明度：只在 Max 档显示。 */
    private var starsAlpha = 0f
    private var starsAnimator: ValueAnimator? = null

    /** Max 档的紫色波浪流光位置，副值表示当前没有波浪。 */
    private var waveProgress = -1f
    private var waveAnimator: ValueAnimator? = null

    /** 轨道上缓慢闪烁的星点。 */
    private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

    private val stars = mutableListOf<Star>()
    private var starAnimator: ValueAnimator? = null
    private var clock = 0f

    private val trackColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurfaceContainerHighest,
        Color.LTGRAY,
    )
    private val primaryColor = MaterialColors.getColor(
        this,
        androidx.appcompat.R.attr.colorPrimary,
        Color.BLACK,
    )
    private val fillStart = blend(primaryColor, trackColor, 0.45f)

    private var fraction = 0f
    private var levelAnimator: ValueAnimator? = null
    private var particleAnimator: ValueAnimator? = null
    private val particles = mutableListOf<Particle>()

    private class Particle(
        var x: Float,
        var y: Float,
        val dx: Float,
        val dy: Float,
        var life: Float,
        val radius: Float,
        val square: Boolean,
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (stars.isEmpty()) buildStars(w, h)
    }

    /** 沿轨道铺一层星点，动画里缓慢闪烁。 */
    private fun buildStars(w: Int, h: Int) {
        val left = knobRadius
        val right = w - knobRadius
        val centerY = h / 2f
        val random = Random(7)
        repeat(38) {
            stars += Star(
                left + random.nextFloat() * (right - left),
                centerY + (random.nextFloat() - 0.5f) * trackHeight * 0.72f,
                dp(0.8f) + random.nextFloat() * dp(1.7f),
                random.nextFloat() * 6.2832f,
                0.5f + random.nextFloat() * 1.1f,
            )
        }
        if (starAnimator?.isRunning != true) {
            starAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2200
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    clock += 0.055f
                    invalidate()
                }
                start()
            }
        }
    }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return

        val centerY = height / 2f
        val left = knobRadius
        val right = width - knobRadius
        val radius = trackHeight / 2f

        trackPaint.color = trackColor
        canvas.drawRoundRect(
            RectF(left, centerY - radius, right, centerY + radius),
            radius,
            radius,
            trackPaint,
        )

        val knobX = left + (right - left) * fraction
        if (knobX > left + 1f) {
            fillPaint.shader = LinearGradient(
                left, 0f, right, 0f,
                fillStart, primaryColor,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(
                RectF(left, centerY - radius, knobX, centerY + radius),
                radius,
                radius,
                fillPaint,
            )
            fillPaint.shader = null
        }

        val steps = LEVELS.size - 1
        val dotInset = dp(12f)
        val dotStart = left + dotInset
        val dotSpan = (right - left) - dotInset * 2
        for (i in 0..steps) {
            val x = dotStart + dotSpan * i / steps
            dotPaint.color = if (x <= knobX) 0x66FFFFFF else 0x33000000
            canvas.drawCircle(x, centerY, dp(2.6f), dotPaint)
        }

        // 轨道上的星点：只有 Max 档才出现，颜色跟随主题（主色的浅调）
        if (starsAlpha > 0.01f) {
            stars.forEach { s ->
                val alpha = (0.28f + 0.5f * (0.5f + 0.5f * sin(clock * s.speed + s.phase)))
                    .coerceIn(0f, 1f) * starsAlpha
                starPaint.color = Color.argb(
                    (alpha * 255).toInt().coerceIn(0, 255),
                    Color.red(primaryColor),
                    Color.green(primaryColor),
                    Color.blue(primaryColor),
                )
                canvas.drawCircle(s.x, s.y, s.radius, starPaint)
            }
        }

        canvas.drawCircle(knobX, centerY + dp(2f), knobRadius, knobShadowPaint)
        canvas.drawCircle(knobX, centerY, knobRadius, knobPaint)

        if (particles.isNotEmpty()) {
            particles.forEach { p ->
                val alpha = (p.life * 150).toInt().coerceIn(0, 255)
                particlePaint.color = if (p.life > 0.72f) {
                    Color.argb(alpha, 255, 255, 255)
                } else {
                    Color.argb(
                        alpha,
                        Color.red(primaryColor),
                        Color.green(primaryColor),
                        Color.blue(primaryColor),
                    )
                }
                if (p.square) {
                    canvas.drawRect(
                        p.x - p.radius, p.y - p.radius,
                        p.x + p.radius, p.y + p.radius,
                        particlePaint,
                    )
                } else {
                    canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val left = knobRadius
                val right = width - knobRadius
                val raw = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                val snapped = Math.round(raw * (LEVELS.size - 1))
                if (snapped != level) {
                    setLevel(snapped, animate = false)
                } else {
                    fraction = raw
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                setLevel(level, animate = true)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** 切换档位；animate 为 true 时滑块弹到目标刻度。 */
    fun setLevel(value: Int, animate: Boolean = true) {
        val target = value.coerceIn(0, LEVELS.size - 1)
        val to = target / (LEVELS.size - 1f)
        val changed = target != level
        level = target

        levelAnimator?.cancel()
        if (animate) {
            levelAnimator = ValueAnimator.ofFloat(fraction, to).apply {
                duration = 320
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fraction = to
            invalidate()
        }

        val isMax = target == LEVELS.size - 1
        setStarsVisible(isMax)
        if (isMax) {
            spawnParticles()
        } else {
            particles.clear()
            waveAnimator?.cancel()
            waveProgress = -1f
            invalidate()
        }
        if (changed) onLevelChanged?.invoke(target)
    }

    fun reset() = setLevel(0, animate = true)

    /** Max 档：滑块周围的粒子扩散。 */
    /** 星点只在 Max 档显示，淡入淡出。 */
    private fun setStarsVisible(visible: Boolean) {
        val target = if (visible) 1f else 0f
        if (starsAlpha == target) return
        starsAnimator?.cancel()
        starsAnimator = ValueAnimator.ofFloat(starsAlpha, target).apply {
            duration = 260
            addUpdateListener {
                starsAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Max 档：一条紫色波浪从左流到右，只走一次。 */
    private fun startWave() {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(-0.18f, 1.18f).apply {
            duration = 900
            interpolator = PathInterpolator(0.3f, 0f, 0.3f, 1f)
            addUpdateListener {
                waveProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 波浪本体：一条带弧度的紫色流光带。 */
    private fun drawWave(canvas: Canvas, left: Float, right: Float, centerY: Float, radius: Float) {
        if (waveProgress < -0.1f) return

        val width = (right - left) * 0.42f
        val center = left + (right - left) * waveProgress
        val amplitude = trackHeight * 0.16f
        val start = center - width / 2f

        wavePath.reset()
        var x = start
        var first = true
        while (x <= center + width / 2f) {
            val t = (x - start) / width
            val y = centerY + sin(t * 6.76f) * amplitude
            if (first) {
                wavePath.moveTo(x, y)
                first = false
            } else {
                wavePath.lineTo(x, y)
            }
            x += dp(3f)
        }

        canvas.save()
        canvas.clipRect(left - radius, centerY - trackHeight, right + radius, centerY + trackHeight)
        wavePaint.shader = LinearGradient(
            center - width / 2f, 0f, center + width / 2f, 0f,
            intArrayOf(0x00FFFFFF, Color.argb(150, 196, 160, 255), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        wavePaint.strokeWidth = trackHeight * 1.5f
        canvas.drawPath(wavePath, wavePaint)
        wavePaint.shader = null
        canvas.restore()
    }

    private fun spawnParticles() {
        particles.clear()
        val centerY = height / 2f
        val left = knobRadius
        val right = width - knobRadius
        val knobX = left + (right - left) * fraction

        repeat(16) {
            val angle = Random.nextFloat() * 6.2832f
            val speed = dp(18f) + Random.nextFloat() * dp(26f)
            particles += Particle(
                knobX,
                centerY,
                cos(angle) * speed,
                sin(angle) * speed,
                1f,
                dp(1.4f) + Random.nextFloat() * dp(2.0f),
                Random.nextFloat() < 0.4f,
            )
        }

        if (particleAnimator?.isRunning != true) {
            particleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                addUpdateListener { anim ->
                    val dt = 0.033f
                    particles.forEach { p ->
                        p.x += p.dx * dt
                        p.y += p.dy * dt
                        p.life -= dt * 1.1f
                    }
                    if (particles.isNotEmpty() && particles.all { it.life <= 0f }) {
                        particles.clear()
                        anim.cancel()
                    }
                    invalidate()
                }
                start()
            }
        }
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) * ratio + Color.red(b) * (1 - ratio)).toInt()
        val g = (Color.green(a) * ratio + Color.green(b) * (1 - ratio)).toInt()
        val bl = (Color.blue(a) * ratio + Color.blue(b) * (1 - ratio)).toInt()
        return Color.rgb(r, g, bl)
    }

    companion object {
        /** 五档：轻度 / 中 / 高 / 极高 / Max。 */
        val LEVELS = arrayOf("轻度", "中", "高", "极高", "Max")
    }
}