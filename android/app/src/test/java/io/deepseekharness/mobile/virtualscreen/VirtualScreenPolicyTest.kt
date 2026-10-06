package io.deepseekharness.mobile.virtualscreen

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VirtualScreenPolicyTest {
    @Test fun `副屏只能确认自己作用域内已恢复的精确目标包名`() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES
              Display #0 (activities from top to bottom):
                mResumedActivity: ActivityRecord{abc u0 com.example.target/.Main t3}
              Display #4 (activities from top to bottom):
                mResumedActivity: ActivityRecord{def u0 com.example.other/.Main t4}
              ResumedActivity: ActivityRecord{abc u0 com.example.target/.Main t3}
        """.trimIndent()
        assertFalse(VirtualScreenPolicy.targetResumed(dump, 0, "com.example.target"))
        assertFalse(VirtualScreenPolicy.targetResumed(dump, 4, "com.example.target"))
        assertTrue(VirtualScreenPolicy.targetResumed(dump, 4, "com.example.other"))
        assertFalse(VirtualScreenPolicy.targetResumed(dump, 4, "example.other"))
        assertFalse(VirtualScreenPolicy.targetResumed(dump, 40, "com.example.other"))
        assertFalse(VirtualScreenPolicy.targetResumed(dump.replace("mResumedActivity", "mLastPausedActivity"), 4, "com.example.other"))
    }

    @Test fun `拒绝损坏入口和无限像素分配`() {
        assertEquals("com.example/.Main", VirtualScreenPolicy.component("com.example/.Main"))
        for (value in listOf("com.example/.Main;id", "com.example/.Main\n", "com.example/.Main --display 0", "a/b", "com.example/")) {
            assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.component(value) }
        }
        VirtualScreenPolicy.dimensions(726, 1600, 320)
        VirtualScreenPolicy.dimensions(1280, 580, 256)
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.dimensions(1440, 2560, 560) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.dimensions(Int.MAX_VALUE, 1600, 320) }
    }

    @Test fun `全局摘要缩进位于末尾副屏中也不能当作副屏恢复证明`() {
        val dump = """
            Display #0 (activities from top to bottom):
                  topResumedActivity=ActivityRecord{abc u0 com.example.target/.Main t2}
            Display #8 (activities from top to bottom):
                mLastPausedActivity: ActivityRecord{def u0 com.example.target/.Main t3}
              ResumedActivity: ActivityRecord{abc u0 com.example.target/.Main t2}
        """.trimIndent()
        assertFalse(VirtualScreenPolicy.targetResumed(dump, 8, "com.example.target"))
        assertTrue(VirtualScreenPolicy.targetResumed(dump.replace("mLastPausedActivity:", "topResumedActivity="), 8, "com.example.target"))
    }

    @Test fun `输入始终携带独立显示编号且参数不能注入命令`() {
        val tap = JSONObject().put("action", "tap").put("x", 725).put("y", 1599)
        assertEquals(listOf("/system/bin/input", "-d", "4", "tap", "725", "1599"), VirtualScreenPolicy.inputArguments(tap, 4, 726, 1600))
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.inputArguments(tap, 0, 726, 1600) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.inputArguments(tap, -1, 726, 1600) }
        for (x in listOf<Any>(-1, 726, 1.5, "1", "1;id", Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.inputArguments(JSONObject(tap.toString()).put("x", x), 4, 726, 1600) }
        }
        assertEquals(listOf("/system/bin/input", "-d", "4", "keyevent", "4"), VirtualScreenPolicy.inputArguments(JSONObject().put("action", "back"), 4, 726, 1600))
    }

    @Test fun `滑动终点与持续时间同样受边界限制`() {
        val swipe = JSONObject().put("action", "swipe").put("x", 0).put("y", 0).put("endX", 100).put("endY", 100).put("durationMs", 300)
        assertEquals(listOf("/system/bin/input", "-d", "4", "swipe", "0", "0", "100", "100", "300"), VirtualScreenPolicy.inputArguments(swipe, 4, 726, 1600))
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.inputArguments(JSONObject(swipe.toString()).put("durationMs", 3000), 4, 726, 1600) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.inputArguments(JSONObject(swipe.toString()).put("endY", 1600), 4, 726, 1600) }
    }

    @Test fun `副屏动作支持受控长按按键和 ASCII 文本`() {
        assertEquals(
            listOf("/system/bin/input", "-d", "4", "swipe", "10", "20", "10", "20", "600"),
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "long_press").put("x", 10).put("y", 20).put("durationMs", 600), 4, 726, 1600),
        )
        assertEquals(
            listOf("/system/bin/input", "-d", "4", "keyevent", "66"),
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "keyevent").put("key", "ENTER"), 4, 726, 1600),
        )
        assertEquals(
            listOf("/system/bin/input", "-d", "4", "text", "hello%sworld"),
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "text").put("text", "hello world"), 4, 726, 1600),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "text").put("text", "中文"), 4, 726, 1600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "keyevent").put("key", "POWER"), 4, 726, 1600)
        }
    }

    @Test fun `会话标识不可用任意显示编号代替`() {
        VirtualScreenPolicy.session("00000000-1111-2222-3333-444444444444")
        listOf("0", "4", "", "00000000-1111-2222-3333-444444444444\n").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.session(id) }
        }
    }

    @Test fun `执行层分发的副屏动作必须与策略层白名单一致`() {
        // 真机缺陷：策略层已支持 long_press/keyevent/text，执行层的 when 却只认 tap/swipe/back，
        // 这三个动作会走到「不支持的副屏操作」，再被映射成 VIRTUAL_SCREEN_UNAVAILABLE。
        // 执行层现在按 INPUT_ACTIONS 分发，这里保证白名单、策略层解析、插件声明三者不脱节。
        val requests = mapOf(
            "tap" to JSONObject().put("action", "tap").put("x", 1).put("y", 2),
            "swipe" to JSONObject().put("action", "swipe").put("x", 1).put("y", 2).put("endX", 3).put("endY", 4).put("durationMs", 300),
            "long_press" to JSONObject().put("action", "long_press").put("x", 1).put("y", 2).put("durationMs", 600),
            "keyevent" to JSONObject().put("action", "keyevent").put("key", "ENTER"),
            "text" to JSONObject().put("action", "text").put("text", "ok"),
            "back" to JSONObject().put("action", "back"),
        )
        assertEquals(requests.keys, VirtualScreenPolicy.INPUT_ACTIONS)
        for ((action, request) in requests) {
            assertEquals(
                "动作 $action 必须能生成 /system/bin/input 命令",
                listOf("/system/bin/input", "-d", "4"),
                VirtualScreenPolicy.inputArguments(request, 4, 726, 1600).take(3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.inputArguments(JSONObject().put("action", "pinch").put("x", 1).put("y", 2), 4, 726, 1600)
        }
    }

    @Test fun `预览模式到采集间隔的映射覆盖三个允许模式并拒绝未知模式`() {
        // 三个模式各自的毫秒数是调用端与宿主共用的稳定契约，取值被改动会让「模式」与「实测帧率」脱节。
        assertEquals(180, VirtualScreenPolicy.frameInterval("limited"))
        assertEquals(66, VirtualScreenPolicy.frameInterval("15fps"))
        assertEquals(33, VirtualScreenPolicy.frameInterval("30fps"))
        assertEquals(16, VirtualScreenPolicy.frameInterval("60fps"))
        assertEquals(8, VirtualScreenPolicy.frameInterval("120fps"))
        assertEquals(11, VirtualScreenPolicy.frameInterval("90fps"))
        assertEquals(6, VirtualScreenPolicy.frameInterval("144fps"))
        assertEquals(6, VirtualScreenPolicy.frameInterval("165fps"))
        assertEquals(5, VirtualScreenPolicy.frameInterval("185fps"))
        assertEquals(4, VirtualScreenPolicy.frameInterval("240fps"))
        // 映射表本身就是工具面与设置页的选项来源，键集合必须与上面列出的十档完全一致。
        assertEquals(
            listOf("limited", "15fps", "30fps", "60fps", "90fps", "120fps", "144fps", "165fps", "185fps", "240fps"),
            VirtualScreenPolicy.FRAME_MODES.keys.toList(),
        )
        // 未知模式必须报出固定错误文案，不能回落到默认间隔悄悄生效。
        for (mode in listOf("", "realtime", "24fps", "120FPS", "Limited", "limited ")) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.frameInterval(mode) }
            assertEquals("副屏预览模式不在允许列表", error.message)
        }
    }

    @Test fun `预览状态标签把 limited 映射为限帧标签其余映射为实时标签`() {
        assertEquals("limited-fps", VirtualScreenPolicy.frameModeLabel("limited"))
        assertEquals("realtime-15fps", VirtualScreenPolicy.frameModeLabel("15fps"))
        assertEquals("realtime-30fps", VirtualScreenPolicy.frameModeLabel("30fps"))
        assertEquals("realtime-60fps", VirtualScreenPolicy.frameModeLabel("60fps"))
        assertEquals("realtime-120fps", VirtualScreenPolicy.frameModeLabel("120fps"))
        assertEquals("realtime-185fps", VirtualScreenPolicy.frameModeLabel("185fps"))
    }

    @Test fun `目标应用入口必须来自 component 字段且与启动校验同规则`() {
        assertEquals("com.example/.Main", VirtualScreenPolicy.targetRequest(JSONObject().put("component", "com.example/.Main")))
        assertEquals("com.example.target/.MainActivity", VirtualScreenPolicy.targetRequest(JSONObject().put("component", "com.example.target/.MainActivity")))
        // 非法入口沿用 component(...) 的规则报错，不接受附加参数或换行。
        for (value in listOf("a/b", "com.example/", "com.example/.Main;id", "com.example/.Main --display 0", "com.example/.Main\n", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.targetRequest(JSONObject().put("component", value))
            }
        }
        // 缺字段属于 JSON 解析问题；字段名由策略层常量固定，调用端不能改名。
        assertEquals("component", VirtualScreenPolicy.TARGET_FIELD)
        assertThrows(org.json.JSONException::class.java) { VirtualScreenPolicy.targetRequest(JSONObject()) }
    }

    @Test fun `只有可打印 ASCII 走设备 Shell 其余文本改走无障碍注入`() {
        // 纯 ASCII（含空格与常见符号）保持既有行为：仍由 /system/bin/input text 输入。
        for (value in listOf("a", "hello world", "dsh-sandbox-ok", "!@#%^&*()_+-=[]{}|;':\",./<>?", "~")) {
            assertFalse("「$value」不应改走无障碍", VirtualScreenPolicy.needsAccessibilityText(value))
        }
        // 中文、emoji、全角符号、换行、制表符与 DEL 都必须分流到无障碍服务。
        for (value in listOf("你好", "微信", "中文😀", "，。！", "a\nb", "a\tb", "a\u007fb", "é", "日本語")) {
            assertTrue("「$value」应改走无障碍", VirtualScreenPolicy.needsAccessibilityText(value))
        }
        // 空串没有可注入的内容，不属于无障碍场景（长度校验由调用端负责）。
        assertFalse(VirtualScreenPolicy.needsAccessibilityText(""))
    }

    @Test fun `状态行标签能反解回档位且未知标签回落到省电`() {
        // 副屏页与副屏悬浮窗拿到的都是状态行标签（frameModeLabel 的输出），必须能反解回档位，
        // 否则悬浮窗上的档位高亮会永远停在省电，用户看不出当前是哪一档。
        for (mode in VirtualScreenPolicy.FRAME_MODES.keys) {
            assertEquals(mode, VirtualScreenPolicy.frameModeOf(VirtualScreenPolicy.frameModeLabel(mode)))
        }
        // 直接传档位名也要能用（调用端可能已经反解过一次）。
        for (mode in VirtualScreenPolicy.FRAME_MODES.keys) {
            assertEquals(mode, VirtualScreenPolicy.frameModeOf(mode))
        }
        // 反解必须是全函数：状态行可能来自旧版本或异常会话，不能因为一个陌生标签就让预览线程崩掉。
        for (label in listOf("", "realtime", "realtime-24fps", "limited-fps ", "Realtime-15fps", "120FPS")) {
            assertEquals("「$label」应回落到省电档", "limited", VirtualScreenPolicy.frameModeOf(label))
        }
    }

    @Test fun `预览拉取间隔不低于下限且必须先用反解归一档位`() {
        // 拉取间隔 = maxOf(下限, 档位间隔)：十档里只有最快档会撞到下限，其余都按档位自身走。
        // 这条链路是「截图 → 解码 PNG → 贴图」，比采集贵得多，不能让它跟着最快档无限加速。
        assertEquals(180, VirtualScreenPolicy.previewPullInterval("limited"))
        assertEquals(66, VirtualScreenPolicy.previewPullInterval("15fps"))
        assertEquals(33, VirtualScreenPolicy.previewPullInterval("30fps"))
        assertEquals(16, VirtualScreenPolicy.previewPullInterval("60fps"))
        assertEquals(11, VirtualScreenPolicy.previewPullInterval("90fps"))
        assertEquals(8, VirtualScreenPolicy.previewPullInterval("120fps"))
        assertEquals(6, VirtualScreenPolicy.previewPullInterval("144fps"))
        assertEquals(6, VirtualScreenPolicy.previewPullInterval("165fps"))
        assertEquals(5, VirtualScreenPolicy.previewPullInterval("185fps"))
        assertEquals(4, VirtualScreenPolicy.previewPullInterval("240fps"))
        // 下限不能高于最快档位的间隔：240fps 档是 4ms，下限若停在 5 就会把最快那一档悄悄夹慢，
        // 采集节奏与预览拉取节奏对不上（档位是给用户看的契约，不能被下限偷偷改掉）。
        assertEquals(4, VirtualScreenPolicy.PREVIEW_PULL_FLOOR_MILLIS)
        // 未知模式沿用 frameInterval 的严格契约（不悄悄回落），调用端要先用 frameModeOf 归一。
        for (mode in listOf("", "realtime-15fps", "24fps")) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.previewPullInterval(mode) }
            assertEquals("副屏预览模式不在允许列表", error.message)
        }
    }
}
