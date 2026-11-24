package com.coda.mobileui.personal

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.coda.mobileui.core.Bg
import com.coda.mobileui.core.LinuxEnv
import com.coda.mobileui.core.ProviderStore
import com.coda.mobileui.core.ZController
import org.json.JSONObject
import java.io.File

/** Kotlin 与 MaiBot Lite 之间的唯一桥；Python 线程负责全部模型调用。 */
class CodaBotRuntime(
    private val context: Context,
    private val profile: CodaBotProfile,
    private val onMessage: (CodaBotMessage) -> Unit,
) {
    interface Listener {
        fun onMessage(id: String, text: String)
        fun onError(message: String)
        fun controlSession(action: String, text: String, sessionId: String): String
        fun listSessions(): String
        fun sendToSession(sessionId: String, text: String): String
        fun stopSession(sessionId: String): String
        fun listModels(sessionId: String): String
        fun selectModel(sessionId: String, providerId: String, modelId: String, level: String): String
        fun approveTool(requestKey: String, decision: String): String
        fun readFile(path: String): String
        fun runTerminal(command: String, timeoutSeconds: Int): String
    }

    @Volatile
    private var bridge: PyObject? = null
    /** 串行化对 Python 桥的发送调用，避免同一实例并发重入。 */
    private val sendLock = Any()
    private val codingAgent by lazy { ZController.get(context) }

    /** 待应答的审批请求：标识 + 到达时间。 */
    private data class PendingPermission(
        val id: Any,
        val at: Long,
    )

    /** 待应答的审批请求队列，按到达顺序处理。 */
    private val pendingPermissions = java.util.LinkedHashMap<String, PendingPermission>()
    private val pendingPermissionLimit = 20
    private val pendingPermissionTtlMs = 10 * 60 * 1000L

    /** 审批 TTL 的定时器：只在下一个请求到达时清理，会让 Agent 无限等待。 */
    private val expiryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiryTick = Runnable { prunePendingPermissions() }

    /** 宿主事件节流：同一会话在该窗口内的连续事件合并成一条。 */
    private val eventWindowMs = 8_000L
    private val eventLock = Any()
    private val lastEventAt = HashMap<String, Long>()
    private val mergedEvents = HashMap<String, Int>()

    /** 各会话待执行的「窗口结束补发」任务（OCR low #105）。 */
    private val mergedRunnables = HashMap<String, Runnable>()

    /**
     * 监听 Coding Agent 的宿主事件。
     *
     * 任意会话（不限于当前打开的那个）回合结束或请求审批时，都把事件作为一条
     * 宿主消息推给 MaiBot，由规划轮次决定是否复核或处理。
     */
    private val hostListener =
        object : ZController.Listener {
            override fun onSessionTurnFinished(sessionId: String, status: String) {
                val title = codingAgent.sessionSnapshot().firstOrNull { it.id == sessionId }?.title ?: sessionId
                val failed = status == "turn.failed"
                val state = if (failed) "回合失败" else "已完成"
                // OCR low #105：失败不能参与 8s 合并——被吞掉时规划模型会以为任务成功。
                notifyHost("Coding Agent 会话「$title」$state，请复核成果后决定是否告知用户。", sessionId, urgent = failed)
            }

            override fun onSessionPermissionRequest(sessionId: String, requestId: Any, params: JSONObject) {
                val requestKey = requestId.toString()
                // 表只增不减会一直涨，也会让 Coding Agent 永远等在审批上：过期与超量的都按拒绝处理。
                prunePendingPermissions()
                synchronized(pendingPermissions) {
                    pendingPermissions[requestKey] = PendingPermission(requestId, System.currentTimeMillis())
                }
                // 即使之后没有新的审批到达，也到点清理一次。
                scheduleExpiry()
                val tool = params.optString("toolName").ifBlank { params.optString("tool") }
                notifyHost(
                    "Coding Agent 请求审批：request_key=$requestKey，session_id=$sessionId，工具「$tool」。" +
                        "请用该 request_key 调用 approve_tool 决定放行或拒绝。",
                )
            }
        }

    init {
        codingAgent.addListener(hostListener)
    }

    fun send(text: String, replyToId: String = "", messageId: String = "", onError: (String) -> Unit) {
        if (profile.plannerModel.isBlank() || profile.replyModel.isBlank()) {
            onError("请先在 Coda Bot 设置中选择规划模型和回复模型")
            return
        }
        Bg.run {
        try {
            val module = ensurePython().getModule("src.lite_android_bridge")
            val instance = synchronized(this) {
                bridge ?: module.callAttr(
                "create_android_bridge",
                File(context.filesDir, "coda-bot-kernel").absolutePath,
                object : Listener {
                    override fun onMessage(id: String, text: String) {
                        onMessage(CodaBotMessage(id = id, fromUser = false, text = text))
                    }

                    override fun onError(message: String) {
                        onError(message)
                    }

                    override fun controlSession(action: String, text: String, sessionId: String): String =
                        guard("control_session") { controlCodingAgent(action, text, sessionId) }

                    override fun listSessions(): String = guard("list_sessions") { sessionListJson() }

                    override fun sendToSession(sessionId: String, text: String): String =
                        guard("send_to_session") { sendToSessionBlocking(sessionId, text) }

                    override fun stopSession(sessionId: String): String =
                        guard("stop_session") { stopSessionBlocking(sessionId) }

                    override fun listModels(sessionId: String): String =
                        guard("list_models") { modelListJson(sessionId) }

                    override fun selectModel(
                        sessionId: String,
                        providerId: String,
                        modelId: String,
                        level: String,
                    ): String = guard("select_model") { applySessionModel(sessionId, providerId, modelId, level) }

                    override fun approveTool(requestKey: String, decision: String): String =
                        guard("approve_tool") { resolvePermission(requestKey, decision) }

                    override fun readFile(path: String): String = guard("read_file") { readContextFile(path) }

                    override fun runTerminal(command: String, timeoutSeconds: Int): String =
                        guard("run_terminal") { runContextCommand(command, timeoutSeconds) }
                },
                profile.name,
                profile.userName,
                listOf(profile.persona, profile.systemPrompt).filter { it.isNotBlank() }.joinToString("\n\n"),
                profile.userAbout,
                providerConfig(profile.plannerProviderId, profile.plannerModel, "planner"),
                providerConfig(profile.replyProviderId, profile.replyModel, "reply"),
            ).also { bridge = it }
            }
            synchronized(sendLock) {
                instance.callAttr("send", text, replyToId, messageId)
            }
        } catch (t: Throwable) {
            onError(t.message ?: t.javaClass.simpleName)
        }
        }
    }

    /**
     * Python 回调的统一兜底（#159）。
     *
     * 这些方法直接作为 Chaquopy Listener 暴露给 Python：任何异常越过边界都会
     * 中断当前规划轮次，而不是返回约定的 `{ok:false}`。canonicalFile、workspacePath、
     * LinuxEnv 构造、respondPermission 这些前置步骤都可能抛，所以整段包住。
     */
    private fun guard(
        tool: String,
        block: () -> String,
    ): String =
        try {
            block()
        } catch (e: Exception) {
            com.coda.mobileui.core.AppLog.w("CodaBot", "$tool 回调失败：${e.javaClass.simpleName}: ${e.message}")
            result(false, "$tool 失败：${e.message ?: e.javaClass.simpleName}")
        }

    private fun controlCodingAgent(action: String, text: String, sessionId: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        val gate = CodaBotTiming.Gate()
        var ok = false
        var message = ""
        val callback: (Boolean, String) -> Unit = { success, detail ->
            // 超时后闸门已关：迟到回调不得再写回被捕获的状态（#157）。
            if (gate.claim()) {
                ok = success
                message = detail
                latch.countDown()
            }
        }
        when (action) {
            "create" -> codingAgent.newSession(cb = callback)
            "send" ->
                if (sessionId.isBlank()) {
                    codingAgent.send(text, cb = callback)
                } else {
                    codingAgent.sendTo(sessionId, text, cb = callback)
                }
            "stop" ->
                if (sessionId.isBlank()) {
                    codingAgent.stop(cb = callback)
                } else {
                    codingAgent.stopSession(sessionId, cb = callback)
                }
            "close" -> {
                // 未指定会话时只关闭宿主当前打开的那个。
                val target = sessionId.ifBlank { codingAgent.activeSessionId().orEmpty() }
                if (target.isBlank()) return result(false, "没有可关闭的 Coding Agent 会话")
                codingAgent.closeSession(target, cb = { success -> callback(success, if (success) "已关闭" else "关闭失败") })
            }
            else -> return result(false, "不支持的动作：$action")
        }
        val timedOut = !latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (timedOut) gate.close()
        val outcome = CodaBotTiming.waitOutcome(timedOut, ok, message, action)
        return pendingResult(outcome)
    }

    /**
     * 把等待结论转成给 Python 的 JSON。
     *
     * 超时额外带 pending 标记：调用方据此知道「已提交、结果待定」，不要重试（#157）。
     */
    private fun pendingResult(outcome: CodaBotTiming.WaitOutcome): String =
        JSONObject()
            .put("ok", outcome.ok)
            .put("message", outcome.message)
            .apply { if (outcome.pending) put("pending", true) }
            .toString()

    private fun sessionListJson(): String {
        val items = org.json.JSONArray()
        codingAgent.sessionSnapshot().forEach { session ->
            items.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("status", session.status)
                    .put("mode", session.mode)
                    .put("updated_at", session.updatedAt),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("current_session_id", codingAgent.activeSessionId().orEmpty())
            .put("sessions", items)
            .toString()
    }

    private fun sendToSessionBlocking(sessionId: String, text: String): String {
        if (sessionId.isBlank()) return result(false, "会话 id 为空")
        if (text.isBlank()) return result(false, "内容为空")
        val latch = java.util.concurrent.CountDownLatch(1)
        val gate = CodaBotTiming.Gate()
        var ok = false
        var detail = ""
        codingAgent.sendTo(sessionId, text) { success, message ->
            if (gate.claim()) {
                ok = success
                detail = message
                latch.countDown()
            }
        }
        val timedOut = !latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (timedOut) gate.close()
        // 超时不能报「发送超时」了事：消息可能已经发出去了，重试会重复发（#157）。
        val outcome =
            CodaBotTiming.waitOutcome(timedOut, ok, if (ok) "已发送" else detail.ifBlank { "发送失败" }, "send")
        return pendingResult(outcome)
    }

    private fun stopSessionBlocking(sessionId: String): String {
        if (sessionId.isBlank()) return result(false, "会话 id 为空")
        val latch = java.util.concurrent.CountDownLatch(1)
        val gate = CodaBotTiming.Gate()
        var ok = false
        var detail = ""
        codingAgent.stopSession(sessionId) { success, message ->
            if (gate.claim()) {
                ok = success
                detail = message
                latch.countDown()
            }
        }
        val timedOut = !latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (timedOut) gate.close()
        return pendingResult(
            CodaBotTiming.waitOutcome(timedOut, ok, if (ok) "已停止" else detail.ifBlank { "停止失败" }, "stop"),
        )
    }

    private fun modelListJson(sessionId: String): String {
        val target = sessionId.ifBlank { codingAgent.activeSessionId().orEmpty() }
        if (target.isBlank()) return result(false, "当前没有打开的 Coding Agent 会话")
        val latch = java.util.concurrent.CountDownLatch(1)
        var options: List<com.coda.mobileui.core.ZModelOption> = emptyList()
        codingAgent.modelOptionsFor(target) { list ->
            options = list
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) return result(false, "读取模型超时")
        val items = org.json.JSONArray()
        options.forEach { option ->
            val levels = org.json.JSONArray()
            option.levels.forEach { levels.put(JSONObject().put("value", it.value).put("label", it.label)) }
            items.put(
                JSONObject()
                    .put("provider_id", option.providerId)
                    .put("provider", option.providerLabel ?: option.providerId)
                    .put("model", option.modelId)
                    .put("label", option.label)
                    .put("default_level", option.defaultLevel ?: "")
                    .put("levels", levels),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("session_id", target)
            .put("models", items)
            .toString()
    }

    private fun applySessionModel(
        sessionId: String,
        providerId: String,
        modelId: String,
        level: String,
    ): String {
        if (providerId.isBlank() || modelId.isBlank()) return result(false, "provider 与 model 都不能为空")
        val target = sessionId.ifBlank { codingAgent.activeSessionId().orEmpty() }
        if (target.isBlank()) return result(false, "当前没有打开的 Coding Agent 会话")
        val latch = java.util.concurrent.CountDownLatch(1)
        val gate = CodaBotTiming.Gate()
        var ok = false
        var detail = ""
        codingAgent.setModelFor(target, providerId, modelId, level.takeIf { it.isNotBlank() }) { success, message ->
            if (gate.claim()) {
                ok = success
                detail = message
                latch.countDown()
            }
        }
        val timedOut = !latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (timedOut) gate.close()
        return pendingResult(
            CodaBotTiming.waitOutcome(timedOut, ok, detail.ifBlank { if (ok) "已切换" else "切换失败" }, "set_model"),
        )
    }

    /** 清掉过期与超量的待审批请求：这些请求不会再有应答，按拒绝放回 Coding Agent。 */
    private fun prunePendingPermissions() {
        val now = System.currentTimeMillis()
        val dropped = mutableListOf<PendingPermission>()
        synchronized(pendingPermissions) {
            val iterator = pendingPermissions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.at > pendingPermissionTtlMs) {
                    dropped += entry.value
                    iterator.remove()
                }
            }
            while (pendingPermissions.size >= pendingPermissionLimit) {
                val first = pendingPermissions.entries.firstOrNull() ?: break
                pendingPermissions.remove(first.key)
                dropped += first.value
            }
        }
        dropped.forEach { pending -> runCatching { codingAgent.respondPermission(pending.id, false) } }
        // 队列里还有剩的：按最早那条的截止时间继续排，不能就此停表。
        scheduleExpiry()
    }

    /**
     * 按**最早**待审批请求的截止时间排下一次扫描（#158）。
     *
     * 旧实现每来一条就 removeCallbacks + now+TTL，定时器只对最后一条负责，
     * 请求密集时最早那条会被无限推迟，Agent 一直卡在等审批。
     */
    private fun scheduleExpiry() {
        val delay =
            synchronized(pendingPermissions) {
                CodaBotTiming.nextExpiryDelay(
                    pendingPermissions.values.map { it.at },
                    System.currentTimeMillis(),
                    pendingPermissionTtlMs,
                )
            }
        expiryHandler.removeCallbacks(expiryTick)
        if (delay != null) expiryHandler.postDelayed(expiryTick, delay)
    }

    /** 退出页面时把没人应答的审批全按拒绝放掉，别让 Coding Agent 一直卡在等审批。 */
    private fun denyPendingPermissions() {
        val dropped = synchronized(pendingPermissions) {
            val all = pendingPermissions.values.toList()
            pendingPermissions.clear()
            all
        }
        dropped.forEach { pending -> runCatching { codingAgent.respondPermission(pending.id, false) } }
    }

    private fun resolvePermission(requestKey: String, decision: String): String {
        val pending = synchronized(pendingPermissions) { pendingPermissions.remove(requestKey) }
            ?: return result(false, "没有该请求标识对应的待审批请求")
        val allow = decision.trim().lowercase() in setOf("allow", "approve", "yes", "true")
        codingAgent.respondPermission(pending.id, allow)
        return result(true, if (allow) "已放行" else "已拒绝")
    }

    /**
     * 把宿主事件作为一条消息送进 MaiBot，触发规划轮次。
     *
     * OCR low #105：窗口内只计数、没有窗口结束的补发，会把「先完成、后失败」里的
     * 失败通知永久吞掉——规划模型以为任务成功。失败与审批属紧急事件，直接发送、
     * 不参与合并；其余合并事件在窗口结束时由 Handler 补发一条摘要。
     */
    private fun notifyHost(
        text: String,
        sessionId: String = "",
        urgent: Boolean = false,
    ) {
        // 设置里可以整体关掉：用户只想安静看 Coding Agent干活时不打扰。
        if (!CodaBotStore.forwardsCodingEvents(context)) return
        if (sessionId.isBlank() || urgent) {
            if (urgent) {
                synchronized(eventLock) {
                    // 紧急事件已单独送达，清掉该会话的待补发计数，避免重复摘要。
                    lastEventAt[sessionId] = System.currentTimeMillis()
                    mergedEvents.remove(sessionId)
                }
            }
            sendEvent(text)
            return
        }
        val now = System.currentTimeMillis()
        var mergedBefore = 0
        synchronized(eventLock) {
            val last = lastEventAt[sessionId] ?: 0L
            // 同一会话在窗口内的连续事件合并成一条，避免一次连发多轮规划。
            if (now - last < eventWindowMs) {
                mergedEvents[sessionId] = (mergedEvents[sessionId] ?: 1) + 1
                // 窗口结束时补发：只计数不补发（旧行为）会丢事件。
                if (mergedRunnables[sessionId] == null) {
                    val delayed = Runnable { flushMerged(sessionId) }
                    mergedRunnables[sessionId] = delayed
                    expiryHandler.postDelayed(delayed, eventWindowMs)
                }
                pruneEventTablesLocked()
                return
            }
            lastEventAt[sessionId] = now
            mergedBefore = (mergedEvents.remove(sessionId) ?: 1) - 1
            pruneEventTablesLocked()
        }
        sendEvent(if (mergedBefore > 0) "$text（同一会话另有 $mergedBefore 次同类事件已合并）" else text)
    }

    /** 窗口结束补发合并摘要（#105）。 */
    private fun flushMerged(sessionId: String) {
        val count =
            synchronized(eventLock) {
                mergedRunnables.remove(sessionId)
                lastEventAt.remove(sessionId)
                mergedEvents.remove(sessionId) ?: 0
            }
        if (count <= 0) return
        sendEvent("Coding Agent 会话在最近 ${eventWindowMs / 1000} 秒内另有 $count 次事件已合并，请复核该会话当前状态。")
    }

    /** 会话很多时表只增不减：超过阈值清掉最久未更新的那批（含待补发任务）。 */
    private fun pruneEventTablesLocked() {
        if (lastEventAt.size <= 64) return
        val stale =
            lastEventAt.entries
                .sortedBy { it.value }
                .take(lastEventAt.size - 32)
                .map { it.key }
        for (key in stale) {
            lastEventAt.remove(key)
            mergedEvents.remove(key)
            mergedRunnables.remove(key)?.let { expiryHandler.removeCallbacks(it) }
        }
    }

    private fun sendEvent(body: String) {
        Bg.run {
            runCatching {
                // 与 send 共用同一把锁（#118）：两者都会触发 Python 侧的 start()，
                // 并发进去会撞 register_driver（Broker 已启动时直接 raise），
                // 本次事件或消息就失败了。Bg 是共享线程池，不保证顺序。
                synchronized(sendLock) { bridge?.callAttr("notify_event", body) }
            }.onFailure { error ->
                com.coda.mobileui.core.AppLog.w(
                    "CodaBot",
                    "宿主事件注入失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun readContextFile(path: String): String {
        if (path.isBlank()) return result(false, "路径为空")
        val workspace = File(codingAgent.workspacePath()).canonicalFile
        val raw = File(path)
        // 只给相对路径时按工作区解析，容器内看到的路径与宿主是同一串。
        val file = (if (raw.isAbsolute) raw else File(workspace, path)).canonicalFile
        val allowedRoots = listOf(
            File(context.filesDir, "coda-bot").canonicalFile,
            workspace,
        )
        if (allowedRoots.none { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }) {
            return result(false, "只能读取附件目录或当前工作区文件")
        }
        if (!file.isFile) return result(false, "文件不存在")
        if (file.length() > 1_048_576L) return result(false, "文件超过 1MB 上限")
        return runCatching {
            JSONObject().put("ok", true).put("content", file.readText()).toString()
        }.getOrElse { result(false, it.message ?: "读取失败") }
    }

    /** 终端工具直接在 Coding Agent 的 Linux 容器里跑，工作目录固定为当前工作区。 */
    private fun runContextCommand(command: String, timeoutSeconds: Int): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return result(false, "命令为空")
        val timeout = timeoutSeconds.coerceIn(1, 30)
        val workspace = File(codingAgent.workspacePath()).apply { mkdirs() }
        val linux = LinuxEnv(context)
        if (!linux.isReady()) return result(false, "Coding Agent 的 Linux 环境尚未就绪")
        val guest = linux.guestPath(workspace.absolutePath)
        val script = "cd " + shellQuote(guest) + " && " + trimmed
        val shellResult = linux.exec(listOf("/bin/bash", "-lc", script), timeout.toLong() * 1000L)
        val text = shellResult.output.take(65_536)
        return JSONObject()
            .put("ok", shellResult.ok)
            .put("output", text)
            .put("message", if (shellResult.ok) "完成" else "退出码 ${shellResult.code}")
            .toString()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun result(ok: Boolean, message: String): String =
        JSONObject().put("ok", ok).put("message", message).toString()

    /**
     * 传给内核的供应商配置（#34）。
     *
     * 之前只透传 name/base_url/api_key/model，内核一律按 OpenAI + bearer 写配置，
     * 于是 anthropic-messages、doubao-web-anonymous 这类协议必然失败，
     * contextWindow 与 supportsToolCall 也只能吃内核默认值。
     */
    private fun providerConfig(providerId: String, model: String, task: String): String {
        val provider = ProviderStore.load(context).firstOrNull { it.id == providerId }
            ?: throw IllegalStateException("找不到模型供应商：$providerId")
        return JSONObject()
            .put("name", provider.name)
            .put("base_url", provider.baseUrl)
            .put("api_key", provider.apiKey)
            .put("model", model)
            .put("task", task)
            .put("api_type", provider.apiType)
            .put("context_window", provider.contextWindow)
            .put("supports_tool_call", provider.supportsToolCall)
            .toString()
    }

    private fun ensurePython(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance()
    }

    fun close() {
        codingAgent.removeListener(hostListener)
        expiryHandler.removeCallbacks(expiryTick)
        denyPendingPermissions()
        Bg.run {
            runCatching {
                // 与 send/notify_event 同一把锁（#118）：否则可能正在发送时把桥关掉。
                synchronized(sendLock) {
                    bridge?.callAttr("close")
                    bridge = null
                }
            }
        }
    }
}
