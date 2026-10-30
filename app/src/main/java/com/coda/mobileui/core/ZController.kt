package com.coda.mobileui.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话与消息状态控制器：把协议能力封装成 UI 可直接调用的一组操作。
 * 所有回调都会切到主线程。
 */
class ZController private constructor(
    private val app: Context,
) {
    interface Listener {
        /** 任意状态变化（会话列表/消息/设置），UI 重新渲染。 */
        fun onStateChanged() {}

        /** 流式文本增量（用于局部渲染）。 */
        fun onDelta(
            assistantMessageId: String,
            text: String,
        ) {}

        /** 流式思考增量（用于局部渲染）。 */
        fun onReasoningDelta(
            assistantMessageId: String,
            text: String,
        ) {}

        /** 轻提示（错误/状态）。 */
        fun onNotice(text: String) {}

        /** 权限请求需要 UI 决策。 */
        fun onPermissionRequest(
            requestId: Any,
            params: JSONObject,
        ) {}

        /** 用户提问需要 UI 决策。 */
        fun onUserInputRequest(
            requestId: Any,
            params: JSONObject,
        ) {}

        /** 会话已打开。 */
        fun onSessionOpened(sessionId: String) {}
    }

    /**
     * 工作流进度回调的独立接口。
     *
     * 进度事件来得又密又轻，如果都走 [Listener.onStateChanged] 会把整个聊天页重建一遍
     * （还会打断滚动）；需要实时刷新的组件单独注册，只重画自己那一块。
     */
    fun interface WorkflowWatcher {
        fun onWorkflowProgress(runId: String)
    }

    val runtime = CoreRuntime(app)

    var sessions: MutableList<ZSessionInfo> = mutableListOf()
        private set
    var currentSessionId: String? = null
        private set
    var messages: MutableList<ZMessage> = mutableListOf()

    /**
     * 当前展示的内容来自本地缓存、而不是内核。
     *
     * 内核没起来时靠它把界面切成只读：能翻看上次的会话与消息，但不允许发送、
     * 不允许新建会话，也不允许改模型——那些动作都要经过内核，发出去只会失败。
     */
    @Volatile
    var cacheOnly: Boolean = false
        private set
    var running: Boolean = false
        private set
    var mode: String = "build"
        private set
    var thoughtLevels: List<ZModelLevel> = emptyList()
        private set
    var currentThoughtLevel: String? = null
        private set
    var modelOptions: List<ZModelOption> = emptyList()
        private set
    var currentModel: Triple<String, String, String?>? = null
        private set
    var slashCommands: List<ZSlashCommand> = emptyList()
        private set

    /** 当前会话待办列表（随会话快照同步）。 */
    var todos: List<ZParse.ZTodo> = emptyList()
        private set

    /** 当前会话的子代理（session/subagents：运行中 + 已结束一页）。 */
    var subagents: List<ZSubagent> = emptyList()
        private set

    /** 当前工作区的动态工作流运行摘要（workflows/runs）。 */
    var workflowRuns: List<ZWorkflowRun> = emptyList()
        private set

    /**
     * 工作流子智能体（actor）→ 它自己的子会话 id。
     *
     * 来源是父会话的 dynamic_workflow_run_progress 事件：`actor-created`/`node-dispatched`
     * 上带 actorSessionId（内核铸成 sess_dwf-<runId>-<site@ordinal>）。键用
     * [ZWorkflowKit.actorKey]，即 runId + 地址，避免两个 run 的同名 actor 相撞。
     */
    private val actorSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

/** 每个 run 最近一条进度事件（折叠卡靠它显示"正在做什么"，不必等工具结果）。 */
    private val workflowLive = java.util.concurrent.ConcurrentHashMap<String, ZWorkflowLive>()

    /** 关心工作流进度的轻量订阅者（打开中的半屏），与主聊天页的整页重渲染分开。 */
    private val workflowWatchers = CopyOnWriteArrayList<WorkflowWatcher>()

    fun addWorkflowWatcher(w: WorkflowWatcher) {
        if (!workflowWatchers.contains(w)) workflowWatchers += w
    }

    fun removeWorkflowWatcher(w: WorkflowWatcher) {
        workflowWatchers -= w
    }

    /** 子会话消息缓存（子代理半屏的整页会话视图用）。 */
    private val childMessages = HashMap<String, List<ZMessage>>()

    /** 子会话消息读取失败过的会话 id（避免把「读失败」当成「确实为空」）。 */
    private val childMessageFailures = HashSet<String>()

    /** 正在读取中的子会话 id（同会话只保留一个在途请求）。 */
    private val childLoading = HashSet<String>()

    /** 父 Agent 工具调用 id → 子会话 id（由流式事件的 parentToolUseId 解析而来）。 */
    private val parentCallToChild = HashMap<String, String>()

    /** 子工具调用 id → 父 Agent 工具调用 id。 */
    private val toolCallToParentCall = HashMap<String, String>()

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("zcode_bridge", Context.MODE_PRIVATE)

    private var started = false
    private var startWaiters = mutableListOf<(Boolean, String) -> Unit>()

    /** 连续意外退出计数（稳定运行超 2 分钟后重置）。 */
    private var exitCount = 0

    /** 最近一次启动成功的时刻。 */
    private var lastStartAt = 0L

    /** 是否已安排自动重启（防重复安排）。 */
    private var autoRestartScheduled = false
    private var refreshScheduled = false
    private var refreshing = false
    private var refreshAgain = false

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun notif(block: Listener.() -> Unit) {
        post { for (l in listeners) l.block() }
    }

    // ---------------------------------------------------------------- 启动

    fun ensureStarted(cb: (Boolean, String) -> Unit) {
        if (started && runtime.isRunning) {
            cb(true, "ok")
            return
        }
        startWaiters += cb
        runtime.events = runtimeEvents()
        runtime.start { ok, msg ->
            started = ok
            if (ok) lastStartAt = System.currentTimeMillis()
            post {
                val ws = startWaiters.toList()
                startWaiters.clear()
                ws.forEach { it(ok, msg) }
            }
            if (ok) {
                refreshSessions { ok2 -> if (ok2) refreshModelOptions() }
                AutomationScheduler.start(this)
            }
        }
    }

    fun restartCore(cb: (Boolean, String) -> Unit) {
        runtime.stopCore()
        started = false
        running = false
        exitCount = 0
        // 稍等旧进程完全退出，避免新旧进程短暂共存后再启动。
        main.postDelayed({
            ensureStarted { ok, msg ->
                if (ok) {
                    currentSessionId?.let { openSession(it, null) }
                    // 配置变更后立即可见：刷新可用模型列表（无会话时读最近快照）。
                    refreshModelOptions()
                }
                cb(ok, msg)
            }
        }, 600)
    }

    private fun runtimeEvents(): CoreRuntime.Events =
        object : CoreRuntime.Events {
            override fun onNotification(
                method: String,
                params: JSONObject,
            ) {
                when (method) {
                    "session/event" -> {
                        handleSessionEvent(params)
                    }

                    "state.updated" -> {
                        handleStateUpdated(params)
                    }

                    else -> {
                    }
                }
            }

            override fun onExit(code: Int) {
                started = false
                running = false
                val now = System.currentTimeMillis()
                val ranFor = if (lastStartAt > 0L) now - lastStartAt else -1L
                if (ranFor > 120_000L) exitCount = 0
                exitCount += 1
                val reason = CoreRuntime.describeExit(code)
                runtime.log("[core] 退出：$reason，本次运行 ${ranFor}ms，连续第 $exitCount 次")
                // 刚拉起就被信号带走的进程，重启只会把同样的退出再演一遍，几秒里刷出十几次相同记录，
                // 真正的原因反被淹没。第二次起不再自动重启，把原因留在提示与日志里。
                val hopeless = ranFor >= 0L && ranFor <= 10_000L && code > 128 && exitCount >= 2
                if (exitCount <= 5 && !autoRestartScheduled && !hopeless) {
                    autoRestartScheduled = true
                    notif { onNotice("核心进程意外退出：$reason，正在自动重启…") }
                    main.postDelayed({
                        autoRestartScheduled = false
                        ensureStarted { ok, msg ->
                            if (ok) {
                                notif { onNotice("核心已自动重启") }
                                currentSessionId?.let { openSession(it, null) }
                            } else {
                                notif { onNotice("自动重启失败：$msg") }
                            }
                        }
                    }, 1200)
                } else if (hopeless) {
                    notif { onNotice("核心进程启动即退出：$reason，已停止自动重启，原因见运行日志") }
                } else {
                    notif { onNotice("核心进程已退出，$reason") }
                }
                notif { onStateChanged() }
            }

            override fun onInteractiveReverse(
                requestId: Any,
                method: String,
                params: JSONObject,
            ): Boolean =
                when (method) {
                    "interaction/requestPermission" -> {
                        runtime.log("[perm] requestPermission tool=${params.optString("toolName")} mode=$mode")
                        if (mode == "yolo") {
                            // 全权模式：自动放行（优先使用选项里的允许应答）
                            val resp = findAllowResponse(params) ?: JSONObject().put("decision", "allow")
                            runtime.respond(requestId, resp)
                            true
                        } else if (listeners.isNotEmpty()) {
                            notif { onPermissionRequest(requestId, params) }
                            true
                        } else {
                            runtime.respond(requestId, findDenyResponse(params) ?: JSONObject().put("decision", "deny"))
                            true
                        }
                    }

                    "interaction/requestUserInput" -> {
                        if (listeners.isNotEmpty()) {
                            notif { onUserInputRequest(requestId, params) }
                            true
                        } else {
                            runtime.respond(requestId, JSONObject().put("action", "decline"))
                            true
                        }
                    }

                    else -> {
                        if (AutomationHost.get(app).handleReverse(runtime, this@ZController, requestId, method, params)) {
                            true
                        } else if (BrowserControl.handleReverse(runtime, requestId, method, params)) {
                            true
                        } else {
                            false
                        }
                    }
                }
        }

    /** 供调度器等内部组件访问应用上下文。 */
    fun appContext(): Context = app

    private fun findAllowResponse(params: JSONObject): JSONObject? {
        val opts = params.optJSONArray("options") ?: return null
        for (i in 0 until opts.length()) {
            val o = opts.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            if (kind.contains("allow")) {
                o.optJSONObject("response")?.let { return it }
            }
        }
        return null
    }

    private fun findDenyResponse(params: JSONObject): JSONObject? {
        val opts = params.optJSONArray("options") ?: return null
        for (i in 0 until opts.length()) {
            val o = opts.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            if (kind.contains("deny") || kind.contains("reject")) {
                o.optJSONObject("response")?.let { return it }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 状态应用

    private fun applySnapshot(root: JSONObject) {
        val sid = ZParse.parseSessionIdFromSnapshot(root) ?: currentSessionId
        if (sid != null) {
            currentSessionId = sid
            val title = ZParse.parseTitleFromSnapshot(root)
            val status = ZParse.parseStatusFromSnapshot(root)
            val m = ZParse.parseModeFromSnapshot(root)
            var info = sessions.find { it.id == sid }
            if (info == null) {
                info = ZSessionInfo(sid, title, status, m, System.currentTimeMillis(), null, null)
                sessions.add(0, info)
            } else {
                info.title = title
                info.status = status
                if (m.isNotEmpty()) info.mode = m
                info.updatedAt = System.currentTimeMillis()
            }
            if (m.isNotEmpty()) mode = m
            if (status == "running" || status == "waiting") running = true else running = false
        }
        messages = ZParse.parseMessages(root).toMutableList()
        // 顺手把消息落到本地缓存，内核退出后还能只读回看。
        // 只在拿到真实数据时写，避免把缓存内容又写回缓存。
        // sid 在这里是可空的（拿不到会话 id 就没有归属，不落盘）。
        val cacheSessionId = sid
        if (!cacheOnly && cacheSessionId != null) {
            SessionCache.saveMessages(app, cacheSessionId, messages)
        }
        val mo = ZParse.parseModelOptions(root)
        // 快照里的 model.available 只含当前模型（内核省流），不能拿它覆盖清单：
        // 覆盖后面板就只剩一个可选，而且会让「列表为空才拉全量」的判断永久失效。
        // 只在还没有任何清单时用它兜底。
        if (mo.isNotEmpty() && modelOptions.isEmpty()) modelOptions = mo
        val tl = ZParse.parseThoughtLevels(root)
        if (tl.isNotEmpty()) thoughtLevels = tl
        ZParse.parseCurrentThoughtLevel(root)?.let { currentThoughtLevel = it }
        ZParse.parseCurrentSelection(root)?.let { currentModel = it }
        val sc = ZParse.parseSlashCommands(root)
        if (sc.isNotEmpty()) slashCommands = sc
        todos = ZParse.parseTodos(root)
        notif { onStateChanged() }
        // 子代理与工作流运行态是独立查询面（不在会话快照里），打开/恢复会话后各拉一次。
        refreshSubagents()
        refreshWorkflowRuns()
    }

    private fun handleStateUpdated(params: JSONObject) {
        val patch = params.optJSONObject("patch") ?: return
        val st = patch.optString("status")
        if (st.isNotEmpty()) running = (st == "running" || st == "waiting")
        patch
            .optJSONObject("mode")
            ?.optString("current")
            ?.takeIf { it.isNotEmpty() }
            ?.let { mode = it }
        patch.optJSONObject("model")?.let { mo ->
            val root = JSONObject().put("settings", JSONObject().put("model", mo))
            val list = ZParse.parseModelOptions(root)
            // 同上：状态 patch 也只带当前模型，不能覆盖全量清单。
            if (list.isNotEmpty() && modelOptions.isEmpty()) modelOptions = list
            mo.optJSONObject("current")?.let { cur ->
                val p = cur.optString("providerId")
                val m = cur.optString("modelId")
                if (p.isNotEmpty() && m.isNotEmpty()) {
                    currentModel = Triple(p, m, cur.optJSONObject("options")?.optString("reasoningLevel"))
                }
            }
        }
        patch.optJSONObject("thoughtLevel")?.let { tl ->
            val root = JSONObject().put("settings", JSONObject().put("thoughtLevel", tl))
            val list = ZParse.parseThoughtLevels(root)
            if (list.isNotEmpty()) thoughtLevels = list
            ZParse.parseCurrentThoughtLevel(root)?.let { currentThoughtLevel = it }
        }
        notif { onStateChanged() }
    }

    private fun handleSessionEvent(params: JSONObject) {
        val sid = params.optString("sessionId")
        if (sid.isNotEmpty() && currentSessionId != null && sid != currentSessionId) return
        val type = params.optString("type")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "turn.started" -> {
                running = true
                scheduleRefreshSoon()
                notif { onStateChanged() }
            }

            "turn.completed" -> {
                running = false
                doRefreshMessages()
                refreshSnapshotQuiet()
                notif { onStateChanged() }
            }

            "turn.failed" -> {
                running = false
                val msg = payload.optJSONObject("error")?.optString("message") ?: "未知错误"
                doRefreshMessages()
                notif {
                    onNotice("回合失败: $msg")
                    onStateChanged()
                }
            }

            "session.titleUpdated" -> {
                val title = payload.optString("title")
                sessions.find { it.id == sid }?.title = title
                notif { onStateChanged() }
            }

            "session.closed" -> {
                refreshSessions(null)
            }

            "session.updated" -> {
                if (running) scheduleRefreshSoon()
            }

            "model.streaming" -> {
                val kind = payload.optString("kind")
                val delta = payload.optString("delta")
                val mid = payload.optString("assistantMessageId")
                if (kind == "tool_input_delta") linkParentToolUse(payload)
                when (kind) {
                    "text_delta" -> {
                        if (delta.isNotEmpty()) {
                            notif { onDelta(mid, delta) }
                            scheduleRefreshSoon()
                        }
                    }

                    "reasoning_delta" -> {
                        if (delta.isNotEmpty()) {
                            notif { onReasoningDelta(mid, delta) }
                        }
                    }
                }
            }

            "tool.updated" -> {
                linkParentToolUse(payload)
                val kind = payload.optString("kind")
                if (kind == "result" || kind == "error" || kind == "batch") {
                    scheduleRefreshSoon()
                }
            }

            // 工作流实时进度：actor 出生时记下它的子会话，之后的卡片就能点进去看输出。
            // 其余几种只更新"正在做什么"，不触发消息重拉（重拉会中断滚动与流式文本）。
            "dynamic_workflow_run_progress" -> {
                onWorkflowProgress(payload)
            }
        }
    }

    /**
     * 处理一条工作流进度事件。
     *
     * 唯一必须落库的是 actorSessionId：它是查看某个子智能体输出的钥匙，且只在
     * actor-created / node-dispatched 上出现一次，丢了就只能等下一轮花名册。
     */
    private fun onWorkflowProgress(payload: JSONObject) {
        val runId = payload.optString("runId")
        if (runId.isEmpty()) return
        val eventType = payload.optString("eventType")
        val inner = payload.optJSONObject("payload") ?: JSONObject()
        val actorSessionId = payload.optString("actorSessionId").takeIf { it.isNotEmpty() }
        val address = ZWorkflowKit.actorAddressOf(inner)
        if (actorSessionId != null && address.isNotEmpty()) {
            actorSessions[ZWorkflowKit.actorKey(runId, address)] = actorSessionId
        }
        val live = ZWorkflowKit.liveFromProgress(runId, eventType, inner, address, actorSessionId)
        post {
            if (live != null) workflowLive[runId] = live
            // 只通知工作流面板：主聊天页的整页重渲染留给真正影响消息的事件，
            // 否则每来一条进度都会把聊天视图重建一次（并可能打断滚动）。
            for (w in workflowWatchers) w.onWorkflowProgress(runId)
        }
        // 结算说明 run 状态变了（可能从 running 变 cancelled），花名册要重拉一次；
        // 这里只拉一次摘要，不重拉消息。
        if (eventType == "run-settled") refreshWorkflowRuns()
    }

    /**
     * 解析流式事件里的 parentToolUseId 关联。镜像到父会话的子代理工具事件同时带 childSessionId 与
     * parentToolCallId，因此可以直接建立「父 Agent 工具调用 ↔ 子会话」与「子工具调用 → 父调用」两种
     * 映射；花名册刷新存在延迟或并发子代理时，靠这条映射仍能定位子会话。
     */
    private fun linkParentToolUse(payload: JSONObject) {
        val parent = ZWorkflowKit.parentToolUseIdOf(payload)
        if (parent.isEmpty()) return
        val toolCallId =
            payload
                .optString("toolCallId")
                .ifEmpty { payload.optString("childToolCallId") }
                .ifEmpty { payload.optString("callID") }
        if (toolCallId.isNotEmpty()) toolCallToParentCall[toolCallId] = parent
        val childSessionId = payload.optString("childSessionId")
        if (childSessionId.isNotEmpty()) parentCallToChild[parent] = childSessionId
    }

    /** 回合结束后的静默快照刷新（同步待办等快照级状态），不切换会话、不触发打开回调。 */
    private var snapshotRefreshing = false

    private fun refreshSnapshotQuiet() {
        val sid = currentSessionId ?: return
        if (snapshotRefreshing) return
        snapshotRefreshing = true
        val params =
            JSONObject()
                .put("sessionId", sid)
                .put("deliveryKind", "desktop-continuous")
                .put("includeSnapshot", true)
        runtime.call("session/subscribe", params) { ok, body ->
            snapshotRefreshing = false
            if (!ok) return@call
            val snap = body.optJSONObject("snapshot") ?: return@call
            post {
                if (currentSessionId == sid) applySnapshot(snap)
            }
        }
    }

    // ---------------------------------------------------------------- 刷新

    private fun scheduleRefreshSoon() {
        if (refreshScheduled) return
        refreshScheduled = true
        main.postDelayed({
            refreshScheduled = false
            doRefreshMessages()
        }, 400)
    }

    private fun doRefreshMessages() {
        val sid = currentSessionId ?: return
        if (refreshing) {
            refreshAgain = true
            return
        }
        refreshing = true
        runtime.call("session/messages", JSONObject().put("sessionId", sid)) { ok, body ->
            refreshing = false
            if (ok) {
                val list = ZParse.parseMessages(body)
                if (!cacheOnly) SessionCache.saveMessages(app, sid, list)
                post {
                    messages = list.toMutableList()
                    for (l in listeners) l.onStateChanged()
                }
                // 工具结果变化往往意味着子代理起停，顺手对齐一次花名册。
                refreshSubagents()
            }
            if (refreshAgain) {
                refreshAgain = false
                scheduleRefreshSoon()
            }
        }
    }

    // ---------------------------------------------------------------- 子代理 / 工作流

    /**
     * 拉取当前会话的子代理清单（session/subagents）。
     * 子代理卡靠 toolCallId 把 Agent 工具调用与子会话对齐，从而能展示「子代理正在进行的事」。
     */
    fun refreshSubagents() {
        val sid = currentSessionId ?: return
        val params =
            JSONObject()
                .put("sessionId", sid)
                .put("endedLimit", 20)
        runtime.call("session/subagents", params) { ok, body ->
            if (ok) {
                val list = ZWorkflowKit.parseSubagents(body)
                post {
                    subagents = list
                    for (l in listeners) l.onStateChanged()
                }
            }
        }
    }

    /** 拉取当前工作区的工作流运行摘要（workflows/runs）。 */
    fun refreshWorkflowRuns() {
        val params =
            JSONObject()
                .put("workspace", workspaceRef())
                .put("limit", 20)
        runtime.call("workflows/runs", params) { ok, body ->
            if (ok) {
                val list = ZWorkflowKit.parseWorkflowRuns(body)
                post {
                    workflowRuns = list
                    for (l in listeners) l.onStateChanged()
                }
            }
        }
    }

    /** Agent 工具调用 → 对应子代理（按 toolCallId 联接）。 */
    fun subagentForToolCall(callId: String?): ZSubagent? {
        if (callId.isNullOrEmpty()) return null
        return subagents.firstOrNull { it.toolCallId == callId }
    }

    /** 某个工作流 run 最近一条有信息量的进度（折叠卡与详情页共用）。 */
    fun liveOfWorkflow(runId: String?): ZWorkflowLive? {
        if (runId.isNullOrEmpty()) return null
        return workflowLive[runId]
    }

    /**
     * 工作流里某个 actor 的子会话 id。
     *
     * [address] 是花名册里的 `siteId@ordinal`；取不到就返回 null（调用方据此提示"该子智能体
     * 还没有独立会话"，而不是打开一个空面板）。
     */
    fun actorSessionOf(
        runId: String?,
        address: String?,
    ): String? {
        if (runId.isNullOrEmpty() || address.isNullOrEmpty()) return null
        return actorSessions[ZWorkflowKit.actorKey(runId, address)]
    }

    /** 当前会话里是否已经收到过某个 run 的实时进度（用来决定卡片要不要显示"实时"标记）。 */
    fun hasLiveWorkflow(runId: String?): Boolean = !runId.isNullOrEmpty() && workflowLive.containsKey(runId)

    /** CreateWorkflow 工具调用 → 对应运行摘要（按 toolCallId 联接）。 */
    fun workflowRunForToolCall(callId: String?): ZWorkflowRun? {
        if (callId.isNullOrEmpty()) return null
        return workflowRuns.firstOrNull { it.toolCallId == callId }
    }

    /**
     * 读取子代理子会话的消息（只读）。半屏的整页会话视图直接复用主界面渲染，因此这里取原始消息
     * 而不是摘要行；结果缓存在 childMessages 里，重渲染时无需再次请求内核。
     */
    fun loadChildMessages(
        childSessionId: String,
        cb: ((List<ZMessage>?) -> Unit)? = null,
    ) {
        if (childSessionId.isEmpty()) {
            post { cb?.invoke(emptyList()) }
            return
        }
        // 同一子会话只允许一个在途读取：冷恢复可能耗时数秒，3 秒跟随刷新会重复触发同一会话。
        if (!childLoading.add(childSessionId)) return

        fun settle(msgs: List<ZMessage>?) {
            post {
                childLoading.remove(childSessionId)
                if (msgs != null) {
                    childMessages[childSessionId] = msgs
                    childMessageFailures.remove(childSessionId)
                } else {
                    childMessageFailures.add(childSessionId)
                }
                cb?.invoke(msgs)
            }
        }
        readChildMessages(childSessionId) { msgs ->
            if (msgs != null) {
                settle(msgs)
                return@readChildMessages
            }
            // 子代理子会话是内核里的 detached child，不在协议层会话表里，直接读会返回
            // 「Session is not active」。冷恢复（session/resume）会把它按 subagent_child 从
            // session store 物化出来，之后 session/messages 才有数据。
            resumeSession(childSessionId) { ok, _ ->
                if (!ok) {
                    settle(null)
                    return@resumeSession
                }
                readChildMessages(childSessionId) { again -> settle(again) }
            }
        }
    }

    /** 单次 session/messages 读取；失败返回 null（区别于「读到了空列表」）。 */
    private fun readChildMessages(
        childSessionId: String,
        cb: (List<ZMessage>?) -> Unit,
    ) {
        runtime.call("session/messages", JSONObject().put("sessionId", childSessionId)) { ok, body ->
            cb(if (ok) ZParse.parseMessages(body) else null)
        }
    }

    /** 已缓存的子会话消息；未拉取过时返回 null（用于区分「加载中」与「确实为空」）。 */
    fun cachedChildMessages(childSessionId: String?): List<ZMessage>? {
        if (childSessionId.isNullOrEmpty()) return null
        return childMessages[childSessionId]
    }

    /** 该子会话消息读取失败过（多半是内核未激活该子会话），用于避免把失败当成「确实为空」。 */
    fun childMessagesFailed(childSessionId: String?): Boolean {
        if (childSessionId.isNullOrEmpty()) return false
        return childMessageFailures.contains(childSessionId)
    }

    /**
     * 工具调用 → 子会话 id。优先用内核花名册，其次用流式事件里解析出的 parentToolCallId 关联，
     * 两者都拿不到时返回 null。
     */
    fun childSessionIdForToolCall(callId: String?): String? {
        val fromRoster = subagentForToolCall(callId)?.childSessionId
        if (!fromRoster.isNullOrEmpty()) return fromRoster
        if (callId.isNullOrEmpty()) return null
        return parentCallToChild[callId]
    }

    /** 该工具调用所属的父 Agent 工具调用 id（由流式事件的 parentToolUseId 解析而来）。 */
    fun parentCallOfToolCall(callId: String?): String? {
        if (callId.isNullOrEmpty()) return null
        return toolCallToParentCall[callId]
    }

    //
    // 用本地缓存撑起界面。
    //
    // 内核不可用（冷启动、崩溃、更新包替换中）时调。列表与消息都从缓存来，
    // 并置 [cacheOnly]，上层据此把界面切成只读。返回 false 表示连缓存都没有。
    //

    /**
     * 直接进入只读态。
     *
     * 用在「内核起不来，但界面上已经有内容」的情形：那些内容不管是刚读的缓存
     * 还是启动前拿到的真实数据，都已经发不出去请求了，必须切成只读。
     */
    fun enterReadOnly() {
        post {
            cacheOnly = true
            for (l in listeners) l.onStateChanged()
        }
    }

    fun restoreFromCache(): Boolean {
        if (sessions.isNotEmpty()) return false
        val cached = SessionCache.loadSessions(app)
        if (cached.isEmpty()) return false
        post {
            cacheOnly = true
            sessions = cached.toMutableList()
            for (l in listeners) l.onStateChanged()
        }
        return true
    }

    /**
     * 只读打开一个缓存会话：把缓存消息填进来，全程不碰内核。
     *
     * 刻意不改工作区、不发任何请求——内核没起来时任何请求都会失败，
     * 还会在界面上报一串错。这里就是要让用户安静地看一眼上次聊了什么。
     */
    fun openCachedSession(sessionId: String): Boolean {
        val cached = SessionCache.loadMessages(app, sessionId)
        if (cached.isEmpty()) return false
        post {
            cacheOnly = true
            currentSessionId = sessionId
            messages = cached.toMutableList()
            for (l in listeners) l.onStateChanged()
        }
        return true
    }

    fun refreshSessions(cb: ((Boolean) -> Unit)?) {
        val params =
            JSONObject()
                .put("workspace", workspaceRef())
                .put("includeArchived", false)
        runtime.call("session/list", params) { ok, body ->
            if (ok) {
                val list = ZParse.parseSessionInfos(body)
                // 拿到了真实列表：说明内核在线，退出只读态，并把这份快照存下来
                // 供下次内核没起来时展示。
                SessionCache.saveSessions(app, list)
                post {
                    cacheOnly = false
                    sessions = list.toMutableList()
                    for (l in listeners) l.onStateChanged()
                }
            } else {
                // 内核不可用：用缓存兜底，让用户至少能看见上次的会话。
                restoreFromCache()
            }
            cb?.invoke(ok)
        }
    }

    /** 拉取 workspace 级表现（含斜杠命令目录）：无会话时也能拿到内置命令表。 */
    fun refreshWorkspacePresentation(cb: ((Boolean) -> Unit)? = null) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("workspace/readPresentation", params) { ok, body ->
            if (ok) {
                val sc = ZParse.parseSlashCommands(body)
                if (sc.isNotEmpty()) {
                    post {
                        slashCommands = sc
                        notif { onStateChanged() }
                    }
                }
            }
            cb?.invoke(ok)
        }
    }

    /**
     * 主动刷新可用模型列表：读取当前（或最近一条）会话的快照。
     * 用于“重启核心后 / 未打开会话时”的模型面板展示（只读，不切换会话）。
     */
    fun refreshModelOptions(cb: ((Boolean) -> Unit)? = null) {
        val sid = currentSessionId ?: sessions.firstOrNull()?.id
        if (sid == null) {
            refreshSessions { ok ->
                val next = if (ok) sessions.firstOrNull()?.id else null
                if (next == null) {
                    cb?.invoke(false)
                } else {
                    readModelOptions(next) { ok2 -> if (ok2) cb?.invoke(true) else readModelOptionsAfterResume(next, cb) }
                }
            }
            return
        }
        readModelOptions(sid) { ok -> if (ok) cb?.invoke(true) else readModelOptionsAfterResume(sid, cb) }
    }

    /** 冷会话读取失败：先恢复（resume）再读一次（与桌面端冷恢复语义一致）。 */
    private fun readModelOptionsAfterResume(
        sessionId: String,
        cb: ((Boolean) -> Unit)?,
    ) {
        resumeSession(sessionId) { rok, _ ->
            if (!rok) {
                cb?.invoke(false)
                return@resumeSession
            }
            readModelOptions(sessionId, cb)
        }
    }

    private fun readModelOptions(
        sessionId: String,
        cb: ((Boolean) -> Unit)?,
    ) {
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("deliveryKind", "desktop-continuous")
                // 要全量模型清单：模型选择面板按它构建列表。内核默认只回当前模型一个
                // （快照省流），这里必须显式声明 "all"，否则面板永远只有一个可选。
                .put("modelAvailability", "all")
        runtime.call("session/read", params) { ok, body ->
            var changed = false
            if (ok) {
                val mo = ZParse.parseModelOptions(body)
                if (mo.isNotEmpty()) {
                    post {
                        modelOptions = mo
                        notif { onStateChanged() }
                    }
                    changed = true
                }
            }
            cb?.invoke(changed)
        }
    }

    /** 使用统计数据（range: "7d" | "30d" | "all"）。 */
    fun fetchUsageStats(
        range: String,
        cb: (Boolean, JSONObject?) -> Unit,
    ) {
        runtime.call("usage/stats", JSONObject().put("range", range)) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 插件列表。 */
    fun fetchPlugins(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("plugins/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** MCP 服务器状态列表（mode: status 只读快照，不做连接操作）。 */
    fun fetchMcpList(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("mode", "status")
        runtime.call("mcp/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 启用 / 停用插件。 */
    fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginId", pluginId)
                .put("enabled", enabled)
        runtime.call("plugins/setEnabled", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 插件总览：市场 / 可用插件 / 已安装 / 可恢复内置。 */
    fun fetchPluginsOverview(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("plugins/overview", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 安装插件。 */
    fun installPlugin(
        pluginName: String,
        marketplace: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginName", pluginName)
                .put("marketplace", marketplace)
        runtime.call("plugins/install", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 卸载插件。 */
    fun uninstallPlugin(
        pluginId: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginId", pluginId)
        runtime.call("plugins/uninstall", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 更新插件（pluginId 或整个 marketplace，二选一）。 */
    fun updatePlugin(
        pluginId: String?,
        marketplace: String?,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        pluginId?.let { params.put("pluginId", it) }
        marketplace?.let { params.put("marketplace", it) }
        runtime.call("plugins/update", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 写入插件配置项（options 为键值对；clearOptionKeys 用于清除选项）。 */
    fun configurePlugin(
        pluginId: String,
        options: JSONObject,
        clearKeys: List<String>,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginId", pluginId)
                .put("options", options)
        if (clearKeys.isNotEmpty()) params.put("clearOptionKeys", JSONArray(clearKeys))
        runtime.call("plugins/configure", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 重置插件配置为默认值。 */
    fun resetPluginConfig(
        pluginId: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginId", pluginId)
        runtime.call("plugins/resetConfig", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 添加插件市场源。 */
    fun addPluginMarketplace(
        source: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("source", source)
        runtime.call("plugins/marketplace/add", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 移除插件市场。 */
    fun removePluginMarketplace(
        marketplace: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("marketplace", marketplace)
        runtime.call("plugins/marketplace/remove", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 刷新插件市场索引（marketplace 为空时刷新全部）。 */
    fun updatePluginMarketplace(
        marketplace: String?,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        marketplace?.let { params.put("marketplace", it) }
        runtime.call("plugins/marketplace/update", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 恢复内置插件。 */
    fun restoreBuiltinPlugin(
        pluginId: String,
        cb: (Boolean, String) -> Unit,
    ) {
        val wp = workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("pluginId", pluginId)
        runtime.call("plugins/restoreBuiltin", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    // ---------------------------------------------------------------- 会话操作

    fun workspacePath(): String = Workspaces.root(app).absolutePath

    /**
     * 新建会话。
     *
     * [workspaceOverride] 非空时用它当工作区，否则落到默认工作区。
     * 客户端的新会话页允许用户先选/建一个工作区再发第一条消息，
     * 那个选择只能从这条口子进来——会话一旦建立，工作区就由内核绑定，
     * 不该再由这个选择器改动。
     */
    fun newSession(
        workspaceOverride: String? = null,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val wp = workspaceOverride?.takeIf { it.isNotBlank() } ?: workspacePath()
        val params =
            JSONObject()
                .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
                .put("mode", prefs.getString("default_mode", "build"))
                .put("titleGenerationEnabled", false)
        val sel = buildModelSelection()
        if (sel != null) params.put("model", sel)
        runtime.call("session/create", params) { ok, body ->
            if (ok) {
                post {
                    applySnapshot(body)
                    // 订阅并载入消息；主线程、且 currentSessionId 就绪后再触发回调
                    val sid = ZParse.parseSessionIdFromSnapshot(body)
                    if (sid != null) openSession(sid, null)
                    cb?.invoke(true, "ok")
                }
            } else {
                post { cb?.invoke(false, errMsg(body)) }
                notif { onNotice("新建会话失败: ${errMsg(body)}") }
            }
        }
    }

    fun openSession(
        sessionId: String,
        cb: ((Boolean) -> Unit)?,
    ) {
        // 只读态直接读缓存：内核没起来，订阅请求必然失败，
        // 先发一次再回退只会白等一轮超时，还会闪一下错误提示。
        if (cacheOnly && openCachedSession(sessionId)) {
            cb?.invoke(true)
            return
        }
        subscribeSession(sessionId) { ok, msg ->
            if (ok) {
                cb?.invoke(true)
                return@subscribeSession
            }
            // 冷会话（内核要求先恢复才能订阅）：按桌面端流程先 session/resume 再订阅
            if (msg != null && msg.contains("not active", ignoreCase = true)) {
                resumeSession(sessionId) { rok, rmsg ->
                    if (rok) {
                        subscribeSession(sessionId) { ok2, msg2 ->
                            if (!ok2 && msg2 != null) notif { onNotice("打开会话失败: $msg2") }
                            cb?.invoke(ok2)
                        }
                    } else {
                        notif { onNotice("恢复会话失败: ${rmsg ?: "未知错误"}") }
                        cb?.invoke(false)
                    }
                }
            } else {
                // 只读态下的会话本来就不能订阅（内核没起来），直接读缓存展示，
                // 不要在这里弹一个用户无法处理的错误。
                if (cacheOnly && openCachedSession(sessionId)) {
                    cb?.invoke(true)
                    return@subscribeSession
                }
                if (msg != null) notif { onNotice("打开会话失败: $msg") }
                cb?.invoke(false)
            }
        }
    }

    /** 订阅会话（打开会话的第二段；冷会话需先 resumeSession 恢复）。 */
    private fun subscribeSession(
        sessionId: String,
        cb: ((Boolean, String?) -> Unit)?,
    ) {
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("deliveryKind", "desktop-continuous")
                .put("includeSnapshot", true)
        runtime.call("session/subscribe", params) { ok, body ->
            if (ok) {
                post {
                    currentSessionId = sessionId
                    body.optJSONObject("snapshot")?.let { applySnapshot(it) }
                    notif {
                        onSessionOpened(sessionId)
                        onStateChanged()
                    }
                    // 主线程、且 currentSessionId 已就绪后再拉消息、再触发回调
                    doRefreshMessages()
                    cb?.invoke(true, null)
                }
            } else {
                post { cb?.invoke(false, errMsg(body)) }
            }
        }
    }

    /**
     * 恢复历史会话（桌面端 session/resume 语义）：
     * 冷会话必须先恢复（激活 runtime）后才能订阅、读取与发送。
     */
    fun resumeSession(
        sessionId: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("workspace", workspaceRef())
        runtime.call("session/resume", params) { ok, body ->
            if (ok) {
                post { cb?.invoke(true, "ok") }
            } else {
                post { cb?.invoke(false, errMsg(body)) }
            }
        }
    }

    /** 统一的 workspace 引用（对齐桌面端 buildWorkspaceRef）。 */
    private fun workspaceRef(): JSONObject {
        val wp = workspacePath()
        return JSONObject().put("workspacePath", wp).put("workspaceKey", wp)
    }

    /**
     * 变更会话所属的工作区。工作区是会话的附属产物，改绑只改会话的归属字段，不新建工作区实体。
     *
     * 旧归属由内核从持久化行读取并做 CAS（客户端不传旧值）；已激活会话会被内核整体拆除，
     * 拆除后事件流与 runtime 都按旧工作区建立，不能续用，故客户端同步放开当前会话。
     * 回调第二参：ok / closed（原会话被拆除）/ 错误文本。
     */
    fun rebindSessionWorkspace(
        sessionId: String,
        targetWorkspacePath: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val path = targetWorkspacePath.trim()
        if (path.isEmpty()) {
            cb?.invoke(false, "工作区路径为空")
            return
        }
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("workspace", JSONObject().put("workspacePath", path).put("workspaceKey", path))
        runtime.call("session/rebindWorkspace", params) { ok, body ->
            if (!ok) {
                post { cb?.invoke(false, errMsg(body)) }
                return@call
            }
            val closedActive = body.optBoolean("closedActiveSession", false)
            post {
                if (closedActive && currentSessionId == sessionId) currentSessionId = null
                refreshSessions(null)
                cb?.invoke(true, if (closedActive) "closed" else "ok")
            }
        }
    }

    fun closeSession(
        sessionId: String,
        cb: ((Boolean) -> Unit)? = null,
    ) {
        runtime.call("session/close", JSONObject().put("sessionId", sessionId)) { _, _ ->
            if (currentSessionId == sessionId) currentSessionId = null
            refreshSessions(null)
            cb?.invoke(true)
        }
    }

    fun send(
        text: String,
        attachments: JSONArray? = null,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "没有打开的会话")
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() && (attachments == null || attachments.length() == 0)) {
            cb?.invoke(false, "内容为空")
            return
        }
        if (trimmed == "/compact" || trimmed.startsWith("/compact ")) {
            compact(trimmed.removePrefix("/compact").trim().takeIf { it.isNotEmpty() })
            cb?.invoke(true, "ok")
            return
        }
        doSend(sid, text, attachments, retried = false, cb = cb)
    }

    /** 发送请求（对齐桌面端：inputId/queryId/modelSelection/attachments）；冷会话失败时自动恢复一次后重试。 */
    private fun doSend(
        sid: String,
        text: String,
        attachments: JSONArray?,
        retried: Boolean,
        cb: ((Boolean, String) -> Unit)?,
    ) {
        val params = JSONObject().put("sessionId", sid).put("content", text)
        params.put("inputId", UUID.randomUUID().toString())
        params.put("queryId", UUID.randomUUID().toString())
        if (attachments != null && attachments.length() > 0) {
            params.put("attachments", attachments)
        }
        val sel = buildModelSelection()
        if (sel != null) params.put("modelSelection", sel)
        runtime.call("session/send", params) { ok, body ->
            if (ok) {
                post {
                    running = true
                    scheduleRefreshSoon()
                    notif { onStateChanged() }
                    cb?.invoke(true, "ok")
                }
            } else {
                val msg = errMsg(body)
                // 冷会话被回收：恢复一次后重试（与桌面端 resume 语义一致）
                if (!retried && msg.contains("not active", ignoreCase = true)) {
                    resumeSession(sid) { rok, _ ->
                        if (rok) {
                            doSend(sid, text, attachments, retried = true, cb = cb)
                        } else {
                            notif { onNotice("发送失败: $msg") }
                            cb?.invoke(false, msg)
                        }
                    }
                } else {
                    notif { onNotice("发送失败: $msg") }
                    cb?.invoke(false, msg)
                }
            }
        }
    }

    /**
     * 读取会话附件内容（v4/attachment/read 分块拼接）。
     * 用于消息里图片附件的预览；回调在主线程，bytes 为 null 表示失败。
     */
    fun readAttachment(
        ref: String,
        cb: (Boolean, ByteArray?) -> Unit,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            post { cb(false, null) }
            return
        }
        val maxBytes = 20 * 1024 * 1024
        val chunkLimit = 512 * 1024
        val out = java.io.ByteArrayOutputStream()

        fun step(offset: Long) {
            val params =
                JSONObject()
                    .put("sessionId", sid)
                    .put("ref", ref)
                    .put("offset", offset)
                    .put("limit", chunkLimit)
            runtime.call("v4/attachment/read", params) { ok, body ->
                if (!ok || body == null) {
                    post { cb(false, null) }
                    return@call
                }
                val bytes =
                    try {
                        android.util.Base64.decode(body.optString("dataBase64"), android.util.Base64.DEFAULT)
                    } catch (_: Throwable) {
                        null
                    }
                if (bytes == null) {
                    post { cb(false, null) }
                    return@call
                }
                out.write(bytes)
                val nextRaw = body.opt("nextOffset")
                if (nextRaw == null || nextRaw == JSONObject.NULL || out.size() >= maxBytes) {
                    val data = out.toByteArray()
                    post { cb(true, data) }
                } else {
                    step(body.optLong("nextOffset", 0L))
                }
            }
        }
        step(0L)
    }

    fun stop(cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        // 终止：优先 legacy session/stop（内核对该命令有越队处理），失败再回退 v4/command
        runtime.call("session/stop", JSONObject().put("sessionId", sid)) { ok, body ->
            if (ok) {
                post {
                    running = false
                    notif { onStateChanged() }
                }
                cb?.invoke(true, "ok")
            } else {
                val cmd =
                    JSONObject()
                        .put("commandId", UUID.randomUUID().toString())
                        .put("clientId", "mobile-ui")
                        .put("sessionId", sid)
                        .put("type", "stop")
                        .put("payload", JSONObject())
                        .put("issuedAt", System.currentTimeMillis())
                runtime.call("v4/command", cmd) { ok2, body2 ->
                    cb?.invoke(ok2, if (ok2) "ok" else errMsg(body2))
                }
            }
        }
    }

    fun compact(
        instructions: String? = null,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        val params = JSONObject().put("sessionId", sid)
        params.put("inputId", UUID.randomUUID().toString())
        if (!instructions.isNullOrEmpty()) params.put("instructions", instructions)
        runtime.call("session/compact", params) { ok, body ->
            cb?.invoke(ok, if (ok) "ok" else errMsg(body))
        }
    }

    // ------------------------------------------------------------ 工作流控制

    /**
     * 停止一个正在跑的工作流 run。
     *
     * 内核没有专属的工作流取消 RPC：详情页与后台面板的停止共用 v4 `cancelBackgroundWork`，
     * 载荷 workId ≡ runId。停下之后 run 变成 cancelled，可以用 [resumeWorkflowRun] 恢复。
     */
    fun cancelWorkflowRun(
        runId: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null || runId.isEmpty()) {
            cb?.invoke(false, "无会话")
            return
        }
        sendV4("cancelBackgroundWork", sid, JSONObject().put("workId", runId)) { ok, msg ->
            if (ok) refreshWorkflowRuns()
            cb?.invoke(ok, msg)
        }
    }

    /**
     * 继续一个被停下或进程中断的 run。
     *
     * 内核的门：只有 cancelled，或失败编码为 Interrupted 的 run 可恢复；其余情况回
     * `workflowRunResumeRejected.<reason>`，这里把 reason 原样带给调用方，由 UI 决定文案。
     */
    fun resumeWorkflowRun(
        runId: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null || runId.isEmpty()) {
            cb?.invoke(false, "无会话")
            return
        }
        sendV4("resumeWorkflowRun", sid, JSONObject().put("workId", runId)) { ok, msg ->
            if (ok) refreshWorkflowRuns()
            cb?.invoke(ok, msg)
        }
    }

    /**
     * 发一条 v4 命令并归一结果。
     *
     * 与 [stop] 里那段内联命令同一个形状（commandId/clientId/issuedAt 由宿主校验）；
     * 领域拒绝会带 reasonCode，网关把可读说明放在 message 里，这里优先取它。
     */
    private fun sendV4(
        type: String,
        sid: String,
        payload: JSONObject,
        cb: (Boolean, String) -> Unit,
    ) {
        val cmd =
            JSONObject()
                .put("commandId", UUID.randomUUID().toString())
                .put("clientId", "mobile-ui")
                .put("sessionId", sid)
                .put("type", type)
                .put("payload", payload)
                .put("issuedAt", System.currentTimeMillis())
        runtime.call("v4/command", cmd) { ok, body ->
            if (ok) {
                cb(true, "ok")
            } else {
                // 拒绝原因优先用具体 reason（not_resumable / already_running 等），便于 UI 精准提示
                val reason = body.optString("reasonCode")
                val msg = errMsg(body)
                cb(false, reason.substringAfterLast('.').takeIf { it.isNotEmpty() } ?: msg)
            }
        }
    }

    // ---------------------------------------------------------------- 会话设置

    fun setMode(
        protocolMode: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            mode = protocolMode
            cb?.invoke(true, "ok")
            return
        }
        doSetMode(sid, protocolMode, retried = false, cb = cb)
    }

    /** 设置发送模式；冷会话失败时自动恢复一次后重试（与发送同策略）。 */
    private fun doSetMode(
        sid: String,
        protocolMode: String,
        retried: Boolean,
        cb: ((Boolean, String) -> Unit)?,
    ) {
        runtime.call("session/setMode", JSONObject().put("sessionId", sid).put("mode", protocolMode)) { ok, body ->
            if (ok) {
                post {
                    mode = protocolMode
                    applySnapshot(body)
                }
                cb?.invoke(true, "ok")
            } else {
                val msg = errMsg(body)
                if (!retried && msg.contains("not active", ignoreCase = true)) {
                    resumeSession(sid) { rok, _ ->
                        if (rok) {
                            doSetMode(sid, protocolMode, retried = true, cb = cb)
                        } else {
                            cb?.invoke(false, msg)
                        }
                    }
                } else {
                    cb?.invoke(false, msg)
                }
            }
        }
    }

    fun setThoughtLevel(
        value: String,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        val params =
            JSONObject()
                .put("sessionId", sid)
                .put("thoughtLevel", value)
                .put("persistAsWorkspaceLastUsed", true)
        runtime.call("session/setThoughtLevel", params) { ok, body ->
            if (ok) {
                currentThoughtLevel = value
                post { applySnapshot(body) }
                cb?.invoke(true, "ok")
            } else {
                cb?.invoke(false, errMsg(body))
            }
        }
    }

    fun setModel(
        providerId: String,
        modelId: String,
        level: String? = null,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        val lv =
            level
                ?: modelOptions
                    .find { it.providerId == providerId && it.modelId == modelId }
                    ?.let { it.defaultLevel ?: it.levels.firstOrNull()?.value }
                ?: currentThoughtLevel
        if (lv.isNullOrEmpty()) {
            cb?.invoke(false, "该模型未声明推理档位")
            return
        }
        val model =
            JSONObject()
                .put("providerId", providerId)
                .put("modelId", modelId)
                .put("options", JSONObject().put("reasoningLevel", lv))
        val params =
            JSONObject()
                .put("sessionId", sid)
                .put("model", model)
                .put("persistAsWorkspaceLastUsed", true)
        runtime.call("session/setModel", params) { ok, body ->
            if (ok) {
                rememberSelection(providerId, modelId, lv)
                post { applySnapshot(body) }
                cb?.invoke(true, "ok")
            } else {
                cb?.invoke(false, errMsg(body))
            }
        }
    }

    // ---------------------------------------------------------------- 选择与偏好

    private fun rememberSelection(
        providerId: String,
        modelId: String,
        level: String?,
    ) {
        prefs
            .edit()
            .putString("last_provider", providerId)
            .putString("last_model", modelId)
            .putString("last_level", level ?: "")
            .apply()
    }

    fun buildModelSelection(): JSONObject? {
        val cur = currentModel
        var p = cur?.first ?: prefs.getString("last_provider", "") ?: ""
        var m = cur?.second ?: prefs.getString("last_model", "") ?: ""
        var lv = cur?.third
        if (p.isEmpty() || m.isEmpty()) {
            // 还没有任何选择记录：从可用模型里取第一个作为候选（首次配置供应商后的场景）
            val first = modelOptions.firstOrNull() ?: return null
            p = first.providerId
            m = first.modelId
            lv = first.defaultLevel ?: first.levels.firstOrNull()?.value
        }
        if (lv.isNullOrEmpty()) {
            lv = currentThoughtLevel
                ?: prefs.getString("last_level", "")?.takeIf { it.isNotEmpty() }
                ?: modelOptions
                    .find { it.providerId == p && it.modelId == m }
                    ?.let { it.defaultLevel ?: it.levels.firstOrNull()?.value }
        }
        val o = JSONObject().put("providerId", p).put("modelId", m)
        if (!lv.isNullOrEmpty()) {
            o.put("options", JSONObject().put("reasoningLevel", lv))
        }
        return o
    }

    fun setDefaultMode(protocolMode: String) {
        prefs.edit().putString("default_mode", protocolMode).apply()
    }

    fun defaultMode(): String = prefs.getString("default_mode", "build") ?: "build"

    private fun errMsg(body: JSONObject): String = body.optString("message").ifEmpty { body.toString().take(200) }

    companion object {
        @Volatile
        private var instance: ZController? = null

        fun get(context: Context): ZController =
            instance ?: synchronized(this) {
                instance ?: ZController(context.applicationContext).also { instance = it }
            }
    }
}

/** 发送模式文案与协议值映射。 */
object SendModes {
    val names = arrayOf("Yolo", "Build", "Chat")

    fun toProtocol(uiName: String): String =
        when (uiName) {
            "Yolo" -> "yolo"
            "Chat" -> "plan"
            else -> "build"
        }

    fun toUiIndex(protocolMode: String): Int =
        when (protocolMode) {
            "yolo" -> 0
            "plan" -> 2
            else -> 1
        }
}
