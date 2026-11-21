package com.coda.mobileui.workspace

/**
 * 预览标签转常驻。
 *
 * 单独抽出来是因为「有没有真的提升」必须可观测：宿主找不到标签、或标签已经不是预览态时
 * 都算没提升，调用方据此决定要不要置位自己的标记，好让下一次改动还能再试一次。
 */
internal object PreviewPromotion {
    /** @return 真的从预览转成常驻时返回 true；本来就常驻或找不到标签时为 false。 */
    fun promote(tab: WorkspaceTab): Boolean {
        if (!tab.preview) return false
        tab.preview = false
        return true
    }
}
