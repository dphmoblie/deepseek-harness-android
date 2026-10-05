package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.virtualscreen.VirtualScreenPolicy.GesturePoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AI 手势动作（`action = "gesture"`）的纯函数契约：点数与坐标必须落在副屏范围内、
 * 时长按边界夹取、插值路径逐帧落在副屏内且首尾与请求一致。
 *
 * 这些规则都在 [VirtualScreenPolicy] 里，不依赖设备即可单测；真机上的实际触感与
 * 逐事件直传是否被 ROM 拦掉，属于待真机验证的部分。
 */
class VirtualScreenGestureTest {
    private val width = 720
    private val height = 1280

    private fun points(vararg pairs: Pair<Int, Int>): JSONArray = JSONArray().also { array ->
        pairs.forEach { array.put(JSONObject().put("x", it.first).put("y", it.second)) }
    }

    private fun request(vararg pairs: Pair<Int, Int>): JSONObject = JSONObject().put("points", points(*pairs))

    @Test fun `点数必须落在 2 到 64 之间`() {
        assertEquals(2, VirtualScreenPolicy.gestureRequest(request(0 to 0, 10 to 10), width, height).size)
        val full = (0 until 64).map { it to it }.toTypedArray()
        assertEquals(64, VirtualScreenPolicy.gestureRequest(request(*full), width, height).size)
        // 空数组、单点、超出上限都拒绝：点数不对时不能猜一个「差不多」的手势代跑。
        for (count in listOf(0, 1, 65)) {
            val array = (0 until count).map { it to it }.toTypedArray()
            val error = assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.gestureRequest(request(*array), width, height)
            }
            assertTrue(error.message!!, error.message!!.contains("手势点数必须为 2～64 个"))
        }
    }

    @Test fun `坐标必须落在副屏范围内且不夹取`() {
        val inside = VirtualScreenPolicy.gestureRequest(request(0 to 0, width - 1 to height - 1), width, height)
        assertEquals(GesturePoint(width - 1, height - 1), inside.last())
        // 越界一律报错而不是夹到边界：把「点到了别处」当成成功返回比报错危险得多。
        for (pair in listOf(width to 0, -1 to 0, 0 to height, 0 to -1)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.gestureRequest(request(0 to 0, pair), width, height)
            }
            assertTrue(error.message!!, error.message!!.contains("坐标超出副屏范围"))
        }
    }

    @Test fun `拒绝非数组 非对象元素与非整数坐标`() {
        val missing = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.gestureRequest(JSONObject(), width, height)
        }
        assertTrue(missing.message!!, missing.message!!.contains("手势必须提供 points 数组"))
        val notArray = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.gestureRequest(JSONObject().put("points", 5), width, height)
        }
        assertTrue(notArray.message!!, notArray.message!!.contains("手势必须提供 points 数组"))
        val notObject = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.gestureRequest(JSONObject().put("points", JSONArray().put(1).put(2)), width, height)
        }
        assertTrue(notObject.message!!, notObject.message!!.contains("手势坐标必须是 {x,y} 对象"))
        // 字符串 "10" 与小数 10.5 一律拒绝，不做截断。
        for (value in listOf<Any>(1.5, "10")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.gestureRequest(
                    JSONObject().put(
                        "points",
                        JSONArray()
                            .put(JSONObject().put("x", value).put("y", 0))
                            .put(JSONObject().put("x", 1).put("y", 1)),
                    ),
                    width,
                    height,
                )
            }
            assertTrue(error.message!!, error.message!!.contains("手势坐标必须为整数"))
        }
    }

    @Test fun `时长缺省为 300 毫秒并按边界夹取`() {
        assertEquals(300, VirtualScreenPolicy.gestureDuration(JSONObject()))
        assertEquals(300, VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", JSONObject.NULL)))
        assertEquals(50, VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", 10)))
        assertEquals(5000, VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", 9000)))
        assertEquals(800, VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", 800)))
        assertEquals(800, VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", 800L)))
        // 类型错误必须拒绝：静默取整会让 AI 以为自己给的参数被原样接受。
        for (value in listOf<Any>(300.0, "300")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.gestureDuration(JSONObject().put("durationMs", value))
            }
            assertTrue(error.message!!, error.message!!.contains("手势时长必须为整数毫秒"))
        }
    }

    @Test fun `插值路径逐帧落在副屏内且首尾与请求一致`() {
        // 320 / 16 = 20 步：起点 + 20 个采样点。
        val path = VirtualScreenPolicy.gesturePath(listOf(GesturePoint(0, 0), GesturePoint(700, 1200)), 320)
        assertEquals(21, path.size)
        assertEquals(GesturePoint(0, 0), path.first())
        assertEquals(GesturePoint(700, 1200), path.last())
        assertTrue(path.all { it.x in 0 until width && it.y in 0 until height })
        // 折线的两段都要被采样到，不能只沿首尾直线走。
        val bent = VirtualScreenPolicy.gesturePath(
            listOf(GesturePoint(0, 0), GesturePoint(100, 0), GesturePoint(100, 100)),
            320,
        )
        assertEquals(21, bent.size)
        assertTrue(bent.any { it.x == 100 && it.y > 0 })
        assertTrue(bent.any { it.y == 0 && it.x > 0 })
        assertEquals(GesturePoint(100, 100), bent.last())
    }

    @Test fun `起点终点重合退化为原地一次按下与抬起`() {
        val path = VirtualScreenPolicy.gesturePath(listOf(GesturePoint(120, 240), GesturePoint(120, 240)), 300)
        assertEquals(listOf(GesturePoint(120, 240)), path)
    }

    @Test fun `路径点数与时长节拍一致`() {
        // 50 毫秒 = 3 步（3 个 16 毫秒的移动事件），5000 毫秒 = 312 步。
        assertEquals(4, VirtualScreenPolicy.gesturePath(listOf(GesturePoint(0, 0), GesturePoint(300, 0)), 50).size)
        val longest = VirtualScreenPolicy.gesturePath(listOf(GesturePoint(0, 0), GesturePoint(300, 0)), 5000)
        assertEquals(313, longest.size)
        assertTrue(longest.all { it.x in 0..300 && it.y == 0 })
    }

    @Test fun `注入失败的码与副屏不可用区分开`() {
        // 约定：注入没落地是这一笔注入的问题，副屏会话本身正常，不能复用 VIRTUAL_SCREEN_UNAVAILABLE。
        assertEquals("VIRTUAL_SCREEN_INJECTION_FAILED", VirtualScreenPolicy.INJECTION_FAILED_CODE)
        assertTrue(VirtualScreenPolicy.INJECTION_FAILED_CODE != VirtualScreenPolicy.errorCode(IllegalStateException("x")))
    }
}
