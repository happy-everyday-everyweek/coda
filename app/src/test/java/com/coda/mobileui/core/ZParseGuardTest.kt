package com.coda.mobileui.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议解析的防御行为。
 *
 * 对应 issue：#454（callId 缺失时键冲突）、#455（非对象 input 被丢弃）、
 * #457（无标题快照覆盖已有标题）、#459（缺 id 消息被静默丢弃）、
 * #460（空 url 附件生成破损占位）。
 */
class ZParseGuardTest {
    private fun messages(vararg infos: String): JSONObject =
        JSONObject("""{"messages":[${infos.joinToString(",")}]}""")

    // ------------------------------------------------------------ #454
    @Test
    fun toolPartsWithoutCallIdGetDistinctKeys() {
        val m = messages(
            """{"info":{"id":"m1","role":"assistant"},"parts":[
                {"type":"tool","tool":"a","state":{"status":"running"}},
                {"type":"tool","tool":"b","state":{"status":"running"}}]}""",
        )
        val parts = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.ToolPart>()
        assertEquals(2, parts.size)
        assertTrue("缺 callId 时不能都落空串", parts[0].callId.isNotEmpty())
        assertNotEquals("两个无 id 工具卡不得共享同一个键", parts[0].callId, parts[1].callId)
    }

    @Test
    fun explicitCallIdStillWins() {
        val m = messages(
            """{"info":{"id":"m1","role":"assistant"},"parts":[
                {"type":"tool","callID":"c1","tool":"a","state":{"status":"running"}}]}""",
        )
        val part = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.ToolPart>()[0]
        assertEquals("c1", part.callId)
    }

    // ------------------------------------------------------------ #455
    @Test
    fun stringInputIsKept() {
        val m = messages(
            """{"info":{"id":"m1","role":"assistant"},"parts":[
                {"type":"tool","callID":"c1","tool":"a","state":{"status":"running","input":"{\"path\":\"x\"}"}}]}""",
        )
        val part = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.ToolPart>()[0]
        assertEquals("{\"path\":\"x\"}", part.input)
    }

    @Test
    fun arrayInputIsKept() {
        val m = messages(
            """{"info":{"id":"m1","role":"assistant"},"parts":[
                {"type":"tool","callID":"c1","tool":"a","state":{"status":"running","input":[1,2]}}]}""",
        )
        val part = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.ToolPart>()[0]
        assertEquals("[1,2]", part.input)
    }

    // ------------------------------------------------------------ #457
    @Test
    fun snapshotWithoutTitleReturnsNull() {
        assertNull(ZParse.parseTitleFromSnapshot(JSONObject("""{"session":{"sessionId":"s"}}""")))
        assertNull(ZParse.parseTitleFromSnapshot(JSONObject("""{"session":{"title":null}}""")))
        assertEquals("t", ZParse.parseTitleFromSnapshot(JSONObject("""{"session":{"title":"t"}}""")))
        // 显式空串是「清空标题」的合法表达，保留为空串而不是 null。
        assertEquals("", ZParse.parseTitleFromSnapshot(JSONObject("""{"session":{"title":""}}""")))
    }

    // ------------------------------------------------------------ #459
    @Test
    fun messageWithoutIdIsKeptWithSyntheticId() {
        val m = messages(
            """{"info":{"role":"assistant"},"parts":[{"type":"text","text":"hello"}]}""",
        )
        val parsed = ZParse.parseMessages(m)
        assertEquals("缺 id 的消息不得整条消失", 1, parsed.size)
        assertTrue(parsed[0].id.isNotEmpty())
        assertEquals("hello", parsed[0].text())
    }

    @Test
    fun syntheticIdsAreStableAcrossParses() {
        val m = messages(
            """{"info":{"role":"assistant"},"parts":[{"type":"text","text":"hello"}]}""",
            """{"info":{"role":"user"},"parts":[{"type":"text","text":"world"}]}""",
        )
        val a = ZParse.parseMessages(m)
        val b = ZParse.parseMessages(m)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertNotEquals(a[0].id, a[1].id)
    }

    // ------------------------------------------------------------ #460
    @Test
    fun filePartWithBlankUrlIsSkipped() {
        val m = messages(
            """{"info":{"id":"m1","role":"user"},"parts":[
                {"type":"file","filename":"a.png","mime":"image/png","url":""},
                {"type":"file","filename":"b.png","mime":"image/png","url":"zcode-artifact://b"}]}""",
        )
        val parts = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.FilePart>()
        assertEquals("空 url 的附件不得生成破损占位", 1, parts.size)
        assertEquals("b.png", parts[0].filename)
    }

    @Test
    fun unsupportedMimeDegradesToGenericFile() {
        val m = messages(
            """{"info":{"id":"m1","role":"user"},"parts":[
                {"type":"file","filename":"x.bin","mime":"application/x-nonsense","url":"zcode-artifact://x"}]}""",
        )
        val part = ZParse.parseMessages(m)[0].parts.filterIsInstance<ZPart.FilePart>()[0]
        // 不受支持的 mime 归一为通用二进制，UI 走文件卡而不是破损图片预览。
        assertEquals("application/octet-stream", part.mime)
    }
}