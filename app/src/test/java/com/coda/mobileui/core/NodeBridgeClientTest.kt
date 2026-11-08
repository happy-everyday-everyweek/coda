package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NodeBridgeClientTest {
    private fun fakeServer(token: String = "secret"): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            try {
                val socket: Socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val request = JSONObject(line)
                    val id = request.optInt("id")
                    val method = request.optString("method")
                    val result =
                        when (method) {
                            NodeBridgeProtocol.METHOD_HELLO -> {
                                if (request.getJSONObject("params").optString("token") != token) {
                                    writer.write(
                                        JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                                            "error",
                                            JSONObject().put("code", NodeBridgeProtocol.ERR_UNAUTHORIZED).put("message", "invalid token"),
                                        ).toString() + "\n",
                                    )
                                    writer.flush()
                                    continue
                                }
                                JSONObject()
                                    .put("protocolVersion", 1)
                                    .put("node", JSONObject().put("id", "dev1"))
                                    .put("sessions", JSONArray().put(JSONObject().put("id", "s1").put("title", "demo")))
                            }
                            NodeBridgeProtocol.METHOD_SNAPSHOT -> JSONObject().put("sessions", JSONArray().put(JSONObject().put("id", "s1")))
                            NodeBridgeProtocol.METHOD_SESSION_NEW -> JSONObject().put("ok", true).put("sessionId", "s2")
                            NodeBridgeProtocol.METHOD_SESSION_OPEN,
                            NodeBridgeProtocol.METHOD_TURN_SEND,
                            NodeBridgeProtocol.METHOD_TURN_STOP,
                            NodeBridgeProtocol.METHOD_INTERACTION_ANSWER -> JSONObject().put("ok", true)
                            NodeBridgeProtocol.METHOD_MESSAGES -> JSONObject().put(
                                "messages",
                                JSONArray().put(JSONObject().put("id", "m1").put("role", "assistant").put("text", "hello")),
                            )
                            else -> JSONObject()
                        }
                    val response =
                        if (method == NodeBridgeProtocol.METHOD_HELLO && result.has("protocolVersion")) {
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                        } else {
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                        }
                    writer.write(response.toString() + "\n")
                    writer.flush()
                    if (method == NodeBridgeProtocol.METHOD_TURN_SEND) {
                        writer.write(
                            NodeBridgeProtocol.event(
                                "message.delta",
                                JSONObject().put("sessionId", "s2").put("messageId", "m2").put("text", "hi"),
                            ).toString() + "\n",
                        )
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        return server to worker
    }

    private fun device(port: Int, token: String = "secret") =
        DeviceStore.Device(id = "dev1", name = "Codex abc", host = "127.0.0.1", port = port, token = token)

    @Test
    fun `握手后能拉快照和消息`() {
        val (server, _) = fakeServer()
        NodeBridgeClient.connect(device(server.localPort)).use { client ->
            val hello = client.hello("secret")
            assertEquals(1, hello.getInt("protocolVersion"))
            assertEquals("s1", client.snapshot().getJSONObject(0).getString("id"))
            assertEquals("hello", client.messages("s1").getJSONObject(0).getString("text"))
        }
        server.close()
    }

    @Test
    fun `远端会话可创建发送停止并收到事件`() {
        val (server, _) = fakeServer()
        NodeBridgeClient.connect(device(server.localPort)).use { client ->
            client.hello("secret")
            val eventText = AtomicReference<String>()
            val latch = CountDownLatch(1)
            client.onEvent = { payload ->
                if (payload.optString("type") == "message.delta") {
                    eventText.set(payload.optString("text"))
                    latch.countDown()
                }
            }
            assertEquals("s2", client.sessionNew(null))
            assertEquals(true, client.sessionOpen("s2"))
            assertEquals(true, client.sendTurn("s2", "你好"))
            assertEquals(true, client.turnStop("s2"))
            assertEquals(true, client.answerInteraction("req1", true))
            assertEquals(true, latch.await(3, TimeUnit.SECONDS))
            assertEquals("hi", eventText.get())
        }
        server.close()
    }

    @Test
    fun `错误令牌抛出 NodeBridgeException`() {
        val (server, _) = fakeServer()
        NodeBridgeClient.connect(device(server.localPort)).use { client ->
            val error = assertThrows(NodeBridgeException::class.java) { client.hello("wrong") }
            assertEquals(NodeBridgeProtocol.ERR_UNAUTHORIZED, error.code)
        }
        server.close()
    }
}
