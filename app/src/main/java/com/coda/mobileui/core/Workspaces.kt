package com.coda.mobileui.core

import android.content.Context
import com.coda.mobileui.SettingsStore
import java.io.File

/**
 * 工作区目录的解析与元数据落点。
 *
 * 根目录由用户选定，未选定时使用设备上的默认位置。工作区自己的状态都放在根目录下的
 * [STATE_DIR] 里，与文件放在一起，换设备时只要带上这个目录就能恢复。
 */
object Workspaces {

    /** 工作区元数据目录名。 */
    const val STATE_DIR = ".coda"

    private const val STATE_FILE = "workspace.json"
    private const val BRAND_DIR = "/sdcard/Coda"
    private const val LEGACY_DIR = "/sdcard/ZCode"

    /** 当前工作区根目录，保证存在。 */
    fun root(ctx: Context): File {
        val chosen = SettingsStore.get(ctx).workspaceRoot
        if (chosen.isNotEmpty() && usable(File(chosen))) return File(chosen)
        val brand = brandDir()
        if (brand != null) {
            val dir = File(brand, "workspace")
            if (usable(dir)) return dir
        }
        val fallback = File(ctx.filesDir, "workspace")
        fallback.mkdirs()
        return fallback
    }

    /** 选定工作区根目录。 */
    fun select(ctx: Context, path: String) {
        SettingsStore.get(ctx).workspaceRoot = File(path).absolutePath
    }

    fun stateDir(root: File): File = File(root, STATE_DIR)

    fun stateFile(root: File): File = File(stateDir(root), STATE_FILE)

    /** 确保元数据目录存在。 */
    fun ensureState(root: File): File {
        val dir = stateDir(root)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun brandDir(): File? {
        val brand = File(BRAND_DIR)
        val legacy = File(LEGACY_DIR)
        if (!brand.exists() && legacy.exists()) {
            runCatching { legacy.renameTo(brand) }
        }
        return if (usable(brand)) brand else null
    }

    private fun usable(dir: File): Boolean = try {
        dir.isDirectory || dir.mkdirs()
    } catch (_: Throwable) {
        false
    }
}