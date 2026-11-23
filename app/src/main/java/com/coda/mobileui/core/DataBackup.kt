package com.coda.mobileui.core

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 设置与本地数据的整体备份。
 *
 * 导出为一个 zip：两份 SharedPreferences（界面设置、核心桥设置）、模型供应商配置、
 * 用户级与工作区级两份提示词文件。会话记录、日志与应用本身不在备份范围内。
 */
object DataBackup {
    /** 备份格式版本。3 起支持选择性导出（categories + partial）。 */
    private const val SCHEMA = 3

    /**
     * files 目录下不备份的顶层项。
     *
     * core 是内核载荷，装包或首次启动会按清单重新释放；tmp 是临时目录。
     * 其余目录全部带走，包括 linux 根文件系统——终端里装过的包、网关凭据与
     * 代理配置都在里面，不带就无法恢复到原状。
     */
    private val SKIP_FILES_TOP = setOf("core", "tmp", "cache")

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PROVIDERS = "provider_config.json"
    private const val ENTRY_PROVIDER_META = "coda-provider-meta.json"
    private const val ENTRY_ASR_PROVIDERS = "coda-asr-providers.json"
    private const val ENTRY_GLOBAL_PROMPT = "prompts/global-AGENTS.md"
    private const val ENTRY_WORKSPACE_PROMPT = "prompts/workspace-AGENTS.md"

    /** 文本条目上限；导入时用流式限长读取，不信任 ZipEntry 自报的大小。 */
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** 单文件与整包解压上限，阻止高压缩比备份耗尽存储。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 200_000
    private val PREFS_NAME = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")

    /** 需要备份的 SharedPreferences。 */
    private val PREFS = listOf("zcode_ui_settings", "zcode_bridge")

    /**
     * 导出到给定 Uri，返回写入的条目数；失败抛出异常。
     *
     * [selected] 决定打包哪些分类，默认全选（与旧版全量行为一致）。
     * 只勾选设置或提示词时不会碰 rootfs 与凭据文件，包体积显著小于全量。
     */
    fun export(
        ctx: Context,
        uri: Uri,
        selected: Set<BackupCategory> = BackupCategory.DEFAULT,
    ): Int {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法写入目标文件")
        var count = 0
        // 实际写进包的路径集合：清单只能引用这里面的条目，否则部分包恢复时
        // 会出现悬空链接或对不存在的文件 chmod。
        val exportedPaths = mutableSetOf<String>()
        ZipOutputStream(out).use { zip ->
            fun putText(
                name: String,
                text: String,
            ) {
                if (!BackupCategory.shouldExport(name, selected)) return
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                exportedPaths += name
                count++
            }

            val execEntries = mutableListOf<String>()

            fun putFile(
                name: String,
                file: File,
            ) {
                if (!BackupCategory.shouldExport(name, selected)) return
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                exportedPaths += name
                if (file.canExecute()) execEntries += name
                count++
            }

            val symlinks = mutableListOf<Pair<String, String>>()

            fun addTree(
                entry: String,
                file: File,
            ) {
                // zip 不保留符号链接：把链接当普通文件读会把目标内容复制一份。
                // Linux 根文件系统里大量工具是链接，复制会让备份膨胀且丢语义，
                // 所以单独记下链接目标，恢复时重建。
                if (isSymlink(file)) {
                    runCatching { Os.readlink(file.absolutePath) }.getOrNull()?.let {
                        symlinks += entry to it
                    }
                    return
                }
                if (file.isFile) {
                    putFile(entry, file)
                    return
                }
                file.listFiles()?.forEach { child -> addTree("$entry/${child.name}", child) }
            }

            // 设置：收集全部 SharedPreferences，而不只是内置的两份。
            val prefNames = LinkedHashSet(PREFS)
            File(ctx.filesDir.parentFile, "shared_prefs")
                .listFiles { f -> f.isFile && f.name.endsWith(".xml") }
                ?.forEach { prefNames += it.name.removeSuffix(".xml") }
            prefNames.forEach { name ->
                val obj = JSONObject()
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    obj.put(k, encode(v))
                }
                putText("prefs/$name.json", obj.toString(2))
            }

            val providers = ProviderStore.fileFor(ctx)
            if (providers.isFile) putText(ENTRY_PROVIDERS, providers.readText())
            // 旁路文件必须一起备份：少了 meta，OAuth 供应商会被当成手填条目；
            // 少了 ASR 文件，换设备后所有转写供应商直接消失。
            ProviderStore.metaFileFor(ctx).takeIf { it.isFile }?.let {
                putText(ENTRY_PROVIDER_META, it.readText())
            }
            ProviderStore.asrFileFor(ctx).takeIf { it.isFile }?.let {
                putText(ENTRY_ASR_PROVIDERS, it.readText())
            }

            // 用户状态目录：除可重新释放的内核载荷外全带走，linux 根文件系统也在内。
            ctx.filesDir.listFiles()?.forEach { top ->
                if (top.name !in SKIP_FILES_TOP) addTree("files/${top.name}", top)
            }
            // 应用数据库（若存在）不属于 filesDir，需要单独收。
            File(ctx.filesDir.parentFile, "databases").takeIf { it.exists() }?.let {
                addTree("databases", it)
            }
            // no_backup 也是应用私有持久区，部分凭据与缓存放在这里。
            ctx.noBackupFilesDir.takeIf { it.exists() }?.let {
                addTree("no-backup", it)
            }

            // 两份清单必须在所有文件条目之后写入：恢复时先落文件，再按清单补执行位与链接。
            // 只收录已导出路径：部分包里留下未导出路径会造成悬空链接与 chmod 失败。
            val keptExec = BackupCategory.filterManifest(execEntries, exportedPaths)
            if (keptExec.isNotEmpty()) putText("file-modes.txt", keptExec.joinToString("\n"))
            val keptLinks =
                symlinks.filter { (path, target) ->
                    if (!BackupCategory.shouldExport(path, selected)) return@filter false
                    // 目标也要过滤（OCR #823）：保留的链接指向未导出分类会重建
                    // 悬空链接。相对目标按链接自身包路径解析后必须仍在导出集合里；
                    // 绝对系统目标（rootfs 内）按原样保留。
                    if (target.startsWith("/")) return@filter true
                    val parent = path.substringBeforeLast('/', "")
                    val joined = if (parent.isEmpty()) target else "$parent/$target"
                    val resolved = TarListing.normalizePath(joined)
                    resolved.isNotEmpty() &&
                        !resolved.startsWith("/") &&
                        resolved.split('/').none { it == ".." } &&
                        (resolved in exportedPaths || exportedPaths.any { it.startsWith("$resolved/") })
                }
            if (keptLinks.isNotEmpty()) {
                putText("symlinks.txt", keptLinks.joinToString("\n") { "${it.first}\t${it.second}" })
            }

            val global = AgentPrompt.globalFile(ctx)
            if (global.isFile) putText(ENTRY_GLOBAL_PROMPT, global.readText())

            val workspace = AgentPrompt.workspaceFile(ctx)
            if (workspace.isFile) putText(ENTRY_WORKSPACE_PROMPT, workspace.readText())

            val manifest =
                JSONObject()
                    .put("schema", SCHEMA)
                    .put("app", "coda")
                    .put("exportedAt", System.currentTimeMillis())
                    .put("entries", count)
                    // 分类清单与 partial 标记：导入端据此知道「这个包会覆盖什么」，
                    // 未包含的分类保持原样不动。
                    .put("categories", BackupCategory.toJson(selected))
                    .put("partial", BackupCategory.isPartial(selected))
            putText(ENTRY_MANIFEST, manifest.toString(2))
        }
        return count
    }

    /**
     * 只做预检：把外部文件落到私有临时文件并跑完所有校验，不写任何正式数据。
     *
     * 调用方据此决定要不要先停运行环境：一个损坏或不受支持的包，根本不该让核心停下来。
     *
     * @return 预检通过返回 null，否则返回可直接展示的原因。
     */
    fun validate(
        ctx: Context,
        uri: Uri,
    ): String? =
        try {
            withPackage(ctx, uri) { file -> validatePackage(ctx, file) }
            null
        } catch (e: Exception) {
            AppLog.w("Backup", "备份预检未通过：${e.message}")
            e.message ?: "无法读取备份文件"
        }

    /** 从给定 Uri 恢复，返回恢复的条目数；失败抛出异常。 */
    fun import(
        ctx: Context,
        uri: Uri,
    ): Int =
        withPackage(ctx, uri) { packageFile ->
            validatePackage(ctx, packageFile)
            importPackage(ctx, packageFile)
        }

    /** 把 Uri 复制到私有临时文件后交给 [block]，结束时一定删掉临时文件。 */
    private fun <T> withPackage(
        ctx: Context,
        uri: Uri,
        block: (File) -> T,
    ): T {
        // manifest 可能在包尾，不能在解压流里读到一半才开始写，所以先落盘。
        val packageFile = File(ctx.cacheDir, "backup-import-${System.nanoTime()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(packageFile).use { output ->
                    // 边拷边判上限（OCR #823）：无上限 copyTo 会把多 GB 的包
                    // 整个灌进内部存储，恰好耗尽 MAX_TOTAL_BYTES 要保护的空间。
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("备份文件超过上限")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalStateException("无法读取备份文件")
            return block(packageFile)
        } finally {
            packageFile.delete()
        }
    }

    /**
     * 暂存一次供预检与导入复用（OCR #823）：validate(uri)+import(uri) 各自
     * withPackage 会把同一个多 GB 包拷两遍。调用方先 stage，再用
     * validateStaged/importStaged，最后 close 删临时文件。
     */
    class StagedPackage internal constructor(
        internal val file: File,
    ) : java.io.Closeable {
        override fun close() {
            runCatching { file.delete() }
        }
    }

    /** 把 Uri 落到私有临时文件（限长）；调用方负责 close。 */
    fun stage(ctx: Context, uri: Uri): StagedPackage {
        val packageFile = File(ctx.cacheDir, "backup-import-${System.nanoTime()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(packageFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("备份文件超过上限")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalStateException("无法读取备份文件")
            return StagedPackage(packageFile)
        } catch (t: Throwable) {
            runCatching { packageFile.delete() }
            throw t
        }
    }

    /** 对已暂存的包做预检；通过返回 null。 */
    fun validateStaged(ctx: Context, staged: StagedPackage): String? =
        try {
            validatePackage(ctx, staged.file)
            null
        } catch (e: Exception) {
            AppLog.w("Backup", "备份预检未通过：${e.message}")
            e.message ?: "无法读取备份文件"
        }

    /** 导入已暂存且预检通过的包。 */
    fun importStaged(ctx: Context, staged: StagedPackage): Int = importPackage(ctx, staged.file)

    /** 预检整个包；所有检查都在覆盖用户数据之前完成。 */
    private fun validatePackage(
        ctx: Context,
        file: File,
    ) {
        var manifest: JSONObject? = null
        ZipFile(file).use { zip ->
            // size() 不物化条目（OCR #823）：百万级中央目录记录先 toList 会
            // 分配数百 MB ZipEntry 直接 OOM，守卫本身成了攻击面。
            if (zip.size() > MAX_ENTRIES) throw IllegalStateException("备份条目过多")
            val names = zip.entries().asSequence().toList()
            // 预检就拦总量（OCR #823）：只查单条会让 zip-bomb 通过 validate、
            // 停掉运行时后在 import 半途才拒，留下新旧混合状态。
            var declaredTotal = 0L
            for (entry in names) {
                if (entry.isDirectory) continue
                val name = entry.name
                val normalized = safeRelative(name)
                if (normalized == null) {
                    AppLog.w("Backup", "拒绝越界备份条目：$name")
                    throw IllegalStateException("备份包含非法路径")
                }
                if (entry.size > MAX_FILE_BYTES) throw IllegalStateException("备份条目过大：$name")
                if (entry.size > 0) {
                    declaredTotal += entry.size
                    if (declaredTotal > MAX_TOTAL_BYTES) throw IllegalStateException("备份声明总量超过上限")
                }
                if (name.startsWith("prefs/") && name.endsWith(".json")) {
                    val pref = name.removePrefix("prefs/").removeSuffix(".json")
                    if (!PREFS_NAME.matches(pref)) {
                        AppLog.w("Backup", "拒绝非法设置名：$name")
                        throw IllegalStateException("备份包含非法设置名")
                    }
                }
                if (name == ENTRY_MANIFEST) {
                    if (entry.size > MAX_TEXT_BYTES) throw IllegalStateException("备份清单过大")
                    manifest = JSONObject(
                        String(
                            zip.getInputStream(entry).use { it.readLimited(MAX_TEXT_BYTES) },
                            Charsets.UTF_8,
                        ),
                    )
                }
            }
            val header = manifest ?: throw IllegalStateException("备份缺少 manifest.json")
            if (header.optString("app") != "coda" || header.optInt("schema", 0) !in 1..SCHEMA) {
                throw IllegalStateException("不是受支持的 Coda 备份")
            }
            val links = names.firstOrNull { it.name == "symlinks.txt" }
            if (links != null) {
                if (links.size > MAX_TEXT_BYTES) throw IllegalStateException("链接清单过大")
                val text = String(zip.getInputStream(links).use { it.readLimited(MAX_TEXT_BYTES) }, Charsets.UTF_8)
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) throw IllegalStateException("链接清单格式错误")
                    val destination =
                        destinationFor(ctx, parts[0])
                            ?: throw IllegalStateException("链接清单包含非法路径")
                    if (safeRelative(destination.second) == null) throw IllegalStateException("链接清单包含非法路径")
                    // Linux rootfs 里的系统链接可以是绝对路径（如 /bin/sh），不能一概拒绝；
                    // 相对链接若越出自己的恢复根目录则拒绝。绝对目标只允许系统路径。
                    val link = parts[1]
                    if (link.startsWith("/")) {
                        if (link.contains('\u0000') || link.split('/').contains("..")) {
                            throw IllegalStateException("链接目标非法")
                        }
                        // 绝对目标只对 Linux rootfs 有意义（proot 按 guest 根重解析，
                        // OCR #823）：files/、databases/、no-backup/ 下的绝对链接会
                        // 指向恢复根之外，后续文件操作或内核可能跟随它。
                        if (!parts[0].startsWith("files/linux/")) {
                            throw IllegalStateException("绝对链接目标只允许在 Linux rootfs 内")
                        }
                    } else {
                        val base = destination.first.canonicalFile
                        val resolved = File(File(base, destination.second).parentFile, link).canonicalFile
                        if (!inside(resolved, base)) throw IllegalStateException("链接目标越出恢复目录")
                    }
                }
            }
        }
    }

    /** 返回条目对应的恢复根目录与相对路径；无法识别时返回 null。 */
    private fun destinationFor(
        ctx: Context,
        name: String,
    ): Pair<File, String>? =
        when {
            name.startsWith("files/") -> ctx.filesDir to name.removePrefix("files/")
            name.startsWith("databases/") -> File(ctx.filesDir.parentFile, "databases") to name.removePrefix("databases/")
            name.startsWith("no-backup/") -> ctx.noBackupFilesDir to name.removePrefix("no-backup/")
            else -> null
        }

    private fun inside(
        target: File,
        base: File,
    ): Boolean = target == base || target.path.startsWith(base.path + File.separator)

    private fun safeRelative(name: String): String? {
        if (name.isBlank() || name.startsWith("/") || name.contains('\u0000')) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return name
    }

    /** 限长读取：ZipEntry.size 可能缺失或被伪造，必须按实际读到的字节数拒绝。 */
    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalStateException("备份文本条目过大")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun importPackage(
        ctx: Context,
        packageFile: File,
    ): Int {
        var count = 0
        var total = 0L
        // 包内**实际存在**的分类，而不是 manifest 声称的：部分包可能只勾了设置，
        // 也可能 manifest 与实际条目不一致（手工改包）。以实际条目为准，
        // 未包含的分类一律不动，绝不因为「读不到」就写空覆盖本机数据。
        val present = mutableSetOf<BackupCategory>()
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            entries.forEach { e ->
                if (!e.isDirectory) BackupCategory.of(e.name)?.let { present.add(it) }
            }
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!BackupCategory.shouldRestore(name, present)) continue
                val input = zip.getInputStream(entry)
                input.use { stream ->
                    when {
                        name.startsWith("files/") -> {
                            total += restoreEntry(ctx.filesDir, name.removePrefix("files/"), stream, MAX_FILE_BYTES)
                            count++
                        }

                        // 旧格式残留的 linux-coda/ 分支已删除：导出侧从未产生该前缀
                        // （linux rootfs 走 files/ 树），且其基准路径 root/.coda 与前缀语义不符。
                        name.startsWith("databases/") -> {
                            total +=
                                restoreEntry(
                                    File(ctx.filesDir.parentFile, "databases"),
                                    name.removePrefix("databases/"),
                                    stream,
                                    MAX_FILE_BYTES,
                                )
                            count++
                        }

                        name.startsWith("no-backup/") -> {
                            total += restoreEntry(ctx.noBackupFilesDir, name.removePrefix("no-backup/"), stream, MAX_FILE_BYTES)
                            count++
                        }

                        name == ENTRY_MANIFEST -> {
                            Unit
                        }

                        else -> {
                            val bytes = stream.readLimited(MAX_TEXT_BYTES)
                            total += bytes.size
                            when {
                                name.startsWith("prefs/") && name.endsWith(".json") -> {
                                    val pref = name.removePrefix("prefs/").removeSuffix(".json")
                                    applyPrefs(ctx, pref, JSONObject(String(bytes, Charsets.UTF_8)))
                                    count++
                                }

                                name == ENTRY_PROVIDERS -> {
                                    // 持 ProviderStore 写锁写盘（OCR r11）：事后 invalidate
                                    // 挡不住在途 load 把旧内容钉回缓存。
                                    ProviderStore.withWriteLock {
                                        ProviderStore.fileFor(ctx).writeText(String(bytes, Charsets.UTF_8))
                                        ProviderStore.invalidateReadCache()
                                    }
                                    count++
                                }

                                name == ENTRY_PROVIDER_META -> {
                                    // 持 ProviderStore 写锁写盘（OCR r11）：事后 invalidate
                                    // 挡不住在途 load 把旧内容钉回缓存。
                                    ProviderStore.withWriteLock {
                                        ProviderStore.metaFileFor(ctx).writeText(String(bytes, Charsets.UTF_8))
                                        ProviderStore.invalidateReadCache()
                                    }
                                    count++
                                }

                                name == ENTRY_ASR_PROVIDERS -> {
                                    // 持 ProviderStore 写锁写盘（OCR r11）：事后 invalidate
                                    // 挡不住在途 load 把旧内容钉回缓存。
                                    ProviderStore.withWriteLock {
                                        ProviderStore.asrFileFor(ctx).writeText(String(bytes, Charsets.UTF_8))
                                        ProviderStore.invalidateReadCache()
                                    }
                                    count++
                                }

                                name == "file-modes.txt" -> {
                                    applyModes(ctx, String(bytes, Charsets.UTF_8)).also { count++ }
                                }

                                name == "symlinks.txt" -> {
                                    restoreSymlinks(ctx, String(bytes, Charsets.UTF_8)).also { count++ }
                                }

                                name == ENTRY_GLOBAL_PROMPT -> {
                                    AgentPrompt.save(AgentPrompt.globalFile(ctx), String(bytes, Charsets.UTF_8)).also { count++ }
                                }

                                name == ENTRY_WORKSPACE_PROMPT -> {
                                    AgentPrompt.save(AgentPrompt.workspaceFile(ctx), String(bytes, Charsets.UTF_8)).also { count++ }
                                }
                            }
                        }
                    }
                }
                if (total > MAX_TOTAL_BYTES) throw IllegalStateException("备份解压总量超过上限")
            }
        }
        // 只有包里真的带了供应商配置才重载：部分包（比如只含提示词）不该触发
        // 供应商重载，更不该删掉 Linux 环境的 resolv.conf。
        if (BackupCategory.PROVIDERS in present) {
            // 上面是不持 writeLock 的直接 writeText（OCR r9 medium）：同 mtime
            // tick 内等长重写时磁盘签名不变，必须先作废读缓存再 load。
            ProviderStore.invalidateReadCache()
            runCatching { ProviderStore.load(ctx) }
        }
        if (BackupCategory.ROOTFS in present) {
            runCatching { File(LinuxEnv(ctx).rootfs, "etc/resolv.conf").delete() }
        }
        return count
    }

    /**
     * 判断路径本身是不是符号链接；File.isFile 会跟随链接，不能用来判断。
     *
     * Os 在 JVM 单测里不可用（stub 抛 RuntimeException），退回 NIO 的
     * NOFOLLOW_LINKS 读属性：语义一致，都是不跟随链接看自身。
     */
    private fun isSymlink(file: File): Boolean =
        runCatching {
            OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
        }.recoverCatching {
            java.nio.file.Files
                .isSymbolicLink(file.toPath())
        }.getOrDefault(false)

    /** 恢复可执行位：zip 不保存 Unix mode，Linux 环境里的 proot 与工具全靠这一步。 */
    private fun applyModes(
        ctx: Context,
        text: String,
    ) {
        val base = ctx.filesDir.canonicalFile
        text.lineSequence().filter { it.startsWith("files/") }.forEach { entry ->
            val target = File(ctx.filesDir, entry.removePrefix("files/")).canonicalFile
            if (target.path.startsWith(base.path + File.separator)) {
                runCatching { Os.chmod(target.absolutePath, 493) } // 0755
            }
        }
    }

    /** 重建符号链接。清单里的路径以 files/、databases/ 或 no-backup/ 开头。 */
    private fun restoreSymlinks(
        ctx: Context,
        text: String,
    ) {
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) throw IllegalStateException("链接清单格式错误")
            val entry = parts[0]
            val link = parts[1]
            val resolved = destinationFor(ctx, entry) ?: throw IllegalStateException("链接清单包含非法路径")
            val base = resolved.first.canonicalFile
            val rel = resolved.second
            if (safeRelative(rel) == null) throw IllegalStateException("链接清单包含非法路径")
            val raw = File(base, rel)
            val parent = raw.parentFile?.canonicalFile ?: throw IllegalStateException("链接父目录非法")
            // 直接子项的 parent 正好等于 base，也必须接受；旧代码会把它们全部静默跳过。
            if (!inside(parent, base)) throw IllegalStateException("链接路径越界")
            val linkTarget = File(parent, link).canonicalFile
            if (!link.startsWith("/") && !inside(linkTarget, base)) throw IllegalStateException("链接目标越界")
            if (link.startsWith("/") && (link.contains('\u0000') || link.split('/').contains(".."))) {
                throw IllegalStateException("链接目标非法")
            }
            // 与 validatePackage 同口径（OCR #823）：绝对目标只允许 rootfs 子树。
            if (link.startsWith("/") && !entry.startsWith("files/linux/")) {
                throw IllegalStateException("绝对链接目标只允许在 Linux rootfs 内")
            }
            raw.parentFile?.mkdirs()
            if (raw.exists() || isSymlink(raw)) {
                if (!raw.delete()) throw IllegalStateException("无法替换已有链接：$entry")
            }
            Os.symlink(link, raw.absolutePath)
        }
    }

    /**
     * 把 zip 当前条目流式写到 [base] 下的 [rel]。
     *
     * 链接检测必须针对未 canonical 化的原始路径；否则会先展开链接再写，直接覆盖链接目标。
     */
    internal fun restoreEntry(
        base: File,
        rel: String,
        input: InputStream,
        limit: Long,
    ): Long {
        if (safeRelative(rel) == null) throw IllegalStateException("备份条目路径非法")
        val root = base.canonicalFile
        // raw 必须从 canonical 根拼（#803）：base 是 /data/user/0/…，
        // root 解析成 /data/data/…，用 base 拼出来的父链永远走不到 root，
        // 循环一路走到 /data/user/0（本身是符号链接 → 抛错）或 /（越界），
        // 常规设备上所有备份条目全部失败。根以下的组件保持未规范化，
        // 符号链接检查才能逐层看到真实路径。
        val raw = File(root, rel)
        // 父目录也要逐层确认，避免某一级已有符号链接把写入带离恢复根。
        var cursor = raw.parentFile
        while (cursor != null && cursor != root) {
            if (isSymlink(cursor)) throw IllegalStateException("备份父目录是符号链接")
            cursor = cursor.parentFile
        }
        if (cursor != root) throw IllegalStateException("备份条目越界")
        raw.parentFile?.mkdirs()
        if (isSymlink(raw) && !raw.delete()) throw IllegalStateException("无法替换符号链接")
        var total = 0L
        FileOutputStream(raw).use { out ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalStateException("备份文件过大")
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    /** 当前可备份内容的说明，用于设置页展示。 */
    fun describe(ctx: Context): String {
        var keys = 0
        PREFS.forEach { keys += ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all.size }
        val providers = ProviderStore.load(ctx).count { it.origin != ProviderStore.ProviderOrigin.BUILTIN }
        val prompts =
            listOf(AgentPrompt.globalFile(ctx), AgentPrompt.workspaceFile(ctx))
                .count { it.isFile }
        return "设置 $keys 项 · 供应商 $providers 个 · 提示词 $prompts 份"
    }

    /**
     * 各分类的体积估算（字节），用于勾选界面显示「这一项有多大」。
     *
     * 只统计常规文件，符号链接按 0 算（它在包里只是一行清单）。
     * 遍历在调用线程同步执行，rootfs 大时较慢，调用方必须放后台。
     */
    fun categorySizes(ctx: Context): Map<BackupCategory, Long> {
        val out = mutableMapOf<BackupCategory, Long>()

        fun add(
            cat: BackupCategory,
            bytes: Long,
        ) {
            out[cat] = (out[cat] ?: 0L) + bytes
        }

        fun treeSize(
            entry: String,
            file: File,
        ) {
            if (isSymlink(file)) return
            if (file.isFile) {
                BackupCategory.of(entry)?.let { add(it, file.length()) }
                return
            }
            file.listFiles()?.forEach { child -> treeSize("$entry/${child.name}", child) }
        }

        // prefs 以 JSON 形式导出，体积与 xml 原文件不同量级但同一数量级，用原文件估算。
        File(ctx.filesDir.parentFile, "shared_prefs")
            .listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.forEach { add(BackupCategory.PREFS, it.length()) }
        listOf(
            ProviderStore.fileFor(ctx),
            ProviderStore.metaFileFor(ctx),
            ProviderStore.asrFileFor(ctx),
        ).forEach { if (it.isFile) add(BackupCategory.PROVIDERS, it.length()) }
        listOf(AgentPrompt.globalFile(ctx), AgentPrompt.workspaceFile(ctx))
            .forEach { if (it.isFile) add(BackupCategory.PROMPTS, it.length()) }
        ctx.filesDir.listFiles()?.forEach { top ->
            if (top.name !in SKIP_FILES_TOP) treeSize("files/${top.name}", top)
        }
        File(ctx.filesDir.parentFile, "databases").takeIf { it.exists() }?.let { treeSize("databases", it) }
        ctx.noBackupFilesDir.takeIf { it.exists() }?.let { treeSize("no-backup", it) }
        return BackupCategory.entries.associateWith { out[it] ?: 0L }
    }

    /**
     * 读一个包实际包含哪些分类，用于导入确认界面展示「会覆盖什么」。
     *
     * 以实际条目为准而不是 manifest 声称的：手工改过的包两者可能不一致。
     * 读不出时返回空集，调用方应据此拒绝导入而不是当成全量包。
     */
    fun categoriesIn(
        ctx: Context,
        uri: Uri,
    ): Set<BackupCategory> =
        runCatching {
            withPackage(ctx, uri) { file ->
                val present = mutableSetOf<BackupCategory>()
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().forEach { e ->
                        if (!e.isDirectory) BackupCategory.of(e.name)?.let { present.add(it) }
                    }
                }
                present
            }
        }.getOrDefault(emptySet())

    /**
     * 流式读分类（OCR #820）：确认页只需要条目名，不必把几百 MB 的包
     * 先落到临时文件再删掉、确认后又落一遍。ZipInputStream 直接过
     * contentResolver 的流；条目数设闸门，畸形包不能无限枚举。
     */
    fun categoriesInStreaming(
        ctx: Context,
        uri: Uri,
    ): Set<BackupCategory> =
        runCatching {
            val present = mutableSetOf<BackupCategory>()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zin ->
                    var count = 0
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (++count > 200_000) break
                        if (!e.isDirectory) BackupCategory.of(e.name)?.let { present.add(it) }
                    }
                }
            }
            present
        }.getOrDefault(emptySet())

    /** 人类可读的字节数，用于勾选界面。 */
    fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

    private fun encode(value: Any?): JSONObject =
        when (value) {
            is String -> JSONObject().put("t", "s").put("v", value)
            is Boolean -> JSONObject().put("t", "b").put("v", value)
            is Int -> JSONObject().put("t", "i").put("v", value)
            is Long -> JSONObject().put("t", "l").put("v", value)
            is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
            is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
            else -> JSONObject().put("t", "s").put("v", value?.toString() ?: "")
        }

    /** 覆盖写入一份 SharedPreferences：先清空，避免旧键残留出两份设置混在一起的配置。 */
    private fun applyPrefs(
        ctx: Context,
        name: String,
        obj: JSONObject,
    ) {
        val editor = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        obj.keys().forEach { key ->
            val item = obj.optJSONObject(key) ?: return@forEach
            when (item.optString("t")) {
                "s" -> {
                    editor.putString(key, item.optString("v"))
                }

                "b" -> {
                    editor.putBoolean(key, item.optBoolean("v"))
                }

                "i" -> {
                    editor.putInt(key, item.optInt("v"))
                }

                "l" -> {
                    editor.putLong(key, item.optLong("v"))
                }

                "f" -> {
                    editor.putFloat(key, item.optDouble("v").toFloat())
                }

                "set" -> {
                    val arr = item.optJSONArray("v") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    editor.putStringSet(key, set)
                }
            }
        }
        // 立即落盘：紧接着要重启核心，核心启动时就要读到新配置。
        // commit 返回 false 表示真实写盘失败，必须中止导入，不能显示导入成功。
        if (!editor.commit()) throw IllegalStateException("写入设置失败：$name")
    }

    /** 备份文件名，带时间戳。 */
    fun defaultFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        return "coda-backup-$ts.zip"
    }

    /** 备份是否包含指定条目（导入前做一次格式校验，避免拿错文件静默覆盖）。 */
    fun looksLikeBackup(
        ctx: Context,
        uri: Uri,
    ): Boolean =
        try {
            var ok = false
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == ENTRY_MANIFEST) {
                            val text = String(zip.readLimited(MAX_TEXT_BYTES), Charsets.UTF_8)
                            val manifest = runCatching { JSONObject(text) }.getOrNull()
                            ok = manifest?.optString("app") == "coda" && manifest.optInt("schema", 0) in 1..SCHEMA
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            ok
        } catch (_: Throwable) {
            false
        }
}
