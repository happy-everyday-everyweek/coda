package com.coda.mobileui.workspace

/**
 * 增量高亮的缓存键与移除阈值（#778、#779、#781）。
 *
 * 增量复用（#495）省下了每敲一键重建数千 span 的开销，但它的正确性依赖
 * 「上一轮的 token 坐标仍与当前 span 对齐」。三个前提缺一不可：
 *
 * - 同一个文本缓冲区。高亮器是单例，多个编辑器标签可能同语言，
 *   不区分 Editable 就会把别人的 token 当成自己的历史（#781）。
 * - 同一套规则实例与**同一份配色**。文本没变时 diff 的 remove 为空，
 *   保留的前缀 span 会留着旧颜色，切主题后文档半新半旧（#779）。
 * - 长度没变。用户删除字符后 Editable 会把后续 span 左移，
 *   而 lastTokens 记的还是旧坐标，拿它当移除阈值会漏掉已移位的陈旧 span，
 *   新旧 span 在同一区间叠加（#778）。
 *
 * 任何一项不满足就整篇重建：正确性优先，重建的代价只是一次线性扫描。
 */
internal object HighlightCache {
    /**
     * 一次扫描的身份。[buffer] 用 Editable 引用本身（按引用比较）。
     *
     * [revision] 是编辑代号，由调用方的 TextWatcher 递增。不能只用长度：
     * 覆盖选区、粘贴替换、输入法组词、撤销回同长度都会让四个字段完全相同，
     * 而 `Editable.replace` 会把落在替换区内的 SPAN_EXCLUSIVE_EXCLUSIVE span 全部丢掉——
     * 重扫得到相同 token、diff.remove 为空、什么都不补，那段就永久失去高亮。
     */
    class Key(
        buffer: Any,
        val rules: LanguageRules,
        val palette: CodePalette,
        val length: Int,
        val revision: Long = 0L,
    ) {
        /**
         * 弱引用持有缓冲区。
         *
         * 高亮器是单例，强引用会把最后一个编辑器的整份文本（上限 20 万字符）
         * 连同 token 列表一直钉在内存里——标签关掉也回收不了。
         * 弱引用不影响身份比较：回收后 get() 为 null，自然判为不可复用。
         */
        private val bufferRef = java.lang.ref.WeakReference(buffer)

        fun sameBuffer(other: Any): Boolean = bufferRef.get() === other

        /** 两个键是否指向同一个仍存活的缓冲区；任一侧被回收都判否。 */
        fun sameAs(other: Key): Boolean {
            val mine = bufferRef.get() ?: return false
            return other.sameBuffer(mine)
        }
    }

    /** 上一轮的历史能否用于本次增量。 */
    fun reusable(
        previous: Key?,
        current: Key,
    ): Boolean {
        if (previous == null) return false
        return previous.sameAs(current) &&
            previous.rules === current.rules &&
            previous.palette == current.palette &&
            previous.length == current.length &&
            previous.revision == current.revision
    }

    // 曾用 31 进制内容指纹判「等长编辑」，已废弃：31 在 mod 2^64 下可逆，
    // "Aa" 与 "BB" 这类相邻差值满足 d2=-31*d1 的文本**必然**碰撞（OCR medium），
    // 且节流窗口内 A→B→A 指纹不变。正确设计是调用方 TextWatcher 的单调计数，
    // 由 Key.revision 承载，见 CodeHighlighter.apply 的 editRevision 参数。

    /**
     * 从哪个偏移开始移除既有 span。
     *
     * 返回 0 表示整篇移除。长度变过或历史不可复用时必须整篇来：
     * span 的实际位置已经与 [firstChanged]（来自旧 token 坐标）不在同一坐标系里。
     */
    fun removeFrom(
        previousLength: Int,
        currentLength: Int,
        firstChanged: Int,
        reusable: Boolean = true,
    ): Int {
        if (!reusable) return 0
        if (previousLength != currentLength) return 0
        return firstChanged.coerceIn(0, currentLength)
    }
}
