package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多平台导入的中间结构与探测（#734）。
 *
 * 中间结构对齐 Interchange v1 的 turns IR：一条用户提问一个 turn，
 * 一条助手消息（含工具调用与结果）一个 step。降级必须显式计数，
 * 绝不静默丢内容——这是参考实现里最重要的一条约定。
 */
class ChatInterchangeTest {
    @Test
    fun `空文档不产出会话`() {
        val doc = Interchange.Document(title = "t", turns = emptyList())
        assertFalse(Interchange.usable(doc))
    }

    @Test
    fun `只有空块的 turn 也不算可用`() {
        val doc =
            Interchange.Document(
                title = "t",
                turns = listOf(Interchange.Turn(userText = "", steps = emptyList())),
            )
        assertFalse(Interchange.usable(doc))
    }

    @Test
    fun `有用户提问或有任一 step 内容即可用`() {
        assertTrue(
            Interchange.usable(
                Interchange.Document("t", listOf(Interchange.Turn("问题", emptyList()))),
            ),
        )
        assertTrue(
            Interchange.usable(
                Interchange.Document(
                    "t",
                    listOf(
                        Interchange.Turn(
                            "",
                            listOf(Interchange.Step(listOf(Interchange.Block.Text("回答")))),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `文本与推理块都进正文，推理带标记`() {
        val step =
            Interchange.Step(
                listOf(
                    Interchange.Block.Reasoning("想一想"),
                    Interchange.Block.Text("答案"),
                ),
            )
        val text = Interchange.stepText(step)
        assertTrue(text.contains("想一想"))
        assertTrue(text.contains("答案"))
        assertTrue(text.contains("思考"))
    }

    @Test
    fun `工具调用与结果转成可读文本行`() {
        val step =
            Interchange.Step(
                listOf(
                    Interchange.Block.ToolCall(name = "read_file", args = "a.txt"),
                    Interchange.Block.ToolResult(name = "read_file", output = "内容"),
                ),
            )
        val text = Interchange.stepText(step)
        assertTrue(text.contains("read_file"))
        assertTrue(text.contains("a.txt"))
        assertTrue(text.contains("内容"))
    }

    @Test
    fun `图片块降级为占位并计入降级统计`() {
        val step = Interchange.Step(listOf(Interchange.Block.Image("shot.png", bytesAvailable = false)))
        val d = Interchange.Degradations()
        val text = Interchange.stepText(step, d)
        assertTrue(text.contains("shot.png"))
        assertEquals(1, d.attachmentSkipped)
    }

    @Test
    fun `有字节的图片也只做占位，因为通道不接受附件`() {
        val step = Interchange.Step(listOf(Interchange.Block.Image("shot.png", bytesAvailable = true)))
        val d = Interchange.Degradations()
        Interchange.stepText(step, d)
        // 通道只接受纯文本 content，图片无论有没有字节都进不去。
        assertEquals(1, d.attachmentSkipped)
    }

    @Test
    fun `密文推理跳过并计数`() {
        val step = Interchange.Step(listOf(Interchange.Block.EncryptedReasoning))
        val d = Interchange.Degradations()
        val text = Interchange.stepText(step, d)
        assertEquals(1, d.reasoningEncrypted)
        assertTrue(text.contains("加密"))
    }

    @Test
    fun `孤儿工具结果计数`() {
        val step = Interchange.Step(listOf(Interchange.Block.ToolResult("x", "输出", orphan = true)))
        val d = Interchange.Degradations()
        Interchange.stepText(step, d)
        assertEquals(1, d.toolResultMissing)
    }

    @Test
    fun `分支折叠计数`() {
        val d = Interchange.Degradations()
        d.branchCollapsed += 2
        val summary = d.summary()
        assertTrue(summary.contains("2"))
        assertTrue(summary.contains("分支"))
    }

    @Test
    fun `没有降级时摘要为空串`() {
        assertEquals("", Interchange.Degradations().summary())
    }

    @Test
    fun `降级摘要覆盖全部类别`() {
        val d =
            Interchange.Degradations(
                toolResultMissing = 1,
                reasoningEncrypted = 1,
                attachmentSkipped = 1,
                branchCollapsed = 1,
            )
        val s = d.summary()
        assertEquals(4, s.lines().size)
    }

    @Test
    fun `文档转成内核消息序列，角色交替`() {
        val doc =
            Interchange.Document(
                title = "会话",
                turns =
                    listOf(
                        Interchange.Turn(
                            userText = "问题一",
                            steps = listOf(Interchange.Step(listOf(Interchange.Block.Text("回答一")))),
                        ),
                        Interchange.Turn(
                            userText = "问题二",
                            steps = listOf(Interchange.Step(listOf(Interchange.Block.Text("回答二")))),
                        ),
                    ),
            )
        val out = Interchange.toMessages(doc)
        assertEquals(4, out.size)
        assertEquals(listOf("user", "assistant", "user", "assistant"), out.map { it.role })
    }

    @Test
    fun `一个 turn 多个 step 合成一条助手消息`() {
        val doc =
            Interchange.Document(
                "t",
                listOf(
                    Interchange.Turn(
                        "问题",
                        listOf(
                            Interchange.Step(listOf(Interchange.Block.ToolCall("ls", "."))),
                            Interchange.Step(listOf(Interchange.Block.Text("结果"))),
                        ),
                    ),
                ),
            )
        val out = Interchange.toMessages(doc)
        assertEquals(2, out.size)
        assertTrue(out[1].content.contains("ls"))
        assertTrue(out[1].content.contains("结果"))
    }

    @Test
    fun `时间戳沿用源值且单调不减`() {
        val doc =
            Interchange.Document(
                "t",
                listOf(
                    Interchange.Turn("a", emptyList(), timestamp = 300L),
                    Interchange.Turn("b", emptyList(), timestamp = 100L),
                ),
            )
        val out = Interchange.toMessages(doc)
        val ts = out.mapNotNull { it.timestamp }
        assertTrue(DeepSeekImport.monotonic(ts))
        // 倒退的那条被抬到前一条的时间，而不是原样写入。
        assertEquals(listOf(300L, 300L), ts)
    }

    @Test
    fun `三级探测：显式格式优先`() {
        val adapters = ChatImportRegistry.all()
        val hit = ChatImportRegistry.probe(explicitFormat = "chatgpt", entryNames = emptyList(), head = "")
        assertNotNull(hit)
        assertEquals("chatgpt", hit!!.id)
    }

    @Test
    fun `三级探测：内容标记次之`() {
        val hit =
            ChatImportRegistry.probe(
                explicitFormat = null,
                entryNames = listOf("conversations.json"),
                head = "",
            )
        assertNotNull(hit)
        assertEquals("chatgpt", hit!!.id)
    }

    @Test
    fun `三级探测：解析器试读兜底`() {
        val hit =
            ChatImportRegistry.probe(
                explicitFormat = null,
                entryNames = listOf("rollout-2024.jsonl"),
                head = """{"type":"session_meta","payload":{}}""",
            )
        assertNotNull(hit)
        assertEquals("codex", hit!!.id)
    }

    @Test
    fun `全部未命中时返回 null 并能列出各适配器放弃的原因`() {
        val hit = ChatImportRegistry.probe(null, listOf("random.bin"), "not json")
        assertNull(hit)
        val reasons = ChatImportRegistry.rejectReasons(listOf("random.bin"), "not json")
        assertEquals(ChatImportRegistry.all().size, reasons.size)
        assertTrue(reasons.all { it.isNotBlank() })
    }

    @Test
    fun `注册表含第一批五类来源`() {
        val ids = ChatImportRegistry.all().map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("deepseek", "chatgpt", "claude_code", "codex", "interchange")))
    }

    @Test
    fun `第二批来源只在注册表预留不实现`() {
        val reserved = ChatImportRegistry.reserved()
        assertTrue(reserved.contains("gemini_cli"))
        assertTrue(reserved.contains("cursor"))
        // 预留项不出现在可用适配器里，避免用户选了却导不了。
        assertTrue(ChatImportRegistry.all().none { it.id in reserved })
    }

    @Test
    fun `每个适配器都有中文名与能力说明`() {
        ChatImportRegistry.all().forEach {
            assertTrue("${it.id} 缺标题", it.title.isNotBlank())
            assertTrue("${it.id} 缺能力说明", it.capability.isNotBlank())
        }
    }

    @Test
    fun `Interchange 文档解析往返`() {
        val json =
            JSONObject()
                .put("interchange", 1)
                .put("title", "会话")
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("user", "问题")
                            .put(
                                "steps",
                                JSONArray().put(
                                    JSONObject().put(
                                        "blocks",
                                        JSONArray().put(JSONObject().put("type", "text").put("text", "回答")),
                                    ),
                                ),
                            ),
                    ),
                )
        val doc = Interchange.parse(json)
        assertNotNull(doc)
        assertEquals("会话", doc!!.title)
        assertEquals(1, doc.turns.size)
        assertEquals("问题", doc.turns[0].userText)
        assertTrue(Interchange.usable(doc))
    }

    @Test
    fun `版本不符或缺 turns 的文档解析失败`() {
        assertNull(Interchange.parse(JSONObject().put("interchange", 99).put("turns", JSONArray())))
        assertNull(Interchange.parse(JSONObject().put("interchange", 1)))
        assertNull(Interchange.parse(JSONObject()))
    }

    @Test
    fun `bundle 指纹不符时拒绝`() {
        val payload = """{"interchange":1,"title":"t","turns":[]}"""
        val digest = Interchange.sha256(payload)
        assertTrue(Interchange.bundleIntact(payload, digest))
        assertFalse(Interchange.bundleIntact(payload, "deadbeef"))
        assertFalse(Interchange.bundleIntact(payload, null))
    }
}