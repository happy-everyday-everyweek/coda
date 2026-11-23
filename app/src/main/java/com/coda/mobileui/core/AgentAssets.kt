package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 用户级 Agent 资产（子智能体 / 命令 / 技能）的文件管理。
 *
 * 目录与运行时内核（桌面端同款落盘格式）一致：
 * - 子智能体: {HOME}/.zcode/agents/<name>.md     （frontmatter 必须 name/description）
 * - 命令:     {HOME}/.zcode/commands/<name>.md   （frontmatter description 可选）
 * - 技能:     {HOME}/.zcode/skills/<name>/SKILL.md（frontmatter name/description）
 *
 * 保存后无需重启：运行时在新建会话 / 扫描目录时现读（已在冒烟环境实测）。
 */
object AgentAssets {
    enum class Kind(
        val label: String,
        val relDir: String,
    ) {
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

    fun rootDir(
        ctx: Context,
        kind: Kind,
    ): File {
        val base = File(File(ctx.filesDir, "home"), ".zcode")
        return File(base, kind.relDir.removePrefix(".zcode/"))
    }

    fun list(
        ctx: Context,
        kind: Kind,
    ): List<Item> {
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
                dir
                    .listFiles()
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
        val kind =
            when {
                f.name == "SKILL.md" -> Kind.SKILL
                AgentAssetText.isUnderSubagentDir(f.absolutePath) -> Kind.SUBAGENT
                else -> Kind.COMMAND
            }
        return parseFile(kind, f)
    }

    private fun parseFile(
        kind: Kind,
        f: File,
    ): Item? =
        try {
            val (fm, body) = splitFrontmatter(f.readText())
            val fallback =
                if (kind == Kind.SKILL) {
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
        } catch (e: Throwable) {
            // 解析失败要能定位到文件，而不是静默从列表里消失。
            AppLog.w("AgentAssets", "解析资产失败：${f.absolutePath} ${e.message}")
            null
        }

    /** 解析委托给纯逻辑层（有单测覆盖 BOM/CRLF/转义还原）。 */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> = AgentAssetText.split(text)

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
        AgentAssetText.nameError(name)?.let { return it }
        val root = rootDir(ctx, kind).canonicalFile
        val target: File
        if (editingPath != null) {
            // 编辑路径来自外部 Intent/状态：必须落在该 kind 的资产根内，不能写任意文件。
            target = File(editingPath).canonicalFile
            // OCR #819：inside 对 target==root 也返回 true；根目录还不存在时
            // rename 会把根路径变成普通文件，list/save/delete 全部报废。与 delete 同规。
            if (!inside(target, root) || target == root) return "路径不在${kind.label}目录内"
        } else {
            target =
                when (kind) {
                    Kind.SKILL -> File(File(root, name), "SKILL.md")
                    else -> File(root, "$name.md")
                }
            if (File(root, name).exists() || target.exists()) return "同名${kind.label}已存在"
        }
        return try {
            target.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("---\n")
            sb.append("name: \"").append(escape(name)).append("\"\n")
            if (description.isNotEmpty()) {
                sb.append("description: \"").append(escape(description)).append("\"\n")
            }
            sb.append("---\n\n")
            sb.append(body)
            if (!body.endsWith("\n")) sb.append("\n")
            // 原子替换：写一半失败不会留下损坏的资产文件。
            // OCR #819：临时名带纳秒后缀——固定 `<name>.tmp` 在并发保存同一资产时
            // 共用一路径，一个写者会把另一个的半截内容 rename 上线。
            val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
            try {
                tmp.writeText(sb.toString())
                if (!tmp.renameTo(target)) {
                    return "写入失败: 无法替换 ${target.name}"
                }
                null
            } finally {
                // 写失败（满盘）/rename 失败都要清掉残留 tmp，不留孤儿。
                if (tmp.exists()) runCatching { tmp.delete() }
            }
        } catch (t: Throwable) {
            "写入失败: ${t.message}"
        }
    }

    private fun inside(
        target: File,
        root: File,
    ): Boolean = target == root || target.path.startsWith(root.path + File.separator)

    /** 转义委托给纯逻辑层：原先只处理引号与反斜杠，裸换行会截断 frontmatter（#162）。 */
    private fun escape(value: String): String = AgentAssetText.escape(value)

    fun delete(
        ctx: Context,
        kind: Kind,
        path: String,
    ): Boolean {
        val root = rootDir(ctx, kind).canonicalFile
        val f = File(path).canonicalFile
        if (!inside(f, root) || f == root) return false
        return try {
            if (f.name == "SKILL.md") {
                // 只删技能自己的目录，且必须正好是 root 的直接子目录。
                val dir = f.parentFile?.canonicalFile ?: return false
                if (dir.parentFile?.canonicalFile != root) return false
                dir.deleteRecursively()
            } else {
                f.delete()
            }
        } catch (e: Throwable) {
            AppLog.w("AgentAssets", "删除资产失败：$path ${e.message}")
            false
        }
    }
}
