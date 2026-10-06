package com.zcode.mobileui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * 长按发送按钮后浮出的模式选择条（复刻参考交互）：
 * 竖向一条深色胶囊，内部三行「标签 + 圆点」，手指上下移动切换，
 * 选中行的圆点变成向上的箭头，标签点亮。
 */
class SendModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 三种发送模式：从上到下依次是 Yolo、Build、Chat。 */
        val MODES = arrayOf("Yolo", "Build", "Chat")
    }

    /** 当前选中项，-1 表示未激活。 */
    var selectedIndex = -1
        private set

    var onSelectionChanged: ((Int) -> Unit)? = null

    /** 手指回到起点（不选任何模式）时回调 true，用来把按钮圆底变红。 */
    var onCancelStateChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val rowHeight = dp(58f)
    private val dotRadius = dp(5.5f)
    private val selectedDotRadius = dp(13f)
    private val pillWidth = dp(64f)
    private val pillPadding = dp(16f)
    private val dotCenterX = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val arrowPath = Path()

    private val surfaceColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        Color.WHITE,
    )

    /** 全部跟随应用主题：点用主色，文字用 onSurface，底用容器色。 */
    private val isNight = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val accentColor = MaterialColors.getColor(
        this,
        androidx.appcompat.R.attr.colorPrimary,
        if (isNight) Color.WHITE else Color.BLACK,
    )
    private val onAccentColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnPrimary,
        if (isNight) Color.BLACK else Color.WHITE,
    )
    private val textColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        if (isNight) Color.WHITE else Color.BLACK,
    )
    private val dimTextColor = Color.argb(
        if (isNight) 140 else 130,
        Color.red(textColor),
        Color.green(textColor),
        Color.blue(textColor),
    )
    private val pillColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurfaceContainerHighest,
        if (isNight) Color.rgb(38, 38, 40) else Color.rgb(238, 238, 240),
    )

    private var appearProgress = 0f
    private var appearAnimator: ValueAnimator? = null

    /** 拖动过程中的纵向映射：向上移动即向下选择（与参考一致）。 */
    private var dragOriginY = 0f

    /** 长按开始时的选中项，位移以它为基准。 */
    private var dragBaseIndex = 1

    init {
        visibility = INVISIBLE
        alpha = 0f
        // 明确不要任何投影 / 描边 / 硬件层阴影
        elevation = 0f
        outlineProvider = null
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (pillWidth + dp(160f)).toInt(),
            (rowHeight * MODES.size + pillPadding * 2).toInt(),
        )
    }

    /** 点列中心相对左边缘的偏移，供外部把点列对齐到发送按钮中心。 */
    val dotCenterOffsetFromLeft: Float get() = width - pillWidth / 2f

    /** 行距（点与点之间、以及最下方点到按钮中心的距离都用它）。 */
    val rowStep: Float get() = rowHeight

    /** 最下方那个点的中心 Y。 */
    val bottomDotCenterY: Float get() = pillPadding + rowHeight * (MODES.size - 0.5f)

    /** 设定当前选中项（默认是 Build）；高亮点会在选项之间平滑移动。 */
    fun setSelected(index: Int) {
        val target = index.coerceIn(0, MODES.lastIndex)
        if (target == selectedIndex) return
        selectedIndex = target
        animateSelectionTo(target.toFloat())
        onSelectionChanged?.invoke(target)
    }

    /** 高亮点的位置插值，用于选项切换时的过渡。 */
    private var animatedIndex = 1f
    private var selectionAnimator: ValueAnimator? = null
    /** 整体出现进度：用于各点依次淡入。 */
    private var revealProgress = 0f

    private fun animateSelectionTo(target: Float) {
        selectionAnimator?.cancel()
        selectionAnimator = ValueAnimator.ofFloat(animatedIndex, target).apply {
            duration = 180
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                animatedIndex = it.animatedValue as Float
                revealProgress = 1f
                invalidate()
            }
            start()
        }
    }

    /** 记录长按起点，用于把手指位移换算成选项。 */
    fun beginDrag(x: Float, y: Float) {
        dragOriginY = y
        dragBaseIndex = if (selectedIndex >= 0) selectedIndex else dragBaseIndex
    }

    /** 手指移动：向上移即选上一项；拖动时高亮点立即跟手（与参考方向一致）。 */
    fun updateDrag(x: Float, y: Float) {
        val steps = ((y - dragOriginY) / rowHeight).roundToInt()
        val index = (dragBaseIndex + steps).coerceIn(0, MODES.lastIndex)
        if (index != selectedIndex) {
            selectedIndex = index
            // 同步高亮位置插值，否则拖动时画面不更新（点的大小与颜色都以 animatedIndex 计算）
            animatedIndex = index.toFloat()
            revealProgress = 1f
            invalidate()
            onSelectionChanged?.invoke(index)
        }
    }

    fun show() {
        visibility = VISIBLE
        animatedIndex = selectedIndex.coerceAtLeast(0).toFloat()
        appearAnimator?.cancel()
        appearAnimator = ValueAnimator.ofFloat(appearProgress, 1f).apply {
            duration = 180
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                appearProgress = it.animatedValue as Float
                alpha = appearProgress
                translationY = (1f - appearProgress) * dp(12f)
                // 各点依次浮现
                revealProgress = ((appearProgress - 0.25f) / 0.75f).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    fun hide() {
        appearAnimator?.cancel()
        appearAnimator = ValueAnimator.ofFloat(appearProgress, 0f).apply {
            duration = 140
            addUpdateListener {
                appearProgress = it.animatedValue as Float
                alpha = appearProgress
                revealProgress = appearProgress
                if (appearProgress <= 0.01f) visibility = INVISIBLE
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pillRight = width.toFloat()
        val pillLeft = pillRight - pillWidth
        val top = pillPadding
        // 不画任何底色：参考交互里没有胶囊，只有文字与点
        val dotX = pillLeft + pillWidth / 2f
        val labelRight = pillLeft - dp(10f)
        labelPaint.textAlign = Paint.Align.RIGHT

        MODES.forEachIndexed { index, label ->
            val centerY = top + rowHeight * index + rowHeight / 2f
            // 与高亮位置的接近度：用于颜色与点大小的过渡
            val near = (1f - kotlin.math.abs(index - animatedIndex)).coerceIn(0f, 1f)
            // 各点依次浮现
            val reveal = (revealProgress * MODES.size - index).coerceIn(0f, 1f)
            if (reveal <= 0.01f) return@forEachIndexed

            val labelAlpha = (dimTextColor ushr 24) + ((textColor ushr 24) - (dimTextColor ushr 24)) * near
            labelPaint.color = Color.argb(
                (labelAlpha * reveal).toInt().coerceIn(0, 255),
                Color.red(textColor),
                Color.green(textColor),
                Color.blue(textColor),
            )
            labelPaint.isFakeBoldText = near > 0.5f
            canvas.drawText(label, labelRight, centerY + labelPaint.textSize / 3f, labelPaint)

            if (near > 0.02f) {
                // 高亮点：位置与大小随动画过渡
                val radius = dotRadius + (selectedDotRadius - dotRadius) * near
                // 高亮大点用主题主色（跟随自定义主色 / 动态取色）
                dotPaint.color = Color.argb(
                    (255 * near * reveal).toInt().coerceIn(0, 255),
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor),
                )
                canvas.drawCircle(dotX, centerY, radius, dotPaint)
                if (near > 0.6f) {
                    arrowPaint.color = Color.argb(
                        ((near - 0.6f) / 0.4f * 255).toInt().coerceIn(0, 255),
                        Color.red(onAccentColor),
                        Color.green(onAccentColor),
                        Color.blue(onAccentColor),
                    )
                    arrowPath.reset()
                    arrowPath.moveTo(dotX, centerY + dp(5f))
                    arrowPath.lineTo(dotX, centerY - dp(5f))
                    arrowPath.moveTo(dotX - dp(4f), centerY - dp(1f))
                    arrowPath.lineTo(dotX, centerY - dp(5f))
                    arrowPath.lineTo(dotX + dp(4f), centerY - dp(1f))
                    canvas.drawPath(arrowPath, arrowPaint)
                }
            } else {
                // 常规点用中性色：浅色模式黑、深色模式白（此前写死白色，浅色模式下不可见）
                dotPaint.color = Color.argb(
                    (170 * reveal).toInt().coerceIn(0, 255),
                    Color.red(textColor),
                    Color.green(textColor),
                    Color.blue(textColor),
                )
                canvas.drawCircle(dotX, centerY, dotRadius, dotPaint)
            }
        }
    }
}