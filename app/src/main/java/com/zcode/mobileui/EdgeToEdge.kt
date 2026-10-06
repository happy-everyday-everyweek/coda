package com.zcode.mobileui

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