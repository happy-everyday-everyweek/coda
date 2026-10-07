package com.coda.mobileui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.android.material.color.MaterialColors

/**
 * 标准半屏卡片容器：
 * - 常规模式：从底部升起，默认停靠为「悬浮卡片」（四周留距、四角圆角）；
 *   向上拖动展开为真全屏（无缝隙、四角直角、紧贴屏幕），向下拖回卡片，卡片态继续下拉关闭；
 * - 紧凑模式（compact）：高度随内容自适应（上限约 0.72 屏），仅支持下拉关闭；
 * - 顶部中央为滑动把手（无关闭按钮；关闭靠下拉、点遮罩或返回键）；
 * - 内容由调用方填充；确认类用「副按钮 + 主按钮」页脚，选择/表单类用 content 区域。
 */
class CodaSheet(private val activity: Activity) {

    companion object {
        /** 半屏卡片高度占屏高比例。 */
        private const val HALF_RATIO = 0.61f

        /** 紧凑模式内容区高度上限占屏高比例。 */
        private const val COMPACT_MAX_RATIO = 0.72f

        /** 卡片态四周留距。 */
        private const val CARD_GAP_DP = 16f

        /** 卡片圆角。 */
        private const val CARD_CORNER_DP = 28f
    }

    private var titleText: String? = null
    private var subtitleText: String? = null
    private var headerActionText: String? = null
    private var headerActionBlock: ((CodaSheet) -> Unit)? = null
    private var primaryText: String? = null
    private var primaryBlock: ((CodaSheet) -> Unit)? = null
    private var secondaryText: String? = null
    private var secondaryBlock: ((CodaSheet) -> Unit)? = null
    private var contentBlock: ((LinearLayout) -> Unit)? = null
    private var liveContentCol: LinearLayout? = null
    private var onDismissBlock: (() -> Unit)? = null
    private var compactMode = false

    private var root: FrameLayout? = null
    private var scrim: View? = null
    private var panel: LinearLayout? = null
    private var grabberRow: FrameLayout? = null
    private var footerRow: LinearLayout? = null
    private var backCallback: OnBackPressedCallback? = null
    private var panelAnim: ValueAnimator? = null
    private var fullScreenH = 0
    private var statusBarH = 0
    private var navBarH = 0
    private var cornerPx = 0f

    var isShowing: Boolean = false
        private set

    fun title(text: String): CodaSheet = apply { titleText = text }

    fun subtitle(text: String): CodaSheet = apply { subtitleText = text }

    fun headerAction(text: String, block: (CodaSheet) -> Unit): CodaSheet = apply {
        headerActionText = text
        headerActionBlock = block
    }

    fun content(block: (LinearLayout) -> Unit): CodaSheet = apply { contentBlock = block }

    fun primaryAction(text: String, block: (CodaSheet) -> Unit): CodaSheet = apply {
        primaryText = text
        primaryBlock = block
    }

    /** 页脚次要按钮（与主按钮并排，描边样式）。 */
    fun secondaryAction(text: String, block: (CodaSheet) -> Unit): CodaSheet = apply {
        secondaryText = text
        secondaryBlock = block
    }

    fun onDismiss(block: () -> Unit): CodaSheet = apply { onDismissBlock = block }

    /** 紧凑模式：高度随内容自适应（上限约 0.72 屏），仅支持下拉关闭。 */
    fun compact(): CodaSheet = apply { compactMode = true }

    private fun dp(v: Float): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(activity.window.decorView, attr, fallback)

    private fun cardHeightPx(): Int = (fullScreenH * HALF_RATIO).toInt()

    /** 显示半屏卡片（常规模式默认停靠卡片态；紧凑模式自适应内容高度）。 */
    fun show() {
        if (isShowing) return
        val decor = activity.window.decorView as ViewGroup
        val screenH = activity.resources.displayMetrics.heightPixels
        statusBarH = statusBarHeightPx()
        navBarH = navBarHeightPx()
        fullScreenH = if (decor.height > 0) decor.height else screenH + statusBarH

        val surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVariant =
            color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val primary = color(androidx.appcompat.R.attr.colorPrimary, onSurface)
        val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)

        val rootView = FrameLayout(activity)

        // 遮罩：点击关闭
        val scrimView = View(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        rootView.addView(scrimView, matchParams())

        // 面板（卡片态：四周留距 + 四角圆角；全屏态：无距、无圆角、贴满屏幕）
        cornerPx = dp(CARD_CORNER_DP).toFloat()
        val panelView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadii = floatArrayOf(
                    cornerPx, cornerPx, cornerPx, cornerPx,
                    cornerPx, cornerPx, cornerPx, cornerPx,
                )
            }
            elevation = dp(24f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
        }
        val panelParams = if (compactMode) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(CARD_GAP_DP)
                rightMargin = dp(CARD_GAP_DP)
                bottomMargin = dp(CARD_GAP_DP)
            }
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                cardHeightPx(),
                Gravity.BOTTOM,
            )
        }
        rootView.addView(panelView, panelParams)

        // 拖拽区：滑动把手行（+ 可选操作行）
        val dragArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabRow = FrameLayout(activity).apply {
            addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2f).toFloat()
                        setColor(withAlpha(onSurface, 0.35f))
                    }
                },
                FrameLayout.LayoutParams(dp(36f), dp(4f), Gravity.CENTER),
            )
        }
        grabberRow = grabRow
        dragArea.addView(
            grabRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20f)),
        )

        if (headerActionText != null) {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), dp(6f), dp(20f), dp(4f))
            }
            header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(
                TextView(activity).apply {
                    text = headerActionText
                    textSize = 14f
                    setTextColor(onSurface)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18f).toFloat()
                        setStroke(dp(1f), withAlpha(onSurface, 0.25f))
                    }
                    setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                    setOnClickListener { headerActionBlock?.invoke(this@CodaSheet) }
                },
            )
            dragArea.addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panelView.addView(
            dragArea,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 内容滚动区
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(20f))
        }
        liveContentCol = contentCol
        val scroll: ScrollView = if (compactMode) {
            MaxHeightScrollView(activity).apply {
                maxHeightPx = (screenH * COMPACT_MAX_RATIO).toInt()
            }
        } else {
            ScrollView(activity)
        }
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_ALWAYS
        scroll.addView(
            contentCol,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        panelView.addView(
            scroll,
            if (compactMode) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            },
        )

        if (!titleText.isNullOrEmpty()) {
            contentCol.addView(
                TextView(activity).apply {
                    text = titleText
                    textSize = 22f
                    setTextColor(onSurface)
                    typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL,
                    )
                },
            )
        }
        if (!subtitleText.isNullOrEmpty()) {
            contentCol.addView(
                TextView(activity).apply {
                    text = subtitleText
                    textSize = 14f
                    setTextColor(onSurfaceVariant)
                    setPadding(0, dp(6f), 0, dp(2f))
                },
            )
        }
        contentBlock?.invoke(contentCol)

        // 页脚按钮（可选：单主按钮，或 副+主 双按钮）
        if (primaryText != null || secondaryText != null) {
            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20f), dp(10f), dp(20f), dp(24f))
            }
            fun footerButton(
                label: String,
                filled: Boolean,
                block: (CodaSheet) -> Unit,
            ): TextView = TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                if (filled) {
                    setTextColor(onPrimary)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(26f).toFloat()
                        setColor(primary)
                    }
                } else {
                    setTextColor(onSurface)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(26f).toFloat()
                        setStroke(dp(1f), withAlpha(onSurface, 0.25f))
                    }
                }
                setOnClickListener { block(this@CodaSheet) }
            }
            val both = primaryText != null && secondaryText != null
            if (secondaryText != null) {
                footer.addView(
                    footerButton(secondaryText!!, false) { secondaryBlock?.invoke(it) },
                    LinearLayout.LayoutParams(0, dp(52f), 1f).apply {
                        if (both) rightMargin = dp(12f)
                    },
                )
            }
            if (primaryText != null) {
                footer.addView(
                    footerButton(primaryText!!, true) { primaryBlock?.invoke(it) },
                    LinearLayout.LayoutParams(0, dp(52f), 1f),
                )
            }
            footerRow = footer
            panelView.addView(
                footer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // —— 拖拽逻辑 ——
        val fling = ViewConfiguration.get(activity).scaledMinimumFlingVelocity.toFloat()
        var dragging = false
        var startRawY = 0f
        var startTrans = 0f
        var startH = 0
        var lastH = 0
        var lastRawY = 0f
        var lastMoveAt = 0L
        dragArea.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startRawY = ev.rawY
                    startTrans = panelView.translationY
                    startH = panelView.height
                    lastH = startH
                    lastRawY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    cancelPanelAnim()
                    panelView.animate().cancel()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dy = ev.rawY - startRawY
                    if (compactMode) {
                        // 紧凑模式：仅向下拖（关），向上阻尼
                        var t = startTrans + dy
                        if (t < 0f) t = t * 0.3f
                        panelView.translationY = t
                    } else {
                        // 常规模式：直接调整高度（上拖变大、下拖变小），越界阻尼
                        var h = startH - dy.toInt()
                        if (h > fullScreenH) h = fullScreenH + ((h - fullScreenH) * 0.35f).toInt()
                        val minH = (cardHeightPx() * 0.42f).toInt()
                        if (h < minH) h = minH
                        applyDock(
                            (h - cardHeightPx()).toFloat() / (fullScreenH - cardHeightPx()),
                            h,
                        )
                        lastH = h
                    }
                    lastRawY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastMoveAt).coerceAtLeast(1L)
                    val vy = (ev.rawY - lastRawY) / dt.toFloat() * 1000f
                    val flingDown = vy > fling * 1.1f
                    val flingUp = vy < -fling * 1.1f
                    if (compactMode) {
                        val t = panelView.translationY
                        if (t > dp(80f) || (t > dp(16f) && flingDown)) dismiss() else animateCompactBack()
                    } else {
                        val h = lastH
                        val belowCard = cardHeightPx() - h
                        val span = (fullScreenH - cardHeightPx()).toFloat()
                        when {
                            belowCard > dp(110f) || (belowCard > dp(24f) && flingDown) -> dismiss()
                            flingUp && h > cardHeightPx() + dp(24f) -> snapTo(1f, h)
                            flingDown && h < fullScreenH - dp(40f) -> snapTo(0f, h)
                            h > cardHeightPx() + span * 0.5f -> snapTo(1f, h)
                            else -> snapTo(0f, h)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        // 返回键优先关闭
        if (activity is ComponentActivity) {
            val cb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                }
            }
            backCallback = cb
            (activity as ComponentActivity).onBackPressedDispatcher.addCallback(activity as ComponentActivity, cb)
        }

        decor.addView(rootView, matchParams())
        root = rootView
        scrim = scrimView
        panel = panelView
        isShowing = true

        // 初始形态与入场动画
        if (!compactMode) {
            applyDock(0f)
            panelView.translationY = (cardHeightPx() + dp(CARD_GAP_DP)).toFloat()
            panelView.animate()
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        } else {
            panelView.post {
                val slide = (panelView.height + dp(CARD_GAP_DP)).toFloat()
                panelView.translationY = slide
                panelView.animate()
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            }
        }
        scrimView.animate().alpha(1f).setDuration(200).start()
    }

    /**
     * 应用停靠进度：p=0 卡片态（半屏），p=1 全屏态。
     * 调整高度 / 四周留距 / 圆角 / 顶部内边距（状态栏避让）与阴影。
     */
    private fun applyDock(pRaw: Float, heightOverride: Int? = null) {
        val panelView = panel ?: return
        val p = pRaw.coerceIn(0f, 1f)
        val full = fullScreenH
        val card = cardHeightPx()
        val h = heightOverride ?: (card + ((full - card) * p).toInt())
        val gap = (dp(CARD_GAP_DP) * (1f - p)).toInt()
        val lp = panelView.layoutParams as FrameLayout.LayoutParams
        if (lp.height != h || lp.leftMargin != gap || lp.rightMargin != gap || lp.bottomMargin != gap) {
            lp.height = h
            lp.leftMargin = gap
            lp.rightMargin = gap
            lp.bottomMargin = gap
            panelView.layoutParams = lp
        }
        val radius = dp(CARD_CORNER_DP) * (1f - p)
        (panelView.background as? GradientDrawable)?.let { bg ->
            bg.cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius,
            )
        }
        cornerPx = radius
        panelView.invalidateOutline()
        panelView.elevation = dp(24f) * (1f - p)
        grabberRow?.setPadding(0, (statusBarH * p).toInt(), 0, 0)
        footerRow?.setPadding(dp(20f), dp(10f), dp(20f), dp(24f) + (navBarH * p).toInt())
    }

    /** 拖拽松手后吸附到卡片（p=0）或全屏（p=1）。 */
    private fun snapTo(p: Float, fromH: Int? = null) {
        val panelView = panel ?: return
        cancelPanelAnim()
        val full = fullScreenH
        val card = cardHeightPx()
        val h0 = fromH ?: panelView.height
        val h1 = card + ((full - card) * p).toInt()
        val p0 = ((h0 - card).toFloat() / (full - card)).coerceIn(0f, 1f)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                applyDock(p0 + (p - p0) * t, (h0 + ((h1 - h0) * t)).toInt())
            }
            start()
        }
        panelAnim = anim
    }

    /** 紧凑模式：下拉未达关闭阈值时弹回。 */
    private fun animateCompactBack() {
        val p = panel ?: return
        p.animate().cancel()
        p.animate()
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun cancelPanelAnim() {
        panelAnim?.cancel()
        panelAnim = null
    }

    /** 展示后更新内容（清空并重建；供异步加载完成后刷新用）。 */
    fun refreshContent(block: (LinearLayout) -> Unit) {
        contentBlock = block
        val col = liveContentCol ?: return
        col.removeAllViews()
        block(col)
    }

    /** 关闭半屏卡片（播放退出动画后移除）。 */
    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        cancelPanelAnim()
        val rootView = root ?: return
        val scrimView = scrim
        val panelView = panel
        backCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        backCallback = null
        panelView?.animate()?.cancel()
        val exit = ((panelView?.height ?: 0) + dp(CARD_GAP_DP)).toFloat()
        panelView?.animate()
            ?.translationY(exit)
            ?.setDuration(200)
            ?.withEndAction {
                (rootView.parent as? ViewGroup)?.removeView(rootView)
                root = null
                scrim = null
                panel = null
                grabberRow = null
                footerRow = null
                onDismissBlock?.invoke()
            }
            ?.start()
        scrimView?.animate()?.alpha(0f)?.setDuration(160)?.start()
    }

    private fun statusBarHeightPx(): Int = try {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) activity.resources.getDimensionPixelSize(id) else dp(24f)
    } catch (e: Throwable) {
        dp(24f)
    }

    private fun navBarHeightPx(): Int = try {
        val id = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    } catch (e: Throwable) {
        0
    }

    private fun matchParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /** 高度受限的滚动容器（紧凑模式内容上限）。 */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeightPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (measuredHeight > maxHeightPx) {
                setMeasuredDimension(measuredWidth, maxHeightPx)
            }
        }
    }
}