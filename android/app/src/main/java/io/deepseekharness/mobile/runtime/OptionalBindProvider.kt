package io.deepseekharness.mobile.runtime

import java.io.File

/**
 * 「可选绑定」的归类。
 *
 * 「可选」的含义只有一条：宿主侧东西不可用时**一个绑定都不追加**。一旦 PRoot 因为其中某个绑定
 * 起不来，又必须能把**这一类**整体撤掉再试一次（见 `RuntimeLaunchResolver.prootProfileFallbacks`）。
 * 归类只依赖挂载目标字符串，因此两条链路、以及不持有门面实例的回退函数，都能用同一份判定。
 */
internal enum class OptionalBindGroup(private val matches: (String) -> Boolean) {
    /** 投递区：`/mnt/inbox`、`/mnt/outbox`（宿主目录见 `RuntimeMailboxLayout`）。 */
    MAILBOX({ it == RuntimeMailboxLayout.GUEST_INBOX || it == RuntimeMailboxLayout.GUEST_OUTBOX }),

    /** 用户目录白名单：访客侧固定为 `/mnt/user/<序号>`。 */
    USER_DIRECTORIES({ RuntimeStorageDirsLayout.isGuestPath(it) });

    /** 这个挂载目标属不属于本类。 */
    fun owns(target: String): Boolean = matches(target)
}

/**
 * 可选绑定提供者：把「可选能力（投递区 / 用户目录白名单）→ PRoot 启动档」的两件事
 * 收在同一个接口上，两条链路（`RuntimeMailbox`、`RuntimeStorageDirs`）都实现它。
 *
 * 抽这个接口的动机是**同一份重复出现了两次**：启动档解析要「取绑定」，缓存键要「取可用性摘要」，
 * 而这两件事在两条链路上各自实现了一遍（连不可用返回空列表、摘要进缓存键的写法都逐字相同）。
 * 接口把交点固定下来，顺带把回退档的归类判定从硬编码的一组挂载目标换成 [group]。
 *
 * 两条语义必须同时成立，接口就是它们的交点：
 * 1. **实时挂载**（[bindMounts]）：绑定集合在每次解析启动档时**重新计算**，绝不缓存——
 *    宿主目录可能刚被删掉、权限可能刚被撤销，拿一份过期的绑定表去启动 PRoot 会直接起不来。
 *    不可用时返回空列表，而不是抛错、也不是退回别的目录：
 *    「这个能力不可用」与「运行时坏了」是两件事（分层见 `RuntimeStorageDirPreferences` 的档位说明）。
 * 2. **校验式批量搬运**（[cacheToken]）：可选绑定的可用性与内容会被折进启动档缓存键，于是
 *    「白名单多了一条 / 投递区权限被撤销」必然让缓存失配、重跑一次探测，而不是拿一份已经过期的
 *    启动档继续用。摘要是**内容摘要**，不含路径（缓存键存在应用私有偏好里，路径不进偏好）。
 *
 * 实现类必须保持既有行为不变：绑定顺序、跳过口径、诊断字段、以及 [cacheToken] 拼出来的键串。
 */
internal interface OptionalBindProvider {
    /** 本提供者负责哪一类绑定目标（回退档据此整体撤掉这一类）。 */
    val group: OptionalBindGroup

    /** 实时挂载：本次启动真正能追加的绑定；宿主侧不可用时为空列表。 */
    fun bindMounts(): List<ProotBindMount>

    /** 启动档缓存键片段：可用性/内容一变，缓存即失效。 */
    fun cacheToken(): String
}

/**
 * 访客侧挂载点：落在 rootfs 根下的绝对路径（如 `/mnt/inbox`、`/mnt/user/1`），不存在时逐级
 * NoFollow 创建。两条链路共用同一份实现——「挂载点建不出来」在两边都只是跳过该条绑定，
 * 不是故障。
 */
internal fun ensureGuestMountPoint(root: File, guestPath: String): Boolean {
    if (!MailboxTree.isRealDirectory(root.toPath())) return false
    val directory = File(root, guestPath.removePrefix("/"))
    return try {
        MailboxTree.createDirectoriesNoFollow(root.toPath(), directory.toPath())
        true
    } catch (_: Throwable) {
        false
    }
}
