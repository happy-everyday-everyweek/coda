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
    /** 备份格式版本。 */
    private const val SCHEMA = 2

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

    /** 导出到给定 Uri，返回写入的条目数；失败抛出异常。 */
    fun export(
        ctx: Context,
        uri: Uri,
    ): Int {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法写入目标文件")
        var count = 0
        ZipOutputStream(out).use { zip ->
            fun putText(
                name: String,
                text: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                count++
            }

            val execEntries = mutableListOf<String>()

            fun putFile(
                name: String,
                file: File,
            ) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
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
            if (execEntries.isNotEmpty()) putText("file-modes.txt", execEntries.joinToString("\n"))
            if (symlinks.isNotEmpty()) {
                putText("symlinks.txt", symlinks.joinToString("\n") { "${it.first}\t${it.second}" })
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
            putText(ENTRY_MANIFEST, manifest.toString(2))
        }
        return count
    }

    /** 从给定 Uri 恢复，返回恢复的条目数；失败抛出异常。 */
    fun import(
        ctx: Context,
        uri: Uri,
    ): Int {
        // 先把外部输入落到私有临时文件：manifest 可能在包尾，不能在解压流里读到一半才开始写。
        val packageFile = File(ctx.cacheDir, "backup-import-${System.nanoTime()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(packageFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取备份文件")
            validatePackage(ctx, packageFile)
            return importPackage(ctx, packageFile)
        } finally {
            packageFile.delete()
        }
    }

    /** 预检整个包；所有检查都在覆盖用户数据之前完成。 */
    private fun validatePackage(
        ctx: Context,
        file: File,
    ) {
        var manifest: JSONObject? = null
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().toList()
            if (names.size > MAX_ENTRIES) throw IllegalStateException("备份条目过多")
            for (entry in names) {
                if (entry.isDirectory) continue
                val name = entry.name
                val normalized = safeRelative(name)
                if (normalized == null) {
                    AppLog.w("Backup", "拒绝越界备份条目：$name")
                    throw IllegalStateException("备份包含非法路径")
                }
                if (entry.size > MAX_FILE_BYTES) throw IllegalStateException("备份条目过大：$name")
                if (name.startsWith("prefs/") && name.endsWith(".json")) {
                    val pref = name.removePrefix("prefs/").removeSuffix(".json")
                    if (!PREFS_NAME.matches(pref)) {
                        AppLog.w("Backup", "拒绝非法设置名：$name")
                        throw IllegalStateException("备份包含非法设置名")
                    }
                }
                if (name == ENTRY_MANIFEST) {
                    if (entry.size > MAX_TEXT_BYTES) throw IllegalStateException("备份清单过大")
                    manifest = JSONObject(String(zip.getInputStream(entry).readLimited(MAX_TEXT_BYTES), Charsets.UTF_8))
                }
            }
            val header = manifest ?: throw IllegalStateException("备份缺少 manifest.json")
            if (header.optString("app") != "coda" || header.optInt("schema", 0) !in 1..SCHEMA) {
                throw IllegalStateException("不是受支持的 Coda 备份")
            }
            val links = names.firstOrNull { it.name == "symlinks.txt" }
            if (links != null) {
                if (links.size > MAX_TEXT_BYTES) throw IllegalStateException("链接清单过大")
                val text = String(zip.getInputStream(links).readLimited(MAX_TEXT_BYTES), Charsets.UTF_8)
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) throw IllegalStateException("链接清单格式错误")
                    val destination = destinationFor(ctx, parts[0])
                        ?: throw IllegalStateException("链接清单包含非法路径")
                    if (safeRelative(destination.second) == null) throw IllegalStateException("链接清单包含非法路径")
                    // Linux rootfs 里的系统链接可以是绝对路径（如 /bin/sh），不能一概拒绝；
                    // 相对链接若越出自己的恢复根目录则拒绝。绝对目标只允许系统路径。
                    val link = parts[1]
                    if (link.startsWith("/")) {
                        if (link.contains('\u0000') || link.split('/').contains("..")) {
                            throw IllegalStateException("链接目标非法")
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

    private fun inside(target: File, base: File): Boolean =
        target == base || target.path.startsWith(base.path + File.separator)

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
        ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                val name = entry.name
                val input = zip.getInputStream(entry)
                input.use { stream ->
                    when {
                        name.startsWith("files/") -> {
                            total += restoreEntry(ctx.filesDir, name.removePrefix("files/"), stream, MAX_FILE_BYTES)
                            count++
                        }
                        name.startsWith("linux-coda/") -> {
                            total += restoreEntry(File(LinuxEnv(ctx).rootfs, "root/.coda"), name.removePrefix("linux-coda/"), stream, MAX_FILE_BYTES)
                            count++
                        }
                        name.startsWith("databases/") -> {
                            total += restoreEntry(File(ctx.filesDir.parentFile, "databases"), name.removePrefix("databases/"), stream, MAX_FILE_BYTES)
                            count++
                        }
                        name.startsWith("no-backup/") -> {
                            total += restoreEntry(ctx.noBackupFilesDir, name.removePrefix("no-backup/"), stream, MAX_FILE_BYTES)
                            count++
                        }
                        name == ENTRY_MANIFEST -> Unit
                        else -> {
                            val bytes = stream.readLimited(MAX_TEXT_BYTES)
                            total += bytes.size
                            when {
                                name.startsWith("prefs/") && name.endsWith(".json") -> {
                                    val pref = name.removePrefix("prefs/").removeSuffix(".json")
                                    applyPrefs(ctx, pref, JSONObject(String(bytes, Charsets.UTF_8)))
                                    count++
                                }
                                name == ENTRY_PROVIDERS -> ProviderStore.fileFor(ctx).writeText(String(bytes, Charsets.UTF_8)).also { count++ }
                                name == ENTRY_PROVIDER_META -> ProviderStore.metaFileFor(ctx).writeText(String(bytes, Charsets.UTF_8)).also { count++ }
                                name == ENTRY_ASR_PROVIDERS -> ProviderStore.asrFileFor(ctx).writeText(String(bytes, Charsets.UTF_8)).also { count++ }
                                name == "file-modes.txt" -> applyModes(ctx, String(bytes, Charsets.UTF_8)).also { count++ }
                                name == "symlinks.txt" -> restoreSymlinks(ctx, String(bytes, Charsets.UTF_8)).also { count++ }
                                name == ENTRY_GLOBAL_PROMPT -> AgentPrompt.save(AgentPrompt.globalFile(ctx), String(bytes, Charsets.UTF_8)).also { count++ }
                                name == ENTRY_WORKSPACE_PROMPT -> AgentPrompt.save(AgentPrompt.workspaceFile(ctx), String(bytes, Charsets.UTF_8)).also { count++ }
                            }
                        }
                    }
                }
                if (total > MAX_TOTAL_BYTES) throw IllegalStateException("备份解压总量超过上限")
            }
        }
        runCatching { ProviderStore.load(ctx) }
        runCatching { File(LinuxEnv(ctx).rootfs, "etc/resolv.conf").delete() }
        return count
    }

    /** 判断路径本身是不是符号链接；File.isFile 会跟随链接，不能用来判断。 */
    private fun isSymlink(file: File): Boolean =
        runCatching {
            OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
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
    private fun restoreEntry(
        base: File,
        rel: String,
        input: InputStream,
        limit: Long,
    ): Long {
        if (safeRelative(rel) == null) throw IllegalStateException("备份条目路径非法")
        val root = base.canonicalFile
        val raw = File(base, rel)
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
                "s" -> editor.putString(key, item.optString("v"))
                "b" -> editor.putBoolean(key, item.optBoolean("v"))
                "i" -> editor.putInt(key, item.optInt("v"))
                "l" -> editor.putLong(key, item.optLong("v"))
                "f" -> editor.putFloat(key, item.optDouble("v").toFloat())
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
