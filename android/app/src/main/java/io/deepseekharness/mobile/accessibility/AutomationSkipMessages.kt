package io.deepseekharness.mobile.accessibility

/**
 * 受控原因码 → 用户可读中文说明。
 *
 * 为什么单独一张表：码要落审计（`AuditPolicy.detailPattern` 只接受 `^[A-Z][A-Z0-9_]{0,63}$`），
 * 所以码里**不能**带中文；中文说明放在这里，界面与运行记录都从这里取，不再各自拼字符串。
 * 一处新增码、另一处忘记加文案会让
 * `AutomationSkipMessagesTest`（遍历 [AutomationGateCodes] 与 [AutomationSkipCodes] 的常量）
 * 直接变红，而不是在界面上显示一个英文码。
 */
internal object AutomationSkipMessages {
    /** 未登记的码（理论上到不了这里，见类注释）。 */
    const val UNKNOWN = "未知原因，已跳过"

    /** 单条文案的长度上限：界面按卡片一行展示，过长会被截断成两种不同的意思。 */
    const val MAX_CHARS = 40

    private val MESSAGES: Map<String, String> = mapOf(
        // 闸门（策略层）：拦的是「场景」，不是「规则做错了」。
        AutomationGateCodes.ALLOWED to "已放行",
        AutomationGateCodes.RULE_DISABLED to "规则已停用",
        AutomationGateCodes.RULE_AUTO_DISABLED to "规则连续失败，已被自动停用",
        AutomationGateCodes.PACKAGE_NOT_ALLOWED to "目标应用不在自动化白名单里",
        AutomationGateCodes.SELF_PACKAGE_BLOCKED to "不允许自动化本应用自身",
        AutomationGateCodes.DEVICE_LOCKED to "设备已锁屏或熄屏，暂不动作",
        AutomationGateCodes.SENSITIVE_WINDOW to "检测到密码、验证码、支付或权限窗口，已拒绝",
        // 闸门（节流与指纹）。
        AutomationGateCodes.EVENT_THROTTLED to "界面变化过于频繁，本次评估已节流",
        AutomationGateCodes.ACTION_INTERVAL_THROTTLED to "距上次动作太近，本次动作已节流",
        AutomationGateCodes.COOLDOWN_ACTIVE to "规则冷却中，暂不动作",
        AutomationGateCodes.ACTION_MAX_REACHED to "本界面已用满规则允许的动作次数",
        AutomationGateCodes.DUPLICATE_FINGERPRINT to "界面与上次已执行的相同，未重复动作",
        // 执行侧。
        AutomationGateCodes.DRY_RUN to "试运行：只评估，未执行动作",
        AutomationGateCodes.BACKEND_UNAVAILABLE to "无障碍服务当前不可用，无法解析节点",
        AutomationGateCodes.NODE_UNRESOLVED to "未找到规则指定的节点",
        AutomationGateCodes.NODE_STALE to "界面已变化，原节点已失效",
        AutomationGateCodes.ACTION_UNSUPPORTED to "目标节点不支持该动作",
        AutomationGateCodes.ACTION_PERFORMED to "动作已执行",
        AutomationGateCodes.CLICK_ANCESTOR_FALLBACK to "节点不可点击，已改为点击可点击的祖先节点",
        AutomationGateCodes.CLICK_FAILED to "点击未成功",
        AutomationGateCodes.LONG_CLICK_PERFORMED to "长按已执行",
        AutomationGateCodes.LONG_CLICK_FAILED to "长按未成功",
        AutomationGateCodes.GESTURE_PERFORMED to "手势已派发",
        AutomationGateCodes.SWIPE_PERFORMED to "滑动已执行",
        AutomationGateCodes.SWIPE_FAILED to "滑动未成功",
        AutomationGateCodes.BACK_PERFORMED to "已返回上一级",
        AutomationGateCodes.BACK_FAILED to "返回操作未成功",
        AutomationGateCodes.KEY_PERFORMED to "按键已发送",
        AutomationGateCodes.KEY_FAILED to "按键未发送成功",
        AutomationGateCodes.LAUNCH_PERFORMED to "已启动目标应用",
        AutomationGateCodes.LAUNCH_FAILED to "启动目标应用失败",
        AutomationGateCodes.WAIT_PERFORMED to "已等待指定时长",
        // 匹配侧（比闸门更早发生，原因都在「当前界面」上）。
        AutomationSkipCodes.MATCHED to "已匹配，本次未产生动作",
        AutomationSkipCodes.RULE_DISABLED to "规则已停用",
        AutomationSkipCodes.PACKAGE_MISMATCH to "当前前台应用与规则目标不一致",
        AutomationSkipCodes.ACTIVITY_UNKNOWN to "无法识别当前界面名称",
        AutomationSkipCodes.ACTIVITY_NOT_ALLOWED to "当前界面不在规则允许的界面列表里",
        AutomationSkipCodes.ACTIVITY_EXCLUDED to "当前界面在规则的排除列表里",
        AutomationSkipCodes.EMPTY_TREE to "界面节点树为空",
        AutomationSkipCodes.MATCH_DELAY_PENDING to "规则配置了匹配延迟，尚未到期",
        AutomationSkipCodes.NODE_NOT_FOUND to "未找到规则指定的节点",
        AutomationSkipCodes.ACTION_MAX_REACHED to "本界面已用满规则允许的动作次数",
        AutomationSkipCodes.COOLDOWN_ACTIVE to "规则冷却中，暂不动作",
        AutomationSkipCodes.CLICK_CENTER_FALLBACK to "节点不可点击，已降级为坐标点击",
    )

    /** 未登记的码返回 [UNKNOWN]，不抛异常：运行记录少一句说明，也不能因此中断自动化链路。 */
    fun message(code: String): String = MESSAGES[code] ?: UNKNOWN

    /**
     * 只给**已登记**的码文案，未登记回 null。
     *
     * 与 [message] 的分工：拦截原因卡片用的是「为什么跳过」，回退成 [UNKNOWN] 是对的；
     * 运行记录里的码既有成功也有失败（`AUTOMATION_ACTION_PERFORMED` 这类），
     * 认不出来时不能显示「未知原因，已跳过」——那会把一次成功的点击说成跳过，界面应改显示原始码。
     */
    fun messageOrNull(code: String): String? = MESSAGES[code]

    /** 已登记文案的码集合，供用例核对「码集合与文案表完全一致」。 */
    fun codes(): Set<String> = MESSAGES.keys
}
