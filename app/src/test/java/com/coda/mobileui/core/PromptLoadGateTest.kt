package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词编辑器的加载与保存闸门（#739、#741）。
 *
 * 这里钉住两类真实的数据丢失：加载期间用户打了字，旧实现照样置 loaded=true，
 * 保存就把整份提示词覆盖成那几个字；读失败返回空串与「文件不存在」不可区分，
 * 于是一个读不出来的提示词可以被保存为空，把瞬时故障变成永久丢失。
 */
class PromptLoadGateTest {
    @Test
    fun `还没加载完不允许保存`() {
        val g = PromptLoadGate()
        assertFalse(g.canSave)
    }

    @Test
    fun `读到内容且用户没动过时可以保存并回填`() {
        val g = PromptLoadGate()
        val d = g.onLoaded(PromptRead(text = "disk body", error = null), typed = "")
        assertEquals("disk body", d.applyText)
        assertEquals("disk body", g.initial)
        assertTrue(g.canSave)
    }

    @Test
    fun `加载期间用户打了字就不回填也不开保存闸门`() {
        val g = PromptLoadGate()
        val d = g.onLoaded(PromptRead(text = "disk body", error = null), typed = "我打的字")
        assertNull("不能把磁盘内容盖到用户输入上", d.applyText)
        assertFalse("闸门必须继续关着，否则保存会覆盖整份提示词", g.canSave)
        assertTrue("要提示用户重新加载", d.needsReload)
    }

    @Test
    fun `加载期间的输入不算已修改`() {
        val g = PromptLoadGate()
        g.onLoaded(PromptRead(text = "disk body", error = null), typed = "我打的字")
        // 基线仍然记下磁盘内容：否则「放弃修改」会拿空串比对。
        assertEquals("disk body", g.initial)
    }

    @Test
    fun `读失败不开保存闸门`() {
        val g = PromptLoadGate()
        val d = g.onLoaded(PromptRead(text = null, error = "权限不足"), typed = "")
        assertFalse(g.canSave)
        assertNull(d.applyText)
        assertEquals("权限不足", d.error)
    }

    @Test
    fun `读失败时用户已经打了字也不开闸门`() {
        val g = PromptLoadGate()
        val d = g.onLoaded(PromptRead(text = null, error = "IO 错误"), typed = "abc")
        assertFalse(g.canSave)
        assertTrue(d.needsReload)
    }

    @Test
    fun `文件不存在是合法的空内容可以保存`() {
        val g = PromptLoadGate()
        // 不存在与读失败必须分开：前者是正常的「还没设置」。
        val d = g.onLoaded(PromptRead(text = "", error = null), typed = "")
        assertTrue(g.canSave)
        assertEquals("", d.applyText)
        assertFalse(d.needsReload)
    }

    @Test
    fun `重试成功后闸门打开`() {
        val g = PromptLoadGate()
        g.onLoaded(PromptRead(text = null, error = "IO 错误"), typed = "")
        assertFalse(g.canSave)
        val d = g.onLoaded(PromptRead(text = "ok", error = null), typed = "")
        assertTrue(g.canSave)
        assertEquals("ok", d.applyText)
    }

    @Test
    fun `保存成功后基线更新为新内容`() {
        val g = PromptLoadGate()
        g.onLoaded(PromptRead(text = "old", error = null), typed = "")
        g.onSaved("new")
        assertEquals("new", g.initial)
        assertTrue(g.canSave)
    }

    @Test
    fun `恢复默认后闸门仍然开着且基线为空`() {
        val g = PromptLoadGate()
        g.onLoaded(PromptRead(text = "custom", error = null), typed = "")
        g.onSaved("")
        assertEquals("", g.initial)
        assertTrue(g.canSave)
    }

    @Test
    fun `未加载时保存失败不会开闸门`() {
        val g = PromptLoadGate()
        g.onSaveFailed()
        assertFalse(g.canSave)
    }
}
