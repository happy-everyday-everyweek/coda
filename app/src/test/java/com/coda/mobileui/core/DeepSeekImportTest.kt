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
 * DeepSeek 官网导出包的解析（#733）。
 *
 * 会话是消息树而不是线性列表：必须按 current_message_id 回溯出当前分支，
 * 否则会把用户切换过的其他分支也导进来，出现重复问答。
 * fragment 到正文的映射、CONTENT_FILTER 轮次剔除、标题截断都是纯逻辑，能直接钉住。
 */
class DeepSeekImportTest {
    private fun msg(
        id: Int,
        parent: Int?,
        role: String,
        vararg fragments: Pair<String, String>,
    ): JSONObject {
        val arr = JSONArray()
        fragments.forEach { (type, content) ->
            arr.put(JSONObject().put("type", type).put("content", content))
        }
        return JSONObject()
            .put("message_id", id)
            .put("parent_id", parent ?: JSONObject.NULL)
            .put("role", role)
            .put("fragments", arr)
            .put("status", "FINISHED")
    }

    @Test
    fun `按 current_message_id 回溯出当前分支`() {
        // 树：1(user) → 2(assistant) → 3(user) → 4(assistant)
        //                 ↘ 5(assistant) 分支未被选中
        val messages =
            listOf(
                msg(1, null, "USER", "TEXT" to "问题一"),
                msg(2, 1, "ASSISTANT", "RESPONSE" to "回答一"),
                msg(5, 1, "ASSISTANT", "RESPONSE" to "另一个分支"),
                msg(3, 2, "USER", "TEXT" to "问题二"),
                msg(4, 3, "ASSISTANT", "RESPONSE" to "回答二"),
            )
        val chain = DeepSeekImport.branchChain(messages, currentMessageId = 4)
        assertEquals(listOf(1, 2, 3, 4), chain.map { it.optInt("message_id") })
    }

    @Test
    fun `current_message_id 缺失时取 id 最大的消息结尾`() {
        val messages =
            listOf(
                msg(1, null, "USER", "TEXT" to "a"),
                msg(2, 1, "ASSISTANT", "RESPONSE" to "b"),
                msg(3, 2, "USER", "TEXT" to "c"),
            )
        val chain = DeepSeekImport.branchChain(messages, currentMessageId = null)
        assertEquals(listOf(1, 2, 3), chain.map { it.optInt("message_id") })
    }

    @Test
    fun `父链断裂时只取能追溯到的部分而不是抛异常`() {
        val messages =
            listOf(
                msg(1, null, "USER", "TEXT" to "a"),
                msg(3, 99, "ASSISTANT", "RESPONSE" to "c"),
            )
        val chain = DeepSeekImport.branchChain(messages, currentMessageId = 3)
        assertEquals(listOf(3), chain.map { it.optInt("message_id") })
    }

    @Test
    fun `父子成环时不死循环`() {
        val messages =
            listOf(
                msg(1, 2, "USER", "TEXT" to "a"),
                msg(2, 1, "ASSISTANT", "RESPONSE" to "b"),
            )
        val chain = DeepSeekImport.branchChain(messages, currentMessageId = 2)
        assertTrue(chain.size <= 2)
    }

    @Test
    fun `空消息列表返回空链`() {
        assertTrue(DeepSeekImport.branchChain(emptyList(), null).isEmpty())
    }

    @Test
    fun `THINK 与 RESPONSE 分别映射为思考与正文`() {
        val m = msg(1, null, "ASSISTANT", "THINK" to "想一下", "RESPONSE" to "答案")
        val parts = DeepSeekImport.partsOf(m)
        assertEquals(2, parts.size)
        assertEquals(DeepSeekImport.PartKind.REASONING, parts[0].kind)
        assertEquals("想一下", parts[0].text)
        assertEquals(DeepSeekImport.PartKind.TEXT, parts[1].kind)
        assertEquals("答案", parts[1].text)
    }

    @Test
    fun `搜索与抓取降级为文本摘要行`() {
        val m =
            msg(
                1,
                null,
                "ASSISTANT",
                "SEARCH" to "关键词",
                "READ_LINK" to "https://example.com",
            )
        val parts = DeepSeekImport.partsOf(m)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.kind == DeepSeekImport.PartKind.TEXT })
        assertTrue(parts[0].text.contains("关键词"))
        assertTrue(parts[1].text.contains("https://example.com"))
    }

    @Test
    fun `CONTENT_FILTER 撤回的整轮略去`() {
        val filtered =
            JSONObject()
                .put("message_id", 2)
                .put("parent_id", 1)
                .put("role", "ASSISTANT")
                .put("status", "CONTENT_FILTER")
                .put("fragments", JSONArray().put(JSONObject().put("type", "RESPONSE").put("content", "被撤回")))
        assertTrue(DeepSeekImport.isWithdrawn(filtered))
        val normal = msg(2, 1, "ASSISTANT", "RESPONSE" to "正常")
        assertFalse(DeepSeekImport.isWithdrawn(normal))
    }

    @Test
    fun `非 FINISHED 回答保留提问但标注失败原因`() {
        val failed =
            JSONObject()
                .put("message_id", 2)
                .put("parent_id", 1)
                .put("role", "ASSISTANT")
                .put("status", "FAILED")
                .put("fragments", JSONArray())
        val note = DeepSeekImport.failureNote(failed)
        assertNotNull(note)
        assertTrue(note!!.isNotBlank())
        assertNull(DeepSeekImport.failureNote(msg(2, 1, "ASSISTANT", "RESPONSE" to "ok")))
    }

    @Test
    fun `标题按 80 UTF-8 字节截断而不是按字符数`() {
        val long = "中".repeat(50) // 每个 3 字节，共 150 字节
        val cut = DeepSeekImport.trimTitle(long)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 80)
        // 不能截出半个字符：截断后仍是合法 UTF-8 且能还原成完整汉字。
        assertTrue(cut.all { it == '中' })
        assertEquals(26, cut.length) // 26*3=78 ≤ 80
    }

    @Test
    fun `短标题与空标题的处理`() {
        assertEquals("短标题", DeepSeekImport.trimTitle("短标题"))
        assertEquals("未命名会话", DeepSeekImport.trimTitle(""))
        assertEquals("未命名会话", DeepSeekImport.trimTitle("   "))
    }

    @Test
    fun `图片扩展名判定`() {
        assertTrue(DeepSeekImport.isImage("a.png"))
        assertTrue(DeepSeekImport.isImage("a.JPG"))
        assertTrue(DeepSeekImport.isImage("a.jpeg"))
        assertTrue(DeepSeekImport.isImage("a.webp"))
        assertTrue(DeepSeekImport.isImage("a.gif"))
        assertFalse(DeepSeekImport.isImage("a.pdf"))
        assertFalse(DeepSeekImport.isImage("a.txt"))
        assertFalse(DeepSeekImport.isImage("noext"))
    }

    @Test
    fun `manifest 版本必须是 3`() {
        assertTrue(DeepSeekImport.supportedManifest(JSONObject().put("export_format_version", 3)))
        assertFalse(DeepSeekImport.supportedManifest(JSONObject().put("export_format_version", 2)))
        assertFalse(DeepSeekImport.supportedManifest(JSONObject()))
    }

    @Test
    fun `识别 DeepSeek 导出结构`() {
        val names = listOf("manifest.json", "chat_session_index.json", "session_1.json")
        assertTrue(DeepSeekImport.looksLikeDeepSeek(names, JSONObject().put("export_format_version", 3)))
        // 缺索引不算
        assertFalse(DeepSeekImport.looksLikeDeepSeek(listOf("manifest.json"), JSONObject().put("export_format_version", 3)))
        // Coda 自家包不算
        assertFalse(DeepSeekImport.looksLikeDeepSeek(names, JSONObject().put("app", "coda")))
    }

    @Test
    fun `按 updated_at 取最近 N 个且时间单调不减`() {
        val index =
            JSONArray()
                .put(JSONObject().put("id", "a").put("updated_at", 100L))
                .put(JSONObject().put("id", "b").put("updated_at", 300L))
                .put(JSONObject().put("id", "c").put("updated_at", 200L))
        val picked = DeepSeekImport.pickRecent(index, limit = 2)
        assertEquals(listOf("b", "c"), picked.map { it.optString("id") })
        // 时间戳单调不减：相等也接受，倒序不接受。
        assertTrue(DeepSeekImport.monotonic(listOf(1L, 2L, 2L, 3L)))
        assertFalse(DeepSeekImport.monotonic(listOf(1L, 3L, 2L)))
        assertTrue(DeepSeekImport.monotonic(emptyList()))
    }

    @Test
    fun `上限判定与 DataBackup 常量对齐`() {
        assertTrue(DeepSeekImport.withinLimits(entryBytes = 1024, totalBytes = 1024, entries = 10))
        assertFalse(DeepSeekImport.withinLimits(entryBytes = DeepSeekImport.MAX_ENTRY_BYTES + 1, totalBytes = 0, entries = 1))
        assertFalse(DeepSeekImport.withinLimits(entryBytes = 0, totalBytes = DeepSeekImport.MAX_TOTAL_BYTES + 1, entries = 1))
        assertFalse(DeepSeekImport.withinLimits(entryBytes = 0, totalBytes = 0, entries = DeepSeekImport.MAX_ENTRIES + 1))
    }

    @Test
    fun `默认会话上限 1000`() {
        assertEquals(1000, DeepSeekImport.DEFAULT_SESSION_LIMIT)
    }

    @Test
    fun `错误分类明确可展示`() {
        DeepSeekImport.Reject.entries.forEach {
            assertTrue("${it.name} 缺原因文案", it.reason.isNotBlank())
        }
    }

    @Test
    fun `转换出的消息序列角色交替且不含空正文`() {
        val messages =
            listOf(
                msg(1, null, "USER", "TEXT" to "问题"),
                msg(2, 1, "ASSISTANT", "THINK" to "思考", "RESPONSE" to "答案"),
            )
        val chain = DeepSeekImport.branchChain(messages, 2)
        val out = DeepSeekImport.toImportMessages(chain)
        assertEquals(2, out.size)
        assertEquals("user", out[0].role)
        assertEquals("assistant", out[1].role)
        assertTrue(out.all { it.content.isNotBlank() })
        // 思考内容并入正文（内核 importedHistory 只接受纯文本 content）。
        assertTrue(out[1].content.contains("思考"))
        assertTrue(out[1].content.contains("答案"))
    }

    @Test
    fun `撤回轮次的提问与回答一起被剔除`() {
        val filtered =
            JSONObject()
                .put("message_id", 2)
                .put("parent_id", 1)
                .put("role", "ASSISTANT")
                .put("status", "CONTENT_FILTER")
                .put("fragments", JSONArray())
        val messages = listOf(msg(1, null, "USER", "TEXT" to "敏感问题"), filtered)
        val out = DeepSeekImport.toImportMessages(DeepSeekImport.branchChain(messages, 2))
        assertTrue(out.isEmpty())
    }
}