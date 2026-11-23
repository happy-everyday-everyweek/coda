package com.coda.mobileui.core

/**
 * 一次提示词读取的结果。
 *
 * [text] 为 null 表示读失败，与「文件不存在」（text 为空串）严格区分（#741）：
 * 前者是瞬时故障，把编辑框置空再保存会造成永久丢失；后者是正常的「还没设置」。
 */
data class PromptRead(
    val text: String?,
    val error: String?,
) {
    val ok: Boolean get() = error == null && text != null
}

/**
 * 提示词编辑器的加载/保存闸门（#739、#741）。
 *
 * 界面只有两种可保存状态：磁盘内容已成功读到并回填，或者用户自己保存过一次。
 * 其余一律关闸——加载未完成、读失败、加载期间用户已经输入。
 * 抽成独立对象是因为这三条分支都只在真机的时序缝隙里出现，
 * 放在 Activity 里无法单测，而它们的后果都是整份提示词被覆盖。
 */
class PromptLoadGate {
    /** 进入页面时的磁盘内容，用于判断有没有未保存的修改。 */
    var initial: String = ""
        private set

    /** 是否允许保存。 */
    var canSave: Boolean = false
        private set

    /** 加载结果的处理决定。 */
    data class Decision(
        /** 非 null 表示应把这段文本回填到输入框。 */
        val applyText: String?,
        /** 需要提示用户重新加载：加载期间的输入不能与磁盘内容自动合并。 */
        val needsReload: Boolean,
        val error: String?,
    )

    /**
     * 处理一次读取结果。
     *
     * [typed] 是此刻输入框里的内容。非空说明用户在异步加载期间已经打了字，
     * 这时既不回填也不开闸：回填会冲掉用户输入，开闸会让保存把整份提示词
     * 覆盖成那几个字。
     */
    fun onLoaded(
        read: PromptRead,
        typed: String,
    ): Decision {
        if (!read.ok) {
            canSave = false
            return Decision(applyText = null, needsReload = typed.isNotEmpty(), error = read.error ?: "读取失败")
        }
        val text = read.text ?: ""
        // 基线无论如何都记下磁盘内容：否则「放弃修改」会拿空串比对，
        // 用户加载期间打的字会被当成未修改而静默丢掉。
        initial = text
        if (typed.isNotEmpty()) {
            canSave = false
            return Decision(applyText = null, needsReload = true, error = null)
        }
        canSave = true
        return Decision(applyText = text, needsReload = false, error = null)
    }

    /** 保存成功：新内容成为新的基线。 */
    fun onSaved(text: String) {
        initial = text
        canSave = true
    }

    /** 保存失败：闸门保持原状，不能因为「试过一次」就认为内容可信。 */
    fun onSaveFailed() {
        // 有意不动 canSave：已加载成功的内容仍然可编辑重试。
    }
}
