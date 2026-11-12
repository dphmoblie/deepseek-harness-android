package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时更新时的访客用户数据保留策略。
 *
 * 本对象是**纯逻辑**（不接触文件系统），便于在 JVM 单元测试里穷举保留范围与拒绝路径；
 * 真正的移动、合并取样由 `RuntimeInstaller` / `PreservedMerge` 负责。
 *
 * 现在的口径（用户要求「更新后不需要任何重做」）：**整块保留访客用户空间 `/root`**，一次 rename
 * 完成；回填时按 [shouldRestoreGuestPath] 把旧令牌、旧脚本与死链/缓存排除在外——它们本来就会被
 * 新运行时重建。这样用户自己建的目录（`/root/dsh`、脚本、`.bashrc`）也一并在内，
 * 不再需要逐条声明。
 *
 * 历史的逐项白名单（`sessions` / `settings.yaml` / …）保留为 [restoreSlots]，**只用于读旧版本
 * 留下的暂存目录**：老版本装出来的 `preserve-*` 里就是这些槽位，回填流程必须还认得它们。
 */
object RuntimePreservePolicy {
    /** 访客 dsh 主目录相对根文件系统的路径。 */
    const val GUEST_DSH_HOME = "root/.dsh"

    /**
     * 旧版逐项保留的 `$DSH_HOME` 槽位。**只用于读旧暂存目录**（见 [restoreSlots]），
     * 新版本整块保留 `/root`，不再逐个搬运这些项。
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
     * 旧版逐项槽位里的「`$DSH_HOME` 之外」两项。**只用于读旧暂存目录**，新版本不再单独搬运。
     */
    private val PRESERVED_OUTSIDE_HOME = mapOf(
        "plugin-manager" to "root/.dsh-mobile/plugin-manager",
        "workspace" to DEFAULT_WORKSPACE_ROOT,
    )

    /**
     * 明确**不保留**的运行时产物，仅用于说明与自检（它们不在白名单内，因此天然被拒绝）。
     *
     * - `profiles`：`profiles/node_modules` 是指向旧 rootfs 内包的符号链接，
     *   `profiles/web` 是按旧 dsh 版本生成的产物；搬进新根目录会制造悬空链接与重复模块。
     * - `cache`、`tmp`、`logs`：缓存、临时文件与日志，由新运行时重建即可，没有跨版本价值。
     * - `llm-deepseek`：远端文件上传缓存（dsh-llm-deepseek/lib/index.js:766），与远端状态绑定。
     * - `launcher-providers.patch.json`：应用每次启动重新生成的启动配置。
     * - `runtime-secrets.env`：应用每次启动重新生成的 0600 秘密环境文件
     *   （`RuntimeCommand.GUEST_SECRET_ENV_PATH`）。它承载每次启动生成、只在一次运行内有效的
     *   临时令牌，**绝不能**跨版本保留 —— 新运行时会重新投递，搬运旧文件只会把过期取值带过去。
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
        "runtime-secrets.env",
        "plugins",
    )

    /** 用户数据暂存目录前缀：`runtimeParent/preserve-<uuid>`。 */
    const val PRESERVE_DIRECTORY_PREFIX = "preserve-"

    /**
     * 暂存目录里的回填清单：`slot → 相对根文件系统的路径`，外加本次回填结果。
     *
     * 它同时解决两件事：
     * 1. 中断恢复时要按**当初**的工作区清单回填，而不是按当前默认值（否则 `workspace-2` 这类
     *    扩展槽位就再也对不上路径，用户数据只能烂在暂存目录里）；
     * 2. 冲突项留在暂存目录时，界面/用户可以照清单找到它们。
     */
    const val REPORT_FILE_NAME = "preserve-report.json"

    /** 应用自己的工作区：dsh 绑定工作区时的目录。保留它是既有行为，不能因为可配置而丢掉。 */
    const val DEFAULT_WORKSPACE_ROOT = "root/1"

    /** 整块保留的访客用户空间：整个 `/root`。槽位名固定，兼容按树回填的分支。 */
    const val GUEST_HOME_SLOT = "guest-home"
    const val GUEST_HOME_ROOT = "root"

    /**
     * 回填时**不搬回新根目录**的访客路径（相对 `root/`）。
     *
     * 三条理由，逐条对应一类文件：
     * 1. **旧令牌**：`runtime-secrets.env` 是每次启动才生成的一次性秘密文件，`launcher-*.patch.json`
     *    指向本次启动的入口与插件补丁。搬进新运行时只会把过期取值带过去，而新运行时启动前
     *    本来就会重写它们。保留 = 留一份没用的秘密文件在盘上。
     * 2. **旧脚本**：`.dsh-mobile` 下的 `.cjs`、`sandbox-runner.sh`、`dsh-runner` 都是**应用投递**的脚本，
     *    每次使用前都会按当前 APK 版本重写（`SessionCatalog.prepareScript` 等）。搬回旧副本等于让
     *    新应用跑旧脚本；用户自己写的脚本（例如 `/root/dsh` 下的 `.sh`）不在这个列表里，照常保留。
     * 3. **死链与缓存**：`.dsh/profiles` 里是指向旧 rootfs 的符号链接，`cache`/`tmp`/`logs`/
     *    `llm-deepseek` 是缓存与远端绑定数据。搬过来就是死链与两份模块并存。
     *
     * 这些项**不删除**：合并时跳过它们，留在暂存目录里；只剩这类项时整份暂存目录在回填成功后删除
     * （它们本来就会被新运行时重建）。
     */
    private val GUEST_PATHS_NOT_RESTORED = listOf(
        ".dsh/profiles",
        ".dsh/cache",
        ".dsh/tmp",
        ".dsh/logs",
        ".dsh/llm-deepseek",
        ".dsh-mobile/runtime-secrets.env",
        ".dsh-mobile/launcher-providers.patch.json",
        ".dsh-mobile/launcher-plugins.patch.json",
        ".dsh-mobile/dsh-runner",
        ".dsh-mobile/session-catalog.cjs",
        ".dsh-mobile/plugin-manager.cjs",
        ".dsh-mobile/harness-packages.cjs",
        ".dsh-mobile/runtime-self-check.cjs",
        ".dsh-mobile/sandbox-runner.sh",
    )

    /** `relative`（相对 `root/`）是不是「不要搬回新根目录」的运行时产物。 */
    fun shouldRestoreGuestPath(relative: String): Boolean =
        GUEST_PATHS_NOT_RESTORED.any { relative == it || relative.startsWith("$it/") }

    /**
     * 保留项对用户展示的分类。
     *
     * 机制只有一份清单，但用户看到的是「会话 / 插件 / 文件 / 设置与凭据」四类：
     * 更新前的预检、更新后的回执、重置对话框的「将删除什么」都用这一套分类，
     * 不再各自维护第二份列表（两份清单必然漂移）。
     */
    enum class Category(val wireValue: String) {
        CONVERSATIONS("conversations"),
        PLUGINS("plugins"),
        FILES("files"),
        SETTINGS("settings"),
        ;

        companion object {
            fun fromWire(value: String?): Category = entries.firstOrNull { it.wireValue == value } ?: SETTINGS
        }
    }

    /** 一条保留项：暂存槽位名、访客相对路径、用户看到的名字与分类。 */
    data class PreservedEntry(
        val slot: String,
        val path: String,
        val category: Category,
        val label: String,
    )

    /** 声明式工作区根的数量上限：多到没边的清单只会把每次更新拖成整盘搬家。 */
    const val WORKSPACE_ROOT_LIMIT = 8

    /** 工作区根相对路径的长度上限。 */
    private const val MAX_WORKSPACE_ROOT_CHARS = 200

    /** 回填清单的大小与条目上限：它是我们自己写的几十行 JSON，超出即视为不可信。 */
    private const val MAX_REPORT_CHARS = 16 * 1024
    private const val MAX_REPORT_ITEMS = 32

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

    /**
     * 把一处声明式工作区根归一成访客相对路径；不合法返回 `null`。
     *
     * 只接受 `root/` 之下的相对路径：绝对路径、反斜杠、`.`/`..` 段、控制字符与空段一律拒绝——
     * 这个值会被拼进 `rename` 的源/目标路径，任何穿越形态都不该出现在这里。
     */
    fun normalizeWorkspaceRoot(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.length !in "root/x".length..MAX_WORKSPACE_ROOT_CHARS) return null
        if (!trimmed.startsWith("root/")) return null
        for (segment in trimmed.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
            if (segment.any { it == '\\' || it.isISOControl() }) return null
        }
        return trimmed
    }

    /**
     * 读旧暂存目录（没有清单、只有 `sessions` / `workspace` 这些老槽位）时靠它还原路径；
     * 新版本的暂存目录里只有 `guest-home` 一项。
     */
    fun restoreSlots(): LinkedHashMap<String, String> {
        val slots = LinkedHashMap<String, String>()
        slots[GUEST_HOME_SLOT] = GUEST_HOME_ROOT
        for (name in PRESERVED_NAMES) slots[name] = "$GUEST_DSH_HOME/$name"
        slots["plugin-manager"] = PRESERVED_OUTSIDE_HOME.getValue("plugin-manager")
        slots["workspace"] = DEFAULT_WORKSPACE_ROOT
        for (index in 2..WORKSPACE_ROOT_LIMIT) slots["workspace-$index"] = "root/1"
        return slots
    }

    /** 槽位是否可识别（回填清单与旧目录共用同一套判定）。 */
    fun isKnownSlot(slot: String): Boolean =
        slot == GUEST_HOME_SLOT || slot in PRESERVED_NAMES || slot == "plugin-manager" ||
            slot == "workspace" || (slot.startsWith("workspace-") && slot.removePrefix("workspace-").toIntOrNull() in 2..WORKSPACE_ROOT_LIMIT)

    /** 分类对应的中文名字；界面直接显示，不另写一份。 */
    fun categoryLabel(category: Category): String = when (category) {
        Category.CONVERSATIONS -> "会话"
        Category.PLUGINS -> "插件"
        Category.FILES -> "文件"
        Category.SETTINGS -> "设置与凭据"
    }

    private fun labelOf(name: String): String = when (name) {
        "sessions" -> "对话与会话记录"
        "settings.yaml" -> "dsh 设置"
        ".credentials.yaml" -> "模型凭据"
        "attachments" -> "上传的附件"
        "skills" -> "用户技能"
        else -> name
    }

    /**
     * 一次更新事务的保留清单（带分类与展示名）。
     *
     * 用户明确要求「更新后不需要任何重做」：**整块保留 `/root`**，一次 rename 完成；
     * 回填时按 [shouldRestoreGuestPath] 把旧令牌、旧脚本与死链/缓存排除在外（它们本来就会被
     * 新运行时重建）。旧版本的逐项槽位仍然能被回填流程读懂，见 [restoreSlots]。
     */
    fun preservedEntries(): List<PreservedEntry> =
        listOf(PreservedEntry(GUEST_HOME_SLOT, GUEST_HOME_ROOT, Category.FILES, "整个用户空间（/root）"))

    /**
     * 一次更新事务的完整保留清单：`暂存名 → 访客相对路径`。
     *
     * 新版只有 `guest-home → root` 一项；旧版暂存目录里的逐项槽位由 [restoreSlots] 认领。
     */
    fun preservedPaths(): LinkedHashMap<String, String> {
        val paths = LinkedHashMap<String, String>()
        for (entry in preservedEntries()) paths[entry.slot] = entry.path
        return paths
    }

    /** 生成 `preserve-<uuid>` 暂存目录名。 */
    fun preserveDirectoryName(nonce: String): String = PRESERVE_DIRECTORY_PREFIX + nonce

    /**
     * 暂存目录里的回填清单文本。
     *
     * `items` 让中断恢复按**当初**的槽位还原路径；`restored` / `merged` / `conflicts` 是给
     * 界面与用户看的结果（冲突项仍在暂存目录里，清单是唯一的「去哪儿找」）。
     */
    fun reportJson(items: Map<String, String>, restored: Int, merged: Int, conflicts: List<String>): String =
        JSONObject()
            .put("version", 1)
            .put("items", JSONObject(items as Map<*, *>))
            .put("categories", JSONObject(items.keys.associateWith { categoryOf(it).wireValue }))
            .put("restored", restored)
            .put("merged", merged)
            .put("conflicts", JSONArray(conflicts))
            .toString()

    /** 槽位属于哪一类：只按槽位名判定，因此离线读旧清单也能分类。 */
    fun categoryOf(slot: String): Category = when {
        slot == GUEST_HOME_SLOT -> Category.FILES
        slot == "sessions" -> Category.CONVERSATIONS
        slot == "plugin-manager" -> Category.PLUGINS
        slot == "workspace" || slot.startsWith("workspace-") -> Category.FILES
        else -> Category.SETTINGS
    }

    /**
     * 整树回填后，按**相对 `root/` 的路径**给出分类（「数据保留」页据此汇总）。
     * 用户空间里 `.dsh` 主要是会话、`.dsh-mobile` 是插件与运行时脚本，其余按文件处理。
     */
    fun categoryOfPath(relative: String): Category = when {
        relative == ".dsh" || relative.startsWith(".dsh/") -> Category.CONVERSATIONS
        relative == ".dsh-mobile" || relative.startsWith(".dsh-mobile/") -> Category.PLUGINS
        else -> Category.FILES
    }

    /** 回填结果摘要：界面只读这三个数，不解析整个清单。 */
    data class ReportSummary(val restored: Int, val merged: Int, val conflicts: List<String>)

    /** 读清单里的结果摘要；没有清单或结构不合规时返回 `null`（界面据此说明「没有记录」）。 */
    fun parseReportSummary(text: String?): ReportSummary? {
        if (text == null || text.isEmpty() || text.length > MAX_REPORT_CHARS) return null
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val restored = json.optInt("restored", -1)
        val merged = json.optInt("merged", -1)
        if (restored < 0 || merged < 0) return null
        val raw = json.optJSONArray("conflicts") ?: JSONArray()
        val conflicts = buildList {
            for (index in 0 until minOf(raw.length(), MAX_REPORT_ITEMS)) {
                val value = raw.opt(index)
                if (value is String && value.length <= 400) add(value)
            }
        }
        return ReportSummary(restored, merged, conflicts)
    }

    /**
     * 从清单里取槽位 → 相对路径；结构、键或路径不合法时返回 `null`。
     *
     * 返回 `null` 的调用方一律退回**当前**默认清单：宁可少认几个扩展槽位，也不按一份
     * 被改坏的路径把数据搬到别处。
     */
    fun parseReportPaths(text: String?): LinkedHashMap<String, String>? {
        if (text == null || text.isEmpty() || text.length > MAX_REPORT_CHARS) return null
        val items = try {
            JSONObject(text).optJSONObject("items") ?: return null
        } catch (_: Exception) {
            return null
        }
        if (items.length() == 0 || items.length() > MAX_REPORT_ITEMS) return null
        val paths = LinkedHashMap<String, String>()
        for (key in items.keys()) {
            if (!isKnownSlot(key)) return null
            val value = items.opt(key) as? String ?: return null
            val valid = if (key == GUEST_HOME_SLOT) value == GUEST_HOME_ROOT else normalizeWorkspaceRoot(value) != null
            if (!valid) return null
            paths[key] = value
        }
        return paths
    }

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

        /**
         * 目标是非空目录：逐项合并，**同名不覆盖**，冲突的那几项留在暂存目录里。
         *
         * 这是旧版「非空即冲突」的修正：新 rootfs 只要预建了一个非空的 `root/1`
         * （自带示例或 `inbox/`），整块用户数据就再也回填不进来，从用户视角看就是「文件没了」。
         */
        MERGE,

        /** 目标是文件或符号链接：真冲突，用户数据保持不动，不覆盖也不删除。 */
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
     * @param merge 非空目录是否走逐项合并。默认 `true`；整项覆盖的旧语义只留给
     *   「整目录搬移」的纯逻辑测试显式关闭。
     */
    fun decideRestore(present: Boolean, isDirectory: Boolean, childCount: Int, merge: Boolean = true): RestoreDecision {
        if (!present) return RestoreDecision.MOVE_IN
        if (isDirectory && childCount == 0) return RestoreDecision.REPLACE_EMPTY_DIRECTORY
        if (isDirectory && merge) return RestoreDecision.MERGE
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
