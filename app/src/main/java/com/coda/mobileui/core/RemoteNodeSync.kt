package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 已授权节点的连接与缓存：握手 → 会话快照写入抽屉缓存 → 拉最近会话正文 → 记录在线/错误。
 *
 * 所有网络操作在线程池里跑；回调也在该线程，调用方自行切主线程。
 */
object RemoteNodeSync {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "coda-node-sync").apply { isDaemon = true } }
    private const val MAX_MESSAGE_SESSIONS = 20

    private data class SyncResult(val sessions: Int, val messages: Int)

    /** 连一次、缓存快照与正文。失败直接抛，由调用方决定怎么记。 */
    private fun syncOne(
        ctx: Context,
        device: DeviceStore.Device,
    ): SyncResult {
        var client: NodeBridgeClient? = null
        try {
            client = NodeBridgeClient.connect(device)
            val hello = client.hello(device.token)
            val sessions = hello.optJSONArray("sessions") ?: JSONArray()
            DeviceStore.cacheSessions(ctx, device.id, sessions)
            var withMessages = 0
            for (i in 0 until minOf(sessions.length(), MAX_MESSAGE_SESSIONS)) {
                val session = sessions.optJSONObject(i) ?: continue
                val id = session.optString("id", "")
                if (id.isBlank()) continue
                runCatching {
                    DeviceStore.cacheMessages(ctx, device.id, id, trimMessages(client.messages(id)))
                    withMessages++
                }
            }
            DeviceStore.markError(ctx, device.id, null)
            return SyncResult(sessions.length(), withMessages)
        } finally {
            client?.close()
        }
    }

    fun refresh(
        ctx: Context,
        device: DeviceStore.Device,
        cb: (Boolean, String) -> Unit,
    ) {
        executor.execute {
            try {
                val result = syncOne(ctx, device)
                cb(true, "已连接，缓存 ${result.sessions} 个会话 / ${result.messages} 个对话正文")
            } catch (error: Exception) {
                val message = error.message ?: "连接失败"
                DeviceStore.markError(ctx, device.id, message)
                cb(false, message)
            }
        }
    }

    fun refreshAll(
        ctx: Context,
        devices: List<DeviceStore.Device>,
        cb: ((Int, Int) -> Unit)? = null,
    ) {
        executor.execute {
            var ok = 0
            var failed = 0
            for (device in devices.filter { it.isRemote }) {
                try {
                    syncOne(ctx, device)
                    ok++
                } catch (error: Exception) {
                    DeviceStore.markError(ctx, device.id, error.message ?: "连接失败")
                    failed++
                }
            }
            cb?.invoke(ok, failed)
        }
    }

    /** 缓存到 SharedPreferences 前做上限，避免单个长会话把本地存储撑爆。 */
    private fun trimMessages(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until minOf(messages.length(), 200)) {
            val source = messages.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("id", source.optString("id", ""))
                    .put("role", source.optString("role", ""))
                    .put("text", source.optString("text", "").take(8000))
                    .put("createdAt", source.optLong("createdAt", 0L)),
            )
        }
        return out
    }

    /** 在线判定：最近 5 分钟内成功连接且没有新错误。 */
    fun isOnline(device: DeviceStore.Device): Boolean =
        device.isRemote && device.lastError == null && System.currentTimeMillis() - device.lastSeen in 0..(5 * 60_000L)

    fun cachedAtLabel(
        ctx: Context,
        device: DeviceStore.Device,
    ): String {
        val at = DeviceStore.cachedAt(ctx, device.id)
        if (at <= 0) return "暂无缓存"
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return if (minutes <= 0) "刚刚缓存" else "${minutes} 分钟前缓存"
    }
}