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
    // task 是 val：对象进入列表后不再被改，equals/hashCode/copy 始终描述同一个值。
    val task: String? = null,
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
    private val WORKFLOW_TOOLS =
        setOf(
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

    private fun token(name: String?): String = name?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /**
     * 从部件/事件 JSON 里读 parentToolUseId（兼容 parentToolCallId，以及 _meta、_meta.zcode 两种包装）。
     * 内核侧：镜像到父会话的子代理工具事件把父调用 id 放在 parentToolCallId，流式事件放在
     * parentToolUseId / _meta.zcode.parentToolUseId。取不到返回空串。
     */
    fun parentToolUseIdOf(o: JSONObject?): String {
        if (o == null) return ""
        val direct =
            firstNonEmpty(
                stringOrNull(o, "parentToolUseId"),
                stringOrNull(o, "parentToolCallId"),
            )
        if (direct.isNotEmpty()) return direct
        val meta = o.optJSONObject("_meta") ?: return ""
        val fromMeta =
            firstNonEmpty(
                stringOrNull(meta, "parentToolUseId"),
                stringOrNull(meta, "parentToolCallId"),
            )
        if (fromMeta.isNotEmpty()) return fromMeta
        val zcode = meta.optJSONObject("zcode") ?: return ""
        return firstNonEmpty(
            stringOrNull(zcode, "parentToolUseId"),
            stringOrNull(zcode, "parentToolCallId"),
        )
    }

    /**
     * 读一个字符串字段。
     *
     * optString 对显式 null 会给回字符串 "null"：字段看起来有值，实际是被污染的，
     * 拿它当父级工具调用 id 就会指向一个不存在的调用。
     */
    private fun stringOrNull(
        o: JSONObject,
        key: String,
    ): String = if (o.isNull(key)) "" else o.optString(key)

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

    fun isWorkflowTool(name: String?): Boolean = WORKFLOW_TOOLS.contains(token(name))

    fun isAgentTool(name: String?): Boolean = AGENT_TOOLS.contains(token(name))

    fun isWorkflowToolRunning(name: String?): Boolean =
        token(name) == "createworkflow" ||
            token(name) == "resumeworkflowrun"

    fun workflowToolLabel(name: String?): String =
        when (token(name)) {
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

    fun runStatusLabel(status: String): String =
        when (status) {
            "pending" -> "排队中"
            "running" -> "运行中"
            "completed" -> "已完成"
            "errored" -> "出错"
            "stopped" -> "已停止"
            else -> status
        }

    /**
     * 工作流控制被拒时的原因文案。
     *
     * 内核按 reasonCode 分流（见 workflowRunResumeRejected.<reason>），这里只翻译给用户看；
     * 空串表示没有具体原因，调用方回落到内核给的 message。
     */
    fun rejectReasonLabel(reason: String?): String =
        when (reason) {
            "not_resumable" -> "这个运行已经不能再继续了"
            "already_running" -> "它已经在运行中"
            "not_found" -> "找不到这个运行"
            "script_missing" -> "脚本已丢失，无法继续"
            "script_mismatch" -> "脚本已变更，无法继续"
            "compile_failed" -> "脚本编译失败，无法继续"
            "unsupported" -> "当前内核不支持这项操作"
            else -> ""
        }

    fun phaseStateLabel(state: String): String =
        when (state) {
            "done" -> "已完成"
            "current" -> "进行中"
            "ahead" -> "未开始"
            "unfinished" -> "未结算"
            else -> state
        }

    fun actorStateLabel(state: String): String =
        when (state) {
            "executing" -> "执行中"
            "waiting" -> "等待中"
            "parked" -> "等回答"
            "idle" -> "空闲"
            "done" -> "已完成"
            "failed" -> "失败"
            "unfinished" -> "未结算"
            else -> state
        }

    fun subagentStatusLabel(status: String): String =
        when (status) {
            "running" -> "进行中"
            "waiting" -> "等待中"
            "blocked" -> "受阻"
            "success" -> "成功"
            "failed" -> "失败"
            "cancelled" -> "已取消"
            "lost" -> "已失联"
            else -> status
        }

    fun toolStatusLabel(status: String): String =
        when (status) {
            "running", "scheduled" -> "进行中"
            "error", "denied" -> "失败"
            else -> "完成"
        }

    /** 阶段状态 → StatusMarkView 的生命周期词。 */
    fun phaseMarkState(state: String): String =
        when (state) {
            "current" -> "running"
            "done" -> "done"
            "unfinished" -> "failed"
            else -> "pending"
        }

    /** 子代理 / actor 状态 → StatusMarkView 的生命周期词。 */
    fun actorMarkState(state: String): String =
        when (state) {
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
                out +=
                    ZSubagent(
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
            out +=
                ZWorkflowRun(
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
    private fun section(
        text: String?,
        tag: String,
    ): String? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf("<$tag>")
        if (start < 0) return null
        val end = text.indexOf("</$tag>", start)
        if (end <= start) return null
        return text.substring(start + tag.length + 2, end)
    }

    private val COLUMN_SPLIT = Regex("\\s{2,}")

    /** 一行的前导空白数（tab 记 1，与内核输出的空格缩进一致）。 */
    private fun indentOf(line: String): Int = line.takeWhile { it.isWhitespace() }.length

    private val ROUNDS = Regex("^(\\d+) rounds?$")
    private val COUNTS_RUNNING = Regex("^(\\d+) settled, (\\d+) (running|unfinished)$")
    private val COUNTS_SETTLED = Regex("^(\\d+) steps? settled$")

    /**
     * 时长格式：数字加时间单位的组合（12s / 1m30s / 2h5m / 450ms / 1.5s）。
     *
     * 内核 formatWorkflowRunDuration 的真实输出还有三种形态（#768）：
     * 双单位用**单空格**分隔（5m 10s）、天单位（3d 2h）、进行中阶段的
     * " so far" 后缀（12s so far）。少任何一样，≥1 分钟的阶段全丢时长列。
     * 单位交替里 ms 在 m 前、d 在 h 前，避免前缀吞掉后续数字。
     */
    private val DURATION = Regex("^\\d+(\\.\\d+)?(ms|d|h|m|s)( ?\\d+(\\.\\d+)?(ms|d|h|m|s))*( so far)?$")
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
            val index =
                if (dot > 0) {
                    head.substring(0, dot).trim().toIntOrNull() ?: (out.size + 1)
                } else {
                    out.size + 1
                }
            val name = if (dot > 0) head.substring(dot + 1).trim() else head
            var state = ""
            var rounds = 0
            var settled = 0
            var running = 0
            var duration = ""
            for (cell in cells.drop(1)) {
                // 每条正则只执行一次并直接取 MatchResult：matches()+find()!! 是两次
                // 独立执行，分支重排后 find 为 null 时 !! 会把可降级场景变成崩溃。
                val roundsMatch = ROUNDS.matchEntire(cell)
                val runningMatch = COUNTS_RUNNING.matchEntire(cell)
                val settledMatch = COUNTS_SETTLED.matchEntire(cell)
                when {
                    PHASE_STATES.contains(cell) -> {
                        state = cell
                    }

                    roundsMatch != null -> {
                        rounds = roundsMatch.groupValues[1].toIntOrNull() ?: 0
                    }

                    runningMatch != null -> {
                        settled = runningMatch.groupValues[1].toIntOrNull() ?: 0
                        running = runningMatch.groupValues[2].toIntOrNull() ?: 0
                    }

                    settledMatch != null -> {
                        settled = settledMatch.groupValues[1].toIntOrNull() ?: 0
                    }

                    // 未知列不再照单全收进 duration：内核格式新增列（token 数、备注）
                    // 会被当成时长渲染成乱码。只收时长格式的单元格。
                    duration.isEmpty() && DURATION.matches(cell) -> {
                        duration = cell
                    }
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
        // 块体的基础缩进 = 第一行非空行的前导空白数；附注行比它更深。
        val baseIndent =
            body.split('\n').firstOrNull { it.isNotBlank() }?.let { indentOf(it) } ?: 0
        val out = ArrayList<ZWorkflowActor>()
        // task 行先暂存，构造 actor 时一次性传入：不再对已入列的对象做后置修改。
        // 前置 task（还没有 actor）挂到下一个 actor；夹在其他行后的 task 仍归属
        // 最近一个 actor，但通过 copy 重建而不是原地改。
        var pendingTask: String? = null
        for (raw in body.split('\n')) {
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.startsWith("task:")) {
                val task = trimmed.removePrefix("task:").trim()
                if (out.isEmpty()) {
                    pendingTask = task
                } else {
                    val last = out.removeAt(out.size - 1)
                    out.add(last.copy(task = task))
                }
                continue
            }
            // 缩进行属于上一条 actor 的附注。注意 section() 取出的块体每行都带
            // 基础缩进，「整块统一缩进」不算附注，只有相对更深的缩进才是。
            if (raw.firstOrNull()?.isWhitespace() == true && indentOf(raw) > baseIndent) continue
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
                        !cell.startsWith("phase ") && !cell.endsWith(" tokens") -> {
                        name = cell
                    }

                    address.isEmpty() && ADDRESS.matches(cell) -> {
                        address = cell
                    }

                    state.isEmpty() && ACTOR_STATES.contains(cell) -> {
                        state = cell
                    }

                    phase == null && cell.startsWith("phase ") -> {
                        phase = cell.removePrefix("phase ").trim()
                    }

                    tokens.isEmpty() && cell.endsWith(" tokens") -> {
                        tokens = cell
                    }

                    else -> {
                        if (activity.isNotEmpty()) {
                            activity.append(' ').append(cell)
                        } else {
                            activity.append(cell)
                        }
                    }
                }
            }
            // 只有状态词的行（name/address 全空）不产出演员：state 已赋值会让
            // 原来的三重空判断失效，渲染出一个空白演员。
            if (name.isEmpty() && address.isEmpty()) continue
            out +=
                ZWorkflowActor(
                    name = name,
                    address = address,
                    state = state,
                    phaseName = phase,
                    activity = activity.toString(),
                    tokens = tokens,
                    task = pendingTask,
                )
            pendingTask = null
        }
        return out
    }

    /** 当前步骤：优先 current，否则最近一个非 ahead 的阶段。 */
    fun currentPhase(phases: List<ZWorkflowPhase>): ZWorkflowPhase? =
        phases.firstOrNull { it.isCurrent } ?: phases.lastOrNull { it.state != "ahead" }

    /** 某个阶段（步骤）上的子智能体。 */
    fun actorsOfPhase(
        actors: List<ZWorkflowActor>,
        phase: ZWorkflowPhase?,
    ): List<ZWorkflowActor> {
        if (phase == null) return emptyList()
        return actors.filter { it.phaseName == phase.name }
    }

    /** 当前步骤上仍在跑/在等的子智能体数量。 */
    fun activeActorCount(
        actors: List<ZWorkflowActor>,
        phase: ZWorkflowPhase?,
    ): Int = actorsOfPhase(actors, phase).count { it.isActive }

    /** 当前步骤的进度读数：已结算 /（已结算 + 在跑）。 */
    fun stepProgressLabel(phase: ZWorkflowPhase?): String {
        if (phase == null) return ""
        val total = phase.stepsSettled + phase.stepsRunning
        if (total <= 0) return ""
        return "${phase.stepsSettled} / $total"
    }

    // ------------------------------------------------------------ 实时进度

    /**
     * actor 的唯一键：runId + 地址（`siteId@ordinal`）。
     *
     * 同一 run 里地址唯一；跨 run 同名 actor 很常见，所以必须带 runId 才不撞。
     */
    fun actorKey(
        runId: String,
        address: String,
    ): String = "$runId#$address"

    /**
     * 从一条 dynamic_workflow_run_progress 事件里取 actor 地址。
     *
     * payload 的 `actor` 形状由引擎决定（可能是字符串，也可能是带 site/ordinal 的对象），
     * 这里两种都认；取不到就返回空串，调用方据此跳过（不会写坏映射）。
     */
    fun actorAddressOf(payload: JSONObject?): String {
        val actor = payload?.opt("actor") ?: return ""
        if (actor is String) return actor
        val o = actor as? JSONObject ?: return ""
        // OCR #819：site 系字段同样要防显式 null——optString 会给出字面量 "null"，
        // {"siteId": null, "ordinal": 2} 拼出 "null@2" 这种假地址。统一走 stringOrNull。
        val direct = firstNonEmpty(stringOrNull(o, "address"), stringOrNull(o, "ref"), stringOrNull(o, "key"))
        if (direct.isNotEmpty()) return direct
        val site = firstNonEmpty(stringOrNull(o, "siteId"), stringOrNull(o, "site"), stringOrNull(o, "id"))
        if (site.isEmpty()) return ""
        // ordinal 缺省时只报 siteId，宁可匹配不到也不猜一个错地址。
        // 显式 null 也算缺省：has() 只判键存在，optInt 会把它变成 0，拼出 site@0 这种错地址。
        val ordinal = if (o.isNull("ordinal")) -1 else o.optInt("ordinal", -1)
        return if (ordinal >= 0) "$site@$ordinal" else site
    }

    /**
     * 把一条进度事件折成"这个 run 现在在做什么"。
     *
     * 只认有展示价值的几种（run-started / actor-created / phase-entered / node-dispatched /
     * run-settled）；日志、用量这类事件没有可读的动作，返回 null 让调用方跳过，
     * 卡片不会因为无信息量的事件反复重画。
     */
    fun liveFromProgress(
        runId: String,
        eventType: String,
        payload: JSONObject,
        actorAddress: String,
        actorSessionId: String?,
    ): ZWorkflowLive? {
        val phase = payload.optString("phaseName").takeIf { it.isNotEmpty() }
        val actorName = payload.optString("actorName").takeIf { it.isNotEmpty() }
        val settled = eventType == "run-settled"
        val line =
            when (eventType) {
                "actor-created" -> {
                    if (actorName.isNullOrEmpty()) "子智能体已就绪" else "$actorName 已就绪"
                }

                "phase-entered" -> {
                    if (phase.isNullOrEmpty()) "进入新阶段" else "进入 $phase"
                }

                "node-dispatched" -> {
                    when {
                        actorName != null -> "$actorName 开始执行"
                        else -> "派发新节点"
                    }
                }

                "run-started" -> {
                    "运行已开始"
                }

                "run-settled" -> {
                    if (payload.optBoolean("resumable", false)) "已停止，可继续" else "运行已结束"
                }

                else -> {
                    return null
                }
            }
        return ZWorkflowLive(
            runId = runId,
            eventType = eventType,
            line = line,
            phaseName = phase,
            actorAddress = actorAddress,
            actorSessionId = actorSessionId,
            settled = settled,
            resumable = payload.optBoolean("resumable", false),
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/**
 * 一个 run 最近一条有信息量的进度事件。
 *
 * 折叠卡用它显示"正在做什么"，不必等 GetWorkflowRun 的结果回到工具卡里才有内容。
 */
data class ZWorkflowLive(
    val runId: String,
    val eventType: String,
    val line: String,
    val phaseName: String?,
    val actorAddress: String,
    val actorSessionId: String?,
    val settled: Boolean,
    val resumable: Boolean,
    val updatedAt: Long,
)
