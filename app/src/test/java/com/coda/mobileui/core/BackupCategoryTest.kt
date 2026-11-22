package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择性备份的分类判定（#732）。
 *
 * 部分包最容易出的两类事故：一是「未包含的分类被当成空数据覆盖掉」——
 * 剔除供应商的包导入后本机配置被清空；二是清单引用了未导出的路径，
 * 恢复时出现悬空链接或对不存在的文件 chmod。这两类都是纯判定，能直接钉住。
 */
class BackupCategoryTest {
    @Test
    fun `默认全选保持现有全量行为`() {
        val all = BackupCategory.DEFAULT
        assertTrue(all.containsAll(BackupCategory.entries))
        assertEquals(BackupCategory.entries.size, all.size)
    }

    @Test
    fun `供应商三件套绑为一组不拆散`() {
        // 少了 meta，OAuth 供应商会被当成手填条目；少了 ASR，转写供应商直接消失。
        val group = BackupCategory.PROVIDERS.entriesInPackage
        assertTrue(group.contains("provider_config.json"))
        assertTrue(group.contains("coda-provider-meta.json"))
        assertTrue(group.contains("coda-asr-providers.json"))
        assertEquals(3, group.size)
    }

    @Test
    fun `按条目名判定所属分类`() {
        assertEquals(BackupCategory.PREFS, BackupCategory.of("prefs/zcode_ui_settings.json"))
        assertEquals(BackupCategory.PROVIDERS, BackupCategory.of("provider_config.json"))
        assertEquals(BackupCategory.PROVIDERS, BackupCategory.of("coda-provider-meta.json"))
        assertEquals(BackupCategory.PROVIDERS, BackupCategory.of("coda-asr-providers.json"))
        assertEquals(BackupCategory.PROMPTS, BackupCategory.of("prompts/global-AGENTS.md"))
        assertEquals(BackupCategory.PROMPTS, BackupCategory.of("prompts/workspace-AGENTS.md"))
        assertEquals(BackupCategory.DATABASES, BackupCategory.of("databases/x.db"))
        assertEquals(BackupCategory.NO_BACKUP, BackupCategory.of("no-backup/token"))
    }

    @Test
    fun `Linux rootfs 单列一项而不是混在用户文件里`() {
        assertEquals(BackupCategory.ROOTFS, BackupCategory.of("files/linux/rootfs/bin/sh"))
        assertEquals(BackupCategory.USER_FILES, BackupCategory.of("files/workspaces/a.txt"))
    }

    @Test
    fun `清单与 manifest 不属于任何可选分类`() {
        // 它们必须始终写入，否则部分包无法被识别与恢复。
        assertNull(BackupCategory.of("manifest.json"))
        assertNull(BackupCategory.of("symlinks.txt"))
        assertNull(BackupCategory.of("file-modes.txt"))
    }

    @Test
    fun `未知条目不归入任何分类`() {
        assertNull(BackupCategory.of("random/thing.bin"))
        assertNull(BackupCategory.of(""))
    }

    @Test
    fun `只勾选设置时其余分类的条目都不导出`() {
        val sel = setOf(BackupCategory.PREFS)
        assertTrue(BackupCategory.shouldExport("prefs/zcode_ui_settings.json", sel))
        assertFalse(BackupCategory.shouldExport("provider_config.json", sel))
        assertFalse(BackupCategory.shouldExport("prompts/global-AGENTS.md", sel))
        assertFalse(BackupCategory.shouldExport("files/linux/rootfs/bin/sh", sel))
        assertFalse(BackupCategory.shouldExport("files/workspaces/a.txt", sel))
        assertFalse(BackupCategory.shouldExport("databases/x.db", sel))
        assertFalse(BackupCategory.shouldExport("no-backup/token", sel))
    }

    @Test
    fun `清单与 manifest 无论勾选什么都导出`() {
        val sel = setOf(BackupCategory.PROMPTS)
        assertTrue(BackupCategory.shouldExport("manifest.json", sel))
        assertTrue(BackupCategory.shouldExport("symlinks.txt", sel))
        assertTrue(BackupCategory.shouldExport("file-modes.txt", sel))
    }

    @Test
    fun `空选择等于什么都不导出但仍写清单`() {
        assertFalse(BackupCategory.shouldExport("prefs/a.json", emptySet()))
        assertTrue(BackupCategory.shouldExport("manifest.json", emptySet()))
    }

    @Test
    fun `清单只收录已导出路径的条目`() {
        val exported = setOf("files/workspaces/a.txt", "files/workspaces/b.txt")
        val links =
            listOf(
                "files/workspaces/a.txt" to "b.txt",
                "files/linux/rootfs/bin/sh" to "/bin/bash",
            )
        val kept = BackupCategory.filterManifest(links.map { it.first }, exported)
        assertEquals(listOf("files/workspaces/a.txt"), kept)
    }

    @Test
    fun `目录前缀下的文件也算已导出`() {
        // 清单里可能记的是目录级条目，导出集合里是其下的具体文件。
        val exported = setOf("files/linux/rootfs/bin/sh")
        val kept = BackupCategory.filterManifest(listOf("files/linux/rootfs/bin"), exported)
        assertEquals(listOf("files/linux/rootfs/bin"), kept)
    }

    @Test
    fun `部分包导入时未包含的分类保持原样`() {
        val inPackage = setOf(BackupCategory.PREFS, BackupCategory.PROMPTS)
        assertTrue(BackupCategory.shouldRestore("prefs/a.json", inPackage))
        assertTrue(BackupCategory.shouldRestore("prompts/global-AGENTS.md", inPackage))
        // 关键：供应商不在包里，绝不能因为「读不到」就写空覆盖。
        assertFalse(BackupCategory.shouldRestore("provider_config.json", inPackage))
        assertFalse(BackupCategory.shouldRestore("files/linux/rootfs/bin/sh", inPackage))
    }

    @Test
    fun `旧全量包没有分类清单时按全部处理`() {
        // schema 1~2 的包没有 categories 字段，必须仍能完整恢复。
        val parsed = BackupCategory.parseManifest(null)
        assertEquals(BackupCategory.DEFAULT, parsed)
        val parsedEmpty = BackupCategory.parseManifest(org.json.JSONArray())
        assertEquals(BackupCategory.DEFAULT, parsedEmpty)
    }

    @Test
    fun `分类清单往返一致`() {
        val sel = setOf(BackupCategory.PREFS, BackupCategory.ROOTFS)
        val arr = BackupCategory.toJson(sel)
        assertEquals(sel, BackupCategory.parseManifest(arr))
    }

    @Test
    fun `未知分类名被忽略而不是让整份清单失效`() {
        val arr = org.json.JSONArray().put("prefs").put("from_the_future").put("prompts")
        assertEquals(setOf(BackupCategory.PREFS, BackupCategory.PROMPTS), BackupCategory.parseManifest(arr))
    }

    @Test
    fun `全是未知分类名时退回全选而不是空集`() {
        // 空集会导致「什么都不恢复」，比恢复到全选更危险（用户以为导入成功了）。
        val arr = org.json.JSONArray().put("from_the_future")
        assertEquals(BackupCategory.DEFAULT, BackupCategory.parseManifest(arr))
    }

    @Test
    fun `含凭据的分类被明确标注`() {
        assertTrue(BackupCategory.PROVIDERS.sensitive)
        assertTrue(BackupCategory.NO_BACKUP.sensitive)
        assertTrue(BackupCategory.PREFS.sensitive)
        assertFalse(BackupCategory.PROMPTS.sensitive)
    }

    @Test
    fun `rootfs 被标注为大项`() {
        assertTrue(BackupCategory.ROOTFS.bulky)
        assertFalse(BackupCategory.PREFS.bulky)
        assertFalse(BackupCategory.PROMPTS.bulky)
    }

    @Test
    fun `每个分类都有可展示的中文名`() {
        BackupCategory.entries.forEach { assertTrue("${it.name} 缺标题", it.title.isNotBlank()) }
    }

    @Test
    fun `部分包标记只在全选时为 false`() {
        assertFalse(BackupCategory.isPartial(BackupCategory.DEFAULT))
        assertTrue(BackupCategory.isPartial(setOf(BackupCategory.PREFS)))
        assertTrue(BackupCategory.isPartial(emptySet()))
    }

    @Test
    fun `体积按量级换单位`() {
        assertEquals("0 B", DataBackup.formatBytes(0))
        assertEquals("512 B", DataBackup.formatBytes(512))
        assertEquals("1 KB", DataBackup.formatBytes(1024))
        assertEquals("1.0 MB", DataBackup.formatBytes(1024L * 1024))
        assertEquals("1.0 GB", DataBackup.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `分类 key 互不重复且与包内前缀对得上`() {
        val keys = BackupCategory.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // key 是 manifest 的稳定标识，改它会让旧包读不出分类。
        assertEquals("prefs", BackupCategory.PREFS.key)
        assertEquals("rootfs", BackupCategory.ROOTFS.key)
    }
}