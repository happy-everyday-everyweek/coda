package com.coda.mobileui.workspace

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.R

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

    /**
     * 回弹用的缓动：起步比 standard 更快、收尾更长，视觉上与 standard 可区分。
     * 原先控制点与 standard 完全一致，传 settle 与传 standard 没有任何差别（#542）。
     */
    val settle: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
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
    // 宿主不可点击时 View.onTouchEvent 对 DOWN 返回 false，父容器就不再派发后续事件，
    // UP/CANCEL 永远收不到，按压态会粘住（#539）。这里保证宿主能吃到整套手势。
    isClickable = true
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

            MotionEvent.ACTION_MOVE -> {
                // 手指拖出边界：视觉上应当立刻恢复，而不是等一个可能不会来的 UP。
                if (!isInsideBounds(v, ev)) releasePress(v)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releasePress(v)
            }
        }
        // 返回 false 让点击与长按继续派发；宿主已 isClickable，事件链不会断。
        false
    }
}

/** 触点是否仍在视图范围内。 */
private fun isInsideBounds(
    v: View,
    ev: MotionEvent,
): Boolean = ev.x >= 0 && ev.y >= 0 && ev.x < v.width && ev.y < v.height

/**
 * 恢复到未按下的形态；重复调用无副作用。
 *
 * MOVE 拖出边界会连续触发（OCR #821）：每次都 cancel+重启会让回弹
 * 动画永远从第 0 帧开始、从不前进。已在恢复态（scale==1 且无在途
 * 按压动画）时直接返回。
 */
private fun releasePress(v: View) {
    // 只看恢复态标记（OCR r10 high）：settle 在途时 scaleX != 1，按缩放值判
    // 会让每次越界 MOVE 都 cancel+重启，回弹永远从第 0 帧开始。
    if (v.getTag(R.id.coda_press_settling) != null) return
    if (v.scaleX == 1f && v.scaleY == 1f) return
    v.setTag(R.id.coda_press_settling, true)
    v.animate().cancel()
    v
        .animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(Motion.SETTLE_MS)
        .setInterpolator(Motion.settle)
        // withEndAction 只在自然完成时跑（OCR r10）：快速再按下或同视图
        // 其它动画会 cancel，标记不清就永远早退、按压反馈再也不恢复。
        // 监听器在 end 清标记与自身（cancel 路径平台会续派 end）。
        .setListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    v.animate().setListener(null)
                    v.setTag(R.id.coda_press_settling, null)
                }
            },
        )
        .start()
    fadeContent(v, 1f, Motion.SETTLE_MS)
}

/**
 * 按下反馈的透明度层。
 *
 * 原先 `v as? ViewGroup ?: return` 让 TextView/ImageView 这类叶子视图完全没有
 * alpha 反馈；ViewGroup 也只淡直接子视图，嵌套层级会出现「外框淡了内容没淡」（#541）。
 * 这里对叶子视图直接改自身 alpha，对容器递归到全部后代。
 * 容器自身的 alpha 仍不动——它可能被切页淡入等外部动画占用。
 */
private fun fadeContent(
    v: View,
    alpha: Float,
    duration: Long,
) {
    if (v !is ViewGroup) {
        v
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(Motion.standard)
            .start()
        return
    }
    for (i in 0 until v.childCount) {
        fadeTarget(v.getChildAt(i), alpha, duration)
    }
}

/** 递归淡一个目标：容器继续往下走，叶子改自身。 */
private fun fadeTarget(
    v: View,
    alpha: Float,
    duration: Long,
) {
    if (v is ViewGroup && v.childCount > 0) {
        for (i in 0 until v.childCount) fadeTarget(v.getChildAt(i), alpha, duration)
        return
    }
    v
        .animate()
        .alpha(alpha)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .start()
}

/**
 * 摘掉本视图 ViewPropertyAnimator 上的监听器（#784）。
 *
 * `animate()` 返回的是**每视图常驻**的 ViewPropertyAnimator，`setListener` 设上去
 * 不会自动清除。不摘的话，之后任何一次 `animate()`（包括别的动画开头的 cancel）
 * 都会重跑这个旧闭包：enterFrom 的 settle 会把 alpha 拉回 1 让退场失效，
 * crossFadeText 的 applyNow 会把文本改回上一次的 newText。
 * 每个回调进来第一件事就是摘掉自己，保证只作用于本次动画。
 */
private fun View.clearAnimatorListener() {
    animate().setListener(null)
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
    // withEndAction 只在正常结束时执行：动画被取消（列表行回收、视图 detach、
    // 竞争动画）时不会触发，视图就永久停在 alpha=0、文本还是旧的。
    // 这里把取消也当成一次收尾，且只应用一次。
    var applied = false

    fun applyNow(fadeIn: Boolean) {
        if (applied) return
        applied = true
        text = newText
        if (!fadeIn) {
            // 被取消：直接落到可见终态，不再补淡入，避免又一次被取消
            alpha = 1f
            return
        }
        animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(Motion.standard)
            .start()
    }

    animate()
        .alpha(0f)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .setListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearAnimatorListener()
                    applyNow(true)
                }

                override fun onAnimationCancel(animation: Animator) {
                    clearAnimatorListener()
                    applyNow(false)
                }
            },
        ).start()
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

    // 取消同样要落到终态：否则视图会停在一半的透明度/位移上，后续页面看着像没画完。
    fun settle() {
        alpha = 1f
        translationY = 0f
    }
    animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .setListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearAnimatorListener()
                    settle()
                }

                override fun onAnimationCancel(animation: Animator) {
                    clearAnimatorListener()
                    settle()
                }
            },
        ).start()
}

/** 视图淡出并轻微位移后退场，结束后执行 [after]。 */
internal fun View.exitTo(
    offsetYPx: Float,
    duration: Long = Motion.SETTLE_MS,
    after: (() -> Unit)? = null,
) {
    animate().cancel()
    // withEndAction 在取消时不执行，after 里的清理与后续导航就永远丢了。
    // 把取消也当成一次结束，after 只跑一次。
    var fired = false

    fun fire() {
        if (fired) return
        fired = true
        after?.invoke()
    }
    animate()
        .alpha(0f)
        .translationY(offsetYPx)
        .setDuration(duration)
        .setInterpolator(Motion.standard)
        .setListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearAnimatorListener()
                    fire()
                }

                override fun onAnimationCancel(animation: Animator) {
                    clearAnimatorListener()
                    fire()
                }
            },
        ).start()
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
    // 反复点击时上一个动画还在跑：不取消就会有两个 updateListener 同时写 height，
    // 视图停在中间高度，且后一个动画的 start 取自被打断的瞬时值（#540）。
    //
    // 必须**先清 tag 再 cancel**（#785）：ValueAnimator.cancel() 同步派发
    // onAnimationCancel + onAnimationEnd，旧监听器里的 `getTag(...) !== animation`
    // 守卫如果此刻还读到旧 animator 就会通过，于是被取消的动画把自己的陈旧终态
    // （height=0 / visibility=GONE）写回去，快速收起→展开后面板反而是隐藏的。
    val old = getTag(R.id.coda_height_animator) as? ValueAnimator
    setTag(R.id.coda_height_animator, null)
    old?.cancel()

    // 宽度为 0（还没布局、或处于 GONE）时 EXACTLY(0) 会量出 0 高度，
    // 展开后内容完全不显示（#537）。退回父容器可用宽度，再不行用 UNSPECIFIED。
    val parentWidth = (parent as? View)?.width ?: 0
    val widthSpec =
        when {
            width > 0 -> View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            parentWidth > 0 -> View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
    // 起点必须在放开测量**之前**取（OCR #821）：show 分支的 measure 会把
    // measuredHeight 覆盖成目标高度，之后再读 start==target，展开动画
    // 被下面的早退直接吞掉。
    val start = if (measuredHeight > 0) measuredHeight else (params.height.coerceAtLeast(0))
    val target =
        if (show) {
            // 量之前先把高度放开，否则 WRAP_CONTENT 之外的固定值会限制测量结果。
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = params
            measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            measuredHeight
        } else {
            0
        }
    if (show) {
        params.height = start
        layoutParams = params
        visibility = View.VISIBLE
    }
    // 目标与起点相同（例如内容量出 0）时直接落终态，避免一个什么都不做的动画。
    if (target == start) {
        params.height = if (show) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        layoutParams = params
        visibility = if (show) View.VISIBLE else View.GONE
        return
    }
    val animator = ValueAnimator.ofInt(start, target)
    setTag(R.id.coda_height_animator, animator)
    animator.apply {
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
                    // 被新动画取消时不要落终态：否则会用旧目标覆盖新动画的意图（#540）。
                    if (getTag(R.id.coda_height_animator) !== animation) return
                    setTag(R.id.coda_height_animator, null)
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
