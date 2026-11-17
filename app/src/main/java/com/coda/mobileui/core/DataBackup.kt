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
        val raw = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取备份文件")
        var count = 0
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    // 目录条目走流式恢复，避免把工作区里的大文件整个读进内存。
                    name.startsWith("files/") -> {
                        restoreEntry(File(ctx.filesDir, ""), name.removePrefix("files/"), zip)
                        count++
                    }

                    name.startsWith("linux-coda/") -> {
                        restoreEntry(File(LinuxEnv(ctx).rootfs, "root/.coda"), name.removePrefix("linux-coda/"), zip)
                        count++
                    }

                    name.startsWith("databases/") -> {
                        restoreEntry(File(ctx.filesDir.parentFile, "databases"), name.removePrefix("databases/"), zip)
                        count++
                    }

                    name.startsWith("no-backup/") -> {
                        restoreEntry(ctx.noBackupFilesDir, name.removePrefix("no-backup/"), zip)
                        count++
                    }

                    else -> {
                        val bytes = zip.readBytes()
                        when {
                            name.startsWith("prefs/") && name.endsWith(".json") -> {
                                applyPrefs(
                                    ctx,
                                    name.removePrefix("prefs/").removeSuffix(".json"),
                                    JSONObject(String(bytes, Charsets.UTF_8)),
                                )
                                count++
                            }

                            name == ENTRY_PROVIDERS -> {
                                val f = ProviderStore.fileFor(ctx)
                                f.writeText(String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == ENTRY_PROVIDER_META -> {
                                ProviderStore.metaFileFor(ctx).writeText(String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == "file-modes.txt" -> {
                                applyModes(ctx, String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == "symlinks.txt" -> {
                                restoreSymlinks(ctx, String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == ENTRY_ASR_PROVIDERS -> {
                                ProviderStore.asrFileFor(ctx).writeText(String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == ENTRY_GLOBAL_PROMPT -> {
                                AgentPrompt.save(AgentPrompt.globalFile(ctx), String(bytes, Charsets.UTF_8))
                                count++
                            }

                            name == ENTRY_WORKSPACE_PROMPT -> {
                                AgentPrompt.save(AgentPrompt.workspaceFile(ctx), String(bytes, Charsets.UTF_8))
                                count++
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // 恢复后让 ProviderStore 重新读一次：旧备份可能带 coda 字段或非法类型，
        // 这一读会把它们就地迁移成内核可接受的新格式。
        runCatching { ProviderStore.load(ctx) }
        // DNS 属于当前网络，不该跟着备份跨设备复制；删掉后 LinuxEnv 会按当前网络重写。
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
        text.lineSequence().filter { it.contains('\t') }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@forEach
            val entry = parts[0]
            val link = parts[1]
            val resolved =
                when {
                    entry.startsWith("files/") -> ctx.filesDir to entry.removePrefix("files/")
                    entry.startsWith("databases/") -> File(ctx.filesDir.parentFile, "databases") to entry.removePrefix("databases/")
                    entry.startsWith("no-backup/") -> ctx.noBackupFilesDir to entry.removePrefix("no-backup/")
                    else -> return@forEach
                }
            val base = resolved.first
            val rel = resolved.second
            if (rel.isBlank()) return@forEach
            val baseCanonical = base.canonicalFile
            val parent = File(base, rel).parentFile?.canonicalFile ?: return@forEach
            if (!parent.path.startsWith(baseCanonical.path + File.separator)) return@forEach
            val target = File(parent, File(rel).name)
            target.parentFile?.mkdirs()
            runCatching {
                if (target.exists() || isSymlink(target)) target.delete()
                Os.symlink(link, target.absolutePath)
            }
        }
    }

    /**
     * 把 zip 当前条目流式写到 [base] 下的 [rel]。
     *
     * 必须做路径校验：zip 里的相对路径可以带 `..`，直接拼会写到应用私有目录之外。
     */
    private fun restoreEntry(
        base: File,
        rel: String,
        input: InputStream,
    ) {
        if (rel.isBlank()) return
        val root = base.canonicalFile
        val target = File(base, rel).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) return
        target.parentFile?.mkdirs()
        // 目标若是旧链接，直接 FileOutputStream 会顺着链接写到别处；先删链接。
        if (isSymlink(target)) target.delete()
        FileOutputStream(target).use { out -> input.copyTo(out) }
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
        editor.commit()
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
                            ok = true
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
