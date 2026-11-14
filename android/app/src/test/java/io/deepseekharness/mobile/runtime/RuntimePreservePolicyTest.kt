package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时用户数据保留策略的纯逻辑测试。
 *
 * 真正的移动/回填依赖文件系统与 Android 的 `Os.rename`，属于设备侧人工验收项
 * （见 `docs/design/运行时更新与数据保留.md` 的验收要点：v1 建会话 → 升到 v2 → 会话与插件仍在；
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
        assertTrue(RuntimePreservePolicy.shouldPreserve("workspace"))
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
                "workspace",
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
            val allowedRoots = listOf(RuntimePreservePolicy.GUEST_DSH_HOME, "root/.dsh-mobile", "root")
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
                "runtime-secrets.env",
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
    fun mergesIntoNonEmptyDirectoriesButStillTreatsFilesAsConflicts() {
        // 非空目录：逐项合并（同名不覆盖），不再整项判冲突 —— 新镜像自带示例文件时
        // 旧实现会把整块用户数据留在 preserve-* 里，用户看到的就是「文件没了」。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.MERGE,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 1),
        )
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.MERGE,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 1024),
        )
        // 文件（即使长度为 0）永远不是空壳，settings.yaml / .credentials.yaml 走的就是这条判定：
        // 同名文件只能判冲突，绝不覆盖新运行时里的配置。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = false, childCount = 0),
        )
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = false, childCount = 1),
        )
        // 显式关闭合并时回到旧的「非空即冲突」语义（留给需要整项判断的调用方）。
        assertEquals(
            RuntimePreservePolicy.RestoreDecision.CONFLICT,
            RuntimePreservePolicy.decideRestore(present = true, isDirectory = true, childCount = 1, merge = false),
        )
    }

    @Test
    fun preservesTheWholeGuestHomeAndStillUnderstandsLegacySlots() {
        // 新口径：整块保留 `/root`，槽位只有一个。
        assertEquals(listOf("guest-home" to "root"), RuntimePreservePolicy.preservedEntries().map { it.slot to it.path })
        assertEquals(mapOf("guest-home" to "root"), RuntimePreservePolicy.preservedPaths())
        // 例外表：旧令牌、旧脚本、死链与缓存不搬回新根目录；用户自己的脚本与目录照常。
        for (skip in listOf(
            ".dsh-mobile/runtime-secrets.env",
            ".dsh-mobile/launcher-plugins.patch.json",
            ".dsh-mobile/session-catalog.cjs",
            ".dsh-mobile/sandbox-runner.sh",
            ".dsh/profiles",
            ".dsh/profiles/web",
            ".dsh/cache",
        )) {
            assertTrue(skip, RuntimePreservePolicy.shouldRestoreGuestPath(skip))
        }
        for (keep in listOf(".dsh/sessions", ".dsh/settings.yaml", ".dsh-mobile/plugin-manager", "1/notes.md", "dsh/audit.md", ".bashrc")) {
            assertFalse(keep, RuntimePreservePolicy.shouldRestoreGuestPath(keep))
        }
        // 旧版本留下的暂存目录（逐项槽位）仍然能被认得，并保留默认工作区与扩展槽位。
        val legacy = RuntimePreservePolicy.restoreSlots()
        assertEquals("root/.dsh/sessions", legacy["sessions"])
        assertEquals("root/.dsh-mobile/plugin-manager", legacy["plugin-manager"])
        assertEquals("root/1", legacy["workspace"])
        assertEquals("root/1", legacy["workspace-2"])
        assertTrue(RuntimePreservePolicy.isKnownSlot("workspace-2"))
        assertFalse(RuntimePreservePolicy.isKnownSlot("workspace-99"))
        // 旧清单里的路径仍然要过同一套穿越检查（只用于读旧目录，不再参与新事务）。
        for (bad in listOf("/root/dsh", "root/../etc", "root/", "root//x", "root/a\\b", "etc/passwd", "root/a/./b")) {
            assertNull(bad, RuntimePreservePolicy.normalizeWorkspaceRoot(bad))
        }
        assertEquals("root/dsh", RuntimePreservePolicy.normalizeWorkspaceRoot("root/dsh/"))
    }

    @Test
    fun readsBackThePreserveReportAndRejectsTamperedPaths() {
        // 新版清单只有整树槽位；旧版清单里的逐项槽位（含 workspace-2）也仍要认得。
        val paths = LinkedHashMap<String, String>()
        paths[RuntimePreservePolicy.GUEST_HOME_SLOT] = RuntimePreservePolicy.GUEST_HOME_ROOT
        paths["workspace-2"] = "root/dsh"
        val text = RuntimePreservePolicy.reportJson(paths, restored = 2, merged = 7, conflicts = listOf("workspace/audit"))
        val parsed = RuntimePreservePolicy.parseReportPaths(text)
        assertNotNull(parsed)
        assertEquals("root/dsh", parsed?.get("workspace-2"))
        assertEquals("root", parsed?.get(RuntimePreservePolicy.GUEST_HOME_SLOT))
        // 被改坏的清单宁可整份不认：按一份可疑的路径回填等于把数据搬到别处。
        for (bad in listOf(
            "",
            "not json",
            """{"items":{"workspace":"../etc"}}""",
            """{"items":{"workspace":"root/../escape"}}""",
            """{"items":{"unknown-slot":"root/x"}}""",
            """{"items":{"workspace":7}}""",
            """{"items":{}}""",
        )) {
            assertNull(bad, RuntimePreservePolicy.parseReportPaths(bad))
        }
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
