package com.coda.mobileui.workspace

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览标签转常驻的判定。
 *
 * 编辑器第一次改动时要把自己的预览标签固定下来，但宿主可能找不到标签、
 * 或标签已经不是预览态。只有真的转换成功才能把「已固定」置位，
 * 否则后续改动会永远失去再次尝试的机会，标签最终被下一次打开顶掉。
 */
class PreviewPromotionTest {
    private fun tab(preview: Boolean) = WorkspaceTab("t1", WorkspaceTabs.EDITOR, JSONObject(), preview)

    @Test
    fun `preview tab is promoted`() {
        val tab = tab(preview = true)
        assertTrue(PreviewPromotion.promote(tab))
        assertFalse(tab.preview)
    }

    @Test
    fun `already pinned tab is not promoted twice`() {
        val tab = tab(preview = true)
        assertTrue(PreviewPromotion.promote(tab))
        assertFalse(PreviewPromotion.promote(tab))
    }

    @Test
    fun `promotion failure leaves the flag untouched`() {
        val tab = tab(preview = false)
        assertFalse(PreviewPromotion.promote(tab))
        assertFalse(tab.preview)
    }

    @Test
    fun `failed promotion can be retried later`() {
        val tab = tab(preview = false)
        assertFalse(PreviewPromotion.promote(tab))
        // 宿主后来把这个标签重新放回预览态，下一次改动仍然应该能提升。
        tab.preview = true
        assertTrue(PreviewPromotion.promote(tab))
    }
}