package com.coda.mobileui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.coda.mobileui.core.Bg
import com.coda.mobileui.core.DeviceStore
import com.coda.mobileui.core.NodeBridgeClient
import com.coda.mobileui.core.NodeBridgeException
import com.google.android.material.color.MaterialColors
import org.json.JSONObject

/**
 * 在另一台 Coda 节点上跑会话：连接、创建/打开会话、发送、流式回显、停止。
 *
 * 与主界面互不影响：主界面的 ZController 只服务本机，远端会话在这里独立维护。
 * 入口：输入框上方的“运行地点”选中远端设备后发送；抽屉里点远端缓存会话也走这里。
 */
class RemoteSessionActivity : BaseActivity() {
    private lateinit var device: DeviceStore.Device
    private var client: NodeBridgeClient? = null
    private var sessionId: String = ""
    private var running = false
    private var connecting = false

    private lateinit var messagesHost: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Row>()
    private val live = LinkedHashMap<String, StringBuilder>()

    private data class Row(val role: String, val text: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        device = DeviceStore.all(this).firstOrNull { it.id == id } ?: run {
            Toast.makeText(this, "设备不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        buildUi()
        connect()
    }

    private fun buildUi() {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val subtle = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val primary = MaterialColors.getColor(this, android.R.attr.colorAccent, Color.CYAN)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(onSurface)
            setPadding(dp(10), dp(2), dp(12), dp(2))
            setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = device.name
            textSize = 17f
            setTextColor(onSurface)
        }
        status = TextView(this).apply {
            text = "正在连接…"
            textSize = 12f
            setTextColor(subtle)
        }
        titles.addView(title)
        titles.addView(status)
        bar.addView(back)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        scroll = ScrollView(this)
        messagesHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(16))
        }
        scroll.addView(messagesHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "发消息给 ${device.name}"
            textSize = 15f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(onSurface)
            setHintTextColor(subtle)
        }
        sendButton = Button(this).apply {
            text = "发送"
            isEnabled = false
            setOnClickListener { send() }
        }
        val stop = Button(this).apply {
            text = "停止"
            setOnClickListener { stopTurn() }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(sendButton)
        composer.addView(stop)
        root.addView(composer)

        setContentView(root)
        // 顶部状态条颜色跟随主色，避免远端页面看起来像“另一个应用”。
        back.setTextColor(primary)
    }

    private fun connect() {
        if (connecting) return
        connecting = true
        setStatus("正在连接…")
        Bg.run {
            try {
                val node = NodeBridgeClient.connect(device)
                node.onEvent = { payload -> ui.post { onEvent(payload) } }
                node.hello(device.token)
                client = node
                if (sessionId.isNotBlank()) {
                    setStatus("正在打开会话…")
                    val ok = node.sessionOpen(sessionId)
                    if (!ok) throw NodeBridgeException("远端拒绝打开会话")
                    val messages = node.messages(sessionId)
                    ui.post {
                        rows.clear()
                        for (i in 0 until messages.length()) {
                            val m = messages.optJSONObject(i) ?: continue
                            rows += Row(m.optString("role", "assistant"), m.optString("text", ""))
                        }
                        renderMessages()
                        setReady()
                    }
                } else {
                    setStatus("正在创建会话…")
                    val workspace = intent.getStringExtra(EXTRA_WORKSPACE)
                    val created = node.sessionNew(workspace)
                    if (created.isBlank()) throw NodeBridgeException("远端未返回会话 id")
                    sessionId = created
                    ui.post { setReady() }
                }
                val initial = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (initial.isNotBlank()) {
                    ui.post { input.setText(initial); send() }
                }
            } catch (error: Throwable) {
                // 连不上时退回本机缓存，至少能读上次同步到的内容。
                val cached = runCatching {
                    if (sessionId.isBlank()) null else DeviceStore.cachedMessages(this, device.id, sessionId)
                }.getOrNull()
                ui.post {
                    sendButton.isEnabled = false
                    if (cached != null && cached.length() > 0) {
                        rows.clear()
                        for (i in 0 until cached.length()) {
                            val m = cached.optJSONObject(i) ?: continue
                            rows += Row(m.optString("role", "assistant"), m.optString("text", ""))
                        }
                        renderMessages()
                        status.text = "离线 · 显示缓存（${error.message}）"
                    } else {
                        status.text = "连接失败：${error.message}"
                    }
                }
            } finally {
                connecting = false
            }
        }
    }

    private fun setReady() {
        status.text = "已连接 · ${device.label.ifBlank { device.host }}"
        sendButton.isEnabled = true
    }

    private fun setStatus(text: String) {
        ui.post { status.text = text }
    }

    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val node = client ?: return
        if (sessionId.isBlank()) return
        input.setText("")
        rows += Row("user", text)
        rows += Row("assistant", "")
        renderMessages()
        running = true
        setStatus("正在运行…")
        Bg.run {
            try {
                node.sendTurn(sessionId, text)
            } catch (error: Throwable) {
                ui.post {
                    running = false
                    setStatus("发送失败：${error.message}")
                }
            }
        }
    }

    private fun stopTurn() {
        val node = client ?: return
        if (!running || sessionId.isBlank()) return
        Bg.run {
            try {
                node.turnStop(sessionId)
            } catch (error: Throwable) {
                ui.post { setStatus("停止失败：${error.message}") }
            }
        }
    }

    private fun onEvent(payload: JSONObject) {
        // 同一节点可能还有别的会话在跑，事件按会话过滤。
        val eventSession = payload.optString("sessionId", "")
        if (eventSession.isNotBlank() && sessionId.isNotBlank() && eventSession != sessionId) return
        when (payload.optString("type")) {
            "message.delta" -> {
                val id = payload.optString("messageId", "live")
                live.getOrPut(id) { StringBuilder() }.append(payload.optString("text", ""))
                updateLive()
            }
            "reasoning.delta" -> Unit
            "turn.finished" -> {
                running = false
                live.clear()
                setStatus("已完成")
                reload()
            }
            "notice" -> setStatus(payload.optString("text", "远端提示"))
            "state.changed" -> Unit
            "interaction.request" -> askApproval(payload)
        }
    }

    /** 增量文本落到最后一个助手气泡上，避免整页重绘。 */
    private fun updateLive() {
        val buf = live.values.lastOrNull()?.toString().orEmpty()
        if (buf.isEmpty()) return
        val last = messagesHost.getChildAt(messagesHost.childCount - 1) as? TextView ?: return
        last.text = buf
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun askApproval(payload: JSONObject) {
        val requestId = payload.optString("requestId")
        if (requestId.isBlank()) return
        val params = payload.optJSONObject("params")?.toString().orEmpty()
        val node = client ?: return
        AlertDialog.Builder(this)
            .setTitle("远端请求审批")
            .setMessage(params.take(800))
            .setPositiveButton("允许") { _, _ ->
                Bg.run { runCatching { node.answerInteraction(requestId, true) } }
            }
            .setNegativeButton("拒绝") { _, _ ->
                Bg.run { runCatching { node.answerInteraction(requestId, false) } }
            }
            .show()
    }

    /** 回合结束后取一次权威消息，避免增量丢失导致展示不全。 */
    private fun reload() {
        val node = client ?: return
        if (sessionId.isBlank()) return
        Bg.run {
            val list = runCatching { node.messages(sessionId) }.getOrNull() ?: return@run
            ui.post {
                rows.clear()
                for (i in 0 until list.length()) {
                    val m = list.optJSONObject(i) ?: continue
                    val role = m.optString("role", "assistant")
                    val text = m.optString("text", "")
                    if (text.isNotBlank()) rows += Row(role, text)
                }
                renderMessages()
            }
        }
    }

    private fun renderMessages() {
        messagesHost.removeAllViews()
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val subtle = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        if (rows.isEmpty()) {
            messagesHost.addView(
                TextView(this).apply {
                    text = "在 ${device.name} 上开始新会话"
                    textSize = 13f
                    setTextColor(subtle)
                    setPadding(dp(2), dp(24), dp(2), dp(24))
                },
            )
        }
        for (row in rows) {
            messagesHost.addView(
                TextView(this).apply {
                    text = row.text.ifEmpty { "…" }
                    textSize = 15f
                    setTextColor(if (row.role == "user") onSurface else onSurface)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    if (row.role == "user") {
                        setBackgroundColor(Color.argb(24, 128, 128, 128))
                    }
                },
            )
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        runCatching { client?.close() }
        client = null
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TEXT = "text"
        const val EXTRA_WORKSPACE = "workspace"
    }
}
