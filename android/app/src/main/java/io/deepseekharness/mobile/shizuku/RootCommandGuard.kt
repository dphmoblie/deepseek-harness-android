package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.Locale

/**
 * 根载荷静态安检（第一道协作网）。
 *
 * 与上一版的差异：
 * 1. 匹配前先 **归一化**（丢掉反斜杠转义、去掉单双引号、折叠空白、转小写），
 *    `reboot \"\\\n\" bootloader`、`\\/dev\\/block`、`'reboot'`、`REBOOT` 这类写法不再能靠字面变形绕过；
 * 2. 规则按「不可逆动作 + 目标」成对描述，而不是逐个命令名打标签——
 *    `wipe` 不再是关键词（`--wipe-cache` 这类正常用法会被误杀），改成 `wipe data/cache/system` 与 `fastboot -w`；
 * 3. 只拦真正不可逆的事：引导链、分区表、块设备写入、SELinux 全局关闭、核心分区改可写。
 *    `rm`/`chmod`/普通 `mount` 交给 [RootSecurityWrapper] 的私有命名空间 + 只读重挂 + `/dev/block` 遮蔽兜住，
 *    护栏不该变成绊脚石。
 * 4. `strict = true` 时加挂 [STRICT_RULES]：那是「沙箱已降级」的执行档（设备没有挂载命名空间，
 *    遮蔽与只读重挂都没生效）。此时没有第二层兜底，护栏必须自己补上——
 *    于是连 `mount`/`umount`、`dd`、写 `/dev` 下的节点、改系统目录权限、加载内核模块一并拦住。
 *    代价是这台设备上做不了挂载类操作，收益是「没有第二层时不来裸奔」。
 *
 * 【安全边界】正则预审只是第一道网，拦不住所有动态混淆。真正的底线是命名空间隔离、`/dev/block` 物理切断，
 * 以及 KernelSU 管理器 App Profile 里由用户亲自关掉的 `CAP_SYS_ADMIN`/`CAP_MKNOD`。
 */
object RootCommandGuard {

    /** 一条规则：`action` 给人看的动作名，`pattern` 作用于归一化文本。 */
    private data class Rule(val action: String, val pattern: Regex)

    /** 核心分区：这些路径改可写等于绕过只读重挂。 */
    private const val CORE_PARTITIONS = "(system|vendor|product|system_ext|odm|apex)"

    private val RULES: List<Rule> = listOf(
        Rule(
            "重启到刷机模式",
            Regex("""\breboot\b[^|;&]{0,40}\b(bootloader|recovery|edl|fastboot|sideload)\b"""),
        ),
        Rule("调用刷写工具", Regex("""\b(fastboot|fastbootd|flash_erase|flash_image|blkdiscard)\b""")),
        Rule("擦除分区数据", Regex("""\bwipe\s+(data|cache|system)\b""")),
        Rule("用 fastboot 擦除", Regex("""\bfastboot\b[^|;&]{0,20}\s-w\b""")),
        Rule("改分区表或格式化", Regex("""\b(parted|sfdisk|sgdisk|fdisk|mke2fs|mkfs(\.[a-z0-9]+)?|tune2fs|make_ext4fs)\b""")),
        Rule("块设备低级操作", Regex("""\b(blockdev|mknod)\b""")),
        Rule(
            "直写物理块设备",
            // `/dev//block`、`/dev/../dev/block` 这类多斜杠或绕路写法一并命中。
            Regex("""(>|of\s*=|tee\s+)[^|;&]*/(dev|by-name)/{1,}(block|disk|mmcblk|nvme|sd[a-z]|ufs)"""),
        ),
        Rule("卸载块设备遮蔽", Regex("""\bumount\b[^|;&]*/dev/+block""")),
        Rule(
            "把核心分区改回可写",
            Regex(
                """\bmount\b[^|;&]*\brw\b[^|;&]*/$CORE_PARTITIONS\b""" +
                    """|\bmount\b[^|;&]*/$CORE_PARTITIONS\b[^|;&]*\brw\b""",
            ),
        ),
        Rule("关闭 SELinux 强制模式", Regex("""\bsetenforce\s+(0|permissive)\b""")),
        Rule("写 SELinux 开关文件", Regex(""">\s*[^|;&]*/sys/fs/selinux/enforce""")),
        Rule("用内核参数关 SELinux", Regex("""\b(androidboot\.)?selinux\s*=\s*permissive\b""")),
    )

    /**
     * 降级档规则（`strict = true`）：只在「沙箱没能提供挂载隔离」时启用。
     *
     * 这些动作本身并不必然有害——`mount` 是常规运维、`chmod` 是修权限。它们被列进来是因为
     * 在**没有私有命名空间**的前提下，一次 `mount` 就落到宿主上，而 [RootSecurityWrapper]
     * 的遮蔽与只读重挂恰恰是防止这件事的那一层。那一层不在时，只能在这里拦。
     */
    private val STRICT_RULES: List<Rule> = listOf(
        Rule("降级档：挂载或卸载（无私有命名空间时直接落到宿主）", Regex("""\b(mount|umount)\b""")),
        Rule("降级档：镜像或设备直写工具", Regex("""(\bdd\b|\blosetup\b|\bdmsetup\b|\bof\s*=)""")),
        Rule("降级档：写 /dev 下的设备节点", Regex("""(>|tee\s+)[^|;&]*/dev/""")),
        Rule(
            "降级档：改系统目录权限或属主",
            Regex("""\b(chmod|chown|chgrp|setfacl)\b[^|;&]*/(system|vendor|product|system_ext|odm|apex|dev|proc|sys)\b"""),
        ),
        Rule("降级档：加载或卸载内核模块", Regex("""\b(insmod|rmmod|modprobe)\b""")),
        Rule(
            "降级档：写内核参数或 sysfs 关键子树",
            Regex("""(>|tee\s+)[^|;&]*/(proc/sys/|sys/(kernel|fs|module|class|devices|firmware)/)"""),
        ),
        // 前面不加 `\b`：`/` 本身不是单词字符，`cat /dev/mem` 里 `/` 前是空格，加 `\b` 会要求
        // 「单词字符↔非单词字符」的边界，而这个位置两边都不是单词字符 → 永不匹配（真机上是静默放行）。
        Rule("降级档：直接读写物理内存", Regex("""(/dev/mem|/dev/kmem|/proc/kcore)\b""")),
    )

    /**
     * 校验待执行的 Shell 脚本；命中任何规则即抛 [RuntimeFailure]，拒绝送进底层通道。
     *
     * @param strict 沙箱已降级（无挂载命名空间）时传 `true`，额外启用 [STRICT_RULES]。
     */
    fun verifySafe(script: String, strict: Boolean = false) {
        val normalized = normalize(script)
        for (rule in if (strict) RULES + STRICT_RULES else RULES) {
            val match = rule.pattern.find(normalized) ?: continue
            throw RuntimeFailure(
                "ROOT_SECURITY_VIOLATION",
                "根命令被安全护栏拦截：${rule.action}（命中片段: ${match.value.take(48)}）",
            )
        }
    }

    /** 逐条列出命中的动作名，供诊断与测试断言使用（不抛异常）。 */
    fun violations(script: String, strict: Boolean = false): List<String> {
        val normalized = normalize(script)
        return (if (strict) RULES + STRICT_RULES else RULES)
            .filter { it.pattern.containsMatchIn(normalized) }
            .map { it.action }
    }

    /**
     * 归一化：丢掉反斜杠（`\/dev` → `/dev`，`re\boot` → `reboot`）、去掉单双引号、折叠空白、转小写。
     * `$IFS`/`${IFS}` 这类变量替换无需特殊处理——它们不含 `|;&`，仍落在规则的任意字符段内。
     */
    fun normalize(script: String): String {
        val out = StringBuilder(script.length)
        for (char in script) {
            when (char) {
                '\\' -> Unit
                '\'', '"' -> Unit
                else -> out.append(char)
            }
        }
        return out.toString().replace(WHITESPACE, " ").lowercase(Locale.ROOT)
    }

    private val WHITESPACE = Regex("""\s+""")
}
