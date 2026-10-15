package io.deepseekharness.mobile.shizuku

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * KernelSU 挂载命名空间（Mount Namespace）与 PID 空间安全隔离包装器。
 *
 * 与旧实现的差异（每一条都是「该停下的时候必须停下」）：
 * 1. 外层不再把整段沙箱脚本塞进 `sh -c '<...>'` 的嵌套单引号里，而是 **Base64 编码后经管道**送进
 *    命名空间内的 `sh -s`。少一层引号拼接，就少一类转义逃逸；
 * 2. `mount --make-rprivate` 与 `/dev/block` 遮蔽失败一律 **失败即停**（受控退出码），不再 `|| true` 静默跳过：
 *    静默跳过的后果是「tmpfs 遮蔽向宿主传播」或「载荷在没有物理切断的环境里以 UID 0 运行」，两者都比报错危险；
 * 3. 命名空间能力按 `-m -p -f` → `-m -p` → `-m -f` → `-m` 依次探测降级，内核没开 `CONFIG_PID_NS` 时仍保有挂载隔离；
 *    探测用 `true` 试探，绝不会把载荷跑第二遍；
 * 4. 两层 Base64 解码都 **先落变量再校验非空**：解码失败时若直接进管道，`sh -s` 读到空输入会退出 0，
 *    调用方会把「什么都没跑」当成执行成功——这是最危险的一类假成功；
 * 5. 加固档（`setpriv` 裁减 `CAP_SYS_ADMIN`/`CAP_MKNOD`）先用 `true` 试探是否真的接受这些参数，
 *    不接受就输出诊断行并降级执行，不谎称已加固——真正无法被 UID 0 载荷撤销的硬约束只能来自
 *    KernelSU 管理器的 App Profile。
 *
 * 诊断行统一前缀 [DIAG_PREFIX]，只走 stderr、不含结果协议哨兵，不会干扰上层的标记解析。
 *
 * **调用约定**：返回值必须作为 `su -c` 的**单个参数**原样传入（见 [RootCommandRunner]）。
 * 若要拼进别的 shell 字符串，先过 [singleQuote]。
 */
object RootSecurityWrapper {

    /** `unshare` 缺失或内核不支持命名空间：拒绝在没有隔离的情况下以 UID 0 运行载荷。 */
    const val EXIT_NAMESPACE_UNAVAILABLE = 125

    /** 挂载传播私有化失败：继续下去可能把遮蔽层传播给宿主，必须停。 */
    const val EXIT_PROPAGATION_FAILED = 126

    /** `/dev/block` 遮蔽失败：防变砖的物理切断没生效，必须停。 */
    const val EXIT_BLOCK_MASK_FAILED = 127

    /** Base64 解码失败或解出空脚本：宁可报错，也不能让空脚本「成功」跑完。 */
    const val EXIT_DECODE_FAILED = 124

    /** stderr 诊断前缀（单行，不含协议哨兵）。 */
    const val DIAG_PREFIX = "DSH_ROOT_SANDBOX:"

    const val DIAG_NAMESPACE_UNAVAILABLE = "NAMESPACE_UNAVAILABLE"
    const val DIAG_PID_ISOLATION_UNAVAILABLE = "PID_ISOLATION_UNAVAILABLE"
    const val DIAG_PROPAGATION_FAILED = "PROPAGATION_FAILED"
    const val DIAG_BLOCK_MASK_FAILED = "BLOCK_MASK_FAILED"
    const val DIAG_PROC_REMOUNT_FAILED = "PROC_REMOUNT_FAILED"
    const val DIAG_HARDENING_UNAVAILABLE = "HARDENING_UNAVAILABLE"
    const val DIAG_DECODE_FAILED = "DECODE_FAILED"

    /** 物理块设备节点目录。 */
    private const val BLOCK_DIR = "/dev/block"

    /**
     * 把原始脚本包进沙箱，返回可直接交给 `su -c` 的命令串。
     *
     * @param hardened 裁减 `CAP_SYS_ADMIN`/`CAP_MKNOD`（默认开）。
     *                 需要 OverlayFS 之类挂载能力的任务显式传 `false`——那时硬约束只剩 App Profile。
     */
    fun wrapWithProtection(rawScript: String, hardened: Boolean = true): String {
        val innerB64 = base64(innerScript(base64(rawScript), hardened))
        return buildString {
            append("if ! command -v unshare >/dev/null 2>&1; then ")
            append("echo \"$DIAG_PREFIX$DIAG_NAMESPACE_UNAVAILABLE\" >&2; exit $EXIT_NAMESPACE_UNAVAILABLE; fi; ")
            // base64 解码器两种来源：优先 toybox 内置，其次独立 base64 命令。
            append("dsh_dec=\"base64\"; command -v toybox >/dev/null 2>&1 && dsh_dec=\"toybox base64 -d\"; ")
            // 逐级探测可用的命名空间组合；`true` 只用于试探内核能力，不执行载荷。
            append("dsh_ns=\"\"; ")
            append("if unshare -m -p -f true 2>/dev/null; then dsh_ns=\"-m -p -f\"; ")
            append("elif unshare -m -p true 2>/dev/null; then dsh_ns=\"-m -p\"; ")
            append("elif unshare -m -f true 2>/dev/null; then dsh_ns=\"-m -f\"; ")
            append("elif unshare -m true 2>/dev/null; then dsh_ns=\"-m\"; ")
            append("else echo \"$DIAG_PREFIX$DIAG_NAMESPACE_UNAVAILABLE\" >&2; exit $EXIT_NAMESPACE_UNAVAILABLE; fi; ")
            append("case \"\$dsh_ns\" in *-p*) ;; *) echo \"$DIAG_PREFIX$DIAG_PID_ISOLATION_UNAVAILABLE\" >&2;; esac; ")
            // 先解码到变量并校验非空，再进管道：空脚本会让 `sh -s` 以 0 退出，那是假成功。
            append("dsh_s=\$(printf '%s' '$innerB64' | \$dsh_dec 2>/dev/null) || ")
            append("{ echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
            append("[ -n \"\$dsh_s\" ] || { echo \"$DIAG_PREFIX$DIAG_DECODE_FAILED\" >&2; exit $EXIT_DECODE_FAILED; }; ")
            append("printf '%s' \"\$dsh_s\" | unshare \$dsh_ns /system/bin/sh -s; ")
            append("exit \$?")
        }
    }

    /** 命名空间内部的加固与载荷执行脚本（整段 Base64 传递，不参与外层引号拼接）。 */
    private fun innerScript(payloadB64: String, hardened: Boolean): String = buildString {
        // 1. 挂载传播私有化：rprivate 不支持时退到 rslave，两者都不行就直接停。
        append("mount --make-rprivate / 2>/dev/null || mount --make-rslave / 2>/dev/null || ")
        append("{ echo \"$DIAG_PREFIX$DIAG_PROPAGATION_FAILED\" >&2; exit $EXIT_PROPAGATION_FAILED; }; ")
        // 2. 核心分区只读重挂：按 mountpoint 判定，SAR 设备上 /system 不是挂载点会自然跳过，单点失败可容忍。
        append("for p in /system /vendor /product /system_ext /odm /apex; do ")
        append("[ -d \"\$p\" ] || continue; ")
        append("if command -v mountpoint >/dev/null 2>&1; then mountpoint -q \"\$p\" || continue; fi; ")
        append("mount -o remount,ro \"\$p\" 2>/dev/null || true; done; ")
        // 3. 物理块设备切断：遮蔽失败必须停，否则「防变砖」只是文档里的形容词。
        append("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs $BLOCK_DIR 2>/dev/null || ")
        append("{ echo \"$DIAG_PREFIX$DIAG_BLOCK_MASK_FAILED\" >&2; exit $EXIT_BLOCK_MASK_FAILED; }; ")
        // 4. 私有 procfs：切断 /proc/<宿主 pid>/root 穿透。失败只记录，不构成变砖风险。
        append("mount -t proc proc /proc 2>/dev/null || echo \"$DIAG_PREFIX$DIAG_PROC_REMOUNT_FAILED\" >&2; ")
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
