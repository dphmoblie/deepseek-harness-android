package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.Locale

/**
 * 根载荷静态安检（第一道协作网）。
 *
 * 与旧版「词表在任何位置命中就拒绝」的差异：
 * 1. 危险动作按**命令位置**匹配（行首、`;`、`|`、`&&`、`||` 之后），并剥掉
 *    `/system/bin/`、`busybox`、`toybox` 这类前缀；`echo "run mount now"` 不再误杀。
 * 2. 两个档位：
 *    - [Profile.STRICT]：默认档。在 [RULES] 之上加挂 [STRICT_RULES]，`mount`/`dd`/
 *      `insmod`/`setprop` 等一律拒绝；适合没有 KernelSU App Profile 兜底的设备。
 *    - [Profile.MAXIMUM]：最大档。只保留 [RULES] 里的不可逆动作——刷机、块设备、
 *      核心分区写入、数据销毁、电源操作、物理内存；`mount`/`dd`（普通文件）/`setprop`
 *      （普通属性）/`insmod` 放行。这是本应用与用户共同承担风险的档位。
 * 3. 不可逆动作始终在 [RULES] 里，两个档位都拦：bootloader/recovery/fastboot、
 *    `/dev/block` 读写、核心分区写入、`rm -rf /data` 一类数据销毁、`reboot`/`poweroff`
 *    与 `/proc/sysrq-trigger`、`/dev/mem`、`/proc/kcore`。
 *
 * 【安全边界】正则预审只是第一道网，拦不住所有动态混淆，也拦不住二进制/解释器内部动作。
 * 真正的底线仍是命名空间隔离、核心分区只读、`/dev/block` 遮蔽，以及 KernelSU App Profile。
 * 本类不承诺自己是硬边界；它只负责把明显的、可静态识别的不可逆动作挡在执行之前。
 */
object RootCommandGuard {

    /** 静态审查档位。 */
    enum class Profile(val wireValue: String) {
        /** 默认：不可逆动作 + 严格档（mount/dd/insmod/setprop 全部拒绝）。 */
        STRICT("strict"),

        /** 最大档：只拦不可逆动作；mount/dd（普通文件）/setprop（普通属性）/insmod 放行。 */
        MAXIMUM("maximum"),
        ;

        companion object {
            fun fromWire(value: String?): Profile =
                entries.firstOrNull { it.wireValue == value } ?: STRICT
        }
    }

    /** 一条规则：`action` 给人看的动作名，`pattern` 作用于归一化文本。 */
    private data class Rule(val action: String, val pattern: Regex)

    /** 核心分区：这些路径改可写等于绕过只读重挂。 */
    private const val CORE_PARTITIONS = "(system|vendor|product|system_ext|odm|apex|system_root)"

    /** 一条命令的起点：行首、分隔符之后，可带一层 shell 关键字/包装命令。 */
    private const val COMMAND_START =
        """(?:^|[;|&({!]\s*)(?:(?:then|else|do|if|elif|while|until|in|time|env|nohup|setsid|command|xargs|watch)\s+)?"""

    /** 命令前缀：绝对路径、busybox/toybox 包装。 */
    private const val COMMAND_PREFIX =
        """(?:/(?:system|system_ext|vendor|product|odm)/bin/|/data/adb/(?:ksu|magisk)/bin/)?(?:busybox\s+|toybox\s+)?"""

    /** 拼一个「命令位置 + 前缀 + 命令名」的正则片段。 */
    private fun command(name: String): String = COMMAND_START + COMMAND_PREFIX + name + """\b"""

    /**
     * 不可逆动作：两个档位都拦。
     *
     * 这里刻意保留「词出现在注释/echo 里也会命中」的少数规则（fastboot、mkfs、块设备等）：
     * 那些词在正常脚本里几乎不会作为普通文本出现，误杀代价远小于漏拦代价。
     */
    private val RULES: List<Rule> = listOf(
        // Command substitution, IFS expansion, and a second shell/eval can
        // synthesize a dangerous command after this text check.  A root payload
        // is already executed by our fixed shell, so there is no safe reason to
        // permit another interpreter or an indirect command name here.
        Rule("动态 Shell 展开", Regex("""(`|\$\(|\$\{?ifs\b)""", RegexOption.IGNORE_CASE)),
        Rule(
            "二次 Shell 解释",
            // normalize() turns newlines into ';' and folds other whitespace, so both are command boundaries here.
            Regex("""\b(eval|exec|source)\b|(?:^|[;&|\s])\.\s+|\b(sh|bash|mksh)\s+-c\b"""),
        ),
        Rule(
            "重启到刷机模式",
            Regex("""\breboot\b[^|;&]{0,40}\b(bootloader|recovery|edl|fastboot|sideload)\b"""),
        ),
        Rule("调用刷写工具", Regex("""\b(fastboot|fastbootd|flash_erase|flash_image|blkdiscard)\b""")),
        Rule("adb 侧载刷机", Regex(command("adb") + """\s+sideload\b""")),
        Rule(
            "通过 adb 重启",
            Regex(command("adb") + """[^|;&]*\b(?:reboot|poweroff|shutdown|(?:svc|cmd)\s+power)\b"""),
        ),
        Rule("嵌套 su 执行", Regex(command("su") + """[^|;&]*\s-c\b""")),
        Rule("通过 adb 执行 Shell", Regex(command("adb") + """[^|;&]*\s(?:shell|exec-out|exec-in)\b""")),
        Rule("第三方刷机工具", Regex("""\b(mtkclient|edl|odin|heimdall)\b""")),
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
            "读写块设备",
            Regex(
                command("(?:cat|dd|cp|mv|install|base64|strings|xxd|hexdump|debugfs|e2fsck|fsck|resize2fs|lpmake)") +
                    """[^|;&]*\s/dev/(?:block|disk|mmcblk|nvme|sd[a-z]|ufs)\b""",
            ),
        ),
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
        // Android 属性服务位于宿主系统命名空间之外，mount namespace 不能隔离它。
        // 最大档只拦电源/服务控制属性；普通属性由用户自行承担风险。
        Rule("修改电源/服务属性", Regex("""\b(setprop|resetprop)\b[^|;&]*\b(sys\.powerctl|ctl\.)""")),
        Rule(
            "直接写 Android 属性存储",
            Regex(
                """(>|tee\s+|of\s*=|\b(?:cp|mv|install)\b[^|;&]*\s)[^|;&]*(/data/property/|/dev/__properties__/?)""",
            ),
        ),

        // ---- 电源：软拦（最大档也拦直接命令；设置页的手动按钮走独立原生通道） ----
        Rule("重启或关机", Regex(command("(?:reboot|poweroff|shutdown|halt)"))),
        Rule("通过系统服务电源操作", Regex(command("(?:svc|cmd)") + """\s+power\b""")),
        Rule(
            "发送重启广播",
            Regex(command("am") + """[^|;&]*\b(?:reboot|android\.intent\.action\.reboot)\b"""),
        ),
        Rule("杀死 init", Regex(command("kill") + """[^|;&]*\s(?:-[\w-]+\s+)*1(?:\s|$|[;|&)])""")),
        Rule("写 sysrq 触发器", Regex("""(?:>|>>|tee\s+)[^|;&]*/proc/sysrq-trigger""")),
        Rule("写电源状态", Regex("""(?:>|>>|tee\s+)[^|;&]*/sys/power/""")),
        Rule(
            "停止关键系统服务",
            Regex(
                command("(?:stop|start)") +
                    """\s+(?:zygote|zygote_secondary|system_server|surfaceflinger|servicemanager)\b""",
            ),
        ),

        // ---- 数据销毁：只保护数据根与系统关键目录；/data/local/tmp 与模块目录仍可操作 ----
        Rule("删除数据根", Regex(command("rm") + """[^|;&]*\s/data/?\s*(?:$|[;|&)])""")),
        Rule("删除数据通配", Regex(command("rm") + """[^|;&]*\s/data/\*""")),
        Rule(
            "删除系统数据",
            Regex(
                command("rm") +
                    """[^|;&]*\s/data/(?:system|misc|vendor|apex|property|app|dalvik-cache|ota[^/\s]*|adb(?!\s*/modules/))(?:\s|/|$|[;|&)])""",
            ),
        ),
        Rule(
            "删除内置存储根",
            Regex(command("rm") + """[^|;&]*\s/(?:sdcard|storage/emulated/0|data/media/0)/?\s*(?:$|[;|&)])"""),
        ),
        Rule(
            "批量删除",
            Regex(command("find") + """[^|;&]*\s/(?:data|sdcard|storage/emulated/0)\b[^|;&]*\s-delete\b"""),
        ),
        Rule(
            "覆写系统数据",
            Regex(
                command("(?:shred|truncate)") +
                    """[^|;&]*\s/data/(?:system|misc|vendor|apex|property|app|dalvik-cache|adb(?!\s*/modules/))\b""",
            ),
        ),
        Rule(
            "覆盖系统数据",
            Regex(
                command("(?:cp|mv|install|dd)") +
                    """[^|;&]*(?:\s|=)/(?:data/(?:system|misc|vendor|apex|property|app|dalvik-cache|adb(?!\s*/modules/)))(?:\s|/|$|[;|&)])""",
            ),
        ),

        // ---- 核心分区写入：不依赖分区是否已被 remount rw ----
        Rule(
            "写核心分区",
            Regex("""(?:>|>>)\s*[^|;&]*/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/|\s|$|[;|&)])"""),
        ),
        Rule(
            "写核心分区",
            Regex(
                command("(?:tee|truncate|sed)") +
                    """[^|;&]*\s/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/|\s|$|[;|&)])""",
            ),
        ),
        Rule(
            "写核心分区",
            Regex(
                command("(?:cp|mv|install)") +
                    """[^|;&]*\s/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/[^\s;|&]*)?\s*(?:$|[;|&)])""",
            ),
        ),

        // ---- 物理内存：读也不允许 ----
        Rule("物理内存", Regex("""(/dev/mem|/dev/kmem|/proc/kcore)\b""")),
    )

    /**
     * 严格档规则（`Profile.STRICT`）：当前仓库的默认执行档。
     *
     * 这些动作本身并不必然有害——`mount` 是常规运维、`chmod` 是修权限。它们被列进来是因为
     * 在**没有私有命名空间**的前提下，一次 `mount` 就落到宿主上，而 [RootSecurityWrapper]
     * 的遮蔽与只读重挂恰恰是防止这件事的那一层。那一层不在时，只能在这里拦。
     */
    private val STRICT_RULES: List<Rule> = listOf(
        Rule("严格档：挂载或卸载（无私有命名空间时直接落到宿主）", Regex("""\b(mount|umount)\b""")),
        Rule("严格档：镜像或设备直写工具", Regex("""(\bdd\b|\blosetup\b|\bdmsetup\b|\bof\s*=)""")),
        Rule("严格档：写 /dev 下的设备节点", Regex("""(>|tee\s+)[^|;&]*/dev/""")),
        Rule(
            "严格档：改系统目录权限或属主",
            Regex("""\b(chmod|chown|chgrp|setfacl)\b[^|;&]*/(system|vendor|product|system_ext|odm|apex|dev|proc|sys)\b"""),
        ),
        Rule("严格档：加载或卸载内核模块", Regex("""\b(insmod|rmmod|modprobe)\b""")),
        Rule(
            "严格档：写内核参数或 sysfs 关键子树",
            Regex("""(>|tee\s+)[^|;&]*/(proc/sys/|sys/(kernel|fs|module|class|devices|firmware)/)"""),
        ),
        Rule("严格档：修改 Android 系统属性", Regex("""\b(setprop|resetprop)\b""")),
    )

    /**
     * 校验待执行的 Shell 脚本；命中任何规则即抛 [RuntimeFailure]，拒绝送进底层通道。
     *
     * @param profile 沙箱已降级（无挂载命名空间）时用 [Profile.STRICT]；
     *                用户显式选择最大档时用 [Profile.MAXIMUM]。
     */
    fun verifySafe(script: String, profile: Profile = Profile.STRICT) {
        val normalized = normalize(script)
        for (rule in rulesFor(profile)) {
            val match = rule.pattern.find(normalized) ?: continue
            throw RuntimeFailure(
                "ROOT_SECURITY_VIOLATION",
                "根命令被安全护栏拦截：${rule.action}（命中片段: ${match.value.take(48)}）",
            )
        }
    }

    /** 逐条列出命中的动作名，供诊断与测试断言使用（不抛异常）。 */
    fun violations(script: String, profile: Profile = Profile.STRICT): List<String> {
        val normalized = normalize(script)
        return rulesFor(profile)
            .filter { it.pattern.containsMatchIn(normalized) }
            .map { it.action }
    }

    private fun rulesFor(profile: Profile): List<Rule> =
        if (profile == Profile.STRICT) RULES + STRICT_RULES else RULES

    /**
     * 归一化：丢掉反斜杠（`\/dev` → `/dev`，`re\boot` → `reboot`）、去掉单双引号、折叠空白、转小写。
     * `$IFS`/`${IFS}` 这类变量替换无需特殊处理——它们不含 `|;&`，仍落在规则的任意字符段内。
     *
     * 换行在折叠空白前先变成 `;`：多行脚本里的下一条命令仍是一个**命令起点**。若按空白折叠，
     * `echo safe\nreboot` 会变成 `echo safe reboot`，命令位置规则就漏掉真正的 `reboot`。
     */
    fun normalize(script: String): String {
        val out = StringBuilder(script.length)
        for (char in script) {
            when (char) {
                '\\' -> Unit
                '\'', '"' -> Unit
                '\r' -> Unit
                '\n' -> out.append(';')
                else -> out.append(char)
            }
        }
        return out.toString().replace(WHITESPACE, " ").lowercase(Locale.ROOT)
    }

    private val WHITESPACE = Regex("""\s+""")
}
