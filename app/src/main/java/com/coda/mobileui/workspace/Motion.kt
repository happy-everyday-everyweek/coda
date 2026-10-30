package com.coda.mobileui.workspace

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 工作区统一的动效语言。
 *
 * 数值全部对齐抽屉展开那一套：缓动 PathInterpolator(0.2, 0, 0, 1)，快起慢收。
 * 时长分四档，任何新加的交互动效都应该从这里取，避免每处各写一个数字。
 */
internal object Motion {
    /** 按下、松开这类即时反馈。 */
    const val TAP_MS = 90L

    /** 回弹、状态切换。 */
    const val SETTLE_MS = 140L

    /** 面板进出、内容替换。 */
    const val PANEL_MS = 200L

    /** 大面板展开这类幅度较大的动作。 */
    const val LARGE_MS = 260L

    /** 标准缓动，与抽屉展开一致。 */
    val standard: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 回弹用的缓动，结尾略微放缓，不做真正的弹簧以免抖动。 */
    val settle: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * 页面切换时长与缓动。
     *
     * 对齐 NextPlayer 的页面过渡（navigationTransition）：200ms、线性缓动。
     * 它不用减速曲线是有道理的——切换过程里两页同时在动，
     * 给整体加缓动会让两页的速度关系不断变化，看着反而发飘；
     * 线性则让位移匀速，层次感来自两页行程不同，而不是来自速度变化。
     */
    const val NAV_MS = 200L
    val linear: TimeInterpolator = LinearInterpolator()

    /**
     * 切换时旧页的退出行程占比。
     *
     * NextPlayer 里前进是「新页走满屏、旧页只走 30%」，后退反过来。
     * 用行程差做出前后景层次，比两页都走满屏更像翻页，也不会让两页
     * 以同样速度对向交叉而显得忙。
     */
    const val NAV_EXIT_RATIO = 0.3f
}

/**
 * 给一个可点区域加按下反馈。
 *
 * 按下缩到 [pressedScale]、回弹时恢复；同时把内容的透明度压低一点（[pressedAlpha]），
 * 两层叠加在深色底上也看得出来。返回 false 让点击与长按继续正常派发。
 */
internal fun View.pressFeedback(
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.72f,
) {
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v
                    .animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(Motion.TAP_MS)
                    .setInterpolator(Motion.standard)
                    .start()
                // 缩放看的是整块，透明度看的是内容：卡片底色很淡时只缩不放透明度会不明显。
                fadeContent(v, pressedAlpha, Motion.TAP_MS)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.SETTLE_MS)
                    .setInterpolator(Motion.settle)
                    .start()
                fadeContent(v, 1f, Motion.SETTLE_MS)
            }
        }
        false
    }
}

/**
 * 只调子视图的 alpha，不动容器自身。
 *
 * 容器的 alpha 会被外部动画（切页淡入、列表重建）占用，改它容易互相打架。
 */
private fun fadeContent(
    v: View,
    alpha: Float,
    duration: Long,
) {
    val group = v as? ViewGroup ?: return
    for (i in 0 until group.childCount) {
        group
            .getChildAt(i)
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(Motion.standard)
            .start()
    }
}

/**
 * 让一行文字换新内容时有一点反馈：先淡下去、改字、再淡回来。
 *
 * 用于底栏的标签数量、状态提示这类"数字变了但没有动画"的位置。
 */
internal fun TextView.crossFadeText(
    newText: CharSequence,
    duration: Long = 110L,
) {
    if (text == newText) return
    animate().cancel()
    animate()
        .alpha(0f)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .withEndAction {
            text = newText
            animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(Motion.standard)
                .start()
        }.start()
}

/**
 * 视图以"从某处出现"的方式入场：透明度 + 轻微位移，位移方向由 [translationY] 决定。
 *
 * 每帧都重新读一次当前高度不可靠（刚 addView 时还是 0），所以位移用固定 dp 值。
 */
internal fun View.enterFrom(
    offsetYPx: Float,
    duration: Long = Motion.PANEL_MS,
) {
    animate().cancel()
    alpha = 0f
    translationY = offsetYPx
    animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .start()
}

/** 视图淡出并轻微位移后退场，结束后执行 [after]。 */
internal fun View.exitTo(
    offsetYPx: Float,
    duration: Long = Motion.SETTLE_MS,
    after: (() -> Unit)? = null,
) {
    animate().cancel()
    animate()
        .alpha(0f)
        .translationY(offsetYPx)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .withEndAction { after?.invoke() }
        .start()
}

/**
 * 高度从 0 长到内容高度（或反向），用于"展开一段说明"这类不改变页面结构的小展开。
 *
 * 结束后把高度交回 WRAP_CONTENT，避免后续内容变化被固定值锁死。
 */
internal fun View.animateHeightTo(
    show: Boolean,
    duration: Long = Motion.PANEL_MS,
) {
    val params = layoutParams ?: return
    val target =
        if (show) {
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            measuredHeight
        } else {
            0
        }
    val start = measuredHeight
    if (show) {
        params.height = 0
        layoutParams = params
        visibility = View.VISIBLE
    }
    ValueAnimator.ofInt(start, target).apply {
        this.duration = duration
        interpolator = Motion.standard
        addUpdateListener { a ->
            val h = a.animatedValue as Int
            val p = layoutParams ?: return@addUpdateListener
            p.height = h
            layoutParams = p
        }
        addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val p = layoutParams ?: return
                    p.height = if (show) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                    layoutParams = p
                    visibility = if (show) View.VISIBLE else View.GONE
                }
            },
        )
        start()
    }
}

/** 把一行做成可点的卡片行，并附带按下反馈。 */
internal fun LinearLayout.asPressable(): LinearLayout =
    apply {
        isClickable = true
        pressFeedback()
    }
