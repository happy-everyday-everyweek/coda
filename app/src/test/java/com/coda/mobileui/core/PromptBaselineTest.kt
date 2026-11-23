package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板状态判定（#742）。
 *
 * 「第一次见到即默认」会把设置页打开之前发生的修改记成默认，真实改动被永久隐藏。
 * 现在基线来自内核写出的清单，客户端只比对；清单缺失时必须说「未知」而不是「默认」。
 */
class PromptBaselineTest {
    private val body = "你是 Coda。\n"
    private val digest = PromptBaseline.sha256(body)

    @Test
    fun `内容与清单一致是默认`() {
        assertEquals(
            PromptBaseline.Status.DEFAULT,
            PromptBaseline.statusOf(exists = true, text = body, digest = digest, baseline = digest),
        )
    }

    @Test
    fun `清单缺失时报未知而不是默认`() {
        // 这正是 #742 的核心：无从判断时不能宣称默认。
        assertEquals(
            PromptBaseline.Status.UNKNOWN,
            PromptBaseline.statusOf(exists = true, text = body, digest = digest, baseline = null),
        )
        assertEquals("待内核初始化", PromptBaseline.label(PromptBaseline.Status.UNKNOWN))
    }

    @Test
    fun `升级前就被改过的内容判为已修改`() {
        val custom = "我自己写的身份\n"
        assertEquals(
            PromptBaseline.Status.MODIFIED,
            PromptBaseline.statusOf(
                exists = true,
                text = custom,
                digest = PromptBaseline.sha256(custom),
                baseline = digest,
            ),
        )
    }

    @Test
    fun `文件不存在是默认`() {
        assertEquals(
            PromptBaseline.Status.DEFAULT_ABSENT,
            PromptBaseline.statusOf(exists = false, text = null, digest = null, baseline = digest),
        )
        assertEquals("默认", PromptBaseline.label(PromptBaseline.Status.DEFAULT_ABSENT))
    }

    @Test
    fun `文件在但读失败单独成一态`() {
        assertEquals(
            PromptBaseline.Status.UNREADABLE,
            PromptBaseline.statusOf(exists = true, text = null, digest = null, baseline = digest),
        )
        assertEquals("读取失败", PromptBaseline.label(PromptBaseline.Status.UNREADABLE))
    }

    @Test
    fun `用户清空是已清空而不是默认`() {
        val blank = "   \n"
        assertEquals(
            PromptBaseline.Status.CLEARED,
            PromptBaseline.statusOf(
                exists = true,
                text = blank,
                digest = PromptBaseline.sha256(blank),
                baseline = digest,
            ),
        )
    }

    @Test
    fun `摘要大小写不影响比对`() {
        assertEquals(
            PromptBaseline.Status.DEFAULT,
            PromptBaseline.statusOf(exists = true, text = body, digest = digest, baseline = digest.uppercase()),
        )
    }

    @Test
    fun `清单解析出全部条目并统一小写`() {
        val m = PromptBaseline.parseManifest("""{"a.md":"ABCDEF","b.md":"123456"}""")
        assertEquals(2, m.size)
        assertEquals("abcdef", m["a.md"])
        assertEquals("123456", m["b.md"])
    }

    @Test
    fun `坏 JSON 与空清单一律解析为空表`() {
        assertTrue(PromptBaseline.parseManifest(null).isEmpty())
        assertTrue(PromptBaseline.parseManifest("").isEmpty())
        assertTrue(PromptBaseline.parseManifest("{").isEmpty())
        assertTrue(PromptBaseline.parseManifest("{}").isEmpty())
    }

    @Test
    fun `清单里的空值条目被丢掉`() {
        // 空摘要不能参与比对，否则任何内容都会被判成已修改。
        val m = PromptBaseline.parseManifest("""{"a.md":"","b.md":"aa"}""")
        assertEquals(1, m.size)
        assertEquals("aa", m["b.md"])
    }

    @Test
    fun `摘要与内核的 utf8 sha256 一致`() {
        // 已知值：sha256("abc") 的标准结果，钉住编码与十六进制格式。
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PromptBaseline.sha256("abc"),
        )
    }
}
