package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 钩子数据层：与内核一致的配置发现、信任摘要（sha256）计算、信任授予与本地编辑。
 *
 * 发现规则（复刻内核 workspace-hook-config）：项目源从工作区目录向上收集到最近的 .git（含），
 * 每级候选文件为 zcode.json 与 .zcode/config.json（固定顺序）；用户源为 HOME/.zcode/cli/config.json。
 * 信任摘要算法（复刻内核 workspace-hook-digest）：JSON.stringify 后的 sha256 十六进制。
 */
object HooksCore {

    val EVENT_NAMES = listOf(
        "SessionStart",
        "UserPromptSubmit",
        "PreToolUse",
        "PermissionRequest",
        "PostToolUse",
        "PostToolUseFailure",
        "Stop",
    )

    val EVENT_LABELS: Map<String, String> = mapOf(
        "SessionStart" to "会话开始",
        "UserPromptSubmit" to "提交消息",
        "PreToolUse" to "工具调用前",
        "PermissionRequest" to "权限请求",
        "PostToolUse" to "工具调用后",
        "PostToolUseFailure" to "工具调用失败",
        "Stop" to "回合结束",
    )

    // ------------------------------------------------------------ 数据结构

    class HookSource(
        val canonicalPath: String,
        val relPath: String,
        val discoveryOrder: Int,
        val configFileKind: String,
        val explicit: Boolean,
        val editable: Boolean,
        val hooks: JSONObject,
    )

    class RuntimeRoot(val enabled: Boolean, val timeoutMs: Long, val maxOutputBytes: Long)

    class HookEntry(
        val source: HookSource,
        val event: String,
        val matcher: String?,
        val matcherIndex: Int,
        val hookIndex: Int,
        val hook: JSONObject,
        val sourceRootEnabled: Boolean,
        val declarationEnabled: Boolean,
        val runtimeHooksEnabled: Boolean,
        val declarationDigest: String,
    ) {
        val command: String get() = hook.optString("command")
        val configuredEnabled: Boolean
            get() = sourceRootEnabled && declarationEnabled && runtimeHooksEnabled
    }

    class Snapshot(
        val workspacePath: String,
        val projectSources: List<HookSource>,
        val userSource: HookSource?,
        val runtimeRoot: RuntimeRoot,
        val entries: List<HookEntry>,
        val userEntries: List<HookEntry>,
        val bundleDigest: String?,
        val errors: List<String>,
    )

    // ------------------------------------------------------------ 发现

    fun discover(workspacePath: String, home: File): Snapshot {
        val wp = normalize(workspacePath)
        val errors = mutableListOf<String>()
        val projectSources = mutableListOf<HookSource>()
        val refs = mutableListOf<Pair<String, Boolean>>()
        for (dir in projectConfigDirs(wp)) {
            refs += File(dir, "zcode.json").absolutePath to false
            refs += File(dir, ".zcode/config.json").absolutePath to false
        }
        val seen = mutableSetOf<String>()
        for ((path, explicit) in refs) {
            if (!seen.add(path)) continue
            val f = File(path)
            if (!f.exists()) continue
            val parsed = try {
                JSONObject(f.readText())
            } catch (e: Throwable) {
                errors += "无法读取 $path"
                continue
            }
            val hooksRaw = parsed.opt("hooks") ?: continue
            val hooks = parseHooksConfig(hooksRaw)
            if (hooks == null) {
                errors += "配置不符合 hooks 结构: $path"
                continue
            }
            val rel = relativePath(wp, path)
            val kind = when {
                explicit -> "explicit"
                File(path).name == "zcode.json" -> "zcode.json"
                else -> ".zcode/config.json"
            }
            projectSources += HookSource(
                canonicalPath = path,
                relPath = rel.ifEmpty { File(path).name },
                discoveryOrder = projectSources.size,
                configFileKind = kind,
                explicit = explicit,
                editable = File(path).absolutePath == File(File(wp, ".zcode"), "config.json").absolutePath,
                hooks = hooks,
            )
        }

        var userSource: HookSource? = null
        val userFile = File(home, ".zcode/cli/config.json")
        if (userFile.exists()) {
            try {
                val parsed = JSONObject(userFile.readText())
                val hooksRaw = parsed.opt("hooks")
                val hooks = if (hooksRaw != null) parseHooksConfig(hooksRaw) else null
                if (hooks != null) {
                    userSource = HookSource(
                        canonicalPath = userFile.absolutePath,
                        relPath = userFile.name,
                        discoveryOrder = 0,
                        configFileKind = "explicit",
                        explicit = true,
                        editable = true,
                        hooks = hooks,
                    )
                }
            } catch (_: Throwable) {
            }
        }

        val runtimeRoot = resolveRuntimeRoot(userSource?.hooks, projectSources)
        val entries = mutableListOf<HookEntry>()
        for (s in projectSources) entries += buildEntries(s, runtimeRoot)
        val userEntries = mutableListOf<HookEntry>()
        if (userSource != null) userEntries += buildEntries(userSource, runtimeRoot)

        val bundleDigest = if (entries.isEmpty()) {
            null
        } else {
            sha256(jsStringify(bundlePayload(projectSources, entries)))
        }

        return Snapshot(
            workspacePath = wp,
            projectSources = projectSources,
            userSource = userSource,
            runtimeRoot = runtimeRoot,
            entries = entries,
            userEntries = userEntries,
            bundleDigest = bundleDigest,
            errors = errors,
        )
    }

    /** 授予一个声明的持久信任；回调运行在调用线程（协议回调线程）。 */
    fun grant(
        rt: CoreRuntime,
        workspacePath: String,
        bundleDigest: String,
        declarationDigest: String,
        cb: (Boolean, String?) -> Unit,
    ) {
        val params = JSONObject()
            .put(
                "workspace",
                JSONObject().put("workspacePath", workspacePath).put("workspaceKey", workspacePath),
            )
            .put("bundleDigest", bundleDigest)
            .put("hookDeclarationDigest", declarationDigest)
        rt.call("workspace/hooks/trustGrant", params) { ok, body ->
            if (!ok) {
                cb(false, body.optString("message").ifEmpty { "请求失败" })
            } else {
                val accepted = body.optBoolean("accepted", false)
                cb(accepted, if (accepted) null else body.optString("reasonCode").ifEmpty { "未接受" })
            }
        }
    }

    /** 读取已信任摘要集合（trust store）。 */
    fun readTrustedDigests(workspaceKey: String, home: File): Set<String> {
        return try {
            val trustFile = trustStoreFile(home)
            if (!trustFile.exists()) return emptySet()
            val root = JSONObject(trustFile.readText())
            val records = root.optJSONArray("records") ?: return emptySet()
            val out = mutableSetOf<String>()
            for (i in 0 until records.length()) {
                val r = records.optJSONObject(i) ?: continue
                if (r.optString("workspaceIdentity") == workspaceKey) {
                    val d = r.optString("hookDeclarationDigest")
                    if (d.length == 64) out += d
                }
            }
            out
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun trustStoreFile(home: File): File {
        val configured = try {
            val cfg = File(home, ".zcode/cli/config.json")
            if (cfg.exists()) {
                JSONObject(cfg.readText()).optJSONObject("storage")?.optString("dir")?.trim().orEmpty()
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        }
        val storageRoot = when {
            configured.isEmpty() -> File(home, ".zcode")
            configured.startsWith("~/") -> File(home, configured.substring(2))
            configured.startsWith("/") -> File(configured)
            else -> File(home, configured)
        }
        return File(storageRoot, "security/workspace-hook-trust-v1.json")
    }

    // ------------------------------------------------------------ 编辑（仅工作区 .zcode/config.json）

    /** 添加一个 command 钩子；返回 null 表示成功。 */
    fun addHook(workspacePath: String, event: String, matcher: String?, command: String, timeoutMs: Long?): String? {
        if (!EVENT_NAMES.contains(event)) return "无效的事件: $event"
        if (command.isBlank()) return "命令不能为空"
        val f = File(File(workspacePath, ".zcode"), "config.json")
        val root = if (f.exists()) {
            try {
                JSONObject(f.readText())
            } catch (_: Throwable) {
                return "现有配置 JSON 解析失败"
            }
        } else {
            JSONObject()
        }
        val hooks = root.optJSONObject("hooks") ?: JSONObject().also { root.put("hooks", it) }
        val events = hooks.optJSONObject("events") ?: JSONObject().also { hooks.put("events", it) }
        val arr = events.optJSONArray(event) ?: JSONArray().also { events.put(event, it) }
        var target: JSONObject? = null
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("matcher").takeIf { it.isNotEmpty() } == matcher) {
                target = m
                break
            }
        }
        if (target == null) {
            target = JSONObject()
            if (matcher != null && matcher.isNotEmpty()) target.put("matcher", matcher)
            target.put("hooks", JSONArray())
            arr.put(target)
        }
        val h = JSONObject().put("type", "command").put("command", command.trim())
        if (timeoutMs != null && timeoutMs > 0) h.put("timeoutMs", timeoutMs)
        target.optJSONArray("hooks")?.put(h)
        return try {
            f.parentFile?.mkdirs()
            f.writeText(root.toString(2))
            null
        } catch (e: Throwable) {
            "写入失败: $e"
        }
    }

    /** 删除工作区源中的一个钩子（按来源文件与索引定位）；返回 null 表示成功。 */
    fun removeHook(sourcePath: String, event: String, matcherIndex: Int, hookIndex: Int): String? {
        val f = File(sourcePath)
        if (!f.exists()) return "文件不存在"
        val root = try {
            JSONObject(f.readText())
        } catch (_: Throwable) {
            return "JSON 解析失败"
        }
        val hooks = root.optJSONObject("hooks") ?: return "没有 hooks 段"
        val events = hooks.optJSONObject("events") ?: return "没有 events 段"
        val arr = events.optJSONArray(event) ?: return "事件不存在"
        if (matcherIndex >= arr.length()) return "索引越界"
        val m = arr.optJSONObject(matcherIndex) ?: return "结构异常"
        val hs = m.optJSONArray("hooks") ?: return "结构异常"
        if (hookIndex >= hs.length()) return "索引越界"
        hs.remove(hookIndex)
        if (hs.length() == 0) arr.remove(matcherIndex)
        return try {
            f.writeText(root.toString(2))
            null
        } catch (e: Throwable) {
            "写入失败: $e"
        }
    }

    // ------------------------------------------------------------ 内部实现

    private fun normalize(p: String): String {
        var s = p.trim().replace('\\', '/')
        while (s.length > 1 && s.endsWith('/')) s = s.dropLast(1)
        return s
    }

    /** 从工作区目录向上直到含 .git 的目录（含）；无 .git 时仅工作区自身。祖先在前。 */
    private fun projectConfigDirs(start: String): List<String> {
        val dirs = mutableListOf<String>()
        var cur = start
        while (true) {
            dirs.add(cur)
            if (File(cur, ".git").exists()) {
                return dirs.reversed()
            }
            val parent = File(cur).parent?.replace('\\', '/') ?: break
            if (parent == cur || parent.isEmpty()) break
            cur = parent
        }
        return listOf(start)
    }

    private fun relativePath(base: String, target: String): String {
        val b = normalize(base).split('/').filter { it.isNotEmpty() }
        val t = normalize(target).split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < b.size && i < t.size && b[i] == t[i]) i++
        val parts = mutableListOf<String>()
        repeat(b.size - i) { parts += ".." }
        parts += t.subList(i, t.size)
        return parts.joinToString("/")
    }

    /** 宽松解析 hooks 配置（events 结构）；结构不合法返回 null。 */
    private fun parseHooksConfig(raw: Any?): JSONObject? {
        val o = raw as? JSONObject ?: return null
        val events = o.optJSONObject("events") ?: return o
        for (k in events.keys()) {
            val arr = events.optJSONArray(k) ?: return null
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: return null
                if (m.optJSONArray("hooks") == null) return null
                val hs = m.optJSONArray("hooks")!!
                for (j in 0 until hs.length()) {
                    val h = hs.optJSONObject(j) ?: return null
                    val t = h.optString("type")
                    if (t != "command" && t != "process") return null
                }
            }
        }
        return o
    }

    private fun resolveRuntimeRoot(userHooks: JSONObject?, sources: List<HookSource>): RuntimeRoot {
        var enabled = false
        var timeoutMs = 60_000L
        var maxOutputBytes = 32_768L
        val roots = mutableListOf<JSONObject?>()
        roots += userHooks
        for (s in sources) roots += s.hooks
        for (r in roots) {
            if (r == null) continue
            if (r.has("enabled") && r.optBoolean("enabled")) enabled = true
            if (r.has("timeoutMs") && !r.isNull("timeoutMs")) timeoutMs = r.optLong("timeoutMs", timeoutMs)
            if (r.has("maxOutputBytes") && !r.isNull("maxOutputBytes")) {
                maxOutputBytes = r.optLong("maxOutputBytes", maxOutputBytes)
            }
        }
        return RuntimeRoot(enabled, Math.max(1L, timeoutMs), Math.max(1L, maxOutputBytes))
    }

    private fun buildEntries(source: HookSource, rr: RuntimeRoot): List<HookEntry> {
        val out = mutableListOf<HookEntry>()
        val events = source.hooks.optJSONObject("events") ?: return out
        val sourceRootEnabled = source.hooks.optBoolean("enabled", true)
        for (event in EVENT_NAMES) {
            val arr = events.optJSONArray(event) ?: continue
            for (mi in 0 until arr.length()) {
                val m = arr.optJSONObject(mi) ?: continue
                val matcher = m.optString("matcher").takeIf { it.isNotEmpty() }
                val hs = m.optJSONArray("hooks") ?: continue
                for (hi in 0 until hs.length()) {
                    val h = hs.optJSONObject(hi) ?: continue
                    val declarationEnabled = h.optBoolean("enabled", true)
                    val digest = sha256(
                        jsStringify(
                            declarationPayload(source, event, matcher, mi, hi, h, rr),
                        ),
                    )
                    out += HookEntry(
                        source = source,
                        event = event,
                        matcher = matcher,
                        matcherIndex = mi,
                        hookIndex = hi,
                        hook = h,
                        sourceRootEnabled = sourceRootEnabled,
                        declarationEnabled = declarationEnabled,
                        runtimeHooksEnabled = rr.enabled,
                        declarationDigest = digest,
                    )
                }
            }
        }
        return out
    }

    private fun declarationPayload(
        source: HookSource,
        event: String,
        matcher: String?,
        matcherIndex: Int,
        hookIndex: Int,
        hook: JSONObject,
        rr: RuntimeRoot,
    ): List<Any?> = listOf(
        "workspace-hook-declaration",
        1,
        source.relPath,
        source.discoveryOrder,
        event,
        matcher,
        matcherIndex,
        hookIndex,
        executionPayload(hook),
        resolveTimeoutMs(hook, rr.timeoutMs),
        rr.maxOutputBytes,
    )

    private fun executionPayload(hook: JSONObject): List<Any?> {
        return if (hook.optString("type") == "process") {
            listOf("process", hook.optString("command"), argsList(hook))
        } else {
            val shellRaw = if (hook.has("shell")) hook.get("shell") else null
            val shellPayload: List<Any?> = when {
                hook.isNull("shell") || shellRaw == null -> listOf("unset")
                shellRaw == true -> listOf("true")
                shellRaw is String -> listOf("string", shellRaw)
                else -> listOf("unset")
            }
            val async = hook.has("async") && !hook.isNull("async") && hook.optBoolean("async")
            listOf("command", hook.optString("command"), async, shellPayload)
        }
    }

    private fun argsList(hook: JSONObject): List<String> {
        val a = hook.optJSONArray("args") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until a.length()) out += a.optString(i)
        return out
    }

    private fun resolveTimeoutMs(hook: JSONObject, defaultMs: Long): Long {
        val direct = if (hook.has("timeoutMs") && !hook.isNull("timeoutMs")) hook.get("timeoutMs") else null
        val raw: Double = when {
            direct is Number -> direct.toDouble()
            hook.optString("type") == "command" && hook.has("timeout") && !hook.isNull("timeout") &&
                hook.get("timeout") is Number -> (hook.get("timeout") as Number).toDouble() * 1000.0
            else -> defaultMs.toDouble()
        }
        return Math.max(1L, Math.round(raw))
    }

    private fun bundlePayload(sources: List<HookSource>, entries: List<HookEntry>): List<Any?> = listOf(
        "workspace-hook-bundle",
        1,
        sources.map { s ->
            listOf<Any?>(
                s.relPath,
                s.discoveryOrder,
                s.configFileKind,
                s.explicit,
                optValue(s.hooks, "enabled"),
                optValue(s.hooks, "timeoutMs"),
                optValue(s.hooks, "maxOutputBytes"),
            )
        },
        entries.map { e ->
            listOf<Any?>(
                e.declarationDigest,
                e.sourceRootEnabled,
                e.declarationEnabled,
                e.runtimeHooksEnabled,
                e.configuredEnabled,
            )
        },
    )

    private fun optValue(o: JSONObject, key: String): List<Any?> {
        if (!o.has(key) || o.isNull(key)) return listOf("unset")
        val v = o.get(key)
        return when (v) {
            is Boolean -> listOf("set", v)
            is Number -> {
                val d = v.toDouble()
                val l = d.toLong()
                if (d == l.toDouble()) listOf("set", l) else listOf("set", d)
            }
            else -> listOf("set", v.toString())
        }
    }

    // ------------------------------------------------------------ JS 兼容序列化与摘要

    /** 复刻 JSON.stringify 的确定性输出（本场景全部为字符串/数字/布尔/null/数组）。 */
    private fun jsStringify(v: Any?): String = when (v) {
        null -> "null"
        is String -> jsQuote(v)
        is Boolean -> if (v) "true" else "false"
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { jsStringify(it) }
        else -> jsQuote(v.toString())
    }

    private fun jsQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}