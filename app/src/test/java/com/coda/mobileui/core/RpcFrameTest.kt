package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-RPC 响应帧的解读。
 *
 * 两条硬约束：字符串 id 也要能关联上 pending；非对象的 result/error 不能静默丢弃，
 * 尤其非对象的 error 绝不能被判成成功。
 */
class RpcFrameTest {
    @Test
    fun `numeric and string ids both produce a key`() {
        assertEquals("7", RpcFrame.idKey(7))
        assertEquals("7", RpcFrame.idKey(7L))
        // JSON 解析可能把整数给成 Double，要与整数落到同一个 key。
        assertEquals("7", RpcFrame.idKey(7.0))
        assertEquals("abc", RpcFrame.idKey("abc"))
    }

    @Test
    fun `absent id yields no key`() {
        assertNull(RpcFrame.idKey(null))
        assertNull(RpcFrame.idKey(JSONObject.NULL))
    }

    @Test
    fun `object result is passed through`() {
        val frame = JSONObject().put("result", JSONObject().put("a", 1))
        assertFalse(RpcFrame.isError(frame))
        val data = RpcFrame.payload(frame)
        assertEquals(1, data.optInt("a"))
        assertFalse(RpcFrame.isWrapped(data))
    }

    @Test
    fun `array result is preserved not dropped`() {
        val frame = JSONObject().put("result", JSONArray().put("x").put("y"))
        val data = RpcFrame.payload(frame)
        assertTrue(RpcFrame.isWrapped(data))
        assertEquals(2, (RpcFrame.unwrapped(data) as JSONArray).length())
    }

    @Test
    fun `scalar results are preserved`() {
        for (value in listOf("text", 42, true)) {
            val data = RpcFrame.payload(JSONObject().put("result", value))
            assertTrue(RpcFrame.isWrapped(data))
            assertEquals(value, RpcFrame.unwrapped(data))
        }
    }

    @Test
    fun `string error is still an error`() {
        val frame = JSONObject().put("error", "boom")
        assertTrue(RpcFrame.isError(frame))
        assertEquals("boom", RpcFrame.unwrapped(RpcFrame.payload(frame)))
    }

    @Test
    fun `numeric error is still an error`() {
        assertTrue(RpcFrame.isError(JSONObject().put("error", -32601)))
    }

    @Test
    fun `object error is passed through`() {
        val frame = JSONObject().put("error", JSONObject().put("message", "nope"))
        assertTrue(RpcFrame.isError(frame))
        assertEquals("nope", RpcFrame.payload(frame).optString("message"))
    }

    @Test
    fun `null error is not a failure`() {
        val frame = JSONObject().put("error", JSONObject.NULL).put("result", JSONObject().put("a", 1))
        assertFalse(RpcFrame.isError(frame))
        assertEquals(1, RpcFrame.payload(frame).optInt("a"))
    }

    @Test
    fun `missing result yields an empty object`() {
        val data = RpcFrame.payload(JSONObject())
        assertEquals(0, data.length())
        assertFalse(RpcFrame.isWrapped(data))
    }
}
