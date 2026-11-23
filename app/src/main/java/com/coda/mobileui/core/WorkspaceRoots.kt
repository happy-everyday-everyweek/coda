package com.coda.mobileui.core

import java.io.File

/**
 * 工作区根目录的选择与旧目录迁移决策（#764、#765、#769）。
 *
 * 三条 Issue 都出在「失败之后怎么办」上，而且都只在存储异常的设备上出现：
 *
 * - 创建失败直接 error() 抛异常，主线程调用点没接住，启动崩溃（#764）。
 *   这里改成返回可空结果 + 原因，由调用方决定降级 UI。
 * - 拷贝半途失败留下不完整 brand 目录，下次 `!brand.exists()` 为假不再重试，
 *   工作区静默指向半份拷贝（#765）。失败必须清掉残留。
 * - 迁移彻底失败时 `usable(brand)` 的 mkdirs 副作用建出空目录，
 *   legacy 数据被永久孤立（#769）。判定必须无副作用，失败优先回 legacy。
 */
internal object WorkspaceRoots {
    /** 一次根目录解析的结果。 */
    data class Picked(
        val dir: File?,
        /** 回落到私有目录的原因；null 表示没回落。 */
        val fallbackReason: String?,
        /** 迁移失败的说明；null 表示没有失败。 */
        val migrationWarning: String?,
    ) {
        val ok: Boolean get() = dir != null
    }

    /**
     * 目录是否可用，**不带创建副作用**（#769）。
     *
     * 用于「这个目录已经存在且能写吗」的判断。要不要创建由调用方显式决定，
     * 否则一次失败的判定会留下空目录，把后续重试的机会抹掉。
     */
    fun existsUsable(dir: File): Boolean =
        try {
            dir.isDirectory && dir.canWrite()
        } catch (_: Exception) {
            false
        }

    /** 存在或能创建，且可写。只在真的要落地时用。 */
    fun ensureUsable(dir: File): Boolean =
        try {
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        } catch (_: Exception) {
            false
        }

    /**
     * 迁移一步的决定。
     *
     * @param brandExists 目标目录是否已存在（判定无副作用）
     * @param legacyExists 旧目录是否存在
     */
    enum class Migration {
        /** 什么都不用做。 */
        NONE,

        /** 该尝试把 legacy 改名成 brand。 */
        MOVE,
    }

    fun migrationStep(
        brandExists: Boolean,
        legacyExists: Boolean,
    ): Migration = if (!brandExists && legacyExists) Migration.MOVE else Migration.NONE

    /**
     * 改名与拷贝都失败后该用哪个目录。
     *
     * 优先 legacy（数据完整可用），其次 brand（可能已部分可用），都没有才返回 null。
     * 绝不能返回一个刚被 mkdirs 建出来的空 brand：那会让完整的旧数据永久不可见。
     */
    fun afterFailedMigration(
        legacy: File,
        brand: File,
    ): File? =
        when {
            existsUsable(legacy) -> legacy
            existsUsable(brand) -> brand
            else -> null
        }

    /**
     * 拷贝失败后清理残留（#765）。
     *
     * 只在「拷贝失败且 brand 是我们这次建出来的」时删。调用方必须先确认
     * brand 在迁移前不存在，否则会把用户已有的目录删掉。
     */
    fun discardPartial(brand: File): Boolean = runCatching { brand.deleteRecursively() }.getOrDefault(false)
}
