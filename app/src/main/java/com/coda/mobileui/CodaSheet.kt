package com.coda.mobileui

import android.app.Activity
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.android.material.color.MaterialColors

/**
 * 标准半屏卡片容器：
 * - 从底部升起，默认停靠半屏（卡片形态）；顶部带滑动把手；
 * - 向上拖动展开为全屏，向下拖动回到半屏；半屏继续下拉（或快速下甩）关闭；
 * - 内容由调用方填充，组件只负责停靠、拖拽与遮罩。
 */
class CodaSheet(private val activity: Activity) {

    companion object {
        /** 全屏态高度占屏高比例（顶部留出少量背景）。 */
        private const val FULL_RATIO = 0.93f
        /** 半屏态高度占屏高比例（卡片形态）。 */
        private const val HALF_RATIO = 0.61f
    }

    private var titleText: String? = null
    private var subtitleText: String? = null
    private var headerActionText: String? = null
    private var headerActionBlock: ((CodaSheet) -> Unit)? = null
    private var primaryText: String? = null
    private var primaryBlock: ((CodaSheet) -> Unit)? = null
    private var contentBlock: ((LinearLayout) -> Unit)? = null
    private var onDismissBlock: (() -> Unit)? = null

    private var root: FrameLayout? = null
    private var scrim: View? = null
    private var panel: View? = null
    private var backCallback: OnBackPressedCallback? = null
    private var fullHeight = 0
    private var halfOffset = 0f

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

    fun onDismiss(block: () -> Unit): CodaSheet = apply { onDismissBlock = block }

    private fun dp(v: Float): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(activity.window.decorView, attr, fallback)

    /** 显示半屏卡片（默认停靠半屏，可拖拽展开全屏）。 */
    fun show() {
        if (isShowing) return
        val decor = activity.window.decorView as ViewGroup
        val screenH = activity.resources.displayMetrics.heightPixels
        fullHeight = (screenH * FULL_RATIO).toInt()
        halfOffset = fullHeight - screenH * HALF_RATIO

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

        // 面板：顶部大圆角，高度 = 全屏态高度（用 translationY 表现半屏）
        val panelView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadii = floatArrayOf(
                    dp(28f).toFloat(), dp(28f).toFloat(),
                    dp(28f).toFloat(), dp(28f).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
            elevation = dp(24f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28f).toFloat())
                }
            }
        }
        rootView.addView(
            panelView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, fullHeight, Gravity.BOTTOM),
        )

        // 拖拽区：滑动把手行 + 控制行
        val dragArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val grabberRow = FrameLayout(activity).apply {
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
        dragArea.addView(
            grabberRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20f)),
        )

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(6f), dp(20f), dp(4f))
        }
        val closeBtn = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(onSurface, 0.08f))
            }
            setOnClickListener { dismiss() }
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_close_lucide)
                    setColorFilter(onSurface)
                },
                FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER),
            )
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (headerActionText != null) {
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
        }
        dragArea.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
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
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            addView(
                contentCol,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panelView.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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

        // 底部主操作（可选）
        if (primaryText != null) {
            val footer = FrameLayout(activity).apply {
                setPadding(dp(20f), dp(10f), dp(20f), dp(24f))
            }
            footer.addView(
                TextView(activity).apply {
                    text = primaryText
                    textSize = 15f
                    setTextColor(onPrimary)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(26f).toFloat()
                        setColor(primary)
                    }
                    setOnClickListener { primaryBlock?.invoke(this@CodaSheet) }
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)),
            )
            panelView.addView(
                footer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // —— 拖拽逻辑（把手/空白区拖动；半屏⇄全屏⇄关闭）——
        val fling = ViewConfiguration.get(activity).scaledMinimumFlingVelocity.toFloat()
        var dragging = false
        var startRawY = 0f
        var startTrans = 0f
        var lastRawY = 0f
        var lastMoveAt = 0L
        dragArea.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startRawY = ev.rawY
                    startTrans = panelView.translationY
                    lastRawY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dy = ev.rawY - startRawY
                    var t = startTrans + dy
                    // 超过上界（全屏后继续上拉）与下界的阻尼
                    t = if (t < 0f) t * 0.35f else t
                    if (t > halfOffset) t = halfOffset + (t - halfOffset) * 0.6f
                    panelView.animate().cancel()
                    panelView.translationY = t
                    lastRawY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastMoveAt).coerceAtLeast(1L)
                    val vy = (ev.rawY - lastRawY) / dt.toFloat() * 1000f // px/s，向下为正
                    val t = panelView.translationY
                    val beyond = t - halfOffset
                    val flingDown = vy > fling * 1.1f
                    val flingUp = vy < -fling * 1.1f
                    when {
                        // 半屏继续下拉：拉出足够距离或快速下甩 → 关闭
                        beyond > dp(96f) || (beyond > dp(24f) && flingDown) -> dismiss()
                        // 快速上甩或上移过半 → 全屏
                        flingUp || t < halfOffset * 0.4f -> animateTo(0f)
                        // 从全屏快速下甩（拖出一点但未过半）→ 半屏
                        flingDown && t > dp(40f) -> animateTo(halfOffset)
                        // 其余按位置吸附
                        t < halfOffset * 0.5f -> animateTo(0f)
                        else -> animateTo(halfOffset)
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

        // 入场：从底部升到半屏
        panelView.translationY = fullHeight.toFloat()
        panelView.animate()
            .translationY(halfOffset)
            .setDuration(280)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
        scrimView.animate().alpha(1f).setDuration(200).start()
    }

    /** 拖拽松手后的吸附动画。 */
    private fun animateTo(target: Float) {
        val p = panel ?: return
        p.animate().cancel()
        p.animate()
            .translationY(target)
            .setDuration(220)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    /** 关闭半屏卡片（播放退出动画后移除）。 */
    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        val rootView = root ?: return
        val scrimView = scrim
        val panelView = panel
        backCallback?.let {
            it.isEnabled = false
            it.remove()
        }
        backCallback = null
        panelView?.animate()?.cancel()
        panelView?.animate()
            ?.translationY(fullHeight.toFloat())
            ?.setDuration(200)
            ?.withEndAction {
                (rootView.parent as? ViewGroup)?.removeView(rootView)
                root = null
                scrim = null
                panel = null
                onDismissBlock?.invoke()
            }
            ?.start()
        scrimView?.animate()?.alpha(0f)?.setDuration(160)?.start()
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
}