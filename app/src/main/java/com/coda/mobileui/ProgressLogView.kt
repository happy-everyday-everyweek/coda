package com.coda.mobileui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 从主题里取一个颜色。
 *
 * 不用 MaterialColors.getColor：那个重载要求第一个参数是 View 或 Activity，
 * 传 Context 时推不出类型，会连带把它后面的 attr 引用一起报成 Unresolved
 * （上一版就是因此编译失败）。TypedValue.resolveAttribute 是平台 API，
 * 只要 Context 就够，而且项目里已经有同样的用法。
 */
private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

/**
 * 不确定进度条：一条线在轨道里来回跑。
 *
 * 与 Material 的 LinearProgressIndicator 不定态不同，那条是「一段固定长度的
 * 色块循环划过」，这里是「线本身在变长变短」——两端交替伸缩，走到头就折返。
 * 这是系统 APK 安装界面的形态，用户看惯了，对「还要等多久」没有预期时最合适。
 *
 * 为什么自己画：项目里没有任何进度条依赖，为一个动效引入整套 material
 * progress 组件不划算；这段只有一次 onDraw 和一个动画循环。
 */
class IndeterminateProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(
            context,
            com.google.android.material.R.attr.colorSurfaceVariant,
            Color.DKGRAY,
        )
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(context, com.google.android.material.R.attr.colorPrimary, Color.WHITE)
    }

    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())

    /** 一个来回的周期。比系统那个略快一点：这里的等待通常只有十几秒。 */
    private val periodMs = 1_100L

    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (running) handler.postDelayed(this, 16)
        }
    }

    /** 条的高度，轨道更薄一点。 */
    private fun barH(): Float = resources.displayMetrics.density * 3.5f
    private fun radius(): Float = barH() / 2f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (barH() * 2).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = (h - barH()) / 2f
        val top = inset
        val bottom = inset + barH()

        // 轨道：整条淡底，让人看见这行的总长度。
        rect.set(0f, top, w, bottom)
        canvas.drawRoundRect(rect, radius(), radius(), trackPaint)

        // 线：两端在一半周期处交换伸缩方向，于是形成来回跑的效果。
        val phase = (System.currentTimeMillis() % periodMs) / periodMs.toFloat()
        val t = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        val minLen = w * 0.18f
        val maxLen = w * 0.62f
        val len = minLen + (maxLen - minLen) * t
        val fromLeft = phase < 0.5f
        val left = if (fromLeft) (w - len) * (1f - t) else (w - len) * t
        rect.set(left, top, left + len, bottom)
        canvas.drawRoundRect(rect, radius(), radius(), barPaint)
    }
}

/**
 * 带日志的进度面板。
 *
 * 网关加载与 APK 下载这类过程会连续产出很多行状态（挑源、测速、下载、
 * 校验、重试…）。之前的写法每次回调都 `refreshContent` 重画整张卡片，
 * 结果屏幕上永远只有最后一行——用户看不到「它到底卡在哪一步」，
 * 而这恰恰是出问题时最需要的信息。
 *
 * 这里改成累积：每行追加到底部，自动滚到最后一行，只保留最近
 * [maxLines] 行（早期步骤的信息价值随时间衰减，但不该让视图无限长）。
 * 进度条则表示「还在动」，避免长任务看起来像死掉。
 *
 * 注意构造参数必须存成属性（`private val ctx`）：不存的写法下，
 * 类体内没有 `context` 这个成员，`TextView(context)` 会直接报
 * Unresolved reference——上一版就是这么错的。
 */
class ProgressLogView(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val onSurface: Int =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    /** 整体容器：进度条 + 日志滚动区。直接把这个加进卡片。 */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private val bar = IndeterminateProgressView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        }
    }

    private val logColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    /**
     * 日志区。不用 ScrollView 限高——
     * LinearLayout.LayoutParams 上没有 maximumHeight 这个字段（那是
     * MaxHeightScrollView 才有的东西），直接写会编译不过。
     * 这里靠 maxLines 限制行数，行数有限自然就不会无限长，
     * 外层卡片本来也有滚动，不需要再套一层。
     */
    private val scroll = ScrollView(ctx).apply {
        isFillViewport = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        addView(logColumn)
    }

    /** 保留的最大行数。再多也不会让视图无限长。 */
    private val maxLines = 80

    init {
        view.addView(bar)
        view.addView(scroll)
    }

    /**
     * 追加一行。
     *
     * @param text 这一行的内容
     * @param emphasis true 表示这是「结果行」（成功/失败），用主色强调
     */
    fun append(text: String, emphasis: Boolean = false) {
        if (text.isBlank()) return
        val line = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (emphasis) onSurface else onSurfaceVariant)
            setPadding(0, dp(2f), 0, dp(2f))
            // 长行（比如带 URL 的）要能折行，不能横向溢出。
            setLineSpacing(0f, 1.15f)
        }
        logColumn.addView(line)
        // 超出上限时从头部删，保留尾部（最新的信息最有用）。
        while (logColumn.childCount > maxLines) {
            logColumn.removeViewAt(0)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 过程结束：停掉进度条并移除它，只留日志。 */
    fun finish() {
        bar.visibility = View.GONE
    }

    /**
     * 最后一行。
     *
     * 给「需要原地更新的那类行」用：调用方 append 一行拿到它的引用，
     * 之后直接改 text，不再追加。下载进度是典型场景——它一秒回调十几次，
     * 每次都追加会把前面所有步骤挤出可视区。
     */
    fun lastLine(): TextView? = logColumn.getChildAt(logColumn.childCount - 1) as? TextView
}