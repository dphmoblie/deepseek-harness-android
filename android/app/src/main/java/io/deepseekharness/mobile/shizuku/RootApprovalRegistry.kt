package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 放行来源：用于审计与执行器行为选择，永不落脚本原文。 */
enum class RootApprovalSource(val auditDetail: String) {
    AUTO_ALLOWED("TIER_LOW_AUTO_ALLOWED"),
    DSH_APPROVED("TIER_ELEVATED_DSH_APPROVED"),
    DSH_FULL_ACCESS("TIER_ELEVATED_DSH_FULL_ACCESS"),
    HOST_CONFIRMED("TIER_HIGH_HOST_CONFIRMED"),
    APP_WARNING_CONFIRMED("TIER_CRITICAL_APP_WARNING_CONFIRMED"),
}

/**
 * 宿主侧一次性根命令执行凭证。
 *
 * 构造器限定为 `internal`，只能由 [RootApprovalRegistry]（放行 `ALLOW` / `DSH_APPROVAL`）
 * 或 [RootApprovalCoordinator]（用户在 App 确认/警告页通过后放行 `HOST_CONFIRM` / `APP_WARNING`）
 * 签发；`RootCommandRunner.runApproved` 再次核验 [scriptSha256]，避免任何跳过确认的调用路径。
 */
class RootExecutionGrant internal constructor(
    val scriptSha256: String,
    val assessment: RootCommandGuard.Assessment,
    val source: RootApprovalSource,
)

/**
 * 根命令审批挑战登记表（绑定 `sha256(script)`、一次性消费、短 TTL、有界容量）。
 *
 * 职责边界：
 *  - 为所有需要审批/确认的命令签发 `challengeId`，防止审批通过与真正执行之间发生脚本内容替换（TOCTOU）；
 *  - **只允许**把 `ApprovalRoute.DSH_APPROVAL`（Tier 1）兑换为 [RootExecutionGrant]；
 *  - `HOST_CONFIRM`（Tier 2）与 `APP_WARNING`（Tier 3）绝不在这里兑换——它们必须进入
 *    [RootApprovalCoordinator] 由人类在 Android 宿主 App 页亲自确认。
 */
object RootApprovalRegistry {

    const val CHALLENGE_TTL_MS = 180_000L
    const val MODE_DSH_APPROVED = "dsh_approved"
    const val MODE_DSH_FULL_ACCESS = "dsh_full_access"
    private const val MAX_PENDING_CHALLENGES = 64

    private data class PendingChallenge(
        val id: String,
        val scriptSha256: String,
        val route: RootCommandGuard.ApprovalRoute,
        val createdAtMs: Long,
    )

    private val pending = ConcurrentHashMap<String, PendingChallenge>()

    fun sha256(script: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(StandardCharsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            hex.append(HEX_CHARS[value ushr 4])
            hex.append(HEX_CHARS[value and 0x0f])
        }
        return hex.toString()
    }

    /** 为可直接放行的命令签发凭证（`ALLOW` 档）。 */
    fun grantDirect(script: String, assessment: RootCommandGuard.Assessment): RootExecutionGrant {
        require(assessment.route == RootCommandGuard.ApprovalRoute.ALLOW) {
            "只有 ALLOW 路由可直接放行，实际为 ${assessment.route}"
        }
        return RootExecutionGrant(sha256(script), assessment, RootApprovalSource.AUTO_ALLOWED)
    }

    /** 签发一次性挑战 ID（绑定脚本摘要）。 */
    fun issueChallenge(
        script: String,
        assessment: RootCommandGuard.Assessment,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        pruneExpired(nowMs)
        evictIfFull()
        val id = UUID.randomUUID().toString()
        pending[id] = PendingChallenge(
            id = id,
            scriptSha256 = sha256(script),
            route = assessment.route,
            createdAtMs = nowMs,
        )
        return id
    }

    /**
     * 兑换 Tier 1（`DSH_APPROVAL`）的 dsh 审批凭证：一次性消费，过期/摘要不一致/重复使用均拒绝。
     *
     * 对 `HOST_CONFIRM` / `APP_WARNING` 永远拒绝，防止访客绕过 App 原生确认页。
     */
    fun consumeDshApproval(
        challengeId: String?,
        script: String,
        assessment: RootCommandGuard.Assessment,
        approvalMode: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): RootExecutionGrant {
        if (assessment.route != RootCommandGuard.ApprovalRoute.DSH_APPROVAL) {
            throw RuntimeFailure("ROOT_APPROVAL_INVALID", "该风险级别不能仅凭访客审批放行，需要在应用内确认")
        }
        val source = when (approvalMode) {
            MODE_DSH_APPROVED -> RootApprovalSource.DSH_APPROVED
            MODE_DSH_FULL_ACCESS -> RootApprovalSource.DSH_FULL_ACCESS
            else -> throw RuntimeFailure("ROOT_APPROVAL_INVALID", "根命令审批模式无效")
        }
        val id = challengeId?.takeIf { CHALLENGE_ID_PATTERN.matches(it) }
            ?: throw RuntimeFailure("ROOT_APPROVAL_INVALID", "根命令审批挑战标识缺失或无效")
        val entry = pending.remove(id)
            ?: throw RuntimeFailure("ROOT_APPROVAL_INVALID", "根命令审批挑战不存在、已使用或已失效，请重新发起")
        if (nowMs - entry.createdAtMs > CHALLENGE_TTL_MS || nowMs < entry.createdAtMs) {
            throw RuntimeFailure("ROOT_APPROVAL_EXPIRED", "根命令审批挑战已过期，请重新发起")
        }
        val actualHash = sha256(script)
        if (entry.scriptSha256 != actualHash) {
            throw RuntimeFailure("ROOT_APPROVAL_INVALID", "提交执行的脚本与审批内容不一致，已拒绝")
        }
        return RootExecutionGrant(actualHash, assessment, source)
    }

    /**
     * 校验并消费一个宿主侧待确认挑战（用于 `HOST_CONFIRM` / `APP_WARNING` 去重与防替换）；
     * 若挑战不存在或未提供，宿主仍可生成新标识并继续拉起 App 警告页（永不跳过确认）。
     */
    fun consumeChallengeIfMatching(
        challengeId: String?,
        script: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val id = challengeId?.takeIf { CHALLENGE_ID_PATTERN.matches(it) }
        if (id != null) {
            val entry = pending.remove(id)
            if (entry != null) {
                if (nowMs - entry.createdAtMs > CHALLENGE_TTL_MS || nowMs < entry.createdAtMs) {
                    throw RuntimeFailure("ROOT_APPROVAL_EXPIRED", "根命令确认请求已过期，请重新发起")
                }
                if (entry.scriptSha256 != sha256(script)) {
                    throw RuntimeFailure("ROOT_APPROVAL_INVALID", "提交执行的脚本与预检内容不一致，已拒绝")
                }
                return id
            }
        }
        return id ?: UUID.randomUUID().toString()
    }

    fun clear() {
        pending.clear()
    }

    private fun pruneExpired(nowMs: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowMs - entry.createdAtMs > CHALLENGE_TTL_MS || nowMs < entry.createdAtMs) {
                iterator.remove()
            }
        }
    }

    private fun evictIfFull() {
        while (pending.size >= MAX_PENDING_CHALLENGES) {
            val oldest = pending.entries.minByOrNull { it.value.createdAtMs } ?: break
            pending.remove(oldest.key, oldest.value)
        }
    }

    private val CHALLENGE_ID_PATTERN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
}
