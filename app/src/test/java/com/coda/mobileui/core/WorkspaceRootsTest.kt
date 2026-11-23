package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工作区目录判定与迁移失败后的落点（#765、#769）。
 *
 * 判定必须无创建副作用：一次失败的 usable 会 mkdirs 出空目录，
 * 让下次 root() 认为「brand 已存在」而永不重试迁移，完整的旧数据被永久孤立。
 */
class WorkspaceRootsTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-wsroots").toFile()

    @Test
    fun `existsUsable 不会创建目录`() {
        val dir = tmpDir()
        val absent = File(dir, "not-there")
        assertFalse(WorkspaceRoots.existsUsable(absent))
        assertFalse("判定不该留下目录", absent.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `ensureUsable 会创建目录`() {
        val dir = tmpDir()
        val target = File(dir, "make-me")
        assertTrue(WorkspaceRoots.ensureUsable(target))
        assertTrue(target.isDirectory)
        dir.deleteRecursively()
    }

    @Test
    fun `已存在且可写的目录判为可用`() {
        val dir = tmpDir()
        assertTrue(WorkspaceRoots.existsUsable(dir))
        dir.deleteRecursively()
    }

    @Test
    fun `文件不是目录判为不可用`() {
        val dir = tmpDir()
        val f = File(dir, "a.txt")
        f.writeText("x")
        assertFalse(WorkspaceRoots.existsUsable(f))
        dir.deleteRecursively()
    }

    @Test
    fun `只有旧目录存在时才迁移`() {
        assertEquals(
            WorkspaceRoots.Migration.MOVE,
            WorkspaceRoots.migrationStep(brandExists = false, legacyExists = true),
        )
    }

    @Test
    fun `新目录已存在就不再迁移`() {
        // 半份拷贝留下的 brand 会让这里判 NONE，所以拷贝失败必须清掉残留。
        assertEquals(
            WorkspaceRoots.Migration.NONE,
            WorkspaceRoots.migrationStep(brandExists = true, legacyExists = true),
        )
        assertEquals(
            WorkspaceRoots.Migration.NONE,
            WorkspaceRoots.migrationStep(brandExists = false, legacyExists = false),
        )
    }

    @Test
    fun `迁移失败优先回完整的旧目录`() {
        val dir = tmpDir()
        val legacy = File(dir, "ZCode").apply { mkdirs() }
        val brand = File(dir, "Coda")
        assertEquals(legacy, WorkspaceRoots.afterFailedMigration(legacy, brand))
        assertFalse("回落判定不该建出 brand", brand.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `旧目录不可用而新目录可用时用新目录`() {
        val dir = tmpDir()
        val legacy = File(dir, "ZCode")
        val brand = File(dir, "Coda").apply { mkdirs() }
        assertEquals(brand, WorkspaceRoots.afterFailedMigration(legacy, brand))
        dir.deleteRecursively()
    }

    @Test
    fun `两边都不可用时返回 null`() {
        val dir = tmpDir()
        assertNull(WorkspaceRoots.afterFailedMigration(File(dir, "ZCode"), File(dir, "Coda")))
        dir.deleteRecursively()
    }

    @Test
    fun `清理半份拷贝会连带子文件一起删掉`() {
        val dir = tmpDir()
        val brand = File(dir, "Coda").apply { mkdirs() }
        File(brand, "workspace").mkdirs()
        File(brand, "workspace/a.txt").writeText("half")
        assertTrue(WorkspaceRoots.discardPartial(brand))
        assertFalse(brand.exists())
        // 清掉之后下一次 root() 才会重新尝试迁移。
        assertEquals(
            WorkspaceRoots.Migration.MOVE,
            WorkspaceRoots.migrationStep(brandExists = brand.exists(), legacyExists = true),
        )
        dir.deleteRecursively()
    }
}
