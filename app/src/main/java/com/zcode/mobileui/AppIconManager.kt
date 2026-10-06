package com.zcode.mobileui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 应用图标套件切换。
 *
 * 图标颜色跟随主色：0 号是“自动取色”的中性套件（黑底白角色，深色模式反转），
 * 1..8 号依次对应主色色板。切换方式是把对应的 activity-alias 设为启用、
 * 其余设为禁用——这是系统允许的、唯一能在运行期更换启动图标的方式。
 */
object AppIconManager {

    private val ALIASES = arrayOf(
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

    /** 启用第 slot 套图标（0=自动取色，1..8 对应色板顺序）。 */
    fun apply(context: Context, slot: Int) {
        val packageName = context.packageName
        val manager = context.packageManager
        val target = slot.coerceIn(0, ALIASES.size - 1)

        ALIASES.forEachIndexed { index, alias ->
            val component = ComponentName(packageName, "$packageName.$alias")
            val state = if (index == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}