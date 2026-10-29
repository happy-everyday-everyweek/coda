package com.coda.mobileui.workspace

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把工作区文件交给别的应用打开时用的 URI 入口。
 *
 * Android N 起不允许把 `file://` 跨应用传递（会抛 FileUriExposedException），
 * 必须走 FileProvider 的 content:// URI，并显式授予读权限。
 */
object WorkspaceFiles {
    /** FileProvider 的 authority，与 AndroidManifest 里的声明保持一致。 */
    private fun authority(context: Context): String = "${context.packageName}.workspacefiles"

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
        val roots =
            listOfNotNull(
                context.filesDir,
                context.getExternalFilesDir(null),
                android.os.Environment.getExternalStorageDirectory(),
            )
        return roots.any { root ->
            val base = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == base || target.startsWith(base + File.separator)
        }
    }

    /** 生成可跨应用读取的 URI；不在可共享范围内返回 null。 */
    fun uriFor(
        context: Context,
        file: File,
    ): Uri? {
        if (!canShare(context, file)) return null
        return runCatching {
            FileProvider.getUriForFile(context, authority(context), file)
        }.getOrNull()
    }
}
