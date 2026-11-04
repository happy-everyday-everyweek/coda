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
        fun approveTool(decision: String): String
        fun readFile(path: String): String
        fun runTerminal(command: String, timeoutSeconds: Int): String
    }

    @Volatile
    private var bridge: PyObject? = null
    private val codingAgent by lazy { ZController.get(context) }

    /** 待应答的审批请求；同一时刻只保留最新一条。 */
    @Volatile
    private var pendingPermission: Any? = null

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
                val state = if (status == "turn.failed") "回合失败" else "已完成"
                notifyHost("Coding Agent 会话「$title」$state，请复核成果后决定是否告知用户。")
            }

            override fun onPermissionRequest(requestId: Any, params: JSONObject) {
                pendingPermission = requestId
                val tool = params.optString("toolName").ifBlank { params.optString("tool") }
                notifyHost("Coding Agent 请求审批：工具「$tool」。请调用 approve_tool 决定放行或拒绝。")
            }
        }

    init {
        codingAgent.addListener(hostListener)
    }

    fun send(text: String, replyToId: String = "", onError: (String) -> Unit) {
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

                    override fun controlSession(action: String, text: String, sessionId: String): String {
                        return runCatching { controlCodingAgent(action, text, sessionId) }
                            .getOrElse { failure -> result(false, failure.message ?: "控制失败") }
                    }

                    override fun listSessions(): String = sessionListJson()

                    override fun sendToSession(sessionId: String, text: String): String =
                        sendToSessionBlocking(sessionId, text)

                    override fun stopSession(sessionId: String): String =
                        stopSessionBlocking(sessionId)

                    override fun listModels(sessionId: String): String = modelListJson(sessionId)

                    override fun selectModel(
                        sessionId: String,
                        providerId: String,
                        modelId: String,
                        level: String,
                    ): String = applySessionModel(sessionId, providerId, modelId, level)

                    override fun approveTool(decision: String): String = resolvePermission(decision)

                    override fun readFile(path: String): String = readContextFile(path)

                    override fun runTerminal(command: String, timeoutSeconds: Int): String =
                        runContextCommand(command, timeoutSeconds)
                },
                profile.name,
                profile.userName,
                listOf(profile.persona, profile.systemPrompt).filter { it.isNotBlank() }.joinToString("\n\n"),
                profile.userAbout,
                providerConfig(profile.plannerProviderId, profile.plannerModel, "planner"),
                providerConfig(profile.replyProviderId, profile.replyModel, "reply"),
            ).also { bridge = it }
            }
            instance.callAttr("send", text, replyToId)
        } catch (t: Throwable) {
            onError(t.message ?: t.javaClass.simpleName)
        }
        }
    }

    private fun controlCodingAgent(action: String, text: String, sessionId: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var message = ""
        val callback: (Boolean, String) -> Unit = { success, detail ->
            ok = success
            message = detail
            latch.countDown()
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
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            return result(false, "Coding Agent 操作超时")
        }
        return result(ok, message)
    }

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
        var ok = false
        var detail = ""
        codingAgent.sendTo(sessionId, text) { success, message ->
            ok = success
            detail = message
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) return result(false, "发送超时")
        return result(ok, if (ok) "已发送" else detail.ifBlank { "发送失败" })
    }

    private fun stopSessionBlocking(sessionId: String): String {
        if (sessionId.isBlank()) return result(false, "会话 id 为空")
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var detail = ""
        codingAgent.stopSession(sessionId) { success, message ->
            ok = success
            detail = message
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) return result(false, "停止超时")
        return result(ok, if (ok) "已停止" else detail.ifBlank { "停止失败" })
    }

    private fun modelListJson(sessionId: String): String {
        if (sessionId.isBlank()) return result(false, "会话 id 为空")
        val latch = java.util.concurrent.CountDownLatch(1)
        var options: List<com.coda.mobileui.core.ZModelOption> = emptyList()
        codingAgent.modelOptionsFor(sessionId) { list ->
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
            .put("session_id", sessionId)
            .put("models", items)
            .toString()
    }

    private fun applySessionModel(
        sessionId: String,
        providerId: String,
        modelId: String,
        level: String,
    ): String {
        if (sessionId.isBlank()) return result(false, "会话 id 为空")
        if (providerId.isBlank() || modelId.isBlank()) return result(false, "provider 与 model 都不能为空")
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var detail = ""
        codingAgent.setModelFor(sessionId, providerId, modelId, level.takeIf { it.isNotBlank() }) { success, message ->
            ok = success
            detail = message
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) return result(false, "切换模型超时")
        return result(ok, detail.ifBlank { if (ok) "已切换" else "切换失败" })
    }

    private fun resolvePermission(decision: String): String {
        val requestId = pendingPermission ?: return result(false, "当前没有待审批的请求")
        val allow = decision.trim().lowercase() in setOf("allow", "approve", "yes", "true")
        pendingPermission = null
        codingAgent.respondPermission(requestId, allow)
        return result(true, if (allow) "已放行" else "已拒绝")
    }

    /** 把宿主事件作为一条消息送进 MaiBot，触发规划轮次。 */
    private fun notifyHost(text: String) {
        Bg.run {
            runCatching { bridge?.callAttr("notify_event", text) }
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

    private fun providerConfig(providerId: String, model: String, task: String): String {
        val provider = ProviderStore.load(context).firstOrNull { it.id == providerId }
            ?: throw IllegalStateException("找不到模型供应商：$providerId")
        return JSONObject()
            .put("name", provider.name)
            .put("base_url", provider.baseUrl)
            .put("api_key", provider.apiKey)
            .put("model", model)
            .put("task", task)
            .toString()
    }

    private fun ensurePython(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance()
    }

    fun close() {
        codingAgent.removeListener(hostListener)
        Bg.run {
            runCatching {
                synchronized(this@CodaBotRuntime) {
                    bridge?.callAttr("close")
                    bridge = null
                }
            }
        }
    }
}
