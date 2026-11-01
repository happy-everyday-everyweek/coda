package com.coda.mobileui.personal

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.coda.mobileui.core.Bg
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

        fun controlSession(action: String, text: String): String
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

                    override fun controlSession(action: String, text: String): String {
                        return runCatching { controlCodingAgent(action, text) }
                            .getOrElse { failure -> result(false, failure.message ?: "控制失败") }
                    }
                },
                profile.name,
                profile.userName,
                profile.persona,
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

    private fun controlCodingAgent(action: String, text: String): String {
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
            "send" -> codingAgent.send(text, cb = callback)
            "stop" -> codingAgent.stop(cb = callback)
            "close" -> {
                // close 只允许关闭当前打开的 Coding Agent 会话。
                val sessionId = codingAgent.activeSessionId()
                if (sessionId.isNullOrBlank()) return result(false, "当前没有打开的 Coding Agent 会话")
                codingAgent.closeSession(sessionId, cb = { success -> callback(success, if (success) "已关闭" else "关闭失败") })
            }
            else -> return result(false, "不支持的动作：$action")
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            return result(false, "Coding Agent 操作超时")
        }
        return result(ok, message)
    }

    private fun result(ok: Boolean, message: String): String =
        JSONObject().put("ok", ok).put("message", message).toString()

    private fun currentCodingSessionId(): String =
        context.getSharedPreferences("zcode_bridge", Context.MODE_PRIVATE).getString("current_session", "") ?: ""

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