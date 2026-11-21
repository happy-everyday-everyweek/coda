package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入备份的收尾规则。
 *
 * 之前失败分支直接 return：核心停着、数据可能已半覆盖，用户回到会话只得到一句「导入失败」，
 * 对话、工具、终端全都不可用，还得自己找地方重启。
 */
class BackupImportFlowTest {
    @Test
    fun `broken package never stops the runtime`() {
        assertFalse(BackupImportFlow.shouldStopRuntime("备份缺少 manifest.json"))
        assertFalse(BackupImportFlow.shouldStopRuntime("无法读取备份文件"))
    }

    @Test
    fun `valid package is allowed to stop the runtime`() {
        assertTrue(BackupImportFlow.shouldStopRuntime(null))
    }

    @Test
    fun `successful import relaunches`() {
        assertTrue(BackupImportFlow.mustRelaunch(42))
    }

    @Test
    fun `failed import relaunches too`() {
        assertTrue(BackupImportFlow.mustRelaunch(null))
        assertTrue(BackupImportFlow.mustRelaunch(0))
    }
}