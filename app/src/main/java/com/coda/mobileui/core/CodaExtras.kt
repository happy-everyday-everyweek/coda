package com.coda.mobileui.core

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置系扩展功能的本地数据层：MCP / 插件 / 钩子 / 定时任务 / 使用统计。
 * 数据来源与桌面端一致：协议（mcp/list、plugins/list、usage/stats 等）+ 本地文件与 SQLite。
 */
object CodaExtras {

    // ---------------------------------------------------------------- MCP

    data class McpServerItem(
        val name: String,
        val status: String,
        val transport: String,
        val toolCount: Int,
        val error: String?,
    )

    fun parseMcpServers(root: JSONObject?): List<McpServerItem> {
        val statuses = root?.optJSONObject("statuses") ?: return emptyList()
        val out = mutableListOf<McpServerItem>()
        val keys = statuses.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val s = statuses.optJSONObject(name) ?: continue
            out += McpServerItem(
                name = name,
                status = s.optString("status"),
                transport = s.optString("transport"),
                toolCount = s.optInt("toolCount", 0),
                error = s.optString("error").takeIf { it.isNotEmpty() },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun mcpStatusLabel(status: String): String = when (status) {
        "connected" -> "已连接"
        "connecting" -> "连接中"
        "disabled" -> "已停用"
        "disconnected" -> "未连接"
        "failed" -> "连接失败"
        "untrusted" -> "未信任"
        else -> status
    }

    // ---------------------------------------------------------------- 插件

    data class PluginItem(
        val id: String,
        val name: String,
        val description: String,
        val version: String?,
        val enabled: Boolean,
        val skillCount: Int,
        val commandCount: Int,
        val mcpCount: Int,
    )

    fun parsePlugins(root: JSONObject?): List<PluginItem> {
        val arr = root?.optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<PluginItem>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out += PluginItem(
                id = p.optString("id"),
                name = p.optString("name").ifEmpty { p.optString("id") },
                description = p.optString("description"),
                version = p.optString("version").takeIf { it.isNotEmpty() },
                enabled = p.optBoolean("enabled", false),
                skillCount = p.optInt("skillCount", p.optInt("skillRootCount", 0)),
                commandCount = p.optInt("commandRootCount", 0),
                mcpCount = p.optJSONArray("mcpServerNames")?.length() ?: 0,
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------- 钩子

    data class HookItem(
        val event: String,
        val matcher: String,
        val command: String,
        val source: String,
    )

    /** 读取工作区级与用户级 settings.json 的 hooks 段（兼容新旧两种结构）。 */
    fun readHooks(workspacePath: String?, homeDir: File): List<HookItem> {
        val out = mutableListOf<HookItem>()
        val files = mutableListOf<Pair<String, File>>()
        if (workspacePath != null) {
            files += "工作区" to File(workspacePath, ".zcode/settings.json")
        }
        files += "用户" to File(homeDir, ".zcode/cli/settings.json")
        files.forEach { (label, f) ->
            if (!f.exists()) return@forEach
            try {
                val root = JSONObject(f.readText())
                val hooks = root.optJSONObject("hooks") ?: return@forEach
                // 新版：{"events": {...}}；旧版：直接是事件名到数组的映射
                val events = hooks.optJSONObject("events") ?: hooks
                val keys = events.keys()
                while (keys.hasNext()) {
                    val event = keys.next()
                    val matchers = events.optJSONArray(event) ?: continue
                    for (i in 0 until matchers.length()) {
                        val m = matchers.optJSONObject(i) ?: continue
                        val matcher = m.optString("matcher")
                        val inner = m.optJSONArray("hooks") ?: continue
                        for (j in 0 until inner.length()) {
                            val h = inner.optJSONObject(j) ?: continue
                            out += HookItem(
                                event = event,
                                matcher = matcher,
                                command = h.optString("command"),
                                source = label,
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 定时任务

    data class AutomationItem(
        val id: String,
        val title: String,
        val cron: String,
        val prompt: String,
        val enabled: Boolean,
        val lifecycle: String,
        val recurring: Boolean,
        val runCount: Int,
        val nextRunAt: Long?,
        val lastRunAt: Long?,
        val lastError: String?,
    )

    /** 读 tasks-index.sqlite 的 automations 表（只读；库/表不存在时返回空列表）。 */
    fun readAutomations(dbFile: File): List<AutomationItem> {
        if (!dbFile.exists()) return emptyList()
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val out = mutableListOf<AutomationItem>()
            db.rawQuery(
                "SELECT automation_id, title, cron_expr, prompt, enabled, lifecycle_status, " +
                    "recurring, run_count, next_run_at, last_run_at, last_error " +
                    "FROM automations ORDER BY created_at DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out += AutomationItem(
                        id = c.getString(0) ?: "",
                        title = c.getString(1) ?: "",
                        cron = c.getString(2) ?: "",
                        prompt = c.getString(3) ?: "",
                        enabled = c.getInt(4) != 0,
                        lifecycle = c.getString(5) ?: "active",
                        recurring = c.getInt(6) != 0,
                        runCount = c.getInt(7),
                        nextRunAt = if (c.isNull(8)) null else c.getLong(8),
                        lastRunAt = if (c.isNull(9)) null else c.getLong(9),
                        lastError = if (c.isNull(10)) null else c.getString(10),
                    )
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        } finally {
            try {
                db?.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun formatTs(ts: Long?): String {
        if (ts == null || ts <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun formatCount(v: Long): String = when {
        v >= 100_000_000 -> String.format(Locale.US, "%.1f亿", v / 100_000_000.0)
        v >= 10_000 -> String.format(Locale.US, "%.1f万", v / 10_000.0)
        else -> v.toString()
    }
}