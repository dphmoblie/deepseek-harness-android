package io.deepseekharness.mobile.shizuku

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject

/**
 * AI 执行或修改高危文件与高危命令的细分权限分类（17 项）。
 *
 * 每一项对应 [RootCommandGuard] 中的一组硬编码静态拦截规则；
 * 默认全部关闭（false），并受总开关 [HighRiskPermissions.enabled]（默认 false）统管。
 */
enum class HighRiskCategory(
    val wireValue: String,
    val title: String,
    val tier: RootCommandGuard.RiskTier,
) {
    // ---- 一、高危文件与存储修改（6 项） ----
    CORE_PARTITION_WRITE(
        "core_partition_write",
        "核心系统分区写入与重挂载",
        RootCommandGuard.RiskTier.HIGH,
    ),
    SYSTEM_DATA_DESTRUCTION(
        "system_data_destruction",
        "系统核心数据目录删除与覆写",
        RootCommandGuard.RiskTier.CRITICAL,
    ),
    SHARED_STORAGE_DELETE(
        "shared_storage_delete",
        "内置共享存储（SD 卡）批量删除",
        RootCommandGuard.RiskTier.CRITICAL,
    ),
    APP_PRIVATE_DATA_MODIFY(
        "app_private_data_modify",
        "应用私有数据目录修改与删除",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    SYSTEM_PERMISSIONS_OWNER(
        "system_permissions_owner",
        "系统目录权限、属主与安全上下文修改",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    DEV_NODE_WRITE(
        "dev_node_write",
        "/dev 设备节点直接写入",
        RootCommandGuard.RiskTier.ELEVATED,
    ),

    // ---- 二、高危系统与运维命令（7 项） ----
    DYNAMIC_SHELL_EVAL(
        "dynamic_shell_eval",
        "动态 Shell 展开与二次解释执行",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    FILESYSTEM_MOUNT(
        "filesystem_mount",
        "文件系统挂载与卸载",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    RAW_DEVICE_IMAGE_TOOL(
        "raw_device_image_tool",
        "镜像直写与回环/映射设备工具",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    KERNEL_MODULE_CONTROL(
        "kernel_module_control",
        "内核模块加载与卸载",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    KERNEL_SYSFS_PROC_WRITE(
        "kernel_sysfs_proc_write",
        "内核运行参数与 sysfs 写入",
        RootCommandGuard.RiskTier.ELEVATED,
    ),
    SYSTEM_PROPERTIES_WRITE(
        "system_properties_write",
        "Android 系统属性与属性库修改",
        RootCommandGuard.RiskTier.HIGH,
    ),
    PACKAGE_INSTALL_REMOVE(
        "package_install_remove",
        "应用安装、卸载、清空与组件禁用",
        RootCommandGuard.RiskTier.ELEVATED,
    ),

    // ---- 三、极危整机、安全与底层硬件（4 项） ----
    SYSTEM_SERVICE_PRIVILEGE(
        "system_service_privilege",
        "关键系统服务控制与嵌套特权 Shell",
        RootCommandGuard.RiskTier.HIGH,
    ),
    SELINUX_SECURITY_POLICY(
        "selinux_security_policy",
        "SELinux 强制模式与安全策略修改",
        RootCommandGuard.RiskTier.HIGH,
    ),
    DEVICE_POWER_CONTROL(
        "device_power_control",
        "设备重启、关机与电源触发",
        RootCommandGuard.RiskTier.HIGH,
    ),
    BLOCK_FLASH_MEMORY_RAW(
        "block_flash_memory_raw",
        "物理块设备、分区格式化、刷机与物理内存访问",
        RootCommandGuard.RiskTier.CRITICAL,
    ),
    ;

    companion object {
        fun fromWire(value: String?): HighRiskCategory? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 启动器「AI 高危文件与命令权限」快照（1 个总开关 + 17 个细分开关）。
 *
 * 规则：
 *  - [enabled] 为 false 时，所有 17 类高危文件修改或高危命令一律由宿主硬编码规则直接拦截；
 *  - [enabled] 为 true 时，仅放行 [categories] 中显式为 true 的细分项，其余仍由硬编码规则直接拦截。
 */
data class HighRiskPermissions(
    val enabled: Boolean = false,
    val categories: Map<HighRiskCategory, Boolean> = DEFAULT_CATEGORIES,
) {
    fun isCategoryEnabled(category: HighRiskCategory): Boolean =
        categories[category] == true

    fun isAllowed(category: HighRiskCategory): Boolean =
        enabled && isCategoryEnabled(category)

    fun toJson(): JSONObject {
        val cats = JSONObject()
        for (category in HighRiskCategory.entries) {
            cats.put(category.wireValue, categories[category] == true)
        }
        return JSONObject()
            .put("enabled", enabled)
            .put("categories", cats)
    }

    companion object {
        private val DEFAULT_CATEGORIES: Map<HighRiskCategory, Boolean> =
            HighRiskCategory.entries.associateWith { false }

        val ALL_DISABLED = HighRiskPermissions(
            enabled = false,
            categories = DEFAULT_CATEGORIES,
        )

        val ALL_ENABLED = HighRiskPermissions(
            enabled = true,
            categories = HighRiskCategory.entries.associateWith { true },
        )
    }
}

/**
 * 「AI 高危文件与命令权限」的持久化存储与入参解析。
 */
object HighRiskPermissionStore {
    private const val PREFERENCES = "high_risk_permissions"
    private const val KEY_MASTER_ENABLED = "master_enabled"
    private const val KEY_CATEGORY_PREFIX = "cat_"

    fun status(context: Context?): HighRiskPermissions {
        if (context == null) return HighRiskPermissions.ALL_DISABLED
        val prefs = preferences(context)
        val enabled = prefs.getBoolean(KEY_MASTER_ENABLED, false)
        val categories = HighRiskCategory.entries.associateWith { category ->
            prefs.getBoolean(KEY_CATEGORY_PREFIX + category.wireValue, false)
        }
        return HighRiskPermissions(enabled = enabled, categories = categories)
    }

    fun update(
        context: Context,
        enabled: Boolean? = null,
        categoryUpdates: Map<HighRiskCategory, Boolean> = emptyMap(),
    ): HighRiskPermissions {
        val prefs = preferences(context)
        val editor = prefs.edit()
        if (enabled != null) {
            editor.putBoolean(KEY_MASTER_ENABLED, enabled)
        }
        for ((category, allowed) in categoryUpdates) {
            editor.putBoolean(KEY_CATEGORY_PREFIX + category.wireValue, allowed)
        }
        val committed = editor.commit()
        if (!committed) {
            throw RuntimeFailure("HIGH_RISK_PERMISSION_SAVE_FAILED", "无法保存高危文件与命令权限设置")
        }
        return status(context)
    }

    /**
     * 解析并校验来自桥接层的更新参数：拒绝未知分类名或非布尔取值，避免静默忽略错误配置。
     */
    fun parseUpdate(
        enabledRaw: Any?,
        categoriesObj: JSONObject?,
    ): Pair<Boolean?, Map<HighRiskCategory, Boolean>> {
        val enabled = when (enabledRaw) {
            null, JSONObject.NULL -> null
            is Boolean -> enabledRaw
            else -> throw RuntimeFailure("HIGH_RISK_PERMISSION_INVALID", "高危权限总开关格式无效")
        }
        val updates = LinkedHashMap<HighRiskCategory, Boolean>()
        if (categoriesObj != null) {
            val keys = categoriesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val category = HighRiskCategory.fromWire(key)
                    ?: throw RuntimeFailure("HIGH_RISK_PERMISSION_INVALID", "未知的高危权限细分项：$key")
                val rawVal = categoriesObj.opt(key)
                if (rawVal !is Boolean) {
                    throw RuntimeFailure("HIGH_RISK_PERMISSION_INVALID", "高危权限细分项 $key 的取值必须是布尔值")
                }
                updates[category] = rawVal
            }
        }
        if (enabled == null && updates.isEmpty()) {
            throw RuntimeFailure("HIGH_RISK_PERMISSION_INVALID", "高危权限更新参数不能为空")
        }
        return enabled to updates
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
