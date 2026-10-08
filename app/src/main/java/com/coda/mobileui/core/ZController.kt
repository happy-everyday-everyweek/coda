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
class ZController private constructor(private val app: Context) {

    interface Listener {
        /** 任意状态变化（会话列表/消息/设置），UI 重新渲染。 */
        fun onStateChanged() {}

        /** 流式文本增量（用于局部渲染）。 */
        fun onDelta(assistantMessageId: String, text: String) {}

        /** 流式思考增量（用于局部渲染）。 */
        fun onReasoningDelta(assistantMessageId: String, text: String) {}

        /** 轻提示（错误/状态）。 */
        fun onNotice(text: String) {}

        /** 权限请求需要 UI 决策。 */
        fun onPermissionRequest(requestId: Any, params: JSONObject) {}

        /** 用户提问需要 UI 决策。 */
        fun onUserInputRequest(requestId: Any, params: JSONObject) {}

        /** 会话已打开。 */
        fun onSessionOpened(sessionId: String) {}
    }

    val runtime = CoreRuntime(app)

    var sessions: MutableList<ZSessionInfo> = mutableListOf()
        private set
    var currentSessionId: String? = null
        private set
    var messages: MutableList<ZMessage> = mutableListOf()
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

    private val refreshRunnable = Runnable { doRefreshMessages() }

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

    private fun runtimeEvents(): CoreRuntime.Events = object : CoreRuntime.Events {

        override fun onNotification(method: String, params: JSONObject) {
            when (method) {
                "session/event" -> handleSessionEvent(params)
                "state.updated" -> handleStateUpdated(params)
                else -> {
                }
            }
        }

        override fun onExit(code: Int) {
            started = false
            running = false
            val now = System.currentTimeMillis()
            if (now - lastStartAt > 120_000L) exitCount = 0
            exitCount += 1
            if (exitCount <= 5 && !autoRestartScheduled) {
                autoRestartScheduled = true
                notif { onNotice("核心进程意外退出（code=$code），正在自动重启…") }
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
            } else {
                notif { onNotice("核心进程已退出（code=$code）") }
            }
            notif { onStateChanged() }
        }

        override fun onInteractiveReverse(requestId: Any, method: String, params: JSONObject): Boolean {
            return when (method) {
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
                    } else {
                        false
                    }
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
        val mo = ZParse.parseModelOptions(root)
        if (mo.isNotEmpty()) modelOptions = mo
        val tl = ZParse.parseThoughtLevels(root)
        if (tl.isNotEmpty()) thoughtLevels = tl
        ZParse.parseCurrentThoughtLevel(root)?.let { currentThoughtLevel = it }
        ZParse.parseCurrentSelection(root)?.let { currentModel = it }
        val sc = ZParse.parseSlashCommands(root)
        if (sc.isNotEmpty()) slashCommands = sc
        todos = ZParse.parseTodos(root)
        notif { onStateChanged() }
    }

    private fun handleStateUpdated(params: JSONObject) {
        val patch = params.optJSONObject("patch") ?: return
        val st = patch.optString("status")
        if (st.isNotEmpty()) running = (st == "running" || st == "waiting")
        patch.optJSONObject("mode")?.optString("current")?.takeIf { it.isNotEmpty() }?.let { mode = it }
        patch.optJSONObject("model")?.let { mo ->
            val root = JSONObject().put("settings", JSONObject().put("model", mo))
            val list = ZParse.parseModelOptions(root)
            if (list.isNotEmpty()) modelOptions = list
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
                when (kind) {
                    "text_delta" -> if (delta.isNotEmpty()) {
                        notif { onDelta(mid, delta) }
                        scheduleRefreshSoon()
                    }

                    "reasoning_delta" -> if (delta.isNotEmpty()) {
                        notif { onReasoningDelta(mid, delta) }
                    }
                }
            }

            "tool.updated" -> {
                val kind = payload.optString("kind")
                if (kind == "result" || kind == "error" || kind == "batch") {
                    scheduleRefreshSoon()
                }
            }
        }
    }

    /** 回合结束后的静默快照刷新（同步待办等快照级状态），不切换会话、不触发打开回调。 */
    private var snapshotRefreshing = false

    private fun refreshSnapshotQuiet() {
        val sid = currentSessionId ?: return
        if (snapshotRefreshing) return
        snapshotRefreshing = true
        val params = JSONObject()
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
                post {
                    messages = list.toMutableList()
                    for (l in listeners) l.onStateChanged()
                }
            }
            if (refreshAgain) {
                refreshAgain = false
                scheduleRefreshSoon()
            }
        }
    }

    fun refreshSessions(cb: ((Boolean) -> Unit)?) {
        val params = JSONObject()
            .put("workspace", workspaceRef())
            .put("includeArchived", false)
        runtime.call("session/list", params) { ok, body ->
            if (ok) {
                val list = ZParse.parseSessionInfos(body)
                post {
                    sessions = list.toMutableList()
                    for (l in listeners) l.onStateChanged()
                }
            }
            cb?.invoke(ok)
        }
    }

    /** 拉取 workspace 级表现（含斜杠命令目录）：无会话时也能拿到内置命令表。 */
    fun refreshWorkspacePresentation(cb: ((Boolean) -> Unit)? = null) {
        val wp = workspacePath()
        val params = JSONObject()
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
    private fun readModelOptionsAfterResume(sessionId: String, cb: ((Boolean) -> Unit)?) {
        resumeSession(sessionId) { rok, _ ->
            if (!rok) {
                cb?.invoke(false)
                return@resumeSession
            }
            readModelOptions(sessionId, cb)
        }
    }

    private fun readModelOptions(sessionId: String, cb: ((Boolean) -> Unit)?) {
        val params = JSONObject()
            .put("sessionId", sessionId)
            .put("deliveryKind", "desktop-continuous")
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
    fun fetchUsageStats(range: String, cb: (Boolean, JSONObject?) -> Unit) {
        runtime.call("usage/stats", JSONObject().put("range", range)) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 插件列表。 */
    fun fetchPlugins(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("plugins/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** MCP 服务器状态列表（mode: status 只读快照，不做连接操作）。 */
    fun fetchMcpList(cb: (Boolean, JSONObject?) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("mode", "status")
        runtime.call("mcp/list", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }

    /** 启用 / 停用插件。 */
    fun setPluginEnabled(pluginId: String, enabled: Boolean, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
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
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        runtime.call("plugins/overview", params) { ok, body ->
            cb(ok, if (ok) body else null)
        }
    }
    /** 安装插件。 */
    fun installPlugin(pluginName: String, marketplace: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("pluginName", pluginName)
            .put("marketplace", marketplace)
        runtime.call("plugins/install", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 卸载插件。 */
    fun uninstallPlugin(pluginId: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("pluginId", pluginId)
        runtime.call("plugins/uninstall", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 更新插件（pluginId 或整个 marketplace，二选一）。 */
    fun updatePlugin(pluginId: String?, marketplace: String?, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
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
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("pluginId", pluginId)
            .put("options", options)
        if (clearKeys.isNotEmpty()) params.put("clearOptionKeys", JSONArray(clearKeys))
        runtime.call("plugins/configure", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 重置插件配置为默认值。 */
    fun resetPluginConfig(pluginId: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("pluginId", pluginId)
        runtime.call("plugins/resetConfig", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 添加插件市场源。 */
    fun addPluginMarketplace(source: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("source", source)
        runtime.call("plugins/marketplace/add", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 移除插件市场。 */
    fun removePluginMarketplace(marketplace: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("marketplace", marketplace)
        runtime.call("plugins/marketplace/remove", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 刷新插件市场索引（marketplace 为空时刷新全部）。 */
    fun updatePluginMarketplace(marketplace: String?, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
        marketplace?.let { params.put("marketplace", it) }
        runtime.call("plugins/marketplace/update", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    /** 恢复内置插件。 */
    fun restoreBuiltinPlugin(pluginId: String, cb: (Boolean, String) -> Unit) {
        val wp = workspacePath()
        val params = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("pluginId", pluginId)
        runtime.call("plugins/restoreBuiltin", params) { ok, body ->
            cb(ok, if (ok) "ok" else errMsg(body))
        }
    }
    // ---------------------------------------------------------------- 会话操作

    fun workspacePath(): String {
        // 品牌目录迁移（一次性）：旧的 /sdcard/ZCode 改名为 /sdcard/Coda
        val brandDir = File("/sdcard/Coda")
        val legacy = File("/sdcard/ZCode")
        if (!brandDir.exists() && legacy.exists()) {
            try {
                legacy.renameTo(brandDir)
            } catch (_: Throwable) {
            }
        }
        val f = File(brandDir, "workspace")
        return try {
            if (f.exists() || f.mkdirs()) f.absolutePath
            else File(app.filesDir, "workspace").apply { mkdirs() }.absolutePath
        } catch (_: Throwable) {
            File(app.filesDir, "workspace").apply { mkdirs() }.absolutePath
        }
    }

    fun newSession(cb: ((Boolean, String) -> Unit)?) {
        val wp = workspacePath()
        val params = JSONObject()
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

    fun openSession(sessionId: String, cb: ((Boolean) -> Unit)?) {
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
                if (msg != null) notif { onNotice("打开会话失败: $msg") }
                cb?.invoke(false)
            }
        }
    }
    /** 订阅会话（打开会话的第二段；冷会话需先 resumeSession 恢复）。 */
    private fun subscribeSession(sessionId: String, cb: ((Boolean, String?) -> Unit)?) {
        val params = JSONObject()
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
    fun resumeSession(sessionId: String, cb: ((Boolean, String) -> Unit)? = null) {
        val params = JSONObject()
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

    fun closeSession(sessionId: String, cb: ((Boolean) -> Unit)? = null) {
        runtime.call("session/close", JSONObject().put("sessionId", sessionId)) { _, _ ->
            if (currentSessionId == sessionId) currentSessionId = null
            refreshSessions(null)
            cb?.invoke(true)
        }
    }

    fun send(text: String, attachments: JSONArray? = null, cb: ((Boolean, String) -> Unit)? = null) {
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
    fun readAttachment(ref: String, cb: (Boolean, ByteArray?) -> Unit) {
        val sid = currentSessionId
        if (sid == null) {
            post { cb(false, null) }
            return
        }
        val maxBytes = 20 * 1024 * 1024
        val chunkLimit = 512 * 1024
        val out = java.io.ByteArrayOutputStream()
        fun step(offset: Long) {
            val params = JSONObject()
                .put("sessionId", sid)
                .put("ref", ref)
                .put("offset", offset)
                .put("limit", chunkLimit)
            runtime.call("v4/attachment/read", params) { ok, body ->
                if (!ok || body == null) {
                    post { cb(false, null) }
                    return@call
                }
                val bytes = try {
                    android.util.Base64.decode(body.optString("dataBase64"), android.util.Base64.DEFAULT)
                } catch (e: Throwable) {
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
                val cmd = JSONObject()
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

    fun compact(instructions: String? = null, cb: ((Boolean, String) -> Unit)? = null) {
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

    // ---------------------------------------------------------------- 会话设置

    fun setMode(protocolMode: String, cb: ((Boolean, String) -> Unit)? = null) {
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
                        if (rok) doSetMode(sid, protocolMode, retried = true, cb = cb)
                        else cb?.invoke(false, msg)
                    }
                } else {
                    cb?.invoke(false, msg)
                }
            }
        }
    }

    fun setThoughtLevel(value: String, cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        val params = JSONObject()
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

    fun setModel(providerId: String, modelId: String, level: String? = null, cb: ((Boolean, String) -> Unit)? = null) {
        val sid = currentSessionId
        if (sid == null) {
            cb?.invoke(false, "无会话")
            return
        }
        val lv = level
            ?: modelOptions.find { it.providerId == providerId && it.modelId == modelId }
                ?.let { it.defaultLevel ?: it.levels.firstOrNull()?.value }
            ?: currentThoughtLevel
        if (lv.isNullOrEmpty()) {
            cb?.invoke(false, "该模型未声明推理档位")
            return
        }
        val model = JSONObject()
            .put("providerId", providerId)
            .put("modelId", modelId)
            .put("options", JSONObject().put("reasoningLevel", lv))
        val params = JSONObject()
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

    private fun rememberSelection(providerId: String, modelId: String, level: String?) {
        prefs.edit()
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
                ?: modelOptions.find { it.providerId == p && it.modelId == m }
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

    private fun errMsg(body: JSONObject): String =
        body.optString("message").ifEmpty { body.toString().take(200) }

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

    fun toProtocol(uiName: String): String = when (uiName) {
        "Yolo" -> "yolo"
        "Chat" -> "plan"
        else -> "build"
    }

    fun toUiIndex(protocolMode: String): Int = when (protocolMode) {
        "yolo" -> 0
        "plan" -> 2
        else -> 1
    }
}