package com.coda.mobileui.core

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 受认证宿主桥：把 App 内已有的 ZController 能力暴露给 Coda 桌面节点。
 *
 * - 传输：TCP + NDJSON（一行一个 JSON-RPC 2.0 消息），默认监听 0.0.0.0:17654；
 * - 认证：首帧 node.hello 必须带 token；token 存 App 私有 SharedPreferences，
 *   常量时间比较；未认证连接只能调用 hello；
 * - 能力：会话列表/新建/打开、发送、停止、审批应答、状态与流式事件；
 * - 边界：不暴露无保护端口，不伪造桌面端能力；终端/附件/成果暂不声明。
 *
 * 该类不负责前台存活；由 [NodeBridgeService] 启动/停止，避免 UI 退出即断连。
 */
object NodeBridge {
    const val DEFAULT_PORT = 17654
    private const val PREFS = "node_bridge"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PORT = "port"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var controller: ZController? = null
    private var listener: ZController.Listener? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val pendingInteractions = ConcurrentHashMap<String, Any>()
    private val running = AtomicBoolean(false)

    @Volatile private var lastStatus: String = "未启动"
    @Volatile private var lastError: String? = null
    @Volatile private var boundPort: Int = 0

    fun status(): String = lastStatus
    fun lastFailure(): String? = lastError
    fun port(): Int = boundPort
    fun isRunning(): Boolean = running.get()
    fun clientCount(): Int = clients.size

    fun token(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    fun resetToken(ctx: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, fresh).apply()
        lastStatus = "令牌已重置，已断开现有连接"
        broadcast("bridge.reset", JSONObject())
        disconnectAll()
        return fresh
    }

    fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    /** 局域网 IPv4 地址；桌面端用 <IP>:<port> 连接。 */
    fun localAddresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val nis = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    if (ia is java.net.Inet4Address && !ia.isLoopbackAddress) out.add(ia.hostAddress ?: continue)
                }
            }
        }
        return out
    }

    fun configuredPort(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT).let {
            if (it in 1024..65535) it else DEFAULT_PORT
        }

    /** 启动服务；返回 null 表示成功，否则是错误原因。 */
    @Synchronized
    fun start(ctx: Context): String? {
        if (running.get()) return null
        lastError = null
        attach(ctx)
        token(ctx)
        val ctl = ZController.get(ctx)
        ctl.ensureStarted { _, _ -> }
        val socket =
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", configuredPort(ctx)))
                }
            } catch (error: Exception) {
                lastStatus = "启动失败"
                lastError = error.message ?: error.javaClass.simpleName
                return lastError
            }
        serverSocket = socket
        boundPort = socket.localPort
        controller = ctl
        installListener(ctl)
        running.set(true)
        lastStatus = "运行中"
        acceptThread = Thread({
            while (running.get()) {
                val socketClient =
                    try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                val client = Client(socketClient)
                clients.add(client)
                Thread({ serve(client) }, "coda-node-client").apply { isDaemon = true }.start()
            }
        }, "coda-node-accept").apply { isDaemon = true }
        acceptThread?.start()
        return null
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        disconnectAll()
        listener?.let { controller?.removeListener(it) }
        listener = null
        controller = null
        boundPort = 0
        lastStatus = "未启动"
    }

    private fun installListener(ctl: ZController) {
        if (listener != null) return
        val l =
            object : ZController.Listener {
                override fun onStateChanged() {
                    broadcast("state.changed", JSONObject())
                }

                override fun onDelta(
                    assistantMessageId: String,
                    text: String,
                ) {
                    broadcast(
                        "message.delta",
                        JSONObject()
                            .put("sessionId", ctl.activeSessionId())
                            .put("messageId", assistantMessageId)
                            .put("text", text),
                    )
                }

                override fun onReasoningDelta(
                    assistantMessageId: String,
                    text: String,
                ) {
                    broadcast(
                        "reasoning.delta",
                        JSONObject()
                            .put("sessionId", ctl.activeSessionId())
                            .put("messageId", assistantMessageId)
                            .put("text", text),
                    )
                }

                override fun onNotice(text: String) {
                    broadcast("notice", JSONObject().put("text", text))
                }

                override fun onPermissionRequest(
                    requestId: Any,
                    params: JSONObject,
                ) {
                    val token = requestId.toString()
                    pendingInteractions[token] = requestId
                    broadcast(
                        "interaction.request",
                        JSONObject()
                            .put("requestId", token)
                            .put("kind", "approval")
                            .put("params", params),
                    )
                }

                override fun onUserInputRequest(
                    requestId: Any,
                    params: JSONObject,
                ) {
                    val token = requestId.toString()
                    pendingInteractions[token] = requestId
                    broadcast(
                        "interaction.request",
                        JSONObject()
                            .put("requestId", token)
                            .put("kind", "question")
                            .put("params", params),
                    )
                }

                override fun onSessionTurnFinished(
                    sessionId: String,
                    status: String,
                ) {
                    broadcast("turn.finished", JSONObject().put("sessionId", sessionId).put("status", status))
                }

                override fun onSessionOpened(sessionId: String) {
                    broadcast("session.opened", JSONObject().put("sessionId", sessionId))
                }
            }
        listener = l
        ctl.addListener(l)
    }

    private fun broadcast(type: String, fields: JSONObject) {
        val frame = NodeBridgeProtocol.event(type, fields)
        for (client in clients) {
            if (!client.authenticated) continue
            try {
                client.send(frame)
            } catch (_: Exception) {
                client.close()
                clients.remove(client)
            }
        }
    }

    private fun disconnectAll() {
        for (client in clients) client.close()
        clients.clear()
    }

    private fun serve(client: Client) {
        try {
            client.socket.soTimeout = 0
            while (!client.socket.isClosed) {
                val line = client.reader.readLine() ?: break
                if (line.isBlank()) continue
                handleLine(client, line)
            }
        } catch (_: Exception) {
        } finally {
            client.close()
            clients.remove(client)
        }
    }

    private fun handleLine(
        client: Client,
        line: String,
    ) {
        val request = NodeBridgeProtocol.parse(line)
        if (request == null) {
            client.send(NodeBridgeProtocol.error(null, NodeBridgeProtocol.ERR_PARSE, "invalid JSON"))
            return
        }
        val id = request.opt("id")
        val method = request.optString("method", "")
        val params = request.optJSONObject("params") ?: JSONObject()
        if (method == NodeBridgeProtocol.METHOD_HELLO) {
            val version = params.optInt("protocolVersion", 1)
            if (version != NodeBridgeProtocol.VERSION) {
                client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_VERSION, "unsupported protocol version $version"))
                return
            }
            val expected = token(client.context)
            if (!NodeBridgeProtocol.tokenMatches(expected, params.optString("token", ""))) {
                client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_UNAUTHORIZED, "invalid token"))
                return
            }
            client.authenticated = true
            client.send(NodeBridgeProtocol.result(id, hello(client.context)))
            return
        }
        if (!client.authenticated) {
            client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_UNAUTHORIZED, "hello required"))
            return
        }
        val ctl = controller
        if (ctl == null) {
            client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, "bridge not running"))
            return
        }
        when (method) {
            NodeBridgeProtocol.METHOD_SNAPSHOT -> client.send(NodeBridgeProtocol.result(id, snapshot(ctl)))
            NodeBridgeProtocol.METHOD_PING -> client.send(NodeBridgeProtocol.result(id, JSONObject().put("pong", true)))
            NodeBridgeProtocol.METHOD_SESSION_NEW -> {
                val workspace = params.optString("workspace", "").takeIf { it.isNotBlank() }
                ctl.newSession(workspace) { ok, message ->
                    if (ok) client.send(NodeBridgeProtocol.result(id, JSONObject().put("ok", true).put("sessionId", ctl.activeSessionId())))
                    else client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, message))
                }
            }
            NodeBridgeProtocol.METHOD_SESSION_OPEN -> {
                val sessionId = params.optString("sessionId", "")
                if (sessionId.isBlank()) {
                    client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_PARAMS, "sessionId required"))
                } else {
                    ctl.openSession(sessionId) { ok ->
                        if (ok) client.send(NodeBridgeProtocol.result(id, JSONObject().put("ok", true)))
                        else client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_NOT_FOUND, "cannot open session"))
                    }
                }
            }
            NodeBridgeProtocol.METHOD_TURN_SEND -> {
                val sessionId = params.optString("sessionId", "")
                val text = params.optString("text", "")
                if (sessionId.isBlank() || text.isBlank()) {
                    client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_PARAMS, "sessionId and text required"))
                } else {
                    ctl.sendTo(sessionId, text) { ok, message ->
                        if (ok) client.send(NodeBridgeProtocol.result(id, JSONObject().put("ok", true)))
                        else client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, message))
                    }
                }
            }
            NodeBridgeProtocol.METHOD_TURN_STOP -> {
                val sessionId = params.optString("sessionId", "")
                if (sessionId.isBlank()) {
                    client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_PARAMS, "sessionId required"))
                } else {
                    ctl.stopSession(sessionId) { ok, message ->
                        if (ok) client.send(NodeBridgeProtocol.result(id, JSONObject().put("ok", true)))
                        else client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, message))
                    }
                }
            }
            NodeBridgeProtocol.METHOD_INTERACTION_ANSWER -> {
                val token = params.optString("requestId", "")
                val allow = params.optBoolean("allow", false)
                val original = pendingInteractions.remove(token)
                if (original == null) {
                    client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_NOT_FOUND, "unknown interaction"))
                } else {
                    ctl.respondPermission(original, allow)
                    client.send(NodeBridgeProtocol.result(id, JSONObject().put("ok", true)))
                }
            }
            else -> client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_METHOD, "unknown method: $method"))
        }
    }

    private fun hello(ctx: Context): JSONObject {
        val versionName = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return JSONObject()
            .put("protocolVersion", NodeBridgeProtocol.VERSION)
            .put("node", JSONObject()
                .put("id", deviceId(ctx))
                .put("name", "Coda Android")
                .put("appVersion", versionName)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL))
            .put("capabilities", JSONObject()
                .put("sessions", true)
                .put("turns", true)
                .put("streaming", true)
                .put("approvals", true)
                .put("questions", true)
                .put("terminal", false)
                .put("attachments", false)
                .put("artifacts", false))
            .put("sessions", snapshot(controller))
    }

    private fun snapshot(ctl: ZController?): JSONArray {
        val array = JSONArray()
        for (session in ctl?.sessionSnapshot() ?: emptyList()) {
            array.put(JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("status", session.status)
                .put("mode", session.mode)
                .put("updatedAt", session.updatedAt)
                .put("workspace", session.workspacePath))
        }
        return array
    }

    private inner class Client(val socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        @Volatile var authenticated = false
        val context: Context get() = appContext ?: throw IllegalStateException("bridge has no context")

        fun send(frame: JSONObject) {
            synchronized(writer) {
                writer.write(frame.toString())
                writer.newLine()
                writer.flush()
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private var appContext: Context? = null

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }
}
