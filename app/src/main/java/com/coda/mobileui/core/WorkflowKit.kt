package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject

/** 会话内的子代理（session/subagents）。 */
data class ZSubagent(
    val childSessionId: String,
    val toolCallId: String?,
    val subagentType: String,
    val title: String,
    val summary: String?,
    val status: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    val isActive: Boolean
        get() = status == "running" || status == "waiting" || status == "blocked"
}

/** 工作流运行摘要（workflows/runs）。 */
data class ZWorkflowRun(
    val runId: String,
    val name: String,
    val status: String,
    val updatedAt: Long,
    val spentTokens: Long,
    val toolCallId: String?,
    val parentSessionId: String?,
) {
    val isTerminal: Boolean
        get() = status != "pending" && status != "running"
}

/** 工作流阶段（GetWorkflowRun 模型面的 <phases> 块）。 */
data class ZWorkflowPhase(
    val index: Int,
    val name: String,
    val state: String,
    val rounds: Int,
    val stepsSettled: Int,
    val stepsRunning: Int,
    val duration: String,
) {
    val isCurrent: Boolean get() = state == "current"
    val isPast: Boolean get() = state == "done"
}

/** 工作流里的子智能体（GetWorkflowRun 模型面的 <subagents> 块一行）。 */
data class ZWorkflowActor(
    val name: String,
    val address: String,
    val state: String,
    val phaseName: String?,
    val activity: String,
    val tokens: String,
    var task: String? = null,
) {
    val isActive: Boolean
        get() = state == "executing" || state == "waiting" || state == "parked"
}

/**
 * 子代理与工作流的展示层：数据来自内核的 session/subagents、workflows/runs，
 * 以及 GetWorkflowRun 工具输出里的 <phases> / <subagents> 文本块
 * （格式与内核 get-workflow-run-format-roster.ts 一致）。
 */
object ZWorkflowKit {

    // ---------------------------------------------------------------- 识别

    /** 工作流一族的工具名（对齐桌面端 packages/ui/src/lib/workflowToolNames.ts）。 */
    private val WORKFLOW_TOOLS = setOf(
        "createworkflow",
        "amendworkflow",
        "getworkflowrun",
        "resumeworkflowrun",
        "listworkflowruns",
        "evalworkflowsnippet",
        "saveworkflow",
        "listsavedworkflows",
        "resolveworkflowquestion",
        "listmodels",
        "escalate",
    )

    /** 子代理一族的工具名。 */
    private val AGENT_TOOLS = setOf("agent", "task", "explore")

    private fun token(name: String?): String =
        name?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /**
     * 从部件/事件 JSON 里读 parentToolUseId（兼容 parentToolCallId，以及 _meta、_meta.zcode 两种包装）。
     * 内核侧：镜像到父会话的子代理工具事件把父调用 id 放在 parentToolCallId，流式事件放在
     * parentToolUseId / _meta.zcode.parentToolUseId。取不到返回空串。
     */
    fun parentToolUseIdOf(o: JSONObject?): String {
        if (o == null) return ""
        val direct = firstNonEmpty(
            o.optString("parentToolUseId"),
            o.optString("parentToolCallId"),
        )
        if (direct.isNotEmpty()) return direct
        val meta = o.optJSONObject("_meta") ?: return ""
        val fromMeta = firstNonEmpty(
            meta.optString("parentToolUseId"),
            meta.optString("parentToolCallId"),
        )
        if (fromMeta.isNotEmpty()) return fromMeta
        val zcode = meta.optJSONObject("zcode") ?: return ""
        return firstNonEmpty(
            zcode.optString("parentToolUseId"),
            zcode.optString("parentToolCallId"),
        )
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotEmpty() } ?: ""

    fun isWorkflowTool(name: String?): Boolean = WORKFLOW_TOOLS.contains(token(name))

    fun isAgentTool(name: String?): Boolean = AGENT_TOOLS.contains(token(name))

    fun isWorkflowToolRunning(name: String?): Boolean = token(name) == "createworkflow" ||
        token(name) == "resumeworkflowrun"

    fun workflowToolLabel(name: String?): String = when (token(name)) {
        "createworkflow" -> "创建工作流"
        "amendworkflow" -> "修订工作流"
        "getworkflowrun" -> "查看工作流"
        "resumeworkflowrun" -> "恢复工作流"
        "listworkflowruns" -> "运行记录"
        "evalworkflowsnippet" -> "运行工作流片段"
        "saveworkflow" -> "保存工作流"
        "listsavedworkflows" -> "已保存工作流"
        "resolveworkflowquestion" -> "回答工作流问题"
        "listmodels" -> "模型目录"
        "escalate" -> "向用户提问"
        else -> name ?: "工作流"
    }

    // ---------------------------------------------------------------- 文案

    fun runStatusLabel(status: String): String = when (status) {
        "pending" -> "排队中"
        "running" -> "运行中"
        "completed" -> "已完成"
        "errored" -> "出错"
        "stopped" -> "已停止"
        else -> status
    }

    fun phaseStateLabel(state: String): String = when (state) {
        "done" -> "已完成"
        "current" -> "进行中"
        "ahead" -> "未开始"
        "unfinished" -> "未结算"
        else -> state
    }

    fun actorStateLabel(state: String): String = when (state) {
        "executing" -> "执行中"
        "waiting" -> "等待中"
        "parked" -> "等回答"
        "idle" -> "空闲"
        "done" -> "已完成"
        "failed" -> "失败"
        "unfinished" -> "未结算"
        else -> state
    }

    fun subagentStatusLabel(status: String): String = when (status) {
        "running" -> "进行中"
        "waiting" -> "等待中"
        "blocked" -> "受阻"
        "success" -> "成功"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        "lost" -> "已失联"
        else -> status
    }

    fun toolStatusLabel(status: String): String = when (status) {
        "running", "scheduled" -> "进行中"
        "error", "denied" -> "失败"
        else -> "完成"
    }

    /** 阶段状态 → StatusMarkView 的生命周期词。 */
    fun phaseMarkState(state: String): String = when (state) {
        "current" -> "running"
        "done" -> "done"
        "unfinished" -> "failed"
        else -> "pending"
    }

    /** 子代理 / actor 状态 → StatusMarkView 的生命周期词。 */
    fun actorMarkState(state: String): String = when (state) {
        "running", "executing" -> "running"
        "success", "done" -> "done"
        "failed", "lost" -> "failed"
        "blocked", "cancelled", "unfinished" -> "cancelled"
        else -> "pending"
    }

    // ---------------------------------------------------------------- 解析

    /** session/subagents → 运行中 + 已结束（一页）子代理。 */
    fun parseSubagents(root: JSONObject): List<ZSubagent> {
        val out = ArrayList<ZSubagent>()
        fun read(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val child = o.optString("childSessionId")
                if (child.isEmpty()) continue
                out += ZSubagent(
                    childSessionId = child,
                    toolCallId = o.optString("toolCallId").takeIf { it.isNotEmpty() },
                    subagentType = o.optString("subagentType"),
                    title = o.optString("title"),
                    summary = o.optString("summary").takeIf { it.isNotEmpty() },
                    status = o.optString("status"),
                    startedAt = o.optLong("startedAt", 0L),
                    endedAt = o.optLong("endedAt", 0L),
                )
            }
        }
        read(root.optJSONArray("running"))
        read(root.optJSONObject("ended")?.optJSONArray("items"))
        return out
    }

    /** workflows/runs → 运行摘要。 */
    fun parseWorkflowRuns(root: JSONObject): List<ZWorkflowRun> {
        val arr = root.optJSONArray("runs") ?: return emptyList()
        val out = ArrayList<ZWorkflowRun>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("runId")
            if (id.isEmpty()) continue
            out += ZWorkflowRun(
                runId = id,
                name = o.optString("name").takeIf { it.isNotEmpty() } ?: id.take(12),
                status = o.optString("status"),
                updatedAt = o.optLong("updatedAt", 0L),
                spentTokens = o.optLong("spentTokens", 0L),
                toolCallId = o.optString("toolCallId").takeIf { it.isNotEmpty() },
                parentSessionId = o.optString("parentSessionId").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    /** 取 <tag>…</tag> 之间的正文。 */
    private fun section(text: String?, tag: String): String? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf("<$tag>")
        if (start < 0) return null
        val end = text.indexOf("</$tag>", start)
        if (end <= start) return null
        return text.substring(start + tag.length + 2, end)
    }

    private val COLUMN_SPLIT = Regex("\\s{2,}")
    private val ROUNDS = Regex("^(\\d+) rounds?$")
    private val COUNTS_RUNNING = Regex("^(\\d+) settled, (\\d+) (running|unfinished)$")
    private val COUNTS_SETTLED = Regex("^(\\d+) steps? settled$")
    private val ADDRESS = Regex("^\\S+@\\d+$")
    private val PHASE_STATES = setOf("done", "current", "ahead", "unfinished")
    private val ACTOR_STATES =
        setOf("executing", "waiting", "parked", "idle", "done", "failed", "unfinished")

    /** GetWorkflowRun 输出 → 阶段轨。 */
    fun parsePhases(output: String?): List<ZWorkflowPhase> {
        val body = section(output, "phases") ?: return emptyList()
        val out = ArrayList<ZWorkflowPhase>()
        for (raw in body.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val cells = line.split(COLUMN_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) continue
            val head = cells[0]
            val dot = head.indexOf('.')
            val index = if (dot > 0) head.substring(0, dot).trim().toIntOrNull() ?: (out.size + 1)
            else out.size + 1
            val name = if (dot > 0) head.substring(dot + 1).trim() else head
            var state = ""
            var rounds = 0
            var settled = 0
            var running = 0
            var duration = ""
            for (cell in cells.drop(1)) {
                when {
                    PHASE_STATES.contains(cell) -> state = cell
                    ROUNDS.matches(cell) -> rounds = ROUNDS.find(cell)!!.groupValues[1].toInt()
                    COUNTS_RUNNING.matches(cell) -> {
                        val m = COUNTS_RUNNING.find(cell)!!
                        settled = m.groupValues[1].toInt()
                        running = m.groupValues[2].toInt()
                    }

                    COUNTS_SETTLED.matches(cell) ->
                        settled = COUNTS_SETTLED.find(cell)!!.groupValues[1].toInt()

                    duration.isEmpty() -> duration = cell
                }
            }
            out += ZWorkflowPhase(index, name, state, rounds, settled, running, duration)
        }
        return out
    }

    /** GetWorkflowRun 输出 → 花名册（子智能体）。 */
    fun parseActors(output: String?): List<ZWorkflowActor> {
        val body = section(output, "subagents") ?: return emptyList()
        if (body.contains("No subagents created yet")) return emptyList()
        val out = ArrayList<ZWorkflowActor>()
        for (raw in body.split('\n')) {
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.startsWith("task:")) {
                out.lastOrNull()?.task = trimmed.removePrefix("task:").trim()
                continue
            }
            // 缩进行属于上一条 actor 的附注
            if (raw.firstOrNull()?.isWhitespace() == true) continue
            val cells = trimmed.split(COLUMN_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) continue
            if (cells.size == 1 && !ACTOR_STATES.contains(cells[0]) && !ADDRESS.matches(cells[0])) {
                continue
            }
            var name = ""
            var address = ""
            var state = ""
            var phase: String? = null
            var tokens = ""
            val activity = StringBuilder()
            for (cell in cells) {
                when {
                    name.isEmpty() && state.isEmpty() && phase == null && tokens.isEmpty() &&
                        !ADDRESS.matches(cell) && !ACTOR_STATES.contains(cell) &&
                        !cell.startsWith("phase ") && !cell.endsWith(" tokens") -> name = cell

                    address.isEmpty() && ADDRESS.matches(cell) -> address = cell
                    state.isEmpty() && ACTOR_STATES.contains(cell) -> state = cell
                    phase == null && cell.startsWith("phase ") ->
                        phase = cell.removePrefix("phase ").trim()

                    tokens.isEmpty() && cell.endsWith(" tokens") -> tokens = cell
                    else -> if (activity.isNotEmpty()) activity.append(' ').append(cell)
                    else activity.append(cell)
                }
            }
            if (state.isEmpty() && address.isEmpty() && name.isEmpty()) continue
            out += ZWorkflowActor(
                name = name,
                address = address,
                state = state,
                phaseName = phase,
                activity = activity.toString(),
                tokens = tokens,
            )
        }
        return out
    }

    /** 当前步骤：优先 current，否则最近一个非 ahead 的阶段。 */
    fun currentPhase(phases: List<ZWorkflowPhase>): ZWorkflowPhase? =
        phases.firstOrNull { it.isCurrent } ?: phases.lastOrNull { it.state != "ahead" }

    /** 某个阶段（步骤）上的子智能体。 */
    fun actorsOfPhase(actors: List<ZWorkflowActor>, phase: ZWorkflowPhase?): List<ZWorkflowActor> {
        if (phase == null) return emptyList()
        return actors.filter { it.phaseName == phase.name }
    }

    /** 当前步骤上仍在跑/在等的子智能体数量。 */
    fun activeActorCount(actors: List<ZWorkflowActor>, phase: ZWorkflowPhase?): Int =
        actorsOfPhase(actors, phase).count { it.isActive }

    /** 当前步骤的进度读数：已结算 /（已结算 + 在跑）。 */
    fun stepProgressLabel(phase: ZWorkflowPhase?): String {
        if (phase == null) return ""
        val total = phase.stepsSettled + phase.stepsRunning
        if (total <= 0) return ""
        return "${phase.stepsSettled} / $total"
    }
}
