package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodaExtras 的纯解析测试。
 *
 * 覆盖 #240 大数值整数化、#239 JSON null 与标量 userConfig、#241 缺 statuses 的区分。
 */
class CodaExtrasParseTest {
    // ---------------------------------------------------------------- #240 数值
    @Test
    fun smallIntegersLoseTrailingZero() {
        assertEquals("42", CodaExtras.doubleText(42.0))
        assertEquals("-7", CodaExtras.doubleText(-7.0))
        assertEquals("0", CodaExtras.doubleText(0.0))
    }

    @Test
    fun fractionalValuesKeepDecimals() {
        assertEquals("1.5", CodaExtras.doubleText(1.5))
    }

    @Test
    fun hugeValueIsNotSaturatedToLongMax() {
        // 9.223372036854776E18 的精确值是 9223372036854775808（Long.MAX_VALUE + 1）。
        // 旧实现 toLong() 饱和成 Long.MAX_VALUE 并据此判为整数，输出 ...807（假值）。
        val v = 9.223372036854776E18
        val text = CodaExtras.doubleText(v)
        assertNotEquals("9223372036854775807", text)
        assertEquals("9223372036854775808", text)
    }

    @Test
    fun valuesBeyondSafeIntegerKeepDistinctText() {
        // 2^53 之后相邻可表示的 Double 相差 2，旧实现往返 Long 会印出同一串。
        val a = 9007199254740992.0
        val b = 9007199254740994.0
        assertNotEquals(CodaExtras.doubleText(a), CodaExtras.doubleText(b))
    }

    @Test
    fun nonFiniteValuesFallBackToString() {
        assertEquals("Infinity", CodaExtras.doubleText(Double.POSITIVE_INFINITY))
        assertEquals("NaN", CodaExtras.doubleText(Double.NaN))
    }

    // ---------------------------------------------------------------- #239 null
    @Test
    fun jsonNullRendersAsEmptyNotNullLiteral() {
        assertEquals("", CodaExtras.anyToText(JSONObject.NULL))
        assertEquals("", CodaExtras.anyToText(null))
    }

    @Test
    fun scalarUserConfigStillProducesOption() {
        val plugin =
            JSONObject()
                .put("id", "p1")
                .put("userConfig", JSONObject().put("apiKey", "secret-value"))
        val root = JSONObject().put("plugins", org.json.JSONArray().put(plugin))
        val detail = CodaExtras.findPluginDetail(root, "p1")!!
        val opt = detail.options.firstOrNull { it.key == "apiKey" }
        assertTrue("标量 userConfig 条目不应被跳过", opt != null)
        assertEquals("secret-value", CodaExtras.anyToText(opt!!.current))
    }

    @Test
    fun explicitNullDefaultBecomesNullNotLiteral() {
        val plugin =
            JSONObject()
                .put("id", "p2")
                .put(
                    "userConfig",
                    JSONObject().put("token", JSONObject().put("type", "string").put("default", JSONObject.NULL)),
                )
        val root = JSONObject().put("plugins", org.json.JSONArray().put(plugin))
        val detail = CodaExtras.findPluginDetail(root, "p2")!!
        val opt = detail.options.first { it.key == "token" }
        assertEquals("", CodaExtras.anyToText(opt.default))
    }

    // ---------------------------------------------------------------- #241 statuses
    @Test
    fun missingStatusesReturnsEmptyWithoutThrowing() {
        assertTrue(CodaExtras.parseMcpServers(JSONObject().put("other", 1)).isEmpty())
        assertTrue(CodaExtras.parseMcpServers(null).isEmpty())
    }

    @Test
    fun presentStatusesAreParsedAndSorted() {
        val root =
            JSONObject().put(
                "statuses",
                JSONObject()
                    .put("zeta", JSONObject().put("status", "ok").put("toolCount", 2))
                    .put("alpha", JSONObject().put("status", "error").put("error", "boom")),
            )
        val items = CodaExtras.parseMcpServers(root)
        assertEquals(listOf("alpha", "zeta"), items.map { it.name })
        assertEquals("boom", items[0].error)
        assertEquals(2, items[1].toolCount)
    }
}