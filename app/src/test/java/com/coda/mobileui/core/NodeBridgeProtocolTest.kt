package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeBridgeProtocolTest {
    @Test
    fun parsesValidJsonAndRejectsGarbage() {
        assertNotNull(NodeBridgeProtocol.parse("""{"jsonrpc":"2.0","id":1,"method":"node.ping"}"""))
        assertNull(NodeBridgeProtocol.parse("not-json"))
    }

    @Test
    fun buildsResultErrorAndEventEnvelopes() {
        val ok = NodeBridgeProtocol.result(7, JSONObject().put("pong", true))
        assertEquals("2.0", ok.getString("jsonrpc"))
        assertEquals(7, ok.getInt("id"))
        assertTrue(ok.getJSONObject("result").getBoolean("pong"))

        val err = NodeBridgeProtocol.error(8, NodeBridgeProtocol.ERR_UNAUTHORIZED, "unauthorized")
        assertEquals(NodeBridgeProtocol.ERR_UNAUTHORIZED, err.getJSONObject("error").getInt("code"))

        val event = NodeBridgeProtocol.event("message.delta", JSONObject().put("sessionId", "s1").put("text", "hi"))
        assertEquals(NodeBridgeProtocol.EVENT, event.getString("method"))
        assertEquals("message.delta", event.getJSONObject("params").getString("type"))
        assertEquals("hi", event.getJSONObject("params").getString("text"))
    }

    @Test
    fun tokenCompareIsExact() {
        assertTrue(NodeBridgeProtocol.tokenMatches("abc", "abc"))
        assertFalse(NodeBridgeProtocol.tokenMatches("abc", "abd"))
        assertFalse(NodeBridgeProtocol.tokenMatches("abc", null))
        assertFalse(NodeBridgeProtocol.tokenMatches("abc", ""))
    }
}
