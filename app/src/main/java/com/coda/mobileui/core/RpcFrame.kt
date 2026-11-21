package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 响应帧的解读。
 *
 * 两个坑：
 * 一是 `id` 不一定是数字。用 `optInt` 解析时字符串 id 会变成兜底值，与 pending 里的 key
 * 对不上，回调永不触发、条目永久泄漏。这里统一按字符串形式做 key。
 * 二是 `result` / `error` 不一定是对象。JSON-RPC 允许数组与标量，用 `optJSONObject`
 * 判断会把它们当成 null——结果被静默丢弃，而非对象的 error 更会被误判成成功。
 * 分支必须按「字段存在且非 null」决定，而不是「能否解析成 JSONObject」。
 */
internal object RpcFrame {
    /** pending 的 key：数字与字符串 id 都归一到同一形式。 */
    fun idKey(raw: Any?): String? {
        if (raw == null || raw == JSONObject.NULL) return null
        return when (raw) {
            // 1 与 1.0 要落到同一个 key：JSON 解析可能把整数给成 Double。
            is Number -> raw.toLong().toString()

            else -> raw.toString()
        }
    }

    /** 这一帧是不是失败响应。 */
    fun isError(obj: JSONObject): Boolean = obj.has("error") && !obj.isNull("error")

    /**
     * 取出载荷，并保证调用方拿到的是 JSONObject。
     *
     * 现有 41 处调用都按 JSONObject 消费，改签名影响面太大。所以非对象结果不丢弃，
     * 而是包进 `[VALUE_KEY]`，同时打上 `[WRAPPED_KEY]` 标记，调用方需要时可以取出原值。
     */
    fun payload(obj: JSONObject): JSONObject {
        val raw = if (isError(obj)) obj.opt("error") else obj.opt("result")
        return when (raw) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> raw
            is JSONArray -> JSONObject().put(WRAPPED_KEY, true).put(VALUE_KEY, raw)
            else -> JSONObject().put(WRAPPED_KEY, true).put(VALUE_KEY, raw)
        }
    }

    /** 非对象载荷的标记字段。 */
    const val WRAPPED_KEY = "__scalar"

    /** 非对象载荷的原值字段。 */
    const val VALUE_KEY = "value"

    /** 载荷是不是被包装过的非对象结果。 */
    fun isWrapped(data: JSONObject): Boolean = data.optBoolean(WRAPPED_KEY)

    /** 取被包装的原值；不是包装结果时返回 null。 */
    fun unwrapped(data: JSONObject): Any? = if (isWrapped(data)) data.opt(VALUE_KEY) else null
}
