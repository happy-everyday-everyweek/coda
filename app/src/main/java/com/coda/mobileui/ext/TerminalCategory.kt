package com.coda.mobileui.ext

import org.json.JSONObject
import java.io.File

/**
 * 终端分类。
 *
 * 这个分类把"可用的终端"变成一份标准能力：拓展负责提供可执行的 shell（以及随包带的工具目录），
 * 分类把已启用拓展合成为 [TerminalCapability] 交给宿主，宿主再把它作为环境注入内核。
 *
 * 清单里的声明段：
 * ```json
 * "terminal": {
 *   "shell": "bin/sh",          // 相对运行目录，或绝对路径（例如 /system/bin/sh）
 *   "dialect": "posix",         // posix / git-bash / cmd
 *   "pathEntries": ["bin"],     // 加入 PATH 的目录（相对运行目录或绝对路径）
 *   "loginShell": true          // 可选，默认 true
 * }
 * ```
 */
object TerminalCategory : ExtensionCategory {
    const val ID = "terminal"

    /** 注入内核的 shell 可执行路径。 */
    const val ENV_SHELL = "ZCODE_TERMINAL_SHELL"

    /** 注入内核的 shell 方言。 */
    const val ENV_DIALECT = "ZCODE_TERMINAL_DIALECT"

    /** 是否以登录 shell 执行：`1` / `0`。 */
    const val ENV_LOGIN = "ZCODE_TERMINAL_LOGIN"

    /** 需要追加到 PATH 的目录，按系统路径分隔符拼接。 */
    const val ENV_PATH = "ZCODE_TERMINAL_PATH"

    override val id: String = ID

    override val title: String = "终端"

    /** 终端拓展至少要声明 shell，否则不构成"能提供终端"。 */
    override val requiredManifestKeys: List<String> = listOf("$ID.shell")

    override fun load(context: ExtensionLoadContext): ExtensionProvider? {
        val section = context.manifest.section(ID)
        val shell = section.optString("shell", "").trim()
        if (shell.isEmpty()) return null
        return TerminalProviderImpl(
            extensionId = context.manifest.id,
            runtimeDir = context.runtimeDir,
            shellSpec = shell,
            dialect = normalizeDialect(section.optString("dialect", "")),
            pathEntrySpecs = readStringList(section, "pathEntries"),
            loginShell = section.optBoolean("loginShell", true),
        )
    }

    override fun capability(providers: List<ExtensionProvider>): ExtensionCapability? {
        // 终端能力是"一份"：多个终端拓展共存时取发现顺序里最靠前的可用者，
        // 其余拓展仍然装载，只是不参与这份对外能力。
        val primary =
            providers.filterIsInstance<TerminalProvider>().firstOrNull { it.available }
                ?: return null
        return TerminalCapabilityImpl(
            shellPath = primary.shellPath,
            dialect = primary.dialect,
            pathEntries = primary.pathEntries,
            loginShell = primary.loginShell,
        )
    }

    /** 内核认识的方言取值；其余一律回退默认，避免把非法值原样注入（#483）。 */
    /** 合法方言白名单：只在本文件的归一化里用，测试也通过 normalizeDialect 间接覆盖。 */
    private val DIALECTS = setOf("posix", "git-bash", "cmd")
    const val DEFAULT_DIALECT = "posix"

    /** 统一小写并做白名单校验；不认识的值回退 posix 并落日志。 */
    fun normalizeDialect(raw: String): String {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return DEFAULT_DIALECT
        if (value in DIALECTS) return value
        com.coda.mobileui.core.AppLog.w("TerminalCategory", "未知方言 '$raw'，已回退 $DEFAULT_DIALECT")
        return DEFAULT_DIALECT
    }

    /**
     * 规格是否为绝对路径（#480）。
     * 除 POSIX 的 / 之外，还要认 Windows 盘符（C:\、C:/）与 UNC（\\server\share）：
     * git-bash / cmd 方言的清单会写这类路径，当成相对路径拼到 runtimeDir 下必然找不到。
     */
    fun isAbsoluteSpec(spec: String): Boolean {
        val s = spec.trim()
        val backslash = '\\'
        // UNC 以两个反斜杠开头
        if (s.startsWith("/") || s.startsWith("$backslash$backslash")) return true
        return s.length >= 3 && s[0].isLetter() && s[1] == ':' && (s[2] == backslash || s[2] == '/')
    }

    private fun readStringList(
        section: JSONObject,
        key: String,
    ): List<String> {
        val arr = section.optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i, "").trim()
            if (value.isNotEmpty()) out += value
        }
        return out
    }
}

/** 终端拓展须实现的接口：给宿主一个可执行的 shell，以及配套的环境说明。 */
interface TerminalProvider : ExtensionProvider {
    /** shell 可执行文件的绝对路径。 */
    val shellPath: String

    /** 方言：`posix` / `git-bash` / `cmd`，与内核的 shell 方言取值对齐。 */
    val dialect: String

    /** 需要加入 PATH 的目录（绝对路径）。 */
    val pathEntries: List<String>

    /** 是否以登录 shell 方式执行。 */
    val loginShell: Boolean
}

/** 终端分类的对外能力。 */
interface TerminalCapability : ExtensionCapability {
    val shellPath: String

    val dialect: String

    val pathEntries: List<String>

    val loginShell: Boolean

    /** 宿主注入内核的环境变量补丁。 */
    fun env(): Map<String, String> {
        val out =
            mutableMapOf(
                TerminalCategory.ENV_SHELL to shellPath,
                TerminalCategory.ENV_DIALECT to dialect,
                TerminalCategory.ENV_LOGIN to if (loginShell) "1" else "0",
            )
        if (pathEntries.isNotEmpty()) {
            out[TerminalCategory.ENV_PATH] = pathEntries.joinToString(File.pathSeparator)
        }
        return out
    }
}

private data class TerminalCapabilityImpl(
    override val shellPath: String,
    override val dialect: String,
    override val pathEntries: List<String>,
    override val loginShell: Boolean,
) : TerminalCapability {
    override val categoryId: String get() = TerminalCategory.ID
}

private class TerminalProviderImpl(
    override val extensionId: String,
    private val runtimeDir: File,
    private val shellSpec: String,
    override val dialect: String,
    private val pathEntrySpecs: List<String>,
    override val loginShell: Boolean,
) : TerminalProvider {
    override var available: Boolean = false
        private set

    private var resolvedShell: String = ""
    private var resolvedEntries: List<String> = emptyList()

    override val shellPath: String get() = resolvedShell

    override val pathEntries: List<String> get() = resolvedEntries

    override fun prepare(): PrepareResult {
        // 判断与建路径必须用同一个 trim 过的值（OCR low #827）：isAbsoluteSpec 内部会
        // trim，这里若用原串构造 File，「 /system/bin/sh」这样的值会被判成绝对路径，
        // 然后拿带空格的名字去查文件，报出误导性的「shell 不存在」。
        val spec = shellSpec.trim()
        val shellFile = File(if (TerminalCategory.isAbsoluteSpec(spec)) spec else File(runtimeDir, spec).path)
        if (!shellFile.isFile) {
            return PrepareResult.Failed("shell 不存在：${shellFile.absolutePath}")
        }
        if (!shellFile.canExecute()) {
            return PrepareResult.Failed("shell 无执行权限：${shellFile.absolutePath}")
        }
        resolvedShell = shellFile.absolutePath
        // 解析不出来的 PATH 条目不能静默丢弃：shell 会拿到不完整的 PATH（#482）。
        val resolved = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (spec in pathEntrySpecs) {
            val dir = resolveDir(spec)
            if (dir != null) resolved += dir.absolutePath else missing += spec
        }
        if (missing.isNotEmpty()) {
            com.coda.mobileui.core.AppLog.w(
                "TerminalCategory",
                "PATH 条目无法解析，已跳过：${missing.joinToString("、")}（运行目录 ${runtimeDir.absolutePath}）",
            )
        }
        resolvedEntries = resolved
        available = true
        return PrepareResult.Ok
    }

    override fun release() {
        available = false
    }

    private fun resolveDir(spec: String): File? {
        val dir = if (TerminalCategory.isAbsoluteSpec(spec)) File(spec) else File(runtimeDir, spec)
        return if (dir.isDirectory) dir else null
    }
}
