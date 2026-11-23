package com.coda.mobileui.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 第三方聊天记录导入的执行器（#733）。
 *
 * 解析与转换的纯逻辑在 [DeepSeekImport]，这里负责读 zip、逐会话转换、
 * 经内核 `session/create` 的 importedHistory 落地，并汇总结果。
 *
 * ## 为什么不写 SessionCache
 *
 * SessionCache 是只读离线缓存，有 30 会话 / 5 会话消息 / 200 条上限且会被 prune 淘汰。
 * 写进去的会话不会出现在真实会话列表里，也无法继续对话。所以必须走内核建会话。
 *
 * ## 流式与上限
 *
 * 先把 Uri 落到私有临时文件（manifest 可能在包尾，流式读一遍拿不到），
 * 然后只解压选中的会话条目，不整包载入内存。单条目与总量都按实际读到的字节数判，
 * 不信任 ZipEntry 自报的大小。
 */
internal object ChatImportRunner {
    /** 一次导入的结果报告。 */
    data class Report(
        val imported: Int,
        val skipped: Int,
        val reasons: List<String>,
        val reject: DeepSeekImport.Reject? = null,
    ) {
        val ok: Boolean get() = reject == null && imported > 0

        fun summary(): String =
            if (reject != null) {
                // 半途失败不能报「未开始」（OCR #822）：已导入的会话要如实计数。
                if (imported > 0 || skipped > 0) {
                    "已导入 $imported 个会话，跳过 $skipped 个，随后中断：${reject.reason}"
                } else {
                    "导入未开始：${reject.reason}"
                }
            } else {
                buildString {
                    append("已导入 $imported 个会话")
                    if (skipped > 0) append("，跳过 $skipped 个")
                    if (reasons.isNotEmpty()) append("\n").append(reasons.take(5).joinToString("\n"))
                    if (reasons.size > 5) append("\n…另有 ${reasons.size - 5} 条")
                }
            }
    }

    /** 来源识别结果。 */
    enum class Source {
        CODA_BACKUP,
        DEEPSEEK,
        UNKNOWN,
    }

    /**
     * 识别包的来源。
     *
     * 只读 manifest 与条目名，不解压任何会话内容。读不出时返回 UNKNOWN，
     * 调用方维持现有报错，不改动任何数据。
     */
    fun detect(
        ctx: Context,
        uri: Uri,
    ): Source =
        runCatching {
            withTempPackage(ctx, uri) { file ->
                ZipFile(file).use { zip ->
                    // detect 也要过条目闸门（OCR #822）：百万级中央目录先物化会 OOM。
                    if (zip.size() > DeepSeekImport.MAX_ENTRIES) return@use Source.UNKNOWN
                    val names =
                        zip
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .toList()
                    val manifestEntry = zip.getEntry("manifest.json") ?: return@use Source.UNKNOWN
                    val manifest =
                        runCatching {
                            JSONObject(zip.readMetaLimited(manifestEntry) ?: return@use Source.UNKNOWN)
                        }.getOrNull() ?: return@use Source.UNKNOWN
                    when {
                        manifest.optString("app") == "coda" -> Source.CODA_BACKUP
                        DeepSeekImport.looksLikeDeepSeek(names, manifest) -> Source.DEEPSEEK
                        else -> Source.UNKNOWN
                    }
                }
            }
        }.getOrDefault(Source.UNKNOWN)

    /** 包里有多少个会话（供导入前确认展示）。 */
    fun countSessions(
        ctx: Context,
        uri: Uri,
        limit: Int = DeepSeekImport.DEFAULT_SESSION_LIMIT,
    ): Int =
        runCatching {
            withTempPackage(ctx, uri) { file ->
                ZipFile(file).use { zip ->
                    if (zip.size() > DeepSeekImport.MAX_ENTRIES) return@use 0
                    val index = readIndex(zip) ?: return@use 0
                    DeepSeekImport.pickRecent(index, limit).size
                }
            }
        }.getOrDefault(0)

    /**
     * 执行导入。
     *
     * [createSession] 由调用方提供「用这份 importedHistory 建一个会话」的同步实现，
     * 这样本类不依赖 ZController 的异步回调形态，也能在单测里替换成假实现。
     * 单个会话失败只计数并继续，不中断整批。
     */
    fun importDeepSeek(
        ctx: Context,
        uri: Uri,
        limit: Int = DeepSeekImport.DEFAULT_SESSION_LIMIT,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        createSession: (JSONObject) -> Boolean,
    ): Report {
        val reasons = mutableListOf<String>()
        var imported = 0
        var skipped = 0
        return runCatching {
            withTempPackage(ctx, uri) { file ->
                if (file.length() > DeepSeekImport.MAX_TOTAL_BYTES) {
                    return@withTempPackage Report(0, 0, emptyList(), DeepSeekImport.Reject.TOO_LARGE)
                }
                ZipFile(file).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    if (entries.size > DeepSeekImport.MAX_ENTRIES) {
                        return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.TOO_LARGE)
                    }
                    val manifestEntry =
                        zip.getEntry("manifest.json")
                            ?: return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.MISSING_MANIFEST)
                    val manifest =
                        runCatching { JSONObject(zip.readMetaLimited(manifestEntry) ?: return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.UNREADABLE_ARCHIVE)) }
                            .getOrNull()
                            ?: return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.UNREADABLE_ARCHIVE)
                    if (!DeepSeekImport.supportedManifest(manifest)) {
                        return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.UNSUPPORTED_VERSION)
                    }
                    val index =
                        readIndex(zip) ?: return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.MISSING_INDEX)
                    val picked = DeepSeekImport.pickRecent(index, limit)
                    if (picked.isEmpty()) {
                        return@use Report(0, 0, emptyList(), DeepSeekImport.Reject.NO_CONVERSATIONS)
                    }
                    var totalBytes = 0L
                    var done = 0
                    // 带标签的 for：取消与总量超限必须 break 整个循环。
                    // forEachIndexed 的 return 只跳过当前元素，剩余会话会继续
                    // 解压、继续追加重复的「已取消」理由，最后还报完成（#810）。
                    loop@ for (i in picked.indices) {
                        val meta = picked[i]
                        if (isCancelled()) {
                            reasons.add("已取消，剩余 ${picked.size - i} 个未导入")
                            break@loop
                        }
                        onProgress(i, picked.size)
                        done = i + 1
                        val id = meta.optString("id")
                        val title = DeepSeekImport.trimTitle(meta.optString("title"))
                        val entry =
                            zip.getEntry("session_$id.json") ?: zip.getEntry("$id.json")
                        if (entry == null) {
                            skipped++
                            reasons.add("$title：包内找不到会话文件")
                            continue@loop
                        }
                        val bytes =
                            runCatching {
                                zip.getInputStream(entry).use { input ->
                                    readBounded(input, DeepSeekImport.MAX_HEAP_ENTRY_BYTES)
                                }
                            }.getOrNull()
                        if (bytes == null) {
                            skipped++
                            reasons.add("$title：会话文件过大或读取失败")
                            continue@loop
                        }
                        totalBytes += bytes.size
                        if (totalBytes > DeepSeekImport.MAX_TOTAL_BYTES) {
                            reasons.add("已达整包解压上限，剩余会话未导入")
                            break@loop
                        }
                        val session =
                            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        if (session == null) {
                            skipped++
                            reasons.add("$title：会话 JSON 无法解析")
                            continue@loop
                        }
                        val messages = messageListOf(session)
                        val current =
                            session.optInt("current_message_id", -1).takeIf { it >= 0 }
                        val chain = DeepSeekImport.branchChain(messages, current)
                        val converted = DeepSeekImport.toImportMessages(chain)
                        if (converted.isEmpty()) {
                            skipped++
                            reasons.add("$title：没有可导入的消息")
                            continue@loop
                        }
                        val history =
                            DeepSeekImport.importedHistory(
                                title = title,
                                createdAt = meta.optLong("inserted_at", 0L).takeIf { it > 0 },
                                updatedAt = meta.optLong("updated_at", 0L).takeIf { it > 0 },
                                messages = converted,
                            )
                        if (runCatching { createSession(history) }.getOrDefault(false)) {
                            imported++
                        } else {
                            skipped++
                            reasons.add("$title：内核建会话失败")
                        }
                    }
                    // 取消/超限时不再报「全部完成」：进度停在真正处理到的位置。
                    onProgress(done, picked.size)
                    Report(imported, skipped, reasons)
                }
            }
        }.getOrElse { e ->
            AppLog.w(TAG, "DeepSeek 导入失败：${e.javaClass.simpleName}: ${e.message}")
            Report(imported, skipped, reasons + (e.message ?: "未知错误"), DeepSeekImport.Reject.UNREADABLE_ARCHIVE)
        }
    }

    /** 会话文件里的消息数组：兼容 messages 与 mapping 两种键名。 */
    private fun messageListOf(session: JSONObject): List<JSONObject> {
        val arr = session.optJSONArray("messages") ?: session.optJSONArray("mapping") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 元数据条目限长读（OCR #822）：高压缩比索引可膨胀到数百 MB 堆。 */
    private const val MAX_META_BYTES = 8 * 1024 * 1024
    private fun ZipFile.readMetaLimited(entry: java.util.zip.ZipEntry): String? =
        runCatching {
            getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_META_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()

    private fun readIndex(zip: ZipFile): JSONArray? {
        val entry = zip.getEntry("chat_session_index.json") ?: return null
        return runCatching {
            val text = zip.readMetaLimited(entry) ?: return null
            // 索引可能直接是数组，也可能包在 {sessions:[...]} 里。
            if (text.trimStart().startsWith("[")) {
                JSONArray(text)
            } else {
                val o = JSONObject(text)
                o.optJSONArray("sessions") ?: o.optJSONArray("chat_sessions") ?: JSONArray()
            }
        }.getOrNull()
    }

    /**
     * 有界读入堆（#812）：超过 [limit] 立即抛错，不再继续分配。
     * ByteArrayOutputStream 翻倍扩容，无界读会在远未到磁盘预算时就 OOM。
     */
    internal fun readBounded(
        input: java.io.InputStream,
        limit: Long,
    ): ByteArray {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var read = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            read += n
            if (read > limit) throw IllegalStateException("条目超过上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 有界拷贝到临时文件（#811）：边拷边判，超过 [limit] 立即抛错并删除半成品。
     * 旧实现 input.copyTo 无上限，512MB 检查在拷贝之后，多 GB 文件会先填满
     * 数据分区；detect/countSessions 甚至完全不查大小。
     */
    internal fun copyBounded(
        input: java.io.InputStream,
        tmp: File,
        limit: Long,
    ) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        FileOutputStream(tmp).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    out.close()
                    runCatching { tmp.delete() }
                    throw IllegalStateException("所选文件超过导入上限")
                }
                out.write(buf, 0, n)
            }
        }
    }

    /** 把 Uri 落到私有临时文件后交给 [block]，结束时一定删掉。 */
    private fun <T> withTempPackage(
        ctx: Context,
        uri: Uri,
        block: (File) -> T,
    ): T {
        val tmp = File(ctx.cacheDir, "chat-import-${System.nanoTime()}.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                copyBounded(input, tmp, DeepSeekImport.MAX_TOTAL_BYTES)
            } ?: throw IllegalStateException("无法读取所选文件")
            return block(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private const val TAG = "ChatImport"
}
