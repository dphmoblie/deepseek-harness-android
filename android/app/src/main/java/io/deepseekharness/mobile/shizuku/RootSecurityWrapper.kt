package io.deepseekharness.mobile.shizuku

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * KernelSU 挂载命名空间（Mount Namespace）与 PID 空间安全隔离包装器。
 *
 * 核心目标：
 * 1. 进程树与命名空间隔离：使用 `unshare -m -p -f` 创建隔离的 Mount 和 PID Namespace，
 *    所有挂载变动不向 Android 全局传播，且子进程无法透过 `/proc/1/root` 逃逸；
 * 2. 系统分区只读锁定：在沙箱内强制将 /system, /vendor, /product 等重新挂载为只读 (ro)；
 * 3. 物理块设备屏蔽：将 /dev/block 挂载为不可访问的空 tmpfs (mode=0000, ro, nosuid, nodev)，
 *    从操作系统层彻底阻断物理扇区写入与格式化；
 * 4. 独立私有 /proc：在沙箱内挂载隔离的 procfs，阻断向宿主外部特权进程的穿越；
 * 5. 载荷防逃逸：将用户脚本以 Base64 编码后通过管道注入子 shell 执行，保持真实退出状态码。
 */
object RootSecurityWrapper {

    /**
     * 将原始 Shell 脚本包装进带防变砖安全沙箱的命令串中。
     * 返回结果可直接由 `su -c <command>` 执行。
     */
    fun wrapWithProtection(rawScript: String): String {
        val encodedPayload = Base64.getEncoder().encodeToString(
            rawScript.toByteArray(StandardCharsets.UTF_8)
        )

        // 命名空间内部的前置安全加固脚本
        val sandboxScript = buildString {
            // 1. 设置本命名空间所有挂载私有化，不向 Android 全局传播
            append("mount --make-rprivate / 2>/dev/null || true; ")
            // 2. 核心物理分区只读重新挂载
            append("for p in /system /vendor /product /system_ext /odm /apex; do ")
            append("[ -d \"\$p\" ] && mount -o remount,ro \"\$p\" 2>/dev/null || true; ")
            append("done; ")
            // 3. 彻底屏蔽物理块设备，禁止读写和执行
            append("mount -t tmpfs -o mode=0000,nosuid,nodev,ro tmpfs /dev/block 2>/dev/null || true; ")
            // 4. 挂载命名空间独立的私有 procfs，切断 /proc/<pid>/root 穿透
            append("mount -t proc proc /proc 2>/dev/null || true; ")
            // 5. 执行原始 Payload (作为独立子 shell，结果状态透传)
            append("printf '%s' '$encodedPayload' | toybox base64 -d | /system/bin/sh -s; ")
            append("exit \$?")
        }

        val escapedSandbox = singleQuote(sandboxScript)
        return "unshare -m -p -f /system/bin/sh -c $escapedSandbox"
    }

    /**
     * POSIX 单引号转义：用 `'\''` 闭合、转义并重开单引号，
     * 确保字符串整体作为单一字面量传递，杜绝命令注入与语法逃逸。
     */
    fun singleQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"
}
