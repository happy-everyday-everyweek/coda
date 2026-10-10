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
            dialect = section.optString("dialect", "").trim().ifEmpty { "posix" },
            pathEntrySpecs = readStringList(section, "pathEntries"),
            loginShell = section.optBoolean("loginShell", true),
        )
    }

    override fun capability(providers: List<ExtensionProvider>): ExtensionCapability? {
        // 终端能力是"一份"：多个终端拓展共存时取发现顺序里最靠前的可用者，
        // 其余拓展仍然装载，只是不参与这份对外能力。
        val primary = providers.filterIsInstance<TerminalProvider>().firstOrNull { it.available }
            ?: return null
        return TerminalCapabilityImpl(
            shellPath = primary.shellPath,
            dialect = primary.dialect,
            pathEntries = primary.pathEntries,
            loginShell = primary.loginShell,
        )
    }

    private fun readStringList(section: JSONObject, key: String): List<String> {
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
        val out = mutableMapOf(
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

    override fun prepare(): Boolean {
        val shell = resolveFile(shellSpec, requireExecutable = true) ?: return false
        resolvedShell = shell.absolutePath
        resolvedEntries = pathEntrySpecs.mapNotNull { resolveDir(it)?.absolutePath }
        available = true
        return true
    }

    override fun release() {
        available = false
    }

    /** 绝对路径直接用（系统 shell、系统工具目录），相对路径一律按运行目录解析。 */
    private fun resolveFile(spec: String, requireExecutable: Boolean): File? {
        val file = if (spec.startsWith("/")) File(spec) else File(runtimeDir, spec)
        if (!file.isFile) return null
        if (requireExecutable && !file.canExecute()) return null
        return file
    }

    private fun resolveDir(spec: String): File? {
        val dir = if (spec.startsWith("/")) File(spec) else File(runtimeDir, spec)
        return if (dir.isDirectory) dir else null
    }
}