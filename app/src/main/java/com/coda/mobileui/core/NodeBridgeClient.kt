package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** 节点桥调用失败（网络/鉴权/超时/远端错误）。 */
open class NodeBridgeException(
    message: String,
    val code: Int = NodeBridgeProtocol.ERR_INTERNAL,
) : Exception(message)

/**
 * 请求超时：远端可能已经执行了，结果未知。
 *
 * 按 CONTRACT §3 不能当成失败去重试，否则同一句话可能在远端执行两遍。
 */
class NodeBridgeTimeoutException(
    message: String,
    val commandId: String? = null,
) : NodeBridgeException(message)

/**
 * Android 端连接另一个 Coda 节点的客户端（与桌面端 android-node.mjs 同协议）。
 *
 * 只做传输与请求关联；缓存、重试和 UI 状态在 RemoteNodeSync / MainActivity。
 */
class NodeBridgeClient private constructor(
    private val socket: Socket,
    private val deviceName: String,
) : AutoCloseable {
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val nextId = AtomicInteger(1)
    /** 远端事件回调（在读取线程上调用，调用方自行切主线程）。 */
    @Volatile var onEvent: ((JSONObject) -> Unit)? = null

    /** 连接结束回调（读取线程上调用）：UI 必须据此显示离线，不能继续假装已连接。 */
    @Volatile var onClose: ((String) -> Unit)? = null
    @Volatile private var closed = false
    private val readerThread =
        Thread({
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            try {
                while (!closed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val frame = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (frame.optString("method") == NodeBridgeProtocol.EVENT) {
                        val payload = frame.optJSONObject("params")
                        if (payload != null) onEvent?.invoke(payload)
                        continue
                    }
                    val id = frame.optInt("id", -1)
                    val future = pending.remove(id) ?: continue
                    if (frame.has("error")) {
                        val error = frame.optJSONObject("error") ?: JSONObject()
                        future.completeExceptionally(
                            NodeBridgeException(error.optString("message", "remote error"), error.optInt("code", NodeBridgeProtocol.ERR_INTERNAL)),
                        )
                    } else {
                        future.complete(frame.optJSONObject("result") ?: JSONObject())
                    }
                }
            } catch (_: Exception) {
            } finally {
                val reason = if (closed) "已关闭连接" else "$deviceName 连接已断开"
                failAll(NodeBridgeException(reason))
                // 连接掉了就通知界面：不能让它继续显示「已连接」。
                onClose?.invoke(reason)
            }
        }, "coda-node-rpc").apply { isDaemon = true }

    init {
        readerThread.start()
    }

    fun hello(token: String, timeoutMs: Long = 8000): JSONObject {
        val result =
            request(
                NodeBridgeProtocol.METHOD_HELLO,
                JSONObject()
                    .put("protocolVersion", NodeBridgeProtocol.VERSION)
                    .put("token", token)
                    .put("clientName", "coda-android"),
                timeoutMs,
            )
        val version = result.optInt("protocolVersion", 0)
        if (version != NodeBridgeProtocol.VERSION) {
            throw NodeBridgeException("节点协议版本不兼容：$version", NodeBridgeProtocol.ERR_VERSION)
        }
        return result
    }

    fun snapshot(timeoutMs: Long = 8000): JSONArray =
        request(NodeBridgeProtocol.METHOD_SNAPSHOT, JSONObject(), timeoutMs).optJSONArray("sessions") ?: JSONArray()

    fun messages(sessionId: String, timeoutMs: Long = 8000): JSONArray =
        request(NodeBridgeProtocol.METHOD_MESSAGES, JSONObject().put("sessionId", sessionId), timeoutMs)
            .optJSONArray("messages") ?: JSONArray()

    fun sessionNew(
        workspace: String?,
        timeoutMs: Long = 30000,
        commandId: String = newCommandId(),
    ): String {
        val params = JSONObject()
        if (!workspace.isNullOrBlank()) params.put("workspace", workspace)
        return request(NodeBridgeProtocol.METHOD_SESSION_NEW, params, timeoutMs, commandId).optString("sessionId", "")
    }

    fun sessionOpen(
        sessionId: String,
        timeoutMs: Long = 15000,
        commandId: String = newCommandId(),
    ): Boolean =
        request(
            NodeBridgeProtocol.METHOD_SESSION_OPEN,
            JSONObject().put("sessionId", sessionId),
            timeoutMs,
            commandId,
        ).optBoolean("ok", false)

    fun sendTurn(
        sessionId: String,
        text: String,
        timeoutMs: Long = 15000,
        commandId: String = newCommandId(),
    ): Boolean =
        request(
            NodeBridgeProtocol.METHOD_TURN_SEND,
            JSONObject().put("sessionId", sessionId).put("text", text),
            timeoutMs,
            commandId,
        ).optBoolean("ok", false)

    fun turnStop(
        sessionId: String,
        timeoutMs: Long = 15000,
        commandId: String = newCommandId(),
    ): Boolean =
        request(
            NodeBridgeProtocol.METHOD_TURN_STOP,
            JSONObject().put("sessionId", sessionId),
            timeoutMs,
            commandId,
        ).optBoolean("ok", false)

    fun answerInteraction(
        requestId: String,
        allow: Boolean,
        timeoutMs: Long = 15000,
        commandId: String = newCommandId(),
    ): Boolean =
        request(
            NodeBridgeProtocol.METHOD_INTERACTION_ANSWER,
            JSONObject().put("requestId", requestId).put("allow", allow),
            timeoutMs,
            commandId,
        ).optBoolean("ok", false)

    private fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long,
        commandId: String? = null,
    ): JSONObject {
        if (closed) throw NodeBridgeException("连接已关闭")
        if (commandId != null) params.put("commandId", commandId)
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        NodeBridgeProtocol.writeFrame(writer, frame)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            pending.remove(id)
            val cause = error.cause
            if (cause is NodeBridgeException) throw cause
            throw NodeBridgeException(cause?.message ?: "远端错误")
        } catch (error: TimeoutException) {
            pending.remove(id)
            throw NodeBridgeTimeoutException("请求超时（结果未知）：$method", commandId)
        } catch (error: InterruptedException) {
            pending.remove(id)
            Thread.currentThread().interrupt()
            throw NodeBridgeException("请求被中断：$method")
        }
    }

    private fun failAll(error: Exception) {
        for ((_, future) in pending) future.completeExceptionally(error)
        pending.clear()
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        failAll(NodeBridgeException("连接已关闭"))
    }

    companion object {
        /** 每条命令一个 id；重投时必须复用同一个 id 才能吃到幂等。 */
        fun newCommandId(): String = java.util.UUID.randomUUID().toString()

        fun connect(device: DeviceStore.Device, timeoutMs: Int = 5000): NodeBridgeClient {
            if (device.host.isBlank()) throw NodeBridgeException("设备地址为空")
            val socket = Socket()
            socket.connect(InetSocketAddress(device.host, device.port), timeoutMs)
            socket.tcpNoDelay = true
            return NodeBridgeClient(socket, device.name)
        }
    }
}
