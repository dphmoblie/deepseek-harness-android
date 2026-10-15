package io.deepseekharness.mobile.shizuku

/**
 * 沙箱能力探测：**在跑载荷之前**先问一次「这台设备到底能给到什么档位的隔离」。
 *
 * 为什么必须先问：静态审查（[RootCommandGuard]）的严格档要按设备能力决定——
 * 有挂载命名空间时，`mount`/`dd` 这类动作由命名空间 + `/dev/block` 遮蔽兜住，不必拦；
 * 没有命名空间时，一次 `mount` 就直接落到宿主上，只能靠审查拦住。审查跑在 `su` 之前，
 * 所以「设备有没有命名空间」这件事必须**先于**审查被知道。
 *
 * 探测脚本是内部常量（不含任何外部输入），因此走 [RootCommandRunner.runProbe]：
 * 不包沙箱、不过护栏——它本身就是那个用来决定「要不要按严格档审」的前置测量。
 *
 * 结果缓存 [CACHE_TTL_MS]：能力随 ROM 与内核固定，逐条命令都探一次纯属浪费；
 * 但也不能永久缓存——设备重启、KernelSU 管理器改 App Profile 都会让它变。
 */
object RootSandboxProbe {

    /** 探测结果的缓存时长。 */
    const val CACHE_TTL_MS = 60_000L

    private const val PROBE_TIMEOUT_MS = 15_000L

    data class Capabilities(
        /** 挂载命名空间可用（`unshare -m` 起步）。 */
        val namespaceIsolation: Boolean,
        /** 挂载 + PID 命名空间都可用（`unshare -m -p`）。 */
        val pidIsolation: Boolean,
        /** `setpriv` 存在且接受裁剪参数的写法。 */
        val hardening: Boolean,
        val detectedAtMs: Long,
    ) {
        /** 有任何一档没拿到，就是降级执行。 */
        val degraded: Boolean
            get() = !namespaceIsolation || !pidIsolation || !hardening

        /** 降级原因码，按 [RootSecurityWrapper.DEGRADATION_CODES] 的同一套词表。 */
        val degradationCodes: List<String>
            get() = buildList {
                if (!namespaceIsolation) add(RootSecurityWrapper.DIAG_NAMESPACE_UNAVAILABLE)
                if (namespaceIsolation && !pidIsolation) add(RootSecurityWrapper.DIAG_PID_ISOLATION_UNAVAILABLE)
                if (!hardening) add(RootSecurityWrapper.DIAG_HARDENING_UNAVAILABLE)
            }
    }

    /** 探测脚本：三个能力各打一行，绝不动载荷、不改设备状态。 */
    internal const val PROBE_SCRIPT: String =
        "if command -v unshare >/dev/null 2>&1 && unshare -m true 2>/dev/null; then " +
            "echo dsh_cap_ns=1; else echo dsh_cap_ns=0; fi; " +
            "if command -v unshare >/dev/null 2>&1 && unshare -m -p true 2>/dev/null; then " +
            "echo dsh_cap_pid=1; else echo dsh_cap_pid=0; fi; " +
            "if command -v setpriv >/dev/null 2>&1 && " +
            "setpriv --bounding-set=-sys_admin,-mknod --inh-caps=-sys_admin,-mknod " +
            "--ambient-caps=-sys_admin,-mknod /system/bin/sh -c true 2>/dev/null; then " +
            "echo dsh_cap_harden=1; else echo dsh_cap_harden=0; fi"

    /** 测试注入点：默认真起一次 `su -c <探测脚本>`。 */
    var runner: (String) -> RootCommandRunner.RootCommandResult = { script ->
        RootCommandRunner.runProbe(script, PROBE_TIMEOUT_MS)
    }

    private var cached: Capabilities? = null

    /**
     * 探测能力（默认走缓存）。
     *
     * 探测本身失败（`su` 缺失、超时、输出看不懂）时按**最保守**的一档返回：认定没有命名空间隔离。
     * 这样静态审查会按严格档跑——宁可多拦，也不要因为「测不出来」而少拦一层。
     */
    fun detect(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): Capabilities {
        val hit = cached
        if (!force && hit != null && nowMs - hit.detectedAtMs in 0..CACHE_TTL_MS) return hit
        val fresh = parse(runner(PROBE_SCRIPT), nowMs)
        cached = fresh
        return fresh
    }

    /** 从探测输出解析能力；缺行即视为该能力不可用。 */
    internal fun parse(result: RootCommandRunner.RootCommandResult, nowMs: Long): Capabilities {
        val flags = result.output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                val key = trimmed.substringBefore('=')
                if (!key.startsWith("dsh_cap_")) return@mapNotNull null
                key to (trimmed.substringAfter('=', "").trim() == "1")
            }
            .toMap()
        val namespace = flags["dsh_cap_ns"] == true
        return Capabilities(
            namespaceIsolation = namespace,
            // 没有挂载命名空间时 PID 隔离无从谈起，直接记 false（与包装器一致：那时根本不进内层）。
            pidIsolation = namespace && flags["dsh_cap_pid"] == true,
            hardening = flags["dsh_cap_harden"] == true,
            detectedAtMs = nowMs,
        )
    }

    /** 测试与「用户刚改了 App Profile」时清缓存。 */
    fun clearCache() {
        cached = null
    }
}
