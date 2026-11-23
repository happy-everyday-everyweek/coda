package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tar 链接的越界校验（#792）。
 *
 * 原实现有三处 fail-open：相对链接按解压根而不是链接自身所在目录解析、
 * 硬链接（h 行）完全不检查、`tar -tvf` 失败时整段校验被跳过并返回 true。
 * 三者都能让归档把文件写到目标目录之外。
 */
class TarLinkSafetyTest {
    @Test
    fun `解析符号链接的目标`() {
        val m = TarListing.parseLink("lrwxrwxrwx 0/0 0 2024-05-06 07:08 sub/x -> ../../etc/p")
        assertEquals("sub/x", m!!.path)
        assertEquals("../../etc/p", m.target)
        assertFalse(m.hardLink)
    }

    @Test
    fun `符号链接不是硬链接`() {
        val m = TarListing.parseLink("lrwxrwxrwx 0/0 0 2024-05-06 07:08 a -> b")
        assertFalse(m!!.hardLink)
    }

    @Test
    fun `硬链接行也能解析出目标`() {
        val m = TarListing.parseLink("hrw-r--r-- 0/0 0 2024-05-06 07:08 dup link to sub/x")
        assertEquals("dup", m!!.path)
        assertEquals("sub/x", m.target)
        assertTrue(m.hardLink)
    }

    @Test
    fun `普通文件没有链接目标`() {
        assertNull(TarListing.parseLink("-rw-r--r-- 0/0 12 2024-05-06 07:08 plain.txt"))
    }

    @Test
    fun `名字含空格的链接不被截断`() {
        val m = TarListing.parseLink("lrwxrwxrwx 0/0 0 2024-05-06 07:08 my file -> other file")
        assertEquals("my file", m!!.path)
        assertEquals("other file", m.target)
    }

    @Test
    fun `相对链接按链接自身所在目录解析`() {
        // sub/x -> ../../etc/p 的真实落点是 etc/p 的上一级，越界。
        assertFalse(
            TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "sub/x", target = "../../etc/p"),
        )
    }

    @Test
    fun `子目录内指向同级文件的相对链接是安全的`() {
        assertTrue(
            TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "sub/x", target = "y"),
        )
    }

    @Test
    fun `子目录内上跳一级仍在根内是安全的`() {
        assertTrue(
            TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "sub/x", target = "../top.txt"),
        )
    }

    @Test
    fun `根目录下的链接上跳一级越界`() {
        assertFalse(
            TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "x", target = "../etc/p"),
        )
    }

    @Test
    fun `绝对链接目标一律拒绝`() {
        assertFalse(TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "x", target = "/etc/passwd"))
        // 指向根内部也不接受：解压时目标可能还不存在，语义不可靠。
        assertFalse(TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "x", target = "/tmp/out/a"))
    }

    @Test
    fun `空链接目标拒绝`() {
        assertFalse(TarLinkSafety.linkStaysInside(root = "/tmp/out", member = "x", target = ""))
    }

    @Test
    fun `成员名本身越界一律拒绝`() {
        assertFalse(TarLinkSafety.memberSafe("../evil"))
        assertFalse(TarLinkSafety.memberSafe("/etc/passwd"))
        assertFalse(TarLinkSafety.memberSafe("a/../../evil"))
        assertTrue(TarLinkSafety.memberSafe("a/b.txt"))
        assertTrue(TarLinkSafety.memberSafe("./a/b.txt"))
    }

    @Test
    fun `列举失败必须判不安全而不是放行`() {
        // fail-closed：拿不到详细列表就无从判断链接，不能返回 true。
        assertFalse(TarLinkSafety.listingUsable(ok = false, output = ""))
        assertFalse(TarLinkSafety.listingUsable(ok = true, output = ""))
        assertTrue(TarLinkSafety.listingUsable(ok = true, output = "-rw-r--r-- 0/0 1 2024-05-06 07:08 a"))
    }

    @Test
    fun `名字含多个箭头时拒绝解析`() {
        // 成员名可以合法含 " -> "：`x -> a/b` 指向 `../../evil` 时整行有两个箭头，
        // 按首个切会把目标读成 `a/b -> ../../evil`，规范化后反而"看起来安全"。
        assertNull(TarListing.parseLink("lrwxrwxrwx 0/0 0 2024-05-06 07:08 x -> a/b -> ../../evil"))
    }

    @Test
    fun `父路径经过符号链接时拒绝`() {
        // d/s -> ../a 让 d/s 实际落在 root/a；
        // 于是 d/s/x -> ../../evil 实际解析成 root/../evil，越界。
        val links = setOf("d/s")
        assertTrue(TarLinkSafety.linkStaysInside("/tmp/out", "d/s", "../a", links))
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "d/s/x", "../../evil", links))
    }

    @Test
    fun `父路径不经过符号链接时照常判定`() {
        val links = setOf("other/s")
        assertTrue(TarLinkSafety.linkStaysInside("/tmp/out", "d/x", "y", links))
    }

    @Test
    fun `目标路径经过符号链接成员时按实际落点判越界`() {
        // OCR high：只查成员的父目录不够，目标自身的中间组件也可能是链接。
        // d/s -> ../a 实际落在 root/a（深度 1），y -> d/s/../../evil 字面
        // 规范化成 evil（看似安全），实际 root/a/../../evil 越界。
        val links = setOf("d/s")
        val targets = mapOf("d/s" to "../a")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "d/s/../../evil", links, targets))
    }

    @Test
    fun `目标经过符号链接但实际仍在根内时放行`() {
        // d/s -> ../a 落在 root/a；y -> d/s/../b 实际是 root/b，安全。
        val links = setOf("d/s")
        val targets = mapOf("d/s" to "../a")
        assertTrue(TarLinkSafety.linkStaysInside("/tmp/out", "y", "d/s/../b", links, targets))
    }

    @Test
    fun `目标经过的链接自身越界时拒绝`() {
        val links = setOf("d/s")
        val targets = mapOf("d/s" to "../../outside")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "d/s/file", links, targets))
    }

    @Test
    fun `符号链接成环时拒绝`() {
        val links = setOf("a", "b")
        val targets = mapOf("a" to "b/x", "b" to "a/y")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "z", "a/file", links, targets))
    }

    @Test
    fun `硬链接指向符号链接成员时拒绝`() {
        assertFalse(
            TarLinkSafety.hardLinkStaysInside(
                root = "/tmp/out",
                member = "dup",
                target = "d/s",
                knownMembers = setOf("dup", "d/s"),
                symlinkMembers = setOf("d/s"),
            ),
        )
    }

    @Test
    fun `转义后的越界名字还原后仍被拒`() {
        // GNU tar 把 `../evil` 列成 `\056\056/evil`，字面上没有 `..` 组件。
        val raw = "\\056\\056/evil"
        assertTrue(TarLinkSafety.memberSafe(raw))
        assertFalse(TarLinkSafety.memberSafe(TarListing.unescape(raw)))
    }

    @Test
    fun `硬链接指向归档内成员是安全的`() {
        assertTrue(
            TarLinkSafety.hardLinkStaysInside(
                root = "/tmp/out",
                member = "dup",
                target = "sub/x",
                knownMembers = setOf("sub/x", "dup"),
            ),
        )
    }

    @Test
    fun `硬链接指向归档外成员拒绝`() {
        assertFalse(
            TarLinkSafety.hardLinkStaysInside(
                root = "/tmp/out",
                member = "dup",
                target = "../outside",
                knownMembers = setOf("dup"),
            ),
        )
    }

    @Test
    fun `硬链接指向未列举的成员拒绝`() {
        // 目标可能是先解出来的越界符号链接，不能只看名字。
        assertFalse(
            TarLinkSafety.hardLinkStaysInside(
                root = "/tmp/out",
                member = "dup",
                target = "ghost",
                knownMembers = setOf("dup"),
            ),
        )
    }
}
