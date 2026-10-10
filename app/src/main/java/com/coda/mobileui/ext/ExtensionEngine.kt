package com.coda.mobileui.ext

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 拓展引擎。
 *
 * 只做四件事：把拓展包放到该放的位置、按清单装载、维护启停状态、把各分类的对外能力汇总出来。
 * 引擎不理解任何分类的语义，分类的能力定义与消费都各自负责，这样新增分类不用动引擎。
 *
 * 三类目录：
 * 内置拓展先释放到 [builtinRoot]（随应用升级按版本刷新），用户拓展放在 [packageRoot]；
 * 两者的二进制都释放到 [runtimeRoot]，拓展包本身保持只读，卸载或重装只影响运行目录。
 */
class ExtensionEngine private constructor(context: Context) {

    /** 拓展集合或启停状态变化后的回调；宿主据此重建内核运行环境。 */
    fun interface Listener {
        fun onExtensionsChanged()
    }

    /** 对外的只读快照。 */
    data class Record(
        val id: String,
        val name: String,
        val version: String,
        val category: String,
        val categoryTitle: String,
        val description: String,
        val builtin: Boolean,
        val enabled: Boolean,
        val available: Boolean,
        val error: String?,
        val packageDir: String,
    )

    private class Entry(
        val manifest: ExtensionManifest,
        val packageDir: File,
        val runtimeDir: File,
        val builtin: Boolean,
        var enabled: Boolean,
        var provider: ExtensionProvider? = null,
        var error: String? = null,
    )

    private val ctx: Context = context.applicationContext
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val categories = LinkedHashMap<String, ExtensionCategory>()
    private val entries = LinkedHashMap<String, Entry>()
    private val listeners = mutableListOf<Listener>()
    private var loaded = false

    val packageRoot: File get() = File(ctx.filesDir, "extensions")
    val builtinRoot: File get() = File(ctx.filesDir, "extensions-builtin")
    val runtimeRoot: File get() = File(ctx.filesDir, "extensions-runtime")

    // ---------------------------------------------------------------- 分类注册

    /** 注册一个分类；分类是开放集合，注册即可用。 */
    @Synchronized
    fun registerCategory(category: ExtensionCategory) {
        categories[category.id] = category
    }

    @Synchronized
    fun categories(): List<ExtensionCategory> = categories.values.toList()

    @Synchronized
    fun category(id: String): ExtensionCategory? = categories[id]

    @Synchronized
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    // ---------------------------------------------------------------- 发现与装载

    /** 首次访问时装载；重复调用无副作用。 */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scan()
    }

    /** 重新扫描磁盘并按当前状态装载。 */
    @Synchronized
    fun reload(): List<Record> {
        loaded = true
        scan()
        return records()
    }

    private fun scan() {
        for (entry in entries.values) unloadEntry(entry)
        entries.clear()
        releaseBuiltinPackages()
        collect(builtinRoot, builtin = true)
        collect(packageRoot, builtin = false)
    }

    private fun collect(root: File, builtin: Boolean) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        for (dir in dirs) {
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.isFile) continue
            val text = try {
                manifestFile.readText()
            } catch (_: Throwable) {
                null
            } ?: continue
            val manifest = ExtensionManifest.parse(text) ?: continue
            // 内置包优先；同 id 的用户包不覆盖内置包。
            if (entries.containsKey(manifest.id)) continue
            // 分类没开放就不装载，避免把不属于任何契约的包当成可用能力。
            if (!categories.containsKey(manifest.category)) continue

            val enabled = prefs.getBoolean(
                KEY_ENABLED + manifest.id,
                manifest.raw.optBoolean("defaultEnabled", true),
            )
            val entry = Entry(
                manifest = manifest,
                packageDir = dir,
                runtimeDir = File(runtimeRoot, manifest.id),
                builtin = builtin,
                enabled = enabled,
            )
            if (enabled) loadEntry(entry)
            entries[manifest.id] = entry
        }
    }

    private fun loadEntry(entry: Entry) {
        entry.error = null
        entry.provider = null
        val category = categories[entry.manifest.category]
        if (category == null) {
            entry.error = "分类未开放：${entry.manifest.category}"
            return
        }
        val missing = category.requiredManifestKeys.filterNot { hasKey(entry.manifest.raw, it) }
        if (missing.isNotEmpty()) {
            entry.error = "缺少必需声明：${missing.joinToString("、")}"
            return
        }
        entry.runtimeDir.mkdirs()
        val provider = category.load(
            ExtensionLoadContext(entry.packageDir, entry.runtimeDir, entry.manifest),
        )
        if (provider == null) {
            entry.error = "不符合分类契约"
            return
        }
        if (!releaseBinaries(entry)) {
            entry.error = "二进制释放失败"
            return
        }
        if (!provider.prepare()) {
            entry.error = "拓展装载失败"
            return
        }
        entry.provider = provider
    }

    private fun unloadEntry(entry: Entry) {
        try {
            entry.provider?.release()
        } catch (_: Throwable) {
        }
        entry.provider = null
    }

    // ---------------------------------------------------------------- 二进制释放

    private fun releaseBinaries(entry: Entry): Boolean {
        var ok = true
        for (binary in entry.manifest.binaries) {
            val relative = binary.path.replace('\\', '/').trimStart('/')
            // 清单里的路径来自包自身，仍然挡住越界写法，避免写到运行目录之外。
            if (relative.isEmpty() || relative.split('/').any { it == ".." }) {
                ok = false
                continue
            }
            val source = resolveSource(entry.packageDir, relative)
            if (source == null) {
                ok = false
                continue
            }
            val target = File(entry.runtimeDir, relative)
            try {
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
                }
                Os.chmod(target.absolutePath, binary.mode)
            } catch (_: Throwable) {
                ok = false
                continue
            }
            if (!releaseLinks(entry, relative, binary.links)) ok = false
        }
        return ok
    }

    /**
     * 创建别名链接。
     *
     * 多调用二进制（busybox、toybox 之类）靠 argv[0] 判断自己是哪个程序，同一个文件必须以多个名字出现。
     * 优先建硬链接：内容与权限跟随同一 inode，任何名字被调用都等价；硬链接不可用时退回软链接。
     */
    private fun releaseLinks(entry: Entry, targetRelative: String, links: List<String>): Boolean {
        if (links.isEmpty()) return true
        val target = File(entry.runtimeDir, targetRelative)
        var ok = true
        for (link in links) {
            val linkRelative = link.replace('\\', '/').trimStart('/')
            if (linkRelative.isEmpty() || linkRelative.split('/').any { it == ".." }) {
                ok = false
                continue
            }
            val linkFile = File(entry.runtimeDir, linkRelative)
            if (linkFile.absolutePath == target.absolutePath) continue
            try {
                linkFile.parentFile?.mkdirs()
                linkFile.delete()
                val hardLinked = try {
                    Os.link(target.absolutePath, linkFile.absolutePath)
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!hardLinked) Os.symlink(target.absolutePath, linkFile.absolutePath)
            } catch (_: Throwable) {
                ok = false
            }
        }
        return ok
    }

    /**
     * 解析二进制来源。
     *
     * 拓展可以按 ABI 分层放置：`bin/sh` 的 ABI 版本是 `bin/<abi>/sh`。
     * 优先取当前设备 ABI 的那份，缺失时退回通用路径，这样一份包可以同时覆盖多种设备。
     */
    private fun resolveSource(packageDir: File, relative: String): File? {
        val direct = File(packageDir, relative)
        val parent = direct.parentFile
        val name = direct.name
        if (parent != null && name.isNotEmpty()) {
            val abiFile = File(File(parent, abi), name)
            if (abiFile.isFile) return abiFile
        }
        return if (direct.isFile) direct else null
    }

    private fun releaseBuiltinPackages() {
        val ids = try {
            ctx.assets.list(BUILTIN_ASSET_DIR)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        for (id in ids) {
            val assetPath = "$BUILTIN_ASSET_DIR/$id"
            val manifestText = try {
                ctx.assets.open("$assetPath/manifest.json").use { it.readBytes().decodeToString() }
            } catch (_: Throwable) {
                continue
            }
            val version = ExtensionManifest.parse(manifestText)?.version ?: continue
            val dest = File(builtinRoot, id)
            val stamp = File(dest, ".version")
            if (stamp.isFile && stamp.readText().trim() == version) continue
            deleteRecursive(dest)
            copyAssetDir(assetPath, dest)
            stamp.writeText(version)
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = try {
            ctx.assets.list(assetPath)
        } catch (_: Throwable) {
            null
        }
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            try {
                ctx.assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out, 256 * 1024) }
                }
            } catch (_: Throwable) {
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(dest, child))
    }

    // ---------------------------------------------------------------- 状态

    @Synchronized
    fun records(): List<Record> {
        ensureLoaded()
        return entries.values.map { snapshot(it) }
    }

    @Synchronized
    fun record(id: String): Record? {
        ensureLoaded()
        return entries[id]?.let { snapshot(it) }
    }

    private fun snapshot(entry: Entry): Record = Record(
        id = entry.manifest.id,
        name = entry.manifest.name,
        version = entry.manifest.version,
        category = entry.manifest.category,
        categoryTitle = categories[entry.manifest.category]?.title ?: entry.manifest.category,
        description = entry.manifest.description,
        builtin = entry.builtin,
        enabled = entry.enabled,
        available = entry.provider?.available == true,
        error = entry.error,
        packageDir = entry.packageDir.absolutePath,
    )

    /** 启停拓展；状态持久化，重启后仍然生效。 */
    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        ensureLoaded()
        val entry = entries[id] ?: return false
        prefs.edit().putBoolean(KEY_ENABLED + id, enabled).apply()
        entry.enabled = enabled
        if (enabled) loadEntry(entry) else unloadEntry(entry)
        notifyChanged()
        return true
    }

    /** 安装一个用户拓展（把源目录复制到用户拓展目录）。 */
    @Synchronized
    fun install(sourceDir: File): Record? {
        ensureLoaded()
        val manifestFile = File(sourceDir, "manifest.json")
        if (!manifestFile.isFile) return null
        val text = try {
            manifestFile.readText()
        } catch (_: Throwable) {
            null
        } ?: return null
        val manifest = ExtensionManifest.parse(text) ?: return null
        if (!categories.containsKey(manifest.category)) return null
        val dest = File(packageRoot, manifest.id)
        try {
            if (dest.exists()) deleteRecursive(dest)
            copyDir(sourceDir, dest)
        } catch (_: Throwable) {
            return null
        }
        scan()
        notifyChanged()
        return entries[manifest.id]?.let { snapshot(it) }
    }

    /** 卸载用户拓展；内置拓展只能停用，不能卸载。 */
    @Synchronized
    fun uninstall(id: String): Boolean {
        ensureLoaded()
        val entry = entries[id] ?: return false
        if (entry.builtin) return false
        unloadEntry(entry)
        deleteRecursive(entry.packageDir)
        deleteRecursive(entry.runtimeDir)
        entries.remove(id)
        prefs.edit().remove(KEY_ENABLED + id).apply()
        notifyChanged()
        return true
    }

    // ---------------------------------------------------------------- 能力

    /** 某分类下已启用的 provider，按发现顺序返回。 */
    @Synchronized
    fun providers(categoryId: String): List<ExtensionProvider> {
        ensureLoaded()
        return entries.values
            .filter { it.manifest.category == categoryId }
            .mapNotNull { it.provider }
    }

    /** 分类的对外能力；该分类没有可用拓展时为 null。 */
    @Synchronized
    fun capability(categoryId: String): ExtensionCapability? {
        ensureLoaded()
        val category = categories[categoryId] ?: return null
        val providers = providers(categoryId)
        if (providers.isEmpty()) return null
        return category.capability(providers)
    }

    /** 便捷入口：终端分类的对外能力。 */
    fun terminalCapability(): TerminalCapability? =
        capability(TerminalCategory.ID) as? TerminalCapability

    // ---------------------------------------------------------------- 工具

    private fun notifyChanged() {
        for (listener in listeners.toList()) {
            try {
                listener.onExtensionsChanged()
            } catch (_: Throwable) {
            }
        }
    }

    private fun copyDir(source: File, dest: File) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { copyDir(it, File(dest, it.name)) }
            return
        }
        dest.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 256 * 1024) }
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }

    /** 判断清单里是否存在某个点路径的键（形如 `terminal.shell`）且取值非空。 */
    private fun hasKey(obj: JSONObject, path: String): Boolean {
        val parts = path.split('.')
        var cursor: JSONObject = obj
        for ((index, part) in parts.withIndex()) {
            if (index == parts.lastIndex) {
                val value = cursor.opt(part)
                return when (value) {
                    null, JSONObject.NULL -> false
                    is String -> value.isNotEmpty()
                    is JSONArray -> value.length() > 0
                    else -> true
                }
            }
            cursor = cursor.optJSONObject(part) ?: return false
        }
        return false
    }

    companion object {

        private const val PREFS = "coda_extensions"
        private const val KEY_ENABLED = "enabled_"
        private const val BUILTIN_ASSET_DIR = "extensions"

        @Volatile
        private var instance: ExtensionEngine? = null

        fun get(context: Context): ExtensionEngine =
            instance ?: synchronized(this) {
                instance ?: ExtensionEngine(context).also { instance = it }
            }

        /** 设备首选 ABI；拓展按 ABI 分层放置二进制时用它选源。 */
        val abi: String
            get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
}