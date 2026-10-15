package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

class RootSecurityTest {

    private val requestId = "11223344-5566-7788-99aa-bbccddeeff00"
    private val runner = DeviceCommandRunner { _, _ -> }

    // ---------------------------------------------------------------- 静态安检

    @Test
    fun `RootCommandGuard 放行普通命令`() {
        val safeCommands = listOf(
            "ls -la /data/data",
            "cat /proc/version",
            "echo 'Hello KernelSU'",
            "dumpsys package io.deepseekharness.mobile",
            "ps -ef | grep node",
            "tar -czf /sdcard/backup.tar.gz /data/data/com.test.app/files",
            // 读块设备文件本身不是破坏动作：没有重定向/of=/tee 就不拦。
            "cat /dev/block/bootdevice/by-name/bootinfo",
            // /data 改可写是正常运维需求，核心分区才是禁区。
            "mount -o remount,rw /data",
            // 只是名字里含 wipe 的正常参数。
            "zip -q --wipe-cache backup.zip /data/local/tmp/x",
            "rm -rf /data/local/tmp/build",
        )
        for (cmd in safeCommands) {
            RootCommandGuard.verifySafe(cmd)
        }
    }

    @Test
    fun `RootCommandGuard 阻断危险指令`() {
        val dangerousCommands = listOf(
            "reboot bootloader",
            "reboot recovery",
            "reboot fastboot",
            "reboot edl",
            "fastboot flash boot boot.img",
            "flash_erase /dev/mtd0 0 0",
            "wipe data",
            "setenforce 0",
            "setenforce permissive",
            "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot",
            "dd if=/dev/zero of = /dev/block/mmcblk0",
            "dd if=/dev/zero of=\"/dev/block/bootdevice/by-name/boot\"",
            "dd if=/dev/zero of='/dev/block/mmcblk0'",
            "cat bad > /dev//block/sda",
            "echo bad | tee /dev/block/sda",
            "cat image.bin > /dev/block/sda1",
            "echo x > /dev/disk/by-name/boot",
            "blockdev --setrw /dev/block/mmcblk0",
            "mknod /dev/block/bootdevice/by-name/boot c 8 1",
            "mkfs.ext4 /dev/block/by-name/userdata",
            "parted /dev/block/mmcblk0 rm 1",
            "sgdisk --zap-all /dev/block/mmcblk0",
            "umount /dev/block",
            "umount -l /dev/block",
            "mount -o remount,rw /system",
            "mount -o remount,rw /vendor",
            "mount -o rw /product",
            "echo 0 > /sys/fs/selinux/enforce",
        )
        for (cmd in dangerousCommands) {
            try {
                RootCommandGuard.verifySafe(cmd)
                fail("应当阻断高危指令: $cmd")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
                assertTrue("报错应提示拦截动作", e.message?.contains("安全护栏拦截") == true)
            }
        }
    }

    @Test
    fun `RootCommandGuard 归一化能看穿转义引号与大小写变形`() {
        val obfuscated = listOf(
            "re\\boot bootloader",
            "'reboot' \"bootloader\"",
            "REBOOT    BOOTLOADER",
            "reboot${'\t'}bootloader",
            "dd if=/dev/zero of=\\/dev\\/block\\/sda",
            "sEtEnFoRcE  0",
            "'setenforce' 'permissive'",
            "cat x > /dev/block/../block/mmcblk0",
        )
        for (cmd in obfuscated) {
            assertTrue(
                "归一化后应命中规则: $cmd",
                RootCommandGuard.violations(cmd).isNotEmpty(),
            )
        }
    }

    // ---------------------------------------------------------------- 沙箱包装

    @Test
    fun `RootSecurityWrapper 包装脚本包含隔离只读锁定与块设备屏蔽`() {
        val wrapped = RootSecurityWrapper.wrapWithProtection("echo 'testing wrapper'; ls /data/data")

        // 命名空间能力逐级探测；探测用 true，绝不把载荷跑第二遍。
        assertTrue("必须探测命名空间组合", wrapped.contains("unshare -m -p -f true"))
        assertTrue("必须有降级路径", wrapped.contains("unshare -m true"))
        assertTrue("探测后才交给 sh -s", wrapped.contains("unshare \$dsh_ns /system/bin/sh -s"))

        // 失败即停：缺 unshare 直接退出，不再静默降级成「无隔离的 UID 0」。
        assertTrue(wrapped.contains("command -v unshare >/dev/null 2>&1"))
        assertTrue(wrapped.contains("exit ${RootSecurityWrapper.EXIT_NAMESPACE_UNAVAILABLE}"))

        // 外层不再自己拼单引号包脚本，也不再调用 sh -c。
        assertFalse("不应再有 sh -c 嵌套", wrapped.contains("sh -c"))
        assertFalse("不应把裸脚本写进外层", wrapped.contains("testing wrapper"))
    }

    @Test
    fun `RootSecurityWrapper 内层经 Base64 往返后仍是完整的加固脚本`() {
        val raw = "echo 'inner payload'; ls /data/data"
        val wrapped = RootSecurityWrapper.wrapWithProtection(raw)
        val inner = decodeInner(wrapped)

        // 内层才是干活的：私有化挂载传播（rslave 回退）、只读重挂、物理切断、私有 procfs。
        assertTrue(inner.contains("mount --make-rprivate /"))
        assertTrue(inner.contains("mount --make-rslave /"))
        assertTrue(inner.contains("exit ${RootSecurityWrapper.EXIT_PROPAGATION_FAILED}"))
        assertTrue(inner.contains("mount -o remount,ro"))
        assertTrue(inner.contains("/system /vendor /product /system_ext /odm /apex"))
        assertTrue(inner.contains("mountpoint -q"))
        assertTrue(inner.contains("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs /dev/block"))
        assertTrue(inner.contains("exit ${RootSecurityWrapper.EXIT_BLOCK_MASK_FAILED}"))
        assertTrue(inner.contains("mount -t proc proc /proc"))

        // 载荷以 Base64 二次编码进内层，参数逃逸不成立。
        val payloadB64 = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        assertTrue(inner.contains(payloadB64))
        assertTrue(inner.contains("setpriv --bounding-set=-sys_admin,-mknod"))
        assertTrue(inner.contains("--ambient-caps=-sys_admin,-mknod"))
        assertTrue(inner.contains("HARDENING_UNAVAILABLE"))
    }

    @Test
    fun `RootSecurityWrapper 关闭加固档时不裁减 capability`() {
        val inner = decodeInner(RootSecurityWrapper.wrapWithProtection("id", hardened = false))
        assertFalse("关掉加固档就不该出现 setpriv", inner.contains("setpriv"))
        assertTrue(inner.contains("/system/bin/sh -s"))
    }

    @Test
    fun `RootSecurityWrapper 解码失败即停而不是空脚本跑成成功`() {
        val wrapped = RootSecurityWrapper.wrapWithProtection("id -u")
        assertTrue("外层先解到变量再校验", wrapped.contains("dsh_s=\$(printf '%s' '"))
        assertTrue(wrapped.contains("exit ${RootSecurityWrapper.EXIT_DECODE_FAILED}"))

        val inner = decodeInner(wrapped)
        assertTrue("内层载荷同样先解到变量再校验", inner.contains("dsh_p=\$(printf '%s' '"))
        assertTrue(inner.contains("exit ${RootSecurityWrapper.EXIT_DECODE_FAILED}"))
    }

    @Test
    fun `RootSecurityWrapper 加固档先试探 setpriv 是否真的接受参数`() {
        val inner = decodeInner(RootSecurityWrapper.wrapWithProtection("id -u"))
        assertTrue("必须先用 true 试探 setpriv 参数", inner.contains("\$dsh_harden /system/bin/sh -c true"))
        assertTrue(inner.contains(RootSecurityWrapper.DIAG_HARDENING_UNAVAILABLE))
    }

    @Test
    fun `singleQuote 转义单个引号`() {
        assertEquals("'a'\\''b'", RootSecurityWrapper.singleQuote("a'b"))
        assertEquals("'plain'", RootSecurityWrapper.singleQuote("plain"))
    }

    // ---------------------------------------------------------------- 状态探测

    @Test
    fun `KernelSuProbe 解析内核版本并判断 GKI 兼容性`() {
        val v510 = KernelSuProbe.parseKernelVersion("Linux version 5.10.101-android12-9-g123456 (builder@host) (clang version 12.0.8)")
        assertEquals(5 to 10, v510)
        assertTrue(KernelSuProbe.isGkiCompatible(5, 10))

        val v515 = KernelSuProbe.parseKernelVersion("Linux version 5.15.78-android13-4-g987654")
        assertEquals(5 to 15, v515)
        assertTrue(KernelSuProbe.isGkiCompatible(5, 15))

        val v61 = KernelSuProbe.parseKernelVersion("Linux version 6.1.25-android14-1-g000000")
        assertEquals(6 to 1, v61)
        assertTrue(KernelSuProbe.isGkiCompatible(6, 1))

        val v419 = KernelSuProbe.parseKernelVersion("Linux version 4.19.157-perf+ (android-build@google)")
        assertEquals(4 to 19, v419)
        assertFalse(KernelSuProbe.isGkiCompatible(4, 19))
    }

    @Test
    fun `KernelSuProbe 推导各环境事实状态`() {
        // 1. 什么都没探测到 -> UNSUPPORTED（未安装）
        val r1 = KernelSuProbe.evaluate(
            isGkiSupported = false,
            hasKernelSuManager = false,
            hasSuBinary = false,
            isRootGranted = false,
            rawVersion = "4.19",
        )
        assertEquals(KernelSuProbe.STATUS_UNSUPPORTED, r1.status)
        assertEquals(KernelSuProbe.REASON_NOT_INSTALLED, r1.reason)

        // 2. 未安装 KSU -> UNSUPPORTED
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
        assertNull("GKI 内核不该有保留意见", r4.warning())
    }

    @Test
    fun `KernelSuProbe 走 LKM 路线时给可用但仍提醒`() {
        // 内核 4.19 + 管理器在 + 已授权：KernelSU 的 LKM 路线支持这种组合，不该判成不可用。
        val result = KernelSuProbe.evaluate(
            isGkiSupported = false,
            hasKernelSuManager = true,
            hasSuBinary = true,
            isRootGranted = true,
            rawVersion = "4.19",
            suIsKernelSu = true,
            suVersion = "KernelSU v1.0.6 (11800)",
        )
        assertEquals(KernelSuProbe.STATUS_READY, result.status)
        assertNotNull("非 GKI 就绪时要给出内核提醒", result.warning())
        assertEquals("KernelSU v1.0.6 (11800)", result.suVersion)
    }

    @Test
    fun `KernelSuProbe 认出别的 root 实现时不谎报待授权`() {
        val result = KernelSuProbe.evaluate(
            isGkiSupported = true,
            hasKernelSuManager = false,
            hasSuBinary = true,
            isRootGranted = true,
            rawVersion = "5.10",
            suIsKernelSu = false,
            suVersion = "MAGISKSU 27.0",
        )
        assertEquals(KernelSuProbe.STATUS_UNSUPPORTED, result.status)
        assertEquals(KernelSuProbe.REASON_ROOT_IMPL_NOT_KERNELSU, result.reason)
        assertFalse(result.isKernelSuDetected)
    }

    @Test
    fun `KernelSuProbe 管理器包名覆盖官方及主要衍生版`() {
        assertTrue(KernelSuProbe.KERNELSU_MANAGER_PACKAGES.contains("me.weishu.kernelsu"))
        assertTrue(KernelSuProbe.KERNELSU_MANAGER_PACKAGES.contains("me.tiann.kernelsu"))
        assertTrue(KernelSuProbe.KERNELSU_MANAGER_PACKAGES.contains("com.rifsxd.ksunext"))
    }

    // ---------------------------------------------------------------- 根命令执行器

    @Test
    fun `RootCommandRunner 命中护栏时拒绝拉起 su`() {
        val previous = RootCommandRunner.processLauncher
        var launched = false
        RootCommandRunner.processLauncher = { launched = true; FakeProcess("", 0) }
        try {
            RootCommandRunner.run("dd if=/dev/zero of=/dev/block/sda")
            fail("危险脚本应在拉起进程前被拦下")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_SECURITY_VIOLATION", e.code)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
        assertFalse("护栏命中后不该起进程", launched)
    }

    @Test
    fun `RootCommandRunner 以单参数调用 su 并剥离沙箱诊断行`() {
        val previous = RootCommandRunner.processLauncher
        val argv = mutableListOf<String>()
        RootCommandRunner.processLauncher = { command ->
            argv += command
            FakeProcess("DSH_ROOT_SANDBOX:PID_ISOLATION_UNAVAILABLE\nuid=0(root)\n", 0)
        }
        try {
            val result = RootCommandRunner.run("id -u")
            assertEquals(listOf("su", "-c"), argv.take(2))
            assertEquals(3, argv.size)
            assertTrue("第三个参数是整段包装脚本", argv[2].startsWith("if ! command -v unshare"))
            assertTrue(result.ok)
            assertEquals(0, result.exitCode)
            assertEquals("uid=0(root)", result.output)
            assertEquals(listOf("PID_ISOLATION_UNAVAILABLE"), result.diagnostics)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
    }

    @Test
    fun `RootCommandRunner 只用诊断行认领沙箱退出码`() {
        val previous = RootCommandRunner.processLauncher
        try {
            val cases = mapOf(
                RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE to RootCommandRunner.ERROR_SANDBOX_UNAVAILABLE,
                RootSecurityWrapper.DIAG_PROPAGATION_FAILED to RootCommandRunner.ERROR_SANDBOX_PROPAGATION,
                RootSecurityWrapper.DIAG_BLOCK_MASK_FAILED to RootCommandRunner.ERROR_BLOCK_MASK_FAILED,
                RootSecurityWrapper.DIAG_DECODE_FAILED to RootCommandRunner.ERROR_SANDBOX_DECODE_FAILED,
            )
            for ((diag, expected) in cases) {
                // 沙箱报障时既打诊断行、也返回受控退出码。
                RootCommandRunner.processLauncher = { FakeProcess("DSH_ROOT_SANDBOX:$diag\n", 126) }
                val result = RootCommandRunner.run("id -u")
                assertFalse(result.ok)
                assertEquals(expected, result.errorCode)
                assertEquals(listOf(diag), result.diagnostics)
            }

            // 载荷自己的 127（command not found）不能冒充「块设备遮蔽失败」。
            RootCommandRunner.processLauncher = { FakeProcess("sh: no-such-tool: not found\n", 127) }
            assertEquals(RootCommandRunner.ERROR_COMMAND_FAILED, RootCommandRunner.run("no-such-tool").errorCode)

            RootCommandRunner.processLauncher = { FakeProcess("sh: id: not allowed to run\n", 1) }
            assertEquals(RootCommandRunner.ERROR_PERMISSION_DENIED, RootCommandRunner.run("id -u").errorCode)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
    }

    @Test
    fun `RootCommandRunner 区分 su 缺失与执行超时`() {
        val previous = RootCommandRunner.processLauncher
        try {
            RootCommandRunner.processLauncher = { throw java.io.IOException("Cannot run program \"su\"") }
            val missing = RootCommandRunner.run("id -u")
            assertFalse(missing.ok)
            assertEquals(RootCommandRunner.ERROR_SU_UNAVAILABLE, missing.errorCode)

            RootCommandRunner.processLauncher = { FakeProcess("", 0, stalls = true) }
            val timedOut = RootCommandRunner.run("sleep 999", timeoutMs = 50)
            assertFalse(timedOut.ok)
            assertEquals(RootCommandRunner.ERROR_TIMEOUT, timedOut.errorCode)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
    }

    // ---------------------------------------------------------------- DeviceCommandRunner 接线

    @Test
    fun `DeviceCommandRunner 不再经由 Shizuku 会话执行 su`() {
        try {
            runner.buildInput(requestId, DeviceCommand.SHELL, "echo 'reading protected data'", useRootEngine = true)
            fail("根通道未接线时不应静默回落到 Shizuku PTY 里跑 su")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_ENGINE_NOT_WIRED", e.code)
        }
    }

    @Test
    fun `DeviceCommandRunner 在 useRootEngine 为 true 时拦截高危指令`() {
        try {
            runner.buildInput(
                requestId,
                DeviceCommand.SHELL,
                "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot",
                useRootEngine = true,
            )
            fail("变砖指令应在 buildInput 阶段即刻被拦截")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_SECURITY_VIOLATION", e.code)
        }
    }

    @Test
    fun `DeviceCommandRunner 默认 useRootEngine 为 false 时走 Shizuku 管道`() {
        val input = runner.buildInput(requestId, DeviceCommand.SHELL, "echo 'normal shell'", useRootEngine = false)

        assertFalse("默认模式不得包含 su -c", input.contains("su -c"))
        assertTrue("默认模式必须走 toybox base64 管道", input.contains("toybox base64 -d | /system/bin/sh -s"))
    }

    // ---------------------------------------------------------------- 辅助

    private fun decodeInner(wrapped: String): String {
        val encoded = wrapped.substringAfterLast("printf '%s' '").substringBefore("'")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    /** 极简假进程：只为验证参数、输出剥离与退出码翻译，不涉及真实 su。 */
    private class FakeProcess(
        private val output: String,
        private val exitCode: Int,
        private val stalls: Boolean = false,
    ) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            if (stalls) Thread.sleep(2_000)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !stalls
        override fun exitValue(): Int = exitCode
        override fun destroy() = Unit
    }
}
