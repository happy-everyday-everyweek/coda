package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * hooks 信任摘要的 JS 兼容数字格式化。
 *
 * 对应 issue #282：Kotlin Double.toString 与 JS Number.toString 在指数阈值、
 * 指数符号与 >=2^53 整数的表示上不一致，会让两端摘要不匹配。
 */
class HooksNumberTest {
    @Test
    fun safeIntegersStayPlain() {
        assertEquals("0", HooksCore.jsNumber(0.0))
        assertEquals("42", HooksCore.jsNumber(42.0))
        assertEquals("-7", HooksCore.jsNumber(-7.0))
        assertEquals("9007199254740991", HooksCore.jsNumber(9007199254740991.0))
    }

    @Test
    fun smallDecimalsMatchJsPlainForm() {
        // JS: (0.000001).toString() === "0.000001"，Kotlin 默认是 1.0E-6。
        assertEquals("0.000001", HooksCore.jsNumber(1.0E-6))
        assertEquals("0.5", HooksCore.jsNumber(0.5))
        assertEquals("1.25", HooksCore.jsNumber(1.25))
    }

    @Test
    fun tinyValuesUseJsExponentForm() {
        // JS: (1e-7).toString() === "1e-7"。
        assertEquals("1e-7", HooksCore.jsNumber(1.0E-7))
        assertEquals("1.5e-7", HooksCore.jsNumber(1.5E-7))
    }

    @Test
    fun hugeValuesUseExplicitPlusExponent() {
        // JS: (1e21).toString() === "1e+21"；toLong() 会饱和成 Long.MAX_VALUE。
        assertEquals("1e+21", HooksCore.jsNumber(1.0E21))
        assertEquals("1e+30", HooksCore.jsNumber(1.0E30))
    }

    @Test
    fun nonFiniteSerializesAsNull() {
        assertEquals("null", HooksCore.jsNumber(Double.NaN))
        assertEquals("null", HooksCore.jsNumber(Double.POSITIVE_INFINITY))
    }
}