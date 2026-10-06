package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.CANCEL
import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.Cursor
import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.DOWN
import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.MOVE
import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.Step
import io.deepseekharness.mobile.virtualscreen.VirtualScreenMotionPlan.UP
import io.deepseekharness.mobile.virtualscreen.VirtualScreenPolicy.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离散触摸通道的事件编排契约（问题 B 的纯逻辑部分）。
 *
 * 覆盖三件事：**事件序列编排**（按下/移动/抬起必须成对、首尾坐标不许缩水、事件数封顶）、
 * **移动合并与过期丢弃**（采样点怎么被合并、落后计划多久的移动事件该丢）、
 * **失败判定**（命令输出里出现什么字样才算「没落地」，退出码之外的第二道判据）。
 *
 * 这些规则不依赖设备，也不需要 Android 运行时，因此能在这台机器的 JVM 单测里跑；
 * 真机上 `input motionevent` 是否真的落到了副屏上，属于待真机验证的部分。
 */
class VirtualScreenMotionPlanTest {

    /** 沿水平线均匀采样 `count` 个点（跨度 `span` 像素），模拟一层插值后的手势路径。 */
    private fun samples(count: Int, span: Int = 640): List<GesturePoint> =
        (0 until count).map { GesturePoint(span * it / (count - 1), 200) }

    @Test fun `按下与抬起必须成对且首尾坐标原样保留`() {
        val path = samples(60)
        val plan = VirtualScreenMotionPlan.plan(path, 1000)
        // 丢了按下等于整段手势没开始，丢了抬起会把副屏留在「被按住」状态：两头都不许合并掉。
        assertEquals(DOWN, plan.steps.first().action)
        assertEquals(GesturePoint(plan.steps.first().x, plan.steps.first().y), path.first())
        assertEquals(UP, plan.steps.last().action)
        assertEquals(GesturePoint(plan.steps.last().x, plan.steps.last().y), path.last())
        assertEquals(60, plan.samples)
        assertEquals(0L, plan.steps.first().atMillis)
        assertEquals(1000L, plan.steps.last().atMillis)
    }

    @Test fun `按下即抬起的单点路径也要有完整事件对`() {
        // 用户点了一下就走（离散通道累积到的点只有一个）：仍然要按下 + 抬起两个事件。
        val plan = VirtualScreenMotionPlan.plan(listOf(GesturePoint(10, 20)), 240)
        assertEquals(listOf(DOWN, UP), plan.steps.map { it.action })
        assertEquals(GesturePoint(10, 20), GesturePoint(plan.steps.first().x, plan.steps.first().y))
        assertEquals(GesturePoint(10, 20), GesturePoint(plan.steps.last().x, plan.steps.last().y))
        assertEquals(1, plan.samples)
        assertEquals(0, plan.merged)
    }

    @Test fun `密集合成的手势被合并成有界的事件序列`() {
        // 60 个点铺在 640 像素、1000 毫秒上：每步约 10.8 像素、约 17 毫秒。
        // 位移够（≥ 8 像素）但间隔不够（< 40 毫秒）的中间点会被合并掉。
        val plan = VirtualScreenMotionPlan.plan(samples(60), 1000)
        val moves = plan.steps.count { it.action == MOVE }
        assertTrue("移动事件数必须有界，实际 $moves", moves <= VirtualScreenMotionPlan.MAX_EVENTS - 2)
        assertTrue("密集采样点应该被合并掉一批，实际只合并了 ${plan.merged} 个", plan.merged >= 40)
        // merged = (采样点数 - 1) - 移动事件数：合并掉的中间采样点数，供诊断说明这不是逐点直传。
        assertEquals(59 - moves, plan.merged)
        assertTrue("事件总数必须封顶", plan.steps.size <= VirtualScreenMotionPlan.MAX_EVENTS)
        // 末点作为移动事件注入，抬起时带着终点坐标；位移不许停在半路。
        assertTrue("首个移动事件应该已经离开起点", plan.steps.first { it.action == MOVE }.x > 0)
        assertEquals(640, plan.steps.last().x)
    }

    @Test fun `再长的路径也不超过事件数上限`() {
        val plan = VirtualScreenMotionPlan.plan(samples(400), 2000)
        assertTrue("事件数必须封顶，实际 ${plan.steps.size}", plan.steps.size <= VirtualScreenMotionPlan.MAX_EVENTS)
        // 按下 1 个 + 移动若干 + 抬起 1 个，三部分加起来就是事件总数。
        assertEquals(plan.steps.size, 1 + plan.steps.count { it.action == MOVE } + 1)
        assertEquals(DOWN, plan.steps.first().action)
        assertEquals(UP, plan.steps.last().action)
        assertEquals(399 - (plan.steps.size - 2), plan.merged)
    }

    @Test fun `时间轴单调不减且末点落在末端`() {
        val plan = VirtualScreenMotionPlan.plan(samples(200), 1500)
        val times = plan.steps.map { it.atMillis }
        assertEquals(times.sorted(), times)
        assertEquals(0L, times.first())
        assertEquals(1500L, times.last())
    }

    @Test fun `末点离上一点再近也要作为移动事件注入`() {
        // 只在最后 1 个像素处收尾：终点必须照样注入，否则整段位移会停在半路。
        val plan = VirtualScreenMotionPlan.plan(
            listOf(GesturePoint(0, 200), GesturePoint(600, 200), GesturePoint(600, 201)),
            900,
        )
        val moves = plan.steps.filter { it.action == MOVE }
        assertTrue("至少要有一个移动事件", moves.isNotEmpty())
        assertEquals(GesturePoint(600, 201), GesturePoint(moves.last().x, moves.last().y))
    }

    @Test fun `游标丢弃落后计划太久的移动事件`() {
        val steps = listOf(
            Step(DOWN, 0, 200, 0L),
            Step(MOVE, 100, 200, 0L),
            Step(MOVE, 200, 200, 100L),
            Step(MOVE, 300, 200, 200L),
            Step(UP, 400, 200, 4000L),
        )
        val cursor = Cursor(steps)
        val injected = ArrayList<String>()
        while (true) {
            injected.add(cursor.next(1_000)?.action ?: break)
        }
        // 三个移动事件都落后计划 800 毫秒以上（远超 250 毫秒容忍度），补发没有意义，全部丢弃；
        // 按下与抬起不参与过期判定：丢了按下等于手势没开始，丢了抬起会把副屏留在被按住状态。
        assertEquals(listOf(DOWN, UP), injected)
        assertEquals(3, cursor.dropped)
        assertEquals(2, cursor.injected)
    }

    @Test fun `容忍窗口之内的移动事件一个都不丢`() {
        val steps = listOf(
            Step(DOWN, 0, 200, 0L),
            Step(MOVE, 100, 200, 0L),
            Step(MOVE, 200, 200, 100L),
            Step(MOVE, 300, 200, 200L),
            Step(UP, 400, 200, 400L),
        )
        val cursor = Cursor(steps)
        val injected = ArrayList<String>()
        while (true) {
            // 恰好等于容忍度（250 毫秒）不算过期：只有「超过」才丢弃。
            injected.add(cursor.next(250)?.action ?: break)
        }
        assertEquals(listOf(DOWN, MOVE, MOVE, MOVE, UP), injected)
        assertEquals(0, cursor.dropped)
        assertEquals(5, cursor.injected)
        assertNull(cursor.next(250))
    }

    @Test fun `命令输出里出现系统拒绝的字样就判为没落地`() {
        // 只看退出码会把「没落地」当成成功：input 被拒绝时仍可能以 0 退出，所以输出也要判。
        assertTrue(
            VirtualScreenMotionPlan.rejected(
                "Error: Injecting to another the application requires INJECT_EVENTS permission",
            ),
        )
        assertTrue(
            VirtualScreenMotionPlan.rejected(
                "java.lang.SecurityException: Injecting input events requires the caller to have INJECT_EVENTS permission",
            ),
        )
        assertTrue(VirtualScreenMotionPlan.rejected("Unknown command: motionevent"))
        assertTrue(VirtualScreenMotionPlan.rejected("Usage: input [<source>] <command> [<arg>...]"))
        assertFalse(VirtualScreenMotionPlan.rejected(""))
        assertFalse(VirtualScreenMotionPlan.rejected("Motionevent MOVE 100 200"))
    }

    @Test fun `动作名映射到 input 参数与中文说明`() {
        assertEquals("DOWN", VirtualScreenMotionPlan.argument(DOWN))
        assertEquals("MOVE", VirtualScreenMotionPlan.argument(MOVE))
        assertEquals("UP", VirtualScreenMotionPlan.argument(UP))
        assertEquals("CANCEL", VirtualScreenMotionPlan.argument(CANCEL))
        // 失败消息里要能说清是哪个事件没落地，用户才判得出「手感为什么不对」。
        assertEquals("按下", VirtualScreenMotionPlan.label(DOWN))
        assertEquals("移动", VirtualScreenMotionPlan.label(MOVE))
        assertEquals("抬起", VirtualScreenMotionPlan.label(UP))
        assertEquals("取消", VirtualScreenMotionPlan.label(CANCEL))
        assertEquals("none", VirtualScreenMotionPlan.label("none"))
    }

    @Test fun `回放时间轴被压到人工拖动上限之内`() {
        // 手指已经抬起了，再按原速把两秒的轨迹慢放一遍只会更卡：离散通道统一压到 240 毫秒以内。
        assertTrue(VirtualScreenMotionPlan.TOUCH_TIMELINE_MILLIS <= 240)
        val plan = VirtualScreenMotionPlan.plan(samples(80), VirtualScreenMotionPlan.TOUCH_TIMELINE_MILLIS)
        assertEquals(VirtualScreenMotionPlan.TOUCH_TIMELINE_MILLIS.toLong(), plan.steps.last().atMillis)
    }
}
