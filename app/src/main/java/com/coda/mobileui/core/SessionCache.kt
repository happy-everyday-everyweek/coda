package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 会话与消息的本地缓存。
 *
 * 内核进程不在时（冷启动、崩溃重启、安装包替换中），界面上什么都看不到，
 * 用户连「上次聊到哪」都无从查。这里把最近的会话元数据与少量消息落盘，
 * 内核没起来时先拿它渲染，并进入只读状态。
 *
 * 几处刻意的取舍：
 *
 * 只缓存文本，不缓存工具调用与附件的完整结构。离线查看的价值是内容本身，
 * 而 parts 结构复杂、附件还带本机路径，复算与隐私成本都不划算。
 *
 * 有上限：会话列表 30 条，消息只留最近 [KEEP_MSG_SESSIONS] 个会话各
 * [MAX_MESSAGES] 条，其余按时间淘汰。缓存是「能看见上次聊了什么」，
 * 不是历史归档，无限增长会拖慢启动也占空间。
 *
 * 写盘走后台单线程 + 原子替换，失败一律静默。缓存不在关键路径上，
 * 不能因为它出错影响正常收发。
 */
object SessionCache {
    private const val DIR = "session_cache"
    private const val MAX_SESSIONS = 30
    private const val KEEP_MSG_SESSIONS = 5
    private const val MAX_MESSAGES = 200

    private val io =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "coda-session-cache").apply { isDaemon = true }
        }

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun msgFile(
        ctx: Context,
        sessionId: String,
    ): File {
        // sessionId 可能是任意串，落成文件名前先压成安全字符，避免出现路径分隔符。
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        return File(dir(ctx), "msg_$safe.json")
    }

    /** 写临时文件再改名：写到一半被杀不会留下半个 JSON 把整份缓存废掉。 */
    private fun writeAtomic(
        file: File,
        text: String,
    ) {
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    /** 缓存会话列表（含工作区路径，离线时也能显示它属于哪个工作区）。 */
    fun saveSessions(
        ctx: Context,
        list: List<ZSessionInfo>,
    ) {
        val array = JSONArray()
        for (s in list.take(MAX_SESSIONS)) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("status", s.status)
                    .put("mode", s.mode)
                    .put("updatedAt", s.updatedAt)
                    .put("workspacePath", s.workspacePath ?: JSONObject.NULL)
                    .put("titleSource", s.titleSource ?: JSONObject.NULL),
            )
        }
        val payload = JSONObject().put("v", 1).put("sessions", array)
        writeAtomic(File(dir(ctx), "sessions.json"), payload.toString())
    }

    /** 读回会话列表；没有缓存或缓存损坏时返回空表。 */
    fun loadSessions(ctx: Context): List<ZSessionInfo> {
        val file = File(dir(ctx), "sessions.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("sessions") ?: return emptyList()
            val out = ArrayList<ZSessionInfo>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out +=
                    ZSessionInfo(
                        id = id,
                        title = o.optString("title"),
                        status = o.optString("status"),
                        mode = o.optString("mode"),
                        updatedAt = o.optLong("updatedAt", 0L),
                        workspacePath = o.optString("workspacePath").takeIf { it.isNotEmpty() && it != "null" },
                        titleSource = o.optString("titleSource").takeIf { it.isNotEmpty() && it != "null" },
                    )
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 缓存某个会话的消息（只留文本，够离线看内容）。 */
    fun saveMessages(
        ctx: Context,
        sessionId: String,
        list: List<ZMessage>,
    ) {
        if (sessionId.isEmpty() || list.isEmpty()) return
        val array = JSONArray()
        // 只留尾部：离线看的是「聊到哪了」，前面几百条对判断上下文没有额外价值。
        for (m in list.takeLast(MAX_MESSAGES)) {
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("createdAt", m.createdAt)
                    .put("text", m.text()),
            )
        }
        val payload = JSONObject().put("v", 1).put("sessionId", sessionId).put("messages", array)
        writeAtomic(msgFile(ctx, sessionId), payload.toString())
        io.execute { prune(ctx) }
    }

    /** 读回某个会话的缓存消息；没有缓存时返回空表。 */
    fun loadMessages(
        ctx: Context,
        sessionId: String,
    ): List<ZMessage> {
        val file = msgFile(ctx, sessionId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("messages") ?: return emptyList()
            val out = ArrayList<ZMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val m = ZMessage(id, o.optString("role"))
                m.createdAt = o.optLong("createdAt", 0L)
                val text = o.optString("text")
                if (text.isNotEmpty()) m.parts.add(ZPart.Text(text))
                out += m
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 只保留最近几个会话的消息文件，其余删掉。 */
    private fun prune(ctx: Context) {
        runCatching {
            val files =
                dir(ctx)
                    .listFiles { f -> f.isFile && f.name.startsWith("msg_") && f.name.endsWith(".json") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: return
            for (f in files.drop(KEEP_MSG_SESSIONS)) {
                runCatching { f.delete() }
            }
        }
    }

    /** 清空全部缓存（退出登录、切换内核数据目录时用）。 */
    fun clear(ctx: Context) {
        io.execute {
            runCatching {
                dir(ctx).listFiles()?.forEach { f -> runCatching { f.delete() } }
            }
        }
    }
}
