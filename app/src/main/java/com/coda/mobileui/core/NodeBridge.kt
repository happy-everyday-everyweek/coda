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
    /** 认证前的单帧上限：hello 只是一小段 JSON，超过就不该再读下去。 */
    private const val MAX_PREAUTH_LINE = 16 * 1024
    /** 审批请求的保留时长：控制器超时或会话结束不会再回调，留着只会涨内存。 */
    private const val INTERACTION_TTL_MS = 10 * 60_000L
    private const val PREFS = "node_bridge"
    private const val KEY_TOKEN = "token"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEY_PAIR_CODE = "pair_code"
    private const val KEY_PAIR_EXPIRES = "pair_code_expires"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PORT = "port"
    /** 配对码有效期：短到够用，长到不至于边看边过期。 */
    private const val PAIR_CODE_TTL_MS = 5 * 60_000L

    private data class Interaction(val requestId: Any, val at: Long)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var controller: ZController? = null
    private var listener: ZController.Listener? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val pendingInteractions = ConcurrentHashMap<String, Interaction>()
    private val receipts = CommandReceipts()
    private val credentialsCodec = NodeCredentials()
    private val running = AtomicBoolean(false)

    @Volatile private var lastStatus: String = "未启动"
    @Volatile private var lastError: String? = null
    @Volatile private var boundPort: Int = 0

    fun status(): String = lastStatus
    fun lastFailure(): String? = lastError
    fun port(): Int = boundPort
    fun isRunning(): Boolean = running.get()
    fun clientCount(): Int = clients.size

    private fun prefsOf(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读凭据表；老版本只有单个 token，这里做一次迁移，把它当作「默认凭据」。 */
    private fun loadCredentials(ctx: Context): List<NodeCredentials.Credential> {
        val prefs = prefsOf(ctx)
        val raw = prefs.getString(KEY_CREDENTIALS, null)
        if (!raw.isNullOrBlank()) return credentialsCodec.fromJson(raw)
        val legacy = prefs.getString(KEY_TOKEN, null)
        if (legacy.isNullOrBlank()) return emptyList()
        val migrated =
            NodeCredentials.Credential(
                id = "cred_legacy",
                label = "默认凭据",
                token = legacy,
                createdAt = System.currentTimeMillis(),
            )
        val list = listOf(migrated)
        prefs.edit().putString(KEY_CREDENTIALS, credentialsCodec.toJson(list)).remove(KEY_TOKEN).apply()
        return list
    }

    private fun saveCredentials(
        ctx: Context,
        list: List<NodeCredentials.Credential>,
    ) {
        prefsOf(ctx).edit().putString(KEY_CREDENTIALS, credentialsCodec.toJson(list)).apply()
    }

    /** 当前授权设备列表（含已撤销条目，界面按 revoked 区分）。 */
    fun credentials(ctx: Context): List<NodeCredentials.Credential> = loadCredentials(ctx)

    fun deviceFingerprint(ctx: Context): String = credentialsCodec.deviceFingerprint(deviceId(ctx))

    /** 仍在生效的第一条凭据；没有就新发一条，保证设置页始终有东西可展示。 */
    fun token(ctx: Context): String {
        val list = loadCredentials(ctx)
        list.firstOrNull { !it.revoked }?.let { return it.token }
        val (next, credential) = credentialsCodec.issue(list, "默认凭据", System.currentTimeMillis())
        saveCredentials(ctx, next)
        return credential.token
    }

    /** 生成一次性配对码，桌面端用它换取自己的凭据。 */
    fun pairingCode(ctx: Context): String {
        val code = credentialsCodec.pairingCode()
        prefsOf(ctx).edit()
            .putString(KEY_PAIR_CODE, code)
            .putLong(KEY_PAIR_EXPIRES, System.currentTimeMillis() + PAIR_CODE_TTL_MS)
            .apply()
        return code
    }

    /** 用配对码签发一条新凭据；码错或过期返回 null。 */
    fun pair(
        ctx: Context,
        code: String,
        label: String,
    ): NodeCredentials.Credential? {
        val prefs = prefsOf(ctx)
        val expected = prefs.getString(KEY_PAIR_CODE, null) ?: return null
        if (prefs.getLong(KEY_PAIR_EXPIRES, 0L) < System.currentTimeMillis()) return null
        if (!NodeBridgeProtocol.tokenMatches(expected, code)) return null
        val (next, credential) = credentialsCodec.issue(loadCredentials(ctx), label, System.currentTimeMillis())
        saveCredentials(ctx, next)
        prefs.edit().remove(KEY_PAIR_CODE).remove(KEY_PAIR_EXPIRES).apply()
        return credential
    }

    /** 按凭据撤销：只断开这条凭据的连接，别的设备不受影响。 */
    fun revoke(
        ctx: Context,
        credentialId: String,
    ) {
        saveCredentials(ctx, credentialsCodec.revoke(loadCredentials(ctx), credentialId))
        for (client in clients) {
            if (client.credentialId == credentialId) {
                client.close()
                clients.remove(client)
            }
        }
        lastStatus = "已撤销一条授权，相关连接已断开"
        broadcast("bridge.revoke", JSONObject().put("credentialId", credentialId))
    }

    /** 撤销全部凭据并新发一条，用于「重置令牌」。 */
    fun resetToken(ctx: Context): String {
        val revokedAll = loadCredentials(ctx).map { it.copy(revoked = true) }
        val (next, credential) = credentialsCodec.issue(revokedAll, "默认凭据", System.currentTimeMillis())
        saveCredentials(ctx, next)
        lastStatus = "已撤销全部授权，现有连接已断开"
        broadcast("bridge.reset", JSONObject())
        disconnectAll()
        return credential.token
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
                    } catch (error: Exception) {
                        // 停服时是预期异常；其余情况不能静默退出，否则状态还写着「运行中」但不再接受连接。
                        if (!running.get()) break
                        lastError = error.message ?: error.javaClass.simpleName
                        runCatching { Thread.sleep(50) }
                        continue
                    }
                val client = Client(socketClient, ctx.applicationContext)
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
                    pruneInteractions()
                    pendingInteractions[token] = Interaction(requestId, System.currentTimeMillis())
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
                    pruneInteractions()
                    pendingInteractions[token] = Interaction(requestId, System.currentTimeMillis())
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
        for (client in clients) {
            if (!client.authenticated) continue
            try {
                client.send(NodeBridgeProtocol.event(type, fields, client.nextSeq()))
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
                // 认证前只允许一小段 hello：没有上限时，一个未认证连接就能用超长行撑爆内存。
                val line = if (client.authenticated) client.reader.readLine() else readBoundedLine(client.reader)
                if (line == null) {
                    if (!client.authenticated) {
                        client.send(NodeBridgeProtocol.error(null, NodeBridgeProtocol.ERR_PARSE, "hello frame too large"))
                    }
                    break
                }
                if (line.isBlank()) continue
                handleLine(client, line)
            }
        } catch (_: Exception) {
        } finally {
            client.close()
            clients.remove(client)
        }
    }

    /**
     * 读一行，最多 [MAX_PREAUTH_LINE] 个字符；超限返回 null。
     *
     * 只在认证前使用：hello 帧很短，没有上限时对端可以用一条不换行的长帧吃光内存。
     */
    private fun readBoundedLine(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val code = reader.read()
            if (code == -1) return if (sb.isEmpty()) null else sb.toString()
            if (code == '\n'.code) return sb.toString()
            if (sb.length >= MAX_PREAUTH_LINE) return null
            if (code != '\r'.code) sb.append(code.toChar())
        }
    }

    /**
     * 同一 commandId 已有收据时直接回原结果，不重复执行（CONTRACT §3、SPEC 11.5）。
     * 返回 true 表示已回复，调用方不应再执行命令。
     */
    private fun duplicateReceipt(
        client: Client,
        id: Any?,
        commandId: String,
    ): Boolean {
        if (commandId.isBlank()) return false
        val entry = receipts.get(commandId) ?: return false
        val result = entry.result
        if (result != null && entry.errorCode == null) {
            client.send(NodeBridgeProtocol.result(id, JSONObject(result.toString()).put("duplicate", true)))
        } else {
            client.send(
                NodeBridgeProtocol.error(id, entry.errorCode ?: NodeBridgeProtocol.ERR_INTERNAL, entry.errorMessage ?: "duplicate command"),
            )
        }
        return true
    }

    private fun rememberResult(
        commandId: String,
        result: JSONObject,
    ) {
        receipts.put(commandId, CommandReceipts.Entry(result = result))
    }

    private fun rememberError(
        commandId: String,
        code: Int,
        message: String,
    ) {
        receipts.put(commandId, CommandReceipts.Entry(errorCode = code, errorMessage = message))
    }

    private fun pruneInteractions() {
        val cutoff = System.currentTimeMillis() - INTERACTION_TTL_MS
        pendingInteractions.entries.removeAll { it.value.at < cutoff }
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
        if (method == NodeBridgeProtocol.METHOD_PAIR) {
            // 配对码是一次性凭证：用它换一条只属于这台桌面端的凭据。
            val credential = pair(client.context, params.optString("code", ""), params.optString("clientName", "桌面端"))
            if (credential == null) {
                client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_UNAUTHORIZED, "pairing code invalid or expired"))
            } else {
                client.send(
                    NodeBridgeProtocol.result(
                        id,
                        JSONObject()
                            .put("ok", true)
                            .put("credentialId", credential.id)
                            .put("token", credential.token)
                            .put("deviceId", deviceId(client.context))
                            .put("fingerprint", deviceFingerprint(client.context)),
                    ),
                )
            }
            return
        }
        if (method == NodeBridgeProtocol.METHOD_HELLO) {
            val version = params.optInt("protocolVersion", 1)
            if (version != NodeBridgeProtocol.VERSION) {
                client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_VERSION, "unsupported protocol version $version"))
                return
            }
            val list = loadCredentials(client.context)
            val credential = credentialsCodec.findByToken(list, params.optString("token", ""))
            if (credential == null) {
                client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_UNAUTHORIZED, "invalid token"))
                return
            }
            client.authenticated = true
            client.credentialId = credential.id
            saveCredentials(client.context, credentialsCodec.markUsed(list, credential.id, System.currentTimeMillis()))
            client.send(NodeBridgeProtocol.result(id, hello(client.context, credential.id)))
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
        val commandId = params.optString("commandId", "")
        val mutating =
            method == NodeBridgeProtocol.METHOD_SESSION_NEW ||
                method == NodeBridgeProtocol.METHOD_SESSION_OPEN ||
                method == NodeBridgeProtocol.METHOD_TURN_SEND ||
                method == NodeBridgeProtocol.METHOD_TURN_STOP ||
                method == NodeBridgeProtocol.METHOD_INTERACTION_ANSWER
        // 重投同一 commandId：回原收据，不再执行一遍。
        if (mutating && duplicateReceipt(client, id, commandId)) return
        when (method) {
            NodeBridgeProtocol.METHOD_SNAPSHOT -> client.send(NodeBridgeProtocol.result(id, snapshot(ctl)))
            NodeBridgeProtocol.METHOD_PING -> client.send(NodeBridgeProtocol.result(id, JSONObject().put("pong", true)))
            NodeBridgeProtocol.METHOD_SESSION_NEW -> {
                val workspace = params.optString("workspace", "").takeIf { it.isNotBlank() }
                ctl.newSession(workspace) { ok, message ->
                    if (ok) {
                        val result = JSONObject().put("ok", true).put("sessionId", ctl.activeSessionId())
                        rememberResult(commandId, result)
                        client.send(NodeBridgeProtocol.result(id, result))
                    } else {
                        rememberError(commandId, NodeBridgeProtocol.ERR_INTERNAL, message)
                        client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, message))
                    }
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
                        if (ok) {
                            val result = JSONObject().put("ok", true)
                            rememberResult(commandId, result)
                            client.send(NodeBridgeProtocol.result(id, result))
                        } else {
                            rememberError(commandId, NodeBridgeProtocol.ERR_INTERNAL, message)
                            client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_INTERNAL, message))
                        }
                    }
                }
            }
            NodeBridgeProtocol.METHOD_MESSAGES -> {
                val sessionId = params.optString("sessionId", "")
                if (sessionId.isBlank()) {
                    client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_PARAMS, "sessionId required"))
                } else {
                    ctl.loadMessages(sessionId) { ok, messages ->
                        if (!ok) {
                            client.send(NodeBridgeProtocol.error(id, NodeBridgeProtocol.ERR_NOT_FOUND, "cannot read messages"))
                        } else {
                            val array = JSONArray()
                            for (message in messages) {
                                array.put(
                                    JSONObject()
                                        .put("id", message.id)
                                        .put("role", message.role)
                                        .put("text", message.text())
                                        .put("createdAt", message.createdAt)
                                        .put("completedAt", message.completedAt),
                                )
                            }
                            client.send(NodeBridgeProtocol.result(id, JSONObject().put("messages", array)))
                        }
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
                val original = pendingInteractions.remove(token)?.requestId
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

    private fun hello(
        ctx: Context,
        credentialId: String,
    ): JSONObject {
        val versionName = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return JSONObject()
            .put("protocolVersion", NodeBridgeProtocol.VERSION)
            .put("credentialId", credentialId)
            .put("node", JSONObject()
                .put("id", deviceId(ctx))
                .put("fingerprint", deviceFingerprint(ctx))
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

    private class Client(val socket: Socket, val context: Context) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private val seq = java.util.concurrent.atomic.AtomicLong(0)
        @Volatile var authenticated = false
        /** 这条连接用的是哪条凭据：撤销时据此只断掉受影响的设备。 */
        @Volatile var credentialId: String? = null

        /** 每条连接内单调递增：客户端据此丢弃重放与乱序事件。 */
        fun nextSeq(): Long = seq.incrementAndGet()

        fun send(frame: JSONObject) = NodeBridgeProtocol.writeFrame(writer, frame)

        fun close() {
            runCatching { socket.close() }
        }
    }

}
