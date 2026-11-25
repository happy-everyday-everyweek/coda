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
    private const val BRAND_NAME = "Coda"
    private const val LEGACY_NAME = "ZCode"

    /** 兼容候选：有些设备没有 /sdcard 这个链接，但它仍是最常见的写法。 */
    private const val BRAND_DIR = "/sdcard/Coda"
    private const val LEGACY_DIR = "/sdcard/ZCode"

    /**
     * 上一次解析为什么回落到应用私有目录（#93）。
     *
     * null 表示没有回落。界面据此给出可见说明，否则用户在文件管理器里
     * 找不到工作区文件，会以为文件被删了。
     */
    @Volatile
    var fallbackReason: String? = null
        private set

    /** 旧目录迁移失败时的说明；迁移成功或没有旧目录时为 null。 */
    @Volatile
    var migrationWarning: String? = null
        private set

    /**
     * 当前工作区根目录。
     *
     * 存储彻底不可写时返回私有目录的**路径**而不再抛异常（#764）：
     * 主线程有多个调用点（WorkspaceActivity.onCreate、MainActivity 的文件入口、
     * ZController.workspacePath），硬抛会把「存储降级」变成启动崩溃。
     * 真正的失败通过 [rootOrNull] 与 [storageError] 暴露，界面据此给错误提示。
     */
    fun root(ctx: Context): File = rootOrNull(ctx) ?: File(ctx.filesDir, "workspace")

    /** 存储不可用时的说明；null 表示可用。 */
    @Volatile
    var storageError: String? = null
        private set

    /**
     * 解析根目录；三处候选都不可写时返回 null 并记下 [storageError]。
     *
     * OCR #819：主线程（onCreate）与后台（WorkspaceTabs.save、ZController）会并发进来，
     * 迁移段必须互斥，否则两个线程同时对同一棵树 rename/copy，产出交错的半份拷贝。
     */
    fun rootOrNull(ctx: Context): File? = synchronized(this) { resolveRootLocked(ctx) }

    private fun resolveRootLocked(ctx: Context): File? {
        val chosen = SettingsStore.get(ctx).workspaceRoot
        if (chosen.isNotEmpty() && usable(File(chosen))) {
            fallbackReason = null
            storageError = null
            return File(chosen)
        }
        val brand = brandDir()
        if (brand != null) {
            val dir = File(brand, "workspace")
            if (usable(dir)) {
                fallbackReason = null
                storageError = null
                return dir
            }
        }
        val fallback = File(ctx.filesDir, "workspace")
        // 回退目录也要校验：忽略 mkdirs 失败会返回不存在的路径，
        // 后续写入在随机调用点抛 IOException，错误现场远离根因。
        if (!usable(fallback)) {
            // 先存进局部变量再用：storageError 是 @Volatile，另一个线程的成功解析
            // 可能在赋值与读取之间把它置回 null，`!!` 会在「防崩溃」的路径上抛 NPE。
            val why = "无法创建工作区目录：$fallback（存储不可写或空间不足）"
            storageError = why
            AppLog.e("Workspaces", why)
            return null
        }
        storageError = null
        // 回落必须留原因（#93）：私有目录卸载即清空，用户有权知道。
        fallbackReason =
            if (chosen.isNotEmpty()) {
                "选定的工作区目录不可用（可能已删除或没有写入权限），暂时使用应用私有目录。"
            } else {
                "外置存储不可用或未授予存储权限，暂时使用应用私有目录；该目录会随应用卸载一起清空。"
            }
        return fallback
    }

    /** 选定工作区根目录。 */
    fun select(
        ctx: Context,
        path: String,
    ) {
        SettingsStore.get(ctx).workspaceRoot = File(path).absolutePath
    }

    fun stateDir(root: File): File = File(root, STATE_DIR)

    fun stateFile(root: File): File = File(stateDir(root), STATE_FILE)

    /** 确保元数据目录存在；创建失败立即报错，不把失败拖到写文件时才暴露。 */
    fun ensureState(root: File): File {
        val dir = stateDir(root)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            error("无法创建工作区状态目录：$dir")
        }
        return dir
    }

    /**
     * 品牌目录候选（#93）。
     *
     * 优先用 Environment.getExternalStorageDirectory() 推导，不再只认硬编码 /sdcard；
     * 后者在部分设备上不存在。两个候选都试，任一可用即可。
     */
    private fun brandCandidates(): List<Pair<File, File>> {
        val external = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
        val pairs = mutableListOf<Pair<File, File>>()
        if (external != null) {
            pairs += File(external, BRAND_NAME) to File(external, LEGACY_NAME)
        }
        pairs += File(BRAND_DIR) to File(LEGACY_DIR)
        // 去重（OCR low #827）：多数设备上 getExternalStorageDirectory() 就是 /sdcard，
        // 与 BRAND_DIR 指向同一处。不去重时迁移分支与 usable() 会对同一路径各跑一遍
        // mkdirs/canWrite，每次 root() 都多一轮存储 IO。
        return pairs.distinctBy {
            runCatching { it.first.canonicalPath }.getOrDefault(it.first.absolutePath)
        }
    }

    private fun brandDir(): File? {
        // OCR #819：每次解析先归零，失败分支再写入；没有旧目录（或用户已手动
        // 移走）时不能继续展示上一轮的迁移失败警告。调用方持有 this 锁。
        migrationWarning = null
        for ((brand, legacy) in brandCandidates()) {
            val step = WorkspaceRoots.migrationStep(brandExists = brand.exists(), legacyExists = legacy.exists())
            if (step == WorkspaceRoots.Migration.MOVE) {
                // 迁移失败不能静默（#93）：老工作区的标签与文件会留在原地不再被读取，
                // 用户看到的是「文件全没了」。rename 失败先试拷贝，仍失败就留下可读警告。
                val renamed = runCatching { legacy.renameTo(brand) }.getOrDefault(false)
                if (renamed) {
                    // OCR #819：成功后必须清掉旧警告，否则第一对候选失败、
                    // 第二对成功后，restoreTabs 仍展示过期的「文件未迁移」。
                    migrationWarning = null
                }
                if (!renamed) {
                    val copied = runCatching { legacy.copyRecursively(brand, overwrite = false) }.getOrDefault(false)
                    if (copied) migrationWarning = null
                    if (!copied) {
                        // 半份拷贝必须清掉（#765）：留着它，下次 `!brand.exists()` 为假
                        // 就再也不会重试迁移，工作区静默指向不完整的副本。
                        // 这里 brand 在迁移前确认过不存在，删它不会碰到用户已有数据。
                        WorkspaceRoots.discardPartial(brand)
                        migrationWarning =
                            "旧工作区未能迁移：${legacy.absolutePath} → ${brand.absolutePath}。" +
                            "旧文件仍在原位置，可在文件页手动移动。"
                        // 迁移彻底失败时优先用完整的旧目录（#769），而不是让 usable 的
                        // mkdirs 建出一个空 brand 把旧数据永久孤立。
                        WorkspaceRoots.afterFailedMigration(legacy, brand)?.let { return it }
                        continue
                    }
                }
            }
            // 有旧目录在等着迁移时，判定不能带创建副作用（#769）：mkdirs 会凭空造出
            // 空 brand，让下次 root() 认为「已迁移」而把完整的旧数据永久孤立。
            // 但没有旧目录时（全新安装的常态）必须照常创建，否则品牌目录永远建不出来，
            // 每台设备都静默落到应用私有目录——那里的内容会随卸载一起清空。
            if (legacy.exists()) {
                if (WorkspaceRoots.existsUsable(brand)) return brand
            } else if (WorkspaceRoots.ensureUsable(brand)) {
                return brand
            }
        }
        return null
    }

    /**
     * 目录可用性判定：存在（或能创建）且可写。
     *
     * 名字承诺的是纯判断加必要创建，但只读目录以前也能通过 isDirectory 检查，
     * 之后所有写入都在运行期静默失败。canWrite 把这类根在入口处拒掉。
     * 捕获 Exception 而非 Throwable：OOM 等致命错误不该被当成「目录不可用」。
     */
    private fun usable(dir: File): Boolean =
        try {
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        } catch (_: Exception) {
            false
        }
}
