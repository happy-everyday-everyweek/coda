package com.zcode.mobileui.core

import android.content.Context
import java.io.File

/**
 * 用户级 Agent 资产（子智能体 / 命令 / 技能）的文件管理。
 *
 * 目录与 ZCode 运行时一致（桌面端同款落盘格式）：
 * - 子智能体: {HOME}/.zcode/agents/<name>.md     （frontmatter 必须 name/description）
 * - 命令:     {HOME}/.zcode/commands/<name>.md   （frontmatter description 可选）
 * - 技能:     {HOME}/.zcode/skills/<name>/SKILL.md（frontmatter name/description）
 *
 * 保存后无需重启：运行时在新建会话 / 扫描目录时现读（已在冒烟环境实测）。
 */
object AgentAssets {

    enum class Kind(val label: String, val relDir: String) {
        SUBAGENT("子智能体", ".zcode/agents"),
        COMMAND("命令", ".zcode/commands"),
        SKILL("技能", ".zcode/skills"),
    }

    data class Item(
        val kind: Kind,
        val name: String,
        val description: String,
        val path: String,
        val body: String,
    )

    fun rootDir(ctx: Context, kind: Kind): File {
        val base = File(File(ctx.filesDir, "home"), ".zcode")
        return File(base, kind.relDir.removePrefix(".zcode/"))
    }

    fun list(ctx: Context, kind: Kind): List<Item> {
        val dir = rootDir(ctx, kind)
        if (!dir.exists()) return emptyList()
        val out = mutableListOf<Item>()
        when (kind) {
            Kind.SKILL -> {
                dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
                    val f = File(d, "SKILL.md")
                    if (f.exists()) parseFile(kind, f)?.let { out += it }
                }
            }
            else -> {
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".md") }
                    ?.sortedBy { it.name }
                    ?.forEach { f -> parseFile(kind, f)?.let { out += it } }
            }
        }
        return out
    }

    fun readItem(path: String): Item? {
        val f = File(path)
        if (!f.exists()) return null
        val kind = when {
            f.name == "SKILL.md" -> Kind.SKILL
            f.absolutePath.contains("/.zcode/agents/") -> Kind.SUBAGENT
            else -> Kind.COMMAND
        }
        return parseFile(kind, f)
    }

    private fun parseFile(kind: Kind, f: File): Item? {
        return try {
            val (fm, body) = splitFrontmatter(f.readText())
            val fallback = if (kind == Kind.SKILL) {
                f.parentFile?.name ?: f.name
            } else {
                f.name.removeSuffix(".md")
            }
            Item(
                kind = kind,
                name = fm["name"]?.takeIf { it.isNotEmpty() } ?: fallback,
                description = fm["description"].orEmpty(),
                path = f.absolutePath,
                body = body,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 解析 `---` 前后包裹的 frontmatter；只取简单 `key: value` 行。 */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return emptyMap<String, String>() to text
        }
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                end = i
                break
            }
        }
        if (end == -1) return emptyMap<String, String>() to text
        val fm = mutableMapOf<String, String>()
        for (i in 1 until end) {
            val line = lines[i]
            if (line.startsWith(" ") || line.startsWith("\t")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim().trim('"')
            fm[k] = v
        }
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trimStart('\n')
        return fm to body
    }

    /**
     * 保存：editingPath 非空时覆盖该文件，否则按 kind 新建。
     * 返回错误信息（null 表示成功）。
     */
    fun save(
        ctx: Context,
        kind: Kind,
        name: String,
        description: String,
        body: String,
        editingPath: String?,
    ): String? {
        if (!name.matches(Regex("[A-Za-z0-9_-]+"))) {
            return "名称只能包含字母、数字、连字符与下划线"
        }
        val target: File = if (editingPath != null) {
            File(editingPath)
        } else {
            when (kind) {
                Kind.SKILL -> File(File(rootDir(ctx, kind), name), "SKILL.md")
                else -> File(rootDir(ctx, kind), "$name.md")
            }
        }
        return try {
            target.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("---\n")
            sb.append("name: \"").append(name.replace("\"", "\\\"")).append("\"\n")
            if (description.isNotEmpty()) {
                sb.append("description: \"").append(description.replace("\"", "\\\"")).append("\"\n")
            }
            sb.append("---\n\n")
            sb.append(body)
            if (!body.endsWith("\n")) sb.append("\n")
            target.writeText(sb.toString())
            null
        } catch (t: Throwable) {
            "写入失败: ${t.message}"
        }
    }

    fun delete(path: String) {
        val f = File(path)
        try {
            if (f.name == "SKILL.md") {
                f.parentFile?.deleteRecursively()
            } else {
                f.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
