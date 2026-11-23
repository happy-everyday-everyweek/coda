package com.coda.mobileui.core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * interval 规则的分钟/小时字段（#757）。
 *
 * expand 支持多值/步长后，single 又用 takeIf{size==1} 把它们静默丢掉
 * 回退 anchor——用户规则被悄悄改写。现在显式拒绝。
 */
class AutomationIntervalFieldTest {
    private val anchor = 1_700_000_000_000L

    @Test
    fun `单值分钟正常通过`() {
        val rule = AutomationSchedule.buildIntervalRule("daily", 1, "30 9 * * *", anchor)
        assertEquals(30, rule.optInt("minute"))
        assertEquals(9, rule.optInt("hour"))
    }

    @Test
    fun `通配分钟回退 anchor 不报错`() {
        val rule = AutomationSchedule.buildIntervalRule("hourly", 1, "* * * * *", anchor)
        assertEquals("hourly", rule.optString("unit"))
    }

    @Test
    fun `多值分钟显式拒绝而不是静默改写`() {
        try {
            AutomationSchedule.buildIntervalRule("daily", 1, "0,30 9 * * *", anchor)
            fail("多值分钟必须拒绝")
        } catch (e: AutomationException) {
            assertTrue(e.message!!.contains("cron"))
        }
    }

    @Test
    fun `步长分钟显式拒绝`() {
        try {
            AutomationSchedule.buildIntervalRule("hourly", 1, "*/15 * * * *", anchor)
            fail("步长分钟必须拒绝")
        } catch (e: AutomationException) {
            assertTrue(e.message!!.contains("cron"))
        }
    }

    @Test
    fun `区间小时显式拒绝`() {
        try {
            AutomationSchedule.buildIntervalRule("daily", 1, "0 9-17 * * *", anchor)
            fail("区间小时必须拒绝")
        } catch (e: AutomationException) {
            assertTrue(e.message!!.contains("cron"))
        }
    }
}
