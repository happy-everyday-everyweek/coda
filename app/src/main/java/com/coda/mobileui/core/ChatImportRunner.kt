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
                "导入未开始：${reject.reason}"
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
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    val manifestEntry = zip.getEntry("manifest.json") ?: return@use Source.UNKNOWN
                    val manifest =
                        runCatching {
                            JSONObject(zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8))
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
                        runCatching { JSONObject(zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8)) }
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
                    picked.forEachIndexed { i, meta ->
                        if (isCancelled()) {
                            reasons.add("已取消，剩余 ${picked.size - i} 个未导入")
                            return@forEachIndexed
                        }
                        onProgress(i, picked.size)
                        val id = meta.optString("id")
                        val title = DeepSeekImport.trimTitle(meta.optString("title"))
                        val entry =
                            zip.getEntry("session_$id.json") ?: zip.getEntry("$id.json")
                        if (entry == null) {
                            skipped++
                            reasons.add("$title：包内找不到会话文件")
                            return@forEachIndexed
                        }
                        val bytes =
                            runCatching {
                                zip.getInputStream(entry).use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    val out = java.io.ByteArrayOutputStream()
                                    var read = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        read += n
                                        if (read > DeepSeekImport.MAX_ENTRY_BYTES) {
                                            throw IllegalStateException("会话条目超过上限")
                                        }
                                        out.write(buf, 0, n)
                                    }
                                    out.toByteArray()
                                }
                            }.getOrNull()
                        if (bytes == null) {
                            skipped++
                            reasons.add("$title：会话文件过大或读取失败")
                            return@forEachIndexed
                        }
                        totalBytes += bytes.size
                        if (totalBytes > DeepSeekImport.MAX_TOTAL_BYTES) {
                            reasons.add("已达整包解压上限，剩余会话未导入")
                            return@forEachIndexed
                        }
                        val session =
                            runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        if (session == null) {
                            skipped++
                            reasons.add("$title：会话 JSON 无法解析")
                            return@forEachIndexed
                        }
                        val messages = messageListOf(session)
                        val current =
                            session.optInt("current_message_id", -1).takeIf { it >= 0 }
                        val chain = DeepSeekImport.branchChain(messages, current)
                        val converted = DeepSeekImport.toImportMessages(chain)
                        if (converted.isEmpty()) {
                            skipped++
                            reasons.add("$title：没有可导入的消息")
                            return@forEachIndexed
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
                    onProgress(picked.size, picked.size)
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

    private fun readIndex(zip: ZipFile): JSONArray? {
        val entry = zip.getEntry("chat_session_index.json") ?: return null
        return runCatching {
            val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            // 索引可能直接是数组，也可能包在 {sessions:[...]} 里。
            if (text.trimStart().startsWith("[")) {
                JSONArray(text)
            } else {
                val o = JSONObject(text)
                o.optJSONArray("sessions") ?: o.optJSONArray("chat_sessions") ?: JSONArray()
            }
        }.getOrNull()
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
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法读取所选文件")
            return block(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private const val TAG = "ChatImport"
}