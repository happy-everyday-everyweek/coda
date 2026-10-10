package com.coda.mobileui.core

import android.content.Context
import android.system.Os
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内置 Linux 环境。
 *
 * 应用只随包携带两样东西：PRoot 引导集与一份 Alpine 根文件系统归档。首次启动把它们释放到
 * `files/linux`，之后每次开终端就只是 execve(proot) 加一次 ptrace 初始化，装配开销只付一次。
 *
 * 这里同时产出进容器所需的启动参数。容器内不继承宿主的 PATH 与 HOME，一律由 entry 里的
 * env -i 重新给出，避免把 Android 的目录布局带进去。
 */
class LinuxEnv(private val ctx: Context) {

    companion object {

        /** 注入内核的环境变量，与内核的终端拓展契约同名。 */
        const val ENV_SHELL = "ZCODE_TERMINAL_SHELL"
        const val ENV_DIALECT = "ZCODE_TERMINAL_DIALECT"
        const val ENV_LOGIN = "ZCODE_TERMINAL_LOGIN"
        const val ENV_PATH = "ZCODE_TERMINAL_PATH"
        const val ENV_LAUNCH_EXEC = "ZCODE_TERMINAL_LAUNCH_EXEC"
        const val ENV_LAUNCH_PREFIX = "ZCODE_TERMINAL_LAUNCH_PREFIX"
        const val ENV_LAUNCH_ENTRY = "ZCODE_TERMINAL_LAUNCH_ENTRY"

        private const val ASSET_ROOT = "linux"
        private const val VERSION_ASSET = "$ASSET_ROOT/version.txt"
        private const val ROOTFS_ASSET = "$ASSET_ROOT/rootfs.tar.gz"
        private const val MARKER = ".installed"
        private const val LOCK = ".installing"
        private const val LINK2SYMLINK_MARK = ".link2symlink"
        private const val MINIMAL_BINDS_MARK = ".minimalbinds"

        /** 引导集：资产路径到运行目录相对路径。总共四个文件，约 300 KB。 */
        private val BOOTSTRAP = listOf(
            "$ASSET_ROOT/bin/proot" to "bin/proot",
            "$ASSET_ROOT/bin/loader" to "bin/loader",
            "$ASSET_ROOT/lib/libtalloc.so.2" to "lib/libtalloc.so.2",
            "$ASSET_ROOT/lib/libandroid-shmem.so" to "lib/libandroid-shmem.so",
        )

        /** 容器内的 PATH，固定值，与宿主无关。 */
        private const val GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /**
         * 绑进容器的宿主路径，格式为源路径到容器内路径（null 表示同名）。
         *
         * Android 各版本暴露的目录并不一致，这里只登记候选，实际绑定时逐个检查是否存在，
         * 缺哪个就跳过哪个——任何一项缺失都不该让终端起不来。
         */
        private val BIND_CANDIDATES = listOf(
            "/dev" to null,
            "/proc" to null,
            "/sys" to null,
            "/dev/pts" to null,
            "/data/local/tmp" to null,
            "/storage/emulated/0" to "/sdcard",
            "/storage/emulated/0" to "/storage/emulated/0",
            "/proc/self/fd" to "/dev/fd",
            "/proc/self/fd/0" to "/dev/stdin",
            "/proc/self/fd/1" to "/dev/stdout",
            "/proc/self/fd/2" to "/dev/stderr",
        )

        private const val LOCK_TIMEOUT_MS = 180_000L
        private const val PROBE_TIMEOUT_SECONDS = 20L

        /**
         * 最小绑定集：只保留设备节点与进程文件系统。
         *
         * 完整绑定集里的 `/sys`、`/dev/pts`、`/proc/self/fd`、`/storage/emulated/0`、`/data/local/tmp`
         * 在不同 Android 版本与权限状态下不一定能绑，任一项失败 proot 会直接起不来。
         * 完整集探测不通过时退到这一档，换掉一部分便利性保住终端可用。
         */
        private val MINIMAL_BIND_CANDIDATES = listOf(
            "/dev" to null,
            "/dev/pts" to null,
            "/proc" to null,
        )

        /** 权限常量：Kotlin 没有八进制字面量，这里写十进制并在注释里标出八进制。 */
        private const val MODE_EXEC = 493 // 0755
        private const val MODE_FILE = 420 // 0644
        private const val MODE_PRIVATE = 384 // 0600
    }

    val home: File = File(ctx.filesDir, "linux")

    /** 根文件系统目录，装配完成后存在于 [home] 下。 */
    val rootfs: File = File(home, "rootfs")

    /** 宿主页缓存用的临时目录，会作为容器的 /dev/shm。 */
    private val tmpDir: File = File(ctx.filesDir, "tmp")

    private val binDir: File = File(home, "bin")
    private val marker: File = File(home, MARKER)
    private val lockDir: File = File(home, LOCK)
    private val link2symlinkMark: File = File(home, LINK2SYMLINK_MARK)
    private val minimalBindsMark: File = File(home, MINIMAL_BINDS_MARK)

    val prootPath: String get() = File(binDir, "proot").absolutePath

    private val loaderPath: String get() = File(binDir, "loader").absolutePath

    /** 环境是否已装配就绪。 */
    fun isReady(): Boolean = try {
        marker.isFile && rootfs.isDirectory && File(prootPath).isFile
    } catch (_: Throwable) {
        false
    }

    /**
     * 装配环境；已装配且资产版本一致时直接返回。
     *
     * 只做一次的操作都收在这里：释放引导集、解归档、探测 proot 能力。
     * 装配完成后会做一次真实的 proot 启动探测，探测失败也照样返回结果，
     * 由调用方决定要不要退回其他终端。
     */
    fun ensureReady(onProgress: (String) -> Unit = {}): Boolean {
        val wanted = assetVersion() ?: return false
        if (isReady() && runCatching { marker.readText().trim() }.getOrNull() == wanted) return true

        home.mkdirs()
        if (!lockDir.mkdirs()) {
            onProgress("Linux 环境正在装配，等待完成…")
            val deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS
            while (lockDir.exists() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return isReady()
        }

        return try {
            tmpDir.mkdirs()
            onProgress("释放 Linux 引导程序…")
            releaseBootstrap()
            onProgress("装配 Linux 环境（首次约需数十秒）…")
            extractRootfs()
            // 分级探测：从功能最全到最保守依次试，第一档能起来就按它启动。
            // 组合是 --link2symlink 有无乘绑定集完整与否，共四档。任一档通过即可，
            // 这样宿主上某个可选绑定路径不可用也不会让整个终端消失。
            val plan = listOf(
                LaunchPlan(link2symlink = true, minimalBinds = false),
                LaunchPlan(link2symlink = true, minimalBinds = true),
                LaunchPlan(link2symlink = false, minimalBinds = false),
                LaunchPlan(link2symlink = false, minimalBinds = true),
            ).firstOrNull { probe(it) }
            if (plan == null) {
                onProgress("Linux 环境自检未通过，将退回内置终端")
                return false
            }
            link2symlinkMark.writeText(if (plan.link2symlink) "1" else "0")
            minimalBindsMark.writeText(if (plan.minimalBinds) "1" else "0")
            marker.writeText(wanted)
            onProgress("Linux 环境就绪")
            true
        } catch (t: Throwable) {
            onProgress("Linux 环境装配失败：${t.message}")
            false
        } finally {
            lockDir.delete()
        }
    }

    /**
     * 把 GitHub 令牌同步进容器。
     *
     * gh 与 git 共用同一份认证：gh 读 hosts.yml，git 的凭据助手直接转调 gh，
     * 这样拿到令牌后两边的 push 与 api 调用都能用。
     */
    fun syncGitHubCredentials() {
        if (!rootfs.isDirectory) return
        val token = GitHub.token(ctx)
        if (token.isNullOrEmpty()) return
        val login = GitHub.loginName(ctx).orEmpty()

        val ghDir = File(rootfs, "root/.config/gh")
        val hosts = buildString {
            append("github.com:\n")
            append("    oauth_token: ").append(token).append('\n')
            append("    git_protocol: https\n")
            if (login.isNotEmpty()) append("    user: ").append(login).append('\n')
        }
        writeFile(File(ghDir, "hosts.yml"), hosts, MODE_PRIVATE)

        val gitConfig = buildString {
            append("[credential \"https://github.com\"]\n")
            append("\thelper = !gh auth git-credential\n")
            append("[credential \"https://gist.github.com\"]\n")
            append("\thelper = !gh auth git-credential\n")
            append("[init]\n")
            append("\tdefaultBranch = main\n")
            if (login.isNotEmpty()) {
                append("[user]\n")
                append("\tname = ").append(login).append('\n')
                append("\temail = ").append(login).append("@users.noreply.github.com\n")
            }
        }
        writeFile(File(rootfs, "root/.gitconfig"), gitConfig, MODE_FILE)
    }

    /** proot 的前缀参数：进入 rootfs、伪造 root、设定初始工作目录并绑定宿主目录。 */
    fun launchPrefix(): List<String> {
        val out = mutableListOf("-0", "-r", rootfs.absolutePath)
        if (runCatching { link2symlinkMark.readText().trim() }.getOrNull() == "1") {
            out += "--link2symlink"
        }
        out += listOf("-w", "/root")
        for ((source, guest) in activeBinds()) {
            out += "-b"
            out += if (guest == null || guest == source) source else "$source:$guest"
        }
        return out
    }

    /** 交给内核的环境变量补丁。 */
    fun env(): Map<String, String> {
        val prefix = JSONArray(launchPrefix())
        val entry = JSONArray(
            listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "SHELL=/bin/bash",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=$GUEST_PATH",
                "/bin/bash",
            ),
        )
        return mapOf(
            ENV_SHELL to prootPath,
            ENV_DIALECT to "posix",
            ENV_LOGIN to "1",
            ENV_PATH to GUEST_PATH,
            ENV_LAUNCH_EXEC to prootPath,
            ENV_LAUNCH_PREFIX to prefix.toString(),
            ENV_LAUNCH_ENTRY to entry.toString(),
            "PROOT_LOADER" to loaderPath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
        )
    }

    // ---------------------------------------------------------------- 装配

    private fun assetVersion(): String? = try {
        ctx.assets.open(VERSION_ASSET).use { it.readBytes().decodeToString().trim() }
    } catch (_: Throwable) {
        null
    }

    private fun releaseBootstrap() {
        for ((asset, relative) in BOOTSTRAP) {
            val target = File(home, relative)
            target.parentFile?.mkdirs()
            ctx.assets.open(asset).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            Os.chmod(target.absolutePath, MODE_EXEC)
        }
    }

    /** 解归档到临时目录再整体就位，中途失败不会破坏上一次可用的环境。 */
    private fun extractRootfs() {
        val staging = File(home, "rootfs.staging")
        deleteRecursive(staging)
        staging.mkdirs()
        ctx.assets.open(ROOTFS_ASSET).use { raw ->
            GZIPInputStream(BufferedInputStream(raw, 1 shl 16)).use { gz -> untar(gz, staging) }
        }
        deleteRecursive(rootfs)
        if (!staging.renameTo(rootfs)) {
            deleteRecursive(staging)
            throw IllegalStateException("根文件系统无法就位")
        }
    }

    /** 精简 ustar 解包器：归档只含常规文件、目录与符号链接，不带 pax 扩展头。 */
    private fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        val buffer = ByteArray(1 shl 16)
        while (true) {
            if (!readBlock(input, header)) return
            if (header[0] == 0.toByte()) return
            val name = readString(header, 0, 100)
            if (name.isEmpty()) return
            val mode = readOctal(header, 100, 8).toInt()
            val size = readOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)
            val prefix = readString(header, 345, 155)
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            val relative = full.removePrefix("./").trimStart('/')
            val isRegular = type == '0' || type == '\u0000' || type == '7'

            val target = if (relative.isEmpty() || relative.split('/').contains("..")) {
                null
            } else {
                File(dest, relative)
            }

            var consumed = false
            if (target != null) {
                when {
                    type == '5' -> target.mkdirs()
                    type == '2' -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching { Os.symlink(linkName, target.absolutePath) }
                    }
                    isRegular -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            copyExactly(input, out, size, buffer)
                        }
                        consumed = true
                        runCatching { Os.chmod(target.absolutePath, mode) }
                    }
                }
            }

            val payload = if (isRegular) size else 0L
            if (!consumed && payload > 0) skipExactly(input, payload, buffer)
            val padding = (512L - (payload % 512L)) % 512L
            if (padding > 0) skipExactly(input, padding, buffer)
        }
    }

    private fun readBlock(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        var started = false
        var index = offset
        val limit = offset + length
        while (index < limit) {
            val c = header[index].toInt().toChar()
            if (!started && (c == ' ' || c == '\u0000')) {
                index++
                continue
            }
            if (c < '0' || c > '7') break
            started = true
            value = value * 8 + (c - '0')
            index++
        }
        return value
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long, buffer: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) throw IllegalStateException("归档数据不完整")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, size: Long, buffer: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read < 0) return
            remaining -= read
        }
    }

    // ---------------------------------------------------------------- 探测

    /** 一类启动组合：是否带 --link2symlink、绑定集用完整还是最小。 */
    private data class LaunchPlan(val link2symlink: Boolean, val minimalBinds: Boolean)

    /**
     * 用真实 proot 启动一次容器做自检。
     *
     * 不用先拼参数试错：能起就采纳这套组合，起不来就换下一档。
     * 启动前必须清掉 LD_LIBRARY_PATH——内核给终端进程带的那个指向 Node 运行时自己的库目录，
     * 继承下去会让动态加载器先去那里找，可能撞库。引导集的 RUNPATH 已是 $ORIGIN 相对形式，
     * 清空后照样能定位到 libtalloc 与 libandroid-shmem。
     */
    private fun probe(plan: LaunchPlan): Boolean {
        val binds = if (plan.minimalBinds) minimalBinds() else bindPairs()
        val command = mutableListOf<String>()
        command += prootPath
        command += "-0"
        command += "-r"
        command += rootfs.absolutePath
        if (plan.link2symlink) command += "--link2symlink"
        command += "-w"
        command += "/root"
        for ((source, guest) in binds) {
            command += "-b"
            command += if (guest == null || guest == source) source else "$source:$guest"
        }
        command += "/usr/bin/env"
        command += "-i"
        command += "HOME=/root"
        command += "/bin/true"

        return try {
            val builder = ProcessBuilder(command)
            builder.directory(home)
            builder.redirectErrorStream(true)
            val environment = builder.environment()
            environment.remove("LD_LIBRARY_PATH")
            environment["PROOT_LOADER"] = loaderPath
            environment["PROOT_TMP_DIR"] = tmpDir.absolutePath
            val process = builder.start()
            process.inputStream.readBytes()
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 探测通过后固定下来的绑定集：上次装配时哪一档通过，运行期就用哪一档。 */
    private fun activeBinds(): List<Pair<String, String?>> {
        val minimal = runCatching { minimalBindsMark.readText().trim() }.getOrNull() == "1"
        return if (minimal) minimalBinds() else bindPairs()
    }

    /** 完整绑定集：候选路径挑存在的，再补上应用沙箱自身。 */
    private fun bindPairs(): List<Pair<String, String?>> = collectBinds(BIND_CANDIDATES)

    private fun minimalBinds(): List<Pair<String, String?>> = collectBinds(MINIMAL_BIND_CANDIDATES)

    private fun collectBinds(candidates: List<Pair<String, String?>>): List<Pair<String, String?>> {
        val pairs = mutableListOf<Pair<String, String?>>()
        for ((source, guest) in candidates) {
            if (runCatching { File(source).exists() }.getOrDefault(false)) pairs += source to guest
        }
        // 宿主应用沙箱自身：让内核传下来的工作目录在容器内同样存在，落点才一致。
        val files = ctx.filesDir.absolutePath
        pairs += files to files
        if (tmpDir.isDirectory) pairs += tmpDir.absolutePath to "/dev/shm"
        return pairs
    }

    // ---------------------------------------------------------------- 工具

    private fun writeFile(file: File, text: String, mode: Int) {
        file.parentFile?.mkdirs()
        file.writeText(text)
        runCatching { Os.chmod(file.absolutePath, mode) }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }
}