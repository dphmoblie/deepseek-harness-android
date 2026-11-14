package io.deepseekharness.mobile.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「整块保留 `/root` + 回填时排除运行时产物」这条链路的接线断言。
 *
 * 为什么用源码断言：`RuntimeInstaller` 需要一个 Android `Context`（`RuntimeStore` 的构造参数），
 * 纯 JVM 单测里造不出来；真正的文件行为在 [PreservedMergeTest] / [PreserveStoreTest] /
 * [RuntimePreservePolicyTest] 里用临时目录与纯逻辑覆盖。这里只钉住「调用方确实这么接了」——
 * 正是这类胶水代码在历史上最容易漏（策略换了、安装流程还在用旧常量）。
 */
class RuntimePromotionWorkspaceTest {
    private fun read(relative: String): String =
        File("src/main/java/io/deepseekharness/mobile/runtime/$relative").readText()

    @Test
    fun `安装与切版本都走整树保留，并把排除表交给合并`() {
        val installer = read("RuntimeInstaller.kt")
        assertTrue(
            "install() 没有走 promoteRuntime：\n$installer",
            installer.contains("promoteRuntime(workspace.stagingRoot, workspace.stagingManifest)"),
        )
        assertTrue(
            "switchToRetained() 没有走 promoteRuntime：\n$installer",
            installer.contains("promoteRuntime(store.retainedRoot, store.retainedManifest)"),
        )
        // 一次事务只认一份清单：整块用户空间。
        assertTrue(installer.contains("RuntimePreservePolicy.preservedPaths()"))
        assertTrue(
            "回填没有走逐项合并：\n$installer",
            installer.contains("PreservedMerge.merge(source, target)"),
        )
        // 整树回填必须把旧令牌/旧脚本/死链排除在外；只剩这些时删掉暂存副本。
        assertTrue(installer.contains("RuntimePreservePolicy.shouldRestoreGuestPath(relative)"))
        assertTrue(installer.contains("PreservedMerge.hasOnlySkipped(source)"))
        // 清单既是「去哪儿找冲突项」的说明，也是中断恢复时还原旧槽位的唯一依据。
        assertTrue(installer.contains("RuntimePreservePolicy.REPORT_FILE_NAME"))
    }

    @Test
    fun `清单模型不再声明 workspaceRoots，保留范围由策略统一给出`() {
        val models = read("RuntimeModels.kt")
        // 旧字段已随「整块保留」一起删除：留着会让后来的改动误以为还需要逐条声明。
        assertTrue("清单模型里不应再有 workspaceRoots", !models.contains("workspaceRoots"))
        val policy = read("RuntimePreservePolicy.kt")
        assertTrue(policy.contains("GUEST_HOME_SLOT"))
        assertTrue(policy.contains("shouldRestoreGuestPath"))
        assertTrue(policy.contains("fun restoreSlots()"))
    }

    @Test
    fun `整树回填有冲突时不能清理用户的暂存原件`() {
        val installer = read("RuntimeInstaller.kt")
        val restore = installer.substringAfter("val outcome = restorePreservedItems(").substringBefore("/** 暂存目录里")
        assertTrue(
            "删除暂存副本前必须确认冲突也为空；guest-home 冲突不在 unplaced 里",
            restore.contains("outcome.unplaced.isEmpty() && outcome.conflicts.isEmpty() && unknown.isEmpty()"),
        )
    }
}
