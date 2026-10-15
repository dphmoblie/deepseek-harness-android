package io.deepseekharness.mobile.shizuku

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * KernelSU 挂载命名空间（Mount Namespace）与 PID 空间安全隔离包装器。
 *
 * 【降级口径】设备能力差异**不**中止执行，只如实报告：
 * 少数 ROM 的 toybox 没有 `unshare`；还有的内核没开 `CONFIG_MOUNT_NS`/`CONFIG_PID_NS`；
 * `setpriv` 也可能不接受裁剪参数的写法。这些都不是调用方的错，把根通道整个拒掉只会
 * 让「能做的事」凭空消失（TC-11 的验收口径：降级执行 + 能力看板如实展示，不硬失败）。
 * 于是本包装器的策略是：
 *
 * 1. 能隔离就隔离：`unshare -m -p -f` → `-m -p` → `-m -f` → `-m` 逐级探测（用 `true` 试探，绝不重跑载荷）；
 * 2. 隔离拿到哪一档，就只做那一档允许的加固——**没有挂载命名空间时绝不碰 `mount`**：
 *    在没有私有命名空间的环境里遮蔽 `/dev/block` 会把宿主系统的块设备一起藏掉，那不是加固是事故；
 * 3. 每一处降级都打一行 [DIAG_PREFIX] 诊断（`NAMESPACE_UNAVAILABLE`、`PID_ISOLATION_UNAVAILABLE`、
 *    `PROPAGATION_FAILED`、`BLOCK_MASK_FAILED`、`BLOCK_MASK_SKIPPED`、`CORE_PARTITION_RO_FAILED`、
 *    `PROC_REMOUNT_FAILED`、`HARDENING_UNAVAILABLE`），由 [RootCommandRunner] 收进 `diagnostics`
 *    并置 `degraded`，前端能力看板照实显示「降级执行」；
 * 4. 唯一还会让沙箱自己中止的是 **Base64 解码失败**（[EXIT_DECODE_FAILED]）：那说明调用侧的脚本
 *    或编码坏了，不是设备能力问题；让它「跑成空脚本并以 0 退出」是最危险的一类假成功。
 *
 * 降级之后剩下的硬约束是三道：[RootCommandGuard] 的静态审查（降级时按 `strict` 档跑，
 * 连 `mount`/`dd`/写 `/dev` 下的设备节点 一并拦住）、KernelSU 管理器 App Profile 里由用户亲自关掉的
 * `CAP_SYS_ADMIN`/`CAP_MKNOD`，以及应用自己的命令白名单。
 *
 * **调用约定**：返回值必须作为 `su -c` 的**单个参数**原样传入（见 [RootCommandRunner]）。
 * 若要拼进别的 shell 字符串，先过 [singleQuote]。
 */
object RootSecurityWrapper {

    /** Base64 解码失败或解出空脚本：宁可报错，也不能让空脚本「成功」跑完。 */
    const val EXIT_DECODE_FAILED = 124

    /** stderr 诊断前缀（单行，不含协议哨兵）。 */
    const val DIAG_PREFIX = "DSH_ROOT_SANDBOX:"

    const val DIAG_NAMESPACE_UNAVAILABLE = "NAMESPACE_UNAVAILABLE"
    const val DIAG_PID_ISOLATION_UNAVAILABLE = "PID_ISOLATION_UNAVAILABLE"
    const val DIAG_PROPAGATION_FAILED = "PROPAGATION_FAILED"
    const val DIAG_BLOCK_MASK_FAILED = "BLOCK_MASK_FAILED"
    const val DIAG_BLOCK_MASK_SKIPPED = "BLOCK_MASK_SKIPPED"
    const val DIAG_CORE_PARTITION_RO_FAILED = "CORE_PARTITION_RO_FAILED"
    const val DIAG_PROC_REMOUNT_FAILED = "PROC_REMOUNT_FAILED"
    const val DIAG_HARDENING_UNAVAILABLE = "HARDENING_UNAVAILABLE"
    const val DIAG_DECODE_FAILED = "DECODE_FAILED"

    /**
     * 「这次执行没能用上完整的沙箱」诊断码闭集。
     *
     * 与 [DIAG_DECODE_FAILED] 分开：解码失败是调用侧的 bug（已中止），其余都是设备能力差异（已降级执行）。
     */
    val DEGRADATION_CODES: Set<String> = setOf(
        DIAG_NAMESPACE_UNAVAILABLE,
        DIAG_PID_ISOLATION_UNAVAILABLE,
        DIAG_PROPAGATION_FAILED,
        DIAG_BLOCK_MASK_FAILED,
        DIAG_BLOCK_MASK_SKIPPED,
        DIAG_CORE_PARTITION_RO_FAILED,
        DIAG_PROC_REMOUNT_FAILED,
        DIAG_HARDENING_UNAVAILABLE,
    )

    /** 物理块设备节点目录。 */
    private const val BLOCK_DIR = "/dev/block"

    /** 内层脚本用来判断「我是不是真的在私有挂载命名空间里」的环境变量名。 */
    private const val ISOLATION_FLAG = "dsh_isolated"

    /**
     * 把原始脚本包进沙箱，返回可直接交给 `su -c` 的命令串。
     *
     * @param hardened 裁减 `CAP_SYS_ADMIN`/`CAP_MKNOD`（默认开）。
     *                 需要 OverlayFS 之类挂载能力的任务显式传 `false`——那时硬约束只剩 App Profile。
     */
    fun wrapWithProtection(rawScript: String, hardened: Boolean = true): String {
        val innerB64 = base64(innerScript(base64(rawScript), hardened))
        return buildString {
            // 逐级探测可用的命名空间组合；`true` 只用于试探内核能力，不执行载荷。
            // 一档都拿不到时不中止：降级为「无命名空间执行 + 强化静态审查」，并打诊断。
            append("dsh_ns=\"\"; ")
            append("if command -v unshare >/dev/null 2>&1; then ")
            append("if unshare -m -p -f true 2>/dev/null; then dsh_ns=\"-m -p -f\"; ")
            append("elif unshare -m -p true 2>/dev/null; then dsh_ns=\"-m -p\"; ")
            append("elif unshare -m -f true 2>/dev/null; then dsh_ns=\"-m -f\"; ")
            append("elif unshare -m true 2>/dev/null; then dsh_ns=\"-m\"; ")
            append("else echo \"$DIAG_PREFIX$DIAG_NAMESPACE_UNAVAILABLE\" >&2; fi; ")
            append("else echo \"$DIAG_PREFIX$DIAG_NAMESPACE_UNAVAILABLE\" >&2; fi; ")
            // 只有真的在私有命名空间里才谈 PID 隔离：没有命名空间时这一行是噪声。
            append("case \"\$dsh_ns\" in \"\"|*-p*) ;; *) ")
            append("echo \"$DIAG_PREFIX$DIAG_PID_ISOLATION_UNAVAILABLE\" >&2;; esac; ")
            // base64 解码器两种来源：优先 toybox 内置，其次独立 base64 命令。
            append("dsh_dec=\"base64\"; command -v toybox >/dev/null 2>&1 && dsh_dec=\"toybox base64 -d\"; ")
            // 先解码到变量并校验非空，再进管道：空脚本会让 `sh -s` 以 0 退出，那是假成功。
            append("dsh_s=\$(printf '%s' '$innerB64' | \$dsh_dec 2>/dev/null) || ")
            append("{ echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
            append("[ -n \"\$dsh_s\" ] || { echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
            // `dsh_isolated` 只在这条命令的环境里生效，内层据此决定「能不能碰 mount」。
            append("if [ -n \"\$dsh_ns\" ]; then ")
            append("printf '%s' \"\$dsh_s\" | $ISOLATION_FLAG=1 unshare \$dsh_ns /system/bin/sh -s; ")
            append("else printf '%s' \"\$dsh_s\" | $ISOLATION_FLAG=0 /system/bin/sh -s; fi; ")
            append("exit \$?")
        }
    }

    /** 命名空间内部的加固与载荷执行脚本（整段 Base64 传递，不参与外层引号拼接）。 */
    private fun innerScript(payloadB64: String, hardened: Boolean): String = buildString {
        // 1. 只有在私有挂载命名空间里才做挂载类加固：没有命名空间时 `mount -t tmpfs /dev/block`
        //    会把宿主系统的块设备一起遮掉，`remount,ro` 也会落到宿主上。
        append("if [ \"\$$ISOLATION_FLAG\" = \"1\" ]; then ")
        // 1.1 挂载传播私有化：拿不到就把遮蔽一并跳过（把遮蔽层传播给宿主比不遮蔽更危险）。
        append("dsh_priv=0; ")
        append("if mount --make-rprivate / 2>/dev/null; then dsh_priv=1; ")
        append("elif mount --make-rslave / 2>/dev/null; then dsh_priv=1; fi; ")
        append("if [ \"\$dsh_priv\" = \"1\" ]; then ")
        // 1.2 核心分区只读重挂：按 mountpoint 判定，SAR 设备上 /system 不是挂载点会自然跳过。
        append("for p in /system /vendor /product /system_ext /odm /apex; do ")
        append("[ -d \"\$p\" ] || continue; ")
        append("if command -v mountpoint >/dev/null 2>&1; then mountpoint -q \"\$p\" || continue; fi; ")
        append("mount -o remount,ro \"\$p\" 2>/dev/null || ")
        append("echo \"$DIAG_PREFIX$DIAG_CORE_PARTITION_RO_FAILED:\$p\" >&2; done; ")
        // 1.3 物理块设备切断：失败只报诊断（降级执行），不再中止。
        append("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs $BLOCK_DIR 2>/dev/null || ")
        append("echo \"$DIAG_PREFIX$DIAG_BLOCK_MASK_FAILED\" >&2; ")
        // 1.4 私有 procfs：切断 /proc/<宿主 pid>/root 穿透。失败只记录，不构成变砖风险。
        append("mount -t proc proc /proc 2>/dev/null || echo \"$DIAG_PREFIX$DIAG_PROC_REMOUNT_FAILED\" >&2; ")
        append("else echo \"$DIAG_PREFIX$DIAG_PROPAGATION_FAILED\" >&2; ")
        append("echo \"$DIAG_PREFIX$DIAG_BLOCK_MASK_SKIPPED\" >&2; fi; ")
        append("else echo \"$DIAG_PREFIX$DIAG_BLOCK_MASK_SKIPPED\" >&2; fi; ")
        append("dsh_dec=\"base64\"; command -v toybox >/dev/null 2>&1 && dsh_dec=\"toybox base64 -d\"; ")
        // 载荷同样先解码校验，避免空载荷假成功。
        append("dsh_p=\$(printf '%s' '$payloadB64' | \$dsh_dec 2>/dev/null) || ")
        append("{ echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
        append("[ -n \"\$dsh_p\" ] || { echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
        append("dsh_harden=\"\"; ")
        if (hardened) {
            // setpriv 存在还不够：参数不被接受时它会直接失败，先用 `true` 探一次。
            append("dsh_harden=\"setpriv --bounding-set=-sys_admin,-mknod --inh-caps=-sys_admin,-mknod --ambient-caps=-sys_admin,-mknod\"; ")
            append("if command -v setpriv >/dev/null 2>&1 && ")
            append("\$dsh_harden /system/bin/sh -c true 2>/dev/null; then ")
            append("printf '%s' \"\$dsh_p\" | \$dsh_harden /system/bin/sh -s; ")
            append("else echo \"$DIAG_PREFIX$DIAG_HARDENING_UNAVAILABLE\" >&2; ")
            append("printf '%s' \"\$dsh_p\" | /system/bin/sh -s; fi; ")
        } else {
            append("echo \"$DIAG_PREFIX$DIAG_HARDENING_UNAVAILABLE\" >&2; ")
            append("printf '%s' \"\$dsh_p\" | /system/bin/sh -s; ")
        }
        append("exit \$?")
    }

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * POSIX 单引号转义：用 `'\''` 闭合、转义并重开单引号。
     * 仅在外层必须把 [wrapWithProtection] 的结果拼进别的 shell 字符串时才需要。
     */
    fun singleQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"
}
