package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 秘密路由与访客环境文件渲染的纯策略测试。
 *
 * 这条策略决定了「哪些取值走 harness 自己的凭据文档、哪些必须经访客环境文件」，
 * 是 P0-6 修复里唯一带判断的部分，因此单独穷举。
 */
class RuntimeSecretPolicyTest {
    private val modelCredentials = linkedMapOf(
        "DEEPSEEK_API_KEY" to "sk-sentinel-model-key",
        "OPENAI_API_KEY" to "sk-sentinel-second-key",
    )
    private val ephemeralSecrets = linkedMapOf(
        "DSH_MOBILE_AUTH_TOKEN" to "sentinel-mobile-auth-token-value",
        "DSH_DEVICE_BRIDGE_TOKEN" to "sentinel-device-bridge-token-value",
    )

    @Test
    fun `model credentials stay out of the guest environment when the document carries them`() {
        val routed = RuntimeSecretPolicy.route(modelCredentials, ephemeralSecrets, modelCredentialsViaFile = true)

        // 环境层优先于凭据文档：模型凭据一旦落进环境就永远轮不到文档生效，
        // 因此文档可用时它们必须完全不出现在环境里。
        assertEquals(ephemeralSecrets, routed)
    }

    @Test
    fun `model credentials fall back to the guest environment file when the document is unusable`() {
        val routed = RuntimeSecretPolicy.route(modelCredentials, ephemeralSecrets, modelCredentialsViaFile = false)

        assertEquals(ephemeralSecrets + modelCredentials, routed)
    }

    @Test
    fun `an empty environment plan produces no file and no wrapper`() {
        val delivery = RuntimeSecretPolicy.delivery(emptyMap(), modelCredentialCount = 3)

        assertTrue(delivery.isEmpty)
        assertNull(delivery.guestEnvironmentFile)
        assertEquals(3, delivery.modelCredentialCount)
    }

    @Test
    fun `a non-empty environment plan names the fixed guest path`() {
        val delivery = RuntimeSecretPolicy.delivery(ephemeralSecrets, modelCredentialCount = 0)

        assertFalse(delivery.isEmpty)
        assertEquals(RuntimeCommand.GUEST_SECRET_ENV_PATH, delivery.guestEnvironmentFile)
        assertEquals(ephemeralSecrets, delivery.environmentValues)
    }

    @Test
    fun `rendered environment file is a sourceable assignment list`() {
        val text = RuntimeSecretPolicy.renderEnvironmentFile(
            linkedMapOf("DEEPSEEK_API_KEY" to "sk-sentinel-value", "DSH_MOBILE_AUTH_TOKEN" to "sentinel-token-value"),
        )

        assertEquals(
            listOf(
                "# dsh-mobile runtime secrets; generated per launch, mode 0600, removed when the runtime stops",
                "DEEPSEEK_API_KEY='sk-sentinel-value'",
                "DSH_MOBILE_AUTH_TOKEN='sentinel-token-value'",
            ),
            text.trimEnd('\n').split('\n'),
        )
    }

    @Test
    fun `single quotes in a value are escaped for POSIX shells`() {
        val text = RuntimeSecretPolicy.renderEnvironmentFile(linkedMapOf("OPENAI_API_KEY" to "a'b"))

        // 单引号标量里唯一需要处理的字符就是 `'`：关引号 → 转义 → 重开引号。
        assertTrue(text.contains("OPENAI_API_KEY='a'\\''b'\n"))
    }

    @Test
    fun `illegal secret shapes fail the launch instead of being skipped`() {
        // 取值形态越界意味着上游校验被绕过；静默跳过会让 dsh 在缺凭据的情况下启动，
        // 把配置故障伪装成运行故障，因此这里必须让启动失败。
        val illegalValues = listOf(
            "DEEPSEEK_API_KEY" to "has space",
            "DEEPSEEK_API_KEY" to "line\nbreak",
            "DEEPSEEK_API_KEY" to "",
            "DEEPSEEK_API_KEY" to "x".repeat(201),
            "not a name" to "value",
            "1LEADING_DIGIT" to "value",
        )

        illegalValues.forEach { (name, value) ->
            val failure = assertThrows(RuntimeFailure::class.java) {
                RuntimeSecretPolicy.requireSafe(mapOf(name to value))
            }
            assertEquals("RUNTIME_CONFIG_FAILED", failure.code)
        }
    }

    @Test
    fun `the token pattern matches the generated ephemeral credentials`() {
        // 43 位 URL 安全字符，与 RuntimeSupervisor.generateToken 的产物一致。
        assertTrue(RuntimeSecretPolicy.TOKEN_PATTERN.matches("A".repeat(43)))
        assertTrue(RuntimeSecretPolicy.TOKEN_PATTERN.matches("abcDEF012_-".repeat(3) + "abcDEF012_"))
        assertFalse(RuntimeSecretPolicy.TOKEN_PATTERN.matches("A".repeat(42)))
        assertFalse(RuntimeSecretPolicy.TOKEN_PATTERN.matches("A".repeat(44)))
        assertFalse(RuntimeSecretPolicy.TOKEN_PATTERN.matches("A".repeat(42) + "!"))
    }
}
