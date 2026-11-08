package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行地点（本机 + 已配对设备）与各设备会话缓存的本地存储。
 *
 * 只存配对信息与最近一次快照，不伪造在线状态：
 * - 设备是否在线由连接层写入 lastSeen/lastError，UI 必须如实展示；
 * - 会话缓存是只读投影，桌面端不可达时仍能浏览，但不能在缓存上执行。
 */
object DeviceStore {
    const val LOCAL_ID = "local"
    private const val PREFS = "coda_devices"
    private const val KEY_DEVICES = "devices"

    data class Device(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val token: String,
        val label: String = "",
        val lastSeen: Long = 0L,
        val lastError: String? = null,
    ) {
        val address: String get() = if (host.isBlank()) "本机" else "$host:$port"
        val paired: Boolean get() = id != LOCAL_ID
    }

    fun local(): Device = Device(LOCAL_ID, "这台设备", "", 0, "")

    fun all(ctx: Context): List<Device> = listOf(local()) + paired(ctx)

    fun paired(ctx: Context): List<Device> {
        val raw = prefs(ctx).getString(KEY_DEVICES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Device>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(
                Device(
                    id = obj.optString("id").ifBlank { "dev_$i" },
                    name = obj.optString("name").ifBlank { "未命名设备" },
                    host = obj.optString("host", ""),
                    port = obj.optInt("port", 17654),
                    token = obj.optString("token", ""),
                    label = obj.optString("label", ""),
                    lastSeen = obj.optLong("lastSeen", 0L),
                    lastError = obj.optString("lastError", "").ifBlank { null },
                ),
            )
        }
        return out
    }

    fun add(ctx: Context, device: Device): Device {
        val id = device.id.ifBlank { "dev_" + System.currentTimeMillis() }
        val list = paired(ctx).filterNot { it.id == id }.toMutableList()
        list.add(0, device.copy(id = id))
        save(ctx, list)
        return device.copy(id = id)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, paired(ctx).filterNot { it.id == id })
        prefs(ctx).edit().remove(sessionsKey(id)).remove(sessionsAtKey(id)).apply()
    }

    fun markError(ctx: Context, id: String, message: String?) {
        val list = paired(ctx).map { if (it.id == id) it.copy(lastError = message, lastSeen = if (message == null) System.currentTimeMillis() else it.lastSeen) else it }
        save(ctx, list)
    }

    private fun save(ctx: Context, list: List<Device>) {
        val array = JSONArray()
        for (d in list.filter { it.paired }) {
            array.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("host", d.host)
                    .put("port", d.port)
                    .put("token", d.token)
                    .put("label", d.label)
                    .put("lastSeen", d.lastSeen)
                    .put("lastError", d.lastError ?: ""),
            )
        }
        prefs(ctx).edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    // ------------------------------------------------------------ 会话缓存

    fun cacheSessions(ctx: Context, deviceId: String, sessions: JSONArray) {
        prefs(ctx)
            .edit()
            .putString(sessionsKey(deviceId), sessions.toString())
            .putLong(sessionsAtKey(deviceId), System.currentTimeMillis())
            .apply()
    }

    fun cachedSessions(ctx: Context, deviceId: String): JSONArray {
        val raw = prefs(ctx).getString(sessionsKey(deviceId), null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun cachedAt(ctx: Context, deviceId: String): Long = prefs(ctx).getLong(sessionsAtKey(deviceId), 0L)

    /** 缓存某个远程会话的消息正文；离线点开时只读展示。 */
    fun cacheMessages(ctx: Context, deviceId: String, sessionId: String, messages: JSONArray) {
        prefs(ctx)
            .edit()
            .putString(messagesKey(deviceId, sessionId), messages.toString())
            .putLong(messagesAtKey(deviceId, sessionId), System.currentTimeMillis())
            .apply()
    }

    fun cachedMessages(ctx: Context, deviceId: String, sessionId: String): JSONArray {
        val raw = prefs(ctx).getString(messagesKey(deviceId, sessionId), null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun messagesCachedAt(ctx: Context, deviceId: String, sessionId: String): Long =
        prefs(ctx).getLong(messagesAtKey(deviceId, sessionId), 0L)

    private fun messagesKey(deviceId: String, sessionId: String) = "messages_${deviceId}_$sessionId"
    private fun messagesAtKey(deviceId: String, sessionId: String) = "messages_at_${deviceId}_$sessionId"
    private fun sessionsKey(id: String) = "sessions_$id"
    private fun sessionsAtKey(id: String) = "sessions_at_$id"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
