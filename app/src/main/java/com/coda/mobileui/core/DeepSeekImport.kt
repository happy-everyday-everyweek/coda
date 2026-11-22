package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek 官网导出包（v3）的解析与转换（#733）。
 *
 * 参考 DSH 官方插件 @deepseek-ai/dsh-deepseek-chat-import（MIT）的设计要点：
 * 会话是消息树、按 current_message_id 选分支、fragment 分类映射、
 * CONTENT_FILTER 轮次整轮略去、防御性上限。这里只实现解析与转换的纯逻辑，
 * 读 zip 与写会话由调用方负责。
 *
 * ## 落地通道的实际约束
 *
 * 内核 `session/create` 已支持 `importedHistory`，但它的 schema 是严格的：
 * `source` 只接受 claudeCode / sharedContext，`messages` 每条只有
 * `{role, content, timestamp}`——**content 是纯字符串**。
 *
 * 所以思考内容、搜索记录、图片都只能降级成文本写进 content，
 * 无法作为独立的 reasoning part 或附件卡片传入。这不是本实现偷懒，
 * 是通道本身不接受结构化内容；要真正保留结构需要扩内核 schema。
 */
internal object DeepSeekImport {
    /** 整包解压上限：与 DataBackup 的量级对齐，防止高压缩比包耗尽存储。 */
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /** 单条目解压后上限。 */
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    /** 条目数上限。 */
    const val MAX_ENTRIES = 200_000

    /** 默认导入会话数上限。 */
    const val DEFAULT_SESSION_LIMIT = 1000

    /** 标题上限：按 UTF-8 字节算，不是字符数。 */
    private const val TITLE_MAX_BYTES = 80

    /** 拒绝原因：每种都要能直接展示给用户，不能只说「导入失败」。 */
    enum class Reject(
        val reason: String,
    ) {
        TOO_LARGE("导出包超过体积上限"),
        UNREADABLE_ARCHIVE("无法读取压缩包，文件可能已损坏"),
        MISSING_MANIFEST("包内缺少 manifest.json"),
        MISSING_INDEX("包内缺少 chat_session_index.json"),
        UNSUPPORTED_VERSION("导出格式版本不受支持，需要 v3"),
        NO_CONVERSATIONS("包内没有可导入的会话"),
    }

    /** fragment 映射后的类别。 */
    enum class PartKind {
        TEXT,
        REASONING,
    }

    data class Part(
        val kind: PartKind,
        val text: String,
    )

    /** 转换后的消息，字段与内核 importedHistory.messages 一一对应。 */
    data class ImportMessage(
        val role: String,
        val content: String,
        val timestamp: Long?,
    )

    /**
     * 按 current_message_id 回溯当前分支，返回从根到该消息的有序链。
     *
     * 缺失时取 message_id 最大的那条作结尾（导出约定：id 递增）。
     * 用 seen 集合防环：真实导出里出现过父子互指，不设上限会死循环。
     */
    fun branchChain(
        messages: List<JSONObject>,
        currentMessageId: Int?,
    ): List<JSONObject> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.optInt("message_id") }
        val tail =
            if (currentMessageId != null && byId.containsKey(currentMessageId)) {
                byId[currentMessageId]
            } else {
                messages.maxByOrNull { it.optInt("message_id") }
            } ?: return emptyList()
        val reversed = mutableListOf<JSONObject>()
        val seen = mutableSetOf<Int>()
        var cursor: JSONObject? = tail
        while (cursor != null) {
            val id = cursor.optInt("message_id")
            if (!seen.add(id)) break
            reversed.add(cursor)
            val parent = if (cursor.isNull("parent_id")) null else cursor.optInt("parent_id", -1)
            cursor = if (parent == null || parent < 0) null else byId[parent]
        }
        return reversed.reversed()
    }

    /**
     * fragment 到内容块的映射。
     *
     * THINK 归 REASONING，RESPONSE/TEXT 归正文；搜索与抓取降级为文本摘要行
     * （首期不做完整工具卡片）。未知类型按正文处理，不静默丢弃内容。
     */
    fun partsOf(message: JSONObject): List<Part> {
        val arr = message.optJSONArray("fragments") ?: return emptyList()
        val out = mutableListOf<Part>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val content = f.optString("content")
            if (content.isBlank()) continue
            when (f.optString("type").uppercase()) {
                "THINK", "REASONING" -> out.add(Part(PartKind.REASONING, content))
                "SEARCH", "WEB_SEARCH" -> out.add(Part(PartKind.TEXT, "［联网搜索］$content"))
                "READ_LINK", "WEB_FETCH", "OPEN_URL" -> out.add(Part(PartKind.TEXT, "［打开网页］$content"))
                else -> out.add(Part(PartKind.TEXT, content))
            }
        }
        return out
    }

    /** 被内容过滤撤回的回答：整轮（含提问）都要略去。 */
    fun isWithdrawn(message: JSONObject): Boolean =
        message.optString("status").uppercase() == "CONTENT_FILTER"

    /** 非 FINISHED 的回答给一句可展示的失败说明；正常回答返回 null。 */
    fun failureNote(message: JSONObject): String? {
        val status = message.optString("status").uppercase()
        if (status.isEmpty() || status == "FINISHED" || status == "CONTENT_FILTER") return null
        if (message.optString("role").uppercase() != "ASSISTANT") return null
        return "［此回答未正常完成：$status］"
    }

    /**
     * 标题截断到 80 UTF-8 字节。
     *
     * 按字符逐个累加字节数，不能直接 cut 字节数组——那样会截出半个汉字，
     * 解码成乱码。空白标题给一个占位名，避免会话列表出现空白行。
     */
    fun trimTitle(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return "未命名会话"
        val sb = StringBuilder()
        var bytes = 0
        for (ch in s) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + n > TITLE_MAX_BYTES) break
            sb.append(ch)
            bytes += n
        }
        return sb.toString().ifEmpty { "未命名会话" }
    }

    /** 图片扩展名判定：只有这几种能作为附件展示，其余生成占位卡片。 */
    fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif")
    }

    fun supportedManifest(manifest: JSONObject): Boolean = manifest.optInt("export_format_version", 0) == 3

    /** 是不是 DeepSeek 导出包：要有 v3 manifest 与索引，且不是 Coda 自家包。 */
    fun looksLikeDeepSeek(
        entryNames: List<String>,
        manifest: JSONObject,
    ): Boolean =
        manifest.optString("app") != "coda" &&
            supportedManifest(manifest) &&
            entryNames.contains("chat_session_index.json")

    /** 按 updated_at 倒序取最近 [limit] 个会话。 */
    fun pickRecent(
        index: JSONArray,
        limit: Int,
    ): List<JSONObject> {
        val all = (0 until index.length()).mapNotNull { index.optJSONObject(it) }
        return all.sortedByDescending { it.optLong("updated_at", 0L) }.take(limit.coerceAtLeast(0))
    }

    /** 时间戳单调不减（相等接受）。导入时要保证会话时间不倒退。 */
    fun monotonic(timestamps: List<Long>): Boolean = timestamps.zipWithNext().all { (a, b) -> a <= b }

    fun withinLimits(
        entryBytes: Long,
        totalBytes: Long,
        entries: Int,
    ): Boolean = entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_BYTES && entries <= MAX_ENTRIES

    /**
     * 把分支链转成内核可接受的消息序列。
     *
     * 三处降级都是通道约束导致的：思考内容并进正文（content 只接受字符串）、
     * 撤回轮次连同提问一起剔除、非 FINISHED 回答保留提问并追加失败说明。
     * 空正文的消息直接丢弃——内核 schema 要求 messages 非空且每条有内容。
     */
    fun toImportMessages(chain: List<JSONObject>): List<ImportMessage> {
        val out = mutableListOf<ImportMessage>()
        chain.forEach { m ->
            val role = if (m.optString("role").uppercase() == "USER") "user" else "assistant"
            if (isWithdrawn(m)) {
                // 撤回的回答连同它前面那条提问一起删掉：只留提问会让上下文里
                // 出现一个没有回答的敏感问题，下一轮模型还会接着答它。
                if (out.isNotEmpty() && out.last().role == "user") out.removeAt(out.size - 1)
                return@forEach
            }
            val parts = partsOf(m)
            val body =
                parts.joinToString("\n\n") { p ->
                    if (p.kind == PartKind.REASONING) "［思考］${p.text}" else p.text
                }
            val note = failureNote(m)
            val content = listOf(body, note.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
            if (content.isBlank()) return@forEach
            val ts = m.optLong("inserted_at", 0L).takeIf { it > 0 }
            out.add(ImportMessage(role = role, content = content, timestamp = ts))
        }
        return out
    }

    /**
     * 组装 `session/create` 的 importedHistory 参数。
     *
     * source 固定用 claudeCode：内核的判别联合只接受 claudeCode 与 sharedContext，
     * 没有 deepseek 这一项。两者的消息结构完全相同（role/content/timestamp），
     * 借用 claudeCode 只是让 schema 通过，不代表内容来自 Claude。
     * 这一点必须记在案：将来内核加了 deepseek source 应当换过来。
     */
    fun importedHistory(
        title: String,
        createdAt: Long?,
        updatedAt: Long?,
        messages: List<ImportMessage>,
    ): JSONObject {
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject().put("role", m.role).put("content", m.content)
            m.timestamp?.let { o.put("timestamp", it) }
            arr.put(o)
        }
        val o =
            JSONObject()
                .put("source", "claudeCode")
                .put("title", trimTitle(title))
                .put("messages", arr)
        createdAt?.let { o.put("createdAt", it) }
        updatedAt?.let { o.put("updatedAt", it) }
        return o
    }
}