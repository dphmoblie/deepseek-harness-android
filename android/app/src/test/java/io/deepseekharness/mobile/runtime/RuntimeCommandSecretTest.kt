package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-6 的**核心验收**：构造出来的 argv 里不包含任何密钥取值。
 *
 * 真机证据（修复前）：`ps -eo pid,args | grep -c DEEPSEEK_API_KEY` = **3** ——
 * App 把 `<PROVIDER>_API_KEY=<明文>`、`DSH_DEVICE_BRIDGE_TOKEN=<明文>`、
 * `DSH_MOBILE_AUTH_TOKEN=<明文>` 逐个作为 argv 元素追加到 PRoot 命令行，
 * 于是同 uid 的任何访客进程、普通 `ps`、bugreport / ANR / 厂商诊断都能读到取值。
 *
 * 本测试用**明显的哨兵取值**（不是真实密钥）驱动 [ProotArgvInput]：
 * 取值确实进入了构造 argv 的代码路径（所以断言不是空转），却一个都不许出现在 argv 里。
 * 除了逐值比对，还断言 argv 里不出现 `API_KEY=` / `TOKEN=` 这类**赋值形态** ——
 * 新增一个 provider、换一个变量名，都会在这里被挡住。
 */
class RuntimeCommandSecretTest {
    private val sentinelSecrets = linkedMapOf(
        "DEEPSEEK_API_KEY" to "sk-sentinel-not-in-argv",
        "DSH_CUSTOM_PROVIDER_GATEWAY_1_API_KEY" to "sk-sentinel-custom-not-in-argv",
        "DSH_DEVICE_BRIDGE_TOKEN" to "sentinel-device-bridge-token-value",
        "DSH_MOBILE_AUTH_TOKEN" to "sentinel-mobile-auth-token-value",
    )

    private val entrypoint = listOf("/usr/local/bin/dsh", "web", "--host", "127.0.0.1", "--port", "3080")

    private fun delivery(): RuntimeSecretDelivery = RuntimeSecretPolicy.delivery(sentinelSecrets, sentinelSecrets.size)

    private fun input(
        entrypoint: List<String> = this.entrypoint,
        secrets: RuntimeSecretDelivery = delivery(),
        harnessSession: Boolean = true,
        deviceBridgePort: Int? = 48879,
    ): ProotArgvInput = ProotArgvInput(
        runnerPath = "/data/app/io.deepseekharness.mobile/lib/arm64/libdsh_proot.so",
        rootPath = "/data/user/0/io.deepseekharness.mobile/no_backup/dsh-runtime/current",
        permissionMode = HarnessPermissionMode.WORKSPACE_WRITE.wireValue,
        entrypoint = entrypoint,
        bindMounts = listOf(ProotBindMount("/dev"), ProotBindMount("/etc/resolv.conf", "/etc/resolv.conf")),
        harnessPidFilePath = "/data/user/0/io.deepseekharness.mobile/no_backup/dsh-harness.pid"
            .takeIf { harnessSession },
        deviceBridgePort = deviceBridgePort,
        secrets = secrets,
    )

    @Test
    fun `no credential value ever reaches argv`() {
        // 证伪空转：取值确实在投递计划里（也就是确实流经了构造 argv 的代码路径）。
        val plan = delivery()
        assertEquals(sentinelSecrets, plan.environmentValues)
        assertEquals(RuntimeCommand.GUEST_SECRET_ENV_PATH, plan.guestEnvironmentFile)

        val argv = RuntimeCommand.prootArgv(input(secrets = plan))

        sentinelSecrets.forEach { (name, value) ->
            assertFalse("argv 里出现了 $name 的取值", argv.any { it.contains(value) })
            // 取值拼成赋值形态是逐值比对之外的第二种绕法，一并断言。
            assertFalse("argv 里出现了 $name= 赋值形态", argv.any { it.contains("$name=") })
        }
    }

    @Test
    fun `argv carries no credential assignment shape at all`() {
        val argv = RuntimeCommand.prootArgv(input())

        argv.forEach { entry ->
            assertFalse("argv 里出现了 API_KEY= 赋值：$entry", entry.contains("API_KEY="))
            assertFalse("argv 里出现了 TOKEN= 赋值：$entry", entry.contains("TOKEN="))
            assertFalse("argv 里出现了 SECRET= 赋值：$entry", entry.contains("SECRET="))
            assertFalse("argv 里出现了 sk- 形态的取值：$entry", entry.contains("sk-"))
        }
    }

    @Test
    fun `only non-secret assignments remain in argv`() {
        val argv = RuntimeCommand.prootArgv(input())

        val assignments = argv
            .filter { ASSIGNMENT.containsMatchIn(it) }
            .map { it.substringBefore('=') }

        assertEquals(
            listOf(
                "HOME",
                "USER",
                "LOGNAME",
                "LANG",
                "TERM",
                "DSH_PERMISSION_MODE",
                "PATH",
                "DSH_DEVICE_BRIDGE_PORT",
                "DSH_PIDFILE",
                "NODE_OPTIONS",
            ),
            assignments,
        )
    }

    @Test
    fun `the secret file path is the only secret-related string in argv`() {
        val argv = RuntimeCommand.prootArgv(input())

        // 路径必须作为独立元素出现（写在包装脚本里也一样能跑，但独立元素更可审计）。
        assertTrue(argv.contains(RuntimeCommand.GUEST_SECRET_ENV_PATH))
        assertEquals(1, argv.count { it == RuntimeCommand.GUEST_SECRET_ENV_PATH })
        // 包装脚本本身必须既无取值、也无路径、也无赋值形态。
        val script = RuntimeCommand.GUEST_SECRET_SOURCE_SCRIPT
        assertFalse(script.contains('='))
        assertFalse(script.contains(RuntimeCommand.GUEST_SECRET_ENV_PATH))
        sentinelSecrets.forEach { (_, value) -> assertFalse(script.contains(value)) }
    }

    @Test
    fun `the guest wrapper keeps the real entrypoint as the exec arguments`() {
        val argv = RuntimeCommand.prootArgv(input())

        val index = argv.indexOf("/bin/sh")
        assertTrue("有秘密投递时必须加入 sh 包装", index >= 0)
        assertEquals("-c", argv[index + 1])
        assertEquals(RuntimeCommand.GUEST_SECRET_SOURCE_SCRIPT, argv[index + 2])
        // sh -c '脚本' <$0> <$1=秘密文件路径> <argv...>：$0 只是占位，脚本 shift 掉 $1 之后
        // exec "$@" 跑的必须正好是真正的入口。
        assertEquals("dsh-mobile", argv[index + 3])
        assertEquals(RuntimeCommand.GUEST_SECRET_ENV_PATH, argv[index + 4])
        assertEquals(entrypoint, argv.drop(index + 5))
    }

    @Test
    fun `credential-free launches keep the unwrapped entrypoint`() {
        val argv = RuntimeCommand.prootArgv(
            input(secrets = RuntimeSecretDelivery.NONE, harnessSession = false, deviceBridgePort = null),
        )

        // 探测、自检、无凭据终端走的还是修复前那一套形态：不引入任何新变量。
        assertFalse(argv.contains("/bin/sh"))
        assertFalse(argv.contains(RuntimeCommand.GUEST_SECRET_ENV_PATH))
        assertEquals(entrypoint, argv.takeLast(entrypoint.size))
        assertFalse(argv.any { it.contains("DSH_PIDFILE") })
        assertFalse(argv.any { it.contains("DSH_DEVICE_BRIDGE_PORT") })
    }

    private companion object {
        /** 赋值形态：`NAME=` 且名字是大写环境变量风格。 */
        val ASSIGNMENT = Regex("^[A-Z_][A-Z0-9_]*=")
    }
}
