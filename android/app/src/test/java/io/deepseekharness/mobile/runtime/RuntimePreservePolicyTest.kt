package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时用户数据保留策略的纯逻辑测试。
 *
 * 真正的移动/回填依赖文件系统与 Android 的 `Os.rename`，属于设备侧人工验收项
 * （见 `docs/运行时更新与数据保留.md` 的验收要点：v1 建会话 → 升到 v2 → 会话与插件仍在；
 * 以及更新中断后旧会话仍在）。这里覆盖可以脱离设备穷举的三类判定：
 * 白名单与拒绝路径、「空壳目录 vs 真冲突」的冲突判定、以及回滚动作的推导
 * （回滚是最容易写错的一步：它既要还原旧运行时，也要把用户数据放回去）。
 */
class RuntimePreservePolicyTest {
    @Test
    fun preservesOnlyWhitelistedGuestData() {
        // $DSH_HOME 下的用户数据；每一项都由 dsh 源码确认过出处。
        assertTrue(RuntimePreservePolicy.shouldPreserve("sessions"))
        assertTrue(RuntimePreservePolicy.shouldPreserve("settings.yaml"))
        assertTrue(RuntimePreservePolicy.shouldPreserve(".credentials.yaml"))
        assertTrue(RuntimePreservePolicy.shouldPreserve("attachments"))
        assertTrue(RuntimePreservePolicy.shouldPreserve("skills"))
        // 用户安装的插件不在 $DSH_HOME 下，但同样必须保留。
        assertTrue(RuntimePreservePolicy.shouldPreserve("plugin-manager"))
        // 白名单是封闭集合：白名单外的名字（含运行时产物）一律拒绝。
        assertFalse(RuntimePreservePolicy.shouldPreserve("profiles"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("cache"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("tmp"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("logs"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("llm-deepseek"))
        // 应用自己生成的启动配置不在 $DSH_HOME 下，永远不保留。
        assertFalse(RuntimePreservePolicy.shouldPreserve("launcher-providers.patch.json"))
        assertFalse(RuntimePreservePolicy.shouldPreserve(".dsh-mobile"))
        // 以下三项都曾被误列为保留项，已被源码证伪：必须保持拒绝以防回归。
        // settings.json 不是 dsh 的设置文件名（真实为 settings.yaml）；
        // $DSH_HOME/plugins 不是 dsh 的路径（用户插件在 root/.dsh-mobile/plugin-manager）；
        // $DSH_HOME/projects 同样没有出处。
        assertFalse(RuntimePreservePolicy.shouldPreserve("settings.json"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("plugins"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("projects"))
    }

    @Test
    fun rejectsNullEmptyAndNonFlatNames() {
        // null 与空串。
        assertFalse(RuntimePreservePolicy.shouldPreserve(null))
        assertFalse(RuntimePreservePolicy.shouldPreserve(""))
        assertFalse(RuntimePreservePolicy.shouldPreserve(" "))
        // 路径穿越：白名单只接受扁平名字，任何带分隔符或相对路径段的写法都必须被拒绝。
        assertFalse(RuntimePreservePolicy.shouldPreserve("../sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("../../root/.dsh/sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve(".."))
        assertFalse(RuntimePreservePolicy.shouldPreserve("."))
        assertFalse(RuntimePreservePolicy.shouldPreserve("./sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("root/.dsh/sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("a/b"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("sessions/"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("sessions/.."))
        assertFalse(RuntimePreservePolicy.shouldPreserve("root/.dsh/../sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("sessions\\..\\.."))
        // 绝对路径。
        assertFalse(RuntimePreservePolicy.shouldPreserve("/sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("/root/.dsh/sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("//sessions"))
        // 不做大小写或空白归一化，避免出现白名单之外的等价写法。
        assertFalse(RuntimePreservePolicy.shouldPreserve("Sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve(" sessions"))
        assertFalse(RuntimePreservePolicy.shouldPreserve("sessions "))
    }

    @Test
    fun mapsWhitelistedNamesToGuestPaths() {
        assertEquals("root/.dsh", RuntimePreservePolicy.GUEST_DSH_HOME)
        assertEquals("root/.dsh/sessions", RuntimePreservePolicy.guestRelativePath("sessions"))
        assertEquals("root/.dsh/settings.yaml", RuntimePreservePolicy.guestRelativePath("settings.yaml"))
        assertEquals("root/.dsh/.credentials.yaml", RuntimePreservePolicy.guestRelativePath(".credentials.yaml"))
        assertEquals("root/.dsh/attachments", RuntimePreservePolicy.guestRelativePath("attachments"))
        assertEquals("root/.dsh/skills", RuntimePreservePolicy.guestRelativePath("skills"))
        // 用户安装的插件不在 $DSH_HOME 下：必须落到显式声明的完整相对路径。
        assertEquals(
            "root/.dsh-mobile/plugin-manager",
            RuntimePreservePolicy.guestRelativePath("plugin-manager"),
        )
        // 被拒绝的输入不产生路径。
        assertNull(RuntimePreservePolicy.guestRelativePath(null))
        assertNull(RuntimePreservePolicy.guestRelativePath(""))
        assertNull(RuntimePreservePolicy.guestRelativePath("../sessions"))
        assertNull(RuntimePreservePolicy.guestRelativePath("/sessions"))
        assertNull(RuntimePreservePolicy.guestRelativePath("a/b"))
        assertNull(RuntimePreservePolicy.guestRelativePath("profiles"))
        // 整个 .dsh-mobile 目录不被保留：同目录下的启动配置每次启动重新生成。
        assertNull(RuntimePreservePolicy.guestRelativePath(".dsh-mobile"))
    }

    @Test
    fun whitelistCannotEscapeItsDeclaredGuestRoots() {
        val names = RuntimePreservePolicy.preservedNames()
        assertEquals(
            listOf(
                "sessions",
                "settings.yaml",
                ".credentials.yaml",
                "attachments",
                "skills",
                "plugin-manager",
            ),
            names,
        )
        for (name in names) {
            // 暂存名必须是扁平名字：它同时被用作 preserve-<uuid>/ 下的条目名，
            // 带分隔符就会把暂存目录结构变成嵌套的。
            assertFalse("暂存名必须是扁平名字：$name", name.contains("/"))
            assertFalse("暂存名不能是相对路径段：$name", name == "." || name == "..")
            val relative = RuntimePreservePolicy.guestRelativePath(name)
            assertTrue("白名单条目必须给出访客相对路径：$name", relative != null)
            assertFalse("白名单路径不得包含相对路径段：$relative", relative!!.contains(".."))
            assertFalse("白名单路径必须是相对路径：$relative", relative.startsWith("/"))
            // 只允许落在两个显式声明的访客根之下，防止将来新增条目时越界。
            val allowedRoots = listOf(RuntimePreservePolicy.GUEST_DSH_HOME, "root/.dsh-mobile")
            assertTrue(
                "$relative 必须落在声明的访客根之下",
                allowedRoots.any { relative.startsWith("$it/") },
            )
        }
    }

    @Test
    fun keepsRuntimeArtifactsOutOfTheWhitelist() {
        // 运行时产物必须显式列出并排除：旧 profiles/node_modules 是指向旧 rootfs 的符号链接，
        // 搬进新根目录会制造悬空链接与重复模块。
        // `plugins` 也列在这里：它**不是** dsh 的路径（用户安装的插件真实位于
        // root/.dsh-mobile/plugin-manager），曾被我误列为保留项，列出以防回归。
        assertEquals(
            listOf(
                "profiles",
                "cache",
                "tmp",
                "logs",
                "llm-deepseek",
                "launcher-providers.patch.json",
                "plugins",
            ),
            RuntimePreservePolicy.RUNTIME_ARTIFACTS_NOT_PRESERVED,
        )
        for (artifact in RuntimePreservePolicy.RUNTIME_ARTIFACTS_NOT_PRESERVED) {
            assertFalse(artifact, RuntimePreservePolicy.shouldPreserve(artifact))
            assertFalse(artifact, RuntimePreservePolicy.preservedNames().contains(artifact))
            assertNull(artifact, RuntimePreservePolicy.guestRelativePath(artifact))
        }
    }

    @Test
    fun movesUserDataWhenTargetIsMissing() {
        // 正常路径：新根目录没有同名项，直接移入。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.MOVE_IN,
            RuntimePreservePolicy.decideRestore(present = false, isDirectory = false, childCount = 0),
        )
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.MOVE_IN,
            RuntimePreservePolicy.decideRestore(present = false, isDirectory = true, childCount = 0),
        )
        // 不存在优先于其它信息：即使调用方带上了目录条目数，也仍然是直接移入。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.MOVE_IN,
            RuntimePreservePolicy.decideRestore(present = false, isDirectory = true, childCount = 7),
        )
    }

    @Test
    fun replacesPrebuiltEmptyShellDirectories() {
        // 最容易写错的一点：镜像预建的空 sessions/、plugins/ 是空壳，必须替换而不是判成冲突，
        // 否则每次正常更新都会失败。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.REPLACE_EMPTY_DIRECTORY,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 0),
        )
        // 目录条目数只在「存在且是目录」时才有意义，单独确认每个白名单条目都走同一判定。
        for (name in RuntimePreservePolicy.preservedNames()) {
            assertEquals(
                name,
                RuntimePreservePolicy.RestoreDecision.REPLACE_EMPTY_DIRECTORY,
                RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 0),
            )
        }
    }

    @Test
    fun treatsFilesAndNonEmptyDirectoriesAsRealConflicts() {
        // 非空目录：真冲突，保留用户数据不动、不覆盖也不删除。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 1),
        )
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 1024),
        )
        // 文件（即使长度为 0）永远不是空壳，settings.yaml / .credentials.yaml 走的就是这条判定。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = false, childCount = 0),
        )
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = false, childCount = 1),
        )
    }

    @Test
    fun recognizesOnlyOwnPreserveDirectories() {
        val nonce = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals("preserve-$nonce", RuntimePreservePolicy.preserveDirectoryName(nonce))
        assertTrue(RuntimePreservePolicy.isPreserveDirectoryName("preserve-$nonce"))
        // 只有本策略生成的 UUID 目录才允许被清理流程识别。
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("preserve-"))
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("preserve-not-a-uuid"))
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("preserve-$nonce-extra"))
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("current"))
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("staging-$nonce"))
        assertFalse(RuntimePreservePolicy.isPreserveDirectoryName("preserve-$nonce/../current"))
    }

    @Test
    fun rollbackRemovesWhatWasPromotedAndRestoresWhatWasBackedUp() {
        val progress = RuntimePreservePolicy.PromotionProgress().apply {
            rootBackedUp = true
            manifestBackedUp = true
            rootPromoted = true
            manifestPromoted = true
        }
        assertEquals(
            RuntimePreservePolicy.RollbackPlan(
                removePromotedRoot = true,
                removePromotedManifest = true,
                restoreBackedUpRoot = true,
                restoreBackedUpManifest = true,
                restorePreservedItems = true,
            ),
            RuntimePreservePolicy.rollbackPlan(progress, preservedItemCount = 4),
        )
    }

    @Test
    fun rollbackNeverDeletesAnOldRootThatWasNotBackedUp() {
        // 旧根目录尚未备份成功时它还在 currentRoot 原位：不能删除，也不能凭空恢复备份。
        val untouched = RuntimePreservePolicy.PromotionProgress()
        assertEquals(
            RuntimePreservePolicy.RollbackPlan(
                removePromotedRoot = false,
                removePromotedManifest = false,
                restoreBackedUpRoot = false,
                restoreBackedUpManifest = false,
                restorePreservedItems = true,
            ),
            RuntimePreservePolicy.rollbackPlan(untouched, preservedItemCount = 2),
        )
        // 清单已提升但根目录还没备份：只删新清单，不动旧根目录。
        val manifestOnly = RuntimePreservePolicy.PromotionProgress().apply {
            manifestPromoted = true
        }
        assertEquals(
            RuntimePreservePolicy.RollbackPlan(
                removePromotedRoot = false,
                removePromotedManifest = true,
                restoreBackedUpRoot = false,
                restoreBackedUpManifest = false,
                restorePreservedItems = false,
            ),
            RuntimePreservePolicy.rollbackPlan(manifestOnly, preservedItemCount = 0),
        )
    }

    @Test
    fun rollbackRestoresPreservedItemsOnlyWhenSomethingWasMovedOut() {
        // 新根目录已就位、清单也已就位：回滚要删新运行时、还原备份，并回填用户数据。
        val promoted = RuntimePreservePolicy.PromotionProgress().apply {
            rootBackedUp = true
            rootPromoted = true
        }
        assertEquals(
            RuntimePreservePolicy.RollbackPlan(
                removePromotedRoot = true,
                removePromotedManifest = false,
                restoreBackedUpRoot = true,
                restoreBackedUpManifest = false,
                restorePreservedItems = true,
            ),
            RuntimePreservePolicy.rollbackPlan(promoted, preservedItemCount = 1),
        )
        // 旧根目录里没有任何白名单用户数据时不需要回填（也就不会误建暂存目录）。
        assertEquals(
            RuntimePreservePolicy.RollbackPlan(
                removePromotedRoot = true,
                removePromotedManifest = false,
                restoreBackedUpRoot = true,
                restoreBackedUpManifest = false,
                restorePreservedItems = false,
            ),
            RuntimePreservePolicy.rollbackPlan(promoted, preservedItemCount = 0),
        )
    }
}
