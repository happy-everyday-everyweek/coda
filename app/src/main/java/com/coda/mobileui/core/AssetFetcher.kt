package com.coda.mobileui.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 随包资产的解析与获取。
 *
 * 目标：**资产不再必须全部塞进 APK**。调用方只要声明「我是谁、原始下载地址、
 * 期望大小、期望 sha256」，这一层负责按顺序找：
 *
 *  1. 目标位置已经有完整文件 → 直接用（内容校验通过才算数）
 *  2. APK 内 assets 里有 → 释放出来（同样校验）
 *  3. 都没有 → 依次尝试镜像站下载（GitLink 那套前缀镜像），逐个校验
 *
 * 为什么校验这么较真：真实踩过坑——镜像站返回了大小完全相同但内容被改写的
 * APK（219,309,172 字节一致、sha256 不一致），安装时报「应用没有签名」。
 * 所以这里宁可多算一遍哈希，也不接受「大小对就当作对」。
 *
 * 校验失败不是终止条件，而是换下一个镜像的理由：坏源换掉就好，不该让用户
 * 面对一个无法自愈的失败。
 */
object AssetFetcher {

    /** 下载进度回调：当前、总量（字节）。 */
    fun interface Progress {
        fun onBytes(done: Long, total: Long)
    }

    /**
     * 一个可获取的资产。
     *
     * @param assetPath APK 内的相对路径（`assets/` 之下）；本地已有则跳过。
     * @param remoteUrl 原始 GitHub 链接（不带镜像前缀）。
     * @param sha256 小写十六进制；null 表示不校验内容（**不推荐**，仅用于无法确定哈希的临时场景）。
     * @param size 期望字节数；0 表示不校验长度。
     */
    data class Spec(
        val assetPath: String,
        val remoteUrl: String,
        val sha256: String?,
        val size: Long,
    )

    /**
     * 确保目标文件就位。
     *
     * [target] 是落盘位置；[spec] 描述来源。整个过程在调用线程同步执行，
     * 大文件（几十 MB）必须放在后台线程调用。
     *
     * 返回 true 表示「target 现在是内容已校验的完整文件」。
     */
    fun ensure(
        ctx: Context,
        target: File,
        spec: Spec,
        onProgress: Progress? = null,
        onLog: (String) -> Unit = {},
    ): Boolean {
        // 1. 已经就位：先校验，避免把上次写坏的半截文件当成好的。
        if (target.isFile && target.length() > 0) {
            if (verify(target, spec)) {
                onLog("已就位：${target.name}")
                return true
            }
            onLog("本地文件校验不通过，重新获取：${target.name}")
            runCatching { target.delete() }
        }

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")

        // 2. 先看 APK 内有没有。
        if (assetExists(ctx, spec.assetPath)) {
            if (extractAsset(ctx, spec.assetPath, tmp) && verify(tmp, spec)) {
                if (tmp.renameTo(target)) {
                    onLog("从安装包释放：${target.name}")
                    return true
                }
            }
            runCatching { tmp.delete() }
            onLog("安装包内资产校验失败，转下载：${target.name}")
        }

        // 3. 联网：ping 剔除死源 → 测速排序 → 按序下载。
        //
        // 三段的理由：
        //  - ping 只做 TCP 握手，1.5 秒内就能确认一个域名是不是根本连不上。
        //    不让测速去发现死源，是因为一条死源要等满 6 秒窗口才判失败。
        //  - 测速拿真实吞吐排序，避免「清单顺序」把流量导到一个很慢但活着的源。
        //  - 下载按序降级，前一个内容校验不过（镜像返回被改写的内容）就换下一个。
        //
        // 小文件（<2MB）跳过测速：测速本身要拉 12MB，比文件还大。ping 仍然做，
        // 因为它几乎不花流量。
        onLog("探测镜像连通性…")
        val alive = MirrorSpeedTester.pingAll(GhMirrors.DEFAULT)
            .filter { it.ok }
            .map { it.mirror }
        if (alive.isEmpty()) {
            onLog("没有可用镜像（全部连接失败）")
            return false
        }
        onLog("${alive.size}/${GhMirrors.DEFAULT.size} 个镜像可连通")

        val ranked: List<GhMirror> = if (spec.size <= 0 || spec.size >= 2L * 1024 * 1024) {
            onLog("测速挑选镜像…")
            val results = MirrorSpeedTester.testAll(alive, spec.remoteUrl)
            val usable = results.filter { it.ok && it.sustainedBps > 0 }
            if (usable.isEmpty()) {
                onLog("测速无结果，按 ping 延迟顺序尝试")
                alive
            } else {
                val best = usable.first()
                onLog("选定 ${best.mirror.id}（${fmtSpeed(best.sustainedBps)}）")
                usable.map { it.mirror } + alive.filter { m -> usable.none { it.mirror.id == m.id } }
            }
        } else {
            alive
        }

        val errors = StringBuilder()
        for (m in ranked) {
            val url = GhMirrors.wrap(m.prefix, spec.remoteUrl)
            onLog("下载 ${m.id}…")
            val got = download(url, tmp, spec, onProgress)
            if (got && verify(tmp, spec)) {
                if (tmp.renameTo(target)) {
                    onLog("完成（${m.id}）：${target.name}")
                    return true
                }
            } else if (got) {
                // 大小对、内容不对：镜像返回了被改写的内容。换源重试，这是唯一可靠的应对。
                onLog("${m.id} 内容校验不通过，换源")
                errors.append(m.id).append(":hash ")
            } else {
                errors.append(m.id).append(":fail ")
            }
            runCatching { tmp.delete() }
        }
        onLog("所有源都失败：$errors")
        return false
    }

    private fun fmtSpeed(bps: Long): String = when {
        bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
        bps >= 1024 -> "%.0f KB/s".format(bps / 1024.0)
        else -> "$bps B/s"
    }

    // ---------- 校验 ----------

    /** 按 spec 校验文件；spec 没给 sha256 也没有 size 时退化为「文件非空」。 */
    fun verify(file: File, spec: Spec): Boolean {
        if (!file.isFile) return false
        if (spec.size > 0 && file.length() != spec.size) return false
        val want = spec.sha256?.trim()?.lowercase()
        if (want.isNullOrEmpty()) return file.length() > 0
        val got = sha256(file) ?: return false
        return got.equals(want, ignoreCase = true)
    }

    fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    // ---------- assets ----------

    fun assetExists(ctx: Context, path: String): Boolean =
        runCatching { ctx.assets.open(path).use { true } }.getOrDefault(false)

    private fun extractAsset(ctx: Context, path: String, out: File): Boolean = runCatching {
        ctx.assets.open(path).use { input ->
            FileOutputStream(out).use { o -> input.copyTo(o, 256 * 1024) }
        }
        true
    }.getOrDefault(false)

    // ---------- 下载 ----------

    /**
     * 单源下载到 [out]。
     *
     * 只负责「把字节搬下来」，内容是否可信交给 [verify]。HTTP 非 2xx、字节数
     * 与 spec.size 不符（且 size 已给出）都直接判失败，省掉一次无意义的哈希。
     */
    private fun download(url: String, out: File, spec: Spec, onProgress: Progress?): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "Coda-Android")
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode !in 200..299) {
                conn.errorStream?.close()
                false
            } else {
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: spec.size
                FileOutputStream(out).use { o ->
                    conn.inputStream.use { input -> copy(input, o, total, onProgress) }
                }
                if (spec.size > 0 && out.length() != spec.size) false else true
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        runCatching { out.delete() }
        false
    }

    private fun copy(input: InputStream, out: FileOutputStream, total: Long, p: Progress?) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            done += n
            // 每 1MB 报一次，够画进度又不会把主线程刷爆。
            if (done - lastReport >= 1_048_576L) {
                lastReport = done
                p?.onBytes(done, total)
            }
        }
        p?.onBytes(done, total)
    }
}