package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 运行时版本管理的纯逻辑测试。
 *
 * 真正的提升/切换依赖文件系统与 Android 的 `Os.rename`，属于设备侧人工验收项
 * （见 `docs/运行时更新与数据保留.md`：装新版本 → 上一版本仍在 → 一键切回 → 用户数据不变）。
 * 这里覆盖可以脱离设备穷举的部分：目标参数校验、按钮可用性判定、
 * dsh 版本号的唯一形态规则，以及从解压目录读取 dsh 版本时的各类失败情形
 * （读不到必须回「读不到」，不能把未校验的原文或路径抛到界面上）。
 */
class RuntimeVersionPolicyTest {
    @Test
    fun acceptsOnlyPreviousAsOperationTarget() {
        assertEquals("previous", RuntimeVersionPolicy.requireTarget("previous"))
        // 前后空白容错：Native 侧回传的值会经过 JS，`trim` 后才能比较。
        assertEquals("previous", RuntimeVersionPolicy.requireTarget(" previous "))
        for (target in listOf("current", "bundled", "retained", "PREVIOUS", "", "  ", null)) {
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeVersionPolicy.requireTarget(target)
            }
            // 未知目标必须以受控错误码拒绝，不能落到「随便切一个试试」。
            assertEquals("RUNTIME_VERSION_TARGET_INVALID", failure.code)
            assertEquals("运行时版本操作目标无效", failure.message)
        }
    }

    @Test
    fun switchingRequiresBothSlots() {
        assertTrue(RuntimeVersionPolicy.canSwitch(currentPresent = true, previousPresent = true))
        // 半份副本（只有根目录或只有清单）无法被提升，按钮必须不可用。
        assertFalse(RuntimeVersionPolicy.canSwitch(currentPresent = true, previousPresent = false))
        assertFalse(RuntimeVersionPolicy.canSwitch(currentPresent = false, previousPresent = true))
        assertFalse(RuntimeVersionPolicy.canSwitch(currentPresent = false, previousPresent = false))
    }

    @Test
    fun deletingRequiresPreviousSlot() {
        assertTrue(RuntimeVersionPolicy.canDelete(previousPresent = true))
        assertFalse(RuntimeVersionPolicy.canDelete(previousPresent = false))
    }

    @Test
    fun slotAndTargetNamesMatchTheFrontendContract() {
        // 前端校验器只认 'current' | 'previous' | 'bundled'，改这里必须同步改 `src/platform/validation.ts`。
        assertEquals("current", RuntimeVersionPolicy.SLOT_CURRENT)
        assertEquals("previous", RuntimeVersionPolicy.SLOT_PREVIOUS)
        assertEquals("bundled", RuntimeVersionPolicy.SLOT_BUNDLED)
        assertEquals("previous", RuntimeVersionPolicy.TARGET_PREVIOUS)
    }

    @Test
    fun acceptsOnlyWellFormedDshVersions() {
        for (version in listOf("0.1.5-rc.2", "0.1.5-rc.3", "0.1.7-alpha.2", "1.0.0", "0.2.0")) {
            assertTrue(version, RuntimeVersionPolicy.DSH_VERSION.matches(version))
        }
        // 不匹配一律按「读不到」处理：缺段、多了 v 前缀、prerelease 为空、带空格或路径片段都拒绝。
        for (version in listOf("", "0.1", "0.1.5.6", "v0.1.5", "0.1.5-", "0.1.5 rc.2", "0.1.5-rc.2 ", "../etc/passwd")) {
            assertFalse(version, RuntimeVersionPolicy.DSH_VERSION.matches(version))
        }
    }

    @Test
    fun readsDshVersionFromExtractedRuntime() {
        val root = tempRoot()
        try {
            writeDshManifest(root, """{"name":"@deepseek-ai/dsh","version":"0.1.7-rc.2"}""")
            assertEquals("0.1.7-rc.2", RuntimeDshVersion.read(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsNothingReadableAsNull() {
        val root = tempRoot()
        try {
            // 没有运行时根目录：读不到就是读不到，不猜版本。
            assertNull(RuntimeDshVersion.read(null))
            assertNull(RuntimeDshVersion.read(File(root, "missing")))
            // 根目录在、包内清单缺失。
            assertNull(RuntimeDshVersion.read(root))

            writeDshManifest(root, "not json at all")
            assertNull(RuntimeDshVersion.read(root))

            writeDshManifest(root, """{"version":"v0.1.7"}""")
            assertNull(RuntimeDshVersion.read(root))

            // 超过 256 KiB 的清单不读：运行时产物里的清单不该有这么大，读它是浪费内存。
            writeDshManifest(root, "x".repeat(256 * 1024 + 1))
            assertNull(RuntimeDshVersion.read(root))

            // 空文件的长度为 0，同样落在可读区间之外。
            writeDshManifest(root, "")
            assertNull(RuntimeDshVersion.read(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File = Files.createTempDirectory("runtime-dsh-version").toFile()

    private fun writeDshManifest(root: File, body: String) {
        val manifest = File(root, RuntimeDshVersion.RELATIVE_PATH)
        manifest.parentFile?.mkdirs()
        manifest.writeText(body)
    }
}
