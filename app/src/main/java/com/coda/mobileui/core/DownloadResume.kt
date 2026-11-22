package com.coda.mobileui.core

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Range 能力探测与响应判定（#730）。
 *
 * 服务端「支持续传」这件事不能靠猜：有的镜像接受 Range 头却回 200 全量，
 * 有的代理回 206 但起点根本不是请求的位置。照着拼会拼出损坏文件，
 * 而且最终 sha256 失败会被误记成镜像内容被改写。
 */
internal object ResumeProbe {
    enum class Kind {
        /** 206 且区间起点吻合：可以从断点接着写。 */
        RESUMED,

        /** 请求了 Range 却回 200：这个源不支持，全量重下。 */
        NOT_SUPPORTED,

        /** 206 但 Content-Range 缺失或起点不符：不可信，全量重下。 */
        UNTRUSTED,

        /** 416：断点已超出远端范围（远端变小了），作废重下。 */
        INVALID_RANGE,

        /** 其它非 2xx：交给 DownloadOutcome.ofHttp 分类，这里不下结论。 */
        HTTP_FAILURE,
    }

    /** `Accept-Ranges: bytes` 才算支持；`none` 与缺失都按不支持处理。 */
    fun acceptsRanges(header: String?): Boolean = header?.trim()?.equals("bytes", ignoreCase = true) == true

    /** 解析出的区间：[total] 为 0 表示服务端用 `*` 表示总长未知。 */
    data class Range(
        val from: Long,
        val total: Long,
    )

    /**
     * 解析 `Content-Range: bytes <from>-<to>/<total|*>`。
     *
     * 任何不符格式的内容都返回 null，不做「尽力猜」——猜错的代价是拼出坏文件。
     */
    fun parseContentRange(header: String?): Range? {
        val s = header?.trim() ?: return null
        if (!s.startsWith("bytes ", ignoreCase = true)) return null
        val body = s.substring(6).trim()
        val slash = body.indexOf('/')
        if (slash <= 0) return null
        val span = body.substring(0, slash)
        val dash = span.indexOf('-')
        if (dash <= 0) return null
        val from = span.substring(0, dash).trim().toLongOrNull() ?: return null
        val to = span.substring(dash + 1).trim().toLongOrNull() ?: return null
        if (from < 0 || to < from) return null
        val totalText = body.substring(slash + 1).trim()
        val total = if (totalText == "*") 0L else (totalText.toLongOrNull() ?: return null)
        if (total < 0) return null
        return Range(from, total)
    }

    /** 按响应码与 Content-Range 判定这次续传请求的结果。 */
    fun ofResponse(
        code: Int,
        requestedFrom: Long,
        contentRange: String?,
    ): Kind =
        when {
            code == 206 -> {
                val r = parseContentRange(contentRange)
                if (r != null && r.from == requestedFrom) Kind.RESUMED else Kind.UNTRUSTED
            }

            code == 416 -> Kind.INVALID_RANGE
            code in 200..299 -> Kind.NOT_SUPPORTED
            else -> Kind.HTTP_FAILURE
        }
}

/**
 * 断点记录（sidecar）。
 *
 * 设计参考 Ghost-Downloader-3 的 `.ghd` 侧车文件（GPL-3.0，仅借鉴设计不复制代码）：
 * 状态与数据分开存，数据文件只追加字节，元信息单独一份 JSON，
 * 写入用临时文件加原子替换，半截 JSON 不会让下次续传读出错乱的断点。
 *
 * @param done 已确认落盘的字节数。磁盘实际字节可能比它多（写完还没更新记录就被杀），
 *   这种情况以磁盘为准，见 [check]。
 * @param etag 远端 ETag；与 [lastModified] 至少有一个才敢续传。
 */
internal data class ResumeState(
    val url: String,
    val done: Long,
    val total: Long,
    val etag: String?,
    val lastModified: String?,
) {
    enum class Verdict {
        RESUME,

        /** 字节已够，不必再连网。 */
        ALREADY_COMPLETE,

        /** 记录是 0 字节，等于没有断点。 */
        DISCARD_EMPTY,

        /** URL 变了（换镜像或换资产），旧字节不属于这次下载。 */
        DISCARD_URL,

        /** ETag/Last-Modified 变了：远端文件已改，拼接必然损坏。 */
        DISCARD_REMOTE_CHANGED,

        /** 两侧都没有校验标识，无法判断远端是否变过，不敢拼。 */
        DISCARD_NO_VALIDATOR,

        /** 磁盘字节比记录少：文件被截断或被清理过。 */
        DISCARD_FILE_MISMATCH,
    }

    fun encode(): String =
        JSONObject()
            .put("v", SCHEMA)
            .put("url", url)
            .put("done", done)
            .put("total", total)
            .put("etag", etag ?: JSONObject.NULL)
            .put("lastModified", lastModified ?: JSONObject.NULL)
            .toString()

    companion object {
        /**
         * 判断这份断点能不能用。
         *
         * 顺序有意如此：先排除「根本不属于这次下载」（URL/空记录），
         * 再排除「远端已变」，最后才看磁盘字节。反过来会把远端变更误报成文件不符，
         * 排查时看错方向。
         */
        fun check(
            state: ResumeState,
            url: String,
            fileBytes: Long,
            etag: String?,
            lastModified: String?,
        ): Verdict {
            if (state.url != url) return Verdict.DISCARD_URL
            if (state.done <= 0 && fileBytes <= 0) return Verdict.DISCARD_EMPTY
            val hadValidator = !state.etag.isNullOrBlank() || !state.lastModified.isNullOrBlank()
            val hasValidator = !etag.isNullOrBlank() || !lastModified.isNullOrBlank()
            if (!hadValidator || !hasValidator) return Verdict.DISCARD_NO_VALIDATOR
            if (!state.etag.isNullOrBlank() && !etag.isNullOrBlank() && state.etag != etag) {
                return Verdict.DISCARD_REMOTE_CHANGED
            }
            if (!state.lastModified.isNullOrBlank() && !lastModified.isNullOrBlank() &&
                state.lastModified != lastModified
            ) {
                return Verdict.DISCARD_REMOTE_CHANGED
            }
            if (fileBytes < state.done) return Verdict.DISCARD_FILE_MISMATCH
            if (state.total > 0 && fileBytes >= state.total) return Verdict.ALREADY_COMPLETE
            return Verdict.RESUME
        }

        /** sidecar 结构版本：字段语义变了要升，旧记录直接作废而不是猜。 */
        const val SCHEMA = 1

        fun decode(text: String?): ResumeState? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(text)
                if (o.optInt("v", 0) != SCHEMA) return null
                val url = o.optString("url")
                if (url.isEmpty()) return null
                val done = o.optLong("done", -1)
                val total = o.optLong("total", -1)
                if (done < 0 || total < 0) return null
                ResumeState(
                    url = url,
                    done = done,
                    total = total,
                    etag = optNullable(o, "etag"),
                    lastModified = optNullable(o, "lastModified"),
                )
            }.getOrNull()
        }

        /**
         * 取可空字符串。
         *
         * 不能直接用 optString：它对 JSONObject.NULL 会返回字面量 "null"，
         * 于是「没有 ETag」被读成「ETag 是字符串 null」，下次比对必然判远端已变，
         * 断点永远续不上。
         */
        private fun optNullable(
            o: JSONObject,
            key: String,
        ): String? {
            if (o.isNull(key)) return null
            return o.optString(key).takeIf { it.isNotEmpty() }
        }

        /**
         * 确定性临时文件名。
         *
         * 以前是 `<name>.<nanoTime>.part`，进程重启后根本找不到上次的半成品，
         * 等于每次启动都从 0 开始。改成按目标名取短哈希：同一目标恒定，
         * 不同目标不冲突，也不把 URL 里的字符带进文件名。
         */
        fun partNameFor(targetName: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(targetName.toByteArray())
            val tag = digest.take(4).joinToString("") { "%02x".format(it) }
            return "$targetName.$tag.part"
        }

        /** sidecar 落在临时文件旁边，名字由临时名派生。 */
        fun sidecarFor(part: File): File = File(part.parentFile, part.name + ".dlstate")

        /**
         * 原子写入：先写 `.tmp` 再 rename。
         *
         * 直接覆盖写会在断电/被杀时留下半截 JSON，下次解析失败还算好，
         * 更坏的情况是恰好截在合法边界上，读出一个错误的 done。
         */
        fun save(
            sidecar: File,
            state: ResumeState,
        ): Boolean =
            runCatching {
                sidecar.parentFile?.mkdirs()
                val tmp = File(sidecar.parentFile, sidecar.name + ".tmp")
                tmp.writeText(state.encode())
                if (tmp.renameTo(sidecar)) {
                    true
                } else {
                    // 目标已存在时某些文件系统不允许覆盖 rename，先删再试。
                    sidecar.delete()
                    tmp.renameTo(sidecar)
                }
            }.getOrDefault(false)

        fun load(sidecar: File): ResumeState? =
            runCatching { if (sidecar.isFile) decode(sidecar.readText()) else null }.getOrNull()

        /** 作废断点：数据与记录一起清，避免下次拿旧记录配新文件。 */
        fun discard(
            part: File,
            sidecar: File,
        ) {
            runCatching { part.delete() }
            runCatching { sidecar.delete() }
        }

        /**
         * 分块任务的断点（#731）。
         *
         * 与单连接断点分开存：分块的已完成量是「每块各自的进度」，不是一个连续前缀，
         * 用 [ResumeState.done] 那种单值表达不了。schema 升到 2，多出 chunks 数组。
         *
         * @param ranges 每块的闭区间，顺序与 [done] 一一对应。
         * @param done 每块已落盘字节数。
         */
        @JvmStatic
        fun encodeChunks(
            url: String,
            total: Long,
            etag: String?,
            lastModified: String?,
            ranges: List<ChunkPlan.Chunk>,
            done: List<Long>,
        ): String {
            val arr = org.json.JSONArray()
            ranges.forEach { c ->
                arr.put(
                    org.json.JSONObject()
                        .put("from", c.from)
                        .put("to", c.to)
                        .put("done", (done.getOrNull(c.index) ?: 0L).coerceIn(0, c.length)),
                )
            }
            return org.json.JSONObject()
                .put("v", CHUNK_SCHEMA)
                .put("url", url)
                .put("total", total)
                .put("etag", etag ?: org.json.JSONObject.NULL)
                .put("lastModified", lastModified ?: org.json.JSONObject.NULL)
                .put("chunks", arr)
                .toString()
        }

        /** 分块 sidecar 的结构版本。与单连接的 [SCHEMA] 互不兼容，读到不匹配就作废。 */
        const val CHUNK_SCHEMA = 2

        /** 解出来的分块断点。 */
        data class ChunkState(
            val url: String,
            val total: Long,
            val etag: String?,
            val lastModified: String?,
            val ranges: List<ChunkPlan.Chunk>,
            val done: List<Long>,
        )

        /**
         * 解析分块断点。
         *
         * 任何一项不合法就整份作废，不做部分采用：半套区间配半套进度会拼出坏文件，
         * 而重下一份的代价只是流量。
         */
        fun decodeChunks(text: String?): ChunkState? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val o = org.json.JSONObject(text)
                if (o.optInt("v", 0) != CHUNK_SCHEMA) return null
                val url = o.optString("url")
                if (url.isEmpty()) return null
                val total = o.optLong("total", -1)
                if (total <= 0) return null
                val arr = o.optJSONArray("chunks") ?: return null
                if (arr.length() == 0) return null
                val ranges = ArrayList<ChunkPlan.Chunk>(arr.length())
                val done = ArrayList<Long>(arr.length())
                var cursor = 0L
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val from = c.optLong("from", -1)
                    val to = c.optLong("to", -1)
                    val d = c.optLong("done", -1)
                    if (from < 0 || to < from || d < 0) return null
                    // 区间必须首尾相接且从 0 开始，否则这份记录描述的字节覆盖不完整。
                    if (from != cursor) return null
                    val len = to - from + 1
                    if (d > len) return null
                    ranges.add(ChunkPlan.Chunk(index = i, from = from, to = to))
                    done.add(d)
                    cursor = to + 1
                }
                if (cursor != total) return null
                ChunkState(
                    url = url,
                    total = total,
                    etag = if (o.isNull("etag")) null else o.optString("etag").takeIf { it.isNotEmpty() },
                    lastModified =
                        if (o.isNull("lastModified")) null else o.optString("lastModified").takeIf { it.isNotEmpty() },
                    ranges = ranges,
                    done = done,
                )
            }.getOrNull()
        }
    }
}