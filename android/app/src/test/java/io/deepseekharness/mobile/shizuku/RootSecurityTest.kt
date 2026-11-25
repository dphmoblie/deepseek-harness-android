package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            // 但 `cat /dev/block/...` 会命中最大档的「读写块设备」规则，见下方专门用例。
            "zip -q --wipe-cache backup.zip /data/local/tmp/x",
            "rm -rf /data/local/tmp/build",
        )
        for (cmd in safeCommands) {
            RootCommandGuard.verifySafe(cmd)
        }
    }

    @Test
    fun `最大档才放行 mount 与普通 setprop`() {
        RootCommandGuard.verifySafe("mount -o remount,rw /data", RootCommandGuard.Profile.MAXIMUM)
        RootCommandGuard.verifySafe("setprop dsh.probe 1", RootCommandGuard.Profile.MAXIMUM)
        RootCommandGuard.verifySafe("dd if=/dev/zero of=/data/local/tmp/x", RootCommandGuard.Profile.MAXIMUM)
        assertTrue(
            "严格档仍要拦 mount",
            RootCommandGuard.violations("mount -o remount,rw /data", RootCommandGuard.Profile.STRICT).isNotEmpty(),
        )
        assertTrue(
            "最大档也不能写核心分区",
            RootCommandGuard.violations("mount -o remount,rw /system", RootCommandGuard.Profile.MAXIMUM).isNotEmpty(),
        )
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
    fun `RootCommandGuard 阻断系统属性修改但允许只读 getprop`() {
        val blocked = listOf(
            "setprop ro.product.model TestPhone",
            "'SET'PROP ro.build.fingerprint test/build:1/user/release-keys",
            "resetprop -n ro.product.device test-device",
            "setprop ctl.stop zygote",
            "echo value > /data/property/persist.sys.locale",
            "printf value | tee /dev/__properties__/u:object_r:system_prop:s0",
            "dd if=/data/local/tmp/property of=/data/property/persist.sys.locale",
            "cp /data/local/tmp/property /data/property/persist.sys.locale",
            "mv /data/local/tmp/property /dev/__properties__/u:object_r:system_prop:s0",
            "install /data/local/tmp/property /data/property/persist.sys.locale",
        )
        for (command in blocked) {
            assertTrue("应阻断系统属性修改: $command", RootCommandGuard.violations(command).isNotEmpty())
            try {
                RootCommandGuard.verifySafe(command)
                fail("应阻断系统属性修改: $command")
            } catch (error: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", error.code)
            }
        }

        for (command in listOf("getprop ro.product.model", "getprop ro.build.fingerprint")) {
            RootCommandGuard.verifySafe(command)
            assertTrue("只读 getprop 不应被拦截: $command", RootCommandGuard.violations(command).isEmpty())
        }
    }

    @Test
    fun `RootCommandGuard 阻断动态展开绕过危险命令检查`() {
        val commands = listOf(
            "setenforce\${IFS}0",
            "setprop\${IFS}ro.product.model\${IFS}TestPhone",
            "\$(printf setprop) ro.product.model TestPhone",
            "`printf resetprop` -n ro.build.fingerprint spoofed",
        )
        for (command in commands) {
            assertTrue("动态展开不得绕过 Root 护栏: $command", RootCommandGuard.violations(command).isNotEmpty())
        }
    }

    @Test
    fun `RootCommandGuard 阻断二次 shell 与间接命令执行`() {
        val commands = listOf(
            "x='reboot'; y='bootloader'; eval \"\$x \$y\"",
            "payload='setprop'; eval \"\$payload ro.product.model spoofed\"",
            "x='dd if=/data/local/tmp/x of=/dev/block/sda'; sh -c \"\$x\"",
            "bash -c \"mount -o remount,rw /system\"",
            "source \"\$SCRIPT\"",
            ". \"\$SCRIPT\"",
            "echo safe\n. \"\$SCRIPT\"",
        )
        for (command in commands) {
            assertTrue("二次 shell 不得绕过 Root 护栏: $command", RootCommandGuard.violations(command).isNotEmpty())
            try {
                RootCommandGuard.verifySafe(command)
                fail("二次 shell 应被阻断: $command")
            } catch (error: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", error.code)
            }
        }
    }

    @Test
    fun `RootCommandGuard 降级档补上命名空间本该承担的那一段`() {
        // 最大档：`mount -o remount,rw /data` 是正常运维，不该拦；严格档见下。
        RootCommandGuard.verifySafe("mount -o remount,rw /data", RootCommandGuard.Profile.MAXIMUM)
        assertTrue(RootCommandGuard.violations("mount -o remount,rw /data", RootCommandGuard.Profile.MAXIMUM).isEmpty())
        assertTrue("严格档必须拦 mount", RootCommandGuard.violations("mount -o remount,rw /data").isNotEmpty())

        val degradedOnly = listOf(
            "mount -o remount,rw /data",
            "mount -t tmpfs tmpfs /mnt/scratch",
            "umount /dev/block/bootdevice/by-name/userdata",
            "dd if=/data/local/tmp/x.img of=/data/local/tmp/y.img",
            "echo x > /dev/kmsg",
            "chmod 666 /dev/block/sda",
            "insmod /data/local/tmp/module.ko",
            "echo 1 > /proc/sys/kernel/perf_event_paranoid",
            "cat /dev/mem",
        )
        for (cmd in degradedOnly) {
            assertTrue("降级档应命中: $cmd", RootCommandGuard.violations(cmd, RootCommandGuard.Profile.STRICT).isNotEmpty())
            try {
                RootCommandGuard.verifySafe(cmd, RootCommandGuard.Profile.STRICT)
                fail("降级档应拦下: $cmd")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
            }
        }

        // 降级档不是「全都不许」：只读观察类命令照旧放行。
        for (cmd in listOf("ls -la /data/data", "cat /proc/version", "echo hello", "ps -ef | grep node")) {
            RootCommandGuard.verifySafe(cmd, RootCommandGuard.Profile.STRICT)
        }
    }

    @Test
    fun `最大档仍拦数据销毁电源与块设备`() {
        val blocked = listOf(
            "rm -rf /data",
            "rm -rf /data/*",
            "rm -rf /data/system",
            "rm -rf /data/adb",
            "x=data; rm -rf /\$x",
            "rm -rf /d*",
            "rm -rf /[d]ata",
            "rm -rf /data/../data",
            "rm -rf /data//",
            "cp /tmp/x /system/\$name",
            "mv /tmp/x /data/../system",
            "find /data -delete",
            "reboot",
            "busybox reboot",
            "poweroff",
            "svc power reboot",
            "cmd power shutdown",
            "am broadcast -a android.intent.action.REBOOT",
            "kill -9 1",
            "echo b > /proc/sysrq-trigger",
            "cp /dev/block/by-name/boot /data/local/tmp/boot.img",
            "cat /dev/block/sda",
            "echo x > /system/etc/hosts",
            "cp /data/foo /system/foo",
            "setprop sys.powerctl reboot",
            "su -c reboot",
            "adb shell reboot",
        )
        for (cmd in blocked) {
            assertTrue(
                "最大档仍应拦截: $cmd",
                RootCommandGuard.violations(cmd, RootCommandGuard.Profile.MAXIMUM).isNotEmpty(),
            )
        }

        val allowed = listOf(
            "rm -rf /data/local/tmp/build",
            "rm -rf /data/adb/modules/foo",
            "echo reboot",
            "echo \"run mount now\"",
            "mount | head -1",
            "setprop dsh.probe 1",
            "dd if=/dev/zero of=/data/local/tmp/x",
            "cp /system/foo /data/foo",
        )
        for (cmd in allowed) {
            assertTrue(
                "最大档不应误杀: $cmd（命中 ${RootCommandGuard.violations(cmd, RootCommandGuard.Profile.MAXIMUM)}）",
                RootCommandGuard.violations(cmd, RootCommandGuard.Profile.MAXIMUM).isEmpty(),
            )
        }
    }

    @Test
    fun `多行脚本的第二行仍按命令起点检查`() {
        val blocked = listOf(
            "echo safe\nreboot",
            "echo safe\nrm -rf /data",
            "echo safe\ncp /data/foo /system/foo",
            "echo safe\nadb shell reboot",
            "if true; then reboot; fi",
            "(reboot)",
            "xargs reboot",
        )
        for (cmd in blocked) {
            assertTrue(
                "换行后的危险命令不能被漏掉: ${cmd.replace("\n", "\\n")}",
                RootCommandGuard.violations(cmd, RootCommandGuard.Profile.MAXIMUM).isNotEmpty(),
            )
        }
        // 注释/echo 里的词仍然不误杀。
        assertTrue(
            RootCommandGuard.violations("echo \"run mount now\"\necho reboot", RootCommandGuard.Profile.MAXIMUM).isEmpty(),
        )
    }

    @Test
    fun `RootCommandRunner 的原生电源动作不过护栏`() {
        val previous = RootCommandRunner.processLauncher
        try {
            RootCommandRunner.processLauncher = { FakeProcess("", 0) }
            assertNull(RootCommandRunner.runSystemAction("reboot").errorCode)
            assertEquals(0, RootCommandRunner.runSystemAction("reboot -p").exitCode)
        } finally {
            RootCommandRunner.processLauncher = previous
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
            val root = RootEngineAccess.Status(
                enabled = true,
                mode = RootEngineAccess.Mode.READ_WRITE,
                guardProfile = RootCommandGuard.Profile.STRICT,
                kernelSuStatus = status,
                reason = "NOT_READY",
                degraded = false,
                degradations = emptyList(),
                suVersion = null,
            )
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

    // ---------------------------------------------------------------- 分级与审批放宽

    @Test
    fun `RootCommandGuard 四级风险分类与多规则取最高档`() {
        // Tier 0: 普通运维与 /dev/null 重定向直接放行
        for (safe in listOf("ls -la /data/data 2>/dev/null", "command -v su >/dev/null 2>&1", "echo reboot")) {
            val low = RootCommandGuard.classify(safe, RootCommandGuard.Profile.STRICT)
            assertEquals("常规命令应为 LOW: $safe", RootCommandGuard.RiskTier.LOW, low.tier)
            assertEquals(RootCommandGuard.ApprovalRoute.ALLOW, low.route)
        }

        // Tier 1: 严格档走 dsh 官方审批，最大档直接放行
        for (elevated in listOf(
            "mount -o remount,rw /data",
            "setprop dsh.probe 1",
            "pid=$(pidof com.example.app); echo \$pid",
            "sh -c 'ls /data/local/tmp'",
            "echo x > /dev/kmsg",
        )) {
            val strict = RootCommandGuard.classify(elevated, RootCommandGuard.Profile.STRICT)
            assertEquals("应为 ELEVATED: $elevated", RootCommandGuard.RiskTier.ELEVATED, strict.tier)
            assertEquals(RootCommandGuard.ApprovalRoute.DSH_APPROVAL, strict.route)
            assertFalse(strict.requiresHostConfirm)

            val maximum = RootCommandGuard.classify(elevated, RootCommandGuard.Profile.MAXIMUM)
            assertEquals(RootCommandGuard.RiskTier.ELEVATED, maximum.tier)
            assertEquals("最大档应直接放行 Tier 1: $elevated", RootCommandGuard.ApprovalRoute.ALLOW, maximum.route)
        }

        // Tier 2: 整机高危无论严格档还是最大档均走 HOST_CONFIRM（不硬拦截，需 App 页确认）
        for (high in listOf("reboot", "setenforce 0", "echo 127.0.0.1 a > /system/etc/hosts", "su -c id")) {
            for (profile in RootCommandGuard.Profile.entries) {
                val res = RootCommandGuard.classify(high, profile)
                assertEquals("应为 HIGH ($profile): $high", RootCommandGuard.RiskTier.HIGH, res.tier)
                assertEquals(RootCommandGuard.ApprovalRoute.HOST_CONFIRM, res.route)
                assertTrue(res.requiresHostConfirm)
                assertTrue(res.bypassSandboxWrapper)
            }
        }

        // Tier 3: 不可逆变砖/删库红线不硬拦截，但必须打开 App 极危警告页
        for (critical in listOf(
            "reboot bootloader",
            "fastboot flash boot boot.img",
            "dd if=/dev/zero of=/dev/block/sda",
            "rm -rf /data",
            "cat /dev/mem",
        )) {
            for (profile in RootCommandGuard.Profile.entries) {
                val res = RootCommandGuard.classify(critical, profile)
                assertEquals("应为 CRITICAL ($profile): $critical", RootCommandGuard.RiskTier.CRITICAL, res.tier)
                assertEquals(RootCommandGuard.ApprovalRoute.APP_WARNING, res.route)
                assertTrue(res.requiresHostConfirm)
                assertTrue(res.bypassSandboxWrapper)
            }
        }

        // 多规则混合：高风险不会被低风险掩盖
        val mixed = RootCommandGuard.classify("x=$(id); rm -rf /data", RootCommandGuard.Profile.MAXIMUM)
        assertEquals(RootCommandGuard.RiskTier.CRITICAL, mixed.tier)
        assertEquals(RootCommandGuard.ApprovalRoute.APP_WARNING, mixed.route)
    }

    @Test
    fun `RootApprovalRegistry 挑战绑定摘要一次性消费且拒绝越权兑换高危命令`() {
        RootApprovalRegistry.clear()
        try {
            val script = "mount -o remount,rw /data"
            val assessment = RootCommandGuard.classify(script, RootCommandGuard.Profile.STRICT)
            val id = RootApprovalRegistry.issueChallenge(script, assessment, nowMs = 1_000L)

            // 篡改脚本内容：拒绝
            try {
                RootApprovalRegistry.consumeDshApproval(id, "mount -o remount,rw /cache", assessment, RootApprovalRegistry.MODE_DSH_APPROVED, nowMs = 2_000L)
                fail("篡改脚本摘要应拒绝")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_INVALID", e.code)
            }
            // 挑战已被原子消费，重放拒绝
            try {
                RootApprovalRegistry.consumeDshApproval(id, script, assessment, RootApprovalRegistry.MODE_DSH_APPROVED, nowMs = 2_000L)
                fail("已消费挑战不得重放")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_INVALID", e.code)
            }

            // 过期拒绝
            val expiredId = RootApprovalRegistry.issueChallenge(script, assessment, nowMs = 1_000L)
            try {
                RootApprovalRegistry.consumeDshApproval(
                    expiredId, script, assessment, RootApprovalRegistry.MODE_DSH_FULL_ACCESS,
                    nowMs = 1_000L + RootApprovalRegistry.CHALLENGE_TTL_MS + 1,
                )
                fail("过期挑战应拒绝")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_EXPIRED", e.code)
            }

            // 正常兑换 dsh_full_access（尊重 dsh 完全权限）
            val validId = RootApprovalRegistry.issueChallenge(script, assessment, nowMs = 1_000L)
            val grant = RootApprovalRegistry.consumeDshApproval(
                validId, script, assessment, RootApprovalRegistry.MODE_DSH_FULL_ACCESS, nowMs = 2_000L,
            )
            assertEquals(RootApprovalSource.DSH_FULL_ACCESS, grant.source)

            // HIGH / CRITICAL 绝不允许用 consumeDshApproval 绕过 App 警告确认页
            val criticalScript = "rm -rf /data"
            val criticalAssessment = RootCommandGuard.classify(criticalScript, RootCommandGuard.Profile.MAXIMUM)
            val critId = RootApprovalRegistry.issueChallenge(criticalScript, criticalAssessment, nowMs = 1_000L)
            try {
                RootApprovalRegistry.consumeDshApproval(
                    critId, criticalScript, criticalAssessment, RootApprovalRegistry.MODE_DSH_FULL_ACCESS, nowMs = 2_000L,
                )
                fail("极危命令不得仅凭访客审批兑换")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_INVALID", e.code)
            }
        } finally {
            RootApprovalRegistry.clear()
        }
    }

    @Test
    fun `RootApprovalCoordinator 支持同意拒绝取消超时并发互斥与显示防欺骗`() {
        val prevPresenter = RootApprovalCoordinator.presenter
        val prevDismiss = RootApprovalCoordinator.dismissNotificationHook
        RootApprovalCoordinator.dismissNotificationHook = {}
        try {
            // BiDi 字符过滤
            val (cleaned, stripped) = RootApprovalCoordinator.sanitizeScriptForDisplay("echo \u202Eevil")
            assertTrue(stripped)
            assertEquals("echo evil", cleaned)

            // 1. 极危命令通过 App 警告页确认 -> APP_WARNING_CONFIRMED
            val critScript = "dd if=/dev/zero of=/dev/block/sda"
            val critAssessment = RootCommandGuard.classify(critScript, RootCommandGuard.Profile.MAXIMUM)
            RootApprovalCoordinator.presenter = { _, req ->
                assertEquals(critScript, req.script)
                assertEquals("恢复测试分区", req.justification)
                RootApprovalCoordinator.resolve(req.requestId, RootApprovalCoordinator.Outcome.APPROVED)
                true
            }
            val grant = RootApprovalCoordinator.requestApproval(
                context = null,
                requestId = "req-1",
                script = critScript,
                assessment = critAssessment,
                justification = "恢复测试分区",
                timeoutMs = 2_000L,
            )
            assertEquals(RootApprovalSource.APP_WARNING_CONFIRMED, grant.source)

            // 2. 拒绝 -> ROOT_APPROVAL_REJECTED
            RootApprovalCoordinator.presenter = { _, req ->
                RootApprovalCoordinator.resolve(req.requestId, RootApprovalCoordinator.Outcome.REJECTED)
                true
            }
            try {
                RootApprovalCoordinator.requestApproval(null, "req-2", critScript, critAssessment, timeoutMs = 2_000L)
                fail("拒绝应抛 ROOT_APPROVAL_REJECTED")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_REJECTED", e.code)
            }

            // 3. 无法打开确认页 -> ROOT_APPROVAL_UNAVAILABLE
            RootApprovalCoordinator.presenter = { _, _ -> false }
            try {
                RootApprovalCoordinator.requestApproval(null, "req-3", critScript, critAssessment, timeoutMs = 2_000L)
                fail("拉起失败应抛 ROOT_APPROVAL_UNAVAILABLE")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_UNAVAILABLE", e.code)
            }

            // 4. 超时 -> ROOT_APPROVAL_TIMEOUT
            RootApprovalCoordinator.presenter = { _, _ -> true }
            try {
                RootApprovalCoordinator.requestApproval(null, "req-4", critScript, critAssessment, timeoutMs = 80L)
                fail("超时应抛 ROOT_APPROVAL_TIMEOUT")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_TIMEOUT", e.code)
            }

            // 5. 并发第二条立刻报 ROOT_APPROVAL_BUSY，不占满线程池
            val presentedLatch = CountDownLatch(1)
            val activeReqId = AtomicReference<String?>()
            RootApprovalCoordinator.presenter = { _, req ->
                activeReqId.set(req.requestId)
                presentedLatch.countDown()
                true
            }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<Any> {
                    try {
                        RootApprovalCoordinator.requestApproval(null, "req-busy-1", critScript, critAssessment, timeoutMs = 5_000L)
                    } catch (e: RuntimeFailure) {
                        e.code
                    }
                }
                assertTrue(presentedLatch.await(2, TimeUnit.SECONDS))
                try {
                    RootApprovalCoordinator.requestApproval(null, "req-busy-2", critScript, critAssessment, timeoutMs = 2_000L)
                    fail("并发第二条应报 ROOT_APPROVAL_BUSY")
                } catch (e: RuntimeFailure) {
                    assertEquals("ROOT_APPROVAL_BUSY", e.code)
                }
                RootApprovalCoordinator.cancel(activeReqId.get())
                assertEquals("ROOT_APPROVAL_CANCELLED", first.get(2, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        } finally {
            RootApprovalCoordinator.cancelAll()
            RootApprovalCoordinator.presenter = prevPresenter
            RootApprovalCoordinator.dismissNotificationHook = prevDismiss
        }
    }

    @Test
    fun `RootCommandRunner runApproved 对高危直通且校验凭证摘要防篡改`() {
        val previous = RootCommandRunner.processLauncher
        val prevPresenter = RootApprovalCoordinator.presenter
        val prevDismiss = RootApprovalCoordinator.dismissNotificationHook
        RootApprovalCoordinator.dismissNotificationHook = {}
        val launchedScripts = mutableListOf<String>()
        RootCommandRunner.processLauncher = { argv ->
            launchedScripts += argv[2]
            FakeProcess("ok\n", 0)
        }
        try {
            // Tier 1 mount 放开 setpriv 能力裁剪
            val mountScript = "mount -o remount,rw /data"
            val mountAssessment = RootCommandGuard.classify(mountScript, RootCommandGuard.Profile.STRICT)
            val mountId = RootApprovalRegistry.issueChallenge(mountScript, mountAssessment)
            val mountGrant = RootApprovalRegistry.consumeDshApproval(mountId, mountScript, mountAssessment, RootApprovalRegistry.MODE_DSH_APPROVED)
            val mountRes = RootCommandRunner.runApproved(mountScript, mountGrant)
            assertTrue(mountRes.ok)
            assertFalse("已确认的 mount 不应再套 setpriv 裁剪", decodeInner(launchedScripts.last()).contains("setpriv"))

            // Tier 3 经 App 警告页确认后直连执行（不加 /dev/block 遮蔽与 setpriv）
            val critScript = "dd if=/dev/zero of=/dev/block/sda"
            val critAssessment = RootCommandGuard.classify(critScript, RootCommandGuard.Profile.MAXIMUM)
            RootApprovalCoordinator.presenter = { _, req ->
                RootApprovalCoordinator.resolve(req.requestId, RootApprovalCoordinator.Outcome.APPROVED)
                true
            }
            val critGrant = RootApprovalCoordinator.requestApproval(null, "req-run-1", critScript, critAssessment)
            val critRes = RootCommandRunner.runApproved(critScript, critGrant)
            assertTrue(critRes.ok)
            assertFalse("App 警告确认后的极危命令不应被外层 unshare/block-mask 拦截", launchedScripts.last().contains("unshare"))

            // 拿旧凭证跑别的脚本必须拒绝
            try {
                RootCommandRunner.runApproved("rm -rf /data", critGrant)
                fail("脚本摘要不匹配必须拒绝")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_APPROVAL_INVALID", e.code)
            }
        } finally {
            RootCommandRunner.processLauncher = previous
            RootApprovalCoordinator.presenter = prevPresenter
            RootApprovalCoordinator.dismissNotificationHook = prevDismiss
            RootApprovalRegistry.clear()
        }
    }

    @Test
    fun `DeviceBridgeServer 根通道端到端：低风险直通、中危 dsh 审批、高危极危必须走 App 警告页`() {
        val prevLauncher = RootCommandRunner.processLauncher
        val prevPresenter = RootApprovalCoordinator.presenter
        val prevDismiss = RootApprovalCoordinator.dismissNotificationHook
        RootApprovalCoordinator.dismissNotificationHook = {}
        RootApprovalRegistry.clear()
        val launched = mutableListOf<List<String>>()
        RootCommandRunner.processLauncher = { argv ->
            launched += argv
            FakeProcess("ran-ok\n", 0)
        }
        val token = "A".repeat(43)
        val strictRootStatus = RootEngineAccess.Status(
            enabled = true,
            mode = RootEngineAccess.Mode.READ_WRITE,
            guardProfile = RootCommandGuard.Profile.STRICT,
            kernelSuStatus = KernelSuProbe.STATUS_READY,
            reason = KernelSuProbe.REASON_READY,
            degraded = true,
            degradations = listOf(RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE),
            suVersion = "3.1.0:KernelSU",
        )
        val bridge = DeviceBridgeServer(
            shizuku = null,
            runner = DeviceCommandRunner { _, _ -> },
            token = token,
            port = 0,
            shellEnabled = { false },
            rootStatusProvider = { strictRootStatus },
            highRiskPermissionsProvider = { HighRiskPermissions.COMMAND_CATEGORIES_ONLY },
        )
        bridge.start()
        try {
            fun post(command: String, param: String): JSONObject {
                val conn = URL("http://127.0.0.1:${bridge.localPort}/device-command").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                val payload = JSONObject().put("command", command).put("param", param).toString().toByteArray(StandardCharsets.UTF_8)
                conn.outputStream.use { it.write(payload) }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                return JSONObject(body)
            }

            // 1. Tier 0 直接执行
            val lowRes = post("shell", "id -u")
            assertTrue(lowRes.getBoolean("ok"))
            assertEquals(1, launched.size)

            // 2. Tier 1 (STRICT) -> 返回 ROOT_APPROVAL_REQUIRED，不拉 su
            val elevatedScript = "mount -o remount,rw /data"
            val tier1Challenge = post("shell", elevatedScript)
            assertFalse(tier1Challenge.getBoolean("ok"))
            assertEquals("ROOT_APPROVAL_REQUIRED", tier1Challenge.getString("errorCode"))
            assertEquals(1, launched.size)
            val challengeJson = JSONObject(tier1Challenge.getString("text"))
            assertEquals("elevated", challengeJson.getString("tier"))
            assertEquals("dsh_approval", challengeJson.getString("route"))
            assertFalse(challengeJson.getBoolean("requiresHostConfirm"))
            assertTrue(challengeJson.getBoolean("degraded"))

            // 2b. 凭 challengeId 经 dsh_full_access 放行执行
            val tier1Exec = post(
                "shellApproved",
                JSONObject()
                    .put("script", elevatedScript)
                    .put("challengeId", challengeJson.getString("challengeId"))
                    .put("approvalMode", "dsh_full_access")
                    .toString(),
            )
            assertTrue(tier1Exec.getBoolean("ok"))
            assertEquals(2, launched.size)

            // 3. Tier 3 (CRITICAL) -> 不硬拦截，返回 app_warning；执行时必须经宿主警告页确认
            val critScript = "rm -rf /data"
            val tier3Challenge = post("shell", critScript)
            assertFalse(tier3Challenge.getBoolean("ok"))
            assertEquals("ROOT_APPROVAL_REQUIRED", tier3Challenge.getString("errorCode"))
            assertEquals(2, launched.size)
            val critJson = JSONObject(tier3Challenge.getString("text"))
            assertEquals("critical", critJson.getString("tier"))
            assertEquals("app_warning", critJson.getString("route"))
            assertTrue(critJson.getBoolean("requiresHostConfirm"))

            // 3b. 宿主警告页拒绝 -> 不执行 su
            var presenterCalls = 0
            RootApprovalCoordinator.presenter = { _, req ->
                presenterCalls += 1
                assertEquals(RootCommandGuard.ApprovalRoute.APP_WARNING, req.assessment.route)
                RootApprovalCoordinator.resolve(req.requestId, RootApprovalCoordinator.Outcome.REJECTED)
                true
            }
            val critRejected = post(
                "shellApproved",
                JSONObject()
                    .put("script", critScript)
                    .put("challengeId", critJson.getString("challengeId"))
                    .put("approvalMode", "dsh_full_access")
                    .put("justification", "测试清理")
                    .toString(),
            )
            assertFalse(critRejected.getBoolean("ok"))
            assertEquals("ROOT_APPROVAL_REJECTED", critRejected.getString("errorCode"))
            assertEquals(1, presenterCalls)
            assertEquals(2, launched.size)

            // 3c. 宿主警告页同意 -> 执行 su
            RootApprovalCoordinator.presenter = { _, req ->
                presenterCalls += 1
                RootApprovalCoordinator.resolve(req.requestId, RootApprovalCoordinator.Outcome.APPROVED)
                true
            }
            val critApproved = post(
                "shellApproved",
                JSONObject()
                    .put("script", critScript)
                    .put("approvalMode", "dsh_full_access")
                    .toString(),
            )
            assertTrue(critApproved.getBoolean("ok"))
            assertEquals(2, presenterCalls)
            assertEquals(3, launched.size)
        } finally {
            bridge.stop()
            RootCommandRunner.processLauncher = prevLauncher
            RootApprovalCoordinator.presenter = prevPresenter
            RootApprovalCoordinator.dismissNotificationHook = prevDismiss
            RootApprovalRegistry.clear()
        }
    }

    @Test
    fun `高危权限 17 项细分硬编码拦截规则逐类验证`() {
        val sampleByCategory: Map<HighRiskCategory, String> = mapOf(
            HighRiskCategory.CORE_PARTITION_WRITE to "echo x > /system/etc/hosts",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION to "rm -rf /data/system",
            HighRiskCategory.SHARED_STORAGE_DELETE to "rm -rf /sdcard/DCIM",
            HighRiskCategory.APP_PRIVATE_DATA_MODIFY to "rm -rf /data/data/com.example.app/files",
            HighRiskCategory.SYSTEM_PERMISSIONS_OWNER to "chmod 777 /system/bin/sh",
            HighRiskCategory.DEV_NODE_WRITE to "echo x > /dev/kmsg",
            HighRiskCategory.DYNAMIC_SHELL_EVAL to "eval \"echo hi\"",
            HighRiskCategory.FILESYSTEM_MOUNT to "mount -o remount,rw /data",
            HighRiskCategory.RAW_DEVICE_IMAGE_TOOL to "dd if=/dev/zero of=/data/local/tmp/x",
            HighRiskCategory.KERNEL_MODULE_CONTROL to "insmod /data/local/tmp/mod.ko",
            HighRiskCategory.KERNEL_SYSFS_PROC_WRITE to "echo 1 > /proc/sys/kernel/perf_event_paranoid",
            HighRiskCategory.SYSTEM_PROPERTIES_WRITE to "setprop dsh.probe 1",
            HighRiskCategory.PACKAGE_INSTALL_REMOVE to "pm uninstall com.example.app",
            HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE to "stop zygote",
            HighRiskCategory.SELINUX_SECURITY_POLICY to "setenforce 0",
            HighRiskCategory.DEVICE_POWER_CONTROL to "reboot",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW to "cat /dev/block/sda",
        )
        assertEquals("必须覆盖全部 17 类高危命令细分权限", HighRiskCategory.COMMAND_CATEGORIES.size, sampleByCategory.size)
        assertEquals("应包含 8 类宿主能力放开细分开关", 8, HighRiskCategory.HOST_UNLOCK_CATEGORIES.size)
        assertEquals("总计应为 25 类高危权限细分开关", 25, HighRiskCategory.entries.size)

        // 1. 低风险常规命令在总开关关闭时依然放行
        val safeCommands = listOf(
            "ls -la /data/data",
            "cat /proc/version",
            "getprop ro.product.model",
            "pm list packages",
            "chmod +x /data/local/tmp/run.sh",
            "rm -rf /data/local/tmp/build",
            "rm -f /data/local/tmp/*.tmp",
            "tar -czf /sdcard/backup.tar.gz /data/data/com.test.app/files",
            "cp /data/data/com.test.app/files/log.txt /data/local/tmp/log.txt",
        )
        for (cmd in safeCommands) {
            val assessment = RootCommandGuard.verifyPermitted(
                cmd,
                RootCommandGuard.Profile.STRICT,
                HighRiskPermissions.ALL_DISABLED,
            )
            assertTrue("安全命令不应命中高危分类: $cmd", assessment.categories.isEmpty())
        }

        // 额外变体：cp/sed -i 修改应用私有数据、/storage/self/primary 别名删除必须命中对应分类
        assertTrue(
            HighRiskCategory.APP_PRIVATE_DATA_MODIFY in RootCommandGuard.classify(
                "cp /data/local/tmp/x /data/data/com.example.app/shared_prefs/a.xml",
            ).categories,
        )
        assertTrue(
            HighRiskCategory.APP_PRIVATE_DATA_MODIFY in RootCommandGuard.classify(
                "sed -i 's/false/true/' /data/data/com.example.app/shared_prefs/a.xml",
            ).categories,
        )
        assertTrue(
            HighRiskCategory.SHARED_STORAGE_DELETE in RootCommandGuard.classify(
                "rm -rf /storage/self/primary/DCIM",
            ).categories,
        )
        // /sdcard 通配删除仅归入 SHARED_STORAGE_DELETE，不误伤 SYSTEM_DATA_DESTRUCTION；
        // 但包含路径穿越的 /sdcard/../data 仍归入 SYSTEM_DATA_DESTRUCTION。
        assertEquals(
            listOf(HighRiskCategory.SHARED_STORAGE_DELETE),
            RootCommandGuard.classify("rm -rf /sdcard/*").categories,
        )
        assertEquals(
            listOf(HighRiskCategory.SHARED_STORAGE_DELETE),
            RootCommandGuard.classify("rm -f /sdcard/Download/*.apk").categories,
        )
        assertTrue(
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION in RootCommandGuard.classify("rm -rf /sdcard/../data").categories,
        )
        assertTrue(
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION in RootCommandGuard.classify("rm -rf /data/local/tmp/../../system").categories,
        )

        // 绕过回归：行内环境变量赋值、带参包装器、SAR 路径、find -exec、unlink/rmdir、SELinux tee/cp
        val bypassRegressions: List<Pair<String, HighRiskCategory>> = listOf(
            "A=1 reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "FOO=1 rm -rf /data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "LANG=C pm uninstall com.example.app" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "X=1 su -c id" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "A=1 setprop ro.debuggable 1" to HighRiskCategory.SYSTEM_PROPERTIES_WRITE,
            "A=1 mount -o remount,rw /data" to HighRiskCategory.FILESYSTEM_MOUNT,
            "env -i reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "timeout 5 rm -rf /data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "nice -n 10 pm clear com.example.app" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "busybox -- reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "/bin/reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "/sbin/reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "/bin/rm -rf /data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "/system/xbin/su -c id" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "find /data/system -exec rm -rf {} +" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "find /sdcard/DCIM -exec rm -rf {} \\;" to HighRiskCategory.SHARED_STORAGE_DELETE,
            "unlink /data/system/locksettings.db" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "rmdir /sdcard/DCIM" to HighRiskCategory.SHARED_STORAGE_DELETE,
            "unlink /data/data/com.example.app/files/a" to HighRiskCategory.APP_PRIVATE_DATA_MODIFY,
            "echo 0 | tee /sys/fs/selinux/enforce" to HighRiskCategory.SELINUX_SECURITY_POLICY,
            "cp /data/local/tmp/z /sys/fs/selinux/enforce" to HighRiskCategory.SELINUX_SECURITY_POLICY,
        )
        for ((script, expectedCategory) in bypassRegressions) {
            val assessment = RootCommandGuard.classify(script, RootCommandGuard.Profile.MAXIMUM)
            assertTrue(
                "绕过变体必须命中 $expectedCategory: $script（实际命中 ${assessment.categories}）",
                expectedCategory in assessment.categories,
            )
            try {
                RootCommandGuard.verifyPermitted(script, RootCommandGuard.Profile.MAXIMUM, HighRiskPermissions.ALL_DISABLED)
                fail("绕过变体在总开关关闭时必须被硬拦截: $script")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
            }
        }
        assertEquals(RootCommandGuard.RiskTier.HIGH, HighRiskCategory.SYSTEM_PROPERTIES_WRITE.tier)

        // 2. 逐类校验：总开关关必拦；总开关开但该细分关必拦；仅开对应细分时放行
        for ((category, script) in sampleByCategory) {
            // 总开关关
            try {
                RootCommandGuard.verifyPermitted(script, RootCommandGuard.Profile.MAXIMUM, HighRiskPermissions.ALL_DISABLED)
                fail("总开关关闭时必须硬拦截: $category -> $script")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
                assertTrue(e.message?.contains("总开关已关闭") == true)
            }

            // 总开关开，但所有细分项都关
            val masterOnly = HighRiskPermissions(enabled = true)
            try {
                RootCommandGuard.verifyPermitted(script, RootCommandGuard.Profile.MAXIMUM, masterOnly)
                fail("细分开关关闭时必须硬拦截: $category -> $script")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
                assertTrue(e.message?.contains("未开启高危权限细分开关") == true)
            }

            // 仅开启命中的所有分类（少数命令如 echo x > /system/etc/hosts 只命中一类，部分命令可能跨两类）
            val matchedCats = RootCommandGuard.classify(script, RootCommandGuard.Profile.MAXIMUM).categories
            assertTrue("样本命令应命中自身分类 $category: $script", category in matchedCats)
            val onlyMatched = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith { it in matchedCats },
            )
            RootCommandGuard.verifyPermitted(script, RootCommandGuard.Profile.MAXIMUM, onlyMatched)
        }

        // 3. 组合脚本命中「已开启分类 + 未开启分类」必须依然拦截
        val partialPerms = HighRiskPermissions(
            enabled = true,
            categories = HighRiskCategory.entries.associateWith { it == HighRiskCategory.SYSTEM_PROPERTIES_WRITE },
        )
        try {
            RootCommandGuard.verifyPermitted(
                "setprop dsh.probe 1 && reboot",
                RootCommandGuard.Profile.MAXIMUM,
                partialPerms,
            )
            fail("组合脚本只要命中任一未开启细分项就必须硬拦截")
        } catch (e: RuntimeFailure) {
            assertEquals("ROOT_SECURITY_VIOLATION", e.code)
            assertTrue(e.message?.contains(HighRiskCategory.DEVICE_POWER_CONTROL.title) == true)
        }
    }

    @Test
    fun `DeviceBridgeServer 在高危权限关闭时直接硬拦截 Root、shellApproved 与 Shizuku 通道`() {
        val prevLauncher = RootCommandRunner.processLauncher
        val launched = mutableListOf<List<String>>()
        RootCommandRunner.processLauncher = { argv ->
            launched += argv
            FakeProcess("ran-ok\n", 0)
        }
        val token = "B".repeat(43)
        var currentPermissions = HighRiskPermissions.ALL_DISABLED
        var useRoot = true
        val strictRootStatus = RootEngineAccess.Status(
            enabled = true,
            mode = RootEngineAccess.Mode.READ_WRITE,
            guardProfile = RootCommandGuard.Profile.MAXIMUM,
            kernelSuStatus = KernelSuProbe.STATUS_READY,
            reason = KernelSuProbe.REASON_READY,
            degraded = false,
            degradations = emptyList(),
            suVersion = "3.1.0:KernelSU",
        )
        val bridge = DeviceBridgeServer(
            shizuku = null,
            runner = DeviceCommandRunner { _, _ -> },
            token = token,
            port = 0,
            shellEnabled = { true },
            rootStatusProvider = { if (useRoot) strictRootStatus else null },
            highRiskPermissionsProvider = { currentPermissions },
        )
        bridge.start()
        try {
            fun post(command: String, param: String): JSONObject {
                val conn = URL("http://127.0.0.1:${bridge.localPort}/device-command").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                val payload = JSONObject().put("command", command).put("param", param).toString().toByteArray(StandardCharsets.UTF_8)
                conn.outputStream.use { it.write(payload) }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                return JSONObject(body)
            }

            // 1. 总开关关闭：Root shell 直接硬拦截，不发 challenge、不拉 su
            val rootBlocked = post("shell", "setprop dsh.probe 1")
            assertFalse(rootBlocked.getBoolean("ok"))
            assertEquals("ROOT_SECURITY_VIOLATION", rootBlocked.getString("errorCode"))
            assertEquals(0, launched.size)

            // 2. 总开关关闭：shellApproved 也直接硬拦截，不能绕过
            val approvedBlocked = post(
                "shellApproved",
                JSONObject().put("script", "setprop dsh.probe 1").put("approvalMode", "dsh_full_access").toString(),
            )
            assertFalse(approvedBlocked.getBoolean("ok"))
            assertEquals("ROOT_SECURITY_VIOLATION", approvedBlocked.getString("errorCode"))
            assertEquals(0, launched.size)

            // 3. 总开关关闭：Shizuku shell 通道同样直接硬拦截（连 Shizuku 会话都不会建）
            useRoot = false
            val shizukuBlocked = post("shell", "pm uninstall com.example.app")
            assertFalse(shizukuBlocked.getBoolean("ok"))
            assertEquals("ROOT_SECURITY_VIOLATION", shizukuBlocked.getString("errorCode"))

            // 4. 开启总开关 + 开启 SYSTEM_PROPERTIES_WRITE 后，Root 最大档直接放行 setprop，但仍拦截未开启的 reboot
            useRoot = true
            currentPermissions = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith { it == HighRiskCategory.SYSTEM_PROPERTIES_WRITE },
            )
            val propAllowed = post("shell", "setprop dsh.probe 1")
            assertTrue(propAllowed.getBoolean("ok"))
            assertEquals(1, launched.size)

            val rebootStillBlocked = post("shell", "reboot")
            assertFalse(rebootStillBlocked.getBoolean("ok"))
            assertEquals("ROOT_SECURITY_VIOLATION", rebootStillBlocked.getString("errorCode"))
            assertEquals(1, launched.size)
        } finally {
            bridge.stop()
            RootCommandRunner.processLauncher = prevLauncher
        }
    }

    @Test
    fun `HighRiskPermissionStore parseUpdate 拒绝非法参数并接受增量更新`() {
        val (enabled, updates) = HighRiskPermissionStore.parseUpdate(
            enabledRaw = true,
            categoriesObj = JSONObject().put("device_power_control", true).put("filesystem_mount", false),
        )
        assertEquals(true, enabled)
        assertEquals(mapOf(HighRiskCategory.DEVICE_POWER_CONTROL to true, HighRiskCategory.FILESYSTEM_MOUNT to false), updates)

        try {
            HighRiskPermissionStore.parseUpdate(null, null)
            fail("空更新必须拒绝")
        } catch (e: RuntimeFailure) {
            assertEquals("HIGH_RISK_PERMISSION_INVALID", e.code)
        }

        try {
            HighRiskPermissionStore.parseUpdate(null, JSONObject().put("unknown_cat", true))
            fail("未知细分项必须拒绝")
        } catch (e: RuntimeFailure) {
            assertEquals("HIGH_RISK_PERMISSION_INVALID", e.code)
        }

        try {
            HighRiskPermissionStore.parseUpdate("true", null)
            fail("非布尔总开关必须拒绝")
        } catch (e: RuntimeFailure) {
            assertEquals("HIGH_RISK_PERMISSION_INVALID", e.code)
        }
    }

    @Test
    fun `ROOT_UNCONFINED_SANDBOX 与 ROOT_SKIP_APP_CONFIRM 开关开合可完整放开并恢复硬编码防护`() {
        val prevLauncher = RootCommandRunner.processLauncher
        val launched = mutableListOf<List<String>>()
        RootCommandRunner.processLauncher = { argv ->
            launched += argv
            FakeProcess("ok\n", 0)
        }
        val token = "C".repeat(43)
        var currentPermissions = HighRiskPermissions.ALL_DISABLED
        val strictRootStatus = RootEngineAccess.Status(
            enabled = true,
            mode = RootEngineAccess.Mode.READ_WRITE,
            guardProfile = RootCommandGuard.Profile.STRICT,
            kernelSuStatus = KernelSuProbe.STATUS_READY,
            reason = KernelSuProbe.REASON_READY,
            degraded = false,
            degradations = emptyList(),
            suVersion = "3.1.0:KernelSU",
        )
        val bridge = DeviceBridgeServer(
            shizuku = null,
            runner = DeviceCommandRunner { _, _ -> },
            token = token,
            port = 0,
            shellEnabled = { false },
            rootStatusProvider = { strictRootStatus },
            highRiskPermissionsProvider = { currentPermissions },
        )
        bridge.start()
        try {
            fun post(command: String, param: String): JSONObject {
                val conn = URL("http://127.0.0.1:${bridge.localPort}/device-command").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                val payload = JSONObject().put("command", command).put("param", param).toString().toByteArray(StandardCharsets.UTF_8)
                conn.outputStream.use { it.write(payload) }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                return JSONObject(body)
            }

            // 1. 开关关闭时，低风险 Root 命令仍经过 RootSecurityWrapper（含 unshare 沙箱包装）
            val sandboxed = post("shell", "id -u")
            assertTrue(sandboxed.getBoolean("ok"))
            assertEquals(1, launched.size)
            assertTrue("默认必须带 unshare 沙箱包装", launched.last().last().contains("unshare"))

            // 2. 开启 ROOT_UNCONFINED_SANDBOX 后，直接以无沙箱 Base64 方式执行（不含 unshare/setpriv）
            currentPermissions = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith { it == HighRiskCategory.ROOT_UNCONFINED_SANDBOX },
            )
            val unconfined = post("shell", "id -u")
            assertTrue(unconfined.getBoolean("ok"))
            assertEquals(2, launched.size)
            assertFalse("开启解除沙箱后不应再套 unshare", launched.last().last().contains("unshare"))
            assertFalse("开启解除沙箱后不应再套 setpriv", launched.last().last().contains("setpriv"))

            // 3. 仅开启 ROOT_SKIP_APP_CONFIRM 但未开启 DEVICE_POWER_CONTROL 时，reboot 仍硬拦截
            currentPermissions = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith { it == HighRiskCategory.ROOT_SKIP_APP_CONFIRM },
            )
            val blockedWithoutCategory = post("shell", "reboot")
            assertFalse(blockedWithoutCategory.getBoolean("ok"))
            assertEquals("ROOT_SECURITY_VIOLATION", blockedWithoutCategory.getString("errorCode"))
            assertEquals(2, launched.size)

            // 4. 同时开启 ROOT_SKIP_APP_CONFIRM + DEVICE_POWER_CONTROL 时，跳过应用警告页直通执行
            currentPermissions = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith {
                    it == HighRiskCategory.ROOT_SKIP_APP_CONFIRM || it == HighRiskCategory.DEVICE_POWER_CONTROL
                },
            )
            val autoAllowed = post("shell", "reboot")
            assertTrue(autoAllowed.getBoolean("ok"))
            assertEquals(3, launched.size)

            // 5. 关闭 ROOT_SKIP_APP_CONFIRM（保留 DEVICE_POWER_CONTROL）后，立刻恢复强制应用警告页审批
            currentPermissions = HighRiskPermissions(
                enabled = true,
                categories = HighRiskCategory.entries.associateWith { it == HighRiskCategory.DEVICE_POWER_CONTROL },
            )
            val restoredConfirm = post("shell", "reboot")
            assertFalse(restoredConfirm.getBoolean("ok"))
            assertEquals("ROOT_APPROVAL_REQUIRED", restoredConfirm.getString("errorCode"))
            assertEquals("host_confirm", JSONObject(restoredConfirm.getString("text")).getString("route"))
            assertEquals(3, launched.size)
        } finally {
            bridge.stop()
            RootCommandRunner.processLauncher = prevLauncher
        }
    }

    @Test
    fun `红队绕过回归：变量命令、通配符、ANSI-C、alias、管道Shell、cd相对路径、前置重定向、高危缺口与误伤修正`() {
        val redTeamCases: List<Pair<String, HighRiskCategory>> = listOf(
            // 1. 变量展开、通配符、ANSI-C 转义、mksh alias、管道与二次 Shell
            "a=reb; b=oot; \$a\$b" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "c=rm; p=/data; \$c -rf \$p" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "/system/bin/reboo?" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "/system/bin/r[e]boot" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "/bin/r? -rf /data" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "\$'\\x72\\x65\\x62\\x6f\\x6f\\x74'" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "alias x=reboot; x" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "echo cmVib290 | base64 -d | sh" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "printf 'rm -rf /data' | /system/bin/sh -s" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "sh -e -c 'echo x'" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "sh -xc 'id'" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "sh /data/local/tmp/a.sh" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "busybox ash -c id" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "awk 'BEGIN{system(\"reboot\")}'" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            "python3 -c 'import os; os.system(\"reboot\")'" to HighRiskCategory.DYNAMIC_SHELL_EVAL,
            // 2. 无前导斜杠的不确定路径
            "p=/data; rm -rf \$p" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "p=/system/etc/hosts; echo x > \$p" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            // 3. cd / pushd 相对路径绕过全链路
            "cd / && rm -rf data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cd /data && rm -rf system" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cd /data && cd system && rm -rf locksettings.db" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cd /data/local/tmp/../../system && rm -f build.prop" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cd /data/local/tmp/../../../system && rm -f build.prop" to HighRiskCategory.CORE_PARTITION_WRITE,
            "rm -rf /data/local/tmp/../../../system/bin" to HighRiskCategory.CORE_PARTITION_WRITE,
            "echo evil > /data/adb/modules/../service.d/backdoor.sh" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "rm -rf //data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "command reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "command -p reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "cd /sdcard && rm -rf DCIM" to HighRiskCategory.SHARED_STORAGE_DELETE,
            "cd /sdcard && find . -delete" to HighRiskCategory.SHARED_STORAGE_DELETE,
            "cd /sys/fs/selinux && echo 0 > enforce" to HighRiskCategory.SELINUX_SECURITY_POLICY,
            "cd /proc && echo b > sysrq-trigger" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "cd /dev/block && cat sda" to HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            "cd /dev/block && dd if=sda of=/data/local/tmp/dump" to HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            "cd /data/data/com.example.app && rm -rf shared_prefs" to HighRiskCategory.APP_PRIVATE_DATA_MODIFY,
            "cd /data/adb/service.d && echo evil > pwn.sh" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cd /system && sed -i -e 's/a/b/' build.prop" to HighRiskCategory.CORE_PARTITION_WRITE,
            // 4. 前置重定向与包装器路径/选项变体
            "</dev/null reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "2>/dev/null rm -rf /data" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "2>/dev/null pm uninstall com.example.app" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "</dev/null su -c id" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "/system/bin/env reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "busybox env -i -u PATH reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            "/system/vendor/bin/reboot" to HighRiskCategory.DEVICE_POWER_CONTROL,
            // 5. 高危文件与命令补全
            "rm -rf /" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "rm -rf --no-preserve-root /" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "rm -rf /system/bin" to HighRiskCategory.CORE_PARTITION_WRITE,
            "unlink /vendor/etc/hosts" to HighRiskCategory.CORE_PARTITION_WRITE,
            "echo evil > /data/adb/service.d/backdoor.sh" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "echo x > /data/system/locksettings.db" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "cp /data/local/tmp/evil /system/etc/hosts 2>/dev/null" to HighRiskCategory.CORE_PARTITION_WRITE,
            "cp -t /system/etc /data/local/tmp/hosts" to HighRiskCategory.CORE_PARTITION_WRITE,
            "tar -xf /data/local/tmp/p.tar -C /system" to HighRiskCategory.CORE_PARTITION_WRITE,
            "cp /data/local/tmp/x /data/data/com.example.app/shared_prefs/a.xml 2>/dev/null" to HighRiskCategory.APP_PRIVATE_DATA_MODIFY,
            "rm -rf /data/media/0/Android/data/com.example.app" to HighRiskCategory.APP_PRIVATE_DATA_MODIFY,
            "find /data/system -ok rm -rf {} +" to HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            "echo reboot | su" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "su 0 reboot" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "killall system_server" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "pkill -9 zygote" to HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            "settings put secure enabled_accessibility_services com.evil/.Svc" to HighRiskCategory.SYSTEM_PROPERTIES_WRITE,
            "pm grant com.example.app android.permission.READ_SMS" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "appops set com.example.app RUN_IN_BACKGROUND ignore" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "dpm set-device-owner com.example.app/.Admin" to HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            "magiskpolicy --live \"allow untrusted_app * * *\"" to HighRiskCategory.SELINUX_SECURITY_POLICY,
            "ksud sepolicy check \"allow untrusted_app * * *\"" to HighRiskCategory.SELINUX_SECURITY_POLICY,
            "dd if=/dev/block/sda of=/data/local/tmp/x" to HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
        )
        for ((script, expectedCategory) in redTeamCases) {
            val assessment = RootCommandGuard.classify(script, RootCommandGuard.Profile.MAXIMUM)
            assertTrue(
                "红队样例必须命中 $expectedCategory: $script（实际命中 ${assessment.categories}）",
                expectedCategory in assessment.categories,
            )
            try {
                RootCommandGuard.verifyPermitted(script, RootCommandGuard.Profile.MAXIMUM, HighRiskPermissions.ALL_DISABLED)
                fail("红队样例在总开关关闭时必须被硬拦截: $script")
            } catch (e: RuntimeFailure) {
                assertEquals("ROOT_SECURITY_VIOLATION", e.code)
            }
        }

        // 误伤修正：合法只读与临时工作目录命令必须保持 LOW（不拦截）
        val safeRegressions = listOf(
            "sed -n '1,5p' /system/build.prop",
            "find /data/local/tmp/build -delete",
            "find /data/adb/modules/demo -delete",
            "cd /data && ls -la",
            "cd /data/local/tmp && rm -rf build",
            "cd /system && sed -n '1,5p' build.prop",
            "settings get global adb_enabled",
            "[ -f /data/local/tmp/a ] && echo ok",
            "find . -name '*.txt'",
            "command -v su >/dev/null 2>&1",
            "command -V reboot",
        )
        for (cmd in safeRegressions) {
            val assessment = RootCommandGuard.verifyPermitted(
                cmd,
                RootCommandGuard.Profile.STRICT,
                HighRiskPermissions.ALL_DISABLED,
            )
            assertTrue("合法只读/工作区命令不应被误杀: $cmd（实际命中 ${assessment.categories}）", assessment.categories.isEmpty())
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
