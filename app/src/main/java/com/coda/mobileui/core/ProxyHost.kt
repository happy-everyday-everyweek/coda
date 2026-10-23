package com.coda.mobileui.core

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * CLIProxyAPI 宿主进程的生命周期管理。
 *
 * 这里只做一件事：把别人写好的 Go 网关当作常驻子进程在本机的 Linux 环境里拉起来，并保证它
 * 可启、可探、可停。它自己不实现任何反代逻辑——账号调度、故障转移、模型转发、插件加载都在
 * 宿主里，那些能力通过 HTTP 管理接口对外，客户端只负责把界面接上去。
 *
 * 为什么是子进程而不是移植：宿主与插件之间是一套 60 个方法的 RPC 契约（插件注册、模型注册、
 * 请求翻译、流式转发、认证登录轮询等），插件是"零件"、宿主是"另一半"。要把插件改成不依赖
 * 宿主，等于把宿主重写一遍。所以这里让宿主保持原样跑起来，插件按 `plugins/` 目录原样加载。
 *
 * 进程走 [LinuxEnv.builder]，与终端页同一条 proot 通路，因此不需要另造一套容器启动逻辑。
 *
 * 分发方式：宿主二进制与八家的 arm64 插件**全部随 APK 发布**（`assets/proxy-host` 与
 * `assets/proxy-plugins`），首次使用只做本地复制，运行期不依赖任何外部服务。
 * 早先这里有一条"资产缺失就联网下载"的兜底，那是设计错误：几十兆的下载在移动网络下
 * 经常失败，用户只会看到一句"网关启动失败"，而真实原因被埋在网络层。
 */
object ProxyHost {

    /** 宿主发布版本；升级只需改这里，随包资产与目录名跟着变。 */
    const val VERSION = "8.0.32"

    /** 内置端口：避开常用端口，冲突时按 [PORT_CANDIDATES] 依次重试。 */
    private const val DEFAULT_PORT = 8317

    private val PORT_CANDIDATES = intArrayOf(8317, 18317, 28317, 38317)

    /** 就绪探测：给宿主留足冷启动时间（解包后首次启动会拉模型目录）。 */
    private const val READY_TIMEOUT_MS = 45_000L

    /** 单次 HTTP 探测的超时；探测本身很快，慢说明进程没起来。 */
    private const val PROBE_TIMEOUT_MS = 1_500

    private const val PROBE_INTERVAL_MS = 500L
    /**
     * 管理接口密钥。
     *
     * 宿主对管理接口有硬要求：即使是回环地址也必须有密钥，留空则整个管理面返回 404。
     * 因此这里写一个固定值，仅用于本机回环调用，不对外暴露，也不需要用户配置。
     */
    private const val MANAGEMENT_KEY = "coda-local-proxy"

    /** 网关给下游用的调用密钥。 */
    private const val CLIENT_KEY = "coda-client-key"

    /** 管理接口密钥。 */
    fun managementKey(): String = MANAGEMENT_KEY

    /**
     * 网关给下游（也就是内核）用的调用密钥。
     *
     * 与管理密钥分开：管理密钥能改配置、删账号，调用密钥只能发请求。内核只需要后者，
     * 把权限更大的那把交给它没有好处。
     */
    fun clientKey(): String = CLIENT_KEY

    private var process: Process? = null
    private var booting = false
    private var activePort = DEFAULT_PORT

    /**
     * 最近一次失败的原因。
     *
     * 这个字段是必要的：之前所有失败都只通过 onProgress 回调出去，接口层拿到 false 之后
     * 只会说一句"网关启动失败"，用户和日志都看不到究竟卡在哪一步（资产缺失、环境未就绪、
     * 端口全占、进程起不来、还是就绪超时）。现在每一步都写这里，界面可以直接显示。
     */
    @Volatile
    var lastError: String? = null
        private set

    /** 宿主进程是否存活。 */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 是否正在启动（覆盖解包与等待就绪阶段）。 */
    val isStarting: Boolean
        get() = booting

    /**
     * 网关程序与其数据的存放根目录，位于 rootfs 之内。
     *
     * 这里有一个必须绕开的坑：早先把二进制放在 `ctx.filesDir/proxy-host`，再靠
     * `guestPath()` 把宿主路径换算成容器路径交给 proot。这条路依赖"应用沙箱以同名路径
     * 绑定进容器"这个假设，实测在真机上不成立——proot 起得来（`env` 正常执行），
     * 但容器里没有那个路径，于是报：
     * `env: can't execute '/data/user/0/.../cli-proxy-api': No such file or directory`
     *
     * 现在改成直接放进 rootfs：rootfs 是以 `-r <rootfs>` 作为容器根挂进去的，
     * 所以宿主上的 `<rootfs>/root/.coda/proxy-host/cli-proxy-api` 在容器里必然就是
     * `/root/.coda/proxy-host/cli-proxy-api`，路径由我们自己完全决定，不经过任何绑定推导。
     * 数据单独放 `data/`，升级宿主时只换二进制、不动账号与配置。
     */
    private fun guestRoot(ctx: Context): File =
        File(File(LinuxEnv(ctx).rootfs, "root"), ".coda/proxy-host").apply { mkdirs() }

    /** 宿主侧的存放目录（rootfs 内），保留这个入口给日志与配置。 */
    fun dirFor(ctx: Context): File = guestRoot(ctx)

    fun binaryFor(ctx: Context): File = File(guestRoot(ctx), "cli-proxy-api")

    /**
     * glibc 运行时目录，位于 rootfs 内。
     *
     * rootfs 是 Alpine（musl），而网关二进制是 glibc 构建，声明的解释器是
     * `/lib/ld-linux-aarch64.so.1`——musl 系统里没有这个文件，内核 execve 找不到
     * 解释器时返回 ENOENT，`env` 把它报成 `can't execute <二进制>: No such file or
     * directory`。报错里的路径是二进制的，真正缺失的是解释器，所以"文件明明在却报
     * 不存在"。
     *
     * 修法：随包带一小套 glibc 运行时（loader + libc + 它的依赖），启动时不再直接
     * execve 二进制，而是 `ld-linux --library-path <glibc 目录> 二进制`，让加载器
     * 用这套库把程序拉起来，绕开 musl rootfs 里不存在的 `/lib/ld-linux-aarch64.so.1`。
     */
    fun glibcDirFor(ctx: Context): File = File(guestRoot(ctx), "glibc").apply { mkdirs() }

    fun pluginsDirFor(ctx: Context): File = File(guestRoot(ctx), "plugins").apply { mkdirs() }

    fun dataDirFor(ctx: Context): File = File(guestRoot(ctx), "data").apply { mkdirs() }

    fun configFor(ctx: Context): File = File(guestRoot(ctx), "config.yaml")

    /**
     * 管理面板静态目录（rootfs 内），必须与网关自己的推导一致：
     * StaticDir = config 文件所在目录 + static/，config 在 /root/.coda/proxy-host/，
     * 所以面板资产释放到 /root/.coda/proxy-host/static/management.html。
     */
    fun staticDirFor(ctx: Context): File = File(guestRoot(ctx), "static").apply { mkdirs() }

    /**
     * 释放管理面板资产（management.html，3.3MB）。
     *
     * 网关对这份资产的态度：本地有就直接伺服，没有才联网下载——而"联网下载"正是
     * 启动被拖死的根源（直连挂起时健康探测永远等不到应答）。因此随包带一份、
     * 启动前释放到位，网关就再没有理由在启动路径上碰网络。
     *
     * 失败不阻塞启动：面板是管理入口，转发与账号能力都不依赖它，缺了顶多
     * 打不开面板，网关本身照常工作。
     */
    private fun ensureManagementAsset(ctx: Context) {
        val target = File(staticDirFor(ctx), "management.html")
        val expected = runCatching { assetSize(ctx, "proxy-host/management.html") }.getOrNull() ?: return
        if (target.isFile && target.length() == expected) return
        runCatching { target.delete() }
        val ok = runCatching {
            ctx.assets.open("proxy-host/management.html").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            true
        }.getOrDefault(false)
        if (ok) {
            AppLog.i("ProxyHost", "管理面板资产就位：${target.absolutePath}")
        } else {
            AppLog.e("ProxyHost", "管理面板资产释放失败（不阻塞网关启动）")
        }
    }

    fun logFor(ctx: Context): File = File(guestRoot(ctx), "host.log")

    /**
     * 容器内的绝对路径。
     *
     * 因为上面所有文件都在 rootfs 内，容器路径就是把 rootfs 的宿主前缀换成根 `/`。
     * 不再走 `guestPath()` 的绑定推导，避免依赖"沙箱同名绑定"这个不可靠假设。
     */
    private fun guestPathOf(ctx: Context, file: File): String {
        val root = LinuxEnv(ctx).rootfs.absolutePath.trimEnd('/')
        val abs = file.absolutePath
        val rel = if (abs.startsWith("$root/")) abs.removePrefix("$root") else "/$abs"
        return rel.ifEmpty { "/" }
    }

    /** 网关程序在容器内的路径。 */
    fun guestBinary(ctx: Context): String = guestPathOf(ctx, binaryFor(ctx))

    /** 配置文件在容器内的路径。 */
    fun guestConfig(ctx: Context): String = guestPathOf(ctx, configFor(ctx))

    /**
     * 实际交给 proot 执行的启动命令。
     *
     * 不是二进制本身：rootfs 是 musl 系，直接 execve 一个 glibc 程序会因解释器
     * `/lib/ld-linux-aarch64.so.1` 不存在而报 ENOENT。这里用随包的 glibc loader
     * 显式加载二进制，库查找也限定在 `glibc/` 目录，与 musl 的库完全隔离。
     */
    private fun launchCommand(ctx: Context, args: List<String>): List<String> {
        val loader = guestPathOf(ctx, File(glibcDirFor(ctx), "ld-linux-aarch64.so.1"))
        val libDir = guestPathOf(ctx, glibcDirFor(ctx))
        val binary = guestBinary(ctx)
        return listOf(loader, "--library-path", libDir, binary) + args
    }

    /** 随包 glibc 运行时的文件清单；释放与安装校验共用一份，避免两处漂移。 */
    private val GLIBC_LIBS = listOf(
        "ld-linux-aarch64.so.1",
        "libc.so.6",
        "libm.so.6",
        "libpthread.so.0",
        "libdl.so.2",
        "libresolv.so.2",
        "libgcc_s.so.1",
    )

    /**
     * 宿主二进制是否已就位。
     *
     * 不只看大小：写了一半的 64MB 文件同样大于 10MB，却不可执行。这里额外核对 ELF 文件头，
     * 让"半截文件"当场被判为未安装并触发重写，而不是等到启动时才报 can't execute。
     */
    fun isInstalled(ctx: Context): Boolean {
        val bin = binaryFor(ctx)
        // glibc 运行时整套一并算作安装条件，且 loader 必须有执行位：loader 是被 env
        // 直接 execve 的，缺执行位时报 Permission denied（上一版释放时漏了 chmod，
        // 存量设备靠这个校验触发补装修复）。
        val glibcDir = glibcDirFor(ctx)
        val libsReady = GLIBC_LIBS.all { File(glibcDir, it).isFile } &&
            File(glibcDir, "ld-linux-aarch64.so.1").canExecute()
        val ok = bin.isFile && bin.length() > 10_000_000L && looksExecutable(bin) && libsReady
        if (!ok) {
            AppLog.d(
                "ProxyHost",
                "isInstalled=false: isFile=${bin.isFile} length=${bin.length()} " +
                    "elf=${looksExecutable(bin)} glibc=$libsReady",
            )
        }
        return ok
    }

    /**
     * 确保宿主二进制就位。
     *
     * 宿主随 APK 一起发布（`assets/proxy-host/cli-proxy-api-<VERSION>`），首次使用只做一次
     * 本地复制，**不联网**。之前这里有一条"资产缺失就从 GitHub 下载"的兜底，那是错的：
     * 二十多兆的下载在移动网络下经常失败，用户看到的就是一句"网关启动失败"，而真正原因
     * 被埋在网络层。构建期就该把二进制放进包里，运行时不该依赖任何外部服务。
     */
    fun ensureInstalled(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val target = binaryFor(ctx)
        AppLog.d("ProxyHost", "ensureInstalled: target=${target.absolutePath} isFile=${target.isFile}")
        if (isInstalled(ctx)) return true

        onProgress("释放网关程序…")
        if (installFromAsset(ctx, target)) {
            AppLog.i("ProxyHost", "网关程序释放成功：${target.absolutePath} ${target.length()}B")
            return true
        }

        // 走到这里说明打包漏了资产，属于构建错误，要让用户看到明确原因而不是干等。
        val reason = "安装包内缺少网关程序（assets/proxy-host/cli-proxy-api-$VERSION）"
        lastError = reason
        onProgress(reason)
        return false
    }

    /** 从随包资产释放宿主二进制。资产缺失或写出不完整时返回 false。 */
    private fun installFromAsset(ctx: Context, target: File): Boolean {
        val names = listOf(
            "proxy-host/cli-proxy-api-$VERSION",
            "proxy-host/cli-proxy-api",
        )
        target.parentFile?.mkdirs()
        for (name in names) {
            // 先量出资产真实长度：只靠"写出后超过 10MB"判断会放过被截断的半截文件——
            // 64MB 的二进制写到一半中断仍然大于 10MB，于是被当成"已安装"，
            // 启动时 proot 就会报 can't execute（文件不可执行/不完整）。
            val expected = runCatching { assetSize(ctx, name) }.getOrNull()
            if (expected == null) {
                AppLog.d("ProxyHost", "资产不存在：$name")
                continue
            }
            AppLog.d("ProxyHost", "资产存在：$name ${expected}B")
            if (expected <= 10_000_000L) continue
            // 写之前先删掉可能残留的半截文件，避免追加到旧内容后面。
            runCatching { target.delete() }
            val ok = runCatching {
                ctx.assets.open(name).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
                }
                true
            }.getOrDefault(false)
            if (!ok) {
                runCatching { target.delete() }
                continue
            }
            // 长度必须与资产完全一致，差一个字节都算失败。
            if (target.length() == expected) {
                Os.chmod(target.absolutePath, 493) // 0755
                // 再确认文件头是 ELF：长度对但内容被替换/写坏时，proot 同样只会报
                // can't execute（那条错误把"不存在"和"格式不对"混在一起，很难分辨）。
                if (looksExecutable(target) && installGlibc(ctx)) return true
            }
            runCatching { target.delete() }
        }
        return false
    }

    /**
     * 释放 glibc 运行时到 rootfs 内的 `glibc/` 目录。
     *
     * 这些库从 Ubuntu glibc 2.39（aarch64）取出，而网关二进制只要求 GLIBC_2.17，
     * 版本完全够用。每个文件独立校验长度，任一失败就整体返回 false，让调用方
     * 走"缺少网关程序"的失败路径并提示用户，而不是带着半套库去启动。
     */
    private fun installGlibc(ctx: Context): Boolean {
        val dir = glibcDirFor(ctx)
        for (name in GLIBC_LIBS) {
            val target = File(dir, name)
            val expected = runCatching { assetSize(ctx, "proxy-host/glibc/$name") }.getOrNull() ?: continue
            if (target.isFile && target.length() == expected) {
                // 已就位也要补执行位：loader 是被 env 直接 execve 的，没执行位就是
                // Permission denied。老版本释放时漏了 chmod，靠这里把存量文件修好。
                runCatching { Os.chmod(target.absolutePath, 493) } // 0755
                continue
            }
            runCatching { target.delete() }
            val ok = runCatching {
                ctx.assets.open("proxy-host/glibc/$name").use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
                }
                true
            }.getOrDefault(false)
            if (!ok || target.length() != expected) {
                AppLog.e("ProxyHost", "释放 glibc 库失败：$name")
                return false
            }
            // FileOutputStream 写出的文件没有执行位，必须显式放开。
            runCatching { Os.chmod(target.absolutePath, 493) } // 0755
        }
        AppLog.i("ProxyHost", "glibc 运行时就位：${dir.absolutePath}")
        return true
    }

    /** 文件头是否是 ELF 可执行。 */
    private fun looksExecutable(file: File): Boolean = runCatching {
        java.io.FileInputStream(file).use { input ->
            val head = ByteArray(4)
            if (input.read(head) != 4) return false
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** 读资产声明的长度；拿不到返回 null。 */
    private fun assetSize(ctx: Context, name: String): Long? = runCatching {
        ctx.assets.openFd(name).use { it.length }
    }.getOrNull() ?: runCatching {
        // 压缩存储的资产没有 openFd，只能实际读一遍统计字节数（首次安装一次性开销）。
        ctx.assets.open(name).use { input ->
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
            }
            total
        }
    }.getOrNull()

    /**
     * 写配置文件。每次启动前重写，保证端口、插件目录、数据目录与实际路径一致。
     *
     * 配置里必须写**容器内**路径：宿主进程跑在容器里，它只认 `/root/.coda/...` 这种形式，
     * 不认 Android 的 `/data/user/0/...`。
     */
    private fun writeConfig(ctx: Context, port: Int) {
        val plugins = guestPathOf(ctx, pluginsDirFor(ctx))
        val data = guestPathOf(ctx, dataDirFor(ctx))
        val text = buildString {
            appendLine("config-version: 8")
            appendLine("server:")
            appendLine("  host: \"127.0.0.1\"")
            appendLine("  port: $port")
            appendLine("oauth:")
            // v8 起认证目录从顶层挪到了 oauth 段下，写在顶层会被忽略。
            appendLine("  auth-dir: \"$data/auth\"")
            appendLine("management:")
            // 管理接口即使在回环地址上也要密钥，写操作（登录、签到、删账号）全部经过它。
            appendLine("  allow-remote: false")
            appendLine("  secret-key: \"$MANAGEMENT_KEY\"")
            // 管理面板资产随包发布并释放到 static/，禁掉启动期与运行期的面板自动更新：
            // 否则网关会先查 GitHub API 比对哈希，直连挂起时整个启动被网络拖死
            //（真机实测 45 秒就绪窗口被下载耗尽，健康探测永远等不到应答）。
            appendLine("  disable-auto-update-panel: true")
            appendLine("access:")
            // 下游（内核）用这把密钥调 /v1，权限只到发请求，不能碰管理面。
            appendLine("  api-keys:")
            appendLine("    - \"$CLIENT_KEY\"")
            appendLine("plugins:")
            appendLine("  enabled: true")
            appendLine("  dir: \"$plugins\"")
            // 只为目录里真实存在的插件写启用项，避免宿主报缺插件。
            val present = OAuthProviders.all.filter {
                File(pluginsDirFor(ctx), OAuthProviders.pluginFile(it)).isFile
            }
            if (present.isNotEmpty()) {
                appendLine("  configs:")
                for (p in present) {
                    appendLine("    ${p.pluginId}:")
                    appendLine("      enabled: true")
                }
            }
        }
        configFor(ctx).writeText(text)
    }

    /**
     * 备好运行前提，但不启动进程：装配 Linux 环境 + 释放宿主二进制。
     *
     * 单独拆出来是因为顺序有硬要求：二进制落在 rootfs 内，必须先有 rootfs；插件又必须在
     * 宿主进程拉起之前放到位，宿主启动时才扫描得到。所以调用方的顺序应当是
     * `ensureRuntime` → `ensurePlugin` → `start`。
     */
    fun ensureRuntime(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        val env = LinuxEnv(ctx)
        if (!env.isReady()) {
            onProgress("装配 Linux 环境…")
            if (!env.ensureReady(onProgress)) {
                lastError = "Linux 环境装配失败，网关无法启动。请先在终端页确认环境可用"
                onProgress(lastError!!)
                return false
            }
        }
        // 面板资产在任何启动路径都先就位：网关本地有它就不会碰网络。
        ensureManagementAsset(ctx)
        return ensureInstalled(ctx, onProgress)
    }

    /**
     * 启动宿主并等到它真正就绪。
     *
     * 调用方必须在后台线程调用：整个过程含解包、进程拉起与最多 [READY_TIMEOUT_MS] 的等待。
     * 端口从候选里挑第一个能用的，挑不到就返回失败，而不是让宿主带着冲突端口起一个假活的进程。
     */
    fun start(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        if (isRunning) return true

        fun fail(reason: String): Boolean {
            lastError = reason
            onProgress(reason)
            // 启动失败必须落日志：这条路径的失败信息此前只存在于界面上，用户一关就没了。
            AppLog.e("ProxyHost", "启动失败：$reason")
            return false
        }

        // 前提条件统一由 ensureRuntime 保证（环境装配 + 二进制释放），顺序与 prepare 一致。
        if (!ensureRuntime(ctx, onProgress)) return false

        val env = LinuxEnv(ctx)

        val port = pickPort() ?: return fail("端口全部被占用（${PORT_CANDIDATES.joinToString("/")}）")
        activePort = port
        writeConfig(ctx, port)
        onProgress("启动网关…")

        val binaryPath = guestBinary(ctx)
        val configPath = guestConfig(ctx)
        // 启动前做一次现场核对：can't execute 这条错误把"文件不存在""不可执行""架构不对"
        // 混在一起，不把真实状态打出来，排查就只能靠猜。
        val hostBinary = binaryFor(ctx)
        if (!hostBinary.isFile) {
            return fail("网关程序未就位（$binaryPath）")
        }
        if (!looksExecutable(hostBinary)) {
            return fail("网关程序文件损坏（大小 ${hostBinary.length()}）")
        }
        if (hostBinary.length() <= 10_000_000L) {
            return fail("网关程序不完整（大小 ${hostBinary.length()}）")
        }
        val builder = env.builder(launchCommand(ctx, listOf("-config", configPath)))

        return try {
            booting = true
            val log = logFor(ctx)
            log.parentFile?.mkdirs()
            log.writeText("")
            val proc = builder.start()
            process = proc
            pump(proc, log)
            val ready = awaitReady(port)
            if (!ready) {
                // 进程活着但不应答，和进程直接退出，是两种完全不同的故障。
                val tail = logTail(log)
                stop()
                return fail(
                    if (proc.isAlive) "网关启动超时（45 秒内未应答$port）" + tail
                    else "网关进程退出" + tail
                )
            }
            true
        } catch (t: Throwable) {
            stop()
            fail("网关启动失败：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            booting = false
        }
    }

    /**
     * 取日志末尾若干行，附在错误里。
     *
     * 宿主起不来时真正的原因（缺动态库、配置语法错、端口绑定失败）都写在它自己的输出里，
     * 不把这几行带出来，界面永远只会显示一句笼统的失败。
     */
    private fun logTail(log: File, lines: Int = 30): String = runCatching {
        val text = log.readLines()
        if (text.isEmpty()) {
            "（网关没有任何输出）"
        } else {
            "：\n" + text.takeLast(lines).joinToString("\n").take(3000)
        }
    }.getOrDefault("")

    /**
     * 把宿主的输出持续写进日志文件，方便排查。
     *
     * 标准输出与错误输出各一条线程，但它们共用同一个文件，必须串行写：两个线程各自
     * 用 appendText 会互相覆盖偏移，日志会变成残缺的碎片。这里只加锁，不合并流——
     * 合并会丢掉"这条是错误还是普通输出"的区分。
     */
    private fun pump(proc: Process, log: File) {
        val lock = Any()
        fun drain(stream: java.io.InputStream) {
            Thread {
                runCatching {
                    stream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            synchronized(lock) {
                                runCatching { log.appendText(line + "\n") }
                            }
                        }
                    }
                }.onFailure {
                    // 读不到输出不致命：宿主是否可用由 HTTP 探测判定，不看日志。
                }
            }.apply { isDaemon = true }.start()
        }
        drain(proc.inputStream)
        drain(proc.errorStream)
    }

    /** 轮询 `/healthz` 直到宿主应答，或超时。 */
    private fun awaitReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            if (probe(port)) return true
            try {
                Thread.sleep(PROBE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun probe(port: Int): Boolean = try {
        val conn = (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (_: Throwable) {
        false
    }

    /** 挑一个可用端口；全部被占用时返回 null。 */
    private fun pickPort(): Int? {
        for (port in PORT_CANDIDATES) {
            if (portFree(port)) return port
        }
        return null
    }

    private fun portFree(port: Int): Boolean = try {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 当前宿主正在监听的端口；未启动时是默认端口。 */
    fun port(): Int = activePort

    /** 管理接口基址。 */
    fun baseUrl(): String = "http://127.0.0.1:${activePort}"

    /** 停掉宿主。销毁即走，不等待优雅退出——它的状态都落在磁盘上，重启会重新加载。 */
    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    /**
     * 带鉴权的管理接口调用。
     *
     * 宿主的写操作要求管理密钥；这里用配置里注入的固定值，只被本机回环地址访问。
     * 返回体原样回给调用方，由调用方决定怎么解析——本层只负责发请求，不解释业务语义。
     */
    fun management(
        ctx: Context,
        path: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 30_000,
    ): String? = try {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutMs
            // 宿主要求所有管理请求都带密钥，回环地址也不例外。
            setRequestProperty("Authorization", "Bearer $MANAGEMENT_KEY")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 取插件自带管理面板的 HTML 原文。
     *
     * 面板挂在 `/v0/resource/plugins/<pluginId>/panel`，是资源路由，不需要管理密钥。
     * 它对我们的价值不在界面，而在它是插件的**能力清单**：面板脚本里用 `api("/xxx")`
     * 调哪些路由，插件就实现了哪些路由。比起拿 POST 空 body 去试探（空 body 在不少插件里
     * 等于「对全部账号执行」，会真的触发签到或停用），读面板是纯只读、且一次拿到全量。
     *
     * 拿不到就返回 null，调用方退回"什么都不显示"而不是瞎猜。
     */
    fun resourceText(ctx: Context, path: String, timeoutMs: Int = 10_000): String? = try {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = timeoutMs
        }
        val code = conn.responseCode
        val text = if (code in 200..299) {
            conn.inputStream?.bufferedReader()?.use { it.readText() }
        } else {
            runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
            null
        }
        conn.disconnect()
        text
    } catch (_: Throwable) {
        null
    }

    /**
     * 发一条管理请求，只回状态码。
     *
     * 用来区分"这条路不存在"与"这条路存在但参数不对"：插件把未知路径当 404 回，
     * 而参数缺失这类业务错误仍是 200。探测插件能力时只有这个区分靠得住。
     * 连不上返回 null，与 404 是两回事。
     */
    fun managementStatus(
        ctx: Context,
        path: String,
        method: String = "POST",
        body: String? = null,
    ): Int? = try {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $MANAGEMENT_KEY")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        // 必须读掉响应体，否则连接不会被回收，反复探测会把连接池占满。
        runCatching {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        }
        conn.disconnect()
        code
    } catch (_: Throwable) {
        null
    }

    /** 解析管理接口返回的 JSON；失败返回 null，由界面决定怎么提示。 */
    fun managementJson(
        ctx: Context,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JSONObject? = runCatching {
        ProxyHost.management(ctx, path, method, body)?.let { JSONObject(it) }
    }.getOrNull()

    /**
     * 用下游调用密钥请求数据面接口（`/v1` 下）。
     *
     * 与管理面分开：这里只带 `clientKey`，只能读模型、发对话，动不了账号与配置。
     */
    fun clientJson(ctx: Context, path: String): JSONObject? = runCatching {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $CLIENT_KEY")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299 && text != null) JSONObject(text) else null
    }.getOrNull()

    // ---------------------------------------------------------------- 插件

    /**
     * 确保某个 OAuth 供应商的插件已就位。
     *
     * 只下这一个插件的 arm64 包（约 3MB），解出 `.so` 放进宿主扫描的插件目录。
     * 已经装过就跳过，避免每次启动都重新下载。
     */
    fun ensurePlugin(ctx: Context, provider: OAuthProviders.Provider, onProgress: (String) -> Unit = {}): Boolean {
        val target = File(pluginsDirFor(ctx), OAuthProviders.pluginFile(provider))
        if (target.isFile && target.length() > 500_000L) return true

        // 先看包内有没有。八家的 arm64 插件都已随包发布，正常情况下这一步就够了，不联网。
        onProgress("安装 ${provider.name} 插件…")
        val assetName = "proxy-plugins/" + OAuthProviders.pluginFile(provider)
        val fromAsset = runCatching {
            ctx.assets.open(assetName).use { input ->
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            true
        }.getOrDefault(false)
        if (fromAsset && target.length() > 500_000L) {
            Os.chmod(target.absolutePath, 493) // 0755
            return true
        }
        target.delete()

        // 包内没有再走网络：只对将来新增、尚未打进包里的平台有意义。
        onProgress("下载 ${provider.name} 插件…")
        val zip = File(dirFor(ctx), "${provider.pluginId}.zip")
        if (!download(ctx, OAuthProviders.downloadUrl(provider), zip)) {
            lastError = "获取 ${provider.name} 插件失败：安装包内没有该插件，联网下载也未成功"
            return false
        }

        return try {
            // 压缩包里只有一个同名 .so，直接按 zip 结构取，不假设路径深度。
            java.util.zip.ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".so")) continue
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zin.copyTo(out, 256 * 1024) }
                    break
                }
            }
            zip.delete()
            if (target.isFile && target.length() > 500_000L) {
                Os.chmod(target.absolutePath, 493) // 0755
                true
            } else {
                target.delete()
                lastError = "${provider.name} 插件包内容不完整"
                false
            }
        } catch (_: Throwable) {
            zip.delete()
            target.delete()
            lastError = "${provider.name} 插件解包失败"
            false
        }
    }

    /**
     * 下载到文件。
     *
     * raw.githubusercontent.com 在部分网络下会被重置，所以失败时退回 GitHub API 的
     * `contents` 原始通道（带鉴权），两条路都不通才算失败。
     */
    private fun download(ctx: Context, url: String, target: File): Boolean {
        if (downloadOnce(ctx, url, target, raw = false)) return true
        val apiUrl = url.replace("https://raw.githubusercontent.com/", "https://api.github.com/repos/")
            .replace("/main/release-assets/", "/contents/release-assets/") + "?ref=main"
        return downloadOnce(ctx, apiUrl, target, raw = true)
    }

    private fun downloadOnce(ctx: Context, url: String, target: File, raw: Boolean): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            if (raw) {
                // API 通道需要鉴权才不会被限流；用户没配令牌时也照常尝试匿名访问。
                setRequestProperty("Accept", "application/vnd.github.raw")
                GitHub.token(ctx)?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        }
        if (conn.responseCode !in 200..299) {
            false
        } else {
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            conn.disconnect()
            target.length() > 500_000L
        }
    } catch (_: Throwable) {
        false
    }
}
