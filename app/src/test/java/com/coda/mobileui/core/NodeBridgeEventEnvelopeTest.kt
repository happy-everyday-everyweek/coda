package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件信封：seq 单调标识、ts 用 ISO-8601，业务字段不许覆盖信封字段。 */
class NodeBridgeEventEnvelopeTest {
    @Test
    fun carriesSeqAndIsoTimestamp() {
        val event = NodeBridgeProtocol.event("message.delta", JSONObject().put("text", "hi"), 7L)
        val params = event.getJSONObject("params")
        assertEquals(7, params.getInt("seq"))
        assertTrue(params.getString("ts").endsWith("Z"))
        assertEquals("hi", params.getString("text"))
    }

    @Test
    fun payloadCannotOverwriteEnvelopeFields() {
        val event =
            NodeBridgeProtocol.event(
                "message.delta",
                JSONObject().put("type", "fake").put("ts", 123).put("seq", 99),
                3L,
            )
        val params = event.getJSONObject("params")
        assertEquals("message.delta", params.getString("type"))
        assertEquals(3, params.getInt("seq"))
        assertTrue(params.getString("ts").endsWith("Z"))
    }

    @Test
    fun isoTimestampIsUtc() {
        assertEquals("1970-01-01T00:00:00Z", NodeBridgeProtocol.isoTimestamp(0L))
    }
}