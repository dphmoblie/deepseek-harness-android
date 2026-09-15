package io.deepseekharness.mobile.runtime

/** 启动默认值；dsh 中保存的默认权限及会话权限仍按上游规则优先。 */
enum class HarnessPermissionMode(val wireValue: String) {
    WORKSPACE_WRITE("workspace-write"),
    FULL_ACCESS("danger-full-access");

    companion object {
        /** 安全校验点：只接受完整枚举，不转换类型或拼接用户输入到进程参数。 */
        fun parse(value: Any?): HarnessPermissionMode = entries.firstOrNull { it.wireValue == value }
            ?: throw RuntimeFailure("SETTINGS_INVALID", "Harness 启动权限格式无效")
    }
}
