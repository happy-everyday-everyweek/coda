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
data class ZModelLevel(val value: String, val label: String)

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
data class ZSlashCommand(val name: String, val description: String, val inputHint: String?)

/** 消息部件。 */
sealed class ZPart {
    class Text(var text: String) : ZPart()
    class Reasoning(var text: String) : ZPart()
    class ToolPart(
        val callId: String,
        var tool: String,
        var status: String,
        var input: String?,
        var output: String?,
        var title: String?,
        var durationMs: Long?,
    ) : ZPart()
}

/** 消息。 */
class ZMessage(val id: String, val role: String) {
    var createdAt: Long = 0
    var completedAt: Long = 0
    val parts = mutableListOf<ZPart>()

    fun text(): String = parts.filterIsInstance<ZPart.Text>().joinToString("") { it.text }
}

/** 协议 JSON 解析（兼容两代字段命名）。 */
object ZParse {

    fun parseMessages(result: JSONObject): List<ZMessage> {
        val arr = result.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<ZMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val info = m.optJSONObject("info") ?: JSONObject()
            val id = info.optString("id").ifEmpty { info.optString("messageId") }
            if (id.isEmpty()) continue
            val msg = ZMessage(id, info.optString("role"))
            val time = info.optJSONObject("time")
            msg.createdAt = time?.optLong("created", 0L) ?: 0L
            msg.completedAt = time?.optLong("completed", 0L) ?: 0L
            val parts = m.optJSONArray("parts")
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    parsePart(p)?.let { msg.parts += it }
                }
            }
            out += msg
        }
        return out
    }

    private fun parsePart(o: JSONObject): ZPart? = when (val t = o.optString("type")) {
        "text" -> ZPart.Text(o.optString("text"))
        "reasoning" -> ZPart.Reasoning(o.optString("text"))
        "tool" -> {
            val st = o.optJSONObject("state")
            val status = normalizeToolStatus(st?.optString("status") ?: "pending")
            val output = when {
                st == null -> null
                st.has("output") -> st.optString("output")
                st.has("error") -> st.optString("error")
                else -> null
            }
            val duration = st?.optJSONObject("time")?.let {
                if (it.has("end") && it.has("start")) it.getLong("end") - it.getLong("start") else null
            } ?: run {
                val s = st?.optLong("startedAt", 0L) ?: 0L
                val e = st?.optLong("completedAt", 0L) ?: 0L
                if (s > 0 && e > 0) e - s else null
            }
            ZPart.ToolPart(
                callId = o.optString("callID").ifEmpty { o.optString("callId") },
                tool = o.optString("tool"),
                status = status,
                input = st?.optJSONObject("input")?.toString(),
                output = output,
                title = st?.optString("title")?.takeIf { it.isNotEmpty() },
                durationMs = duration,
            )
        }

        else -> null
    }

    fun normalizeToolStatus(raw: String): String = when (raw) {
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
            out += ZSessionInfo(
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

    fun parseTitleFromSnapshot(root: JSONObject): String =
        root.optJSONObject("session")?.optString("title") ?: ""

    fun parseStatusFromSnapshot(root: JSONObject): String =
        root.optJSONObject("session")?.optString("status", "") ?: ""

    fun parseModeFromSnapshot(root: JSONObject): String =
        root.optJSONObject("session")?.optString("mode", "") ?: ""

    /** 解析 settings.model.available → 模型列表（当前模型的值域）。 */
    fun parseModelOptions(root: JSONObject): List<ZModelOption> {
        val settings = root.optJSONObject("settings") ?: return emptyList()
        val arr = settings.optJSONObject("model")?.optJSONArray("available") ?: return emptyList()
        val out = ArrayList<ZModelOption>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val ref = m.optJSONObject("ref") ?: continue
            val levels = ArrayList<ZModelLevel>()
            val reasoning = m.optJSONObject("reasoning")
            val larr = reasoning?.optJSONArray("levels")
            if (larr != null) {
                for (j in 0 until larr.length()) {
                    val l = larr.optJSONObject(j) ?: continue
                    levels += ZModelLevel(l.optString("value"), l.optString("label", l.optString("value")))
                }
            }
            out += ZModelOption(
                providerId = ref.optString("providerId"),
                modelId = ref.optString("modelId"),
                label = m.optString("label", ref.optString("modelId")),
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
            out += ZSlashCommand(
                name = c.optString("name"),
                description = c.optString("description"),
                inputHint = c.optString("inputHint").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /** 待办项（会话快照 todos）。 */
    data class ZTodo(val content: String, val status: String, val priority: String)

    /** 解析快照待办：优先根级 todos；为空时回退到最后一个 todoGroup 的列表。 */
    fun parseTodos(root: JSONObject): List<ZTodo> {
        val direct = parseTodoArray(root.optJSONArray("todos"))
        if (direct.isNotEmpty()) return direct
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
            out += ZTodo(
                content = content,
                status = o.optString("status", "pending"),
                priority = o.optString("priority", "medium"),
            )
        }
        return out
    }
}
