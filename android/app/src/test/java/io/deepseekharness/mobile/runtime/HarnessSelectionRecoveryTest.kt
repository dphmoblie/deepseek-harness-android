package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessSelectionRecoveryTest {
    @Test
    fun `损坏的选择值和包记录均恢复为内置版本`() {
        for (code in listOf("HARNESS_VERSION_INVALID", "HARNESS_PACKAGE_INVALID")) {
            var recovered = false
            val selected = HarnessVersions.selectedOrBundled(
                readActive = { throw RuntimeFailure(code, "无效记录") },
                fallBack = { recovered = true },
            )
            assertEquals(HarnessVersions.BUNDLED, selected)
            assertTrue("$code 应写回内置选择", recovered)
        }
    }

    @Test
    fun `有效默认版本不被改写`() {
        var recovered = false
        assertEquals("0.2.10", HarnessVersions.selectedOrBundled(
            readActive = { "0.2.10" },
            fallBack = { recovered = true },
        ))
        assertFalse(recovered)
    }

    @Test
    fun `路径异常不写回未经信任的选择文件`() {
        var recovered = false
        assertEquals(HarnessVersions.BUNDLED, HarnessVersions.selectedOrBundled(
            readActive = { throw RuntimeFailure("HARNESS_PATH_INVALID", "路径无效") },
            fallBack = { recovered = true },
        ))
        assertFalse(recovered)
    }

    @Test
    fun `恢复落盘失败仍允许显示内置版本`() {
        assertEquals(HarnessVersions.BUNDLED, HarnessVersions.selectedOrBundled(
            readActive = { throw RuntimeFailure("HARNESS_VERSION_INVALID", "无效记录") },
            fallBack = { error("存储暂时不可写") },
        ))
    }
}
