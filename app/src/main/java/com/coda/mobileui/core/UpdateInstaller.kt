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
object UpdateInstaller {

    /** 下载进度。 */
    fun interface Progress {
        fun onBytes(done: Long, total: Long)
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
    ): File? {
        if (result.apkUrl.isEmpty() || result.versionCode <= 0) {
            onLog("更新信息不完整，无法下载")
            return null
        }

        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        // 文件名带版本号，避免上一版的半成品被当成这一版。
        val target = File(dir, "coda-${result.versionCode}.apk")
        if (target.isFile && target.length() > 0) {
            // 已下过且校验通过就直接用，省一次几十 MB 的重下。
            val spec = specFor(result)
            if (AssetFetcher.verify(target, spec)) {
                onLog("已有下载好的更新包：${target.name}")
                return target
            }
            onLog("旧的下载包校验不通过，重新下载")
            runCatching { target.delete() }
        }

        val ok = AssetFetcher.ensure(
            ctx = ctx,
            target = target,
            spec = specFor(result),
            onProgress = onProgress?.let { AssetFetcher.Progress { d, t -> it.onBytes(d, t) } },
            onLog = onLog,
        )
        if (!ok) return null

        // 从清单拿到的更新包一定带哈希；没有哈希说明走的是 API 兜底，
        // 这种情况只保证长度，安装前仍由系统校验签名。
        if (result.sha256 == null) {
            onLog("注意：本次更新包没有哈希，只能依赖系统校验签名")
        }
        return target
    }

    private fun specFor(result: UpdateChecker.Result) = AssetFetcher.Spec(
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
    fun install(ctx: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        return try {
            val uri = FileProvider.getUriForFile(
                ctx,
                "${BuildConfig.APPLICATION_ID}.workspacefiles",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 清理旧的更新包。
     *
     * 升级完成后旧包就没用了，但它可能占几十 MB。只保留 [keepVersionCode]
     * 对应的那个，其余删掉。
     */
    fun cleanup(ctx: Context, keepVersionCode: Int) {
        val dir = File(ctx.filesDir, "updates")
        if (!dir.isDirectory) return
        val keep = "coda-$keepVersionCode.apk"
        dir.listFiles()?.forEach { f ->
            if (f.name != keep) runCatching { f.delete() }
        }
    }

    /** 当前设备架构是否受支持。目前只出 arm64 包，这条用于给出准确提示。 */
    fun isSupportedAbi(): Boolean {
        val abis = Build.SUPPORTED_ABIS ?: return true
        return abis.any { it.contains("arm64") || it.contains("aarch64") }
    }
}