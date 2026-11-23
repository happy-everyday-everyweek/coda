package com.coda.mobileui.core

/**
 * `tar -tvf` 详细列表的解析。
 *
 * 单独抽出来是为了能在 JVM 上验证：tar 的输出格式有几个坑，靠真机点目录很难覆盖全。
 * 一是成员名可能含空格、引号，不能盲目 trim；二是 `tar -cf x.tar .` 会带 `./` 前缀，
 * 不规范化就与调用方拼出的 base 对不上；三是名字里可能有 GNU tar 的 C 风格转义。
 */
internal object TarListing {
    /** 一条解析结果。 */
    data class Member(
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modifiedAt: Long,
    )

    /**
     * 规范化成员路径：只剥开头的 `./`，保留名字里合法的首尾空格与引号。
     *
     * 行尾的 `\r` 由调用方去掉（那是 CRLF 换行的一部分，不是名字）。
     */
    fun normalizePath(raw: String): String {
        var path = raw
        while (path.startsWith("./")) path = path.substring(2)
        return path
    }

    /** 还原 GNU tar 的 C 风格转义；没有转义时原样返回。 */
    fun unescape(name: String): String {
        if (!name.contains('\\')) return name
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c != '\\' || i + 1 >= name.length) {
                sb.append(c)
                i++
                continue
            }
            when (val next = name[i + 1]) {
                'n' -> {
                    sb.append('\n')
                }

                't' -> {
                    sb.append('\t')
                }

                'r' -> {
                    sb.append('\r')
                }

                '\\' -> {
                    sb.append('\\')
                }

                '"' -> {
                    sb.append('"')
                }

                else -> {
                    // 八进制转义 \ooo：tar 用它表示不可打印字节。
                    if (next in '0'..'7' && i + 3 < name.length) {
                        val oct = name.substring(i + 1, i + 4)
                        val value = oct.toIntOrNull(8)
                        if (oct.all { it in '0'..'7' } && value != null) {
                            sb.append(value.toChar())
                            i += 4
                            continue
                        }
                    }
                    sb.append(next)
                }
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * 解析 `tar -tvf` 的一行。
     *
     * 典型格式（GNU 与 busybox 一致）：
     * `-rw-r--r-- 0/0  1234 2024-05-06 07:08 path/to/file`
     * 符号链接会多出 ` -> target`，取箭头前的部分作为成员名。
     * 解析不出时返回 null，由调用方跳过而不是猜一个路径出来。
     */
    fun parseLine(raw: String): Member? {
        val line = raw.removeSuffix("\r")
        if (line.isBlank()) return null
        val mode = line.firstOrNull() ?: return null
        // 只认 tar 的类型位；其他开头说明这不是详细列表（比如误用了 -tf）。
        if (mode !in "-dlbcps") return null
        val isDir = mode == 'd'
        // 前三个字段是权限、属主、大小；随后是日期与时间；剩下的整段才是名字。
        val parts = line.split(Regex("\\s+"), limit = 6)
        if (parts.size < 6) return null
        val size = parts[2].toLongOrNull() ?: 0L
        val stamp = parts[3] + " " + parts[4]
        var name = parts[5]
        // 符号链接与硬链接的目标不属于成员名。
        val arrow = name.indexOf(" -> ")
        if (arrow >= 0) name = name.substring(0, arrow)
        name = normalizePath(unescape(name))
        if (name.isEmpty() || name == ".") return null
        val dirByName = name.endsWith("/")
        return Member(
            path = name.trimEnd('/'),
            isDir = isDir || dirByName,
            size = if (isDir || dirByName) 0L else size,
            modifiedAt = parseStamp(stamp),
        )
    }

    /** 一条链接成员（#792）。 */
    data class Link(
        val path: String,
        val target: String,
        /** true 表示硬链接（`h` 行，`link to`），false 表示符号链接（`l` 行，`->`）。 */
        val hardLink: Boolean,
    )

    /**
     * 解析链接行，取出成员名与链接目标（#792）。
     *
     * 符号链接是 `l... name -> target`，硬链接是 `h... name link to target`。
     * 用 [parseLine] 同一套字段切分与转义还原，名字里的空格与引号不会被截断；
     * 之前调用方自己 `substringAfter(" -> ").trim()` 会把这两类名字弄坏，
     * 而且完全看不到硬链接。非链接行返回 null。
     */
    fun parseLink(raw: String): Link? {
        val line = raw.removeSuffix("\r")
        if (line.isBlank()) return null
        val mode = line.firstOrNull() ?: return null
        if (mode != 'l' && mode != 'h') return null
        val parts = line.split(Regex("\\s+"), limit = 6)
        if (parts.size < 6) return null
        val rest = parts[5]
        val hard = mode == 'h'
        val sep = if (hard) " link to " else " -> "
        val at = rest.indexOf(sep)
        if (at < 0) return null
        val name = normalizePath(unescape(rest.substring(0, at)))
        val target = unescape(rest.substring(at + sep.length))
        if (name.isEmpty()) return null
        return Link(path = name.trimEnd('/'), target = target, hardLink = hard)
    }

    /** 把 `2024-05-06 07:08` 解析成毫秒；解析不出返回 0，由上层决定回退值。 */
    fun parseStamp(stamp: String): Long =
        runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(stamp.trim())?.time ?: 0L
        }.getOrDefault(0L)
}
