package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `resetWorkspace()` 必须真的清得掉 `stale-*` 残留——这条契约的回归保护。
 *
 * 起因就是「注释说会清、实现没清」：`RuntimeInstaller` 里那段注释声称改名残留会随显式重置被清理，
 * 但重置的匹配集合里没有 `stale-*`，于是每轮更新失败都在盘上永久多留一份 rootfs（约 960 MB）。
 *
 * 为什么用源码断言而不是行为断言：[RuntimeInstaller] 需要一个 Android `Context`（[RuntimeStore] 的构造
 * 参数），纯 JVM 单测里造不出来。清扫器本身的行为在 [RuntimeResidueSweeperTest] 里用临时目录真实覆盖，
 * 这里只钉住「重置路径确实调用了清扫器」这一行胶水——它正是当初漏掉的那一行。
 */
class RuntimeInstallerResetResidueTest {
    @Test
    fun `显式重置环境会调用残留清扫器`() {
        val reset = bodyOf("fun resetWorkspace()")

        assertTrue(
            "resetWorkspace() 没有调用 retireStaleResidue()，stale-* 残留又会删不掉：\n$reset",
            reset.contains("retireStaleResidue()"),
        )
        // 清理完还要留一条盘点记录，让「重置之后盘上还剩什么」在诊断页里看得见。
        assertTrue(reset.contains("recordRuntimeInventory(\"reset\")"))
    }

    @Test
    fun `安装成功收尾会尽力回收残留`() {
        val install = bodyOf("fun install(source: RuntimeSource)")

        // 两条成功路径都要走到：正常装完，以及「已经是当前版本」的提前返回。
        val calls = install.windowed("finishRuntimeMaintenance()".length).count { it == "finishRuntimeMaintenance()" }
        assertTrue("install() 里只有 $calls 处收尾清理，两条成功路径都要回收残留：\n$install", calls >= 2)
    }

    @Test
    fun `残留清理走共享的分级删除实现`() {
        val source = installerSource()

        // 分级删除（严格 → 兜底）只有一份实现，安装清理与残留清扫共用，避免语义漂移。
        assertTrue(source.contains("RuntimeResidueCleanup()"))
        assertTrue(source.contains("residueCleanup.delete(target, store.runtimeParent)"))
        // 残留名字的生成与匹配也集中在一处，改名与扫回来必须用同一套判定。
        assertTrue(source.contains("RuntimeResidueNames.asideName("))
        assertTrue(source.contains("RuntimeResidueNames.siblingsOf("))
    }

    private fun bodyOf(signature: String): String {
        val source = installerSource()
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        // 函数体以缩进 4 个空格的右花括号结束：这类顶层成员函数在本文件里都是这个缩进。
        val end = source.indexOf("\n    }", start)
        assertTrue("$signature 的函数体没有正常结束", end > start)
        return source.substring(start, end)
    }

    /**
     * 读 [RuntimeInstaller] 的源码。
     *
     * 单测的工作目录是 Gradle 模块目录（`android/app`），但为了不把测试钉死在启动方式上，
     * 这里按几个候选相对路径找；都找不到就明确失败，而不是让断言静默变成「文件不存在」。
     */
    private fun installerSource(): String {
        val relative = "src/main/java/io/deepseekharness/mobile/runtime/RuntimeInstaller.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("android/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            fail("找不到 RuntimeInstaller.kt（工作目录：${File(".").absolutePath}）")
            return ""
        }
        return file.readText()
    }
}
