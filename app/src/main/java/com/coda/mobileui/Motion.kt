package com.coda.mobileui

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * 动效 token，取自 dsh-Go 的 AppMotion（ccch1mneyyy/dsh-Go，19k stars）。
 *
 * 原则是「安静、短、跟手」：反馈类 ≤160ms，结构类 240–340ms。
 * 抽屉、胶囊这类跟手后落位的运动用 glide（iOS 式长尾减速），
 * 不用线性插值——线性走完会显得生硬，尾部也没有缓慢收住的感觉。
 */
object Motion {
    /** 淡入淡出、图标切换。 */
    const val QUICK = 160L

    /** 小块展开收起、列表项进出。 */
    const val STANDARD = 240L

    /** 抽屉落位、胶囊变形。 */
    const val EMPHASIZED = 340L

    /** M3 standard：cubic-bezier(.4, 0, .2, 1)。 */
    val STD: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** 跟手后落位：cubic-bezier(.32, .72, 0, 1)，长尾减速。 */
    val GLIDE: Interpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)
}
