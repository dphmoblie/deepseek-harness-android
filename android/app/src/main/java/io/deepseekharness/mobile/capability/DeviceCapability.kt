package io.deepseekharness.mobile.capability

/**
 * 设备能力清单：把「这件事现在能不能做、为什么不能、还能怎么走」集中成一处。
 *
 * 背景（真机反馈）：AI 侧的工具入口已经不少，但每个入口只会在**调用时**拒绝，
 * 于是它会反复尝试当前根本走不通的路（没有 Shizuku 权限时反复起副屏、只开了无障碍时
 * 反复送中文）。这一层把判据提前算出来，让调用方先看清单再决定路线。
 *
 * 刻意的取舍：
 *  - 只做**事实推导**：不发命令、不读文件、不碰 binder。清单由调用方把已读到的状态喂进来
 *    （Shizuku 状态走既有的缓存读取、无障碍服务状态走既有闸门），所以这里可以在纯 JVM 用例里
 *    把所有分支跑完；
 *  - 每项都带**原因码**与**替代方式码**，而不是直接给中文句子：码是闭集，前端按码出文案、
 *    测试按码断言，改文案不会动到判定；码是英文大写下划线，与既有错误码同风格；
 *  - `action` 只在「用户/界面做一下就能好」时给（去开 Shizuku、去授权、去开无障碍设置），
 *    设备本身不支持（Android 版本太低、没装 Shizuku）时给 null——那种情况点按钮也变不出来。
 */
internal data class DeviceCapability(
    val id: String,
    val state: String,
    val reason: String,
    val action: String?,
    val alternative: String?,
)

/** 推导能力清单所需的**事实**：全部由调用方读好后喂进来，本类不自己去读。 */
internal data class DeviceCapabilityFacts(
    val sdkInt: Int,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    /** Shizuku 状态里的 `permission`：`granted` / `denied` / `undetermined`。 */
    val shizukuPermission: String,
    val shizukuConnected: Boolean,
    /** 无障碍服务的三态：`connected` / `enabled_disconnected` / `off`。 */
    val accessibilityStatus: String,
    val shellEnabled: Boolean = false,
    /** 用户是否在设置里打开了根通道（默认关闭）。 */
    val rootEngineEnabled: Boolean = false,
    /** KernelSU 的探测结论（调 `KernelSuProbe.probeLocal` 得到）；未启用时保持 null。 */
    val rootEngineStatus: String? = null,
    /** KernelSU 探测的原因码；未启用时为 null。 */
    val rootEngineReason: String? = null,
    /** 沙箱降级（能跑，但少了一层隔离）；由 `RootSandboxProbe` 探测得到。 */
    val rootSandboxDegraded: Boolean = false,
)

internal object DeviceCapabilityPolicy {
    /** 现在就能用。 */
    const val READY = "ready"

    /** 前置没满足，但用户做一步就能好（清单里的 `action` 就是那一步）。 */
    const val NEEDS_ACTION = "needs_action"

    /** 被另一个能力挡住了（例如副屏要先连上 Shizuku），此时点名那个能力的原因码。 */
    const val BLOCKED = "blocked"

    /** 这台设备或这个 Android 版本做不到，做什么都没用。 */
    const val UNSUPPORTED = "unsupported"

    /**
     * 能用，但少了一层保护。
     *
     * 单独立一档是因为「能用」与「能用且完整」对用户的意义不同：根通道在缺少挂载命名空间的
     * ROM 上仍会执行（降级口径），但静态审查会按严格档收紧、`/dev/block` 遮蔽不会生效。
     * 把它说成 `ready` 是在瞒着用户，说成 `needs_action` 又不对——用户没有可做的一步。
     */
    const val DEGRADED = "degraded"

    const val SHIZUKU = "shizuku"
    const val KERNELSU_ROOT = "kernelsu_root"
    const val ACCESSIBILITY = "accessibility"
    const val VIRTUAL_SCREEN = "virtual_screen"
    const val SCREENSHOT = "screenshot"
    const val CHINESE_INPUT = "chinese_input"

    const val REASON_READY = "READY"
    const val REASON_SHIZUKU_NOT_INSTALLED = "SHIZUKU_NOT_INSTALLED"
    const val REASON_SHIZUKU_NOT_RUNNING = "SHIZUKU_NOT_RUNNING"
    const val REASON_SHIZUKU_PERMISSION_DENIED = "SHIZUKU_PERMISSION_DENIED"
    const val REASON_SHIZUKU_PERMISSION_UNDETERMINED = "SHIZUKU_PERMISSION_UNDETERMINED"
    const val REASON_SHIZUKU_NOT_CONNECTED = "SHIZUKU_NOT_CONNECTED"
    const val REASON_ACCESSIBILITY_OFF = "ACCESSIBILITY_OFF"
    const val REASON_ACCESSIBILITY_ENABLED_DISCONNECTED = "ACCESSIBILITY_ENABLED_DISCONNECTED"
    const val REASON_ANDROID_TOO_OLD = "ANDROID_TOO_OLD"
    const val REASON_TEXT_INPUT_NEEDS_ACCESSIBILITY = "TEXT_INPUT_NEEDS_ACCESSIBILITY"
    const val REASON_DEVICE_SHELL_DISABLED = "DEVICE_SHELL_DISABLED"

    /** 根通道没开（默认），或开了但 KernelSU 侧不满足。 */
    const val REASON_ROOT_ENGINE_DISABLED = "ROOT_ENGINE_DISABLED"
    const val REASON_ROOT_ENGINE_NOT_INSTALLED = "KERNELSU_NOT_INSTALLED"
    const val REASON_ROOT_ENGINE_PERMISSION_REQUIRED = "KERNELSU_PERMISSION_REQUIRED"
    const val REASON_ROOT_ENGINE_NOT_KERNELSU = "ROOT_IMPL_NOT_KERNELSU"

    /** 根通道可用，但沙箱只拿到部分隔离（详见 `RootSecurityWrapper.DEGRADATION_CODES`）。 */
    const val REASON_ROOT_SANDBOX_DEGRADED = "ROOT_SANDBOX_DEGRADED"

    const val ACTION_INSTALL_SHIZUKU = "install_shizuku"
    const val ACTION_OPEN_SHIZUKU = "open_shizuku"
    const val ACTION_GRANT_SHIZUKU_PERMISSION = "grant_shizuku_permission"
    const val ACTION_CONNECT_SHIZUKU = "connect_shizuku"
    const val ACTION_OPEN_ACCESSIBILITY_SETTINGS = "open_accessibility_settings"
    const val ACTION_ENABLE_DEVICE_SHELL = "enable_device_shell"

    /** 到设置里打开本应用的根通道开关。 */
    const val ACTION_ENABLE_ROOT_ENGINE = "enable_root_engine"

    /** 去 KernelSU 管理器里给本应用授权。 */
    const val ACTION_GRANT_ROOT_PERMISSION = "grant_root_permission"

    const val ALTERNATIVE_NO_DEVICE_SHELL = "NO_DEVICE_SHELL"
    const val ALTERNATIVE_SHELL_ASCII_ONLY = "SHELL_ASCII_ONLY"

    /** 没有根通道时，Shizuku 的设备 Shell 仍是可用的一档（只是没有 UID 0）。 */
    const val ALTERNATIVE_SHIZUKU_SHELL = "SHIZUKU_SHELL"

    /** 副屏 / 截图 / 设备 Shell 都需要的最低版本（`createVirtualDisplay` 与 `screencap` 的公共下限）。 */
    const val MIN_SDK_FOR_DISPLAY = 29

    /**
     * 六项能力的当前清单。
     *
     * 顺序固定为「前置 → 依赖它的能力」：Shizuku、根通道（KernelSU）、无障碍、副屏、截图、中文输入。
     * 界面按这个顺序显示，AI 侧也按这个顺序决定先补哪一环。
     */
    fun report(facts: DeviceCapabilityFacts): List<DeviceCapability> = listOf(
        shizuku(facts),
        kernelSuRoot(facts),
        accessibility(facts),
        virtualScreen(facts),
        screenshot(facts),
        chineseInput(facts),
    )

    /**
     * 根通道（KernelSU + 命名空间沙箱）。
     *
     * 四档：没开开关 → 开一步就好；开了但 KernelSU 侧不满足 → 照实说缺什么；
     * 能跑但沙箱降级 → [DEGRADED]（用户没有可做的一步，但界面必须说清少了一层保护）；
     * 全齐 → [READY]。
     */
    private fun kernelSuRoot(facts: DeviceCapabilityFacts): DeviceCapability {
        if (!facts.rootEngineEnabled) {
            return DeviceCapability(
                KERNELSU_ROOT,
                NEEDS_ACTION,
                REASON_ROOT_ENGINE_DISABLED,
                ACTION_ENABLE_ROOT_ENGINE,
                shizukuAlternative(facts),
            )
        }
        when (facts.rootEngineStatus) {
            "ready" -> Unit
            "needs_action" -> return DeviceCapability(
                KERNELSU_ROOT,
                NEEDS_ACTION,
                facts.rootEngineReason ?: REASON_ROOT_ENGINE_PERMISSION_REQUIRED,
                ACTION_GRANT_ROOT_PERMISSION,
                shizukuAlternative(facts),
            )

            else -> return DeviceCapability(
                KERNELSU_ROOT,
                UNSUPPORTED,
                facts.rootEngineReason ?: REASON_ROOT_ENGINE_NOT_INSTALLED,
                null,
                shizukuAlternative(facts),
            )
        }
        if (facts.rootSandboxDegraded) {
            return DeviceCapability(KERNELSU_ROOT, DEGRADED, REASON_ROOT_SANDBOX_DEGRADED, null, null)
        }
        return ready(KERNELSU_ROOT)
    }

    /** 根通道不可用时的退路：Shizuku 的普通 Shell（没有 UID 0，但能跑白名单命令）。 */
    private fun shizukuAlternative(facts: DeviceCapabilityFacts): String? =
        if (facts.shellEnabled && shizukuBlocker(facts) == null) ALTERNATIVE_SHIZUKU_SHELL else null

    private fun shizuku(facts: DeviceCapabilityFacts): DeviceCapability {
        val blocker = shizukuBlocker(facts) ?: return ready(SHIZUKU)
        return DeviceCapability(
            SHIZUKU,
            // 没装是设备层面做不到（点按钮也变不出来），其余三档都是「用户做一步就好」。
            if (blocker.first == REASON_SHIZUKU_NOT_INSTALLED) UNSUPPORTED else NEEDS_ACTION,
            blocker.first,
            blocker.second,
            ALTERNATIVE_NO_DEVICE_SHELL,
        )
    }

    /**
     * Shizuku 这一环第一个没满足的要求：原因码 + 该点的那一步；全都满足时回 null。
     *
     * 副屏与截图共用它，所以「没装 Shizuku」时那两项会给出**同一个**原因码与同一步动作，
     * 而不是各自编一句「未连接」——用户只要补这一环，三处一起好。
     */
    private fun shizukuBlocker(facts: DeviceCapabilityFacts): Pair<String, String>? = when {
        !facts.shizukuInstalled -> REASON_SHIZUKU_NOT_INSTALLED to ACTION_INSTALL_SHIZUKU
        !facts.shizukuRunning -> REASON_SHIZUKU_NOT_RUNNING to ACTION_OPEN_SHIZUKU
        facts.shizukuPermission == "denied" -> REASON_SHIZUKU_PERMISSION_DENIED to ACTION_GRANT_SHIZUKU_PERMISSION
        facts.shizukuPermission != "granted" -> REASON_SHIZUKU_PERMISSION_UNDETERMINED to ACTION_GRANT_SHIZUKU_PERMISSION
        !facts.shizukuConnected -> REASON_SHIZUKU_NOT_CONNECTED to ACTION_CONNECT_SHIZUKU
        else -> null
    }

    /** 依赖 Shizuku 的能力被挡住时的那一条；已经满足时回 null。 */
    private fun blockedByShizuku(id: String, facts: DeviceCapabilityFacts): DeviceCapability? {
        val blocker = shizukuBlocker(facts) ?: return null
        // 副屏的显示是借 Shizuku 的权限建的：没连上时连启动都过不去，所以这里是 blocked 而不是
        // needs_action —— 要补的是 Shizuku 那一环，不是再点一次「启动副屏」。
        return DeviceCapability(id, BLOCKED, blocker.first, blocker.second, null)
    }

    private fun accessibility(facts: DeviceCapabilityFacts): DeviceCapability = when (facts.accessibilityStatus) {
        "connected" -> ready(ACCESSIBILITY)
        "enabled_disconnected" -> DeviceCapability(
            ACCESSIBILITY,
            NEEDS_ACTION,
            REASON_ACCESSIBILITY_ENABLED_DISCONNECTED,
            ACTION_OPEN_ACCESSIBILITY_SETTINGS,
            shellAlternative(facts),
        )

        else -> DeviceCapability(
            ACCESSIBILITY,
            NEEDS_ACTION,
            REASON_ACCESSIBILITY_OFF,
            ACTION_OPEN_ACCESSIBILITY_SETTINGS,
            shellAlternative(facts),
        )
    }

    private fun virtualScreen(facts: DeviceCapabilityFacts): DeviceCapability {
        if (facts.sdkInt < MIN_SDK_FOR_DISPLAY) {
            return DeviceCapability(VIRTUAL_SCREEN, UNSUPPORTED, REASON_ANDROID_TOO_OLD, null, null)
        }
        blockedByShizuku(VIRTUAL_SCREEN, facts)?.let { return it }
        if (!facts.shellEnabled) {
            return DeviceCapability(VIRTUAL_SCREEN, NEEDS_ACTION, REASON_DEVICE_SHELL_DISABLED, ACTION_ENABLE_DEVICE_SHELL, null)
        }
        return ready(VIRTUAL_SCREEN)
    }

    private fun screenshot(facts: DeviceCapabilityFacts): DeviceCapability {
        if (facts.sdkInt < MIN_SDK_FOR_DISPLAY) {
            return DeviceCapability(SCREENSHOT, UNSUPPORTED, REASON_ANDROID_TOO_OLD, null, null)
        }
        return blockedByShizuku(SCREENSHOT, facts) ?: ready(SCREENSHOT)
    }

    /**
     * 中文（任意 Unicode）写入。
     *
     * 只有无障碍的远程写入（`ACTION_SET_TEXT`）不需要输入法就能写字；设备 Shell 的 `input text`
     * 只吃可打印 ASCII。所以这一项直接跟无障碍服务连没连上走，与 Shizuku 无关。
     */
    private fun chineseInput(facts: DeviceCapabilityFacts): DeviceCapability =
        if (facts.accessibilityStatus == "connected") {
            ready(CHINESE_INPUT)
        } else {
            DeviceCapability(
                CHINESE_INPUT,
                BLOCKED,
                REASON_TEXT_INPUT_NEEDS_ACCESSIBILITY,
                ACTION_OPEN_ACCESSIBILITY_SETTINGS,
                shellAlternative(facts),
            )
        }

    /** 无障碍不在时，设备 Shell 仍是一条路（仅可打印 ASCII）。 */
    private fun shellAlternative(facts: DeviceCapabilityFacts): String? =
        if (facts.shellEnabled && shizukuBlocker(facts) == null) ALTERNATIVE_SHELL_ASCII_ONLY else null

    private fun ready(id: String) = DeviceCapability(id, READY, REASON_READY, null, null)
}
