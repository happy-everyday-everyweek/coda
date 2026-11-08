package com.coda.mobileui.core

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Android 宿主桥的 JSON-RPC 2.0 信封与鉴权工具（纯函数，便于 JVM 单测）。
 *
 * 传输：TCP + NDJSON（一行一个 JSON 对象），只有本机/局域网可达。
 * 认证：首帧 node.hello 必须携带 token；token 由 App 生成并保存在私有 prefs。
 */
object NodeBridgeProtocol {
    const val VERSION = 1
    const val METHOD_HELLO = "node.hello"
    const val METHOD_SNAPSHOT = "node.snapshot"
    const val METHOD_SESSION_NEW = "node.session.new"
    const val METHOD_SESSION_OPEN = "node.session.open"
    const val METHOD_TURN_SEND = "node.turn.send"
    const val METHOD_TURN_STOP = "node.turn.stop"
    const val METHOD_MESSAGES = "node.messages"
    const val METHOD_INTERACTION_ANSWER = "node.interaction.answer"
    const val METHOD_PING = "node.ping"
    const val EVENT = "node.event"

    const val ERR_PARSE = -32700
    const val ERR_INVALID = -32600
    const val ERR_METHOD = -32601
    const val ERR_PARAMS = -32602
    const val ERR_INTERNAL = -32603
    const val ERR_VERSION = -32001
    const val ERR_UNAUTHORIZED = -32002
    const val ERR_NOT_FOUND = -32003

    fun parse(line: String): JSONObject? = runCatching { JSONObject(line) }.getOrNull()

    fun result(id: Any?, value: Any?): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", value ?: JSONObject.NULL)

    fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    fun event(type: String, fields: JSONObject = JSONObject()): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", EVENT)
            .put("params", JSONObject().put("type", type).put("ts", System.currentTimeMillis()).apply {
                for (key in fields.keys()) put(key, fields.opt(key))
            })

    /** 常量时间比较，避免 token 被按字节计时猜测。 */
    fun tokenMatches(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }
}
