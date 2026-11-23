package com.coda.mobileui.workspace

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编辑器保存的判定与落盘（#789、#790、#791、#793）。
 *
 * 四条 Issue 的后果都是不可逆的数据丢失，而且都藏在界面时序里，
 * 放在 Activity/Page 里无法单测，所以把决策与写盘抽到这里：
 *
 * - 外部改过磁盘且本地有未保存修改时必须先确认，不能静默覆盖（#789）。
 * - 写盘必须先写同目录临时文件再改名：原地截断时进程死掉就没了原文（#790）。
 * - 编码用 REPORT 而不是默认 REPLACE：GBK 文件里的 emoji 会被换成问号（#790）。
 * - 文件被删除的标签是 detached，不是压缩包只读：脏判定要保留（#793）。
 * - 判脏不能依赖去抖后的结果：等长替换后立刻关标签会丢编辑（#791）。
 */
internal object EditorSavePolicy {
    /** 编辑器此刻的状态。 */
    data class State(
        val readOnlyPreview: Boolean = false,
        val hasTarget: Boolean = false,
        val detached: Boolean = false,
        val dirty: Boolean = false,
        val externalConflict: Boolean = false,
    )

    /** 保存前该做什么。 */
    enum class Verdict {
        /** 可以直接写。 */
        SAVE,

        /** 磁盘被外部改过且本地脏：必须先问用户。 */
        CONFIRM_OVERWRITE,

        /** 压缩包内文件，只读。 */
        BLOCKED_ARCHIVE,

        /** 目标文件已被删除：正文还在，但没有写回目标。 */
        BLOCKED_DETACHED,
    }

    fun decide(s: State): Verdict =
        when {
            s.readOnlyPreview -> Verdict.BLOCKED_ARCHIVE

            !s.hasTarget -> Verdict.BLOCKED_DETACHED

            // 只要有未处理的外部冲突就要确认，不看当前是否脏：
            // 用户可能改过之后又撤销回原文，dirty 变回 false 而冲突仍在，
            // 这时保存依然是一次真实写入，会把外部改动覆盖掉。
            s.externalConflict -> Verdict.CONFIRM_OVERWRITE

            else -> Verdict.SAVE
        }

    /**
     * 这个状态下要不要跟踪脏标记（#793）。
     *
     * detached（文件被删）仍然要跟踪：正文还在编辑框里，关标签前必须问。
     * 只有压缩包内预览是真的不可写，才不跟踪。
     */
    fun tracksDirty(s: State): Boolean = !s.readOnlyPreview

    /**
     * 判脏（#791）。
     *
     * [debouncePending] 为真表示逐字比较还没跑。此时不能沿用旧的 dirty 值：
     * 等长替换（改一个字）在 200ms 窗口内 dirty 仍是 false，
     * 用户此刻关标签就会丢掉编辑。这里直接现算。
     */
    fun isDirty(
        original: String,
        current: String,
        debouncePending: Boolean,
    ): Boolean = original != current

    /** 一次编码的结果。 */
    data class Encoded(
        val bytes: ByteArray?,
        val error: String?,
    ) {
        val ok: Boolean get() = bytes != null
    }

    /**
     * 按指定编码转字节，不可映射字符报错而不是替换（#790）。
     *
     * `String.toByteArray(charset)` 用的是 REPLACE：GBK 文件里粘一个 emoji
     * 会被静默换成 '?'，保存后原文永久损坏。这里用 REPORT，让调用方给出
     * 「改用 UTF-8 保存」这样的明确选择。
     */
    fun encode(
        text: String,
        charset: Charset,
    ): Encoded =
        try {
            val encoder =
                charset
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buf = encoder.encode(java.nio.CharBuffer.wrap(text))
            val out = ByteArray(buf.remaining())
            buf.get(out)
            Encoded(out, null)
        } catch (e: Exception) {
            Encoded(null, "${charset.name()} 无法表示部分字符（${e.javaClass.simpleName}）")
        }

    /**
     * 原子写：同目录临时文件 + 改名（#790）。
     *
     * 不用原地 writeBytes：它先截断再写，中途进程被杀或 IO 失败，
     * 原文已经没了而新内容只写了一半。改名在同一文件系统上是原子的，
     * 任何时刻读到的要么是旧全文要么是新全文。
     */
    fun writeAtomic(
        target: File,
        bytes: ByteArray,
    ): Boolean {
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".save")
        return try {
            tmp.writeBytes(bytes)
            // 临时文件是默认权限：直接改名会把可执行脚本的执行位抹掉、
            // 把 0600 的私有文件放宽。先把原文件的权限位搬过去。
            copyMode(target, tmp)
            if (tmp.renameTo(target)) {
                true
            } else {
                // 目标被目录占用等情况：改名失败，原文未被动过。
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /** 把 [from] 的读写执行位复制到 [to]；文件不存在时什么都不做。 */
    private fun copyMode(
        from: File,
        to: File,
    ) {
        if (!from.isFile) return
        runCatching {
            to.setReadable(from.canRead(), false)
            to.setWritable(from.canWrite(), false)
            // 执行位单独判：普通文本文件没有它，脚本有。
            if (from.canExecute()) to.setExecutable(true, false)
        }
    }
}
