package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutomationControlPolicy] 的转移规则与 [AutomationControlPreferences] 的读写回退。
 *
 * 用内存替身而不是真 SharedPreferences：这里要验证的「默认关闭、坏值回落、暂停不点亮关闭态」
 * 挂在设备上都得先装应用、改偏好文件才能复现一次。用例的每条断言都对应一个用户可见的后果，
 * 不是对着实现抄一遍。
 */
class AutomationControlTest {

    private class FakeStorage(private val values: MutableMap<String, String> = mutableMapOf()) :
        AutomationControlPreferences.Storage {
        var lastWriteKey: String? = null

        override fun readString(key: String): String? = values[key]

        override fun writeString(key: String, value: String?) {
            lastWriteKey = key
            if (value == null) values.remove(key) else values[key] = value
        }

        fun snapshot(): Map<String, String> = values.toMap()
    }

    @Test
    fun `启动与恢复都落到运行态`() {
        assertEquals(AutomationRuntime.RUNNING, AutomationControlPolicy.next(AutomationRuntime.OFF, AutomationControlAction.START))
        assertEquals(AutomationRuntime.RUNNING, AutomationControlPolicy.next(AutomationRuntime.PAUSED, AutomationControlAction.RESUME))
        // 已经开着时再点一次开启不该变成别的状态。
        assertEquals(AutomationRuntime.RUNNING, AutomationControlPolicy.next(AutomationRuntime.RUNNING, AutomationControlAction.START))
    }

    @Test
    fun `暂停保留已开启的意图但不会把关闭态点亮`() {
        assertEquals(AutomationRuntime.PAUSED, AutomationControlPolicy.next(AutomationRuntime.RUNNING, AutomationControlAction.PAUSE))
        assertEquals(AutomationRuntime.PAUSED, AutomationControlPolicy.next(AutomationRuntime.PAUSED, AutomationControlAction.PAUSE))
        // 关着的时候按暂停仍必须是「关闭」：否则界面上会出现一个用户从未开启过的「已暂停」。
        assertEquals(AutomationRuntime.OFF, AutomationControlPolicy.next(AutomationRuntime.OFF, AutomationControlAction.PAUSE))
    }

    @Test
    fun `紧急停止在任何状态下都回落到关闭`() {
        for (current in AutomationRuntime.values()) {
            assertEquals(
                "紧急停止必须与暂停区分：它要求用户再明确开一次",
                AutomationRuntime.OFF,
                AutomationControlPolicy.next(current, AutomationControlAction.EMERGENCY_STOP),
            )
        }
    }

    @Test
    fun `只有运行态放行`() {
        assertTrue(AutomationControlPolicy.admits(AutomationRuntime.RUNNING))
        assertFalse(AutomationControlPolicy.admits(AutomationRuntime.PAUSED))
        assertFalse(AutomationControlPolicy.admits(AutomationRuntime.OFF))
    }

    @Test
    fun `偏好里的未知取值一律回落到关闭`() {
        // 缺失、空串、大小写不符、拼错的枚举名——全部按「没开」处理（fail-closed）。
        for (value in listOf(null, "", " ", "RUNNING", "Running", "paused ", "on", "true", "1", "关闭")) {
            assertEquals("取值 ${value?.let { "「$it」" } ?: "null"} 必须回落到关闭", AutomationRuntime.OFF, AutomationControlPolicy.fromName(value))
        }
        assertEquals(AutomationRuntime.RUNNING, AutomationControlPolicy.fromName("running"))
        assertEquals(AutomationRuntime.PAUSED, AutomationControlPolicy.fromName("paused"))
    }

    @Test
    fun `名称与解析往返一致`() {
        for (runtime in AutomationRuntime.values()) {
            assertEquals(runtime, AutomationControlPolicy.fromName(AutomationControlPolicy.name(runtime)))
        }
        assertEquals("off", AutomationControlPolicy.name(AutomationRuntime.OFF))
        assertEquals("running", AutomationControlPolicy.name(AutomationRuntime.RUNNING))
        assertEquals("paused", AutomationControlPolicy.name(AutomationRuntime.PAUSED))
    }

    @Test
    fun `桥接动作名认不出来一律拒收而不是当成开启`() {
        assertEquals(AutomationControlAction.START, AutomationControlPolicy.actionFromName("start"))
        assertEquals(AutomationControlAction.PAUSE, AutomationControlPolicy.actionFromName("pause"))
        assertEquals(AutomationControlAction.RESUME, AutomationControlPolicy.actionFromName("resume"))
        assertEquals(AutomationControlAction.EMERGENCY_STOP, AutomationControlPolicy.actionFromName("emergency_stop"))

        // 桥接侧的兜底必须是不认：一个拼错的参数若能落到 START，就等于 AI 能自己打开自动化，
        // 那与「AI 只能关不能开」冲突（docs/AI自动化参数设计.md）。
        for (value in listOf(null, "", " ", "START", "Start", "start ", "resume\t", "on", "true", "1", "紧急停止", "emergency-stop")) {
            assertEquals("取值 ${value?.let { "「$it」" } ?: "null"} 必须被拒收", null, AutomationControlPolicy.actionFromName(value))
        }
    }

    @Test
    fun `动作名是稳定的审计受控码`() {
        assertEquals("start", AutomationControlPolicy.actionName(AutomationControlAction.START))
        assertEquals("pause", AutomationControlPolicy.actionName(AutomationControlAction.PAUSE))
        assertEquals("resume", AutomationControlPolicy.actionName(AutomationControlAction.RESUME))
        assertEquals("emergency_stop", AutomationControlPolicy.actionName(AutomationControlAction.EMERGENCY_STOP))
        // 动作名必须能被 actionFromName 原样读回来：桥接协议与审计码共用同一个名字，不许出现两套写法。
        for (action in AutomationControlAction.values()) {
            assertEquals(action, AutomationControlPolicy.actionFromName(AutomationControlPolicy.actionName(action)))
        }
        // 四个动作名互不重复（重名会让审计分不清是谁按的）。
        val names = AutomationControlAction.values().map { AutomationControlPolicy.actionName(it) }
        assertEquals(names.size, names.toSet().size)
        // 审计码是全大写形式 `AUTOMATION_<ACTION>`：拼错这里就是审计格式变更。
        assertEquals("AUTOMATION_START", AutomationControlPolicy.auditDetail(AutomationControlAction.START))
        assertEquals("AUTOMATION_EMERGENCY_STOP", AutomationControlPolicy.auditDetail(AutomationControlAction.EMERGENCY_STOP))
    }

    @Test
    fun `偏好默认关闭且写入后能原样读回`() {
        val storage = FakeStorage()
        val preferences = AutomationControlPreferences(storage)

        // 默认值是这套开关的核心语义：装完应用、没点过任何按钮时不许自动动别人的界面。
        assertEquals(AutomationRuntime.OFF, preferences.read())
        assertEquals(null, storage.snapshot()[AutomationControlPreferences.KEY_RUNTIME])

        preferences.write(AutomationRuntime.PAUSED)
        assertEquals(AutomationRuntime.PAUSED, preferences.read())
        assertEquals(AutomationRuntime.PAUSED, AutomationControlPreferences(storage).read())
        assertEquals(AutomationControlPreferences.KEY_RUNTIME, storage.lastWriteKey)

        // 关闭时必须把意图写回去（而不是删键）：删键与「关闭」在读取侧等价，但界面要能区分
        // 「从没开过」与「用户主动关掉」时，落盘就得留下这次动作。
        preferences.write(AutomationRuntime.OFF)
        assertEquals(AutomationRuntime.OFF, preferences.read())
        assertEquals("off", storage.snapshot()[AutomationControlPreferences.KEY_RUNTIME])
    }

    @Test
    fun `总开关用独立偏好文件不复用白名单与密码`() {
        assertEquals("accessibility_automation_control", AutomationControlPreferences.FILE_NAME)
        assertEquals("runtime", AutomationControlPreferences.KEY_RUNTIME)
        assertNotEquals(AutomationRuleStore.FILE_NAME, AutomationControlPreferences.FILE_NAME)
        assertNotEquals(AccessibilityPasswordPreferences.FILE_NAME, AutomationControlPreferences.FILE_NAME)
        // 白名单的偏好文件名是私有的（AccessibilityAutomationStore.PREFERENCES），这里钉住字面量，
        // 防止将来有人把总开关塞进同一个文件里、让「清白名单」顺手把开关也清掉。
        assertNotEquals("accessibility_automation", AutomationControlPreferences.FILE_NAME)
    }

    @Test
    fun `按一次用户操作推进状态并落盘`() {
        val storage = FakeStorage()
        val preferences = AutomationControlPreferences(storage)

        // 这里只验证 Preferences 一侧：store 入口要拿 Context，属于设备上的路径（见类注释）。
        preferences.write(AutomationRuntime.RUNNING)
        val paused = AutomationControlPolicy.next(preferences.read(), AutomationControlAction.PAUSE)
        preferences.write(paused)
        assertEquals(AutomationRuntime.PAUSED, AutomationControlPreferences(storage).read())

        val stopped = AutomationControlPolicy.next(preferences.read(), AutomationControlAction.EMERGENCY_STOP)
        preferences.write(stopped)
        assertEquals(AutomationRuntime.OFF, AutomationControlPreferences(storage).read())
        assertFalse(AutomationControlPolicy.admits(AutomationControlPreferences(storage).read()))
    }
}
