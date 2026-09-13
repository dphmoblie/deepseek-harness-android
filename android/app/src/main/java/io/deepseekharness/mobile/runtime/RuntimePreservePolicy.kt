package io.deepseekharness.mobile.runtime

/**
 * 运行时更新时的访客用户数据保留策略。
 *
 * 本对象是**纯逻辑**（不接触文件系统），便于在 JVM 单元测试里穷举白名单与拒绝路径；
 * 真正的移动、冲突判定取样由 `RuntimeInstaller` 负责。
 *
 * 背景见 `docs/运行时更新与数据保留.md`：dsh 的用户数据写在访客的 `$DSH_HOME`
 * （即 `/root/.dsh`，对应宿主 `currentRoot/root/.dsh`），而提升新根目录时旧根目录会被整体删除，
 * 于是对话会话、用户安装的插件与 dsh 配置一起消失。
 *
 * 这里刻意采用**白名单**而不是整个 `root/.dsh`：
 * `root/.dsh/profiles/node_modules` 下是指向 rootfs 内包的符号链接，
 * `root/.dsh/profiles/web` 也是按当前 dsh 版本生成的产物。整体搬运会同时制造
 * 悬空符号链接与新旧两套运行时模块并存（即 `docs/ARCHITECTURE.md` 的
 * 「运行时依赖版本钉与 Symbol 身份」小节所述
 * 「同名包出现两份物理副本会让 Symbol 身份不一致」的同一类故障）。
 * 因此只搬运明确的用户数据，运行时产物一律由新 rootfs 重新提供。
 */
object RuntimePreservePolicy {
    /** 访客 dsh 主目录相对根文件系统的路径。 */
    const val GUEST_DSH_HOME = "root/.dsh"

    /**
     * 需要跨升级保留的 `$DSH_HOME` 用户数据。**全部是扁平名字**（不含路径分隔符）：
     * 白名单只描述「`$DSH_HOME` 下的哪一项」，不做嵌套匹配，从根上消除路径穿越面。
     * 顺序固定，便于日志与测试断言。每一项都由 dsh 源码确认过出处。
     */
    private val PRESERVED_NAMES = listOf(
        // 对话会话（scripts/mobile-session-publish.py 的 SESSION_ROOT = /root/.dsh/sessions）。
        "sessions",
        // dsh 设置。真实文件名是 settings.yaml（dsh-settings-file/lib/index.js:32）；
        // 早期实现误写为 settings.json，导致设置从未被保留。
        "settings.yaml",
        // 模型凭据（dsh-credentials-local/lib/index.js:49,58）。该文件必须以 0600 保留，
        // 因此只能 Os.rename、绝不能复制：复制会被 umask 改成 0644，而 dsh 会拒绝读取
        // 权限宽松的凭据文件并直接报错（同上 lib/index.js:104）。
        ".credentials.yaml",
        // 用户上传的附件（dsh-attachment-local/lib/index.js:986）。
        "attachments",
        // 用户技能（dsh-skill-filesystem/lib/index.js:172）。
        "skills",
    )

    /**
     * 不在 `$DSH_HOME` 下、但同样必须跨版本保留的访客项：**暂存名 → 访客相对路径**。
     *
     * `plugin-manager` 是用户安装的插件：`plugin-manager.cjs` 把插件 npm 装到
     * `root/.dsh-mobile/plugin-manager/versions/<txid>/node_modules/`。它**不在 `$DSH_HOME` 下**，
     * 扁平的「`$DSH_HOME` 下名字」模型表达不了，必须显式给出完整相对路径。两条边界：
     *  - 只保留 `plugin-manager` 这一项，**不是**整个 `root/.dsh-mobile`：同目录下的
     *    `launcher-providers.patch.json` 由应用每次启动重新生成，保留它只会把过期配置带进新运行时；
     *  - 跨版本保留插件目录会留下指向旧 rootfs 的悬空符号链接，这一条由插件侧的「修复动作」
     *    负责（见 docs/项目状态.md）。
     */
    private val PRESERVED_OUTSIDE_HOME = mapOf(
        "plugin-manager" to "root/.dsh-mobile/plugin-manager",
    )

    /**
     * 明确**不保留**的运行时产物，仅用于说明与自检（它们不在白名单内，因此天然被拒绝）。
     *
     * - `profiles`：`profiles/node_modules` 是指向旧 rootfs 内包的符号链接，
     *   `profiles/web` 是按旧 dsh 版本生成的产物；搬进新根目录会制造悬空链接与重复模块。
     * - `cache`、`tmp`、`logs`：缓存、临时文件与日志，由新运行时重建即可，没有跨版本价值。
     * - `llm-deepseek`：远端文件上传缓存（dsh-llm-deepseek/lib/index.js:766），与远端状态绑定。
     * - `launcher-providers.patch.json`：应用每次启动重新生成的启动配置。
     * - `plugins`：**这不是 dsh 的路径**（用户安装的插件真实位于 `root/.dsh-mobile/plugin-manager`，
     *   见 [PRESERVED_OUTSIDE_HOME]）。它曾被误列为保留项，已删除；列在这里是为了防止回归。
     */
    val RUNTIME_ARTIFACTS_NOT_PRESERVED = listOf(
        "profiles",
        "cache",
        "tmp",
        "logs",
        "llm-deepseek",
        "launcher-providers.patch.json",
        "plugins",
    )

    /** 用户数据暂存目录前缀：`runtimeParent/preserve-<uuid>`。 */
    const val PRESERVE_DIRECTORY_PREFIX = "preserve-"

    private val UUID_SUFFIX = Regex("^[a-f0-9-]{36}$")

    /**
     * 名字是否属于需要保留的用户数据。
     *
     * 只接受白名单里的项：`null`、空串、`../x`、`/abs`、`a/b`、`.`、`..`
     * 以及任何其它输入一律拒绝，避免把路径穿越面带进提升流程。
     */
    fun shouldPreserve(name: String?): Boolean =
        name != null && (PRESERVED_NAMES.contains(name) || PRESERVED_OUTSIDE_HOME.containsKey(name))

    /** 相对根文件系统的白名单路径；名字不被接受时返回 `null`。 */
    fun guestRelativePath(name: String?): String? = when {
        name == null -> null
        name in PRESERVED_NAMES -> "$GUEST_DSH_HOME/$name"
        else -> PRESERVED_OUTSIDE_HOME[name]
    }

    /** 白名单条目（暂存名，顺序固定：先 `$DSH_HOME` 内的，再 `$DSH_HOME` 外的）。 */
    fun preservedNames(): List<String> = PRESERVED_NAMES + PRESERVED_OUTSIDE_HOME.keys

    /** 生成 `preserve-<uuid>` 暂存目录名。 */
    fun preserveDirectoryName(nonce: String): String = PRESERVE_DIRECTORY_PREFIX + nonce

    /** 是否是本策略生成的暂存目录名（严格校验 UUID，避免误删重名目录）。 */
    fun isPreserveDirectoryName(name: String): Boolean =
        if (!name.startsWith(PRESERVE_DIRECTORY_PREFIX)) {
            false
        } else {
            UUID_SUFFIX.matches(name.removePrefix(PRESERVE_DIRECTORY_PREFIX))
        }

    /** 把用户数据回填进新根目录时，对同名项的处理方式。 */
    enum class RestoreDecision {
        /** 目标不存在：直接移入（正常路径）。 */
        MOVE_IN,

        /** 目标是**空目录**：视为新 rootfs 预建的空壳，删除空壳后把用户数据移入。 */
        REPLACE_EMPTY_DIRECTORY,

        /** 目标是非空目录或文件：真冲突，用户数据保持不动，不覆盖也不删除。 */
        CONFLICT,
    }

    /**
     * 回填时的冲突判定（实现约定 4）。
     *
     * 新 rootfs 很可能预建了**空**的 `sessions`、`plugins` 目录；若把空壳当成冲突，
     * 每次正常更新都会被判成冲突而失败。反过来，只要目标里有内容就必须判为真冲突，
     * 绝不覆盖用户数据。
     *
     * @param present 目标同名项是否存在（判断不跟随符号链接）
     * @param isDirectory 目标是目录（判断不跟随符号链接；符号链接一律按冲突处理）
     * @param childCount 目标目录内的条目数；文件传 0 即可（文件永远不是空壳）
     */
    fun decideRestore(present: Boolean, isDirectory: Boolean, childCount: Int): RestoreDecision {
        if (!present) return RestoreDecision.MOVE_IN
        if (isDirectory && childCount == 0) return RestoreDecision.REPLACE_EMPTY_DIRECTORY
        return RestoreDecision.CONFLICT
    }

    /**
     * 提升进度：回滚动作**只**由这四个事实决定（由 `RuntimeInstaller.promoteStaging` 逐步置位）。
     */
    class PromotionProgress {
        /** 旧根目录已改名为 `backupRoot`。 */
        var rootBackedUp = false

        /** 旧清单已改名为 `backupManifest`。 */
        var manifestBackedUp = false

        /** 暂存根目录已改名为 `currentRoot`。 */
        var rootPromoted = false

        /** 暂存清单已改名为 `currentManifest`。 */
        var manifestPromoted = false
    }

    /** 回滚要执行的动作（纯描述，实际文件操作由 `RuntimeInstaller` 完成）。 */
    data class RollbackPlan(
        /** 删除已经提升上来的新根目录。 */
        val removePromotedRoot: Boolean,
        /** 删除已经提升上来的新清单。 */
        val removePromotedManifest: Boolean,
        /** 把备份的旧根目录改回 `currentRoot`。 */
        val restoreBackedUpRoot: Boolean,
        /** 把备份的旧清单改回 `currentManifest`。 */
        val restoreBackedUpManifest: Boolean,
        /** 把已移出的用户数据放回恢复出来的旧根目录。 */
        val restorePreservedItems: Boolean,
    )

    /**
     * 由提升进度推导回滚动作。
     *
     * 两条必须守住的规则：
     * - **只有确实提升上来的项才删除**：旧根目录尚未备份成功时它还在 `currentRoot` 原位，
     *   把它当成新根目录删掉等于直接丢掉旧运行时（原来的回滚就是这样写的）。
     * - **移出过用户数据就必须回填**：否则「回滚」本身就在丢数据（实现约定 3）。
     */
    fun rollbackPlan(progress: PromotionProgress, preservedItemCount: Int): RollbackPlan = RollbackPlan(
        removePromotedRoot = progress.rootPromoted,
        removePromotedManifest = progress.manifestPromoted,
        restoreBackedUpRoot = progress.rootBackedUp,
        restoreBackedUpManifest = progress.manifestBackedUp,
        restorePreservedItems = preservedItemCount > 0,
    )
}
