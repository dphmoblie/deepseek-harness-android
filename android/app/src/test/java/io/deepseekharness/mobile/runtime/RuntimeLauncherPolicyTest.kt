package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行器可用性判定表的测试。
 *
 * 守的是 0.2.4 的真机回归：根内 loader 的「内容正确」被当成「可用」，于是缺属主执行位的拷贝被
 * 反复复用，PRoot 每次启动都在 `execve` 上 EACCES（界面只显示「PRoot 无法加载 Ubuntu 程序」）。
 * 判定必须把权限位与 `access(X_OK)` 一起看，并且只允许「缺执行位」这一种形态就地修。
 */
class RuntimeLauncherPolicyTest {
    private fun observation(
        regular: Boolean = true,
        size: Long = 1632,
        mode: Int = RuntimeLauncherPolicy.EXECUTABLE_FILE_MODE,
        execAccess: Boolean = true,
        stamped: Boolean = false,
    ) = RuntimeLauncherPolicy.Observation(
        regular = regular,
        size = size,
        mode = mode,
        execAccess = execAccess,
        stamped = stamped,
    )

    @Test
    fun acceptsExecutableRegularFile() {
        val current = observation()

        assertNull(RuntimeLauncherPolicy.failureCode(current))
        assertTrue(RuntimeLauncherPolicy.isUsable(current))
        assertTrue(RuntimeLauncherPolicy.isUsablePath(current))
        assertFalse(RuntimeLauncherPolicy.repairableInPlace(current))
        assertFalse(RuntimeLauncherPolicy.shouldDropStamp(current))
    }

    @Test
    fun reportsMissingPath() {
        assertEquals(RuntimeLauncherPolicy.MISSING, RuntimeLauncherPolicy.failureCode(null))
        assertFalse(RuntimeLauncherPolicy.isUsable(null))
        assertFalse(RuntimeLauncherPolicy.isUsablePath(null))
        assertFalse(RuntimeLauncherPolicy.repairableInPlace(null))
        assertFalse(RuntimeLauncherPolicy.shouldDropStamp(null))
    }

    /** 符号链接一律不算「根内 loader 可用」：Landlock 按最终 inode 判定，链接形态在真机上是 125。 */
    @Test
    fun rejectsNonRegularFile() {
        val link = observation(regular = false, size = 9, mode = 0x1ff)

        assertEquals(RuntimeLauncherPolicy.NOT_REGULAR, RuntimeLauncherPolicy.failureCode(link))
        assertFalse(RuntimeLauncherPolicy.isUsable(link))
        assertFalse(RuntimeLauncherPolicy.repairableInPlace(link))
        // 兜底形态允许链接，但它的目标必须真的能执行。
        assertTrue(RuntimeLauncherPolicy.isUsablePath(link))
        assertFalse(RuntimeLauncherPolicy.isUsablePath(link.copy(execAccess = false)))
    }

    @Test
    fun rejectsEmptyFile() {
        val empty = observation(size = 0, execAccess = false)

        assertEquals(RuntimeLauncherPolicy.EMPTY, RuntimeLauncherPolicy.failureCode(empty))
        assertFalse(RuntimeLauncherPolicy.isUsable(empty))
        assertFalse(RuntimeLauncherPolicy.isUsablePath(empty))
        assertFalse(RuntimeLauncherPolicy.repairableInPlace(empty))
    }

    /** 真机回归的形态：内容对（长度与 APK 一致）但没有属主执行位。 */
    @Test
    fun treatsMissingOwnerExecuteAsRepairable() {
        val notExecutable = observation(mode = 0x180, execAccess = false, stamped = true)

        assertEquals(RuntimeLauncherPolicy.NOT_EXECUTABLE, RuntimeLauncherPolicy.failureCode(notExecutable))
        assertTrue(RuntimeLauncherPolicy.repairableInPlace(notExecutable))
        // 有执行位之前不碰盖章：这一步的语义还没有定论，别顺手改掉别的东西。
        assertFalse(RuntimeLauncherPolicy.shouldDropStamp(notExecutable))
    }

    @Test
    fun doesNotRepairWhenSystemDeniesExecution() {
        val denied = observation(execAccess = false)

        assertEquals(RuntimeLauncherPolicy.EXEC_DENIED, RuntimeLauncherPolicy.failureCode(denied))
        assertFalse(RuntimeLauncherPolicy.repairableInPlace(denied))
        assertFalse(RuntimeLauncherPolicy.shouldDropStamp(denied))
    }

    /** 「有执行位、系统仍拒绝」时才值得去掉 security.android.exec：它在部分 ROM 上可能是禁止标记。 */
    @Test
    fun dropsStampOnlyWhenExecutableBitIsSetAndExecutionIsDenied() {
        val deniedAndStamped = observation(execAccess = false, stamped = true)

        assertEquals(RuntimeLauncherPolicy.EXEC_DENIED, RuntimeLauncherPolicy.failureCode(deniedAndStamped))
        assertTrue(RuntimeLauncherPolicy.shouldDropStamp(deniedAndStamped))
    }

    @Test
    fun describesModeInOctal() {
        assertEquals("missing", RuntimeLauncherPolicy.describe(null))

        val description = RuntimeLauncherPolicy.describe(observation(mode = 0x180, execAccess = false, stamped = true))
        assertTrue(description, description.contains("regular=true"))
        assertTrue(description, description.contains("size=1632"))
        assertTrue(description, description.contains("mode=0600"))
        assertTrue(description, description.contains("execAccess=false"))
        assertTrue(description, description.contains("stamped=true"))
    }

    @Test
    fun keepsTargetModeAndOwnerBitConsistent() {
        assertEquals(0x1c0, RuntimeLauncherPolicy.EXECUTABLE_FILE_MODE)
        assertEquals(0x40, RuntimeLauncherPolicy.OWNER_EXECUTE_BIT)
        assertTrue(RuntimeLauncherPolicy.hasOwnerExecute(RuntimeLauncherPolicy.EXECUTABLE_FILE_MODE))
        assertTrue(RuntimeLauncherPolicy.hasOwnerExecute(0x140))
        assertFalse(RuntimeLauncherPolicy.hasOwnerExecute(0x180))
        assertFalse(RuntimeLauncherPolicy.hasOwnerExecute(0x100))
        assertFalse(RuntimeLauncherPolicy.hasOwnerExecute(0))
    }
}
