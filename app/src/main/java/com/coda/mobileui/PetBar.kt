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
 * 它是**常驻**的：跟原项目一样，空闲时也在，会呼吸、眨眼、左右张望，久了我还会打瞌睡。
 * 有事情发生时切到对应状态并在旁边补一句话；事情结束后回到空闲，而不是整条消失。
 *
 * 之前这里是"有事才出现、没事就收起"，与用户要的"Pet 是常驻核心功能"不符，
 * 也正是"任何界面都看不见"的直接原因之一。
 */
class PetBar(
    context: Context,
) : LinearLayout(context) {
    private val pet = PetView(context)
    private val bubble = TextView(context)
    private val row = LinearLayout(context)

    /** 展开与收起时回调，方便外面把上下间距一起收掉。 */
    var onShownChanged: ((Boolean) -> Unit)? = null

    private var shown = true
    private var pendingDismiss: Runnable? = null

    /** 承载本条的容器。收起时整块隐藏，避免它的 padding 留下空隙。 */
    private var host: ViewGroup? = null

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        // 常驻：一创建就可见，不靠事件驱动
        visibility = View.VISIBLE

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

    /**
     * 注入皮肤。
     *
     * 原项目的五个形象各有自己的调色板，颜色全写在 manifest 里，所以这里不再注入主题色；
     * 保留签名只是为了少改调用点。
     */
    fun applySkin(
        ctx: Context,
        skinId: String,
        accent: Int = 0,
        accentLight: Int = 0,
        accentSoft: Int = 0,
    ) {
        pet.skin = PetSkins.resolve(ctx, skinId)
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
        // 文字长短变了行数会变，高度是固定值时会裁掉第二行，这里按新内容重新量一次。
        relayoutToContent()
    }

    /**
     * 按当前内容重算高度并直接套用（不动画）。
     *
     * 展开时用的是动画，动画结束时已经切成 WRAP_CONTENT；
     * 但显示期间文字变了（"读取文件" → "编辑文件：PetBar.kt"）时，还需要再量一次。
     */
    private fun relayoutToContent() {
        if (!shown) return
        post {
            if (!shown) return@post
            val parent = parent as? View
            val available = (parent?.width ?: 0) - paddingStart - paddingEnd
            val widthSpec =
                if (available > 0) {
                    MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                }
            measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            layoutParams.height = LayoutParams.WRAP_CONTENT
            requestLayout()
        }
    }

    /** 需要用户确认。 */
    fun showWaiting(text: String) {
        show(text, PetMood.WAITING, tint = 0xFFFF9F0A.toInt())
    }

    /** 做完了。文字停留几秒后清掉，但宠物本体继续在（它会自己回到空闲）。 */
    fun showDone(text: String) {
        show(text, PetMood.DONE, tint = 0xFF34C759.toInt())
        clearTextLater(6000)
    }

    /** 出错。 */
    fun showError(text: String) {
        show(text, PetMood.ERROR, tint = 0xFFFF3B30.toInt())
        clearTextLater(9000)
    }

    /**
     * 回到空闲。
     *
     * 只清气泡文字并把表情切回 idle；宠物本体继续显示，不会整条消失。
     * 名字保留 hideNow 以免改动调用点，但它不再隐藏任何东西。
     */
    fun hideNow() {
        pendingDismiss?.let { removeCallbacks(it) }
        pendingDismiss = null
        clearBubble()
        pet.setMood(PetMood.IDLE, animate = false)
        onShownChanged?.invoke(false)
    }

    private fun clearTextLater(delayMs: Long) {
        pendingDismiss?.let { removeCallbacks(it) }
        val r =
            Runnable {
                pendingDismiss = null
                // 只清文字，宠物继续；它自己会从 done/error 回到空闲
                clearBubble()
                onShownChanged?.invoke(false)
            }
        pendingDismiss = r
        postDelayed(r, delayMs)
    }

    /** 清掉气泡文字，并把行高收回仅宠物的高度。 */
    private fun clearBubble() {
        bubble.text = ""
        bubble.visibility = View.GONE
        relayoutToContent()
    }

    // ---------------------------------------------------------------- 内部

    private fun show(
        text: String,
        mood: PetMood,
        tint: Int?,
    ) {
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
        // 常驻显示：这里只按需重算高度，不再做展开/收起动画
        host?.visibility = View.VISIBLE
        visibility = View.VISIBLE
        relayoutToContent()
        onShownChanged?.invoke(true)
    }

    private fun onMood(mood: PetMood) {
        // 情绪由 show* 主动设定。宠物自己回到 idle 时不清气泡文字：
        // 文字的生灭由 show* / clearTextLater 管，否则刚写的句子会被立刻抹掉。
    }

    private fun pill(tint: Int?): GradientDrawable =
        GradientDrawable().apply {
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

    private fun blend(
        a: Int,
        b: Int,
        ratio: Float,
    ): Int {
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

    private fun resolveColor(
        attr: Int,
        fallback: Int,
    ): Int {
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
    fun attachTo(
        parent: ViewGroup,
        index: Int = -1,
    ) {
        host = parent
        parent.visibility = View.GONE
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, 0)
        if (index >= 0) parent.addView(this, index, lp) else parent.addView(this, lp)
    }
}
