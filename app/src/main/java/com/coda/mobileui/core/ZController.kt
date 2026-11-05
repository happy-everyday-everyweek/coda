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
 * 会话与消息的唯一 UI 侧聚合控制器。
 *
 * 管理快照、会话路由、请求审批。
 */
class ZController private constructor(
    private val app: Context,
) {
    interface Listener {
        /** 状态变化（会话/消息/工作流）需要刷新。 */
        fun onStateChanged() {}

        /** 流式文本增量：assistantMessageId 对应某条助手消息。 */
        fun onDelta(
            assistantMessageId: String,
            text: String,
        ) {}

        /** 流式推理增量：assistantMessageId 对应某条助手消息。 */
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

        /** 为需要区分 Coding Agent 会话的监听器提供审批上下文。 */
        fun onSessionPermissionRequest(
            sessionId: String,
            requestId: Any,
            params: JSONObject,
        ) {
            onPermissionRequest(requestId, params)
        }

        /** 任意会话的回合结束，不限于当前打开的会话。 */
        fun onSessionTurnFinished(
            sessionId: String,
            status: String,
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
     * 工作流实时观察者。
     *
     * 监听者只读聚合视图状态，工作流组件通过 [Listener.onStateChanged] 收到刷新通知；
     * 需要监听事件时，先调用 addWorkflowWatcher。这些接口被工作流组件使用，
     * 不要误判为无用代码。
     */
    interface WorkflowWatcher {
        fun onWorkflowProgress(runId: String)
    }

    val runtime = CoreRuntime(app)

    var sessions: MutableList<ZSessionInfo> = mutableListOf()
        private set
    var currentSessionId: String? = null
        private set
    var messages: MutableList<ZMessage> = mutableListOf()

    /**
     * 只读缓存模式下打开会话。
     *
     * 当前没有运行核心、或内核正在重启时，允许用户只看历史消息，不向核心发起打开、发送、停止或刷新。
     * 进入离线模式后必须显示为不可编辑；不请求快照，因此不会造成网络状态变化或缓存覆盖。
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

    /** 当前会话斜杠命令。 */
    var todos: List<ZParse.ZTodo> = emptyList()
        private set
    /** 当前会话子代理：session/subagents 驱动 UI。 */
    var subagents: List<ZSubagent> = emptyList()
        private set

    /** 当前会话的工作流运行：workflows/runs 驱动 UI。 */
    var workflowRuns: List<ZWorkflowRun> = emptyList()
        private set

    /**
     * 工作区最近一次动态 run 状态：actor 节点是否已出现。
     *
     * 来源不是普通会话消息，而是 dynamic_workflow_run_progress 事件里的 actor-created/node-dispatched。
     * 从运行列表及工作流管理器得知 actorSessionId，即 sess_dwf-<runId>-<site@ordinal>；其事件结构
     * 与 [ZWorkflowKit.actorKey] 规则一致，因此本模块以 runId + 地址索引跨轮次追踪。
     */
    private val actorSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

/** 同一 run 最近一次事件（给全局工作流面板使用）。本状态只用于界面展示，不替代内核状态。 */
    private val workflowLive = java.util.concurrent.ConcurrentHashMap<String, ZWorkflowLive>()

    /** 工作流观察者：仅用于新节点出现后刷新当前工作流。 */
    private val workflowWatchers = CopyOnWriteArrayList<WorkflowWatcher>()

    fun addWorkflowWatcher(w: WorkflowWatcher) {
        if (!workflowWatchers.contains(w)) workflowWatchers += w
    }

    fun removeWorkflowWatcher(w: WorkflowWatcher) {
        workflowWatchers -= w
    }
    /** 子会话消息缓存：以子会话 id 为键。 */
    private val childMessages = HashMap<String, List<ZMessage>>()

    /** 当前会话的已加载子会话 id（用于判断是否需要显示“更多”按钮）。 */
    private val childMessageFailures = HashSet<String>()

    /** 防止子会话加载重复发出；每个 id 同时只允许一个请求。 */
    private val childLoading = HashSet<String>()

    /** 子 Agent 工具调用 id → 子会话 id；用于在子代理卡片下面展开子会话消息。 */
    private val parentCallToChild = HashMap<String, String>()

    /** 子工具调用 id → 父 Agent 工具调用 id。 */
    private val toolCallToParentCall = HashMap<String, String>()

    private val listeners = CopyOnWriteArrayList<Listener>()
    /** 待应答的权限请求参数（requestId → requestPermission 的原始 params）。 */
    private val pendingPermissionParams = java.util.Collections.synchronizedMap(linkedMapOf<String, JSONObject>())
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("zcode_bridge", Context.MODE_PRIVATE)

    private var started = false
    private var startWaiters = mutableListOf<(Boolean, String) -> Unit>()

    /** 意外退出的连续计数（启动成功超过 2 分钟视为恢复正常）。 */
    private var exitCount = 0

    /** 最近一次核心的启动时刻。 */
    private var lastStartAt = 0L

    /** 是否已为本轮退出安排一次自动重启。 */
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
        // 给旧进程 600ms 收尾，避免新实例撞上旧实例尚未释放的端口与数据库锁。
        main.postDelayed({
            ensureStarted { ok, msg ->
                if (ok) {
                    currentSessionId?.let { openSession(it, null) }
                    // 重启后模型列表由会话读取回调恢复，不沿用旧面板缓存。
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
                            synchronized(pendingPermissionParams) {
                                pendingPermissionParams[requestId.toString()] = params
                            }
                            val sessionId = params.optString("sessionId")
                            notif { onSessionPermissionRequest(sessionId, requestId, params) }
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

    /** 为附件面板等提供应用上下文。 */
    fun appContext(): Context = app

    private fun findAllowResponse(params: JSONObject): JSONObject? {
        val opts = params.optJSONArray("options") ?: return null
        for (i in 0 until opts.length()) {
            val o = opts.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            if (kind.contains("allow")) {
                o.optJSONObject("responses")?.let { return it }
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
                o.optJSONObject("responses")?.let { return it }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 会话快照

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
        // 将流式收到的增量固化进会话本地缓存，这样切走再切回来不必再向内核重新拉取。
        // 只在 sid 已绑定时写缓存，避免首个快照之前把消息错写到占位会话。
        // sid 在初始化流程里保证非空，不能另算一个会话 id。
        val cacheSessionId = sid
        if (!cacheOnly && cacheSessionId != null) {
            SessionCache.saveMessages(app, cacheSessionId, messages)
        }
        val mo = ZParse.parseModelOptions(root)
        // 只有 modelAvailability=all 的完整快照才刷新模型列表，日常轮询并不带该参数；
        // 解析为空也不能把已经取到的模型列表清掉。下拉菜单在初始化前可能尚未绑定。
        // 只在真正执行首次完整读取时刷新。
        if (mo.isNotEmpty() && modelOptions.isEmpty()) modelOptions = mo
        val tl = ZParse.parseThoughtLevels(root)
        if (tl.isNotEmpty()) thoughtLevels = tl
        ZParse.parseCurrentThoughtLevel(root)?.let { currentThoughtLevel = it }
        ZParse.parseCurrentSelection(root)?.let { currentModel = it }
        val sc = ZParse.parseSlashCommands(root)
        if (sc.isNotEmpty()) slashCommands = sc
        todos = ZParse.parseTodos(root)
        notif { onStateChanged() }
        // 子代理与工作流数据是附属面板内容，不在会话切换主路径中阻塞等待。
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
            // 模型列表只在完整读取时刷新，避免增量 patch 覆盖为局部内容。
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
        val type = params.optString("type")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        if (type == "turn.completed" || type == "turn.failed") {
            // 先广播给所有监听者，再决定是否继续处理当前会话的 UI 状态。
            notif { onSessionTurnFinished(sid, type) }
        }
        if (sid.isNotEmpty() && currentSessionId != null && sid != currentSessionId) return
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
                            scheduleRefreshSoon()
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

            // 工作流原子进度更新：actor 节点出现后，下游立刻获得其所属会话 id，
            // 关联父节点与子会话的入口始终保留，工作区详情也直接读取这个映射。
            "dynamic_workflow_run_progress" -> {
                onWorkflowProgress(payload)
            }
        }
    }

    /**
     * 接收一条动态工作流事件。
     *
     * 所有来自 session 工作区的 actorSessionId，均可作为后续跳转到子会话的线索，
     * actor-created / node-dispatched 上各有一份；这也是该工具归属会话的数据来源。
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
        val live = ZWorkflowKit.liveFromProgress(runId, eventType, inner, address)
        post {
            if (live != null) workflowLive[runId] = live
            // 工作流卡片重绘时读取 liveOfWorkflow；地址含 siteId@ordinal，工具 id 的生成和调用
            // 与此索引规则一致，工作流节点不存在时也无需发送交互式审批。
            for (w in workflowWatchers) w.onWorkflowProgress(runId)
        }
        // 同一次事件可能先发生节点创建再发生节点完成，run-settled 只在尾部通知刷新工作流列表。
        if (eventType == "run-settled") refreshWorkflowRuns()
    }

    /**
     * 跟踪工具子调用与父工具调用的关系。
     *
     * 工作流节点有 actorSessionId，但工具事件只暴露 parentToolUseId 和 toolCallId。
     * 通过 actor-created / node-dispatched 上记录到的 childSessionId，可精确跳转到子会话。
     * 用工具标识关联父子，不建立猜测式映射。
     */
    private fun linkParentToolUse(payload: JSONObject) {
        val parent = ZWorkflowKit.parentToolUseIdOf(payload)
        if (parent.isEmpty()) return
        val toolCallId =
            payload
                .optString("toolCallId")
                .ifEmpty { payload.optString("childToolCallId") }
                .ifEmpty { payload.optString("callId") }
        if (toolCallId.isNotEmpty()) toolCallToParentCall[toolCallId] = parent
        val childSessionId = payload.optString("childSessionId")
        if (childSessionId.isNotEmpty()) parentCallToChild[parent] = childSessionId
    }

    /** 保证当前会话的快照最终刷新到最新状态。 */
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

    // ---------------------------------------------------------------- 消息拉取

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
                // 工具轮次完成后子代理与工作流数据需要一起更新，而不是等下一次进入会话。
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
     * 读取当前会话的子代理列表(session/subagents)。
     * 子代理名字与工具调用 id 都来自该响应，不再扫描消息来猜测。
     * 这些字段虽然由 UI 间接使用，但不能因为没有同步调用就当作死代码。
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

    /** 当前工作区工作流列表（workflows/runs）。 */
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

    /** 子 Agent 工具调用 id → 在会话工作流快照中标记为子代理。 */
    fun subagentForToolCall(callId: String?): ZSubagent? {
        if (callId.isNullOrEmpty()) return null
        return subagents.firstOrNull { it.toolUseId == callId }
    }

    /** 第二个工具调用的 run 是否存在；仅依赖事件状态，不读 UI 控件。 */
    fun liveOfWorkflow(runId: String?): ZWorkflowLive? {
        if (runId.isNullOrEmpty()) return null
        return workflowLive[runId]
    }

    /**
     * 已知动态 run 时，按 actor 会话 id 跳转。
     *
     * [address] 是节点在事件里的 `siteId@ordinal`；工具和节点都是这一地址体系。
     * 找不到说明该节点尚无独立会话（例如普通生成节点），应留给 UI 展示进度，不能猜测 id。
     */
    fun actorSessionOf(
        runId: String?,
        address: String?,
    ): String? {
        if (runId.isNullOrEmpty() || address.isNullOrEmpty()) return null
        return actorSessions[ZWorkflowKit.actorKey(runId, address)]
    }

    /** 当前会话的工作流条目是否存在（任何节点）。 */
    fun hasLiveWorkflow(runId: String?): Boolean = !runId.isNullOrEmpty() && workflowLive.containsKey(runId)

    /** CreateWorkflow 工具调用 id → 在会话工作流中找到对应运行。 */
    fun workflowRunForToolCall(callId: String?): ZWorkflowRun? {
        if (callId.isNullOrEmpty()) return null
        return workflowRuns.firstOrNull { it.toolUseId == callId }
    }

    /**
     * 读取子代理会话并缓存。是否命中缓存由调用方决定；
     * 但不刷新父子关系时，仍需要根据 childMessages 中已有条目判断展开。
     * 回源使用 session/messages；不存在子会话时提示一次而不是自动重试，
     * 让错误可感知：有的子代理仅有名称与摘要，未建立独立会话。
     */
    fun loadChildMessages(
        childSessionId: String,
        cb: ((List<ZMessage>?) -> Unit)? = null,
    ) {
        if (childSessionId.isEmpty()) {
            post { cb?.invoke(emptyList()) }
            return
        }
        // 同类查询与主会话一样串行执行，防止切换父会话时旧结果覆盖新面板。
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
            // 子代理中的新会话可能还未被激活，先 resume 再读；session store
            // 会返回只读历史。resume 会改变其进程状态，
            // 不用于“session is not active”的其他非子会话。
            resumeSession(childSessionId) { ok, _ ->
                if (!ok) {
                    settle(null)
                    return@resumeSession
                }
                readChildMessages(childSessionId) { again -> settle(again) }
            }
        }
    }

    /** 通过 session/messages 读取子会话历史，不依赖父会话 UI。 */
    private fun readChildMessages(
        childSessionId: String,
        cb: (List<ZMessage>?) -> Unit,
    ) {
        runtime.call("session/messages", JSONObject().put("sessionId", childSessionId)) { ok, body ->
            cb(if (ok) ZParse.parseMessages(body) else null)
        }
    }

    /** 已有缓存可用来展开子会话；空列表即表示没有内容。 */
    fun cachedChildMessages(childSessionId: String?): List<ZMessage>? {
        if (childSessionId.isNullOrEmpty()) return null
        return childMessages[childSessionId]
    }

    /** 对子会话历史读取失败显示错误，而不是永远转圈。 */
    fun childMessageFailed(childSessionId: String?): Boolean {
        if (childSessionId.isNullOrEmpty()) return false
        return childMessageFailures.contains(childSessionId)
    }

    /**
     * 工具调用对应子会话 id。该映射由主会话消息工具链中的 parentToolUseId 与动态工作流事件提供。
     * 若节点未加载，则返回 null，不允许凭字符串结构生成不存在的会话 id。
     */
    fun childSessionIdForToolCall(callId: String?): String? {
        val fromRoster = subagentForToolCall(callId)?.childSessionId
        if (!fromRoster.isNullOrEmpty()) return fromRoster
        if (callId.isNullOrEmpty()) return null
        return parentCallToChild[callId]
    }

    /** 工具调用的父工具调用 id（用于建立嵌套代理的展示树）。 */
    fun parentCallOfToolCall(callId: String?): String? {
        if (callId.isNullOrEmpty()) return null
        return toolCallToParentCall[callId]
    }

    //
    // 会话列表管理
    //

    // 会话只读预览
    //
    // 如果尚未启动核心，用户仍可打开历史消息。进入这个模式后禁止发送新消息、终止会话或发起刷新。
    // 即使这样，重新打开列表中的某一项也会因本地缓存不足返回错误，不会伪造空会话。
    //

    /**
     * 开启离线只读预览。
     *
     * 显示缓存以 session 元数据为根，没有运行时状态。UI 需依据 [cacheOnly] 禁用输入、发送和加载。
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
     * 离线打开会话。
     *
     * 无法恢复会话时只显示缓存；不能覆盖当前上下文。
     * 如果本地缓存就是正在使用的对话，则无需额外请求。
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
                // 替换而非累加：刷新是权威快照，删除/归档的会话不能继续留在列表。
                SessionCache.saveSessions(app, list)
                post {
                    cacheOnly = false
                    sessions = list.toMutableList()
                    for (l in listeners) l.onStateChanged()
                }
            } else {
                // 读失败时才启用本地快照，不保证服务端会话一定已经同步到手机。
                restoreFromCache()
            }
            cb?.invoke(ok)
        }
    }

    /** 阅读 workspace/readPresentation 获得展示信息，但不会为此强制同步工作区。 */
    fun refreshWorkspacePresentation(cb: ((Boolean) -> Unit)? = null) {
        val wp = workspacePath()
        val params = JSONObject().put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("workspace/readPresentation", params) { ok, body ->
            if (ok) {
                val sc = ZParse.parseSlashCommands(body)
                if (sc.isNotEmpty()) {
                    slashCommands = sc
                    notif { onStateChanged() }
                }
            }
            cb?.invoke(ok)
        }
    }

    /**
     * 选择会话时可读取的模型选项。该函数刷新当前已打开会话，或列表第一项；
     * 不切换默认模型。读取使用完整的模型可见性，
     * 避免用户切换模型时看到的只是旧面板缓存。
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

    /** 会话不可读时先 resume，再补读一次模型列表。 */
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
                // 管理面板要列出全部可选模型，而不是仅当前供应商的一个。
                // 这里必须使用 "all"，否则没有会话快照时也会漏掉所有模型。
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

    /** 使用统计，范围: "7d" | "30d" | "all"。 */
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
        val params = JSONObject().put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("plugins/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** MCP 服务器列表：服务器配置只列 mode，不携带自动启动/停止行为。 */
    fun fetchMcpList(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject().put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("mcp/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 启用 / 禁用插件。 */
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

    /** 卸载插件：卸载会移除该插件的本地配置。 */
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

    /** 安装插件：marketplace 可选，插件名必填。 */
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

    /** 更新插件：pluginId 与 marketplace 都是可选参数。 */
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

    /** 配置插件：options 是当前显示值，clearOptionKeys 用于清除可编辑字段。 */
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

    /** 添加插件市场。 */
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

    /** 删除插件市场。 */
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

    /** 更新插件市场：marketplace 为 null 时更新所有源。 */
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

    /** 恢复内置插件到可用状态。 */
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
    // ---------------------------------------------------------------- 会话控制

    fun workspacePath(): String = Workspaces.root(app).absolutePath

    /** Personal Agent 规划工具只操作宿主当前打开的编码会话。 */
    fun activeSessionId(): String? = currentSessionId

    /** 会话快照：供 Personal Agent 的规划模型挑选要操作的会话。 */
    fun sessionSnapshot(): List<ZSessionInfo> = sessions.toList()

    /** 向指定会话发送任务，不改变宿主当前打开的会话。 */
    fun sendTo(sessionId: String, text: String, cb: ((Boolean, String) -> Unit)? = null) {
        if (sessionId.isBlank()) {
            cb?.invoke(false, "会话 id 为空")
            return
        }
        doSend(sessionId, text, null, retried = false, cb = cb)
    }

    /** 终止指定会话的生成；与 stop 的区别是作用于任意会话。 */
    fun stopSession(sessionId: String, cb: ((Boolean, String) -> Unit)? = null) {
        if (sessionId.isBlank()) {
            cb?.invoke(false, "会话 id 为空")
            return
        }
        runtime.call("session/stop", JSONObject().put("sessionId", sessionId)) { ok, body ->
            if (ok) {
                post {
                    if (currentSessionId == sessionId) running = false
                    notif { onStateChanged() }
                }
                cb?.invoke(true, "ok")
            } else {
                cb?.invoke(false, errMsg(body))
            }
        }
    }

    /** 读取指定会话可选的模型；只读，不改动宿主面板当前的模型列表。 */
    fun modelOptionsFor(sessionId: String, cb: (List<ZModelOption>) -> Unit) {
        if (sessionId.isBlank()) {
            cb(emptyList())
            return
        }
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("deliveryKind", "desktop-continuous")
                .put("modelAvailability", "all")
        runtime.call("session/read", params) { ok, body ->
            cb(if (ok) ZParse.parseModelOptions(body) else emptyList())
        }
    }

    /** 切换指定会话使用的模型；显式传 level 时优先，否则取该模型默认档位。 */
    fun setModelFor(
        sessionId: String,
        providerId: String,
        modelId: String,
        level: String? = null,
        cb: ((Boolean, String) -> Unit)? = null,
    ) {
        if (sessionId.isBlank()) {
            cb?.invoke(false, "会话 id 为空")
            return
        }
        modelOptionsFor(sessionId) { options ->
            val option = options.firstOrNull { it.providerId == providerId && it.modelId == modelId }
            if (option == null) {
                cb?.invoke(false, "该会话没有这个模型：$providerId/$modelId")
                return@modelOptionsFor
            }
            val chosen = level?.takeIf { it.isNotEmpty() } ?: option.defaultLevel ?: option.levels.firstOrNull()?.value
            if (chosen.isNullOrEmpty()) {
                cb?.invoke(false, "该模型未声明推理档位")
                return@modelOptionsFor
            }
            val model =
                JSONObject()
                    .put("providerId", providerId)
                    .put("modelId", modelId)
                    .put("options", JSONObject().put("reasoningLevel", chosen))
            val params =
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("model", model)
                    .put("persistAsWorkspaceLastUsed", true)
            runtime.call("session/setModel", params) { ok, body ->
                cb?.invoke(ok, if (ok) "已切换到 ${option.label}" else errMsg(body))
            }
        }
    }

    /**
     * 新建会话。
     *
     * 创建完成后默认打开该会话；标题由第一条消息触发服务端自动生成。
     */
    fun newSession(cb: (Boolean, String) -> Unit) {
        val params =
            JSONObject()
                .put("workspace", workspaceRef())
                .put("defaultMode", defaultMode())
        runtime.call("session/new", params) { ok, body ->
            if (!ok) {
                cb(false, errMsg(body))
                return@call
            }
            val sid = ZParse.parseSessionIdFromNew(body)
            if (sid.isNullOrEmpty()) {
                cb(false, "创建会话成功但未返回 sessionId")
                return@call
            }
            openSession(sid, cb)
        }
    }

    /**
     * 打开会话并读取快照。
     *
     * 切换时会先绑定 currentSessionId，避免打开期间的消息事件落入旧会话。
     */
    fun openSession(sessionId: String, cb: ((Boolean, String) -> Unit)?) {
        cacheOnly = false
        currentSessionId = sessionId
        messages.clear()
        subagents = emptyList()
        workflowRuns = emptyList()
        todos = emptyList()
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("deliveryKind", "desktop-continuous")
                .put("includeSnapshot", true)
        runtime.call("session/open", params) { ok, body ->
            if (ok) {
                val snap = body.optJSONObject("snapshot") ?: body
                applySnapshot(snap)
                refreshSnapshotQuiet()
                readModelOptions(sessionId, null)
                notif { onSessionOpened(sessionId) }
                cb?.invoke(true, "ok")
            } else {
                cb?.invoke(false, errMsg(body))
            }
        }
    }

    /** 归档/恢复会话；归档列表由会话管理页控制。 */
    fun archiveSession(
        sessionId: String,
        archived: Boolean,
        cb: (Boolean, String) -> Unit,
    ) {
        val params = JSONObject().put("sessionId", sessionId).put("archived", archived)
        runtime.call("session/archive", params) { ok, body ->
            if (ok) refreshSessions(null)
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 关闭当前会话，不改变工作区的历史记录。 */
    fun closeSession(sessionId: String, cb: (Boolean) -> Unit) {
        runtime.call("session/close", JSONObject().put("sessionId", sessionId)) { ok, body ->
            if (ok) {
                if (currentSessionId == sessionId) {
                    currentSessionId = null
                    messages.clear()
                    subagents = emptyList()
                    workflowRuns = emptyList()
                }
                refreshSessions(null)
            }
            cb(ok)
            if (!ok) notif { onNotice(errMsg(body)) }
        }
    }

    /** 恢复一个已关闭的会话。 */
    fun resumeSession(sessionId: String, cb: (Boolean, String) -> Unit) {
        runtime.call("session/resume", JSONObject().put("sessionId", sessionId)) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 删除会话，删除后由会话列表事件刷新 UI。 */
    fun deleteSession(sessionId: String, cb: (Boolean, String) -> Unit) {
        runtime.call("session/delete", JSONObject().put("sessionId", sessionId)) { ok, body ->
            if (ok && currentSessionId == sessionId) {
                currentSessionId = null
                messages.clear()
                subagents = emptyList()
                workflowRuns = emptyList()
            }
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 重命名会话；不传 null 时由服务端按内容生成标题。 */
    fun renameSession(sessionId: String, title: String?, cb: (Boolean, String) -> Unit) {
        val params = JSONObject().put("sessionId", sessionId)
        if (title != null) params.put("title", title)
        runtime.call("session/rename", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 切换模式：build/plan/yolo 等由核心定义，不在客户端自行映射。 */
    fun setMode(mode: String, cb: (Boolean, String) -> Unit) {
        val sid = currentSessionId ?: return cb(false, "没有打开的会话")
        val params = JSONObject().put("sessionId", sid).put("mode", mode)
        runtime.call("session/setMode", params) { ok, body ->
            if (ok) {
                this.mode = mode
                notif { onStateChanged() }
            }
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 切换推理等级；不能独立修改供应商和模型名。 */
    fun setThoughtLevel(value: String, cb: (Boolean, String) -> Unit) {
        val sid = currentSessionId ?: return cb(false, "没有打开的会话")
        val params = JSONObject().put("sessionId", sid).put("thoughtLevel", value)
        runtime.call("session/setThoughtLevel", params) { ok, body ->
            if (ok) {
                currentThoughtLevel = value
                notif { onStateChanged() }
            }
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /**
     * 发送文本；可以携带一条附件上下文，附件由宿主应用创建。
     *
     * 发送失败时回调返回 false，界面可以恢复草稿。发送期间不直接插入假的助手消息。
     */
    fun send(text: String, cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "没有打开的会话")
            return
        }
        doSend(sid, text, null, false, cb)
    }

    fun sendWithContext(text: String, contextFile: File, cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "没有打开的会话")
            return
        }
        val part = JSONObject().put("type", "file").put("path", contextFile.absolutePath)
        doSend(sid, text, JSONArray().put(part), false, cb)
    }

    /** 实际发送逻辑；同一函数负责新建后的首次发送与重试。 */
    private fun doSend(
        sessionId: String,
        text: String,
        parts: JSONArray?,
        retried: Boolean,
        cb: ((Boolean, String) -> Unit)?,
    ) {
        if (text.isBlank() && parts == null) {
            cb?.invoke(false, "消息为空")
            return
        }
        running = true
        notif { onStateChanged() }
        val params =
            JSONObject()
                .put("sessionId", sessionId)
                .put("text", text)
                .put("deliveryKind", "desktop-continuous")
                .put("requestId", UUID.randomUUID().toString())
        if (parts != null) params.put("parts", parts)
        runtime.call("session/send", params) { ok, body ->
            if (ok) {
                running = true
                notif { onStateChanged() }
                cb?.invoke(true, "ok")
            } else {
                running = false
                // session not active 时恢复会话并重发一次；其它错误不改变原消息。
                if (!retried && body.toString().contains("not active")) {
                    resumeSession(sessionId) { rok, _ ->
                        if (rok) doSend(sessionId, text, parts, true, cb) else cb?.invoke(false, errMsg(body))
                    }
                } else {
                    cb?.invoke(false, errMsg(body))
                }
            }
        }
    }

    /** 停止当前回合。 */
    fun stop(cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId ?: return
        runtime.call("session/stop", JSONObject().put("sessionId", sid)) { ok, body ->
            running = false
            notif { onStateChanged() }
            cb?.invoke(ok, if (ok) "ok" else errMsg(body))
        }
    }

    /** 导出当前会话的 Markdown；只有本地持久化，不执行分享或网络上传。 */
    fun exportMarkdown(): String = buildString {
        val title = sessions.find { it.id == currentSessionId }?.title ?: "Coda 会话"
        append("# ").append(title).append("\n\n")
        messages.forEach { m ->
            append(if (m.role == "user") "## 你" else "## Coda").append("\n\n")
            append(m.text).append("\n\n")
        }
    }

    /** 回答用户输入请求：拒绝时按协议返回 decline，选项不存在时仍给出可识别的动作。 */
    fun respondUserInput(requestId: Any, response: JSONObject) {
        runtime.respond(requestId, response)
    }

    /** 当前工作区的引用。 */
    private fun workspaceRef(): JSONObject {
        val path = workspacePath()
        return JSONObject().put("workspacePath", path).put("workspaceKey", path)
    }

    private fun defaultMode(): String = prefs.getString("default_mode", "build") ?: "build"

    /** 把核心错误体压缩成用户可读的字符串，不把 JSON 原文显示在 Toast 里。 */
    private fun errMsg(body: JSONObject): String {
        val error = body.optJSONObject("error") ?: body
        return error.optString("message").ifBlank { body.toString() }
    }

    /** 当前会话的待办列表（由 session/todos 解析）。 */
    fun todosJson(): JSONObject {
        val o = JSONObject()
        o.put("sessionId", currentSessionId ?: "")
        o.put("todos", JSONArray(todos.map { JSONObject().put("content", it.content).put("status", it.status) }))
        return o
    }

    /** 应答一条交互式权限请求；allow 为 true 放行，false 拒绝。 */
    fun respondPermission(requestId: Any, allow: Boolean) {
        val params = synchronized(pendingPermissionParams) { pendingPermissionParams.remove(requestId.toString()) } ?: JSONObject()
        val response =
            (if (allow) findAllowResponse(params) else findDenyResponse(params))
                ?: JSONObject().put("decision", if (allow) "allow" else "deny")
        runtime.respond(requestId, response)
    }

    fun setDefaultMode(protocolMode: String) {
prefs.edit().putString("default_mode", protocolMode).apply()
    }

    companion object {
        @Volatile private var instance: ZController? = null

        fun get(context: Context): ZController =
            instance ?: synchronized(this) {
                instance ?: ZController(context.applicationContext).also { instance = it }
            }
    }
}
