package com.coda.mobileui.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 通用会话交换结构（#734），对齐 Interchange v1 的 turns IR。
 *
 * 参考 Nwflower/dsh-chat-import（MIT，325 stars，DSH 插件精选列表收录）的协议设计：
 * 一条用户提问一个 turn，一条助手消息（含工具调用与结果）一个 step，
 * 内容块分 text / reasoning / image / tool-call / tool-result。
 * 长尾来源经外部转换成这个结构后即可导入，不必逐个内置转换器。
 *
 * ## 降级必须显式
 *
 * 参考实现里最重要的一条约定：tool-result-missing、reasoning-encrypted、
 * attachment-skipped、branch-collapsed 全部计数并随结果报告，绝不静默。
 * 这里用 [Degradations] 承载，转换时逐项累加。
 *
 * ## 落地通道的约束
 *
 * 内核 importedHistory 的 content 是纯字符串，所以工具调用、推理、图片
 * 都只能转成文本行或占位。图片即使有字节也进不去——这不是取舍，是通道限制。
 */
internal object Interchange {
    /** 内容块。 */
    sealed class Block {
        data class Text(
            val text: String,
        ) : Block()

        data class Reasoning(
            val text: String,
        ) : Block()

        /** Codex 的密文推理：拿不到明文，只能占位并计数。 */
        data object EncryptedReasoning : Block()

        data class Image(
            val name: String,
            val bytesAvailable: Boolean,
        ) : Block()

        data class ToolCall(
            val name: String,
            val args: String,
        ) : Block()

        data class ToolResult(
            val name: String,
            val output: String,
            /** 找不到对应的调用：孤儿结果，仍导入但要计数。 */
            val orphan: Boolean = false,
        ) : Block()
    }

    /** 一条助手消息（可含多个内容块）。 */
    data class Step(
        val blocks: List<Block>,
    )

    /** 一轮：一条用户提问 + 若干助手 step。 */
    data class Turn(
        val userText: String,
        val steps: List<Step>,
        val timestamp: Long? = null,
    )

    data class Document(
        val title: String,
        val turns: List<Turn>,
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
        /** 来源标识，用于重复导入去重判断。 */
        val sourceId: String? = null,
    )

    /** 降级计数。每一类都要能单独展示，让用户知道丢了什么。 */
    data class Degradations(
        var toolResultMissing: Int = 0,
        var reasoningEncrypted: Int = 0,
        var attachmentSkipped: Int = 0,
        var branchCollapsed: Int = 0,
    ) {
        fun summary(): String =
            listOfNotNull(
                toolResultMissing.takeIf { it > 0 }?.let { "$it 个工具结果找不到对应调用" },
                reasoningEncrypted.takeIf { it > 0 }?.let { "$it 段推理为加密内容，已跳过" },
                attachmentSkipped.takeIf { it > 0 }?.let { "$it 个图片/附件未能导入，仅保留名称" },
                branchCollapsed.takeIf { it > 0 }?.let { "$it 处分支已折叠为主链" },
            ).joinToString("\n")
    }

    /** 0 轮的文档不产出空壳会话。 */
    fun usable(doc: Document): Boolean =
        doc.turns.any { t ->
            t.userText.isNotBlank() || t.steps.any { s -> s.blocks.isNotEmpty() }
        }

    /**
     * 一个 step 转成可读文本。
     *
     * 推理带「思考」标记，工具调用与结果各占一行，图片只留名称。
     * 每处降级都往 [d] 里记一笔。
     */
    fun stepText(
        step: Step,
        d: Degradations = Degradations(),
    ): String =
        step.blocks.mapNotNull { b ->
            when (b) {
                is Block.Text -> b.text.takeIf { it.isNotBlank() }
                is Block.Reasoning -> "［思考］${b.text}".takeIf { b.text.isNotBlank() }
                is Block.EncryptedReasoning -> {
                    d.reasoningEncrypted++
                    "［推理内容为加密数据，无法导入］"
                }

                is Block.Image -> {
                    d.attachmentSkipped++
                    "［附件：${b.name}（未导入）］"
                }

                is Block.ToolCall -> "［调用工具 ${b.name}］${b.args}"
                is Block.ToolResult -> {
                    if (b.orphan) d.toolResultMissing++
                    "［工具 ${b.name} 返回］${b.output}"
                }
            }
        }.joinToString("\n\n")

    /**
     * 文档转成内核消息序列。
     *
     * 一个 turn 的多个 step 合成一条助手消息：内核的 importedHistory 是
     * 线性 user/assistant 序列，没有 step 概念，拆成多条会破坏角色交替。
     *
     * 时间戳沿用源值，但**强制单调不减**：源里出现过倒退（分支合并、
     * 时钟调整），原样写入会让会话列表排序错乱。
     */
    fun toMessages(
        doc: Document,
        d: Degradations = Degradations(),
    ): List<DeepSeekImport.ImportMessage> {
        val out = mutableListOf<DeepSeekImport.ImportMessage>()
        var lastTs = 0L
        fun nextTs(raw: Long?): Long? {
            if (raw == null || raw <= 0) return null
            lastTs = maxOf(lastTs, raw)
            return lastTs
        }
        doc.turns.forEach { t ->
            val ts = nextTs(t.timestamp)
            if (t.userText.isNotBlank()) {
                out.add(DeepSeekImport.ImportMessage("user", t.userText, ts))
            }
            val body = t.steps.map { stepText(it, d) }.filter { it.isNotBlank() }.joinToString("\n\n")
            if (body.isNotBlank()) {
                out.add(DeepSeekImport.ImportMessage("assistant", body, ts))
            }
        }
        return out
    }

    /**
     * 解析 Interchange v1 文档。
     *
     * 版本不符或缺 turns 一律返回 null：宁可不导，也不猜一个可能错的结构。
     */
    fun parse(json: JSONObject): Document? {
        if (json.optInt("interchange", 0) != 1) return null
        val turnsArr = json.optJSONArray("turns") ?: return null
        val turns = mutableListOf<Turn>()
        for (i in 0 until turnsArr.length()) {
            val t = turnsArr.optJSONObject(i) ?: continue
            val steps = mutableListOf<Step>()
            val stepsArr = t.optJSONArray("steps")
            if (stepsArr != null) {
                for (j in 0 until stepsArr.length()) {
                    val s = stepsArr.optJSONObject(j) ?: continue
                    val blocks = mutableListOf<Block>()
                    val blocksArr = s.optJSONArray("blocks")
                    if (blocksArr != null) {
                        for (k in 0 until blocksArr.length()) {
                            parseBlock(blocksArr.optJSONObject(k))?.let { blocks.add(it) }
                        }
                    }
                    steps.add(Step(blocks))
                }
            }
            turns.add(
                Turn(
                    userText = t.optString("user"),
                    steps = steps,
                    timestamp = t.optLong("timestamp", 0L).takeIf { it > 0 },
                ),
            )
        }
        return Document(
            title = DeepSeekImport.trimTitle(json.optString("title")),
            turns = turns,
            createdAt = json.optLong("createdAt", 0L).takeIf { it > 0 },
            updatedAt = json.optLong("updatedAt", 0L).takeIf { it > 0 },
            sourceId = json.optString("sourceId").takeIf { it.isNotEmpty() },
        )
    }

    private fun parseBlock(o: JSONObject?): Block? {
        if (o == null) return null
        return when (o.optString("type")) {
            "text" -> Block.Text(o.optString("text"))
            "reasoning" -> Block.Reasoning(o.optString("text"))
            "reasoning-encrypted" -> Block.EncryptedReasoning
            "image" -> Block.Image(o.optString("name", "image"), o.optBoolean("bytesAvailable", false))
            "tool-call" -> Block.ToolCall(o.optString("name"), o.optString("args"))
            "tool-result" -> Block.ToolResult(o.optString("name"), o.optString("output"), o.optBoolean("orphan", false))
            else -> null
        }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * bundle 指纹校验。
     *
     * 便携 bundle 带会话级与文件级双层 sha256，指纹不符必须拒绝导入——
     * 半截或被改写的 bundle 导进去就是一段错误的历史，比不导更糟。
     */
    fun bundleIntact(
        payload: String,
        expectedSha256: String?,
    ): Boolean {
        if (expectedSha256.isNullOrBlank()) return false
        return sha256(payload).equals(expectedSha256, ignoreCase = true)
    }
}