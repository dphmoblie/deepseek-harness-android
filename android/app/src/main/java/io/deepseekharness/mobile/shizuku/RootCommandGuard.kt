package io.deepseekharness.mobile.shizuku

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.util.Locale

/**
 * 根载荷静态风险分级器（唯一规则真源）。
 *
 * 与早期「命中词表直接硬拦截」的演进差异：
 * 1. 危险动作按**命令位置**匹配（行首、`;`、`|`、`&&`、`||` 之后），并剥掉
 *    `/system/bin/`、`busybox`、`toybox` 这类前缀；`echo "run mount now"`、`echo reboot`、
 *    `ls /data 2>/dev/null` 等常规脚本不再误杀。
 * 2. 规则按破坏半径分成四档（[RiskTier]），全部路由到审批/警告而非一刀切拒绝：
 *    - [RiskTier.LOW]：低风险常规运维与诊断，直接放行；
 *    - [RiskTier.ELEVATED]：可逆运维与动态 Shell（`mount`/`dd` 普通文件/`insmod`/`setprop`/
 *      写 `/proc/sys` 与 `/dev` 设备节点/`$(...)`/`sh -c`/`eval`/`source`）。
 *      `Profile.MAXIMUM` 直接放行；`Profile.STRICT` 交由 dsh 官方审批（dsh 处于
 *      `danger-full-access` 时尊重 dsh 直接放行）；
 *    - [RiskTier.HIGH]：整机级高危（电源、关 SELinux、停系统服务、写核心分区、改系统属性存储、
 *      嵌套 `su -c`/`adb shell`）。需在 dsh 询问（若非 `never`）并经 Android 宿主 App 页确认；
 *    - [RiskTier.CRITICAL]：不可逆变砖/数据销毁红线（刷机、`fastboot`、`/dev/block` 读写、
 *      `rm -rf /data` 等数据销毁、`/dev/mem`/`/proc/kcore`）。**不再硬拦截**，但**必须打开
 *      App 极危警告页**展示完整脚本并经用户强确认后才允许执行，访客侧不可自行绕过。
 * 3. 多条规则同时命中时永远按**最高风险档**定级（`CRITICAL > HIGH > ELEVATED > LOW`），
 *    防止用 `$(...)` 等中危写法掩盖刷机或删库命令。
 *
 * 【安全边界】静态文本分类无法识别二进制内部动作或复杂混淆；
 * 真正不可被访客伪造的边界由宿主侧 `RootApprovalCoordinator`（App 警告确认页）、
 * 命名空间隔离以及 KernelSU App Profile 共同承担。
 */
object RootCommandGuard {

    /** 静态审查档位（与既有配置协议兼容）。 */
    enum class Profile(val wireValue: String) {
        /** 严格档：中高风险运维走 dsh 官方审批（尊重 dsh 完全权限）；高危/极危走 App 确认与警告页。 */
        STRICT("strict"),

        /** 最大档：常规运维与动态脚本直通；整机级高危与极危仍走 App 确认与警告页。 */
        MAXIMUM("maximum"),
        ;

        companion object {
            fun fromWire(value: String?): Profile =
                entries.firstOrNull { it.wireValue == value } ?: STRICT
        }
    }

    /** 风险层级（由低到高）。 */
    enum class RiskTier(val wireValue: String) {
        LOW("low"),
        ELEVATED("elevated"),
        HIGH("high"),
        CRITICAL("critical"),
    }

    /** 宿主判定的放行/询问路径。 */
    enum class ApprovalRoute(val wireValue: String) {
        /** 无需额外确认，直接执行。 */
        ALLOW("allow"),

        /** 走 dsh 官方审批（若 dsh 会话为 `danger-full-access` 则尊重 dsh 直接放行）。 */
        DSH_APPROVAL("dsh_approval"),

        /** dsh 询问（若非 `never`）+ Android 宿主 App 页确认（不可被访客绕过）。 */
        HOST_CONFIRM("host_confirm"),

        /** 不可逆极危红线：必须打开 App 极危警告页并强确认（不可被访客绕过）。 */
        APP_WARNING("app_warning"),
    }

    /** 一次静态分类的完整结论。 */
    data class Assessment(
        val tier: RiskTier,
        val route: ApprovalRoute,
        /** 按严重度排序的命中规则名（给人看，不含脚本敏感参数之外的原文）。 */
        val actions: List<String>,
        /** 最高风险规则命中的短片段（最多 48 字符，供警告卡片定位）。 */
        val snippet: String,
        /** 是否需要放开 `setpriv` 能力裁剪（如挂载、内核模块、写 `/dev`/`/proc/sys`、电源等）。 */
        val needsUnconfinedCaps: Boolean,
        /** 是否需要跳过外层沙箱包装（仅当用户明确在 App 警告页确认了 HIGH / CRITICAL 时生效）。 */
        val bypassSandboxWrapper: Boolean,
        /** 命中的细分高危权限类别（去重且按严重度排序），供启动器高危权限硬编码开关直接拦截。 */
        val categories: List<HighRiskCategory> = emptyList(),
    ) {
        val requiresHostConfirm: Boolean
            get() = route == ApprovalRoute.HOST_CONFIRM || route == ApprovalRoute.APP_WARNING
    }

    /** 一条规则：`action` 给人看的动作名，`category` 对应启动器细分权限开关，`pattern` 作用于归一化文本。 */
    private data class Rule(
        val action: String,
        val category: HighRiskCategory,
        val pattern: Regex,
        val unconfinedCaps: Boolean = false,
    )

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
     * Tier 3 · 不可逆变砖 / 数据销毁红线：未开启对应细分权限时由硬编码规则直接拦截；
     * 开启后必须打开 App 极危警告页并由用户强确认。
     *
     * 少数极高危词（fastboot、mkfs、块设备等）保留全位置匹配：正常脚本几乎不会作为普通文本出现。
     */
    private val CRITICAL_RULES: List<Rule> = listOf(
        Rule(
            "重启到刷机模式",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\breboot\b[^|;&]{0,40}\b(bootloader|recovery|edl|fastboot|sideload)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "调用刷写工具",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\b(fastboot|fastbootd|flash_erase|flash_image|blkdiscard)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "adb 侧载刷机",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex(command("adb") + """\s+sideload\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "第三方刷机工具",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\b(mtkclient|edl|odin|heimdall)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "擦除分区数据",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex("""\bwipe\s+(data|cache|system)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "用 fastboot 擦除",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex("""\bfastboot\b[^|;&]{0,20}\s-w\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "改分区表或格式化",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\b(parted|sfdisk|sgdisk|fdisk|mke2fs|mkfs(\.[a-z0-9]+)?|tune2fs|make_ext4fs)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "块设备低级操作",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\b(blockdev|mknod)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "直写物理块设备",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            // `/dev//block`、`/dev/../dev/block` 这类多斜杠或绕路写法一并命中。
            Regex("""(>|of\s*=|tee\s+)[^|;&]*/(dev|by-name)/{1,}(block|disk|mmcblk|nvme|sd[a-z]|ufs)"""),
            unconfinedCaps = true,
        ),
        Rule(
            "卸载块设备遮蔽",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""\bumount\b[^|;&]*/dev/+block"""),
            unconfinedCaps = true,
        ),
        Rule(
            "读写块设备",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex(
                command("(?:cat|dd|cp|mv|install|base64|strings|xxd|hexdump|debugfs|e2fsck|fsck|resize2fs|lpmake)") +
                    """[^|;&]*\s/dev/(?:block|disk|mmcblk|nvme|sd[a-z]|ufs)\b""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "高风险操作使用不确定路径",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            // 变量、通配符和路径穿越可能把写入/删除目标解析到核心目录；最大档也不能静默放行。
            Regex(
                command("(?:rm|cp|mv|install|shred|truncate|dd|tee)") +
                    """[^|;&]*\s/[^|;&]*(?:\$|`|[*?]|\[|/\.\.(?:/|$)|/\.(?:/|$)|/{2,})""",
            ),
            unconfinedCaps = true,
        ),
        // ---- 数据销毁：保护数据根与系统关键目录；/data/local/tmp 与模块目录仍可常规操作 ----
        Rule(
            "删除数据根",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(command("rm") + """[^|;&]*\s/data/?\s*(?:$|[;|&)])"""),
        ),
        Rule(
            "删除数据通配",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(command("rm") + """[^|;&]*\s/data/\*"""),
        ),
        Rule(
            "删除时使用不确定路径",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            // 变量、命令替换、通配符和路径穿越无法在静态分类时可靠解析，按极危处理，
            // 防止 `rm -rf /$name`、`rm -rf /d*` 或 `/data/../data` 绕过数据根规则。
            Regex(
                command("rm") +
                    """[^|;&]*\s/(?:\$|`|[*?]|\[|data/(?:\.\.?(?:/|$)|/{2,}))""",
            ),
        ),
        Rule(
            "删除系统数据",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(
                command("rm") +
                    """[^|;&]*\s/data/(?:system|misc|vendor|apex|property|app|dalvik-cache|ota[^/\s]*|adb(?!\s*/modules/))(?:\s|/|$|[;|&)])""",
            ),
        ),
        Rule(
            "删除内置存储根",
            HighRiskCategory.SHARED_STORAGE_DELETE,
            Regex(command("rm") + """[^|;&]*\s/(?:sdcard|storage/emulated/0|storage/self/primary|data/media/0)/?\s*(?:$|[;|&)])"""),
        ),
        Rule(
            "删除内置存储关键目录或通配",
            HighRiskCategory.SHARED_STORAGE_DELETE,
            Regex(
                command("rm") +
                    """[^|;&]*\s/(?:sdcard|storage/emulated/0|storage/self/primary|data/media/0)/(?:\*|(?:dcim|pictures|download|documents|movies|music)\b)""",
            ),
        ),
        Rule(
            "批量删除",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(command("find") + """[^|;&]*\s/data\b[^|;&]*\s-delete\b"""),
        ),
        Rule(
            "批量删除",
            HighRiskCategory.SHARED_STORAGE_DELETE,
            Regex(command("find") + """[^|;&]*\s/(?:sdcard|storage/emulated/0|storage/self/primary|data/media/0)\b[^|;&]*\s-delete\b"""),
        ),
        Rule(
            "覆写系统数据",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(
                command("(?:shred|truncate)") +
                    """[^|;&]*\s/data/(?:system|misc|vendor|apex|property|app|dalvik-cache|adb(?!\s*/modules/))\b""",
            ),
        ),
        Rule(
            "覆盖系统数据",
            HighRiskCategory.SYSTEM_DATA_DESTRUCTION,
            Regex(
                command("(?:cp|mv|install|dd)") +
                    """[^|;&]*(?:\s|=)/(?:data/(?:system|misc|vendor|apex|property|app|dalvik-cache|adb(?!\s*/modules/)))(?:\s|/|$|[;|&)])""",
            ),
        ),
        // ---- 物理内存 ----
        Rule(
            "物理内存",
            HighRiskCategory.BLOCK_FLASH_MEMORY_RAW,
            Regex("""(/dev/mem|/dev/kmem|/proc/kcore)\b"""),
            unconfinedCaps = true,
        ),
    )

    /**
     * Tier 2 · 整机级高危：电源、SELinux、系统核心分区写入、关键服务控制、嵌套特权外壳。
     * 未开启对应细分权限时由硬编码规则直接拦截；开启后需经 dsh 官方询问 + Android 宿主 App 页确认后方可执行。
     */
    private val HIGH_RULES: List<Rule> = listOf(
        Rule(
            "把核心分区改回可写",
            HighRiskCategory.CORE_PARTITION_WRITE,
            Regex(
                """\bmount\b[^|;&]*\brw\b[^|;&]*/$CORE_PARTITIONS\b""" +
                    """|\bmount\b[^|;&]*/$CORE_PARTITIONS\b[^|;&]*\brw\b""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "写核心分区",
            HighRiskCategory.CORE_PARTITION_WRITE,
            Regex("""(?:>|>>)\s*[^|;&]*/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/|\s|$|[;|&)])"""),
            unconfinedCaps = true,
        ),
        Rule(
            "写核心分区",
            HighRiskCategory.CORE_PARTITION_WRITE,
            Regex(
                command("(?:tee|truncate|sed)") +
                    """[^|;&]*\s/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/|\s|$|[;|&)])""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "写核心分区",
            HighRiskCategory.CORE_PARTITION_WRITE,
            Regex(
                command("(?:cp|mv|install)") +
                    """[^|;&]*\s/(?:system|vendor|product|system_ext|odm|apex|system_root)(?:/[^\s;|&]*)?\s*(?:$|[;|&)])""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "关闭 SELinux 强制模式",
            HighRiskCategory.SELINUX_SECURITY_POLICY,
            Regex("""\bsetenforce\s+(0|permissive)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "写 SELinux 开关文件",
            HighRiskCategory.SELINUX_SECURITY_POLICY,
            Regex(""">\s*[^|;&]*/sys/fs/selinux/enforce"""),
            unconfinedCaps = true,
        ),
        Rule(
            "用内核参数关 SELinux",
            HighRiskCategory.SELINUX_SECURITY_POLICY,
            Regex("""\b(androidboot\.)?selinux\s*=\s*permissive\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "修改电源/服务属性",
            HighRiskCategory.SYSTEM_PROPERTIES_WRITE,
            Regex("""\b(setprop|resetprop)\b[^|;&]*\b(sys\.powerctl|ctl\.)"""),
            unconfinedCaps = true,
        ),
        Rule(
            "直接写 Android 属性存储",
            HighRiskCategory.SYSTEM_PROPERTIES_WRITE,
            Regex(
                """(>|tee\s+|of\s*=|\b(?:cp|mv|install)\b[^|;&]*\s)[^|;&]*(/data/property/|/dev/__properties__/?)""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "重启或关机",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex(command("(?:reboot|poweroff|shutdown|halt)")),
            unconfinedCaps = true,
        ),
        Rule(
            "通过系统服务电源操作",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex(command("(?:svc|cmd)") + """\s+power\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "发送重启广播",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex(command("am") + """[^|;&]*\b(?:reboot|android\.intent\.action\.reboot)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "通过 adb 重启",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex(command("adb") + """[^|;&]*\b(?:reboot|poweroff|shutdown|(?:svc|cmd)\s+power)\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "杀死 init",
            HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            Regex(command("kill") + """[^|;&]*\s(?:-[\w-]+\s+)*1(?:\s|$|[;|&)])"""),
            unconfinedCaps = true,
        ),
        Rule(
            "写 sysrq 触发器",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex("""(?:>|>>|tee\s+)[^|;&]*/proc/sysrq-trigger"""),
            unconfinedCaps = true,
        ),
        Rule(
            "写电源状态",
            HighRiskCategory.DEVICE_POWER_CONTROL,
            Regex("""(?:>|>>|tee\s+)[^|;&]*/sys/power/"""),
            unconfinedCaps = true,
        ),
        Rule(
            "停止关键系统服务",
            HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            Regex(
                command("(?:stop|start)") +
                    """\s+(?:zygote|zygote_secondary|system_server|surfaceflinger|servicemanager)\b""",
            ),
            unconfinedCaps = true,
        ),
        Rule(
            "嵌套 su 执行",
            HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            Regex(command("su") + """[^|;&]*\s-c\b"""),
            unconfinedCaps = true,
        ),
        Rule(
            "通过 adb 执行 Shell",
            HighRiskCategory.SYSTEM_SERVICE_PRIVILEGE,
            Regex(command("adb") + """[^|;&]*\s(?:shell|exec-out|exec-in)\b"""),
            unconfinedCaps = true,
        ),
    )

    /**
     * Tier 1 · 中高危可逆运维 & 动态 Shell 展开：
     * - 未开启对应细分权限时由硬编码规则直接拦截；
     * - 开启后：`Profile.MAXIMUM` 下直接放行；`Profile.STRICT` 下交由 dsh 官方审批（若 dsh 为 `danger-full-access` 则尊重 dsh 放行）。
     *
     * `/dev/null`、`2>/dev/null`、`/dev/zero`、`/dev/std*` 等标准空设备/流重定向不视为危险写设备。
     */
    private val ELEVATED_RULES: List<Rule> = listOf(
        Rule(
            "动态 Shell 展开",
            HighRiskCategory.DYNAMIC_SHELL_EVAL,
            Regex("""(`|\$\(|\$\{?ifs\b)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            "二次 Shell 解释",
            HighRiskCategory.DYNAMIC_SHELL_EVAL,
            Regex("""\b(eval|exec|source)\b|(?:^|[;&|\s])\.\s+|\b(sh|bash|mksh)\s+-c\b"""),
        ),
        Rule(
            "挂载或卸载",
            HighRiskCategory.FILESYSTEM_MOUNT,
            Regex(command("(?:mount|umount)")),
            unconfinedCaps = true,
        ),
        Rule(
            "镜像或设备直写工具",
            HighRiskCategory.RAW_DEVICE_IMAGE_TOOL,
            Regex(command("(?:dd|losetup|dmsetup)") + """|\bof\s*="""),
            unconfinedCaps = true,
        ),
        Rule(
            "写 /dev 下的设备节点",
            HighRiskCategory.DEV_NODE_WRITE,
            Regex("""(>|tee\s+)[^|;&]*/dev/(?!(?:null|zero|stdout|stderr|stdin|fd/\d+)(?:\s|$|[;|&)]))"""),
            unconfinedCaps = true,
        ),
        Rule(
            "改系统目录权限或属主",
            HighRiskCategory.SYSTEM_PERMISSIONS_OWNER,
            Regex(
                """\b(chmod|chown|chgrp|setfacl|chcon|restorecon)\b[^|;&]*/(?:system|vendor|product|system_ext|odm|apex|dev|proc|sys|data/(?:system|misc|vendor|apex|property|app|dalvik-cache|data|user/\d+|user_de/\d+|adb(?!\s*/modules/)))\b""",
            ),
        ),
        Rule(
            "加载或卸载内核模块",
            HighRiskCategory.KERNEL_MODULE_CONTROL,
            Regex(command("(?:insmod|rmmod|modprobe)")),
            unconfinedCaps = true,
        ),
        Rule(
            "写内核参数或 sysfs 关键子树",
            HighRiskCategory.KERNEL_SYSFS_PROC_WRITE,
            Regex("""(>|tee\s+)[^|;&]*/(proc/sys/|sys/(kernel|fs|module|class|devices|firmware)/)"""),
            unconfinedCaps = true,
        ),
        Rule(
            "修改 Android 系统属性",
            HighRiskCategory.SYSTEM_PROPERTIES_WRITE,
            Regex(command("(?:setprop|resetprop)")),
        ),
        Rule(
            "修改或删除应用私有数据",
            HighRiskCategory.APP_PRIVATE_DATA_MODIFY,
            Regex(
                command("(?:rm|shred|truncate)") +
                    """[^|;&]*\s/(?:data/(?:data|user/\d+|user_de/\d+)|(?:sdcard|storage/emulated/0|storage/self/primary)/android/(?:data|obb))(?:/|\s|$|[;|&)])""" +
                    """|(?:>|>>|tee\s+|of\s*=)[^|;&]*/(?:data/(?:data|user/\d+|user_de/\d+)|(?:sdcard|storage/emulated/0|storage/self/primary)/android/(?:data|obb))/""" +
                    """|""" + command("(?:cp|mv|install)") +
                    """[^|;&]*\s/(?:data/(?:data|user/\d+|user_de/\d+)|(?:sdcard|storage/emulated/0|storage/self/primary)/android/(?:data|obb))(?:/[^\s;|&]*)?\s*(?:$|[;|&)])""" +
                    """|""" + command("sed") +
                    """[^|;&]*\s-i\b[^|;&]*/(?:data/(?:data|user/\d+|user_de/\d+)|(?:sdcard|storage/emulated/0|storage/self/primary)/android/(?:data|obb))/""",
            ),
        ),
        Rule(
            "安装卸载或禁用应用",
            HighRiskCategory.PACKAGE_INSTALL_REMOVE,
            Regex(command("(?:pm|cmd\\s+package)") + """\s+(?:uninstall|install|clear|disable|disable-user|hide|suspend)\b"""),
        ),
    )

    /**
     * 对待执行的 Root 脚本做分级评估（唯一规则真源）。
     *
     * 多条规则同时命中时按最高风险档定级（`CRITICAL > HIGH > ELEVATED > LOW`）。
     */
    fun classify(script: String, profile: Profile = Profile.STRICT): Assessment {
        val normalized = normalize(script)
        val criticalMatches = matchRules(normalized, CRITICAL_RULES)
        val highMatches = matchRules(normalized, HIGH_RULES)
        val elevatedMatches = matchRules(normalized, ELEVATED_RULES)

        val (tier, primaryMatches) = when {
            criticalMatches.isNotEmpty() -> RiskTier.CRITICAL to criticalMatches
            highMatches.isNotEmpty() -> RiskTier.HIGH to highMatches
            elevatedMatches.isNotEmpty() -> RiskTier.ELEVATED to elevatedMatches
            else -> RiskTier.LOW to emptyList()
        }

        val route = when (tier) {
            RiskTier.LOW -> ApprovalRoute.ALLOW
            RiskTier.ELEVATED -> if (profile == Profile.MAXIMUM) ApprovalRoute.ALLOW else ApprovalRoute.DSH_APPROVAL
            RiskTier.HIGH -> ApprovalRoute.HOST_CONFIRM
            RiskTier.CRITICAL -> ApprovalRoute.APP_WARNING
        }

        val allOrdered = criticalMatches + highMatches + elevatedMatches
        val actions = allOrdered.map { it.first.action }.distinct().take(MAX_REPORTED_ACTIONS)
        val categories = allOrdered.map { it.first.category }.distinct()
        val snippet = primaryMatches.firstOrNull()?.second?.take(MAX_SNIPPET_CHARS).orEmpty()
        val needsUnconfinedCaps = tier == RiskTier.HIGH ||
            tier == RiskTier.CRITICAL ||
            allOrdered.any { it.first.unconfinedCaps }
        val bypassSandboxWrapper = tier == RiskTier.HIGH || tier == RiskTier.CRITICAL

        return Assessment(
            tier = tier,
            route = route,
            actions = actions,
            snippet = snippet,
            needsUnconfinedCaps = needsUnconfinedCaps,
            bypassSandboxWrapper = bypassSandboxWrapper,
            categories = categories,
        )
    }

    /**
     * 宿主硬编码高危权限开关拦截器：
     * - 低风险常规命令（未命中任何高危分类）直接通过；
     * - 一旦命中任何高危分类（[Assessment.categories] 非空）：
     *   1. 若启动器「AI 高危文件与命令权限总开关」关闭（`!permissions.enabled`），**当场硬拦截**；
     *   2. 若命中分类中存在任一未开启的细分开关，**当场硬拦截**；
     *   3. 仅当总开关开启且所有命中的细分开关均开启时，才允许进入后续执行/审批链路。
     *
     * 此检查完全由宿主硬编码正则规则执行，绝不交由 AI 自行判断。
     */
    fun verifyPermitted(
        script: String,
        profile: Profile = Profile.STRICT,
        permissions: HighRiskPermissions,
    ): Assessment {
        val assessment = classify(script, profile)
        if (assessment.categories.isEmpty()) return assessment
        val action = assessment.actions.firstOrNull() ?: "高危文件或命令操作"
        if (!permissions.enabled) {
            val cats = assessment.categories.joinToString("、") { it.title }
            throw RuntimeFailure(
                "ROOT_SECURITY_VIOLATION",
                "根命令被安全护栏拦截：$action（AI 高危文件与命令权限总开关已关闭，涉及细分项：$cats；命中片段: ${assessment.snippet}）",
            )
        }
        val blockedCategories = assessment.categories.filterNot { permissions.isCategoryEnabled(it) }
        if (blockedCategories.isNotEmpty()) {
            val cats = blockedCategories.joinToString("、") { it.title }
            throw RuntimeFailure(
                "ROOT_SECURITY_VIOLATION",
                "根命令被安全护栏拦截：$action（未开启高危权限细分开关：$cats；命中片段: ${assessment.snippet}）",
            )
        }
        return assessment
    }

    /**
     * 校验未经过审批流程的直接执行脚本；任何需要审批/确认的档位一律 fail-closed 抛 [RuntimeFailure]。
     *
     * 已通过 dsh 审批或宿主确认页的请求走 [RootCommandRunner.runApproved]（持宿主签发的 [RootExecutionGrant]）。
     */
    fun verifySafe(script: String, profile: Profile = Profile.STRICT) {
        val assessment = classify(script, profile)
        if (assessment.route == ApprovalRoute.ALLOW) return
        val action = assessment.actions.firstOrNull() ?: "高风险根命令"
        throw RuntimeFailure(
            "ROOT_SECURITY_VIOLATION",
            "根命令被安全护栏拦截或需确认：$action（命中片段: ${assessment.snippet}）",
        )
    }

    /** 逐条列出在当前 [profile] 下需要确认/拦截的动作名，供诊断与测试断言使用（不抛异常）。 */
    fun violations(script: String, profile: Profile = Profile.STRICT): List<String> {
        val assessment = classify(script, profile)
        return if (assessment.route == ApprovalRoute.ALLOW) emptyList() else assessment.actions
    }

    private fun matchRules(normalized: String, rules: List<Rule>): List<Pair<Rule, String>> {
        val hits = ArrayList<Pair<Rule, String>>()
        for (rule in rules) {
            val match = rule.pattern.find(normalized) ?: continue
            hits.add(rule to match.value)
        }
        return hits
    }

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

    private const val MAX_SNIPPET_CHARS = 48
    private const val MAX_REPORTED_ACTIONS = 6
    private val WHITESPACE = Regex("""\s+""")
}
