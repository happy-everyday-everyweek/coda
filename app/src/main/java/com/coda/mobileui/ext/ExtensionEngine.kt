package com.coda.mobileui.ext

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import com.coda.mobileui.core.AppLog
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
class ExtensionEngine private constructor(
    context: Context,
) {
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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val packageRoot: File get() = File(ctx.filesDir, "extensions")
    val builtinRoot: File get() = File(ctx.filesDir, "extensions-builtin")
    val runtimeRoot: File get() = File(ctx.filesDir, "extensions-runtime")

    // ---------------------------------------------------------------- 分类注册

    /** 注册一个分类；分类是开放集合，注册即可用。 */
    @Synchronized
    fun registerCategory(category: ExtensionCategory) {
        val isNew = !categories.containsKey(category.id)
        categories[category.id] = category
        // 分类晚于扫描注册时，扫描阶段拿它判断不了契约的包会停在「分类未开放」；
        // 这里补一次装载，否则它们在本进程里永远不可用。
        if (isNew && loaded) loadDeferred(category.id)
    }

    /** 补装载某个分类下尚未装载的包。只在扫描已完成、分类后注册时调用。 */
    private fun loadDeferred(categoryId: String) {
        for (entry in entries.values) {
            if (entry.manifest.category != categoryId) continue
            if (!entry.enabled || entry.provider != null) continue
            try {
                loadEntry(entry)
            } catch (e: Throwable) {
                entry.error = e.message ?: e.toString()
                AppLog.e("ExtensionEngine", "补装载扩展失败：${entry.manifest.id}", e)
            }
        }
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

    /** 是否已经完成过一次扫描。 */
    @Synchronized
    fun isLoaded(): Boolean = loaded

    /** 首次访问时装载；重复调用无副作用。 */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            scan()
            // 扫描成功后才置位：中途抛异常时保持未加载，下一次调用还能重试。
            loaded = true
        } catch (e: Throwable) {
            com.coda.mobileui.core.AppLog.e("ExtensionEngine", "扫描扩展失败", e)
            for (entry in entries.values) unloadEntry(entry)
            entries.clear()
            loaded = false
        }
    }

    /** 重新扫描磁盘并按当前状态装载。 */
    @Synchronized
    fun reload(): List<Record> {
        try {
            scan()
            loaded = true
        } catch (e: Throwable) {
            com.coda.mobileui.core.AppLog.e("ExtensionEngine", "重载扩展失败", e)
            for (entry in entries.values) unloadEntry(entry)
            entries.clear()
            loaded = false
        }
        return records()
    }

    private fun scan() {
        for (entry in entries.values) unloadEntry(entry)
        entries.clear()
        releaseBuiltinPackages()
        collect(builtinRoot, builtin = true)
        collect(packageRoot, builtin = false)
    }

    private fun collect(
        root: File,
        builtin: Boolean,
    ) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        for (dir in dirs) {
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.isFile) continue
            val text =
                try {
                    manifestFile.readText()
                } catch (_: Throwable) {
                    null
                } ?: continue
            val manifest = ExtensionManifest.parse(text) ?: continue
            // 内置包优先；同 id 的用户包不覆盖内置包。
            if (entries.containsKey(manifest.id)) continue
            // 分类是否开放不在扫描阶段过滤：那时分类可能还没注册，直接跳过会让这个包
            // 既不进 entries、也没有错误记录，之后注册分类也无从补救。
            // 契约检查放在 loadEntry（它会记「分类未开放」），由 registerCategory 补装载。
            val enabled =
                prefs.getBoolean(
                    KEY_ENABLED + manifest.id,
                    manifest.raw.optBoolean("defaultEnabled", true),
                )
            val entry =
                Entry(
                    manifest = manifest,
                    packageDir = dir,
                    runtimeDir = File(runtimeRoot, manifest.id),
                    builtin = builtin,
                    enabled = enabled,
                )
            if (enabled) {
                try {
                    loadEntry(entry)
                } catch (e: Throwable) {
                    // 单个包失败只记录在该条目上，不能中断其余包的扫描。
                    entry.error = e.message ?: e.toString()
                    entry.provider?.release()
                    entry.provider = null
                    com.coda.mobileui.core.AppLog.e("ExtensionEngine", "装载扩展失败：${manifest.id}", e)
                }
            }
            entries[manifest.id] = entry
        }
    }

    private fun loadEntry(entry: Entry) {
        // 旧 provider 必须先释放：重复启用时直接置 null 会泄漏它持有的句柄与线程。
        entry.provider?.release()
        entry.provider = null
        entry.error = null
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
        val provider =
            try {
                category.load(ExtensionLoadContext(entry.packageDir, entry.runtimeDir, entry.manifest))
            } catch (e: Throwable) {
                entry.error = "装载异常：${e.message}"
                return
            }
        if (provider == null) {
            entry.error = "不符合分类契约"
            return
        }
        if (!releaseBinaries(entry)) {
            provider.release()
            entry.error = "二进制释放失败"
            return
        }
        // prepare 现在带回具体原因，直接展示给用户，不再笼统报「装载失败」（#476）。
        val prepared =
            try {
                provider.prepare()
            } catch (e: Exception) {
                PrepareResult.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        if (!prepared.isOk) {
            provider.release()
            entry.error = prepared.reasonOrNull ?: "拓展装载失败"
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
    private fun releaseLinks(
        entry: Entry,
        targetRelative: String,
        links: List<String>,
    ): Boolean {
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
                val hardLinked =
                    try {
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
    private fun resolveSource(
        packageDir: File,
        relative: String,
    ): File? {
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
        val ids =
            try {
                ctx.assets.list(BUILTIN_ASSET_DIR)?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        for (id in ids) {
            val assetPath = "$BUILTIN_ASSET_DIR/$id"
            val manifestText =
                try {
                    ctx.assets.open("$assetPath/manifest.json").use { it.readBytes().decodeToString() }
                } catch (_: Throwable) {
                    continue
                }
            val version = ExtensionManifest.parse(manifestText)?.version ?: continue
            val dest = File(builtinRoot, id)
            val stamp = File(dest, ".version")
            val stamped = runCatching { stamp.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            if (stamped == version) continue
            deleteRecursive(dest)
            // 拷贝成功才写版本戳：任一文件失败都保留“未释放”状态，下次启动重试。
            val copied = copyAssetDir(assetPath, dest)
            if (copied) {
                runCatching { stamp.writeText(version) }
                    .onFailure { com.coda.mobileui.core.AppLog.e("ExtensionEngine", "写入内置扩展版本戳失败：$id", it) }
            } else {
                com.coda.mobileui.core.AppLog.w("ExtensionEngine", "释放内置扩展不完整：$id")
                deleteRecursive(dest)
            }
        }
    }

    /** 递归释放 assets 目录；任一文件失败返回 false。 */
    private fun copyAssetDir(
        assetPath: String,
        dest: File,
    ): Boolean {
        val children =
            try {
                ctx.assets.list(assetPath)
            } catch (_: Throwable) {
                null
            }
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            return try {
                ctx.assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out, 256 * 1024) }
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (!dest.isDirectory && !dest.mkdirs()) return false
        var ok = true
        for (child in children) {
            if (!copyAssetDir("$assetPath/$child", File(dest, child))) ok = false
        }
        return ok
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

    private fun snapshot(entry: Entry): Record =
        Record(
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
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        ensureLoaded()
        val entry = entries[id] ?: return false
        // 幂等：状态没变就不重复装载，避免叠出未释放的 provider。
        if (entry.enabled == enabled) return true
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
        val text =
            try {
                manifestFile.readText()
            } catch (_: Throwable) {
                null
            } ?: return null
        val manifest = ExtensionManifest.parse(text) ?: return null
        if (!categories.containsKey(manifest.category)) return null
        val dest = File(packageRoot, manifest.id)
        val sourcePath = runCatching { sourceDir.canonicalPath }.getOrElse { sourceDir.absolutePath }
        val destPath = runCatching { dest.canonicalPath }.getOrElse { dest.absolutePath }
        // 从已安装目录重装时，先删 dest 会把源目录本身删掉。
        if (sourcePath == destPath || sourcePath.startsWith(destPath + File.separator)) return null
        packageRoot.mkdirs()
        // 先拷到临时目录，成功后再替换：失败不会留下半截包，也不会破坏旧版本。
        val staging = File(packageRoot, ".${manifest.id}.partial-${System.nanoTime()}")
        try {
            deleteRecursive(staging)
            copyDir(sourceDir, staging)
            if (dest.exists() && !deleteRecursive(dest)) {
                deleteRecursive(staging)
                return null
            }
            if (!staging.renameTo(dest)) {
                deleteRecursive(staging)
                return null
            }
        } catch (e: Throwable) {
            com.coda.mobileui.core.AppLog.e("ExtensionEngine", "安装扩展失败：${manifest.id}", e)
            deleteRecursive(staging)
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
        // 同 id 用户包被内置包遮蔽时，卸载对象应是用户包：清掉它并重扫，否则永远删不掉。
        val userDir = File(packageRoot, id)
        if (entry.builtin) {
            if (!userDir.isDirectory) return false
            if (!deleteRecursive(userDir)) return false
            prefs.edit().remove(KEY_ENABLED + id).apply()
            scan()
            // 运行目录按 manifest id 键控，用户包与内置包共享（#761）：
            // 用户包的二进制此前覆盖了内置包的，删掉用户包目录后幸存内置
            // 条目的 shellPath/PATH 仍指向旧内容。重扫后从内置包目录重新
            // 释放一遍，内置终端才不会静默损坏。
            entries[id]?.takeIf { it.builtin && it.enabled }?.let { survivor ->
                if (!releaseBinaries(survivor)) {
                    AppLog.w("ExtensionEngine", "卸载遮蔽包后重释放内置二进制失败：$id")
                }
            }
            notifyChanged()
            return true
        }
        unloadEntry(entry)
        // 包目录删不掉就保留条目与状态：否则下次扫描会把磁盘残包当新扩展重新启用。
        if (!deleteRecursive(entry.packageDir)) return false
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
    fun terminalCapability(): TerminalCapability? = capability(TerminalCategory.ID) as? TerminalCapability

    // ---------------------------------------------------------------- 工具

    private fun notifyChanged() {
        // 调用方都持有引擎监视器：回调改投主线程，避免在锁内执行宿主的重活或重入引擎。
        val snapshot = synchronized(this) { listeners.toList() }
        main.post {
            for (listener in snapshot) {
                try {
                    listener.onExtensionsChanged()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun copyDir(
        source: File,
        dest: File,
    ) {
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

    private fun deleteRecursive(file: File): Boolean {
        var ok = true
        if (file.isDirectory) {
            file.listFiles()?.forEach { if (!deleteRecursive(it)) ok = false }
        }
        if (file.exists() && !file.delete()) ok = false
        return ok && !file.exists()
    }

    /** 判断清单里是否存在某个点路径的键（形如 `terminal.shell`）且取值非空。 */
    private fun hasKey(
        obj: JSONObject,
        path: String,
    ): Boolean {
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
