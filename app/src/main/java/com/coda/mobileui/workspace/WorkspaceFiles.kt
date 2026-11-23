package com.coda.mobileui.workspace

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.coda.mobileui.R
import com.coda.mobileui.core.AppLog
import java.io.File

/**
 * 把工作区文件交给别的应用打开时用的 URI 入口。
 *
 * Android N 起不允许把 `file://` 跨应用传递（会抛 FileUriExposedException），
 * 必须走 FileProvider 的 content:// URI。
 *
 * 授权分工：本对象只生成 URI，**不授予读权限**。授权由调用方在构造 Intent 时加
 * FLAG_GRANT_READ_URI_PERMISSION（或显式 grantUriPermission）完成。早先的注释
 * 声称这里会「显式授予读权限」，与实现不符（#553）。
 */
object WorkspaceFiles {
    /** FileProvider 的 authority，与 AndroidManifest 里的声明保持一致。 */
    private fun authority(context: Context): String = "${context.packageName}.workspacefiles"

    /** URI 生成失败的两种原因，调用方据此决定提示文案（#554）。 */
    sealed class Result {
        data class Ok(val uri: Uri) : Result()

        /** 文件不在 file_paths.xml 声明的根内：换个方式打开即可。 */
        object OutOfScope : Result()

        /** Provider 配置/运行期错误：所有文件都会失败，需要日志与明确提示。 */
        data class ProviderError(val reason: String) : Result()
    }

    /**
     * 可共享根目录，直接从 `res/xml/file_paths.xml` 解析（#552）。
     *
     * 原先这里硬编码一份 roots，与 xml 各自维护，两边一致只是巧合：
     * 改 xml 忘改代码就会「canShare 说行、FileProvider 抛异常」，反之则白白拒绝。
     * 现在以 xml 为唯一事实来源，按标签映射到对应的实际目录。
     * 解析结果按进程缓存（OCR #821）：声明在运行期不变，canShare/explain
     * 落在文件打开路径上，不能每次都重开 XmlResourceParser + canonicalPath。
     */
    @Volatile private var rootsCache: List<File>? = null

    private fun sharedRoots(context: Context): List<File> {
        rootsCache?.let { return it }
        val app = context.applicationContext ?: context
        val out = mutableListOf<File>()
        val parser = runCatching { app.resources.getXml(R.xml.file_paths) }.getOrNull()
        if (parser == null) {
            // fail-closed（OCR #821）：解析失败时退回更宽的内置列表，会重新引入
            // 「canShare 说行、FileProvider 抛异常」的分裂。宁可全部拒绝。
            AppLog.w("WorkspaceFiles", "无法打开 file_paths.xml，按不可共享处理")
            return emptyList()
        }
        val parsed =
            runCatching {
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        // path="." 表示整个根；子路径则拼到根后面。
                        // 只剥斜杠与显式的 "./" 前缀：trim('.') 会把 `.config`
                        // 这类合法子路径误剥成 config，造成错误的 OutOfScope。
                        var sub = parser.getAttributeValue(null, "path").orEmpty().trim('/')
                        while (sub.startsWith("./")) sub = sub.substring(2)
                        if (sub == ".") sub = ""
                        val base =
                            when (parser.name) {
                                "files-path" -> app.filesDir
                                "cache-path" -> app.cacheDir
                                "external-path" -> android.os.Environment.getExternalStorageDirectory()
                                "external-files-path" -> app.getExternalFilesDir(null)
                                "external-cache-path" -> app.externalCacheDir
                                "root-path" -> File("/")
                                else -> null
                            }
                        if (base != null) {
                            val f = if (sub.isEmpty()) base else File(base, sub)
                            out += runCatching { f.canonicalFile }.getOrDefault(f)
                        }
                    }
                    event = parser.next()
                }
            }
        // XmlResourceParser 不是 AutoCloseable（API 26 起才有 close 的默认实现），显式关。
        runCatching { parser.close() }
        if (parsed.isFailure) {
            AppLog.w("WorkspaceFiles", "解析 file_paths.xml 失败，按不可共享处理：${parsed.exceptionOrNull()?.message}")
            return emptyList()
        }
        // 解析成功才建缓存；失败不缓存，让下次调用还能重试。
        val roots = out.toList()
        rootsCache = roots
        return roots
    }

    /**
     * 判断一个真实路径是否落在已声明的可共享范围内。
     *
     * FileProvider 对未声明的根会直接抛异常，提前判断能让调用方安静地回退到
     * "换个方式打开"，而不是崩一次再被 catch 住。
     */
    fun canShare(
        context: Context,
        file: File,
    ): Boolean {
        val target = runCatching { file.canonicalPath }.getOrNull() ?: return false
        // sharedRoots 返回的已是 canonical 根，这里不再逐个做 syscall。
        return sharedRoots(context).any { root ->
            val base = root.path
            target == base || target.startsWith(base + File.separator)
        }
    }

    /** 生成可跨应用读取的 URI；失败返回 null（需要原因时用 [explain]）。 */
    fun uriFor(
        context: Context,
        file: File,
    ): Uri? = (explain(context, file) as? Result.Ok)?.uri

    /**
     * 与 [uriFor] 相同，但把失败原因区分开（#554）。
     *
     * 原先一律 getOrNull()，「文件不在范围内」与「Provider 配置错误」都变成 null，
     * 后者会让所有文件都分享不了却没有任何日志线索。
     */
    fun explain(
        context: Context,
        file: File,
    ): Result {
        if (!canShare(context, file)) return Result.OutOfScope
        return try {
            Result.Ok(FileProvider.getUriForFile(context, authority(context), file))
        } catch (e: Exception) {
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.e("WorkspaceFiles", "FileProvider 生成 URI 失败（authority=${authority(context)}）：$reason")
            Result.ProviderError(reason)
        }
    }
}
