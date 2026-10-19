package com.coda.mobileui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/**
 * 全面屏（edge-to-edge）适配：内容延伸到系统栏区域；
 * 状态栏默认隐藏（沉浸式），从顶部下滑可临时呼出。
 */
fun AppCompatActivity.setupEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    controller.isAppearanceLightStatusBars = !isNight
    controller.isAppearanceLightNavigationBars = !isNight
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.statusBars())
    // 部分设备在窗口完全附着前会忽略 hide，补一次调用。
    window.decorView.post {
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}

/** 顶部组件内边距：系统栏与刘海/挖孔区域取并集（保证组件可达）。 */
fun View.applyTopSystemBarInset() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
        insets
    }
}

/** 顶部 + 底部组件内边距：系统栏与刘海/挖孔区域取并集。 */
fun View.applySystemBarsInset() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
        insets
    }
}

/**
 * 顶部避让高度：系统状态栏与刘海/挖孔取较大者。
 *
 * 沉浸式下 WindowInsets 常常返回 0（项目里踩过这个坑），所以这里不依赖 insets，
 * 直接用资源里的状态栏高度再和挖孔比较，与聊天页顶栏用的是同一套口径。
 */
fun Context.topSafeInsetPx(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    val statusBar = if (id > 0) resources.getDimensionPixelSize(id) else dpPx(26)
    val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val activity = this as? android.app.Activity
        activity?.window?.decorView?.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0
    } else {
        0
    }
    return maxOf(statusBar, cutout)
}

/** 底部避让高度：系统导航栏高度；取不到时用 24dp 兜底。 */
fun Context.bottomSafeInsetPx(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dpPx(24)
}

private fun Context.dpPx(v: Int): Int =
    (v * resources.displayMetrics.density).roundToInt()

/**
 * 给一个顶部组件加状态栏避让：把原有内边距整体下移一个安全区高度。
 *
 * 与 applyTopSystemBarInset 的区别是它不依赖 WindowInsets —— 沉浸式下那套会拿到 0，
 * 于是组件直接顶到屏幕最上面、被状态栏或挖孔压住。
 */
fun View.padForTopSafeInset() {
    val extra = context.topSafeInsetPx()
    setPadding(paddingLeft, paddingTop + extra, paddingRight, paddingBottom)
}

/** 给一个底部组件加导航栏避让。 */
fun View.padForBottomSafeInset() {
    val extra = context.bottomSafeInsetPx()
    setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + extra)
}

/** 底部内边距：取系统栏与输入法（键盘）的较大者，并叠加原有底部间距。 */
fun View.applyBottomInsetWithIme() {
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            baseBottom + maxOf(bars.bottom, ime.bottom),
        )
        insets
    }
}

/** 抽屉内边距：顶部在系统栏/挖孔区域之外再留出 gap，底部跟随系统栏。 */
fun View.applyDrawerInset(topGapDp: Int) {
    val gap = (topGapDp * resources.displayMetrics.density).roundToInt()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.setPadding(view.paddingLeft, bars.top + gap, view.paddingRight, bars.bottom)
        insets
    }
}