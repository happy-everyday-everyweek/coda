package com.coda.mobileui.core
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商配置的损坏保护判定（#770）。
 *
 * load 因损坏跳过迁移、返回部分解析结果；update 若照常 save，
 * 会把损坏但可救的文件覆盖成空，.corrupt 备份成唯一副本。
 * 任一文件损坏时 update 必须拒绝。Context/文件路径由 CI 编译覆盖。
 */
class ProviderCorruptGuardTest {
    @Test
    fun `主文件损坏时拒绝更新`() {
        assertFalse(ProviderStore.updateAllowed(mainCorrupt = true, sidecarCorrupt = false))
    }

    @Test
    fun `旁路文件损坏时同样拒绝`() {
        assertFalse(ProviderStore.updateAllowed(mainCorrupt = false, sidecarCorrupt = true))
    }

    @Test
    fun `两份都损坏时拒绝`() {
        assertFalse(ProviderStore.updateAllowed(mainCorrupt = true, sidecarCorrupt = true))
    }

    @Test
    fun `都完好时允许更新`() {
        assertTrue(ProviderStore.updateAllowed(mainCorrupt = false, sidecarCorrupt = false))
    }
}
