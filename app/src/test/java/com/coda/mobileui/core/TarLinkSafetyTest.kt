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
    fun `经另一条链接到达的成员也要按字面键展开`() {
        // OCR 复审 high：a -> b 之后 a/s -> ../../evil 的键是字面 a/s，
        // 它的目标相对**字面**父目录 a 解析：a/../../evil 上跳两层出根。
        // 只按已解析前缀（b/s）查会漏掉这条链接，字面规范化成 b/evil 误放行。
        val links = setOf("a", "a/s")
        val targets = mapOf("a" to "b", "a/s" to "../../evil")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "a/s/x", links, targets))
    }

    @Test
    fun `成员名含点段或空段时键仍对得上`() {
        // OCR 复审 high：a/b/./s 与 a/b/s 是同一个成员，字面键必须规范化。
        val links = setOf("a/b/s")
        val targets = mapOf("a/b/./s" to "../x")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "a/b/s/../../../evil", links, targets))
    }

    @Test
    fun `fail-closed 回退不被点段绕过`() {
        // OCR 复审 high：无目标表时按前缀查链接，a/./s 必须先消点段再查。
        val links = setOf("a/s")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "a/./s/../../evil", links))
    }

    @Test
    fun `外层链接变浅后内层目标按物理深度判`() {
        // OCR r5 critical：d/e/a -> ../../b 物理落 root/b（深度 1），
        // d/e/a/s -> ../../evil 物理上从 root/b/s 上跳两层出根。
        // 按字面深度（d/e/a/s）消 .. 会误判成 d/evil（根内）。
        val links = setOf("d/e/a", "d/e/a/s")
        val targets = mapOf("d/e/a" to "../../b", "d/e/a/s" to "../../evil")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "d/e/a/s/pwn", links, targets))
    }

    @Test
    fun `链接展开段不得伪匹配同名成员键`() {
        // OCR r6 high：L -> t 展开出的 t 是物理目标段，不是归档成员 L/t。
        // 给它带上字面键会双重展开、把物理深度搞坏。L/z 应落 t/z（根内）。
        val links = setOf("L", "L/t")
        val targets = mapOf("L" to "t", "L/t" to "../../evil")
        assertTrue(TarLinkSafety.linkStaysInside("/tmp/out", "y", "L/z", links, targets))
    }

    @Test
    fun `字面 base 里的上跳先规范化再查键`() {
        // OCR r5 high：q/../a/s 的成员键是 a/s；base 不消 .. 会查不到，
        // 链接被当普通目录，../../evil 按字面深度误放行。
        val links = setOf("a", "a/s", "q/z")
        val targets = mapOf("a" to "b", "a/s" to "../x", "q/z" to "../a/s/../../evil")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "q/../a/s/../../evil", links, targets))
    }

    @Test
    fun `上跳跨过链接展开段后字面跟踪必须作废`() {
        // OCR r7 high：d1/d2/q -> ../../x 物理落 root/x，原始 .. 弹回 root，
        // 后续 a 的字面 base 规范化回 d1/d2 会伪匹配成员 d1/d2/a（u/v/w，深度3），
        // 三个 .. 看似落在根内。物理上 root/a 深度 1，三个 .. 出根，必须拒绝。
        val links = setOf("d1/d2/q", "d1/d2/a")
        val targets = mapOf("d1/d2/q" to "../../x", "d1/d2/a" to "u/v/w")
        assertFalse(
            TarLinkSafety.linkStaysInside("/tmp/out", "y", "d1/d2/q/../a/../../../evil", links, targets),
        )
    }

    @Test
    fun `经物理别名到达的成员链接也要展开`() {
        // OCR r7 medium：L -> t/u 使成员 L/t 实际落在 t/u/t。
        // y -> t/u/t/../../../evil 若把 t/u/t 当普通目录（深度3）会误放行；
        // 物理上它是链接 ../evil（落 t/evil，深度2），三个 .. 出根。
        val links = setOf("L", "L/t")
        val targets = mapOf("L" to "t/u", "L/t" to "../evil")
        assertFalse(TarLinkSafety.linkStaysInside("/tmp/out", "y", "t/u/t/../../../evil", links, targets))
    }

    @Test
    fun `物理别名展开后仍在根内的放行`() {
        // 同一组链接，t/u/t/x 经别名展开到 t/evil/x，深度足够，合法。
        val links = setOf("L", "L/t")
        val targets = mapOf("L" to "t/u", "L/t" to "../evil")
        assertTrue(TarLinkSafety.linkStaysInside("/tmp/out", "y", "t/u/t/x", links, targets))
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

    @Test
    fun `物理别名传递可达时越界链接拒绝`() {
        // OCR r9 high：A/B 的物理父 d/B 本身是 C/B 注册出来的别名，
        // 别名构建必须迭代到不动点，否则 d/e/z 被当普通目录漏放。
        val targets = mapOf("A" to "d", "C" to "d", "C/B" to "e", "A/B/z" to "../w")
        assertFalse(
            TarLinkSafety.linkStaysInside(
                root = "/tmp/out",
                member = "y",
                target = "d/e/z/../../../evil",
                symlinkMembers = targets.keys,
                symlinkTargets = targets,
            ),
        )
        // 成员自身的 ../w 目标（d/w）仍在根内，不受影响。
        assertTrue(
            TarLinkSafety.linkStaysInside(
                root = "/tmp/out",
                member = "A/B/z",
                target = "../w",
                symlinkMembers = targets.keys,
                symlinkTargets = targets,
            ),
        )
    }
}
