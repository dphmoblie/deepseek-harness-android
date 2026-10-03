package io.deepseekharness.mobile.runtime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 沙箱运行器宿主侧的契约测试。
 *
 * 这里的重点不是“函数返回了什么”，而是**两侧必须逐字一致**：Kotlin 常量决定访客里的路径与
 * 签名，访客脚本与自检脚本按字面量匹配它们。任何一侧单独改动都会让沙箱静默失效——
 * 沙箱失效的表现是“命令被权限拒绝”，而不是“配置错了”，所以必须有测试把漂移挡住。
 */
class RuntimeSandboxRunnerTest {
    @Test
    fun `overlay entry carries only the runner command and the failure signature`() {
        val entry = RuntimeSandboxRunner.overlayEntry()
        val config = entry.getJSONObject("config")

        assertEquals("sandbox-local", entry.getString("id"))
        assertEquals(setOf("runnerCommand", "runnerFailureSignatures"), config.keys().asSequence().toSet())
        assertEquals(
            listOf("/bin/sh", "/root/.dsh-mobile/sandbox-runner.sh"),
            config.getJSONArray("runnerCommand").let { array -> (0 until array.length()).map(array::getString) },
        )
        assertEquals(
            listOf("dsh-sandbox-runner: "),
            config.getJSONArray("runnerFailureSignatures").let { array -> (0 until array.length()).map(array::getString) },
        )
    }

    @Test
    fun `overlay keeps the sandbox entry first and appends the providers entry`() {
        val providers = JSONObject().put("id", "llm-pi-ai").put("config", JSONObject().put("providers", JSONObject()))

        assertEquals(1, RuntimeSandboxRunner.overlay(null).length())

        val overlay = RuntimeSandboxRunner.overlay(providers)
        assertEquals(2, overlay.length())
        assertEquals("sandbox-local", overlay.getJSONObject(0).getString("id"))
        assertEquals("llm-pi-ai", overlay.getJSONObject(1).getString("id"))
    }

    @Test
    fun `runner command points at the guest script path`() {
        assertEquals("/root/.dsh-mobile/sandbox-runner.sh", RuntimeSandboxRunner.GUEST_SCRIPT_PATH)
        assertTrue(RuntimeSandboxRunner.GUEST_SCRIPT_PATH.endsWith("/" + RuntimeSandboxRunner.SCRIPT_NAME))
        assertEquals(listOf("/bin/sh", RuntimeSandboxRunner.GUEST_SCRIPT_PATH), RuntimeSandboxRunner.RUNNER_COMMAND)
    }

    @Test
    fun `guest script agrees with the host side constants`() {
        val script = asset("sandbox-runner.sh")

        assertTrue(
            "脚本必须认这条失败签名，它同时被写进 runnerFailureSignatures",
            script.contains(RuntimeSandboxRunner.FAILURE_SIGNATURE),
        )
        assertTrue(
            "脚本的 loader 目录默认值必须与 GUEST_NATIVE_LIB_PATH 逐字一致",
            script.contains("DSH_MOBILE_NATIVE_LIB:-" + RuntimeSandboxRunner.GUEST_NATIVE_LIB_PATH + "}"),
        )
        assertTrue("脚本要真的把 bwrap 的 --ro-bind 翻译成 --ro", script.contains("--ro-bind"))
        assertTrue("脚本要真的处理 --bind", script.contains("--bind"))
        assertTrue("脚本要调用 landlock-run", script.contains("landlock-run"))
    }

    @Test
    fun `self check script goes through the sandbox runner`() {
        val script = asset("runtime-self-check.cjs")

        assertTrue(script.contains(RuntimeSandboxRunner.SCRIPT_NAME))
        assertTrue(script.contains(RuntimeSandboxRunner.FAILURE_SIGNATURE))
        assertTrue(
            "自检要按运行器能翻译的形态给它档参数，否则沙箱组会测出一个假的失败",
            script.contains("--ro-bind"),
        )
    }

    @Test
    fun `native library mount survives every profile fallback`() {
        val native = ProotBindMount("/data/app/~~abc==/io.deepseekharness.mobile-xyz==/lib/arm64", "/.dsh-native")
        val profile = ProotLaunchProfile(
            disableSeccomp = false,
            bindMounts = listOf(
                ProotBindMount("/dev", "/dev"),
                native,
                ProotBindMount("/storage/emulated/0/Documents/DSH/inbox", "/mnt/inbox"),
            ),
        )
        val result = ProcessProbeResult(1, false, "proot error: bind failed")

        val fallbacks = prootProfileFallbacks(profile, result, commandCanFail = true)

        assertTrue("至少要给出一个回退档", fallbacks.isNotEmpty())
        assertTrue(
            "沙箱绑定不是可选能力：回退档必须全部保留它",
            fallbacks.all { fallback -> fallback.bindMounts.any { it.target == RuntimeSandboxRunner.GUEST_NATIVE_LIB_PATH } },
        )
    }

    @Test
    fun `safe absolute path accepts the apk install path`() {
        val nativeLibraryDir = "/data/app/~~pKcbrEMsyamhHOLXSfEJGg==/io.deepseekharness.mobile-GGLqRVg7zrYrjVQYqCdbfQ==/lib/arm64"

        assertTrue(RuntimeCommand.isSafeAbsolutePath(nativeLibraryDir))
        assertTrue(RuntimeCommand.isSafeAbsolutePath("/data/app/~~abc==/base.apk"))
        assertTrue(RuntimeCommand.isSafeAbsolutePath("/storage/emulated/0/Download"))
    }

    @Test
    fun `safe absolute path still rejects unsafe names`() {
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/data/app/~~a b==/x"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/storage/emulated/0/我的文档"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/root/../etc/passwd"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/root/./x"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/root/.dsh-mobile/"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("relative/path"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath("/"))
        assertFalse(RuntimeCommand.isSafeAbsolutePath(""))
    }

    /**
     * 读 APK 资产。
     *
     * 单测的工作目录是 Gradle 模块目录（`android/app`），但为了不把测试钉死在启动方式上，
     * 这里按几个候选相对路径找；都找不到就明确失败，而不是让断言静默变成“文件不存在”。
     */
    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/support/$name"),
            File("app/src/main/assets/support/$name"),
            File("android/app/src/main/assets/support/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            fail("找不到资产 $name（工作目录：${File(".").absolutePath}）")
            return ""
        }
        return file.readText()
    }
}
