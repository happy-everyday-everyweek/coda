package com.coda.mobileui.core

/**
 * Agent 资产（Skill / Subagent / Command）的文本处理：frontmatter 解析与生成、
 * 名称校验、资产目录归属判定。
 *
 * 从 AgentAssets 里拆出来是为了能单测：这些判定原先内联在文件读写流程里，
 * 转义不全（#162）、死条件与 CRLF（#164）、正则重复构造（#165）、
 * 路径子串误判（#167）都无法单独验证。
 */
object AgentAssetText {
    /** 名称规则只编译一次；原先每次 save 都内联 Regex(...) 重新构造（#165）。 */
    private val NAME_PATTERN = Regex("[A-Za-z0-9_-]+")

    /** 名称非法时返回错误文案，合法返回 null。 */
    fun nameError(name: String): String? =
        if (name.isNotEmpty() && name.matches(NAME_PATTERN)) null else "名称只能包含字母、数字、连字符与下划线"

    /**
     * frontmatter 值转义（#162）。
     *
     * 顺序有硬要求：反斜杠必须最先处理，否则后面插入的转义反斜杠会被二次转义。
     * 换行/回车/制表符也要转成字面量——frontmatter 是单行 `key: value`，
     * 裸换行会把值截断，并把后半段当成新的键。
     */
    fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** 转义的逆操作；顺序与 escape 相反，单次扫描避免二次还原。 */
    fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> {
                        sb.append(c)
                        sb.append(next)
                    }
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * 解析 `---` 包裹的 frontmatter，只取简单 `key: value` 行。
     *
     * 与旧实现的差别（#164）：去掉永远为假的 lines.isEmpty()（split 至少返回一个元素），
     * 改为判断文本本身是否为空；容忍 UTF-8 BOM；按 \n 切行后逐行 trimEnd('\r')，
     * 使 CRLF 文件的首行也能被识别。
     */
    fun split(text: String): Pair<Map<String, String>, String> {
        if (text.isEmpty()) return emptyMap<String, String>() to text
        val normalized = text.removePrefix("\uFEFF")
        val lines = normalized.split("\n").map { it.trimEnd('\r') }
        if (lines[0].trim() != "---") return emptyMap<String, String>() to text
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
            val raw = line.substring(idx + 1).trim()
            fm[k] =
                if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    unescape(raw.substring(1, raw.length - 1))
                } else {
                    raw
                }
        }
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trimStart('\n')
        return fm to body
    }

    /**
     * 路径是否落在 `.zcode/agents/` 目录之下（#167）。
     *
     * 旧写法用 absolutePath.contains("/.zcode/agents/")，会把
     * `my.zcode/agents/`、`.zcode/agents-archive/` 这类路径也算进去。
     * 这里按路径段精确比较，并要求 agents 之后还有文件名（目录本身不算资产）。
     */
    fun isUnderSubagentDir(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size - 2) {
            if (parts[i] == ".zcode" && parts[i + 1] == "agents") return true
        }
        return false
    }
}
