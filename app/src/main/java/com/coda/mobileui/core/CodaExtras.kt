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
    private const val TAG = "CodaExtras"

    /** automations 表用到的列，按名字取值。 */
    private val AUTOMATION_COLUMNS =
        listOf(
            "automation_id",
            "title",
            "cron_expr",
            "prompt",
            "enabled",
            "lifecycle_status",
            "recurring",
            "run_count",
            "next_run_at",
            "last_run_at",
            "last_error",
        )
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
            out +=
                McpServerItem(
                    name = name,
                    status = s.optString("status"),
                    transport = s.optString("transport"),
                    toolCount = s.optInt("toolCount", 0),
                    error = s.optString("error").takeIf { it.isNotEmpty() },
                )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun mcpStatusLabel(status: String): String =
        when (status) {
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
            out +=
                PluginItem(
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

    // ---- 插件总览（plugins/overview） ----
    data class PluginMarketplace(
        val id: String,
        val name: String,
        val description: String?,
        val pluginCount: Int,
        val isOfficial: Boolean,
        val lastUpdated: String?,
        val refreshFailure: String?,
    )

    data class PluginCandidate(
        val id: String,
        val name: String,
        val marketplace: String,
        val description: String?,
        val version: String?,
        val installed: Boolean,
        val componentTypes: List<String>,
    )

    data class PluginInstalled(
        val id: String,
        val name: String,
        val marketplace: String,
        val description: String?,
        val version: String?,
        val enabled: Boolean,
        val updateStatus: String?,
        val latestVersion: String?,
        val componentTypes: List<String>,
    )

    data class PluginOverview(
        val marketplaces: List<PluginMarketplace>,
        val available: List<PluginCandidate>,
        val installed: List<PluginInstalled>,
        val restorable: List<PluginCandidate>,
        val capabilitySupported: Boolean,
        val capabilityReason: String?,
    )

    private fun readStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private fun candidateFrom(p: JSONObject): PluginCandidate =
        PluginCandidate(
            id = p.optString("id"),
            name = p.optString("name").ifEmpty { p.optString("id") },
            marketplace = p.optString("marketplace"),
            description = p.optString("description").takeIf { it.isNotEmpty() },
            version = p.optString("version").takeIf { it.isNotEmpty() },
            installed = p.optBoolean("installed", false),
            componentTypes = readStringList(p.optJSONArray("componentTypes")),
        )

    fun parsePluginsOverview(root: JSONObject?): PluginOverview? {
        if (root == null) return null
        val marketplaces = mutableListOf<PluginMarketplace>()
        val mkArr = root.optJSONArray("marketplaces")
        if (mkArr != null) {
            for (i in 0 until mkArr.length()) {
                val m = mkArr.optJSONObject(i) ?: continue
                marketplaces +=
                    PluginMarketplace(
                        id = m.optString("id"),
                        name = m.optString("name").ifEmpty { m.optString("id") },
                        description = m.optString("description").takeIf { it.isNotEmpty() },
                        pluginCount = m.optInt("pluginCount", 0),
                        isOfficial = m.optBoolean("isOfficial", false),
                        lastUpdated = m.optString("lastUpdated").takeIf { it.isNotEmpty() },
                        refreshFailure =
                            m
                                .optJSONObject("refreshFailure")
                                ?.optString("message")
                                ?.takeIf { it.isNotEmpty() },
                    )
            }
        }
        val available = mutableListOf<PluginCandidate>()
        val avArr = root.optJSONArray("availablePlugins")
        if (avArr != null) {
            for (i in 0 until avArr.length()) {
                val p = avArr.optJSONObject(i) ?: continue
                available += candidateFrom(p)
            }
        }
        val installed = mutableListOf<PluginInstalled>()
        val inArr = root.optJSONArray("installedPlugins")
        if (inArr != null) {
            for (i in 0 until inArr.length()) {
                val p = inArr.optJSONObject(i) ?: continue
                installed +=
                    PluginInstalled(
                        id = p.optString("id"),
                        name = p.optString("name").ifEmpty { p.optString("id") },
                        marketplace = p.optString("marketplace"),
                        description = p.optString("description").takeIf { it.isNotEmpty() },
                        version = p.optString("version").takeIf { it.isNotEmpty() },
                        enabled = p.optBoolean("enabled", false),
                        updateStatus = p.optString("updateStatus").takeIf { it.isNotEmpty() && it != "none" },
                        latestVersion = p.optString("latestVersion").takeIf { it.isNotEmpty() },
                        componentTypes = readStringList(p.optJSONArray("componentTypes")),
                    )
            }
        }
        val restorable = mutableListOf<PluginCandidate>()
        val rsArr = root.optJSONArray("restorableBuiltins")
        if (rsArr != null) {
            for (i in 0 until rsArr.length()) {
                val p = rsArr.optJSONObject(i) ?: continue
                restorable += candidateFrom(p)
            }
        }
        val cap = root.optJSONObject("capability")
        return PluginOverview(
            marketplaces = marketplaces,
            available = available,
            installed = installed,
            restorable = restorable,
            capabilitySupported = cap?.optBoolean("supported", true) ?: true,
            capabilityReason = cap?.optString("reason")?.takeIf { it.isNotEmpty() },
        )
    }

    // ---- 插件详情（plugins/list 单条） ----
    data class PluginComponentGroup(
        val kind: String,
        val names: List<String>,
    )

    data class PluginConfigOption(
        val key: String,
        val title: String?,
        val description: String?,
        val type: String,
        val default: Any?,
        val required: Boolean,
        val sensitive: Boolean,
        val current: Any?,
    )

    data class PluginDetail(
        val id: String,
        val name: String,
        val description: String?,
        val version: String?,
        val enabled: Boolean,
        val marketplace: String,
        val source: String,
        val author: String?,
        val homepage: String?,
        val skillRootCount: Int,
        val commandRootCount: Int,
        val mcpServerNames: List<String>,
        val components: List<PluginComponentGroup>,
        val hookSummaries: List<String>,
        val options: List<PluginConfigOption>,
    )

    fun componentKindLabel(kind: String): String =
        when (kind.lowercase().removeSuffix("s")) {
            "agent" -> "智能体"
            "command" -> "命令"
            "skill" -> "技能"
            "hook" -> "钩子"
            "mcp" -> "MCP"
            else -> kind
        }

    /** JSONObject.NULL 要当成「没有值」，否则界面会显示字面串 "null"（#239）。 */
    private fun jsonValueOrNull(v: Any?): Any? = if (v == null || v == JSONObject.NULL) null else v

    fun anyToText(v: Any?): String =
        when (v) {
            null, JSONObject.NULL -> ""
            is String -> v
            is Boolean -> if (v) "true" else "false"
            is Double -> doubleText(v)
            else -> v.toString()
        }

    /**
     * Double 的展示文本（#240）。
     *
     * 旧写法 v == v.toLong().toDouble() 在 |v| >= 2^63 时会被 toLong() 饱和成
     * Long.MAX_VALUE 并误判为整数，输出 9223372036854775807 这种假值；
     * 超过 2^53 后 Long↔Double 往返还会丢精度，让不同的值印出同一串数字。
     * 因此只在「安全整数范围」内做整数化，其余交给 BigDecimal 保留原值。
     */
    fun doubleText(v: Double): String {
        if (!v.isFinite()) return v.toString()
        // 2^53：Double 能精确表示的整数上界。
        val safe = 9_007_199_254_740_992.0
        if (kotlin.math.abs(v) < safe && v == kotlin.math.floor(v)) {
            return v.toLong().toString()
        }
// 字符串构造器而不是 double 构造器（#766）：后者捕获精确二进制值，
        // 0.1 会展开成 55 位小数直接写进配置编辑器。toString 是最短往返表示。
        // 但 ≥2^53 的整数 Double.toString 走科学计数法（9.223372036854776E18），
        // 有效位只有 16 位，会把精确值 ...775808 舍成 ...776000（#240 的假值问题）。
        // 这一段仍用 double 构造器：整数的二进制展开就是精确值本身。
        val ctor = if (kotlin.math.abs(v) >= safe) java.math.BigDecimal(v) else java.math.BigDecimal(v.toString())
        return ctor.stripTrailingZeros().toPlainString()
    }

    fun findPluginDetail(
        root: JSONObject?,
        pluginId: String,
    ): PluginDetail? {
        val arr = root?.optJSONArray("plugins") ?: return null
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("id") == pluginId) return parsePluginDetail(p)
        }
        return null
    }

    fun parsePluginDetail(p: JSONObject): PluginDetail {
        val components = mutableListOf<PluginComponentGroup>()
        val cmpArr = p.optJSONArray("components")
        if (cmpArr != null) {
            for (i in 0 until cmpArr.length()) {
                val g = cmpArr.optJSONObject(i) ?: continue
                val names = mutableListOf<String>()
                val itemsArr = g.optJSONArray("items")
                if (itemsArr != null) {
                    for (j in 0 until itemsArr.length()) {
                        val item = itemsArr.optJSONObject(j) ?: continue
                        val nm = item.optString("name")
                        if (nm.isNotEmpty()) names += nm
                    }
                }
                components += PluginComponentGroup(g.optString("kind"), names)
            }
        }
        val hooks = mutableListOf<String>()
        val hkArr = p.optJSONArray("hookDetails")
        if (hkArr != null) {
            for (i in 0 until hkArr.length()) {
                val h = hkArr.optJSONObject(i) ?: continue
                val ev = h.optString("event")
                val cmd = h.optString("command")
                if (ev.isNotEmpty()) hooks += if (cmd.isNotEmpty()) "$ev → $cmd" else ev
            }
        }
        val configured = p.optJSONObject("configuredOptions")
        val options = mutableListOf<PluginConfigOption>()
        val cfg = p.optJSONObject("userConfig")
        if (cfg != null) {
            val keys = cfg.keys()
            val all = mutableListOf<String>()
            while (keys.hasNext()) all += keys.next()
            all.sorted().forEach { key ->
                // userConfig 的值可能是标量简写（"apiKey": "xxx"），不能整条跳过：
                // 声明了却不出现在详情页里，用户根本没法配（#239）。
                val o = cfg.optJSONObject(key)
                val scalar = if (o == null) cfg.get(key) else null
                // OCR #819：标量简写不能一律当 string/false——数字会被写成字符串、
                // 布尔失去开关、密钥明文展示。类型按 JSON 实际类型推导；
                // 键名含 key/token/secret/password 的字符串按敏感处理。
                val scalarType =
                    when (scalar) {
                        is Boolean -> "boolean"
                        is Number -> "number"
                        else -> "string"
                    }
                val keyLooksSecret =
                    listOf("apikey", "api_key", "token", "secret", "password", "passwd").any {
                        key.lowercase().replace("-", "").contains(it)
                    }
                options +=
                    PluginConfigOption(
                        key = key,
                        title = o?.optString("title")?.takeIf { it.isNotEmpty() },
                        description = o?.optString("description")?.takeIf { it.isNotEmpty() },
                        type = o?.optString("type", "string")?.ifEmpty { "string" } ?: scalarType,
                        default = if (o != null && o.has("default")) jsonValueOrNull(o.get("default")) else null,
                        required = o?.optBoolean("required", false) ?: false,
                        sensitive = o?.optBoolean("sensitive", false) ?: (scalar is String && keyLooksSecret),
                        // 标量简写时，那个值本身就是当前值。
                        current =
                            when {
                                configured != null && configured.has(key) -> jsonValueOrNull(configured.get(key))
                                scalar != null -> jsonValueOrNull(scalar)
                                else -> null
                            },
                    )
            }
        }
        return PluginDetail(
            id = p.optString("id"),
            name = p.optString("name").ifEmpty { p.optString("id") },
            description = p.optString("description").takeIf { it.isNotEmpty() },
            version = p.optString("version").takeIf { it.isNotEmpty() },
            enabled = p.optBoolean("enabled", false),
            marketplace = p.optString("marketplace"),
            source = p.optString("source"),
            author = p.optString("author").takeIf { it.isNotEmpty() },
            homepage = p.optString("homepage").takeIf { it.isNotEmpty() },
            skillRootCount = p.optInt("skillRootCount", 0),
            commandRootCount = p.optInt("commandRootCount", 0),
            mcpServerNames = readStringList(p.optJSONArray("mcpServerNames")),
            components = components,
            hookSummaries = hooks,
            options = options,
        )
    }
    // ---------------------------------------------------------------- 钩子

    data class HookItem(
        val event: String,
        val matcher: String,
        val command: String,
        val source: String,
    )

    /** 读取工作区级与用户级 settings.json 的 hooks 段（兼容新旧两种结构）。 */

    /**
     * 一次钩子读取的结果：条目与错误一起交出（#237）。
     *
     * OCR #819：以前错误存全局 lastReadErrors——没有任何消费方，且两个工作区
     * 交错读取会互相覆盖错误。改成随结果返回，调用方按次拿到自己的失败信息。
     */
    data class HooksReadResult(
        val items: List<HookItem>,
        val errors: List<String>,
    )

    fun readHooks(
        workspacePath: String?,
        homeDir: File,
    ): HooksReadResult {
        val out = mutableListOf<HookItem>()
        val readErrors = mutableListOf<String>()
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
                            out +=
                                HookItem(
                                    event = event,
                                    matcher = matcher,
                                    command = h.optString("command"),
                                    source = label,
                                )
                        }
                    }
                }
            } catch (e: Exception) {
                // 空 catch 会让「读取失败」与「确实没配钩子」长得一样（#237）。
                AppLog.w(TAG, "读取钩子配置失败：${f.absolutePath} ${e.javaClass.simpleName}: ${e.message}")
                readErrors += "$label：${e.message ?: e.javaClass.simpleName}"
            }
        }
        return HooksReadResult(out, readErrors)
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

    /**
     * 读 tasks-index.sqlite 的 automations 表（只读；库/表不存在时返回空列表）。
     *
     * 列按名字取，不写死下标：表结构升级（增列、调序）时下标会整体错位，
     * 字段串到别的列上，界面上看不出错，只是数据全是错的。
     */
    fun readAutomations(dbFile: File): List<AutomationItem> {
        if (!dbFile.exists()) return emptyList()
        var db: SQLiteDatabase? = null
        return try {
            db =
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
            val out = mutableListOf<AutomationItem>()
            db
                .rawQuery(
                    // 投影由列清单生成（OCR low #827）：手写的 SQL 与 AUTOMATION_COLUMNS
                    // 是两份必须同步的副本，漏改一处就会让 getColumnIndex 拿到 -1。
                    "SELECT ${AUTOMATION_COLUMNS.joinToString(", ")} FROM automations ORDER BY created_at DESC",
                    null,
                ).use { c ->
                    val index = AUTOMATION_COLUMNS.associateWith { c.getColumnIndex(it) }
                    val missing = index.filterValues { it < 0 }.keys
                    if (missing.isNotEmpty()) {
                        AppLog.w(TAG, "automations 表缺少列：${missing.joinToString()}")
                        return emptyList()
                    }

                    fun text(name: String): String? = c.getString(index.getValue(name))

                    fun number(name: String): Long? = index.getValue(name).let { at -> if (c.isNull(at)) null else c.getLong(at) }

                    fun count(name: String): Int = c.getInt(index.getValue(name))

                    while (c.moveToNext()) {
                        out +=
                            AutomationItem(
                                id = text("automation_id") ?: "",
                                title = text("title") ?: "",
                                cron = text("cron_expr") ?: "",
                                prompt = text("prompt") ?: "",
                                enabled = count("enabled") != 0,
                                lifecycle = text("lifecycle_status") ?: "active",
                                recurring = count("recurring") != 0,
                                runCount = count("run_count"),
                                nextRunAt = number("next_run_at"),
                                lastRunAt = number("last_run_at"),
                                lastError = text("last_error"),
                            )
                    }
                }
            out
        } catch (e: Exception) {
            // 库损坏、被内核占用都要能看见是哪种，不能默默给一张空列表。
            AppLog.w(TAG, "读取定时任务失败 ${dbFile.name}：${e.message ?: e.javaClass.simpleName}")
            emptyList()
        } finally {
            runCatching { db?.close() }
        }
    }

    fun formatTs(ts: Long?): String {
        if (ts == null || ts <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun formatCount(v: Long): String =
        when {
            v >= 100_000_000 -> String.format(Locale.US, "%.1f亿", v / 100_000_000.0)
            v >= 10_000 -> String.format(Locale.US, "%.1f万", v / 10_000.0)
            else -> v.toString()
        }
}
