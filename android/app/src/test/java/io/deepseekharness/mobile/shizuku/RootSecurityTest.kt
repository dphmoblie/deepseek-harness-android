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

    @Test
    fun `RootCommandGuard 降级档补上命名空间本该承担的那一段`() {
        // 常规档：`mount -o remount,rw /data` 是正常运维，不该拦。
        RootCommandGuard.verifySafe("mount -o remount,rw /data")
        assertTrue(RootCommandGuard.violations("mount -o remount,rw /data").isEmpty())

        val degradedOnly = listOf(
            "mount -o remount,rw /data",
            "mount -t tmpfs tmpfs /mnt/scratch",
            "umount /dev/block/bootdevice/by-name/userdata",
            "dd if=/data/local/tmp/x.img of=/data/local/tmp/y.img",
            "echo x > /dev/null",
            "chmod 666 /dev/block/sda",
            "insmod /data/local/tmp/module.ko",
            "echo 1 > /proc/sys/kernel/perf_event_paranoid",
            "cat /dev/mem",
        )
        for (cmd in degradedOnly) {
            assertTrue("降级档应命中: $cmd", RootCommandGuard.violations(cmd, strict = true).isNotEmpty())
            try {
                RootCommandGuard.verifySafe(cmd, strict = true)
                fail("降级档应拦下: $cmd")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
            }
        }

        // 降级档不是「全都不许」：只读观察类命令照旧放行。
        for (cmd in listOf("ls -la /data/data", "cat /proc/version", "echo hello", "ps -ef | grep node")) {
            RootCommandGuard.verifySafe(cmd, strict = true)
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

        // 拿不到命名空间**不**中止：打一行诊断后按「无隔离」执行（TC-11 的降级口径）。
        assertTrue(wrapped.contains("command -v unshare >/dev/null 2>&1"))
        assertTrue(wrapped.contains("echo \"${RootSecurityWrapper.DIAG_PREFIX}${RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE}\" >&2"))
        assertTrue("降级时也要真的跑载荷", wrapped.contains("| dsh_isolated=0 /system/bin/sh -s"))
        assertFalse("命名空间缺失不该是受控退出码", wrapped.contains("exit 125"))

        // 外层不再自己拼单引号包脚本，也不再调用 sh -c。
        assertFalse("不应再有 sh -c 嵌套", wrapped.contains("sh -c"))
        assertFalse("不应把裸脚本写进外层", wrapped.contains("testing wrapper"))
    }

    @Test
    fun `RootSecurityWrapper 内层经 Base64 往返后仍是完整的加固脚本`() {
        val raw = "echo 'inner payload'; ls /data/data"
        val wrapped = RootSecurityWrapper.wrapWithProtection(raw)
        val inner = decodeInner(wrapped)

        // 内层只在**真的进了私有命名空间**时才碰 mount：没有命名空间时遮蔽会落到宿主上。
        assertTrue(inner.contains("if [ \"\$dsh_isolated\" = \"1\" ]; then"))
        assertTrue(inner.contains("mount --make-rprivate /"))
        assertTrue(inner.contains("mount --make-rslave /"))
        assertTrue(inner.contains("mount -o remount,ro"))
        assertTrue(inner.contains("/system /vendor /product /system_ext /odm /apex"))
        assertTrue(inner.contains("mountpoint -q"))
        assertTrue(inner.contains("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs /dev/block"))
        assertTrue(inner.contains("mount -t proc proc /proc"))

        // 传播私有化失败 / 遮蔽失败 / 无命名空间：都只报诊断，不中止执行。
        assertTrue(inner.contains(RootSecurityWrapper.DIAG_PROPAGATION_FAILED))
        assertTrue(inner.contains(RootSecurityWrapper.DIAG_BLOCK_MASK_FAILED))
        assertTrue(inner.contains(RootSecurityWrapper.DIAG_BLOCK_MASK_SKIPPED))
        assertFalse(inner.contains("exit 126"))
        assertFalse(inner.contains("exit 127"))

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
    fun `KernelSuProbe 明确非 KernelSU 不受管理器覆盖且保留未知版本兼容`() {
        data class Case(val signature: Boolean?, val granted: Boolean, val state: String, val reason: String)
        listOf(
            Case(false, true, KernelSuProbe.STATUS_UNSUPPORTED, KernelSuProbe.REASON_ROOT_IMPL_NOT_KERNELSU),
            Case(false, false, KernelSuProbe.STATUS_UNSUPPORTED, KernelSuProbe.REASON_ROOT_IMPL_NOT_KERNELSU),
            Case(null, true, KernelSuProbe.STATUS_READY, KernelSuProbe.REASON_READY),
            Case(null, false, KernelSuProbe.STATUS_NEEDS_ACTION, KernelSuProbe.REASON_PERMISSION_REQUIRED),
        ).forEach { case ->
            val result = KernelSuProbe.evaluate(
                isGkiSupported = true, hasKernelSuManager = true, hasSuBinary = true,
                isRootGranted = case.granted, suIsKernelSu = case.signature,
                suVersion = if (case.signature == false) "MAGISKSU 27.0" else null,
            )
            assertEquals(case.toString(), case.state, result.status)
            assertEquals(case.toString(), case.reason, result.reason)
            assertEquals(case.signature != false, result.isKernelSuDetected)
        }
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
            assertTrue("第三个参数是整段包装脚本", argv[2].startsWith("dsh_ns=\"\""))
            assertTrue(result.ok)
            assertEquals(0, result.exitCode)
            assertEquals("uid=0(root)", result.output)
            assertEquals(listOf("PID_ISOLATION_UNAVAILABLE"), result.diagnostics)
            assertTrue("降级诊断要能被调用方看见", result.degraded)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
    }

    @Test
    fun `RootCommandRunner 只用诊断行认领沙箱故障`() {
        val previous = RootCommandRunner.processLauncher
        try {
            // 解码失败是调用侧的 bug：受控错误码 + 已中止（这条诊断行之外没有别的输出）。
            RootCommandRunner.processLauncher = { FakeProcess("DSH_ROOT_SANDBOX:DECODE_FAILED\n", 124) }
            val decodeFailed = RootCommandRunner.run("id -u")
            assertFalse(decodeFailed.ok)
            assertEquals(RootCommandRunner.ERROR_SANDBOX_DECODE_FAILED, decodeFailed.errorCode)
            assertFalse("解码失败不属于降级执行", decodeFailed.degraded)

            // 降级诊断 ≠ 失败：命令照跑，退出码与输出都是载荷自己的。
            for (diag in listOf(
                RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE,
                RootSecurityWrapper.DIAG_PROPAGATION_FAILED,
                RootSecurityWrapper.DIAG_BLOCK_MASK_SKIPPED,
                RootSecurityWrapper.DIAG_HARDENING_UNAVAILABLE,
            )) {
                RootCommandRunner.processLauncher = { FakeProcess("DSH_ROOT_SANDBOX:$diag\nuid=0(root)\n", 0) }
                val degraded = RootCommandRunner.run("id -u")
                assertTrue("降级不等于失败: $diag", degraded.ok)
                assertNull(degraded.errorCode)
                assertEquals("uid=0(root)", degraded.output)
                assertTrue(degraded.degraded)
            }

            // 载荷自己的 127（command not found）不能冒充沙箱故障。
            RootCommandRunner.processLauncher = { FakeProcess("sh: no-such-tool: not found\n", 127) }
            val notFound = RootCommandRunner.run("no-such-tool")
            assertEquals(RootCommandRunner.ERROR_COMMAND_FAILED, notFound.errorCode)
            assertFalse(notFound.degraded)

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
    fun `根通道不可用时回退 Shizuku 且不能绕过 AI Shell 开关`() {
        listOf(KernelSuProbe.STATUS_UNSUPPORTED, KernelSuProbe.STATUS_NEEDS_ACTION).forEach { status ->
            val root = RootEngineAccess.Status(true, status, "NOT_READY", false, emptyList(), null)
            assertFalse(root.usable)
            assertFalse(DeviceCommand.requireShellRoute(DeviceCommand.SHELL, root.usable, shellEnabled = true))
            try {
                DeviceCommand.requireShellRoute(DeviceCommand.SHELL, root.usable, shellEnabled = false)
                fail("不可用的根通道不应绕过 AI Shell 权限")
            } catch (failure: RuntimeFailure) {
                assertEquals("DEVICE_SHELL_DISABLED", failure.code)
            }
        }
        assertTrue(DeviceCommand.requireShellRoute(DeviceCommand.SHELL, rootUsable = true, shellEnabled = false))
        assertFalse(DeviceCommand.requireShellRoute(DeviceCommand.BACKGROUND_TASKS, rootUsable = true, shellEnabled = true))
    }

    @Test
    fun `Root 输出超限只截断保留内容且仍排空整个管道`() {
        val previous = RootCommandRunner.processLauncher
        val source = object : ByteArrayInputStream("x".repeat(RootCommandRunner.MAX_OUTPUT_CHARS + 16384).toByteArray()) {
            var exhausted = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it == -1) exhausted = true
            }
        }
        RootCommandRunner.processLauncher = { object : Process() {
            override fun getInputStream(): InputStream = source
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
        } }
        try {
            val result = RootCommandRunner.run("echo diagnostic")
            assertTrue(result.ok)
            assertTrue(result.truncated)
            assertEquals(RootCommandRunner.MAX_OUTPUT_CHARS, result.output.length)
            assertTrue("保留上限之后仍必须排空至 EOF", source.exhausted)
        } finally {
            RootCommandRunner.processLauncher = previous
        }
    }

    @Test
    fun `DeviceCommandRunner 根通道在应用进程内起 su 而不经 Shizuku 会话`() {
        val previousLauncher = RootCommandRunner.processLauncher
        val previousProbe = RootSandboxProbe.runner
        val launches = mutableListOf<List<String>>()
        var wroteToShizuku = false
        val rootRunner = DeviceCommandRunner { _, _ -> wroteToShizuku = true }
        RootSandboxProbe.runner = {
            RootCommandRunner.RootCommandResult(
                true,
                0,
                "dsh_cap_ns=1\ndsh_cap_pid=1\ndsh_cap_harden=1",
                false,
                null,
            )
        }
        RootSandboxProbe.clearCache()
        RootCommandRunner.processLauncher = { argv -> launches += argv; FakeProcess("uid=0(root)\n", 0) }
        try {
            val result = rootRunner.execute("session-1", DeviceCommand.SHELL, "id -u", 5_000, useRootEngine = true)
            assertTrue(result.ok)
            assertEquals("uid=0(root)", result.text)
            assertFalse("根通道不得把命令写进 Shizuku 会话", wroteToShizuku)
            assertEquals(1, launches.size)
            assertEquals(listOf("su", "-c"), launches[0].take(2))
            assertEquals(3, launches[0].size)
        } finally {
            RootCommandRunner.processLauncher = previousLauncher
            RootSandboxProbe.runner = previousProbe
            RootSandboxProbe.clearCache()
        }
    }

    @Test
    fun `DeviceCommandRunner 沙箱降级时按严格档拦截挂载类脚本`() {
        val previousLauncher = RootCommandRunner.processLauncher
        val previousProbe = RootSandboxProbe.runner
        var launched = false
        val rootRunner = DeviceCommandRunner { _, _ -> }
        // 探测说这台设备没有挂载命名空间。
        RootSandboxProbe.runner = {
            RootCommandRunner.RootCommandResult(true, 0, "dsh_cap_ns=0\ndsh_cap_pid=0\ndsh_cap_harden=1", false, null)
        }
        RootSandboxProbe.clearCache()
        RootCommandRunner.processLauncher = { launched = true; FakeProcess("", 0) }
        try {
            rootRunner.execute("session-1", DeviceCommand.SHELL, "mount -o remount,rw /data", 5_000, useRootEngine = true)
            fail("降级档应拦下挂载类脚本")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_SECURITY_VIOLATION", e.code)
        } finally {
            RootCommandRunner.processLauncher = previousLauncher
            RootSandboxProbe.runner = previousProbe
            RootSandboxProbe.clearCache()
        }
        assertFalse("被护栏拦下就不该起 su", launched)
    }

    @Test
    fun `DeviceCommandRunner 把沙箱降级如实写进正文`() {
        val previousLauncher = RootCommandRunner.processLauncher
        val previousProbe = RootSandboxProbe.runner
        val rootRunner = DeviceCommandRunner { _, _ -> }
        RootSandboxProbe.runner = {
            RootCommandRunner.RootCommandResult(true, 0, "dsh_cap_ns=0\ndsh_cap_pid=0\ndsh_cap_harden=1", false, null)
        }
        RootSandboxProbe.clearCache()
        RootCommandRunner.processLauncher = {
            FakeProcess("DSH_ROOT_SANDBOX:NAMESPACE_UNAVAILABLE\nhello\n", 0)
        }
        try {
            val result = rootRunner.execute("session-1", DeviceCommand.SHELL, "echo hello", 5_000, useRootEngine = true)
            assertTrue(result.ok)
            assertTrue("保留载荷输出", result.text.contains("hello"))
            assertTrue("如实说明降级", result.text.contains("根通道降级执行"))
            assertTrue(result.text.contains(RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE))
        } finally {
            RootCommandRunner.processLauncher = previousLauncher
            RootSandboxProbe.runner = previousProbe
            RootSandboxProbe.clearCache()
        }
    }

    @Test
    fun `DeviceCommandRunner 未开根引擎时仍走 Shizuku 管道`() {
        val input = runner.buildInput(requestId, DeviceCommand.SHELL, "echo 'normal shell'")

        assertFalse("默认模式不得包含 su -c", input.contains("su -c"))
        assertTrue("默认模式必须走 toybox base64 管道", input.contains("toybox base64 -d | /system/bin/sh -s"))
    }

    // ---------------------------------------------------------------- 沙箱能力探测

    @Test
    fun `RootSandboxProbe 解析探测输出并在缓存期内复用`() {
        val previous = RootSandboxProbe.runner
        var calls = 0
        RootSandboxProbe.runner = {
            calls += 1
            RootCommandRunner.RootCommandResult(true, 0, "dsh_cap_ns=1\ndsh_cap_pid=0\ndsh_cap_harden=1", false, null)
        }
        RootSandboxProbe.clearCache()
        try {
            val first = RootSandboxProbe.detect(nowMs = 1_000)
            assertTrue(first.namespaceIsolation)
            assertFalse("探测说没有 PID 命名空间", first.pidIsolation)
            assertTrue(first.hardening)
            assertTrue(first.degraded)
            assertEquals(
                listOf(RootSecurityWrapper.DIAG_PID_ISOLATION_UNAVAILABLE),
                first.degradationCodes,
            )

            RootSandboxProbe.detect(nowMs = 2_000)
            assertEquals("缓存期内不该再探", 1, calls)

            RootSandboxProbe.detect(nowMs = 1_000 + RootSandboxProbe.CACHE_TTL_MS + 1)
            assertEquals("过期后必须重探", 2, calls)
        } finally {
            RootSandboxProbe.runner = previous
            RootSandboxProbe.clearCache()
        }
    }

    @Test
    fun `RootSandboxProbe 探测失败时按最保守一档处理`() {
        val previous = RootSandboxProbe.runner
        RootSandboxProbe.runner = { RootCommandRunner.RootCommandResult(false, -1, "", false, RootCommandRunner.ERROR_SU_UNAVAILABLE) }
        RootSandboxProbe.clearCache()
        try {
            val caps = RootSandboxProbe.detect(nowMs = 5_000)
            assertFalse("测不出来就不能假设有隔离", caps.namespaceIsolation)
            assertTrue(caps.degraded)
            assertEquals(
                listOf(RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE, RootSecurityWrapper.DIAG_HARDENING_UNAVAILABLE),
                caps.degradationCodes,
            )
        } finally {
            RootSandboxProbe.runner = previous
            RootSandboxProbe.clearCache()
        }
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
