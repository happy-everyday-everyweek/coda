package com.coda.mobileui.ui

import com.coda.mobileui.AppIconManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标套件切换的行为约定。
 *
 * 这三条约束都来自真实故障：先禁用旧别名会让桌面图标短暂消失；循环里一次异常会
 * 让后续别名全部保持禁用；每次页面创建都无条件设置 9 个组件会让启动器反复刷新。
 */
class AppIconPlanTest {
    @Test
    fun enablesTargetBeforeAnythingElse() {
        val plan = AppIconManager.planFor(0)
        assertEquals("LauncherAuto", plan.first().first)
        assertEquals(AppIconManager.STATE_ENABLED, plan.first().second)
    }

    @Test
    fun disablesEveryOtherAlias() {
        val plan = AppIconManager.planFor(3)
        assertEquals(AppIconManager.ALIAS_NAMES.size, plan.size)
        assertEquals(1, plan.count { it.second == AppIconManager.STATE_ENABLED })
        assertEquals("LauncherViolet", plan.first().first)
    }

    @Test
    fun clampsOutOfRangeSlots() {
        assertEquals("LauncherOrange", AppIconManager.planFor(99).first().first)
        assertEquals("LauncherAuto", AppIconManager.planFor(-4).first().first)
    }

    @Test
    fun skipsOnlyWhenSameSlotIsAlreadyEnabled() {
        assertTrue(AppIconManager.shouldSkip(2, 2, true))
        assertFalse(AppIconManager.shouldSkip(2, 1, true))
        assertFalse(AppIconManager.shouldSkip(2, 2, false))
    }

    @Test
    fun keepsGoingWhenOneComponentThrows() {
        val seen = mutableListOf<String>()
        val ok =
            AppIconManager.applyWith(0) { alias, _ ->
                seen += alias
                if (alias == "LauncherBlue") throw IllegalArgumentException("alias missing in manifest")
                true
            }
        assertFalse(ok)
        assertEquals(AppIconManager.ALIAS_NAMES.size, seen.size)
        assertEquals("LauncherAuto", seen.first())
    }

    @Test
    fun reportsSuccessWhenEveryComponentWasSet() {
        val ok = AppIconManager.applyWith(1) { _, _ -> true }
        assertTrue(ok)
    }
}
