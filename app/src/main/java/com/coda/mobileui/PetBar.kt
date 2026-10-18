package com.coda.mobileui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.core.PetMood
import com.coda.mobileui.core.PetSkins

/**
 * 输入框上方的宠物提示条。
 *
 * 出现与收起的节奏照 Claude Code 的做法：只在有事情发生时露面，事情结束就退场，
 * 绝不常驻占位置。具体是：
 *
 * 空闲时不显示，整条高度归零；
 * 运行中滑入，显示"它在做什么"，换动作只换文字不回退进场；
 * 等你确认时滑入并带提示色底，处理完立刻收起；
 * 做完后先庆祝，再显示最后一句话的首句，停留几秒自动收起；
 * 出错时滑入并带错误色底，停久一点。
 */
class PetBar(context: Context) : LinearLayout(context) {

    private val pet = PetView(context)
    private val bubble = TextView(context)
    private val row = LinearLayout(context)
    private var anim: ValueAnimator? = null

    /** 展开与收起时回调，方便外面把上下间距一起收掉。 */
    var onShownChanged: ((Boolean) -> Unit)? = null

    private var shown = false
    private var pendingDismiss: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        visibility = View.GONE

        row.orientation = HORIZONTAL
        row.gravity = Gravity.BOTTOM or Gravity.START
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

// 宠物宽度固定，高度按皮肤比例自适应（18×16 的网格大约是 44dp 高）
        pet.layoutParams = LayoutParams(dp(46), LayoutParams.WRAP_CONTENT)
        row.addView(pet)

        bubble.apply {
            textSize = 12.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = pill(null)
            visibility = View.GONE
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(2)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        row.addView(bubble)

        pet.onMoodChanged = { mood -> onMood(mood) }
    }

    /** 注入皮肤与主题色；设置页改了之后当场生效。 */
    fun applySkin(ctx: Context, skinId: String, accent: Int, accentLight: Int, accentSoft: Int) {
        pet.skin = PetSkins.resolve(ctx, skinId)
        pet.accent = accent
        pet.accentLight = accentLight
        pet.accentSoft = accentSoft
    }

    fun petView(): PetView = pet

    // ---------------------------------------------------------------- 对外

    /** 开始跑任务。text 是当前动作，例如"正在读文件"。 */
    fun showRunning(text: String) {
        show(text, PetMood.RUNNING, tint = null)
    }

    /**
     * 只换文字，不动状态。
     *
     * 一个回合里工具会连续切换（读文件 → 改文件 → 跑命令），每次都重播进场动画
     * 会显得很吵。已经在运行中时走这条路径，只把气泡文字换掉。
     */
    fun updateText(text: String) {
        if (!shown) {
            showRunning(text)
            return
        }
        pendingDismiss?.let { removeCallbacks(it) }
        pendingDismiss = null
        bubble.text = text
        bubble.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 需要用户确认。 */
    fun showWaiting(text: String) {
        show(text, PetMood.WAITING, tint = 0xFFFF9F0A.toInt())
    }

    /** 做完了。传最后一句话的首句，停留几秒后自动收起。 */
    fun showDone(text: String) {
        show(text, PetMood.DONE, tint = 0xFF34C759.toInt())
        autoDismiss(6000)
    }

    /** 出错。 */
    fun showError(text: String) {
        show(text, PetMood.ERROR, tint = 0xFFFF3B30.toInt())
        autoDismiss(9000)
    }
    /** 回到闲置：整条收起，不占输入框上方的空间。 */
    fun hideNow() {
        pendingDismiss?.let { removeCallbacks(it) }
        pendingDismiss = null
        if (!shown) return
        shown = false
        animateHeight(0) {
            visibility = View.GONE
            onShownChanged?.invoke(false)
        }
    }

    private fun autoDismiss(delayMs: Long) {
        pendingDismiss?.let { removeCallbacks(it) }
        val r = Runnable {
            pendingDismiss = null
            hideNow()
        }
        pendingDismiss = r
        postDelayed(r, delayMs)
    }

    // ---------------------------------------------------------------- 内部

    private fun show(text: String, mood: PetMood, tint: Int?) {
        pendingDismiss?.let { removeCallbacks(it) }
        pendingDismiss = null
        bubble.text = text
        bubble.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        bubble.background = pill(tint)
        bubble.setTextColor(
            if (tint == null) {
                resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
            } else {
                blendOnSurface(tint)
            },
        )
        pet.setMood(mood)
        if (!shown) {
            shown = true
            visibility = View.VISIBLE
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED,
            )
            animateHeight(measuredHeight) { onShownChanged?.invoke(true) }
        }
    }

    private fun onMood(mood: PetMood) {
        // 情绪由 show* 主动设定；自己回闲置时把整条收起
        if (mood == PetMood.IDLE) hideNow()
    }

    private fun animateHeight(target: Int, end: (() -> Unit)? = null) {
        anim?.cancel()
        val from = height.coerceAtLeast(0)
        if (from == target) {
            layoutParams.height = target
            end?.invoke()
            return
        }
        anim = ValueAnimator.ofInt(from, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val v = animation.animatedValue as Int
                layoutParams.height = v
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    layoutParams.height = if (target == 0) 0 else LayoutParams.WRAP_CONTENT
                    if (target != 0) requestLayout()
                    end?.invoke()
                }
            })
            start()
        }
    }

    private fun pill(tint: Int?): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        if (tint == null) {
            setColor(
                resolveColor(
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    0x33808080,
                ),
            )
        } else {
            // 提示色做半透明底，不抢正文
            setColor((tint and 0x00FFFFFF) or 0x33000000)
        }
    }

    private fun blendOnSurface(tint: Int): Int {
        val onSurface = resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val dark = isDark()
        // 浅色主题配深字、深色主题配浅字，各掺一点提示色让语义还在
        val base = if (dark) Color.WHITE else onSurface
        return blend(base, tint, if (dark) 0.22f else 0.30f)
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt()
        val g = (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt()
        val bl = (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun isDark(): Boolean {
        val cfg = context.resources.configuration
        return (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        val ta = context.obtainStyledAttributes(tv.data, intArrayOf(attr))
        return try {
            if (ta.hasValue(0)) ta.getColor(0, fallback) else fallback
        } finally {
            ta.recycle()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 塞进容器时用，初始高度为 0（收起态）。 */
    fun attachTo(parent: ViewGroup, index: Int = -1) {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, 0)
        if (index >= 0) parent.addView(this, index, lp) else parent.addView(this, lp)
    }
}