package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON 里显式 null、超长长度字段这类"看着有值实际没有"的输入。 */
class JsonParsingGuardTest {
    @Test
    fun explicitNullDoesNotBecomeTheStringNull() {
        val o =
            JSONObject()
                .put("parentToolUseId", JSONObject.NULL)
                .put("parentToolCallId", "call-1")
        assertEquals("call-1", ZWorkflowKit.parentToolUseIdOf(o))
    }

    @Test
    fun explicitNullOrdinalDoesNotTurnIntoAddressZero() {
        val actor = JSONObject().put("siteId", "site-1").put("ordinal", JSONObject.NULL)
        assertEquals("site-1", ZWorkflowKit.actorAddressOf(JSONObject().put("actor", actor)))
    }

    @Test
    fun zeroOrdinalIsStillAPosition() {
        val actor = JSONObject().put("siteId", "site-1").put("ordinal", 0)
        assertEquals("site-1@0", ZWorkflowKit.actorAddressOf(JSONObject().put("actor", actor)))
    }

    @Test
    fun oversizedLengthFieldIsAProtocolError() {
        // field 7, wire 2，长度 0xFFFFFFFF：旧实现 toInt 截断成 -1，随后 copyOfRange 抛越界。
        val frame =
            byteArrayOf(
                58,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0x0F,
            )
        val error = runCatching { VoiceGenieProtocol.decodeResponse(frame, isText = false) }.exceptionOrNull()
        assertTrue(error is VoiceGenieProtocol.ProtocolError)
    }
}
