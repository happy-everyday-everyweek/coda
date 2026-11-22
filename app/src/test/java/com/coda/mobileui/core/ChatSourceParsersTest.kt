package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个来源解析器（#734 第一批）。
 *
 * 重点盯两类容易错的地方：ChatGPT 的 DAG 主链回溯（分支不能全导进来），
 * 以及降级计数是否真的记上（静默丢内容是参考实现明确禁止的）。
 */
class ChatSourceParsersTest {
    private fun node(
        id: String,
        parent: String?,
        role: String,
        text: String,
        time: Double = 0.0,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("parent", parent ?: JSONObject.NULL)
            .put(
                "message",
                JSONObject()
                    .put("author", JSONObject().put("role", role))
                    .put("create_time", time)
                    .put("content", JSONObject().put("parts", JSONArray().put(text))),
            )

    @Test
    fun `ChatGPT 按 current_node 取主链，其他分支折叠并计数`() {
        val mapping =
            JSONObject()
                .put("a", node("a", null, "user", "问题", 1.0))
                .put("b", node("b", "a", "assistant", "主回答", 2.0))
                .put("c", node("c", "a", "assistant", "被弃用的分支", 3.0))
        val conv = JSONObject().put("title", "会话").put("mapping", mapping).put("current_node", "b")
        val d = Interchange.Degradations()
        val doc = ChatSourceParsers.chatgpt(conv, d)
        assertEquals(1, doc.turns.size)
        assertEquals("问题", doc.turns[0].userText)
        val body = Interchange.toMessages(doc).last().content
        assertTrue(body.contains("主回答"))
        assertFalse(body.contains("被弃用的分支"))
        assertEquals(1, d.branchCollapsed)
    }

    @Test
    fun `ChatGPT 缺 current_node 时按时间取最晚节点`() {
        val mapping =
            JSONObject()
                .put("a", node("a", null, "user", "问", 1.0))
                .put("b", node("b", "a", "assistant", "早", 2.0))
                .put("c", node("c", "b", "assistant", "晚", 5.0))
        val doc = ChatSourceParsers.chatgpt(JSONObject().put("mapping", mapping))
        assertTrue(Interchange.toMessages(doc).last().content.contains("晚"))
    }

    @Test
    fun `ChatGPT 时间戳从秒转毫秒`() {
        val mapping = JSONObject().put("a", node("a", null, "user", "问", 1_700_000_000.0))
        val conv = JSONObject().put("mapping", mapping).put("current_node", "a")
        val doc = ChatSourceParsers.chatgpt(conv)
        assertEquals(1_700_000_000_000L, Interchange.toMessages(doc)[0].timestamp)
    }

    @Test
    fun `ChatGPT 父子成环不死循环`() {
        val mapping =
            JSONObject()
                .put("a", node("a", "b", "user", "问"))
                .put("b", node("b", "a", "assistant", "答"))
        val doc = ChatSourceParsers.chatgpt(JSONObject().put("mapping", mapping).put("current_node", "a"))
        assertTrue(doc.turns.size <= 2)
    }

    @Test
    fun `Claude Code 工具调用与结果配对，孤儿结果计数`() {
        val events =
            listOf(
                JSONObject()
                    .put("timestamp", "2024-01-01T00:00:00Z")
                    .put("message", JSONObject().put("role", "user").put("content", "读文件")),
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "tool_use").put("id", "t1").put("name", "read_file")
                                .put("input", JSONObject().put("path", "a.txt")),
                        ),
                    ),
                ),
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", "t1").put("content", "内容")),
                    ),
                ),
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "tool_result").put("tool_use_id", "unknown").put("content", "孤儿")),
                    ),
                ),
            )
        val d = Interchange.Degradations()
        val doc = ChatSourceParsers.claudeCode(events, d)
        // 只有 tool_result 的 user 消息不另起一轮，所以仍是一轮。
        assertEquals(1, doc.turns.size)
        val body = Interchange.toMessages(doc, d).last().content
        assertTrue(body.contains("read_file"))
        assertTrue(body.contains("内容"))
        assertEquals(1, d.toolResultMissing)
    }

    @Test
    fun `Claude Code 的 thinking 进推理块，image 计降级`() {
        val events =
            listOf(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "thinking").put("thinking", "想一想"))
                            .put(JSONObject().put("type", "text").put("text", "答案"))
                            .put(JSONObject().put("type", "image")),
                    ),
                ),
            )
        val d = Interchange.Degradations()
        val doc = ChatSourceParsers.claudeCode(events, d)
        val body = Interchange.toMessages(doc, d).last().content
        assertTrue(body.contains("想一想"))
        assertTrue(body.contains("答案"))
        assertEquals(1, d.attachmentSkipped)
    }

    @Test
    fun `Codex 密文推理占位并计数，summary 作标题`() {
        val events =
            listOf(
                JSONObject().put("type", "session_meta").put("payload", JSONObject().put("thread_name", "我的任务")),
                JSONObject().put("type", "response_item").put(
                    "payload",
                    JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("text", "做事"))),
                ),
                JSONObject().put("type", "response_item").put(
                    "payload",
                    JSONObject().put("role", "assistant").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("encrypted_content", "AAAA"))
                            .put(JSONObject().put("text", "做完了")),
                    ),
                ),
            )
        val d = Interchange.Degradations()
        val doc = ChatSourceParsers.codex(events, d)
        assertEquals("我的任务", doc.title)
        val body = Interchange.toMessages(doc, d).last().content
        assertTrue(body.contains("做完了"))
        assertTrue(body.contains("加密"))
        assertEquals(1, d.reasoningEncrypted)
    }

    @Test
    fun `JSONL 坏行跳过而不是整份失败`() {
        val text =
            """
            {"type":"session_meta","payload":{}}
            这不是 JSON
            {"type":"response_item","payload":{"role":"user","content":[{"text":"问"}]}}
            """.trimIndent()
        val events = ChatSourceParsers.jsonlEvents(text)
        assertEquals(2, events.size)
    }

    @Test
    fun `空 JSONL 与空 conversations 都返回空列表`() {
        assertTrue(ChatSourceParsers.jsonlEvents("").isEmpty())
        assertTrue(ChatSourceParsers.conversations("not json").isEmpty())
        assertTrue(ChatSourceParsers.conversations("[]").isEmpty())
    }

    @Test
    fun `conversations 数组解析`() {
        val text = """[{"title":"a","mapping":{}},{"title":"b","mapping":{}}]"""
        assertEquals(2, ChatSourceParsers.conversations(text).size)
    }

    @Test
    fun `ISO 时间解析失败返回 null 而不是 0`() {
        assertEquals(0L, ChatSourceParsers.parseIso("1970-01-01T00:00:00Z")!!)
        assertNull(ChatSourceParsers.parseIso("不是时间"))
        assertNull(ChatSourceParsers.parseIso(""))
        assertNull(ChatSourceParsers.parseIso(null))
        assertTrue(ChatSourceParsers.parseIso("2024-01-01T00:00:00Z")!! > 0)
    }

    @Test
    fun `空 mapping 的 ChatGPT 会话不产出空壳`() {
        val doc = ChatSourceParsers.chatgpt(JSONObject().put("title", "空").put("mapping", JSONObject()))
        assertFalse(Interchange.usable(doc))
    }

    @Test
    fun `三个解析器都产出可用的 IR 而非直接产消息`() {
        // 框架约定：解析器只到 IR，落地统一走 Interchange.toMessages。
        val conv = JSONObject().put("mapping", JSONObject().put("a", node("a", null, "user", "问"))).put("current_node", "a")
        assertTrue(Interchange.usable(ChatSourceParsers.chatgpt(conv)))
        val cc =
            listOf(
                JSONObject().put("message", JSONObject().put("role", "user").put("content", "问")),
            )
        assertTrue(Interchange.usable(ChatSourceParsers.claudeCode(cc)))
        val cx =
            listOf(
                JSONObject().put("type", "response_item").put(
                    "payload",
                    JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("text", "问"))),
                ),
            )
        assertTrue(Interchange.usable(ChatSourceParsers.codex(cx)))
    }
}