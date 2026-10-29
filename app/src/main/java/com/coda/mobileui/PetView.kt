package com.coda.mobileui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.coda.mobileui.core.ActorSnapshot
import com.coda.mobileui.core.GlyphDraw
import com.coda.mobileui.core.PetActor
import com.coda.mobileui.core.PetMood
import com.coda.mobileui.core.PetSkin
import com.coda.mobileui.core.PetSkins
import com.coda.mobileui.core.PixelGrid
import kotlin.math.min

/**
 * 宠物渲染器。
 *
 * 严格按 critter 的绘制方式：地面是基准线，基框 18×10 的 row height 就是地面；
 * 姿态用像素包围盒绘制，所以抬手、垂腿这些超出基框的像素照样画得出来，不会被裁掉。
 *
 * 每 tick 由 PetActor 推进，渲染器只把 ActorSnapshot 画出来；快照相同就跳过重绘。
 * 没有常驻动画循环：只在下一个 tick 才 postDelayed 一次，页面不可见时停表。
 */
class PetView(
    context: Context,
) : View(context) {
    var skin: PetSkin = PetSkins.resolve(context, PetSkins.defaultId)
        set(value) {
            if (field === value) return
            field = value
            gridCache.clear()
            bitmapCache.clear()
            actor = PetActor(value)
            actor.hidden = !isShown
            actor.reduceMotion = reduceMotion
            lastSnapshot = null
            requestLayout()
            invalidate()
        }

    var reduceMotion: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            actor.reduceMotion = value
            lastSnapshot = null
            invalidate()
        }

    /** 允许播放（页面不可见或滚动中可关掉，省电）。 */
    var animating: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) scheduleTick() else removeCallbacks(ticker)
        }

    /** 状态变化回调，方便外面同步文字。 */
    var onMoodChanged: ((PetMood) -> Unit)? = null

    private var actor = PetActor(skin)
    private val gridCache = HashMap<String, PixelGrid?>()
    private val bitmapCache = HashMap<String, android.graphics.Bitmap?>()
    private var lastSnapshot: ActorSnapshot? = null
    private var current: ActorSnapshot? = null

    private val ticker = Runnable { onTick() }
    private val paint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        shadowPaint.color = 0x47000000
    }

    // ------------------------------------------------------------ 对外

    /** 切状态。 */
    fun setMood(
        mood: PetMood,
        animate: Boolean = true,
    ) {
        val before = actor.mood
        actor.setMood(mood, animate)
        if (before != actor.mood) onMoodChanged?.invoke(actor.mood)
        pump()
    }

    fun mood(): PetMood = actor.mood

    /** 换工作变体（laptop / think / read / bash / juggle）。 */
    fun setActivity(activity: String?) {
        actor.setActivity(activity)
    }

    /** 点一下逗它。 */
    fun pet() {
        actor.pet()
        pump()
    }

    fun wake() {
        actor.wake()
        pump()
    }

    /** 立即推进一帧（状态刚变时不必等下一个 tick）。 */
    private fun pump() {
        lastSnapshot = null
        onTick()
    }

    // ------------------------------------------------------------ 时钟

    private fun onTick() {
        removeCallbacks(ticker)
        if (!animating) return
        actor.hidden = !isShown
        actor.step()
        current = actor.snapshot()
        if (current != lastSnapshot) {
            lastSnapshot = current
            invalidate()
        }
        scheduleTick()
    }

    private fun scheduleTick() {
        removeCallbacks(ticker)
        if (!animating) return
        postDelayed(ticker, skin.tickMs.toLong().coerceAtLeast(16))
    }

    // ------------------------------------------------------------ 绘制

    /** 一整格多少像素：基框宽度铺满控件宽度。 */
    private fun unit(): Float {
        val w = (width - paddingLeft - paddingRight).toFloat()
        if (w <= 0f) return 0f
        return w / skin.cols.coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        val u = unit()
        if (u <= 0f) return
        val snap = current ?: return
        if (!snap.visible) return

        val groundY = paddingTop + skin.rows * u
        val baseLeft = paddingLeft + ((width - paddingLeft - paddingRight) - skin.cols * u) / 2f

        // 影子：贴在脚下，宽度随离地高度变化
        if (snap.shadowWidth > 0) {
            val sw = snap.shadowWidth * u
            val sh = min(2f * u, u * 0.5f).coerceAtLeast(2f)
            val cx = baseLeft + (skin.cols / 2f) * u
            rect.set(cx - sw / 2f, groundY - sh, cx + sw / 2f, groundY)
            canvas.drawRoundRect(rect, sh, sh, shadowPaint)
        }

        drawPose(canvas, snap, baseLeft, groundY, u)

        // 字形：道具 → 粒子 → 挂件 → 标记，都在身体之上，不裁切
        for (d in snap.props) drawGlyph(canvas, d, baseLeft, groundY, u)
        for (d in snap.particles) drawGlyph(canvas, d, baseLeft, groundY, u)
        snap.accessory?.let { drawGlyph(canvas, it, baseLeft, groundY, u) }
        snap.badge?.let { drawGlyph(canvas, it, baseLeft, groundY, u) }
    }

    /**
     * 画一个姿态。
     *
     * 基框坐标 y 向下、地面在第 rows 行；包围盒 minY 可能为负（画到地面以上更远），
     * 也可能超过 rows（垂下的腿）。位置按 (rows - minY - height) 反推。
     */
    private fun drawPose(
        canvas: Canvas,
        snap: ActorSnapshot,
        baseLeft: Float,
        groundY: Float,
        u: Float,
    ) {
        val grid = gridCache.getOrPut(snap.pose) { skin.gridOf(snap.pose) } ?: return
        val bmp = bitmapFor(grid, snap)
        val left = baseLeft + (snap.bodyX + grid.minX) * u
        val top = groundY + (snap.bodyY - grid.minY - grid.height) * u
        rect.set(left, top, left + grid.width * u, top + grid.height * u)
        paint.alpha = 255
        paint.isFilterBitmap = false
        canvas.drawBitmap(bmp, null, rect, paint)
    }

    /**
     * 一个姿态的一张位图。
     *
     * 缓存键带上淡化标记：原项目里 idle 与 error 会给整个状态加一层 dim，
     * 同姿态在两种状态下颜色不同，不能共用一个位图。
     */
    private fun bitmapFor(
        grid: PixelGrid,
        snap: ActorSnapshot,
    ): android.graphics.Bitmap {
        val key = snap.pose + if (snap.tintDim) "#dim" else ""
        bitmapCache[key]?.let { return it }
        val w = grid.width.coerceAtLeast(1)
        val h = grid.height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val line = grid.rows[y]
            for (x in 0 until w) {
                val ch = line[x]
                if (ch == '.') continue
                val hex = skin.palette[ch.toString()] ?: continue
                px[y * w + x] = skin.resolveColor(hex, snap.tintDim)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bitmapCache[key] = bmp
        return bmp
    }

    /** 画一个字形。x/y 是左上格，y 向下、地面在第 rows 行。 */
    private fun drawGlyph(
        canvas: Canvas,
        d: GlyphDraw,
        baseLeft: Float,
        groundY: Float,
        u: Float,
    ) {
        val rows = skin.glyph(d.glyph)
        if (rows.isEmpty()) return
        val w = rows.first().length
        val h = rows.size
        if (w <= 0) return
        paint.color = runCatching { android.graphics.Color.parseColor(d.color) }.getOrDefault(0xFFFFFFFF.toInt())
        paint.alpha = (d.alpha.coerceIn(0.0, 1.0) * 255).toInt()
        val left = baseLeft + d.x * u
        val top = groundY + (d.y - skin.rows) * u
        for (r in 0 until h) {
            val line = rows[r]
            var c = 0
            while (c < line.length) {
                if (line[c] != '#') {
                    c++
                    continue
                }
                var end = c + 1
                while (end < line.length && line[end] == '#') end++
                rect.set(left + c * u, top + r * u, left + end * u, top + (r + 1) * u)
                canvas.drawRect(rect, paint)
                c = end
            }
        }
        paint.alpha = 255
    }

    // ------------------------------------------------------------ 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
        bitmapCache.clear()
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            scheduleTick()
        } else {
            removeCallbacks(ticker)
        }
    }

    /** 高度按基框比例算：宽度定下来一格多大就定了，控件高度 = 基框 + 上下余量。 */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = skin.cols.coerceAtLeast(1)
        val cell = (w - paddingLeft - paddingRight) / cols.toFloat()
        // 上下各留两格：头顶挂件要空间，脚下要影子
        val h = (cell * (skin.rows + 4)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }
}
