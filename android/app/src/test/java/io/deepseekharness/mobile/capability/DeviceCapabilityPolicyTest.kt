package io.deepseekharness.mobile.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 能力清单的判定表：只跑事实推导，不碰 Android。
 *
 * 每个用例只改**一个**事实，断言「状态 + 原因码 + 下一步动作 + 替代方式」四件套——
 * 界面与 AI 侧都靠这四件套决定怎么说、怎么做，所以它们必须一起被钉住。
 */
class DeviceCapabilityPolicyTest {
    private fun report(
        sdkInt: Int = 34,
        installed: Boolean = true,
        running: Boolean = true,
        permission: String = "granted",
        connected: Boolean = true,
        accessibility: String = "connected",
        rootEnabled: Boolean = false,
        rootStatus: String? = null,
        rootReason: String? = null,
        rootDegraded: Boolean = false,
    ) = DeviceCapabilityPolicy.report(
        DeviceCapabilityFacts(
            sdkInt = sdkInt,
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuPermission = permission,
            shizukuConnected = connected,
            accessibilityStatus = accessibility,
            rootEngineEnabled = rootEnabled,
            rootEngineStatus = rootStatus,
            rootEngineReason = rootReason,
            rootSandboxDegraded = rootDegraded,
        ),
    )

    private fun List<DeviceCapability>.entry(id: String): DeviceCapability = first { it.id == id }

    @Test
    fun `六项能力的顺序固定，根通道默认关闭只差用户点一下`() {
        val list = report()
        assertEquals(
            listOf(
                DeviceCapabilityPolicy.SHIZUKU,
                DeviceCapabilityPolicy.KERNELSU_ROOT,
                DeviceCapabilityPolicy.ACCESSIBILITY,
                DeviceCapabilityPolicy.VIRTUAL_SCREEN,
                DeviceCapabilityPolicy.SCREENSHOT,
                DeviceCapabilityPolicy.CHINESE_INPUT,
            ),
            list.map { it.id },
        )
        // 除根通道外，五项就绪时不该有任何待办。
        list.filterNot { it.id == DeviceCapabilityPolicy.KERNELSU_ROOT }.forEach { entry ->
            assertEquals(entry.id, DeviceCapabilityPolicy.READY, entry.state)
            assertEquals(entry.id, DeviceCapabilityPolicy.REASON_READY, entry.reason)
            assertNull(entry.id, entry.action)
            assertNull(entry.id, entry.alternative)
        }
        val root = list.entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, root.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ROOT_ENGINE_DISABLED, root.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_ENABLE_ROOT_ENGINE, root.action)
        assertEquals(DeviceCapabilityPolicy.ALTERNATIVE_SHIZUKU_SHELL, root.alternative)
    }

    @Test
    fun `根通道开着但 KernelSU 侧不满足时照实说缺什么`() {
        val needsGrant = report(rootEnabled = true, rootStatus = "needs_action", rootReason = DeviceCapabilityPolicy.REASON_ROOT_ENGINE_PERMISSION_REQUIRED)
            .entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, needsGrant.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ROOT_ENGINE_PERMISSION_REQUIRED, needsGrant.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_GRANT_ROOT_PERMISSION, needsGrant.action)

        val notKernelSu = report(rootEnabled = true, rootStatus = "unsupported", rootReason = DeviceCapabilityPolicy.REASON_ROOT_ENGINE_NOT_KERNELSU)
            .entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertEquals(DeviceCapabilityPolicy.UNSUPPORTED, notKernelSu.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ROOT_ENGINE_NOT_KERNELSU, notKernelSu.reason)
        // 不是 KernelSU 也不是「去做一步就能好」：没有可点的动作，只给退路。
        assertNull(notKernelSu.action)
        assertEquals(DeviceCapabilityPolicy.ALTERNATIVE_SHIZUKU_SHELL, notKernelSu.alternative)
    }

    @Test
    fun `根通道可用但沙箱降级时单独立一档`() {
        val ready = report(rootEnabled = true, rootStatus = "ready").entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertEquals(DeviceCapabilityPolicy.READY, ready.state)
        assertEquals(DeviceCapabilityPolicy.REASON_READY, ready.reason)

        val degraded = report(rootEnabled = true, rootStatus = "ready", rootDegraded = true)
            .entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertEquals(DeviceCapabilityPolicy.DEGRADED, degraded.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ROOT_SANDBOX_DEGRADED, degraded.reason)
        // 降级不是用户能点好的一步：不给动作，也不给替代方式（命令照跑）。
        assertNull(degraded.action)
        assertNull(degraded.alternative)
    }

    @Test
    fun `Shizuku 也不可用时根通道不给替代方式`() {
        val root = report(installed = false, running = false, permission = "undetermined", connected = false)
            .entry(DeviceCapabilityPolicy.KERNELSU_ROOT)
        assertNull(root.alternative)
    }

    @Test
    fun `没装 Shizuku 时副屏与截图指向同一个修法`() {
        val list = report(installed = false, running = false, permission = "undetermined", connected = false)
        val shizuku = list.entry(DeviceCapabilityPolicy.SHIZUKU)
        assertEquals(DeviceCapabilityPolicy.UNSUPPORTED, shizuku.state)
        assertEquals(DeviceCapabilityPolicy.REASON_SHIZUKU_NOT_INSTALLED, shizuku.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_INSTALL_SHIZUKU, shizuku.action)
        assertEquals(DeviceCapabilityPolicy.ALTERNATIVE_NO_DEVICE_SHELL, shizuku.alternative)
        listOf(DeviceCapabilityPolicy.VIRTUAL_SCREEN, DeviceCapabilityPolicy.SCREENSHOT).forEach { id ->
            val entry = list.entry(id)
            assertEquals(id, DeviceCapabilityPolicy.BLOCKED, entry.state)
            assertEquals(id, DeviceCapabilityPolicy.REASON_SHIZUKU_NOT_INSTALLED, entry.reason)
            assertEquals(id, DeviceCapabilityPolicy.ACTION_INSTALL_SHIZUKU, entry.action)
            assertNull(id, entry.alternative)
        }
    }

    @Test
    fun `Shizuku 装了但没运行时给的是打开 Shizuku`() {
        val entry = report(running = false, permission = "undetermined", connected = false)
            .entry(DeviceCapabilityPolicy.SHIZUKU)
        assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, entry.state)
        assertEquals(DeviceCapabilityPolicy.REASON_SHIZUKU_NOT_RUNNING, entry.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_OPEN_SHIZUKU, entry.action)
    }

    @Test
    fun `权限被拒与还没问过是两个码，动作都是去授权`() {
        val denied = report(permission = "denied", connected = false).entry(DeviceCapabilityPolicy.SHIZUKU)
        assertEquals(DeviceCapabilityPolicy.REASON_SHIZUKU_PERMISSION_DENIED, denied.reason)
        val undetermined = report(permission = "undetermined", connected = false).entry(DeviceCapabilityPolicy.SHIZUKU)
        assertEquals(DeviceCapabilityPolicy.REASON_SHIZUKU_PERMISSION_UNDETERMINED, undetermined.reason)
        listOf(denied, undetermined).forEach {
            assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, it.state)
            assertEquals(DeviceCapabilityPolicy.ACTION_GRANT_SHIZUKU_PERMISSION, it.action)
        }
    }

    @Test
    fun `授权了但服务没连上时给的是连接 Shizuku`() {
        val entry = report(connected = false).entry(DeviceCapabilityPolicy.SHIZUKU)
        assertEquals(DeviceCapabilityPolicy.REASON_SHIZUKU_NOT_CONNECTED, entry.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_CONNECT_SHIZUKU, entry.action)
    }

    @Test
    fun `无障碍没开时中文输入被挡住，Shell 就绪时给出只能打 ASCII 的替代`() {
        val list = report(accessibility = "off")
        val accessibility = list.entry(DeviceCapabilityPolicy.ACCESSIBILITY)
        assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, accessibility.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ACCESSIBILITY_OFF, accessibility.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_OPEN_ACCESSIBILITY_SETTINGS, accessibility.action)
        assertEquals(DeviceCapabilityPolicy.ALTERNATIVE_SHELL_ASCII_ONLY, accessibility.alternative)
        val input = list.entry(DeviceCapabilityPolicy.CHINESE_INPUT)
        assertEquals(DeviceCapabilityPolicy.BLOCKED, input.state)
        assertEquals(DeviceCapabilityPolicy.REASON_TEXT_INPUT_NEEDS_ACCESSIBILITY, input.reason)
        assertEquals(DeviceCapabilityPolicy.ACTION_OPEN_ACCESSIBILITY_SETTINGS, input.action)
        assertEquals(DeviceCapabilityPolicy.ALTERNATIVE_SHELL_ASCII_ONLY, input.alternative)
    }

    @Test
    fun `系统里开着但服务没连上时报的是另一个码`() {
        val entry = report(accessibility = "enabled_disconnected").entry(DeviceCapabilityPolicy.ACCESSIBILITY)
        assertEquals(DeviceCapabilityPolicy.NEEDS_ACTION, entry.state)
        assertEquals(DeviceCapabilityPolicy.REASON_ACCESSIBILITY_ENABLED_DISCONNECTED, entry.reason)
    }

    @Test
    fun `Shell 也不可用时不给替代方式`() {
        val list = report(installed = false, running = false, permission = "undetermined", connected = false, accessibility = "off")
        assertNull(list.entry(DeviceCapabilityPolicy.ACCESSIBILITY).alternative)
        assertNull(list.entry(DeviceCapabilityPolicy.CHINESE_INPUT).alternative)
    }

    @Test
    fun `Android 9 上副屏与截图是不可用，而不是待办`() {
        val list = report(sdkInt = 28)
        listOf(DeviceCapabilityPolicy.VIRTUAL_SCREEN, DeviceCapabilityPolicy.SCREENSHOT).forEach { id ->
            val entry = list.entry(id)
            assertEquals(id, DeviceCapabilityPolicy.UNSUPPORTED, entry.state)
            assertEquals(id, DeviceCapabilityPolicy.REASON_ANDROID_TOO_OLD, entry.reason)
            assertNull(id, entry.action)
        }
        // 版本低不影响另外三项：无障碍与中文写入不经过副屏。
        assertEquals(DeviceCapabilityPolicy.READY, list.entry(DeviceCapabilityPolicy.ACCESSIBILITY).state)
        assertEquals(DeviceCapabilityPolicy.READY, list.entry(DeviceCapabilityPolicy.CHINESE_INPUT).state)
    }

    @Test
    fun `中文写入只看无障碍，不看 Shizuku`() {
        val list = report(installed = false, running = false, permission = "undetermined", connected = false, accessibility = "connected")
        assertEquals(DeviceCapabilityPolicy.READY, list.entry(DeviceCapabilityPolicy.CHINESE_INPUT).state)
        assertEquals(DeviceCapabilityPolicy.REASON_READY, list.entry(DeviceCapabilityPolicy.CHINESE_INPUT).reason)
    }
}
