package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 各来源到 Interchange IR 的转换器（#734 第一批）。
 *
 * 每个转换器只做「源格式 → turns IR」这一段，落地统一走
 * [Interchange.toMessages] 与内核 importedHistory，不各写一套。
 *
 * 能力差异是**源固有的**，不是转换器缺陷：ChatGPT 导出没有工具结果，
 * Codex 的推理是密文，Claude Code 的截图是 base64。这些都按降级规则计数。
 */
internal object ChatSourceParsers {
    /**
     * ChatGPT 官方导出：mapping 是 DAG，按 current_node 回溯主链。
     *
     * 其余分支折叠并计数——用户在网页上切换过的回答分支不会全部导进来，
     * 否则同一段对话会出现多个互相矛盾的回答。
     */
    fun chatgpt(
        conversation: JSONObject,
        d: Interchange.Degradations = Interchange.Degradations(),
    ): Interchange.Document {
        val mapping = conversation.optJSONObject("mapping") ?: JSONObject()
        val keys = mapping.keys().asSequence().toList()
        val byId = keys.associateWith { mapping.optJSONObject(it) }
        val current = conversation.optString("current_node").takeIf { it.isNotEmpty() }
        val tailId =
            current ?: keys.maxByOrNull { id ->
                byId[id]?.optJSONObject("message")?.optDouble("create_time", 0.0) ?: 0.0
            }
        val chain = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var cursor = tailId
        while (cursor != null && seen.add(cursor)) {
            val node = byId[cursor] ?: break
            node.optJSONObject("message")?.let { chain.add(it) }
            cursor = node.optString("parent").takeIf { it.isNotEmpty() }
        }
        chain.reverse()
        // 分支折叠计数：DAG 里不在主链上的节点数。
        val offChain = keys.count { id -> id !in seen && byId[id]?.optJSONObject("message") != null }
        if (offChain > 0) d.branchCollapsed += offChain

        val turns = mutableListOf<Interchange.Turn>()
        var pendingUser = ""
        var pendingTs: Long? = null
        var steps = mutableListOf<Interchange.Step>()
        fun flush() {
            if (pendingUser.isNotBlank() || steps.isNotEmpty()) {
                turns.add(Interchange.Turn(pendingUser, steps.toList(), pendingTs))
            }
            pendingUser = ""
            steps = mutableListOf()
        }
        chain.forEach { m ->
            val role = m.optJSONObject("author")?.optString("role") ?: ""
            val parts = m.optJSONObject("content")?.optJSONArray("parts")
            val text =
                (0 until (parts?.length() ?: 0))
                    .mapNotNull { parts!!.optString(it).takeIf { s -> s.isNotBlank() } }
                    .joinToString("\n\n")
            val ts = (m.optDouble("create_time", 0.0) * 1000).toLong().takeIf { it > 0 }
            when (role) {
                "user" -> {
                    flush()
                    pendingUser = text
                    pendingTs = ts
                }

                "assistant" -> if (text.isNotBlank()) steps.add(Interchange.Step(listOf(Interchange.Block.Text(text))))
                // tool 角色的消息在 ChatGPT 导出里通常没有可读输出，跳过不计数：
                // 它不是「丢了工具结果」，而是这个源本来就不导出工具结果。
                else -> Unit
            }
        }
        flush()
        return Interchange.Document(
            title = DeepSeekImport.trimTitle(conversation.optString("title")),
            turns = turns,
            createdAt = (conversation.optDouble("create_time", 0.0) * 1000).toLong().takeIf { it > 0 },
            updatedAt = (conversation.optDouble("update_time", 0.0) * 1000).toLong().takeIf { it > 0 },
            sourceId = conversation.optString("id").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Claude Code 转录：逐行事件（JSONL）。
     *
     * 含 reasoning、工具调用与结果、base64 截图。截图降级为占位并计数——
     * 通道不接受附件，即使字节就在事件里也传不进去。
     */
    fun claudeCode(
        events: List<JSONObject>,
        d: Interchange.Degradations = Interchange.Degradations(),
    ): Interchange.Document {
        val turns = mutableListOf<Interchange.Turn>()
        var pendingUser = ""
        var pendingTs: Long? = null
        var steps = mutableListOf<Interchange.Step>()
        val callNames = mutableMapOf<String, String>()
        fun flush() {
            if (pendingUser.isNotBlank() || steps.isNotEmpty()) {
                turns.add(Interchange.Turn(pendingUser, steps.toList(), pendingTs))
            }
            pendingUser = ""
            steps = mutableListOf()
        }
        events.forEach { e ->
            val msg = e.optJSONObject("message") ?: return@forEach
            val role = msg.optString("role")
            val ts = parseIso(e.optString("timestamp"))
            val content = msg.optJSONArray("content")
            val blocks = mutableListOf<Interchange.Block>()
            if (content == null) {
                msg.optString("content").takeIf { it.isNotBlank() }?.let { blocks.add(Interchange.Block.Text(it)) }
            } else {
                for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i) ?: continue
                    when (c.optString("type")) {
                        "text" -> c.optString("text").takeIf { it.isNotBlank() }?.let { blocks.add(Interchange.Block.Text(it)) }
                        "thinking" ->
                            c.optString("thinking").takeIf { it.isNotBlank() }
                                ?.let { blocks.add(Interchange.Block.Reasoning(it)) }

                        "tool_use" -> {
                            val id = c.optString("id")
                            val name = c.optString("name")
                            if (id.isNotEmpty()) callNames[id] = name
                            blocks.add(Interchange.Block.ToolCall(name, c.opt("input")?.toString() ?: ""))
                        }

                        "tool_result" -> {
                            val id = c.optString("tool_use_id")
                            val name = callNames[id]
                            blocks.add(
                                Interchange.Block.ToolResult(
                                    name = name ?: "未知工具",
                                    output = c.opt("content")?.toString() ?: "",
                                    orphan = name == null,
                                ),
                            )
                        }

                        "image" -> blocks.add(Interchange.Block.Image("截图 ${i + 1}", bytesAvailable = true))
                        else -> Unit
                    }
                }
            }
            if (blocks.isEmpty()) return@forEach
            if (role == "user") {
                // 用户消息里若只有 tool_result，它属于上一轮的助手 step，不另起一轮。
                val onlyToolResults = blocks.all { it is Interchange.Block.ToolResult }
                if (onlyToolResults) {
                    steps.add(Interchange.Step(blocks))
                } else {
                    flush()
                    pendingUser = blocks.filterIsInstance<Interchange.Block.Text>().joinToString("\n\n") { it.text }
                    pendingTs = ts
                }
            } else {
                steps.add(Interchange.Step(blocks))
            }
        }
        flush()
        val first = events.firstOrNull()
        return Interchange.Document(
            title = DeepSeekImport.trimTitle(first?.optString("summary") ?: "Claude Code 会话"),
            turns = turns,
            sourceId = first?.optString("sessionId")?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Codex 转录：rollout-*.jsonl。
     *
     * encrypted_content 是密文推理，拿不到明文，占位并计数；summary 照常导入。
     */
    fun codex(
        events: List<JSONObject>,
        d: Interchange.Degradations = Interchange.Degradations(),
    ): Interchange.Document {
        val turns = mutableListOf<Interchange.Turn>()
        var pendingUser = ""
        var pendingTs: Long? = null
        var steps = mutableListOf<Interchange.Step>()
        var title: String? = null
        fun flush() {
            if (pendingUser.isNotBlank() || steps.isNotEmpty()) {
                turns.add(Interchange.Turn(pendingUser, steps.toList(), pendingTs))
            }
            pendingUser = ""
            steps = mutableListOf()
        }
        events.forEach { e ->
            val payload = e.optJSONObject("payload") ?: e
            val ts = parseIso(e.optString("timestamp"))
            when (e.optString("type")) {
                "session_meta" -> title = payload.optString("thread_name").takeIf { it.isNotEmpty() }
                "response_item", "event_msg" -> {
                    val role = payload.optString("role")
                    val blocks = mutableListOf<Interchange.Block>()
                    val content = payload.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val c = content.optJSONObject(i) ?: continue
                            when {
                                c.has("encrypted_content") -> blocks.add(Interchange.Block.EncryptedReasoning)
                                c.optString("type") == "reasoning" ->
                                    c.optString("text").takeIf { it.isNotBlank() }
                                        ?.let { blocks.add(Interchange.Block.Reasoning(it)) }

                                else ->
                                    c.optString("text").takeIf { it.isNotBlank() }
                                        ?.let { blocks.add(Interchange.Block.Text(it)) }
                            }
                        }
                    }
                    payload.optString("text").takeIf { it.isNotBlank() }?.let { blocks.add(Interchange.Block.Text(it)) }
                    if (blocks.isEmpty()) return@forEach
                    if (role == "user") {
                        flush()
                        pendingUser = blocks.filterIsInstance<Interchange.Block.Text>().joinToString("\n\n") { it.text }
                        pendingTs = ts
                    } else {
                        steps.add(Interchange.Step(blocks))
                    }
                }

                else -> Unit
            }
        }
        flush()
        return Interchange.Document(
            title = DeepSeekImport.trimTitle(title ?: "Codex 会话"),
            turns = turns,
        )
    }

    /**
     * ISO8601 时间戳转毫秒。
     *
     * 解析失败返回 null 而不是 0：0 会被当成 1970 年写进会话时间，
     * 让导入的会话排在所有真实会话之前。
     */
    internal fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            java.time.Instant.parse(text).toEpochMilli()
        }.getOrNull()
    }

    /** 把 JSONL 文本逐行解析成事件；坏行跳过而不是让整份失败。 */
    fun jsonlEvents(text: String): List<JSONObject> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .toList()

    /** ChatGPT 的 conversations.json 是会话数组。 */
    fun conversations(text: String): List<JSONObject> =
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
}