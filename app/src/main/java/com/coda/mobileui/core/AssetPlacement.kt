package com.coda.mobileui.core

import java.io.File

/**
 * 把下载好并校验通过的临时文件放到最终位置。
 *
 * `renameTo` 会在跨设备、目标是目录、权限不足、目标已存在且只读等情况下返回 false。
 * 之前这些失败被静默吞掉：已下载并校验通过的文件被删掉、镜像不记分、错误也不进日志，
 * 最后只剩一句「所有源都失败：」而详情是空的。
 *
 * 这里把「就位」当成一个独立步骤：先试重命名，不行就复制再删源，两条都失败才报错。
 */
internal object AssetPlacement {
    /** 就位结果。失败时带上可直接写进日志的原因。 */
    sealed class Result {
        /** 成功，并给出最终文件的字节数（重命名后不能再读临时文件的长度）。 */
        data class Placed(
            val bytes: Long,
        ) : Result()

        data class Failed(
            val reason: String,
        ) : Result()
    }

    /**
     * 把 [tmp] 放到 [target]。
     *
     * 字节数一律从 [target] 读：`tmp` 在重命名成功后已不存在，`tmp.length()` 会返回 0，
     * 而 MirrorScoreboard 只在 bytes > 0 时累加吞吐，记 0 等于让测速样本全部作废。
     */
    fun place(
        tmp: File,
        target: File,
    ): Result {
        if (!tmp.isFile) return Result.Failed("临时文件不存在")
        // 目标是目录时 renameTo 必然失败，先说清楚而不是归因到校验。
        if (target.isDirectory) return Result.Failed("目标路径是目录")
        if (tmp.renameTo(target)) {
            return Result.Placed(target.length())
        }
        // 跨设备（临时目录与目标不在同一挂载点）时重命名会失败，复制是可行的回退。
        // 复制先写目标同目录的 staging 再 rename（OCR #823）：直接写 target
        // 会让进程被杀/并发 ensure 的 step-1 校验看到半截文件，无 sha256 的
        // spec 甚至能把截断文件当成品。失败只删自己的 staging，绝不动已有 target。
        val staging = File(target.parentFile, target.name + "." + System.nanoTime() + ".staging")
        val copyError =
            runCatching {
                try {
                    tmp.inputStream().use { input ->
                        staging.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    if (staging.length() != tmp.length()) throw java.io.IOException("复制长度不符")
                    if (!staging.renameTo(target)) throw java.io.IOException("替换 ${target.name} 失败")
                } finally {
                    if (staging.exists()) runCatching { staging.delete() }
                }
            }.exceptionOrNull()
        if (copyError == null) {
            runCatching { tmp.delete() }
            return Result.Placed(target.length())
        }
        return Result.Failed(
            "重命名与复制都失败（${tmp.absolutePath} → ${target.absolutePath}）：${copyError.message ?: copyError.javaClass.simpleName}",
        )
    }
}
