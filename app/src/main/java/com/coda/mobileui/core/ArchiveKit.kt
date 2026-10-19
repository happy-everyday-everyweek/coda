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
    enum class Kind(val label: String) {
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

    /** 是否是可浏览的压缩包（用于列表里换图标与点击行为）。 */
    fun isArchive(file: File): Boolean =
        !file.isDirectory && kindOf(file) != Kind.UNKNOWN

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
     * 列出某个目录层级下的直接条目。
     *
     * [dirPath] 是包内路径，空串表示根。只返回这一层的直接子项：中间目录由调用方
     * 通过"存在性"补齐（zip 里常有 `a/b/c.txt` 而没有 `a/` 与 `a/b/` 条目）。
     */
    fun list(file: File, dirPath: String, env: LinuxEnv?, kind: Kind? = null): List<Entry> =
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
    ): String? = when (kind ?: kindOf(file)) {
        Kind.ZIP -> readZipText(file, path, maxBytes)
        Kind.TAR, Kind.TAR_GZ -> if (env == null) null else readTarText(file, path, env, maxBytes)
        else -> null
    }

    // ------------------------------------------------------------------ zip

    private fun listZip(file: File, dirPath: String): List<Entry> {
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
                            seenDirs[dirName] = Entry(
                                path = base + dirName,
                                name = dirName,
                                isDir = true,
                                size = 0,
                                compressedSize = 0,
                                modifiedAt = e.time,
                            )
                        }
                    } else {
                        if (e.isDirectory) {
                            if (!seenDirs.containsKey(rest)) {
                                seenDirs[rest] = Entry(
                                    path = base + rest,
                                    name = rest,
                                    isDir = true,
                                    size = 0,
                                    compressedSize = 0,
                                    modifiedAt = e.time,
                                )
                            }
                        } else {
                            out += Entry(
                                path = base + rest,
                                name = rest,
                                isDir = false,
                                size = e.size,
                                compressedSize = e.compressedSize,
                                modifiedAt = e.time,
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

    private fun readZipText(file: File, path: String, maxBytes: Long): String? = runCatching {
        ZipFile(file).use { zf ->
            val e = zf.getEntry(path) ?: return null
            if (e.size > maxBytes) return null
            zf.getInputStream(e).bufferedReader(Charsets.UTF_8).use { reader ->
                // 按字符读上限，避免 e.size 不可靠（流式 zip）时一次性读爆
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0L
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return null
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        }
    }.getOrNull()

    // ------------------------------------------------------------------ tar

    /**
     * 通过容器里的 tar 列目录。
     *
     * 容器自带 busybox tar，但它对 --exclude 之类的 GNU 参数支持不全，
     * 所以只用最朴素的 `tar -tf`，目录层级的裁剪在 Kotlin 侧做。
     */
    private fun listTar(file: File, dirPath: String, env: LinuxEnv): List<Entry> {
        val list = runCatching {
            env.exec(listOf("tar", "-tf", file.absolutePath)).output.split('\n')
        }.getOrElse { return emptyList() }
        val base = normalizeDir(dirPath)
        val dirs = LinkedHashMap<String, Entry>()
        val out = ArrayList<Entry>()
        for (raw in list) {
            val line = raw.trim('\r', ' ', '"')
            if (line.isEmpty()) continue
            if (base.isNotEmpty() && !line.startsWith(base)) continue
            val rest = line.removePrefix(base)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val dirName = rest.substring(0, slash)
                if (dirName.isNotEmpty() && !dirs.containsKey(dirName)) {
                    dirs[dirName] = Entry(
                        path = base + dirName,
                        name = dirName,
                        isDir = true,
                        size = 0,
                        compressedSize = 0,
                        modifiedAt = 0L,
                    )
                }
            } else {
                // tar -tf 不带尾部斜杠区分不了目录与空文件，按"尾部原样有斜杠"判断
                val isDir = line.endsWith("/")
                if (!isDir) {
                    out += Entry(
                        path = base + rest,
                        name = rest,
                        isDir = false,
                        size = 0,
                        compressedSize = 0,
                        modifiedAt = 0L,
                    )
                }
            }
        }
        return (dirs.values + out).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun readTarText(
        file: File,
        path: String,
        env: LinuxEnv,
        maxBytes: Long,
    ): String? = runCatching {
        val result = env.exec(listOf("tar", "-xOf", file.absolutePath, path))
        if (!result.ok) return null
        if (result.output.length > maxBytes) return null
        result.output
    }.getOrNull()

    private fun normalizeDir(path: String): String {
        if (path.isEmpty()) return ""
        return if (path.endsWith("/")) path else "$path/"
    }
}
