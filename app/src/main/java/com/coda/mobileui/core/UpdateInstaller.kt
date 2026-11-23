package com.coda.mobileui.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.coda.mobileui.BuildConfig
import java.io.File

/**
 * 应用内更新的「取」这一半：下载 APK 并交给系统安装器。
 *
 * 下载复用 [AssetFetcher]：它已经带了 ping 筛活、测速排序、逐源 sha256 校验和
 * 「校验不过就换源」的降级。这里不重复实现，只负责把「更新」翻译成它的 Spec。
 *
 * 一个刻意的取舍：清单给了 sha256 就强制校验，校验不过一律不安装。
 * 镜像返回被改写的内容是真实发生过的事故（大小完全相同、内容不同、装上去报
 * 「没有签名」），更新通道比普通资产更该守住这条线。
 */
internal object UpdateInstaller {
    /** 下载进度。 */
    fun interface Progress {
        fun onBytes(
            done: Long,
            total: Long,
        )
    }

    /**
     * 下载更新包到应用私有目录。
     *
     * 放私有目录而不是 Download：安装完就删，不占用户可见空间，也不需要存储权限。
     * FileProvider 已经把 files-path 纳入可共享范围（见 res/xml/file_paths.xml），
     * 所以这里能直接把它交给安装器。
     *
     * 同步执行，必须放后台线程。
     */
    fun download(
        ctx: Context,
        result: UpdateChecker.Result,
        onProgress: Progress? = null,
        onLog: (String) -> Unit = {},
        cancel: Downloader.Cancel? = null,
    ): File? {
        if (result.apkUrl.isEmpty() || result.versionCode <= 0) {
            onLog("更新信息不完整，无法下载")
            return null
        }
        // 更新包地址来自清单/接口，必须限制成 https 且带主机名：
        // 允许 http 或相对地址等于把安装包交给中间人替换。
        if (!isTrustedUrl(result.apkUrl)) {
            onLog("更新地址不合法，已拒绝下载：${result.apkUrl.take(120)}")
            return null
        }

        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        // 文件名带版本号，避免上一版的半成品被当成这一版。
        val target = File(dir, "coda-${result.versionCode}.apk")
        // 「已就位先校验、坏了删掉重下」是 AssetFetcher.ensure 的第 1 步，
        // 这里不再重复实现一遍缓存判断（两处各写一份必然漂移），spec 也只构造一次。
        val spec = specFor(result)
        val ok =
            try {
                AssetFetcher.ensure(
                    ctx = ctx,
                    target = target,
                    spec = spec,
                    onProgress = onProgress?.let { AssetFetcher.Progress { d, t -> it.onBytes(d, t) } },
                    onLog = onLog,
                    cancel = cancel,
                )
            } catch (e: java.util.concurrent.CancellationException) {
                // 取消是正常退出路径（OCR r9 medium）：不能让异常炸掉整个
                // Bg.run 块，否则 UI 完成回调被跳过，面板永远停在「下载中」。
                onLog("下载已取消")
                return null
            }
        if (!ok) return null

        // 从清单拿到的更新包一定带哈希；没有哈希说明走的是 API 兜底。
        // 这时不能只判「文件非空」就交给安装器：截断的 APK 同样非空，
        // 至少要核对声明大小与 APK 结构（能打开 zip 且含 AndroidManifest）。
        if (result.sha256 == null) {
            val sizeOk = result.apkSize <= 0L || target.length() == result.apkSize
            val zipOk =
                runCatching {
                    java.util.zip
                        .ZipFile(target)
                        .use { z -> z.getEntry("AndroidManifest.xml") != null }
                }.getOrDefault(false)
            if (!sizeOk || !zipOk) {
                onLog("更新包校验失败：缺少哈希，且大小或包结构不符")
                runCatching { target.delete() }
                return null
            }
            onLog("注意：本次更新包没有哈希，已按大小与包结构校验，安装签名仍由系统校验")
        }
        return target
    }

    /**
     * 更新地址是否可信。
     *
     * 只接受 https 且带主机名的绝对地址；`file:`、`content:`、`http:` 与相对路径一律拒绝，
     * 否则镜像被劫持时可以直接把下载目标换成任意地址。
     */
    private fun isTrustedUrl(url: String): Boolean {
        val uri =
            runCatching { java.net.URI(url.trim()) }.getOrElse { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host ?: return false
        return host.isNotBlank() && !host.contains(' ')
    }

    private fun specFor(result: UpdateChecker.Result) =
        AssetFetcher.Spec(
            // 更新包不在 APK 内，assetPath 给一个必然不存在的名字，让 AssetFetcher 跳过释放步骤。
            assetPath = "updates/not-bundled",
            remoteUrl = result.apkUrl,
            sha256 = result.sha256,
            size = result.apkSize,
        )

    /**
     * 交给系统安装器。
     *
     * REQUEST_INSTALL_PACKAGES 权限在 Android 8+ 需要用户先允许「安装未知应用」，
     * 没允许时系统会弹自己的引导页，这里不用额外判断——判断了也拦不住，系统
     * 自己的流程更准确。
     */
    fun install(
        ctx: Context,
        apk: File,
    ): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        // 安装前比对签名者：清单里的 sha256 与 apkUrl 同源，被劫持的镜像能一起改写，
        // 只有「签名证书与本应用一致」这条不来自网络的约束能挡住伪造更新。
        val mine = ownSigner(ctx)
        val theirs = archiveSigner(ctx, apk)
        if (!UpdateTrust.signerMatches(mine, theirs)) {
            AppLog.w("Update", "更新包签名与本应用不一致，已拒绝安装")
            runCatching { apk.delete() }
            return false
        }
        return try {
            val uri =
                FileProvider.getUriForFile(
                    ctx,
                    "${BuildConfig.APPLICATION_ID}.workspacefiles",
                    apk,
                )
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            // 不能静默：无安装器（ActivityNotFound）、FileProvider 配置错
            // （IllegalArgument）、URI 授权失败（Security）都要留下可排查的日志，
            // 否则用户侧只剩「点了没反应」。Error 不拦，让它照常传播。
            AppLog.w("Update", "启动安装器失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 本应用当前的签名证书 SHA-256。
     *
     * 以运行时自身的证书为基准，dev 与 release 各自成立，不需要构建期注入常量；
     * 取不到时返回 null，调用方据此拒绝安装。
     */
    private fun ownSigner(ctx: Context): String? =
        runCatching {
            // GET_SIGNING_CERTIFICATES/signingInfo 仅 API 28+（#754）：
            // minSdk 26，Android 8.x 上标志被忽略、signingInfo 为 null，
            // 两个指纹都取不到 → 所有更新被拒。低版本退回 GET_SIGNATURES。
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info =
                    ctx.packageManager.getPackageInfo(
                        ctx.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                signerOf(info.signingInfo)
            } else {
                @Suppress("DEPRECATION")
                val info =
                    ctx.packageManager.getPackageInfo(
                        ctx.packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES,
                    )
                @Suppress("DEPRECATION")
                fingerprintOf(info.signatures)
            }
        }.getOrNull()

    /** 待安装 APK 的签名证书 SHA-256。 */
    private fun archiveSigner(
        ctx: Context,
        apk: File,
    ): String? =
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info =
                    ctx.packageManager.getPackageArchiveInfo(
                        apk.absolutePath,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
                    ) ?: return null
                signerOf(info.signingInfo)
            } else {
                @Suppress("DEPRECATION")
                val info =
                    ctx.packageManager.getPackageArchiveInfo(
                        apk.absolutePath,
                        android.content.pm.PackageManager.GET_SIGNATURES,
                    ) ?: return null
                @Suppress("DEPRECATION")
                fingerprintOf(info.signatures)
            }
        }.getOrNull()

    private val hexDigits = "0123456789ABCDEF".toCharArray()

    /**
     * 签名数组 → 第一个签名者的证书 SHA-256 指纹（大写 hex）。
     *
     * ownSigner/archiveSigner 的 API<28 分支与 signerOf 共用这一份
     * （OCR r8 low）：查表拼 hex，不用逐字节 String.format——那有
     * locale 敏感性且每字节一次格式化开销。
     */
    private fun fingerprintOf(signatures: Array<android.content.pm.Signature>?): String? {
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(cert)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hexDigits[v ushr 4]).append(hexDigits[v and 0x0F])
        }
        return sb.toString()
    }

    /** 从 signingInfo 取第一个签名者的证书指纹；多签名者时只认第一个（本应用单签名）。 */
    private fun signerOf(signingInfo: android.content.pm.SigningInfo?): String? =
        fingerprintOf(signingInfo?.apkContentsSigners)

    /**
     * 清理旧的更新包。
     *
     * 升级完成后比 [keepVersionCode] 旧的包没用了，但它可能占几十 MB。
     * 比当前版本新的包必须保留：那是「已下载、待安装」的更新，
     * 只保留当前版本会把下载缓存整个清空，复用优化永远不生效。
     */
    fun cleanup(
        ctx: Context,
        keepVersionCode: Int,
    ) {
        val dir = File(ctx.filesDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (!shouldKeepApk(f.name, keepVersionCode)) runCatching { f.delete() }
        }
    }

    /** 更新包文件名格式；对象级常量只编译一次。 */
    private val APK_NAME = Regex("^coda-(\\d+)\\.apk$")

    /** 更新包文件是否该保留：命名合规且版本号 >= 当前已安装版本。 */
    internal fun shouldKeepApk(
        name: String,
        keepVersionCode: Int,
    ): Boolean {
        val match = APK_NAME.matchEntire(name) ?: return false
        val version = match.groupValues[1].toIntOrNull() ?: return false
        return version >= keepVersionCode
    }

    /** 当前设备架构是否受支持。目前只出 arm64 包，这条用于给出准确提示。 */
    fun isSupportedAbi(): Boolean {
        val abis = Build.SUPPORTED_ABIS ?: return true
        return abis.any { it.contains("arm64") || it.contains("aarch64") }
    }
}
