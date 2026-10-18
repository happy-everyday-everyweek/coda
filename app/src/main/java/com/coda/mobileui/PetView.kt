package com.coda.mobileui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.coda.mobileui.core.PetFrame
import com.coda.mobileui.core.PetMood
import com.coda.mobileui.core.PetSkin
import com.coda.mobileui.core.PetSkins
import kotlin.math.min

/**
 * 宠物渲染器。
 *
 * 只做三件事：把当前姿态画成像素方块、按片段逐格推进、按状态机切换片段。
 * 它不认识"小芽""Clawd"这类具体形象，换皮肤只换 [skin] 数据。
 *
 * 性能上刻意做到"静止时不耗电"：没有常驻循环，只在需要换帧时 postDelayed 一格；
 * 帧与帧完全相同就跳过重绘。空闲一段时间后自动降到极低频，页面不可见时整体停表。
 */
class PetView(context: Context) : View(context) {

    // ---------------------------------------------------------------- 状态

    var skin: PetSkin = PetSkins.builtin
        set(value) {
            if (field === value) return
            field = value
            cachedPalette = value.resolvePalette(accentLight, accent, accentSoft)
            poseCache.clear()
            restart()
        }

    /** 主题色：皮肤里的 @accent 系列会解析成这三种。 */
    var accent: Int = 0xFF6750A4.toInt()
        set(value) { field = value; refreshPalette() }
    var accentLight: Int = 0xFFB39DF3.toInt()
        set(value) { field = value; refreshPalette() }
    var accentSoft: Int = 0xFF9580DD.toInt()
        set(value) { field = value; refreshPalette() }

    /** 减少动态效果：只在换状态时直接落到代表姿态，不做逐格动画。 */
    var reduceMotion: Boolean = false

    /** 是否允许播放（例如滚动时、页面不可见时关掉，省电）。 */
    var animating: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) restart() else stopTicker()
        }

    /** 状态变化回调，用于让外面同步标签文字。 */
    var onMoodChanged: ((PetMood) -> Unit)? = null

    private var mood: PetMood = PetMood.IDLE
    /** 当前状态是否已经装配过片段（用于 setMood 的幂等判断）。 */
    private var moodReady: Boolean = false
    private var cachedPalette: Map<Char, Int> = skin.resolvePalette(accentLight, accent, accentSoft)
    private val poseCache = HashMap<String, Array<CharArray>>()

    // 播放游标
    private var clipName: String = ""
    private var frames: List<PetFrame> = emptyList()
    private var frameIndex = 0
    private var current: PetFrame? = null
    private var loopClip = true

    // 计时
    private val ticker = Runnable { advance() }
    private var dwellUntil = 0L
    private var fidgetAt = 0L
    private var fidgeting = false
    private var phaseBeforeFidget = -1

    // 位移动画（格 → 像素的平滑过渡）
    private var drawDx = 0f
    private var drawDy = 0f
    private var dxAnim: ValueAnimator? = null
    private var dyAnim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellRect = RectF()
    private val badgeRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        badgeText.textAlign = Paint.Align.CENTER
        applyMood(PetMood.IDLE, animate = false)
    }

    // ---------------------------------------------------------------- 状态机

    /** 切状态。同一状态重复调用不留痕，避免重播进场动画。 */
    fun setMood(next: PetMood, animate: Boolean = true) {
        // 只比 mood 不够：still 类型的状态（如 idle）不设 clipName，
        // 光看 clipName 会误判成"还没播过"而重播；反过来 hide 之后又可能被误当成"已在播"。
        // 用一个自增的 revision 标记"这一版状态是否已经装配过"。
        if (next == mood && moodReady) return
        applyMood(next, animate)
    }

    fun mood(): PetMood = mood

    /**
     * 回到闲置并把播放器复位。
     *
     * 提示条收起时必须调用：否则下一次显示「做完了」时，会因为状态相同而被
     * setMood 的幂等判断直接跳过，动画就不会播。
     */
    fun resetToIdle() {
        moodReady = false
        applyMood(PetMood.IDLE, animate = false)
        moodReady = true
    }

    private fun applyMood(next: PetMood, animate: Boolean) {
        mood = next
        onMoodChanged?.invoke(next)
        val spec = skin.state(next.key)
        val enter = if (animate && !reduceMotion) spec?.enter else null
        if (enter != null && skin.clip(enter).isNotEmpty()) {
            play(enter, loop = false)
        } else {
            val still = spec?.still
            if (still != null) {
                showPose(still)
                frames = emptyList()
                current?.let { schedule(skin.tickMs.toLong()) }
            } else {
                play(spec?.loop, loop = true)
            }
        }
        dwellUntil = if (spec != null && spec.dwellMs > 0) {
            System.currentTimeMillis() + spec.dwellMs
        } else {
            0L
        }
        val every = spec?.fidgetEveryMs ?: 0L
        fidgetAt = if (every > 0) System.currentTimeMillis() + every else 0L
        fidgeting = false
        moodReady = true
    }

    private fun play(name: String?, loop: Boolean) {
        val list = skin.clip(name)
        clipName = name.orEmpty()
        frames = list
        frameIndex = 0
        loopClip = loop
        stopTicker()
        if (list.isEmpty()) {
            current = null
            invalidate()
            return
        }
        val first = list[0]
        current = first
        animateOffset(first)
        invalidate()
        schedule(first.hold * skin.tickMs.toLong())
    }

    private fun advance() {
        if (!animating) return

        // 到点自动回 idle（做完了、出错了都只停留一会）
        if (dwellUntil > 0 && System.currentTimeMillis() >= dwellUntil) {
            dwellUntil = 0
            setMood(PetMood.IDLE)
            return
        }

        // 空闲时插一段随机小动作，看起来像活物
        if (!fidgeting && fidgetAt > 0 && System.currentTimeMillis() >= fidgetAt) {
            val spec = skin.state(mood.key)
            val pick = spec?.fidget?.randomOrNull()
            val extra = skin.clip(pick)
            if (extra.isNotEmpty()) {
                phaseBeforeFidget = frameIndex
                fidgeting = true
                frames = extra
                frameIndex = 0
                current = extra[0]
                animateOffset(extra[0])
                invalidate()
                schedule(extra[0].hold * skin.tickMs.toLong())
                return
            }
            fidgetAt = System.currentTimeMillis() + (spec?.fidgetEveryMs ?: 0L)
        }

        if (frames.isEmpty()) {
            // 静止姿态：什么都不用做，等下一次状态变化
            stopTicker()
            return
        }

        val next = frameIndex + 1
        if (next >= frames.size) {
            if (loopClip) {
                frameIndex = 0
            } else if (fidgeting) {
                // 小动作播完，回到主循环的当前位置
                fidgeting = false
                val spec = skin.state(mood.key)
                val main = skin.clip(spec?.loop)
                fidgetAt = if (spec != null && spec.fidgetEveryMs > 0) {
                    System.currentTimeMillis() + spec.fidgetEveryMs
                } else {
                    0L
                }
                frames = main
                frameIndex = if (main.isEmpty()) 0 else {
                    (phaseBeforeFidget.coerceAtLeast(0)) % main.size
                }
                if (main.isEmpty()) {
                    val still = spec?.still
                    if (still != null) showPose(still)
                    stopTicker()
                    return
                }
                val f = main[frameIndex]
                current = f
                animateOffset(f)
                invalidate()
                schedule(f.hold * skin.tickMs.toLong())
                return
            } else {
                // 进场片段播完，转主循环
                val spec = skin.state(mood.key)
                val loop = skin.clip(spec?.loop)
                if (loop.isNotEmpty()) {
                    play(spec?.loop, loop = true)
                } else {
                    val still = spec?.still
                    if (still != null) showPose(still)
                    frames = emptyList()
                    stopTicker()
                }
                return
            }
        } else {
            frameIndex = next
        }

        val frame = frames[frameIndex]
        val same = frame.pose == current?.pose && frame.dx == current?.dx && frame.dy == current?.dy
        current = frame
        animateOffset(frame)
        if (!same) invalidate()
        schedule(frame.hold * skin.tickMs.toLong())
    }

    private fun schedule(delayMs: Long) {
        removeCallbacks(ticker)
        if (!animating) return
        postDelayed(ticker, delayMs.coerceAtLeast(16))
    }

    private fun stopTicker() {
        removeCallbacks(ticker)
    }

    private fun restart() {
        clipName = ""
        applyMood(mood, animate = false)
    }

    private fun showPose(pose: String) {
        current = PetFrame(pose, 1)
        invalidate()
    }

    private fun refreshPalette() {
        cachedPalette = skin.resolvePalette(accentLight, accent, accentSoft)
        invalidate()
    }

    /** 位移做 120ms 插值，逐格跳会显得抖。 */
    private fun animateOffset(frame: PetFrame) {
        val cell = cellSize()
        val tx = frame.dx * cell
        val ty = frame.dy * cell
        if (reduceMotion || !isAttachedToWindow) {
            drawDx = tx
            drawDy = ty
            return
        }
        if (drawDx == tx && drawDy == ty) return
        dxAnim?.cancel()
        dyAnim?.cancel()
        if (drawDx != tx) {
            dxAnim = ValueAnimator.ofFloat(drawDx, tx).apply {
                duration = 120
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawDx = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (drawDy != ty) {
            dyAnim = ValueAnimator.ofFloat(drawDy, ty).apply {
                duration = 120
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawDy = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    // ---------------------------------------------------------------- 绘制

    private fun cellSize(): Float {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return 0f
        // 上方留一格给头顶标记，下方留一格给落地阴影
        return min(w / skin.cols, h / (skin.rows + 2))
    }

    private fun grid(pose: String): Array<CharArray> =
        poseCache.getOrPut(pose) { skin.gridOf(pose) }

    override fun onDraw(canvas: Canvas) {
        val cell = cellSize()
        if (cell <= 0f) return
        val frame = current ?: return
        val g = grid(frame.pose)
        // 整体水平居中；垂直留出顶部空间给标记与负坐标部件
        val offsetX = paddingLeft + (width - paddingLeft - paddingRight - skin.cols * cell) / 2f + drawDx
        val offsetY = paddingTop + cell * 2 + drawDy

        for (y in 0 until skin.rows) {
            val row = g[y]
            var x = 0
            while (x < skin.cols) {
                val ch = row[x]
                if (ch == '.') {
                    x++
                    continue
                }
                val color = cachedPalette[ch]
                if (color == null) {
                    x++
                    continue
                }
                // 横向合并同色格，减少 drawRect 次数
                var end = x + 1
                while (end < skin.cols && row[end] == ch) end++
                paint.color = color
                cellRect.set(
                    offsetX + x * cell,
                    offsetY + y * cell,
                    offsetX + end * cell,
                    offsetY + (y + 1) * cell,
                )
                canvas.drawRect(cellRect, paint)
                x = end
            }
        }

        drawBadge(canvas, cell, offsetX, offsetY)
    }

    /** 头顶状态标记：等你确认是橙色问号，做完了是绿色对勾，出错是红色感叹号。 */
    private fun drawBadge(canvas: Canvas, cell: Float, originX: Float, originY: Float) {
        val spec = skin.state(mood.key) ?: return
        if (spec.badge.isEmpty()) return
        val color = when (spec.badgeColor) {
            "" -> 0xFF6750A4.toInt()
            else -> runCatching { android.graphics.Color.parseColor(spec.badgeColor) }
                .getOrDefault(0xFF6750A4.toInt())
        }
        val r = cell * 2.1f
        val cx = originX + skin.cols * cell / 2f + cell * 5.6f
        val cy = originY - cell * 0.4f
        // 轻微呼吸，不抢眼但能看出"它在等你"
        val pulse = if (reduceMotion) 1f else {
            val t = (System.currentTimeMillis() % 1600L) / 1600f
            1f + 0.06f * kotlin.math.sin(t * 2f * Math.PI).toFloat()
        }
        badgePaint.color = color
        badgePaint.alpha = 240
        badgeRect.set(cx - r * pulse, cy - r * pulse, cx + r * pulse, cy + r * pulse)
        canvas.drawCircle(badgeRect.centerX(), badgeRect.centerY(), r * pulse, badgePaint)

        badgeText.color = 0xFFFFFFFF.toInt()
        badgeText.textSize = r * 1.35f
        badgeText.isFakeBoldText = true
        val baseline = badgeRect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2f
        canvas.drawText(spec.badge, badgeRect.centerX(), baseline, badgeText)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTicker()
        dxAnim?.cancel()
        dyAnim?.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) restart() else stopTicker()
    }

    /** 让它可以按宽高比自适应：外部给宽度，高度按皮肤比例算。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = skin.cols.coerceAtLeast(1)
        val cell = (w - paddingLeft - paddingRight) / cols.toFloat()
        val h = (cell * (skin.rows + 2)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }
}