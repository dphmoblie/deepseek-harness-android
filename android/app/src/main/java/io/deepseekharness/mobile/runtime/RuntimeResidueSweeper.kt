package io.deepseekharness.mobile.runtime

import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File

/**
 * 残留条目的命名与 token 规范。
 *
 * 清理失败时 [RuntimeInstaller] 会把目录改名成 `stale-<原名>-<36 进制时间>` 挪到一边，
 * 这些名字与诊断 token 的生成规则集中在这里，保证「挪走」与「扫回来」用的是同一套判定。
 */
internal object RuntimeResidueNames {
    /** 改名残留的统一前缀。 */
    const val PREFIX = "stale-"

    /** 一次改名残留的名字；[millis] 只用于让同一名字的多份残留互不相同。 */
    fun asideName(name: String, millis: Long): String = "$PREFIX$name-${millis.toString(36)}"

    /** 是否是我们自己生成的改名残留；`stale-` 后面必须有内容，避免把空壳名字当残留。 */
    fun isResidueName(name: String): Boolean =
        name.startsWith(PREFIX) && name.length > PREFIX.length

    /** 同一个名字对应的全部改名残留（每失败一次就多一份）。 */
    fun siblingsOf(parent: File, name: String): List<File> = children(parent)
        .filter { it.name == "$PREFIX$name" || it.name.startsWith("$PREFIX$name-") }

    /** 残留条目的 reason token：`stale_` + 原名（去掉残留前缀），只保留 token 允许的字符。 */
    fun reason(name: String): String =
        RuntimeResidueTokens.of("stale_" + name.removePrefix(PREFIX), fallback = "stale")

    /** 列父目录的直接子项（按名字排序，保证每次清理顺序一致）；读不了就当作空目录。 */
    internal fun children(parent: File): List<File> =
        parent.listFiles()?.sortedBy { it.name } ?: emptyList()
}

/**
 * 把任意取值压成诊断策略允许的 token（`^[a-z0-9][a-z0-9._-]{0,31}$`）。
 *
 * 诊断策略对不合规的字段是**静默丢弃**，所以这里必须先规范化：否则一行记录会悄悄少掉
 * `reason`，排障时看不出清理的到底是哪一类残留。
 */
internal object RuntimeResidueTokens {
    /** 与 [io.deepseekharness.mobile.runtime.diagnostics.DiagnosticPolicy] 的 token 长度上限一致。 */
    const val MAX_CHARS = 32

    private val unsafe = Regex("[^a-z0-9._-]")

    fun of(raw: String, fallback: String): String {
        val sanitized = raw.lowercase().replace(unsafe, "_").take(MAX_CHARS)
        val first = sanitized.firstOrNull() ?: return fallback
        return if (first in 'a'..'z' || first in '0'..'9') sanitized else fallback
    }
}

/** 一份残留的删除结果：严格删除器 → 兜底删除器 → 失败。 */
internal sealed interface ResidueDeleteOutcome {
    /** 严格删除器直接删干净。 */
    object Cleaned : ResidueDeleteOutcome

    /** 严格删除器失败，由兜底删除器删干净；[strictCode] 是严格那次的失败类别。 */
    data class CleanedByFallback(val strictCode: String) : ResidueDeleteOutcome

    /** 两条删除器都失败；[failure] 是最终解释（兜底那次的失败）。 */
    data class Failed(val failure: RuntimeFailure) : ResidueDeleteOutcome
}

/**
 * 单份残留的删除分级，[RuntimeInstaller] 的清理路径与残留清扫器共用这一份实现。
 *
 * 真机上严格删除器（[RuntimeFiles.deleteTreeNoFollow]，走 `SecureDirectoryStream`）在这类大目录树上
 * 会直接失败，兜底删除器（[RuntimeFiles.deleteTreeNoFollowFallback]）能把残留真正回收掉，所以：
 *
 *  - 严格删除器成功即结束；
 *  - 严格失败后必须再走兜底，兜底成功要保留严格那次的失败类别（真机故障的证据）；
 *  - 兜底也失败时返回**兜底那次**失败，它才是「为什么还是删不掉」的最终解释。
 *
 * 两条删除器自带作用域校验（`normalizedRoot.parent != normalizedParent` ⇒ `RESET_SCOPE_INVALID`），
 * 这里不再放宽任何校验：越界路径一定会被拒绝。
 */
internal class RuntimeResidueCleanup(
    private val strictDelete: (File, File) -> Unit = { target, parent ->
        RuntimeFiles.deleteTreeNoFollow(target, parent)
    },
    private val fallbackDelete: (File, File) -> Unit = { target, parent ->
        RuntimeFiles.deleteTreeNoFollowFallback(target, parent)
    },
) {
    fun delete(target: File, allowedParent: File): ResidueDeleteOutcome {
        val strictFailure = try {
            strictDelete(target, allowedParent)
            return ResidueDeleteOutcome.Cleaned
        } catch (failure: RuntimeFailure) {
            failure
        }
        return try {
            fallbackDelete(target, allowedParent)
            ResidueDeleteOutcome.CleanedByFallback(RuntimeRetireTokens.of(strictFailure))
        } catch (failure: RuntimeFailure) {
            ResidueDeleteOutcome.Failed(failure)
        }
    }
}

/**
 * 一次残留清扫的结果。
 *
 * [cleaned]、[fallbackCleaned]、[failed] 都是**份数**；[reclaimedBytes] 是成功回收的字节数
 * （删除前按不跟随符号链接的方式统计，统计被条目预算截断时它是下界）。
 */
internal data class RuntimeResidueSweepResult(
    val cleaned: Int,
    val fallbackCleaned: Int,
    val failed: Int,
    val reclaimedBytes: Long,
) {
    val hasFailures: Boolean get() = failed > 0
}

/**
 * `stale-*` 残留清扫器：把「删除失败被改名挪到一边」的整份 rootfs 真正收回来。
 *
 * 语义是**尽力而为、绝不抛出**：
 *
 *  - 逐份走 [RuntimeResidueCleanup] 的分级删除，失败一定留一条 `result=failed` 记录，绝不静默；
 *  - 单份失败不阻断其余残留，也不重试同一份（重试只会把真故障变成静默重试）；
 *  - 调用方（安装成功收尾、显式重置、后续的版本管理页）因此可以安全地忽略异常，只看返回的份数与字节数。
 */
internal class RuntimeResidueSweeper(
    private val runtimeParent: File,
    private val cleanup: RuntimeResidueCleanup = RuntimeResidueCleanup(),
    private val bytesOf: (File) -> Long = { RuntimeResidueSizing.of(it).bytes },
    private val record: (DiagnosticLevel, Map<String, String>) -> Unit = { _, _ -> },
) {
    fun sweep(): RuntimeResidueSweepResult {
        val candidates = try {
            // 只扫运行时父目录的直接子项：与 resetWorkspace 的清理集合取的是同一份判定。
            RuntimeResidueNames.children(runtimeParent).filter { RuntimeResidueNames.isResidueName(it.name) }
        } catch (_: Throwable) {
            emptyList()
        }

        var cleaned = 0
        var fallbackCleaned = 0
        var failed = 0
        var reclaimed = 0L
        var lastCode: String? = null

        for (target in candidates) {
            val reason = RuntimeResidueNames.reason(target.name)
            val bytes = try {
                bytesOf(target)
            } catch (_: Throwable) {
                0L
            }
            try {
                when (val outcome = cleanup.delete(target, runtimeParent)) {
                    is ResidueDeleteOutcome.Cleaned -> {
                        cleaned += 1
                        reclaimed += bytes
                    }

                    is ResidueDeleteOutcome.CleanedByFallback -> {
                        cleaned += 1
                        fallbackCleaned += 1
                        reclaimed += bytes
                        record(
                            DiagnosticLevel.WARN,
                            mapOf(
                                "phase" to CLEANUP_PHASE,
                                "result" to "succeeded",
                                "code" to outcome.strictCode,
                                "reason" to reason,
                                "count" to "1",
                                "bytes" to residueBytesToken(bytes),
                            ),
                        )
                    }

                    is ResidueDeleteOutcome.Failed -> {
                        failed += 1
                        val code = RuntimeRetireTokens.of(outcome.failure)
                        lastCode = code
                        record(DiagnosticLevel.WARN, failureFields(code, reason, bytes))
                    }
                }
            } catch (error: Throwable) {
                // 删除器只应抛 RuntimeFailure：其它异常同样不能静默，照样记一行失败。
                failed += 1
                val code = RuntimeRetireTokens.of(error)
                lastCode = code
                record(DiagnosticLevel.WARN, failureFields(code, reason, bytes))
            }
        }

        if (candidates.isNotEmpty()) {
            record(
                if (failed == 0) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
                summaryFields(cleaned = cleaned, reclaimed = reclaimed, failed = failed, lastCode = lastCode),
            )
        }
        return RuntimeResidueSweepResult(
            cleaned = cleaned,
            fallbackCleaned = fallbackCleaned,
            failed = failed,
            reclaimedBytes = reclaimed,
        )
    }

    private fun failureFields(code: String, reason: String, bytes: Long): Map<String, String> = mapOf(
        "phase" to CLEANUP_PHASE,
        "result" to "failed",
        "code" to code,
        "reason" to reason,
        "bytes" to residueBytesToken(bytes),
    )

    /**
     * 汇总行：[result] 只在全部回收成功时才是 `succeeded`，只要还剩一份没删掉就是 `failed` +
     * `residual=true`，界面与导出件都能一眼看出「磁盘上还有残留」。
     */
    private fun summaryFields(
        cleaned: Int,
        reclaimed: Long,
        failed: Int,
        lastCode: String?,
    ): Map<String, String> = buildMap {
        put("phase", CLEANUP_PHASE)
        put("result", if (failed == 0) "succeeded" else "failed")
        put("reason", "stale_residue")
        put("count", cleaned.toString())
        put("bytes", residueBytesToken(reclaimed))
        put("residual", if (failed == 0) "false" else "true")
        if (failed > 0) put("code", lastCode ?: RuntimeRetireTokens.UNKNOWN)
    }

    private companion object {
        const val CLEANUP_PHASE = "cleanup"
    }
}
