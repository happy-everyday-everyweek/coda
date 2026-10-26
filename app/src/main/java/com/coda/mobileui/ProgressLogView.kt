package com.coda.mobileui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 从主题里取一个颜色。
 *
 * 不用 MaterialColors.getColor：那个重载要求第一个参数是 View 或 Activity，
 * 传 Context 时推不出类型，会连带把它后面的 attr 引用一起报成 Unresolved。
 * TypedValue.resolveAttribute 是平台 API，只要 Context 就够。
 */
private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

/**
 * 带日志的进度面板。
 *
 * 网关加载与 APK 下载这类过程会连续产出很多行状态（挑源、测速、下载、
 * 校验、重试…）。之前的写法每次回调都 refreshContent 重画整张卡片，
 * 结果屏幕上永远只有最后一行——用户看不到「它到底卡在哪一步」。
 *
 * 进度条改用 Material3 的 LinearProgressIndicator。
 *
 * 【为什么换掉自绘】
 * 之前那段自绘的位移公式在相位切换处不连续：长度到最大值时，左端点会
 * 从 0 直接跳到 (w - len)，实测 549ms 与 550ms 之间左端跳变 379px，
 * 周期末尾再跳 818px。每轮循环两次瞬移，看起来就是「抽搐一下」。
 * 根因是用同一个 t 既算长度又算位置，两段方向相反时必然不连续。
 * 换成 MD3 组件后几何连续性由官方实现保证，不再自己维护动画曲线。
 *
 * 用 CONTINUOUS（连续型）：色块在轨道里变长变短、来回游走，就是系统
 * APK 安装界面那种形态。DISJOINT 是两段分离色块循环，观感不同。
 *
 * 注意构造参数必须存成属性（private val ctx）：不存的写法下类体内
 * 没有 context 这个成员，TextView(context) 会直接报 Unresolved。
 */
class ProgressLogView(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val onSurface: Int =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int =
        themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val surfaceVariant: Int =
        themeColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY)
    private val primary: Int =
        themeColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.WHITE)

    /** 整体容器：进度条 + 日志滚动区。直接把这个加进卡片。 */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /**
     * MD3 不确定进度条。
     *
     * 轨道厚度自己定：主题默认 4dp 但四周留白偏大，放进日志卡片会显得空。
     * 4dp 厚度配 2dp 圆角，与之前自绘的 3.5dp 视觉密度接近。
     */
    private val bar = LinearProgressIndicator(ctx).apply {
        // 颜色先设、类型后设。CONTIGUOUS 在 Material 里有硬性约束：
        // LinearProgressIndicatorSpec.validateSpec 要求 indicatorColors.size >= 3，
        // 否则 setIndeterminateAnimationType 当场抛
        // IllegalArgumentException（真机崩在 ProgressLogView 构造第 77 行）。
        // 这里同一颜色传三次：动画内部是三条重叠色段各自长大缩小，
        // 同色即视觉上仍是一条纯色游走，正是系统安装界面的样子。
        setIndicatorColor(primary, primary, primary)
        isIndeterminate = true
        // 连续型：色块变长变短来回游走，与系统安装界面一致。
        indeterminateAnimationType =
            LinearProgressIndicator.INDETERMINATE_ANIMATION_TYPE_CONTIGUOUS
        trackThickness = dp(4f)
        trackCornerRadius = dp(2f)
        // MD3 默认在轨道末端画一个小停靠点，短轨道上显得多余。
        trackStopIndicatorSize = 0
        setTrackColor(surfaceVariant)
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
