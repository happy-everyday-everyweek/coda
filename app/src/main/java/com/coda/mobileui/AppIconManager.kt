package com.coda.mobileui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.coda.mobileui.core.AppLog

/**
 * 应用图标套件切换。
 *
 * 图标颜色跟随主色：0 号是“自动取色”的中性套件（黑底白角色，深色模式反转），
 * 1..8 号依次对应主色色板。切换方式是把对应的 activity-alias 设为启用、
 * 其余设为禁用——这是系统允许的、唯一能在运行期更换启动图标的方式。
 */
object AppIconManager {
    private val ALIASES =
        arrayOf(
            "LauncherAuto",
            "LauncherBlack",
            "LauncherBlue",
            "LauncherViolet",
            "LauncherGreen",
            "LauncherAmber",
            "LauncherRose",
            "LauncherTeal",
            "LauncherOrange",
        )

    /** 别名清单，测试与实现共用同一份顺序。 */
    internal val ALIAS_NAMES: List<String> = ALIASES.toList()

    /** 与 PackageManager 的 COMPONENT_ENABLED_STATE_* 取值一致，便于在 JVM 单测里断言。 */
    internal const val STATE_ENABLED = 1

    /** 见 [STATE_ENABLED]。 */
    internal const val STATE_DISABLED = 2

    private const val PREFS = "app_icon"
    private const val KEY_APPLIED = "appliedSlot"
    private const val TAG = "AppIcon"

    /**
     * 组件设置序列：先启用目标，再禁用其余。
     *
     * 顺序是有意义的——之前先禁用旧别名、再启用新别名，两步之间的空档里桌面上
     * 一个可用入口都没有，图标会闪一下甚至消失。
     */
    internal fun planFor(slot: Int): List<Pair<String, Int>> {
        val target = slot.coerceIn(0, ALIASES.size - 1)
        val plan = ArrayList<Pair<String, Int>>(ALIASES.size)
        plan += ALIASES[target] to STATE_ENABLED
        ALIASES.forEachIndexed { index, alias ->
            if (index != target) plan += alias to STATE_DISABLED
        }
        return plan
    }

    /**
     * 是否需要真的写组件状态。
     *
     * 每个页面创建都会走一次切换，而每次写入都会让启动器刷新图标。槽位没变、
     * 目标别名也仍处于启用状态时直接返回。
     */
    internal fun shouldSkip(
        slot: Int,
        applied: Int,
        targetEnabled: Boolean,
    ): Boolean = applied == slot && targetEnabled

    /**
     * 按 [planFor] 逐个设置组件。
     *
     * 目标别名的启用排在最前面，但它**可能失败**（清单缺失、SecurityException）。
     * 启用没落地就继续禁用其余别名，会把桌面清成零入口——图标直接消失，
     * 正是「先启用后禁用」要防的事故（#817）。启用失败立即中止；
     * 禁用阶段单个失败只影响它自己，继续处理其余。
     */
    internal fun applyWith(
        slot: Int,
        set: (alias: String, state: Int) -> Boolean,
    ): Boolean {
        var allOk = true
        for ((alias, state) in planFor(slot)) {
            val ok = runCatching { set(alias, state) }.getOrElse { false }
            if (!ok) {
                allOk = false
                if (state == STATE_ENABLED) return false
            }
        }
        return allOk
    }

    /** 启用第 slot 套图标（0=自动取色，1..8 对应色板顺序）。 */
    fun apply(
        context: Context,
        slot: Int,
    ) {
        val packageName = context.packageName
        val manager = context.packageManager
        val target = slot.coerceIn(0, ALIASES.size - 1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val targetComponent = ComponentName(packageName, "$packageName.${ALIASES[target]}")
        val targetEnabled = runCatching { isEnabled(manager, targetComponent) }.getOrDefault(false)
        if (shouldSkip(target, prefs.getInt(KEY_APPLIED, -1), targetEnabled)) return

        val allOk =
            applyWith(target) { alias, state ->
                val component = ComponentName(packageName, "$packageName.$alias")
                manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                true
            }
        if (allOk) {
            prefs.edit().putInt(KEY_APPLIED, target).apply()
        } else {
            // 有别名没设置成功：不记为已应用，下次进入页面还会再试一次
            AppLog.w(TAG, "图标切换未全部成功，槽位 $target 不记为已应用")
        }
    }

    /**
     * 目标别名当前是否启用。
     *
     * 从未设置过的组件处于 COMPONENT_ENABLED_STATE_DEFAULT，此时以清单声明为准：
     * 只有 LauncherAuto 是 enabled="true"。
     */
    private fun isEnabled(
        manager: PackageManager,
        component: ComponentName,
    ): Boolean =
        when (manager.getComponentEnabledSetting(component)) {
            STATE_ENABLED -> true
            STATE_DISABLED -> false
            else -> component.className.endsWith(".LauncherAuto")
        }
}
