package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.regex.Pattern

/**
 * Root 执行通道的静态指令防火墙（防误触协作网）。
 *
 * 目标：在指令送入内核执行前，快速阻断可能破坏系统完整性、清空硬件分区或致使设备变砖的高危工具调用。
 *
 * 【重要安全边界说明】：
 * 正则审查属于纵深防御的第一道协作预审，不能阻止所有复杂的动态 Shell 混淆绕过。
 * 核心安全底线由 [RootSecurityWrapper] 的 Mount/PID Namespace 隔离与物理分区/块设备锁定强制保障。
 */
object RootCommandGuard {

    private val BLOCKED_COMMAND_PATTERNS = listOf(
        // 阻断重启至刷机/引导模式
        Pattern.compile("""\breboot\s+(bootloader|recovery|edl|fastboot)\b""", Pattern.CASE_INSENSITIVE),
        // 阻断直接刷写工具与清空操作
        Pattern.compile("""\b(fastboot|flash_erase|wipe|make_ext4fs)\b""", Pattern.CASE_INSENSITIVE),
        // 阻断底层块设备参数重设与格式化
        Pattern.compile("""\b(blockdev|mkfs(\.[a-z0-9]+)?|mke2fs)\b""", Pattern.CASE_INSENSITIVE),
        // 阻断禁用 SELinux 强制模式
        Pattern.compile("""\bsetenforce\s+0\b""", Pattern.CASE_INSENSITIVE),
        // 阻断尝试卸载 /dev/block 屏蔽层
        Pattern.compile("""\bumount\s+([^|\n]*\s+)?["']?/dev/+block""", Pattern.CASE_INSENSITIVE),
        // 阻断重定向直写物理块设备 (例如 > /dev/block, of=/dev/block, tee /dev/block，支持带引号与多斜杠)
        Pattern.compile("""(>|of\s*=|tee\s+[^|\n]*)\s*["']?/dev/+block""", Pattern.CASE_INSENSITIVE),
        // 阻断尝试重新以可写模式挂载核心系统分区
        Pattern.compile("""\bmount\s+[^|\n]*-o\s+[^|\n]*remount[^|\n]*,?\s*rw\s+/(system|vendor|product|apex)""", Pattern.CASE_INSENSITIVE),
    )

    /**
     * 校验待执行的 Shell 脚本。
     * 若包含高危破坏性指令，直接抛出 [RuntimeFailure]，拒绝送入底层通道。
     */
    fun verifySafe(script: String) {
        for (pattern in BLOCKED_COMMAND_PATTERNS) {
            val matcher = pattern.matcher(script)
            if (matcher.find()) {
                val matched = matcher.group(0).take(48)
                throw RuntimeFailure(
                    "ROOT_SECURITY_VIOLATION",
                    "Root 命令被安全防火墙拦截（包含危险操作: $matched）",
                )
            }
        }
    }
}
