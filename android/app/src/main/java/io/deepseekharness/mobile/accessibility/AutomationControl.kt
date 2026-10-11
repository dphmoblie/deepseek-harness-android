package io.deepseekharness.mobile.accessibility

import android.content.Context

/**
 * 「允许无障碍服务按规则自动动作」这件事的用户级状态。
 *
 * 三态刻意分开，因为**服务开着不等于自动化在跑**：
 * - [OFF]：允许自动化这件事被关掉。它既是默认值，也是紧急停止后的落点；
 * - [RUNNING]：放行按规则的自动动作；
 * - [PAUSED]：暂时不放行，但保留用户「已经开过」的意图，恢复时不必再确认一遍。
 *
 * 这套状态与 [AccessibilityAutomationStore] 的白名单、与系统里的无障碍开关是**三件不同的事**，
 * 界面必须分开显示（白名单 = 允许对哪些应用生效；系统开关 = 服务本身连没连上）。
 */
internal enum class AutomationRuntime { OFF, RUNNING, PAUSED }

/** 用户对自动化总开关能做的操作。 */
internal enum class AutomationControlAction { START, PAUSE, RESUME, EMERGENCY_STOP }

/**
 * 总开关的转移规则（纯逻辑，可 JVM 单测）。
 *
 * 两条刻意的选择：
 * 1. **在 [AutomationRuntime.OFF] 上按暂停仍是 [AutomationRuntime.OFF]**——暂停不能把「已关闭」
 *    悄悄升级成「已开启但暂停」，那会让用户以为自己关掉的东西还活着；
 * 2. 紧急停止在任何状态下都落到 [AutomationRuntime.OFF]，且**不是**暂停：它要的是「停到用户
 *    再明确打开为止」，恢复需要一次显式的开启动作。
 */
internal object AutomationControlPolicy {
    fun next(current: AutomationRuntime, action: AutomationControlAction): AutomationRuntime = when (action) {
        AutomationControlAction.START, AutomationControlAction.RESUME -> AutomationRuntime.RUNNING
        AutomationControlAction.PAUSE ->
            if (current == AutomationRuntime.OFF) AutomationRuntime.OFF else AutomationRuntime.PAUSED
        AutomationControlAction.EMERGENCY_STOP -> AutomationRuntime.OFF
    }

    /** 只有 [AutomationRuntime.RUNNING] 放行；暂停与关闭都不建节点树、不跑评估。 */
    fun admits(runtime: AutomationRuntime): Boolean = runtime == AutomationRuntime.RUNNING

    /** 偏好里的字符串 → 状态。**未知、缺失、大小写不符一律回 [AutomationRuntime.OFF]**（fail-closed）。 */
    fun fromName(value: String?): AutomationRuntime = when (value) {
        NAME_RUNNING -> AutomationRuntime.RUNNING
        NAME_PAUSED -> AutomationRuntime.PAUSED
        else -> AutomationRuntime.OFF
    }

    fun name(runtime: AutomationRuntime): String = when (runtime) {
        AutomationRuntime.RUNNING -> NAME_RUNNING
        AutomationRuntime.PAUSED -> NAME_PAUSED
        AutomationRuntime.OFF -> NAME_OFF
    }

    /**
     * 桥接传来的动作名 → 动作。
     *
     * **未知、缺失、大小写不符一律回 null**：调用方必须拒收。这里绝不能有「认不出来就当开始」的兜底，
     * 否则一个拼错的参数就能把自动化打开。
     */
    fun actionFromName(value: String?): AutomationControlAction? = when (value) {
        ACTION_START -> AutomationControlAction.START
        ACTION_PAUSE -> AutomationControlAction.PAUSE
        ACTION_RESUME -> AutomationControlAction.RESUME
        ACTION_EMERGENCY_STOP -> AutomationControlAction.EMERGENCY_STOP
        else -> null
    }

    /**
     * 动作的稳定名字 = **桥接侧用的那一个**（`start` / `pause` / `resume` / `emergency_stop`）。
     *
     * 这里刻意回小写常量而不是 `action.name`：`action.name` 是 `START` 这种枚举名，与
     * [actionFromName] 收的取值不是同一套写法，回读一次就解析不回来（`actionFromName(actionName(a))` 会变 null），
     * 审计码与桥接协议也会各写一套大小写。要审计码请用 [auditDetail]。
     */
    fun actionName(action: AutomationControlAction): String = when (action) {
        AutomationControlAction.START -> ACTION_START
        AutomationControlAction.PAUSE -> ACTION_PAUSE
        AutomationControlAction.RESUME -> ACTION_RESUME
        AutomationControlAction.EMERGENCY_STOP -> ACTION_EMERGENCY_STOP
    }

    /** 审计 detail 用的受控码（`AUTOMATION_START` 这种全大写形式，与既有审计码同风格）。 */
    fun auditDetail(action: AutomationControlAction): String = "AUTOMATION_" + actionName(action).uppercase()

    const val NAME_OFF = "off"
    const val NAME_RUNNING = "running"
    const val NAME_PAUSED = "paused"

    const val ACTION_START = "start"
    const val ACTION_PAUSE = "pause"
    const val ACTION_RESUME = "resume"
    const val ACTION_EMERGENCY_STOP = "emergency_stop"
}

/**
 * 总开关的存储（独立偏好文件，**不复用**白名单与密码的文件）。
 *
 * 存储抽象可注入（生产实现包 SharedPreferences，JVM 单测用内存替身），与
 * [AccessibilityPasswordPreferences] 同一个模式：判定逻辑里不出现 `getSharedPreferences`，
 * 否则「默认关闭、开了之后读回、坏数据回退」这条链条只能挂到设备上手工点。
 */
internal class AutomationControlPreferences(private val storage: Storage) {
    /** 存储抽象：生产实现包 SharedPreferences，测试用内存替身。 */
    interface Storage {
        fun readString(key: String): String?
        fun writeString(key: String, value: String?)
    }

    /** 缺失、空串、未知取值都回 [AutomationRuntime.OFF]。 */
    fun read(): AutomationRuntime = AutomationControlPolicy.fromName(storage.readString(KEY_RUNTIME))

    fun write(runtime: AutomationRuntime) {
        storage.writeString(KEY_RUNTIME, AutomationControlPolicy.name(runtime))
    }

    companion object {
        const val FILE_NAME = "accessibility_automation_control"
        const val KEY_RUNTIME = "runtime"

        /** 生产实现：独立偏好文件，仅本应用可读（只存一个枚举字符串）。 */
        fun from(context: Context): AutomationControlPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return AutomationControlPreferences(object : Storage {
                override fun readString(key: String): String? =
                    if (preferences.contains(key)) preferences.getString(key, null) else null

                override fun writeString(key: String, value: String?) {
                    val editor = preferences.edit()
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                    editor.apply()
                }
            })
        }
    }
}

/** 服务与桥接共用的入口：读当前状态、按一次用户操作推进状态。 */
internal object AutomationControlStore {
    fun runtime(context: Context): AutomationRuntime = AutomationControlPreferences.from(context).read()

    /**
     * 应用一次用户操作并落盘，返回**落盘后**的状态。
     *
     * 紧急停止与暂停都只改这里，**不会**去调 `disableSelf()`：关掉无障碍服务会连带停掉副屏节点树
     * 与悬浮球菜单项，那是另一件事（见 `OverlayBallService`），界面必须分开表达。
     */
    fun apply(context: Context, action: AutomationControlAction): AutomationRuntime {
        val preferences = AutomationControlPreferences.from(context)
        val next = AutomationControlPolicy.next(preferences.read(), action)
        preferences.write(next)
        return next
    }
}
