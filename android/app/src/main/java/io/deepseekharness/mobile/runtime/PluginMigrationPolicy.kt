package io.deepseekharness.mobile.runtime

/**
 * 「应用（APK）升级后的插件迁移」的判定策略（**纯逻辑**，零 Android 依赖，便于 JVM 单测穷举）。
 *
 * 要解决的缺口：插件包本体装在**运行时**目录里，应用升级本身不会动它；但一次应用升级往往伴随
 * 「新 rootfs / 新插件布局 / 新的清单写法」，而此前只有运行时（rootfs）升级有自愈入口
 * （`recoverIfNeeded` / `repairInstalledIfNeeded` 按**运行时代次**指纹去重）。应用升级后第一次
 * 启动 Harness 时，若运行时代次没变，自愈判定为「这一代次已经修过」而直接跳过 —— 于是
 * 「应用升级引入了新形态、插件链接却是旧形态」这种情况没有任何入口去核对。
 *
 * 本策略只回答一个问题：**这次启动要不要跑一次插件迁移**。三条判据：
 *  1. 上次迁移成功的 `versionCode` 与当前 `BuildConfig.VERSION_CODE` 不同（含升级与降级）；
 *  2. 从未迁移过（没有记账文件）；
 *  3. `versionCode` 相同但**运行时代次变了**（`runtimeId|版本|rootfs 摘要` 变了）——
 *     这一条与既有的自愈闸门正交：运行时升级后即使应用没换版本，也要有一次「按账本核对」。
 *
 * 为什么**降级也迁移**：降级后盘上的插件状态可能是更新版本的应用写下的，核对一次是幂等的、
 * 不改用户数据；而不迁移却把记账写成新版本号，等于谎报「这一步做过了」。宁可多跑一次。
 *
 * 迁移动作本身是幂等的（回放未提交事务 + 按代次自愈链接 + 按账本核对目录），因此这里的判定
 * 只影响「跑几次」，不影响「跑成什么样」；真正的代价是每次迁移最多多起一次访客 Node 进程。
 */
object PluginMigrationPolicy {
    /** 从未迁移过：第一次带记账文件的启动。 */
    const val REASON_FIRST_RUN = "first-run"
    /** 应用升级（当前 versionCode 更大）。 */
    const val REASON_APP_UPGRADED = "app-upgraded"
    /** 应用降级（当前 versionCode 更小）：盘上状态可能来自更新的版本。 */
    const val REASON_APP_DOWNGRADED = "app-downgraded"
    /** 应用没换版本，但运行时代次变了。 */
    const val REASON_RUNTIME_CHANGED = "runtime-changed"
    /** 两个判据都没变：这一代次、这个版本已经迁移过。 */
    const val REASON_CURRENT = "current"

    /**
     * 迁移判定结果。
     *
     * [reason] 是**受控取值**（见上面的常量），只用于诊断记录与记账，不参与界面文案：
     * 界面只看「有没有迁移、迁移成没成」。
     */
    data class Decision(val migrate: Boolean, val reason: String)

    /**
     * 本次是否应当执行插件迁移。
     *
     * @param lastMigratedVersionCode 上次**成功**迁移时的 `BuildConfig.VERSION_CODE`；
     *   没有记账文件（从未迁移）或记账不可用时为 `null`。
     * @param currentVersionCode 当前 `BuildConfig.VERSION_CODE`。正常恒为正；非正值不做特殊
     *   处理 —— 按数值比较的结论同样安全（迁移幂等），额外加一条"非法就不迁移"的分支反而会
     *   在取不到版本号时把迁移永久关掉。
     * @param generationChanged 运行时代次指纹是否与上次迁移时不同（应用没换版本时才起作用）。
     */
    fun decide(
        lastMigratedVersionCode: Int?,
        currentVersionCode: Int,
        generationChanged: Boolean,
    ): Decision {
        // 没有记账 = 从未成功迁移过：第一次必须跑，否则「新增的核对步骤」在这台设备上永不生效。
        if (lastMigratedVersionCode == null) return Decision(true, REASON_FIRST_RUN)
        if (currentVersionCode > lastMigratedVersionCode) return Decision(true, REASON_APP_UPGRADED)
        // 降级同样跑：盘上的状态可能由更新版本的应用写下，核对一次是幂等的。
        if (currentVersionCode < lastMigratedVersionCode) return Decision(true, REASON_APP_DOWNGRADED)
        // 版本没变：只有换了运行时代次才需要重新核对。
        if (generationChanged) return Decision(true, REASON_RUNTIME_CHANGED)
        return Decision(false, REASON_CURRENT)
    }
}
