package com.coda.mobileui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 全面屏（edge-to-edge）适配：内容延伸到系统栏区域；
 * 状态栏默认隐藏（沉浸式），从顶部下滑可临时呼出。
 */
fun AppCompatActivity.setupEdgeToEdge() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    val isNight =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
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
    val base = basePad()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        view.padRelative(base, bars.top, 0)
        insets
    }
}

/** 顶部 + 底部组件内边距：系统栏与刘海/挖孔区域取并集。 */
fun View.applySystemBarsInset() {
    val base = basePad()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        view.padRelative(base, bars.top, bars.bottom)
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
    val cutout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 页面拿到的 Context 常被主题包装过（ContextThemeWrapper 等），
            // 直接 `as? Activity` 会失败，刘海避让就悄悄退回状态栏高度
            findHostActivity()
                ?.window
                ?.decorView
                ?.rootWindowInsets
                ?.displayCutout
                ?.safeInsetTop ?: 0
        } else {
            0
        }
    return maxOf(statusBar, cutout)
}

/** 沿 ContextWrapper 链往上找宿主 Activity；找不到返回 null。 */
internal fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** 底部避让高度：系统导航栏高度；取不到时用 24dp 兜底。 */
fun Context.bottomSafeInsetPx(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dpPx(24)
}

private fun Context.dpPx(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

/**
 * 给一个顶部组件加状态栏避让：把原有内边距整体下移一个安全区高度。
 *
 * 与 applyTopSystemBarInset 的区别是它不依赖 WindowInsets —— 沉浸式下那套会拿到 0，
 * 于是组件直接顶到屏幕最上面、被状态栏或挖孔压住。
 */
fun View.padForTopSafeInset() {
    val extra = context.topSafeInsetPx()
    val applied = topPads[this] ?: 0
    val top = EdgeToEdge.toppedPadding(paddingTop, applied, extra)
    topPads[this] = extra
    setPaddingRelative(paddingStart, top, paddingEnd, paddingBottom)
}

/** 给一个底部组件加导航栏避让。 */
fun View.padForBottomSafeInset() {
    val extra = context.bottomSafeInsetPx()
    val applied = bottomPads[this] ?: 0
    val bottom = paddingBottom - applied + extra
    bottomPads[this] = extra
    setPaddingRelative(paddingStart, paddingTop, paddingEnd, bottom)
}

/**
 * 安全区内边距的纯计算部分。
 *
 * 单独拆出来是因为它是这条链路上唯一能脱离 View 验证的一环：之前直接写
 * `paddingTop + extra`，页面每次重建都调用一次，内边距就一路累加。
 */
internal object EdgeToEdge {
    /** 在已加过 [previouslyAdded] 的内边距上，改设成 [extra]。 */
    fun toppedPadding(
        current: Int,
        previouslyAdded: Int,
        extra: Int,
    ): Int = current - previouslyAdded + extra
}

/** 记录每个 View 上一次加了多少安全区，供下次调用时扣掉，避免重复累加。 */
private val topPads = WeakHashMap<View, Int>()
private val bottomPads = WeakHashMap<View, Int>()

/**
 * 记录 View 的原始（XML 声明的）四向内边距，只在第一次见到该 View 时快照。
 * inset 监听器每次分发都在基线上叠加安全区，而不是覆盖当前 padding：
 * 直接 setPadding(bars.top) 会把 XML 的 paddingTop=12dp 静默丢掉，
 * 后续代码加的 padding 也会被下一次分发清掉。
 */
private data class BasePad(val left: Int, val top: Int, val right: Int, val bottom: Int, val start: Int, val end: Int)
private val basePads = WeakHashMap<View, BasePad>()
private fun View.basePad(): BasePad =
    basePads.getOrPut(this) { BasePad(paddingLeft, paddingTop, paddingRight, paddingBottom, paddingStart, paddingEnd) }

/** 在基线 padding 上叠加安全区；用相对方向 API 保住 RTL 解析。 */
private fun View.padRelative(base: BasePad, top: Int, bottom: Int) {
    setPaddingRelative(base.start, base.top + top, base.end, base.bottom + bottom)
}

/** 底部内边距：取系统栏与输入法（键盘）的较大者，并叠加原有底部间距。 */
fun View.applyBottomInsetWithIme() {
    val base = basePad()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.padRelative(base, 0, maxOf(bars.bottom, ime.bottom))
        insets
    }
}

/** 抽屉内边距：顶部在系统栏/挖孔区域之外再留出 gap，底部跟随系统栏。 */
fun View.applyDrawerInset(topGapDp: Int) {
    val gap = (topGapDp * resources.displayMetrics.density).roundToInt()
    val base = basePad()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        view.padRelative(base, bars.top + gap, bars.bottom)
        insets
    }
}
