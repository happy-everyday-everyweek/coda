package com.coda.mobileui.core

import java.io.File
import java.util.zip.ZipFile

/**
 * 压缩包浏览。
 *
 * 目标：压缩包点开后的操作与目录一致——列条目、进出子目录、看文件内容。
 * 这里只做「读」，解压落盘是另一件事（[ArchiveOpener] 之外的单次解压）。
 *
 * 支持面刻意收窄到 Android 自带的 java.util.zip：zip / jar / apk / war 都能直接读。
 * tar / tar.gz / tgz 走容器里的系统 tar（容器本身带 busybox tar），不额外引依赖。
 */
object ArchiveKit {
    /** 一个可浏览的压缩包条目（目录或文件）。 */
    data class Entry(
        /** 包内完整路径，例如 `src/main/AndroidManifest.xml`。 */
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val compressedSize: Long,
        val modifiedAt: Long,
    )

    /** 压缩包类型。决定用哪条读取路径。 */
    enum class Kind(
        val label: String,
    ) {
        ZIP("zip"),
        TAR("tar"),
        TAR_GZ("tar.gz"),
        GZ("gz"),
        UNKNOWN("压缩包"),
    }

    /** 按扩展名判断类型。压缩包后缀在不同场景下写法差异很大，这里一并覆盖。 */
    fun kindOf(file: File): Kind {
        val n = file.name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") ||
                n.endsWith(".war") || n.endsWith(".aar") || n.endsWith(".whl") ||
                n.endsWith(".xpi") || n.endsWith(".epub") || n.endsWith(".docx") ||
                n.endsWith(".xlsx") || n.endsWith(".pptx") -> Kind.ZIP

            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ

            n.endsWith(".tar") -> Kind.TAR

            n.endsWith(".gz") -> Kind.GZ

            else -> Kind.UNKNOWN
        }
    }

    /**
     * 是否是可浏览的压缩包（用于列表里换图标与点击行为）。
     *
     * 纯 `.gz` 排除在外：它只是单个文件的压缩流，没有成员表，
     * 而 list/readText 也没有对应分支。放进来只会得到一个永远为空的列表，
     * 让用户分不清「包是空的」与「不支持这个格式」。
     */
    fun isArchive(file: File): Boolean {
        if (file.isDirectory) return false
        return when (kindOf(file)) {
            Kind.UNKNOWN, Kind.GZ -> false
            else -> true
        }
    }

    /**
     * 按文件头判断是不是 zip 容器。
     *
     * 后缀不可信：apk 改名成 .bin、从网络下来的无后缀文件、`.ZIP` 大写、`.zipx` 都存在。
     * zip 的本地文件头固定是 `PK\x03\x04`；空包是 `PK\x05\x06`；跨卷分片是 `PK\x07\x08`。
     * 只读前 4 字节，对任意大小的文件都是常数开销。
     */
    fun looksLikeZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return runCatching {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(4)
                if (input.read(head) != 4) {
                    false
                } else {
                    head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                        (
                            (head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
                                (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()) ||
                                (head[2] == 0x07.toByte() && head[3] == 0x08.toByte())
                        )
                }
            }
        }.getOrDefault(false)
    }

    /**
     * 打开某个文件时该走哪条路。
     *
     * 判断顺序就是需求里的优先级：先看后缀明确是压缩包；后缀不明时，只要有别的打开方式
     * （系统里注册过这个 MIME/后缀的应用），就交给系统；确实没有别的办法，才按二进制内容
     * 试一次 ZIP。
     */
    enum class Route {
        /** 后缀是压缩包，直接包内浏览。 */
        ARCHIVE,

        /** 后缀未知，但内容像 ZIP，且没有别的打开方式。 */
        ARCHIVE_BY_CONTENT,

        /** 普通文本/可编辑文件。 */
        TEXT,

        /** 交给系统应用。 */
        SYSTEM,

        /** 没有任何可用方式。 */
        NONE,
    }

    /**
     * 现在能不能读这个压缩包。
     *
     * tar 系要靠容器里的 tar，容器没就绪时 [list] 只能返回空列表——那与「包确实是空的」
     * 在界面上长得一模一样。调用方先用这个判断，不可读就提示「暂时无法读取」而不是「没有条目」。
     *
     * @return null 表示可读；否则是可直接展示的原因。
     */
    fun unavailableReason(
        file: File,
        env: LinuxEnv?,
        kind: Kind? = null,
    ): String? =
        when (kind ?: kindOf(file)) {
            Kind.TAR, Kind.TAR_GZ ->
                if (env == null) "运行环境尚未就绪，暂时无法读取该压缩包" else null
            Kind.UNKNOWN -> "不支持的压缩包格式"
            Kind.GZ -> "单个 gzip 文件不支持按目录浏览"
            else -> if (!file.isFile) "压缩包文件不存在" else null
        }

    /**
     * 列出某个目录层级下的直接条目。
     *
     * [dirPath] 是包内路径，空串表示根。只返回这一层的直接子项：中间目录由调用方
     * 通过"存在性"补齐（zip 里常有 `a/b/c.txt` 而没有 `a/` 与 `a/b/` 条目）。
     */
    fun list(
        file: File,
        dirPath: String,
        env: LinuxEnv?,
        kind: Kind? = null,
    ): List<Entry> =
        when (kind ?: kindOf(file)) {
            Kind.ZIP -> listZip(file, dirPath)
            Kind.TAR, Kind.TAR_GZ -> if (env == null) emptyList() else listTar(file, dirPath, env)
            else -> emptyList()
        }

    /**
     * 读取包内某个文件的文本内容。
     *
     * [maxBytes] 是硬上限：压缩包里可能躺着几百 MB 的日志，读进内存会把进程拖死。
     * 超过上限返回 null，由调用方提示"文件过大"。
     */
    fun readText(
        file: File,
        path: String,
        env: LinuxEnv?,
        maxBytes: Long = 2L * 1024 * 1024,
        kind: Kind? = null,
    ): String? =
        when (kind ?: kindOf(file)) {
            Kind.ZIP -> readZipText(file, path, maxBytes)
            Kind.TAR, Kind.TAR_GZ -> if (env == null) null else readTarText(file, path, env, maxBytes)
            else -> null
        }

    // ------------------------------------------------------------------ zip

    /**
     * 条目的修改时间。
     *
     * `ZipEntry.getTime()` 在没有时间戳时返回 -1（流式生成的包很常见），
     * 直接交给 UI 会渲染成 1969-12-31。这里退回压缩包自身的修改时间。
     */
    internal fun entryTime(
        raw: Long,
        archive: File,
    ): Long = raw.takeIf { it > 0 } ?: archive.lastModified()

    private fun listZip(
        file: File,
        dirPath: String,
    ): List<Entry> {
        val base = normalizeDir(dirPath)
        return runCatching {
            ZipFile(file).use { zf ->
                val seenDirs = LinkedHashMap<String, Entry>()
                val out = ArrayList<Entry>()
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val full = e.name
                    if (base.isNotEmpty() && !full.startsWith(base)) continue
                    var rest = full.removePrefix(base)
                    if (rest.isEmpty()) continue
                    // 只看这一层：遇到斜杠就说明还在更深一层，交给"隐式目录"处理
                    val slash = rest.indexOf('/')
                    if (slash >= 0) {
                        val dirName = rest.substring(0, slash)
                        if (dirName.isEmpty()) continue
                        if (!seenDirs.containsKey(dirName)) {
                            seenDirs[dirName] =
                                Entry(
                                    path = base + dirName,
                                    name = dirName,
                                    isDir = true,
                                    size = 0,
                                    compressedSize = 0,
                                    modifiedAt = entryTime(e.time, file),
                                )
                        }
                    } else {
                        if (e.isDirectory) {
                            if (!seenDirs.containsKey(rest)) {
                                seenDirs[rest] =
                                    Entry(
                                        path = base + rest,
                                        name = rest,
                                        isDir = true,
                                        size = 0,
                                        compressedSize = 0,
                                        modifiedAt = entryTime(e.time, file),
                                    )
                            }
                        } else {
                            out +=
                                Entry(
                                    path = base + rest,
                                    name = rest,
                                    isDir = false,
                                    size = e.size,
                                    compressedSize = e.compressedSize,
                                    modifiedAt = entryTime(e.time, file),
                                )
                        }
                    }
                }
                // 目录在前、同类按名字排，和文件页的排序口径一致
                (seenDirs.values + out).sortedWith(
                    compareBy({ !it.isDir }, { it.name.lowercase() }),
                )
            }
        }.getOrElse { emptyList() }
    }

    internal fun readZipText(
        file: File,
        path: String,
        maxBytes: Long,
    ): String? =
        runCatching {
            ZipFile(file).use { zf ->
                val e = zf.getEntry(path) ?: return null
                if (e.size > maxBytes) return null
                zf.getInputStream(e).use { input ->
                    // 按字节计上限：原来数的是字符数，UTF-8 中文一个字符占三字节，
                    // 实际读进内存的量是上限的三倍。
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) return null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }.getOrNull()

    // ------------------------------------------------------------------ tar

    /**
     * 同一个压缩包的完整成员表缓存。
     *
     * 进出子目录只是换个前缀筛选，不需要重新 `tar -tvf` 一遍。大包每层都全量扫描
     * 会把导航拖得很慢，所以按「路径 + 长度 + 修改时间」缓存解析结果。
     */
    private data class TarCacheKey(
        val path: String,
        val length: Long,
        val modified: Long,
    )

    // accessOrder=true（OCR #823）：插入序 FIFO 会让热包被冷包挤掉，改 LRU。
    private val tarCache = LinkedHashMap<TarCacheKey, List<TarListing.Member>>(16, 0.75f, true)

    /** 缓存上限：压缩包可能很大，只留最近几个包的成员表。 */
    private const val TAR_CACHE_MAX = 4
    /** tar -tvf 输出上限：约 16MB 详细列表，足够几十万条目；超出按无法浏览。 */
    private const val TAR_LISTING_MAX_BYTES = 16 * 1024 * 1024

    private fun tarMembers(
        file: File,
        env: LinuxEnv,
    ): List<TarListing.Member>? {
        val key = TarCacheKey(file.absolutePath, file.length(), file.lastModified())
        synchronized(tarCache) {
            tarCache[key]?.let { return it }
        }
        // -tvf 而不是 -tf：只有详细列表才带大小与修改时间，否则列表全是 0 B 与 1970。
        // 限长读回（OCR #823）：默认 Int.MAX_VALUE 会把超大包的整份详细列表
        // 读进内存再缓存 4 份，浏览一个大 tar 就能 OOM。截断的列表不完整，
        // 按「无法浏览」处理而不是缓存半张成员表。
        val raw =
            runCatching {
                val result = env.exec(listOf("tar", "-tvf", file.absolutePath), maxOutputBytes = TAR_LISTING_MAX_BYTES)
                if (!result.ok) return null
                if (result.output.toByteArray(Charsets.UTF_8).size >= TAR_LISTING_MAX_BYTES) return null
                result.output
            }.getOrNull() ?: return null
        val members = raw.split('\n').mapNotNull { TarListing.parseLine(it) }
        synchronized(tarCache) {
            if (tarCache.size >= TAR_CACHE_MAX) {
                tarCache.remove(tarCache.keys.first())
            }
            tarCache[key] = members
        }
        return members
    }

    private fun listTar(
        file: File,
        dirPath: String,
        env: LinuxEnv,
    ): List<Entry> {
        val members = tarMembers(file, env) ?: return emptyList()
        val base = normalizeDir(dirPath)
        val dirs = LinkedHashMap<String, Entry>()
        val out = ArrayList<Entry>()
        for (m in members) {
            if (base.isNotEmpty() && !m.path.startsWith(base)) continue
            val rest = m.path.removePrefix(base)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val dirName = rest.substring(0, slash)
                if (dirName.isNotEmpty() && !dirs.containsKey(dirName)) {
                    // 中间目录：tar 里不一定有对应条目，先按占位建，遇到真条目再覆盖。
                    dirs[dirName] =
                        Entry(
                            path = base + dirName,
                            name = dirName,
                            isDir = true,
                            size = 0,
                            compressedSize = 0,
                            modifiedAt = 0L,
                        )
                }
            } else if (m.isDir) {
                dirs[rest] =
                    Entry(
                        path = m.path,
                        name = rest,
                        isDir = true,
                        size = 0,
                        compressedSize = 0,
                        modifiedAt = m.modifiedAt.takeIf { it > 0 } ?: file.lastModified(),
                    )
            } else {
                out +=
                    Entry(
                        path = m.path,
                        name = rest,
                        isDir = false,
                        size = m.size,
                        // tar 本身不压缩，压缩后大小与原始一致。
                        compressedSize = m.size,
                        modifiedAt = m.modifiedAt.takeIf { it > 0 } ?: file.lastModified(),
                    )
            }
        }
        return (dirs.values + out).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun readTarText(
        file: File,
        path: String,
        env: LinuxEnv,
        maxBytes: Long,
    ): String? =
        runCatching {
            // 成员名与列表用同一种形式：列表已剥掉 ./，这里也要剥，否则点击后读不出内容。
            val member =
                TarListing.normalizePath(path).takeIf { isSafeMember(it) } ?: return null
            // 输出上限要在这里就交给 exec：等它把整个成员读成字符串再判断，
            // 一个几百 MB 的成员已经把内存吃掉了。
            val limit = (maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            var result = env.exec(listOf("tar", "-xOf", file.absolutePath, member), maxOutputBytes = limit)
            if (!result.ok) {
                // busybox 原样输出成员名（OCR #823）：C 转义还原可能把字面
                // 反斜杠名弄坏。用列表里保留的原始名回退重试一次。
                val raw =
                    tarMembers(file, env)
                        ?.firstOrNull { it.path == member }
                        ?.rawPath
                        ?.takeIf { it != member && isSafeMember(it) }
                if (raw != null) {
                    result = env.exec(listOf("tar", "-xOf", file.absolutePath, raw), maxOutputBytes = limit)
                }
                if (!result.ok) return null
            }
            // 按字节判上限，不是字符数：UTF-8 中文一个字符占三字节，
            // 用 output.length 比较会让实际内存占用达到上限的三倍。
            if (result.output.toByteArray(Charsets.UTF_8).size.toLong() > maxBytes) return null
            result.output
        }.getOrNull()

    /**
     * 成员名能否直接交给 tar。
     *
     * 以 `-` 开头会被 tar 当成选项（例如 `--checkpoint-action=exec=…` 这类可以直接
     * 执行命令）；换行能让一个名字在输出/日志里伪装成两行。这两种都拒绝。
     */
    internal fun isSafeMember(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("-") && !path.contains('\n') && !path.contains('\r') &&
            // tar 把成员参数当 glob（OCR #823）：名字含 * ? [ 可能匹配到别的
            // 成员，readTarText 会显示错误文件的内容。容器 tar 未必支持
            // --no-wildcards，直接在守卫侧拒绝元字符。
            path.none { it == '*' || it == '?' || it == '[' }

    private fun normalizeDir(path: String): String {
        // 与成员路径用同一种形式：TarListing 已剥掉开头的 ./，这里也必须剥，
        // 否则从带 ./ 前缀的包进入子目录后 base 与成员路径对不上，逐层导航全部失效。
        val clean = TarListing.normalizePath(path)
        if (clean.isEmpty()) return ""
        return if (clean.endsWith("/")) clean else "$clean/"
    }
}
