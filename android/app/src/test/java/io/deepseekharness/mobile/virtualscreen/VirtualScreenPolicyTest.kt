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

    @Test fun `会话标识不可用任意显示编号代替`() {
        VirtualScreenPolicy.session("00000000-1111-2222-3333-444444444444")
        listOf("0", "4", "", "00000000-1111-2222-3333-444444444444\n").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.session(id) }
        }
    }
}
