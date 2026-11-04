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
        fun listModels(): String
        fun selectModel(kind: String, providerId: String, model: String): String
        fun readFile(path: String): String
        fun runTerminal(command: String, timeoutSeconds: Int): String
    }

    @Volatile
    private var bridge: PyObject? = null
    private val codingAgent by lazy { ZController.get(context) }

    fun send(text: String, onError: (String) -> Unit) {
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

                    override fun listModels(): String = modelListJson()

                    override fun selectModel(kind: String, providerId: String, model: String): String =
                        applyModelSelection(kind, providerId, model)

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
            instance.callAttr("send", text)
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

    private fun modelListJson(): String {
        val items = org.json.JSONArray()
        ProviderStore.load(context)
            .filter { it.kind == ProviderStore.ProviderKind.LLM }
            .forEach { provider ->
                provider.models.forEach { model ->
                    items.put(
                        JSONObject()
                            .put("provider_id", provider.id)
                            .put("provider", provider.name)
                            .put("model", model),
                    )
                }
            }
        val p = CodaBotStore.profile(context)
        return JSONObject()
            .put("ok", true)
            .put("planner", "${p.plannerProviderId}/${p.plannerModel}")
            .put("reply", "${p.replyProviderId}/${p.replyModel}")
            .put("models", items)
            .toString()
    }

    private fun applyModelSelection(kind: String, providerId: String, model: String): String {
        if (providerId.isBlank() || model.isBlank()) return result(false, "provider 与 model 都不能为空")
        val provider = ProviderStore.load(context).firstOrNull { it.id == providerId }
            ?: return result(false, "找不到供应商：$providerId")
        if (model !in provider.models) return result(false, "该供应商没有模型：$model")
        when (kind) {
            "planner" -> {
                profile.plannerProviderId = providerId
                profile.plannerModel = model
            }
            "reply" -> {
                profile.replyProviderId = providerId
                profile.replyModel = model
            }
            else -> return result(false, "未知的模型类型：$kind，只支持 planner 或 reply")
        }
        CodaBotStore.saveProfile(context, profile)
        return result(true, "已切换到 $model")
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
