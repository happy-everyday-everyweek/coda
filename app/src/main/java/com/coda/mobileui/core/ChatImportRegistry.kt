package com.coda.mobileui.core

/**
 * 来源适配器注册表（#734）。
 *
 * 每接一个平台不再改一次备份入口：入口按注册表逐个 probe，命中即走对应导入器，
 * 全部未命中时列出各适配器放弃的原因，而不是只说一句「不支持」。
 *
 * ## 三级探测
 *
 * 显式格式 > 内容标记 > 解析器试读，与参考实现一致。
 * 试读只看前 64KB（[HEAD_BYTES]），不为了判类型把整个大文件读进内存。
 *
 * ## 能力矩阵
 *
 * 每个适配器都要说清「这个源有什么、没有什么」。缺失是源固有有损，
 * 不是导入器缺陷——用户看到「Claude Code 有工具结果、ChatGPT 没有」
 * 才不会以为是导入坏了。
 */
internal object ChatImportRegistry {
    /** 试读字节上限。 */
    const val HEAD_BYTES = 64 * 1024

    /**
     * 一个来源适配器。
     *
     * @param markers 包内出现任一即命中（文件名或路径片段）。
     * @param headMarkers 试读文本里出现任一即命中。
     * @param capability 面向用户的能力说明：这个源能带过来什么。
     */
    data class Adapter(
        val id: String,
        val title: String,
        val capability: String,
        val markers: List<String>,
        val headMarkers: List<String>,
        val rejectReason: String,
    )

    private val adapters =
        listOf(
            Adapter(
                id = "deepseek",
                title = "DeepSeek 官网导出",
                capability = "正文与思考内容；联网搜索降级为文本行；图片暂不导入",
                markers = listOf("chat_session_index.json"),
                headMarkers = listOf("\"export_format_version\""),
                rejectReason = "没有 chat_session_index.json，也不是 v3 导出结构",
            ),
            Adapter(
                id = "chatgpt",
                title = "ChatGPT 官方导出",
                capability = "正文与时间；mapping DAG 只取主链，其余分支折叠；无工具结果",
                markers = listOf("conversations.json"),
                headMarkers = listOf("\"mapping\"", "\"current_node\""),
                rejectReason = "没有 conversations.json，也没有 mapping/current_node 结构",
            ),
            Adapter(
                id = "codex",
                title = "Codex 转录",
                capability = "正文与工具调用；encrypted_content 密文推理跳过并计数",
                markers = listOf("rollout-", ".codex/sessions/"),
                headMarkers = listOf("\"session_meta\"", "\"encrypted_content\""),
                rejectReason = "不是 rollout-*.jsonl，也没有 session_meta 事件",
            ),
            Adapter(
                id = "claude_code",
                title = "Claude Code 转录",
                capability = "正文、推理、工具调用与结果、base64 截图（截图降级为占位）",
                // 只认 .claude/projects 路径，不认裸 .jsonl：Codex 的 rollout 也是 .jsonl，
                // 用扩展名判会把 Codex 转录误认成 Claude Code。
                markers = listOf(".claude/projects/"),
                headMarkers = listOf("\"parentUuid\""),
                rejectReason = "不是 .claude/projects 下的 JSONL，也没有 parentUuid 事件链",
            ),
            Adapter(
                id = "interchange",
                title = "Interchange v1 文档 / bundle",
                capability = "按文档声明的内容块导入；bundle 需双层 sha256 校验通过",
                markers = listOf(".dshbundle.json", ".interchange.json"),
                headMarkers = listOf("\"interchange\""),
                rejectReason = "没有 interchange 版本声明，也不是 .dshbundle.json",
            ),
        )

    /** 已实现的适配器。 */
    fun all(): List<Adapter> = adapters

    /**
     * 第二批来源：只在注册表预留，本期不实现。
     *
     * 单独列出来而不是混进 [all]：用户选了却导不了比看不到更糟。
     */
    fun reserved(): List<String> =
        listOf("gemini_cli", "opencode", "kimi_cli", "cursor", "cherry_studio", "doubao")

    /**
     * 三级探测。
     *
     * 显式格式优先（用户明确指定就不猜），其次内容标记，最后试读。
     * 同一级里按注册顺序取第一个命中，顺序即优先级。
     */
    fun probe(
        explicitFormat: String?,
        entryNames: List<String>,
        head: String,
    ): Adapter? {
        explicitFormat?.trim()?.lowercase()?.let { want ->
            adapters.firstOrNull { it.id == want }?.let { return it }
        }
        adapters.firstOrNull { a -> a.markers.any { m -> entryNames.any { it.contains(m) } } }?.let { return it }
        val prefix = head.take(HEAD_BYTES)
        return adapters.firstOrNull { a -> a.headMarkers.any { prefix.contains(it) } }
    }

    /** 全部未命中时，逐个说明为什么放弃——比一句「不支持」有用得多。 */
    fun rejectReasons(
        entryNames: List<String>,
        head: String,
    ): List<String> = adapters.map { "${it.title}：${it.rejectReason}" }
}