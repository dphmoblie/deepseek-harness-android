package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class RootSecurityTest {

    private val requestId = "11223344-5566-7788-99aa-bbccddeeff00"
    private val runner = DeviceCommandRunner { _, _ -> }

    @Test
    fun `RootCommandGuard 放行普通命令`() {
        val safeCommands = listOf(
            "ls -la /data/data",
            "cat /proc/version",
            "echo 'Hello KernelSU'",
            "dumpsys package io.deepseekharness.mobile",
            "ps -ef | grep node",
            "tar -czf /sdcard/backup.tar.gz /data/data/com.test.app/files",
        )
        for (cmd in safeCommands) {
            // 不抛异常即为通过
            RootCommandGuard.verifySafe(cmd)
        }
    }

    @Test
    fun `RootCommandGuard 阻断危险变砖指令`() {
        val dangerousCommands = listOf(
            "reboot bootloader",
            "reboot recovery",
            "reboot fastboot",
            "reboot edl",
            "fastboot flash boot boot.img",
            "flash_erase /dev/mtd0 0 0",
            "wipe data",
            "setenforce 0",
            "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot",
            "dd if=/dev/zero of = /dev/block/mmcblk0",
            "dd if=/dev/zero of=\"/dev/block/bootdevice/by-name/boot\"",
            "dd if=/dev/zero of='/dev/block/mmcblk0'",
            "cat bad > /dev//block/sda",
            "echo bad | tee /dev/block/sda",
            "cat image.bin > /dev/block/sda1",
            "blockdev --setrw /dev/block/mmcblk0",
            "mkfs.ext4 /dev/block/by-name/userdata",
            "umount /dev/block",
            "umount -l /dev/block",
            "mount -o remount,rw /system",
            "mount -o remount,rw /vendor",
        )
        for (cmd in dangerousCommands) {
            try {
                RootCommandGuard.verifySafe(cmd)
                fail("应当阻断高危指令: $cmd")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
                assertTrue("报错应提示拦截操作", e.message?.contains("安全防火墙拦截") == true)
            }
        }
    }

    @Test
    fun `RootSecurityWrapper 包装脚本包含挂载隔离只读锁定与块设备屏蔽`() {
        val rawScript = "echo 'testing wrapper'; ls /data/data"
        val wrapped = RootSecurityWrapper.wrapWithProtection(rawScript)

        // 必须以 unshare -m -p -f 启动 mount 与 pid 命名空间隔离
        assertTrue("必须以 unshare -m -p -f 启动命名空间隔离", wrapped.startsWith("unshare -m -p -f /system/bin/sh -c '"))
        assertTrue(wrapped.endsWith("'"))

        // 必须包含私有化挂载传播
        assertTrue(wrapped.contains("mount --make-rprivate /"))

        // 必须包含系统分区只读重新挂载
        assertTrue(wrapped.contains("mount -o remount,ro"))
        assertTrue(wrapped.contains("/system /vendor /product /system_ext /odm /apex"))

        // 必须包含 /dev/block 屏蔽 (含 ro 标记)
        assertTrue(wrapped.contains("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs /dev/block"))

        // 必须包含隔离的 procfs 重新挂载，防止从 /proc/1/root 逃逸
        assertTrue(wrapped.contains("mount -t proc proc /proc"))

        // 必须包含载荷 Base64 编码，防止参数逃逸
        val expectedB64 = Base64.getEncoder().encodeToString(rawScript.toByteArray(Charsets.UTF_8))
        assertTrue(wrapped.contains(expectedB64))
        assertTrue(wrapped.contains("toybox base64 -d | /system/bin/sh -s"))
    }

    @Test
    fun `KernelSuProbe 解析内核版本并判断 GKI 兼容性`() {
        // GKI 5.10+ 测试
        val v510 = KernelSuProbe.parseKernelVersion("Linux version 5.10.101-android12-9-g123456 (builder@host) (clang version 12.0.8)")
        assertEquals(5 to 10, v510)
        assertTrue(KernelSuProbe.isGkiCompatible(5, 10))

        val v515 = KernelSuProbe.parseKernelVersion("Linux version 5.15.78-android13-4-g987654")
        assertEquals(5 to 15, v515)
        assertTrue(KernelSuProbe.isGkiCompatible(5, 15))

        val v61 = KernelSuProbe.parseKernelVersion("Linux version 6.1.25-android14-1-g000000")
        assertEquals(6 to 1, v61)
        assertTrue(KernelSuProbe.isGkiCompatible(6, 1))

        // 旧内核测试 (非 GKI)
        val v419 = KernelSuProbe.parseKernelVersion("Linux version 4.19.157-perf+ (android-build@google)")
        assertEquals(4 to 19, v419)
        assertFalse(KernelSuProbe.isGkiCompatible(4, 19))

        val v414 = KernelSuProbe.parseKernelVersion("Linux version 4.14.180-perf")
        assertEquals(4 to 14, v414)
        assertFalse(KernelSuProbe.isGkiCompatible(4, 14))
    }

    @Test
    fun `KernelSuProbe 推导各环境事实状态`() {
        // 1. 非 GKI 内核 -> UNSUPPORTED
        val r1 = KernelSuProbe.evaluate(
            isGkiSupported = false,
            hasKernelSuManager = false,
            hasSuBinary = false,
            isRootGranted = false,
            rawVersion = "4.19",
        )
        assertEquals(KernelSuProbe.STATUS_UNSUPPORTED, r1.status)
        assertEquals(KernelSuProbe.REASON_KERNEL_UNSUPPORTED, r1.reason)

        // 2. GKI 内核但未安装 KSU -> UNSUPPORTED
        val r2 = KernelSuProbe.evaluate(
            isGkiSupported = true,
            hasKernelSuManager = false,
            hasSuBinary = false,
            isRootGranted = false,
            rawVersion = "5.10",
        )
        assertEquals(KernelSuProbe.STATUS_UNSUPPORTED, r2.status)
        assertEquals(KernelSuProbe.REASON_NOT_INSTALLED, r2.reason)

        // 3. 已安装但尚未授权 -> NEEDS_ACTION
        val r3 = KernelSuProbe.evaluate(
            isGkiSupported = true,
            hasKernelSuManager = true,
            hasSuBinary = true,
            isRootGranted = false,
            rawVersion = "5.10",
        )
        assertEquals(KernelSuProbe.STATUS_NEEDS_ACTION, r3.status)
        assertEquals(KernelSuProbe.REASON_PERMISSION_REQUIRED, r3.reason)

        // 4. 就绪且已授权 -> READY
        val r4 = KernelSuProbe.evaluate(
            isGkiSupported = true,
            hasKernelSuManager = true,
            hasSuBinary = true,
            isRootGranted = true,
            rawVersion = "5.10",
        )
        assertEquals(KernelSuProbe.STATUS_READY, r4.status)
        assertEquals(KernelSuProbe.REASON_READY, r4.reason)
    }

    @Test
    fun `KernelSuProbe 管理器包名覆盖官方及主要衍生版`() {
        assertTrue(KernelSuProbe.KERNELSU_MANAGER_PACKAGES.contains("me.tiann.kernelsu"))
        assertTrue(KernelSuProbe.KERNELSU_MANAGER_PACKAGES.contains("com.rifsxd.ksunext"))
    }

    @Test
    fun `DeviceCommandRunner 在 useRootEngine 为 true 时通过 su 与安全沙箱下发`() {
        val safeScript = "echo 'reading protected data'; ls /data/data"
        val input = runner.buildInput(requestId, DeviceCommand.SHELL, safeScript, useRootEngine = true)

        // 外层必须包含 su -c 与 unshare
        assertTrue("必须调用 su -c", input.contains("su -c"))
        assertTrue("必须调用 unshare -m -p -f", input.contains("unshare -m -p -f"))
        assertTrue("必须包含块设备屏蔽", input.contains("tmpfs /dev/block"))
    }

    @Test
    fun `DeviceCommandRunner 在 useRootEngine 为 true 时拦截高危指令`() {
        val brickScript = "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot"
        try {
            runner.buildInput(requestId, DeviceCommand.SHELL, brickScript, useRootEngine = true)
            fail("通过 Root 引擎下发变砖指令应在 buildInput 阶段即刻被拦截")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_SECURITY_VIOLATION", e.code)
        }
    }

    @Test
    fun `DeviceCommandRunner 默认 useRootEngine 为 false 时走 Shizuku 管道`() {
        val script = "echo 'normal shell'"
        val input = runner.buildInput(requestId, DeviceCommand.SHELL, script, useRootEngine = false)

        assertFalse("默认模式不得包含 su -c", input.contains("su -c"))
        assertTrue("默认模式必须走 toybox base64 管道", input.contains("toybox base64 -d | /system/bin/sh -s"))
    }
}
