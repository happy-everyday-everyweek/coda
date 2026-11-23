package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject

/** 会话摘要（session/list 与快照通用）。 */
data class ZSessionInfo(
    val id: String,
    var title: String,
    var status: String,
    var mode: String,
    var updatedAt: Long,
    var workspacePath: String?,
    var titleSource: String?,
)

/** 模型档位。 */
data class ZModelLevel(
    val value: String,
    val label: String,
)

/** 可用模型。 */
data class ZModelOption(
    val providerId: String,
    val modelId: String,
    var label: String,
    var providerLabel: String?,
    var contextWindow: Int,
    var levels: List<ZModelLevel>,
    var defaultLevel: String?,
) {
    val key: String get() = "$providerId/$modelId"
}

/** 斜杠命令。 */
data class ZSlashCommand(
    val name: String,
    val description: String,
    val inputHint: String?,
)

/** 消息部件。 */
sealed class ZPart {
    class Text(
        var text: String,
    ) : ZPart()

    class Reasoning(
        var text: String,
    ) : ZPart()

    class ToolPart(
        val callId: String,
        var tool: String,
        var status: String,
        var input: String?,
        var output: String?,
        var title: String?,
        var durationMs: Long?,
        /** 所属父 Agent 工具调用 id：子代理内部产生的工具调用带此字段（协议 parentToolUseId）。 */
        var parentToolUseId: String? = null,
    ) : ZPart()

    /** 附件部件（用户消息）：文件名 / MIME / 内容引用（本地路径或 zcode-artifact://）/ 大小与图片尺寸。 */
    class FilePart(
        val filename: String,
        val mime: String,
        val url: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val previewReady: Boolean,
    ) : ZPart()
}

/** 消息。 */
class ZMessage(
    val id: String,
    val role: String,
) {
    var createdAt: Long = 0
    var completedAt: Long = 0
    val parts = mutableListOf<ZPart>()

    fun text(): String = parts.filterIsInstance<ZPart.Text>().joinToString("") { it.text }
}

/** 协议 JSON 解析（兼容两代字段命名）。 */
object ZParse {
    /** OCR #819：合成 id 警告限频，流式刷新不能每条消息每轮都刷一行日志。 */
    @Volatile
    private var lastSyntheticWarnAt = 0L

    fun parseMessages(result: JSONObject): List<ZMessage> {
        val arr = result.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<ZMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val info = m.optJSONObject("info") ?: JSONObject()
            val rawId = info.optString("id").ifEmpty { info.optString("messageId") }
            // 缺 id 的消息不再整条丢弃（内容会凭空消失）：用不可变字段派生稳定合成 id。
            // OCR #819：种子不能含 parts 序列化——流式 delta 每轮都变，id 跟着变
            // 会让下游把每次刷新当成新消息。role + createdAt + 序号在流式期间不变。
            val createdAt = info.optJSONObject("time")?.optLong("created", 0L) ?: 0L
            val id =
                rawId.ifEmpty {
                    val seed = info.optString("role") + "|" + createdAt + "|" + i
                    "synthetic-" + Integer.toHexString(seed.hashCode()) + "-$i"
                }
            if (rawId.isEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastSyntheticWarnAt >= 30_000L) {
                    lastSyntheticWarnAt = now
                    AppLog.w("ZParse", "消息缺少 id/messageId，使用合成 id（示例）：$id")
                }
            }
            val msg = ZMessage(id, info.optString("role"))
            msg.createdAt = createdAt
            msg.completedAt = info.optJSONObject("time")?.optLong("completed", 0L) ?: 0L
            val parts = m.optJSONArray("parts")
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    parsePart(p, id, j)?.let { msg.parts += it }
                }
            }
            out += msg
        }
        return out
    }

    private fun parsePart(
        o: JSONObject,
        messageId: String,
        partIndex: Int,
    ): ZPart? =
        when (val t = o.optString("type")) {
            "text" -> {
                ZPart.Text(o.optString("text"))
            }

            "reasoning" -> {
                ZPart.Reasoning(o.optString("text"))
            }

            "tool" -> {
                val st = o.optJSONObject("state")
                val status = normalizeToolStatus(st?.optString("status") ?: "pending")
                val output =
                    when {
                        st == null -> null
                        st.has("output") -> st.optString("output")
                        st.has("error") -> st.optString("error")
                        else -> null
                    }
                val duration =
                    st?.optJSONObject("time")?.let {
                        // 内核会把未完成的工具调用写成 "end": null，getLong 遇到 null 直接抛
                        // JSONException，整份快照解析就此中断、界面停在上一条消息上。
                        if (it.has("end") && !it.isNull("end") && it.has("start") && !it.isNull("start")) {
                            it.optLong("end") - it.optLong("start")
                        } else {
                            null
                        }
                    } ?: run {
                        val s = st?.optLong("startedAt", 0L) ?: 0L
                        val e = st?.optLong("completedAt", 0L) ?: 0L
                        if (s > 0 && e > 0) e - s else null
                    }
                ZPart.ToolPart(
                    // callId 是下游卡片/diff 的唯一键：缺失时不能都落空串（键冲突
                    // 会把更新归到错误卡片）。OCR #819：兜底种子改用消息 id + part 序号，
                    // 不能用整个 part JSON 哈希——status/output/耗时在流式期间每轮都变，
                    // callId 跟着变会让卡片重复或把更新归错。
                    callId = o.optString("callID")
                        .ifEmpty { o.optString("callId") }
                        .ifEmpty { o.optString("id") }
                        .ifEmpty { "anon-" + Integer.toHexString((messageId + "|" + partIndex).hashCode()) },
                    tool = o.optString("tool"),
                    status = status,
                    // input 可能是对象、JSON 字符串或数组（协议变体）：
                    // optJSONObject 会把非对象形态静默丢成 null，改用 opt 归一化。
                    input = st?.opt("input")?.takeIf { it != JSONObject.NULL }?.toString(),
                    output = output,
                    title = st?.optString("title")?.takeIf { it.isNotEmpty() },
                    durationMs = duration,
                    parentToolUseId =
                        sequenceOf(
                            ZWorkflowKit.parentToolUseIdOf(o),
                            ZWorkflowKit.parentToolUseIdOf(o.optJSONObject("metadata")),
                            ZWorkflowKit.parentToolUseIdOf(st),
                        ).firstOrNull { it.isNotEmpty() },
                )
            }

            "file" -> {
                val url = o.optString("url")
                if (url.isBlank()) {
                    // 空 url 的附件无法渲染或打开，只会留下破损占位：跳过并留痕。
                    AppLog.w("ZParse", "附件缺少 url，跳过：${o.optString("filename")}")
                    null
                } else {
                    val meta = o.optJSONObject("metadata")
                    val img = meta?.optJSONObject("image")
                    ZPart.FilePart(
                        filename = o.optString("filename").ifEmpty { "附件" },
                        mime = normalizeMime(o.optString("mime")),
                        url = url,
                        sizeBytes = meta?.optLong("sizeBytes", 0L) ?: 0L,
                        width = img?.optInt("width", 0) ?: 0,
                        height = img?.optInt("height", 0) ?: 0,
                        previewReady = meta?.optString("recoverability") == "provider_ready",
                    )
                }
            }

            else -> {
                null
            }
        }

    fun normalizeToolStatus(raw: String): String =
        when (raw) {
            "pending", "scheduled" -> "scheduled"
            "running" -> "running"
            "completed" -> "completed"
            "error" -> "error"
            "denied" -> "denied"
            else -> raw
        }

    fun parseSessionInfos(result: JSONObject): List<ZSessionInfo> {
        val arr = result.optJSONArray("sessions") ?: return emptyList()
        val out = ArrayList<ZSessionInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("sessionId").ifEmpty { s.optString("id") }
            if (id.isEmpty()) continue
            out +=
                ZSessionInfo(
                    id = id,
                    title = s.optString("title"),
                    status = s.optString("status", "idle"),
                    mode = s.optString("mode", "build"),
                    updatedAt = s.optLong("updatedAt", 0L),
                    workspacePath = s.optJSONObject("workspace")?.optString("workspacePath"),
                    titleSource = s.optString("titleSource").takeIf { it.isNotEmpty() },
                )
        }
        return out
    }

    fun parseSessionIdFromSnapshot(root: JSONObject): String? =
        root.optJSONObject("session")?.optString("sessionId")?.takeIf { it.isNotEmpty() }

    /**
     * 快照里的标题；null 表示「本次快照没有标题」，调用方必须跳过更新。
     * 空串是合法值（显式清空标题），与缺失区分开，避免无标题快照擦掉已有标题。
     */
    fun parseTitleFromSnapshot(root: JSONObject): String? {
        val session = root.optJSONObject("session") ?: return null
        if (!session.has("title") || session.isNull("title")) return null
        return session.optString("title")
    }

    /**
     * 附件 MIME 归一化：渲染层只认 image/ video/ audio/ text/ 与常见文档类型，
     * 其余（含空值）统一降为 application/octet-stream，走通用文件卡而不是破损预览。
     */
    internal fun normalizeMime(raw: String): String {
        val m = raw.trim().lowercase()
        if (m.isEmpty()) return "application/octet-stream"
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") || m.startsWith("text/")) return m
        return when (m) {
            "application/pdf", "application/json", "application/zip",
            "application/octet-stream",
            -> m
            else -> "application/octet-stream"
        }
    }

    fun parseStatusFromSnapshot(root: JSONObject): String = root.optJSONObject("session")?.optString("status", "") ?: ""

    fun parseModeFromSnapshot(root: JSONObject): String = root.optJSONObject("session")?.optString("mode", "") ?: ""

    /** 解析 settings.model.available → 模型列表（当前模型的值域）。 */
    fun parseModelOptions(root: JSONObject): List<ZModelOption> {
        val settings = root.optJSONObject("settings") ?: return emptyList()
        val arr = settings.optJSONObject("model")?.optJSONArray("available") ?: return emptyList()
        val out = ArrayList<ZModelOption>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val ref = m.optJSONObject("ref") ?: continue
            // ref 缺 providerId/modelId 时选项无法被选中：把它放进列表，用户点了也没反应。
            val providerId = ref.optString("providerId")
            val modelId = ref.optString("modelId")
            if (providerId.isEmpty() || modelId.isEmpty()) continue
            val levels = ArrayList<ZModelLevel>()
            val reasoning = m.optJSONObject("reasoning")
            val larr = reasoning?.optJSONArray("levels")
            if (larr != null) {
                for (j in 0 until larr.length()) {
                    val l = larr.optJSONObject(j) ?: continue
                    levels += ZModelLevel(l.optString("value"), l.optString("label", l.optString("value")))
                }
            }
            out +=
                ZModelOption(
                    providerId = providerId,
                    modelId = modelId,
                    label = m.optString("label", modelId),
                    providerLabel = m.optString("providerLabel").takeIf { it.isNotEmpty() },
                    contextWindow = m.optInt("contextWindow", 0),
                    levels = levels,
                    defaultLevel = reasoning?.optString("defaultLevel")?.takeIf { it.isNotEmpty() },
                )
        }
        return out
    }

    /** 当前选择（settings.model.current）。 */
    fun parseCurrentSelection(root: JSONObject): Triple<String, String, String?>? {
        val settings = root.optJSONObject("settings") ?: return null
        val cur = settings.optJSONObject("model")?.optJSONObject("current") ?: return null
        val p = cur.optString("providerId")
        val m = cur.optString("modelId")
        if (p.isEmpty() || m.isEmpty()) return null
        val level = cur.optJSONObject("options")?.optString("reasoningLevel")?.takeIf { it.isNotEmpty() }
        return Triple(p, m, level)
    }

    /** thoughtLevel 状态。 */
    fun parseThoughtLevels(root: JSONObject): List<ZModelLevel> {
        val settings = root.optJSONObject("settings") ?: return emptyList()
        val tl = settings.optJSONObject("thoughtLevel") ?: return emptyList()
        val arr = tl.optJSONArray("available") ?: return emptyList()
        val out = ArrayList<ZModelLevel>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            out += ZModelLevel(l.optString("value"), l.optString("label", l.optString("value")))
        }
        return out
    }

    fun parseCurrentThoughtLevel(root: JSONObject): String? {
        val settings = root.optJSONObject("settings") ?: return null
        val tl = settings.optJSONObject("thoughtLevel") ?: return null
        return tl.optString("current").takeIf { it.isNotEmpty() }
            ?: tl.optString("defaultLevel").takeIf { it.isNotEmpty() }
    }

    /** 斜杠命令目录（快照里的 slashCommands）。 */
    fun parseSlashCommands(root: JSONObject): List<ZSlashCommand> {
        val arr = root.optJSONArray("slashCommands") ?: return emptyList()
        val out = ArrayList<ZSlashCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out +=
                ZSlashCommand(
                    name = c.optString("name"),
                    description = c.optString("description"),
                    inputHint = c.optString("inputHint").takeIf { it.isNotEmpty() },
                )
        }
        return out
    }

    /** 待办项（会话快照 todos）。 */
    data class ZTodo(
        val content: String,
        val status: String,
        val priority: String,
    )

    /** 解析快照待办：根级出现过 todos 就以它为准；只有完全没有这个键时才回退到 todoGroup。 */
    fun parseTodos(root: JSONObject): List<ZTodo> {
        // 根级 todos 存在但为空，说明当前确实没有待办；再回退到 todoGroup 会把
        // 上一轮遗留的旧列表又渲染出来，看起来像「刚清空的待办又回来了」。
        // OCR #819：显式 todos:null 与空数组同等对待——optJSONArray 对 JSON null
        // 也返回 null，直接穿透会把旧列表复活；键存在即参与更新，与 title/status 一致。
        if (root.has("todos")) {
            if (root.isNull("todos")) return emptyList()
            root.optJSONArray("todos")?.let { return parseTodoArray(it) }
        }
        val groups = root.optJSONArray("todoGroups") ?: return emptyList()
        for (i in groups.length() - 1 downTo 0) {
            val g = groups.optJSONObject(i) ?: continue
            val list = parseTodoArray(g.optJSONArray("todos"))
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun parseTodoArray(arr: JSONArray?): List<ZTodo> {
        if (arr == null) return emptyList()
        val out = ArrayList<ZTodo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content")
            if (content.isEmpty()) continue
            out +=
                ZTodo(
                    content = content,
                    status = o.optString("status", "pending"),
                    priority = o.optString("priority", "medium"),
                )
        }
        return out
    }
}
