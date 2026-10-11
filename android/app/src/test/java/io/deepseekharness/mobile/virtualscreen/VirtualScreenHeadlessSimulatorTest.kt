package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无设备副屏模拟器的单测：固定帧 + 事件流 + 虚拟时钟，跑出确定的时间线并断言关键不变量。
 *
 * 这里断言的是**跨类的因果与顺序**（判定动作 → 缓冲所有权 → 触摸能不能直传 → 小窗怎么重挂），
 * 单个类的行为由 `VirtualScreenPreviewCoreTest` / `VirtualScreenPreviewPumpTest` /
 * `VirtualScreenPreviewTouchFlowTest` / `VirtualScreenOverlayMachineTest` 各自覆盖。
 * 所有用例都只用固定输入与可推进的假钟，重复跑逐字相同。
 */
class VirtualScreenHeadlessSimulatorTest {

    // ------------------------------------------------------------------
    // 帧所有权：动作决定缓冲归谁，换帧才关旧缓冲
    // ------------------------------------------------------------------

    @Test
    fun `换帧后旧硬件缓冲被关而 KEEP 只关在途缓冲`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(
            SimFrame(SimFrameKind.HARDWARE, costMillis = 5),
            SimFrame(SimFrameKind.UNREADABLE, costMillis = 5),
            SimFrame(SimFrameKind.HARDWARE_UNREADABLE, costMillis = 5),
            SimFrame(SimFrameKind.HARDWARE, costMillis = 5),
        )
        sim.startPump()

        assertEquals(FRAME_SHOW, sim.beat()?.action)
        assertEquals("首帧上屏时没有旧缓冲要关", emptyList<Int>(), sim.closedBuffers)

        assertEquals(FRAME_KEEP, sim.beat()?.action)
        assertEquals("PNG 路径没解出画面：这一拍手里根本没有缓冲，所以没人要关", emptyList<Int>(), sim.closedBuffers)
        assertTrue(sim.displayed)

        assertEquals(FRAME_KEEP, sim.beat()?.action)
        assertEquals("硬件路径没解出画面：这一拍的在途缓冲必须关掉", listOf(2), sim.closedBuffers)
        assertTrue("KEEP 不动已显示的那一块", sim.displayed)

        assertEquals(FRAME_SHOW, sim.beat()?.action)
        assertEquals("换帧的那一刻旧缓冲才被关", listOf(2, 1), sim.closedBuffers)
    }

    @Test
    fun `KEEP 不推进画面时刻：纯色帧与读不到的帧都保留最近一张真画面，换了新画面才推前`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(
            SimFrame(SimFrameKind.CONTENT, costMillis = 5),
            SimFrame(SimFrameKind.BLANK, costMillis = 5),
            SimFrame(SimFrameKind.UNREADABLE, costMillis = 5),
            SimFrame(SimFrameKind.CHANGED, costMillis = 5),
        )
        sim.startPump()

        val shown = sim.beat()!!
        assertEquals(FRAME_SHOW, shown.action)
        assertTrue(shown.refreshObservedAt)
        assertEquals("副屏预览 · 触摸直传 · 30.0 fps · 档位 realtime-30fps", shown.line)
        val shownAt = sim.observedAtMillis

        val blank = sim.beat()!!
        assertEquals(FRAME_KEEP, blank.action)
        assertFalse("纯色帧不前移画面时刻", blank.refreshObservedAt)
        assertEquals(shownAt, sim.observedAtMillis)
        assertTrue(blank.line.contains("当前帧接近纯色"))

        val unreadable = sim.beat()!!
        assertEquals(FRAME_KEEP, unreadable.action)
        assertFalse(unreadable.refreshObservedAt)
        assertEquals(shownAt, sim.observedAtMillis)
        assertTrue(unreadable.line.contains("未读到新帧"))
        assertTrue(sim.displayed)
        assertEquals(2, sim.keepCount)

        val changed = sim.beat()!!
        assertEquals("换了一张新画面：这一拍才是 SHOW", FRAME_SHOW, changed.action)
        assertTrue("新画面要推前画面时刻，触摸直传的 3000 毫秒窗口就是拿它算的", changed.refreshObservedAt)
        assertTrue("画面真的变了，状态行不再补「画面暂无变化」", !changed.line.contains("画面暂无变化"))
        assertTrue("画面时刻被推到了刚才那些 KEEP 之后", sim.observedAtMillis > shownAt)
        assertEquals(2, sim.showCount)
    }

    @Test
    fun `代号变化时在途帧被丢弃：不进判定核心也不再续排`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.HARDWARE, costMillis = 5))
        sim.startPump()
        assertEquals(FRAME_SHOW, sim.beat()?.action)
        val shownAt = sim.observedAtMillis

        assertTrue(sim.fetch())
        sim.bumpGeneration("重挂")
        assertNull("代号不符：这一拍直接丢弃，没有结论", sim.land())

        assertEquals("丢弃的在途帧没有进判定核心", shownAt, sim.observedAtMillis)
        assertEquals(listOf(2), sim.closedBuffers)
        assertEquals("丢弃在途帧的那一支不调 fetchLanded，所以没有下一拍", 0, sim.queuedBeats)
        assertNull(sim.beat())
        assertEquals(1, sim.discardCount)
        assertTrue(
            sim.timeline().any { it.contains("动作=丢弃在途帧") && it.contains("原因=控件代号已变(1→2)") },
        )
    }

    @Test
    fun `在途帧属于取帧那一刻的会话：会话换掉之后这一帧让判定核心清屏`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.HARDWARE, costMillis = 5))
        sim.startPump()
        assertEquals(FRAME_SHOW, sim.beat()?.action)
        assertTrue(sim.displayed)

        assertTrue(sim.fetch())
        sim.switchSession("s2")
        val cleared = sim.land()!!

        assertEquals(FRAME_CLEAR, cleared.action)
        assertEquals("清屏那一拍的状态行是固定的等待文案", VirtualScreenPolicy.PREVIEW_GONE_MESSAGE, cleared.line)
        assertTrue(cleared.showWaiting)
        assertFalse(sim.displayed)
        assertEquals(0L, sim.observedAtMillis)
        assertEquals("CLEAR 是正常路径：照旧续排下一拍", 1, sim.queuedBeats)
        assertEquals("在途缓冲与已显示缓冲都被关", listOf(2, 1), sim.closedBuffers)
    }

    // ------------------------------------------------------------------
    // 档位与节奏：续排延迟 = 档位间隔 − 本拍耗时，且永不追赶积压
    // ------------------------------------------------------------------

    @Test
    fun `每个档位的续排延迟都等于 previewPullDelay 且每拍只续排一次`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.CONTENT))
        sim.startPump()

        val expected = mutableListOf<Long>()
        val actual = mutableListOf<Long>()
        for ((mode, interval) in VirtualScreenPolicy.FRAME_MODES) {
            sim.changeMode(mode)
            val before = sim.pumpDelays.size
            assertNotNull(sim.beat())
            assertEquals("每拍只续排一次", 1, sim.pumpDelays.size - before)
            assertEquals("档位表里的间隔就是采集间隔", interval, VirtualScreenPolicy.previewPullInterval(mode))
            expected += VirtualScreenPolicy.previewPullDelay(mode, 0)
            actual += sim.pumpDelays.last()
        }

        assertEquals("档位表恰好十档", 10, VirtualScreenPolicy.FRAME_MODES.size)
        assertEquals(expected, actual)
        assertEquals(10, sim.beatCount)
    }

    @Test
    fun `慢一拍不追赶积压：多花的时间从下一拍等待里扣掉`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.changeMode("15fps")
        sim.script(
            SimFrame(SimFrameKind.CONTENT, costMillis = 200),
            SimFrame(SimFrameKind.CONTENT, costMillis = 0),
            SimFrame(SimFrameKind.CONTENT, costMillis = 66),
        )
        sim.startPump()

        sim.beat()
        assertEquals("取帧花了 200 毫秒，已经超过 66 毫秒的间隔：夹到下界 1 毫秒", 1L, sim.pumpDelays.last())
        sim.beat()
        assertEquals("上一拍没花时间：老老实实等满一个间隔", 66L, sim.pumpDelays.last())
        sim.beat()
        assertEquals("正好花掉一个间隔：余量为 0，夹到 1 毫秒", 1L, sim.pumpDelays.last())

        assertEquals(3, sim.beatCount)
        assertEquals("积压不补拍：队列里始终只排着一拍", 1, sim.queuedBeats)
    }

    @Test
    fun `取帧下限成立：最快的档位也要等够 PREVIEW_PULL_FLOOR_MILLIS`() {
        VirtualScreenPolicy.FRAME_MODES.keys.forEach { mode ->
            assertTrue(
                "档位 $mode 的采集间隔低于取帧下限",
                VirtualScreenPolicy.previewPullInterval(mode) >= VirtualScreenPolicy.PREVIEW_PULL_FLOOR_MILLIS,
            )
        }

        val sim = VirtualScreenHeadlessSimulator()
        sim.changeMode("240fps")
        sim.script(
            SimFrame(SimFrameKind.CONTENT, costMillis = 0),
            SimFrame(SimFrameKind.CONTENT, costMillis = 4),
            SimFrame(SimFrameKind.CONTENT, costMillis = 1_000),
        )
        sim.startPump()

        sim.beat()
        assertEquals(4L, sim.pumpDelays.last())
        sim.beat()
        assertEquals("正好用掉 4 毫秒：余量 0 也保底 1 毫秒", 1L, sim.pumpDelays.last())
        sim.beat()
        assertEquals(1L, sim.pumpDelays.last())
        assertTrue("续排延迟永远不小于 1 毫秒", sim.pumpDelays.drop(1).all { it >= 1L })
    }

    // ------------------------------------------------------------------
    // 触摸：直传节流与补发、离散点击、长按改判、代号判废
    // ------------------------------------------------------------------

    @Test
    fun `直传按下移动抬起：8 毫秒节流合并中间点，抬起时补发最后一个点`() {
        val sim = streamReady()
        sim.down(at = floatArrayOf(10f, 20f))
        sim.move(at = floatArrayOf(11f, 20f))
        sim.advance(4)
        sim.move(at = floatArrayOf(12f, 20f))
        sim.advance(6)
        sim.move(at = floatArrayOf(13f, 20f))
        sim.advance(2)
        sim.move(at = floatArrayOf(14f, 20f))
        sim.up(at = floatArrayOf(15f, 20f))
        sim.flushTouch()

        assertEquals(
            listOf(
                "stream:down@10,20#1",
                "stream:move@11,20#1",
                "stream:move@13,20#1",
                "front",
                "click",
                "stream:up@14,20#1",
            ),
            sim.deliveredTouch,
        )
        assertFalse("被节流合并掉的中间点不能补发", sim.deliveredTouch.contains("stream:move@12,20#1"))
        assertFalse("抬起用的是合并掉的那个末位置，不是抬起点", sim.deliveredTouch.contains("stream:up@15,20#1"))
    }

    @Test
    fun `UP 未拖动就是置前加点击：非直传通道走离散动作且占位会放开`() {
        val sim = streamReady(channel = "tap")
        sim.down(at = floatArrayOf(10f, 20f))
        sim.up(at = floatArrayOf(10f, 20f))
        assertEquals("抬起之前一条都没有投递出去", emptyList<String>(), sim.deliveredTouch)
        sim.flushTouch()

        assertEquals(listOf("front", "click", "tap@10,20#1"), sim.deliveredTouch)
        assertFalse("投递完之后离散动作的占位必须放开", sim.discreteBusy)

        sim.down(at = floatArrayOf(30f, 40f))
        sim.up(at = floatArrayOf(30f, 40f))
        sim.flushTouch()
        assertEquals("占位放开之后第二次点击照旧发得出去", 2, sim.deliveredTouch.count { it.startsWith("tap@") })
    }

    @Test
    fun `UP 未拖动的滑动带起点终点与时长，时长按按下到抬起的间隔夹取`() {
        val sim = streamReady(channel = "tap")
        sim.down(at = floatArrayOf(10f, 20f))
        sim.advance(300)
        sim.up(at = floatArrayOf(60f, 90f), eventTimeMillis = sim.uptimeMillis)
        sim.flushTouch()
        assertEquals(listOf("front", "click", "swipe@10,20->60,90ms300#1"), sim.deliveredTouch)

        sim.down(at = floatArrayOf(10f, 20f))
        sim.advance(10)
        sim.up(at = floatArrayOf(60f, 90f), eventTimeMillis = sim.uptimeMillis)
        sim.flushTouch()
        assertEquals("时长下界 100 毫秒", "swipe@10,20->60,90ms100#1", sim.deliveredTouch.last())
    }

    @Test
    fun `长按超过拖动阈值：先给副屏补一个 cancel，再按新位置拖窗`() {
        val sim = streamReady()
        assertTrue("小窗在手，才有窗口可拖", sim.showOverlay())
        sim.down(at = floatArrayOf(10f, 20f), rawX = 100f, rawY = 200f)
        sim.flushTouch()
        sim.advance(350)
        // 位移 4,4 还在静置容差（8dp × density 2 = 16px）里：按住够久就改判拖窗
        sim.move(at = floatArrayOf(12f, 22f), rawX = 104f, rawY = 204f)
        sim.flushTouch()

        // 触发顺序就是真机上 `sendTouch` 的同步顺序：拖动开始 → 补 cancel → 按新位置拖。
        // 投递顺序另算（投递发生在工作线程上），这里两处 cancel 都排在那次拖动的后面落地。
        assertEquals(
            listOf(
                "触发 stream:down@10,20#2",
                "投递 stream:down@10,20#2",
                "拖动 开始（可拖=true）",
                "触发 stream:cancel@10,20#2",
                "拖动 位置更新 +4,+4",
                "投递 stream:cancel@10,20#2",
            ),
            sim.touchLog(),
        )
        assertEquals(
            "cancel 必须排在拖动之后触发，且只有一条",
            listOf("触发 stream:cancel@10,20#2"),
            sim.touchLog().filter { it.startsWith("触发 stream:cancel") },
        )
        assertTrue(
            "改判之后位置按按下原点位移更新",
            sim.timeline().any { it.contains("窗口几何 → 位置=(207,146) 尺寸=674×1486") },
        )
    }

    @Test
    fun `拖动位移相对按下原点换算：被节流丢掉的中间点不会攒出偏差`() {
        val sim = streamReady()
        assertTrue(sim.showOverlay())
        sim.down(at = floatArrayOf(10f, 20f), rawX = 100f, rawY = 200f)
        sim.advance(350)
        sim.move(at = floatArrayOf(12f, 22f), rawX = 104f, rawY = 204f)
        sim.advance(4)
        sim.move(at = floatArrayOf(42f, 22f), rawX = 134f, rawY = 204f)
        sim.advance(6)
        sim.move(at = floatArrayOf(48f, 20f), rawX = 140f, rawY = 200f)
        sim.advance(2)
        sim.move(at = floatArrayOf(53f, 30f), rawX = 145f, rawY = 210f)
        sim.up(at = floatArrayOf(53f, 30f), rawX = 145f, rawY = 210f)
        sim.flushTouch()

        val positions = sim.timeline().filter { it.contains("窗口几何 →") }
        assertEquals(
            listOf(
                "窗口几何 → 位置=(207,146) 尺寸=674×1486",
                "窗口几何 → 位置=(243,142) 尺寸=674×1486",
                "窗口几何 → 位置=(248,152) 尺寸=674×1486",
            ),
            positions.map { it.substringAfter("窗口 ") },
        )
        assertEquals(
            "拖动端口的调用序列：位移一律相对按下原点，被节流丢掉的中间点不攒偏差",
            listOf(
                "拖动 开始（可拖=true）",
                "拖动 位置更新 +4,+4",
                "拖动 位置更新 +40,+0",
                "拖动 结束 +45,+10",
            ),
            sim.touchLog().filter { it.startsWith("拖动") },
        )
        assertFalse("拖窗手势不发 up、不发点击", sim.deliveredTouch.any { it.startsWith("stream:up") || it.startsWith("tap") })
    }

    @Test
    fun `作废代号之后直传事件被丢弃，但 cancel 永远投递`() {
        val sim = streamReady()
        sim.down(at = floatArrayOf(10f, 20f))
        sim.bumpGeneration("重挂")
        sim.flushTouch()
        assertEquals("代号变了：按下事件作废", emptyList<String>(), sim.deliveredTouch)

        sim.cancel(at = floatArrayOf(10f, 20f))
        sim.flushTouch()
        assertEquals("cancel 不看代号，永远投递", listOf("stream:cancel@10,20#2"), sim.deliveredTouch)
        assertTrue(
            sim.touchEffects.any { it.contains("丢弃 stream:down@10,20#1") && it.contains("原因=控件代号已变") },
        )
    }

    // ------------------------------------------------------------------
    // 窗口：幂等、建窗失败回滚、重挂、节流、收起与销毁
    // ------------------------------------------------------------------

    @Test
    fun `幂等显示：窗口已经在手时第二次请求连布局都不会重算`() {
        val sim = VirtualScreenHeadlessSimulator()
        assertTrue(sim.showOverlay())
        assertEquals(1, sim.addViewCalls)
        assertEquals(1, sim.layoutCalls)
        assertEquals(OverlayState.SHOWN, sim.overlayState)

        assertFalse(sim.showOverlay())
        assertEquals("没有第二次建窗", 1, sim.addViewCalls)
        assertEquals("连一次屏幕读数都没发生", 1, sim.layoutCalls)
        assertEquals(OverlayState.SHOWN, sim.overlayState)
        assertTrue(sim.timeline().any { it.contains("显示被吞掉（幂等：窗口已经在手）") })
    }

    @Test
    fun `建窗失败回滚到 ABSENT：帧循环没有启动，也没有窗口可置前`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.overlayAddWorks = false
        assertFalse(sim.showOverlay())

        assertEquals(OverlayState.ABSENT, sim.overlayState)
        assertNull("布局连同 pending 一起被丢掉", sim.overlayLayout)
        assertFalse("拍子从来没有启动过", sim.pumpRunning)
        assertEquals(0, sim.queuedBeats)
        assertFalse("没有窗口：置前请求无效", sim.bringOverlayToFront())
        assertFalse(sim.displayed)
    }

    @Test
    fun `权限或会话没就绪时显示被拒绝，状态机不会记成一个中间态`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.overlayPermission = false
        assertFalse(sim.showOverlay())
        assertEquals(OverlayState.ABSENT, sim.overlayState)

        sim.overlayPermission = true
        sim.overlaySessionReady = false
        assertFalse(sim.showOverlay())
        assertEquals(OverlayState.ABSENT, sim.overlayState)
        assertEquals(0, sim.layoutCalls)
        assertTrue(sim.timeline().any { it.contains("显示被拒绝：请先授予悬浮窗权限") })
        assertTrue(sim.timeline().any { it.contains("显示被拒绝：副屏尚未就绪") })
    }

    @Test
    fun `重挂失败的 effect 顺序：显式放掉已经摘下的帧循环`() {
        val sim = VirtualScreenHeadlessSimulator()
        assertTrue(sim.showOverlay())
        sim.advance(1200)
        sim.overlayReAddWorks = false

        assertFalse(sim.bringOverlayToFront())
        assertEquals(
            listOf(
                "KeepFrameLoop（保留取帧循环）",
                "RemoveAndReAddView（摘下来立刻重挂）",
                "ReleaseFrameLoop（复位保留取帧循环）",
                "RemoveView（摘窗）",
                "ReleaseDetachedFrameLoop（显式释放已摘下的帧循环）",
                "ReleaseFrameLoop（复位保留取帧循环）",
            ),
            sim.windowLog(),
        )
        assertEquals(OverlayState.HIDDEN, sim.overlayState)
        assertFalse("重挂失败之后取帧循环被释放", sim.pumpRunning)
        assertTrue(sim.timeline().any { it.contains("中间态 SUSPENDED") })
    }

    @Test
    fun `置前 1200 毫秒节流：不满 1200 毫秒被吞掉且不记账`() {
        val sim = VirtualScreenHeadlessSimulator()
        assertTrue(sim.showOverlay())
        assertEquals(OverlayState.SHOWN, sim.overlayState)

        sim.advance(1199)
        assertFalse(sim.bringOverlayToFront())
        assertTrue("被吞掉的请求一条 effect 都不发", sim.windowEffects.isEmpty())
        assertEquals(OverlayState.SHOWN, sim.overlayState)
        assertTrue("被吞掉也不会顺手停掉拍子", sim.pumpRunning)

        sim.advance(1)
        assertTrue(sim.bringOverlayToFront())
        assertEquals(3, sim.windowEffects.size)
        assertEquals(OverlayState.SHOWN, sim.overlayState)
        assertTrue("保留取帧循环：拍子活过了这次重挂", sim.pumpRunning)

        assertFalse("同一次节流窗口里再点：照样吞掉", sim.bringOverlayToFront())
        sim.advance(1199)
        assertFalse(sim.bringOverlayToFront())
        sim.advance(1)
        assertTrue("满 1200 毫秒之后重新放行", sim.bringOverlayToFront())
        assertEquals(6, sim.windowEffects.size)
    }

    @Test
    fun `收起小窗先复位保留开关再摘窗，销毁之后不再接受显示请求`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.CONTENT, costMillis = 5))
        assertTrue(sim.showOverlay())
        assertNotNull(sim.beat())
        assertTrue(sim.displayed)

        sim.hideOverlay()
        assertEquals(
            listOf("ReleaseFrameLoop（复位保留取帧循环）", "RemoveView（摘窗）"),
            sim.windowLog(),
        )
        assertEquals(OverlayState.HIDDEN, sim.overlayState)
        assertFalse(sim.pumpRunning)
        assertFalse("摘窗之后画面被清掉", sim.displayed)
        assertEquals(0, sim.queuedBeats)

        assertTrue("窗口不在手时还能重新显示", sim.showOverlay())
        assertEquals(OverlayState.SHOWN, sim.overlayState)
        sim.destroyService()
        assertEquals(OverlayState.DEAD, sim.overlayState)
        assertFalse(sim.pumpRunning)
        assertFalse("销毁之后显示请求被拒", sim.showOverlay())
        assertEquals(OverlayState.DEAD, sim.overlayState)
        assertTrue(sim.timeline().any { it.contains("显示被拒绝：副屏尚未就绪") })
    }

    // ------------------------------------------------------------------
    // 息屏与恢复
    // ------------------------------------------------------------------

    @Test
    fun `息屏停拍子并清屏，恢复之后从下一拍重新建立画面`() {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.CONTENT, costMillis = 5))
        // 息屏走的是「控件从窗口上摘下来」这条路，所以得先让小窗把控件挂上
        assertTrue(sim.showOverlay())
        assertNotNull(sim.beat())
        assertTrue(sim.displayed)
        val shownAt = sim.observedAtMillis

        sim.screenOff()
        assertFalse(sim.pumpRunning)
        assertFalse(sim.displayed)
        assertEquals(0L, sim.observedAtMillis)
        assertNull("拍子没在跑：这一拍根本没排上", sim.beat())
        assertEquals("息屏清掉画面时刻之后，不会再被推回来", 0L, sim.observedAtMillis)
        assertEquals(0, sim.queuedBeats)

        sim.screenOn()
        assertTrue(sim.pumpRunning)
        assertNotNull(sim.beat())
        assertTrue(sim.displayed)
        assertTrue("恢复之后画面时刻重新前移", sim.observedAtMillis > shownAt)
        assertEquals(1, sim.queuedBeats)
    }

    // ------------------------------------------------------------------
    // 确定性 + 人工阅读入口
    // ------------------------------------------------------------------

    @Test
    fun `同一段脚本跑两次得到逐字相同的时间线`() {
        val first = richScript()
        val second = richScript()

        assertTrue("样例时间线要有足够多的行", first.timeline().size > 20)
        assertEquals(first.timeline(), second.timeline())
        assertEquals(first.render(), second.render())
        assertEquals(first.windowEffects, second.windowEffects)
        assertEquals(first.touchEffects, second.touchEffects)
        assertEquals(first.closedBuffers, second.closedBuffers)
        assertTrue("样例里要覆盖到每一种动作", first.timeline().any { it.contains("动作=丢弃在途帧") })
    }

    @Test
    fun `打印一条完整时间线供人工阅读`() {
        val text = richScript().render()
        println(text)

        assertTrue(text.contains("==== 无设备副屏模拟器时间线 ===="))
        assertTrue(text.contains("列说明"))
        assertTrue(text.contains("动作=SHOW"))
        assertTrue(text.contains("动作=KEEP"))
        assertTrue(text.contains("窗口 effect 序列"))
        assertTrue(text.contains("触摸 effect 序列"))
        assertTrue(text.contains("已释放硬件缓冲"))
    }

    // ------------------------------------------------------------------
    // 用例内的公共脚手架
    // ------------------------------------------------------------------

    /** 先跑出一拍直传画面（通道 = stream 且画面新鲜），触摸才有直传的资格。 */
    private fun streamReady(
        costMillis: Long = 5,
        channel: String = "stream",
    ): VirtualScreenHeadlessSimulator {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(SimFrame(SimFrameKind.CONTENT, channel = channel, costMillis = costMillis))
        sim.startPump()
        assertEquals(FRAME_SHOW, sim.beat()?.action)
        assertEquals(channel, sim.observedChannel)
        return sim
    }

    /** 一段覆盖全部事件族的脚本：固定帧 + 触摸 + 置前重挂 + 在途帧被打断 + 息屏恢复。 */
    private fun richScript(): VirtualScreenHeadlessSimulator {
        val sim = VirtualScreenHeadlessSimulator()
        sim.script(
            SimFrame(SimFrameKind.CONTENT, costMillis = 5),
            SimFrame(SimFrameKind.SAME, costMillis = 5),
            SimFrame(SimFrameKind.CHANGED, costMillis = 12),
            SimFrame(SimFrameKind.BLANK, costMillis = 4),
            SimFrame(SimFrameKind.UNREADABLE, costMillis = 30),
            SimFrame(SimFrameKind.HARDWARE, costMillis = 6),
            SimFrame(SimFrameKind.HARDWARE_UNREADABLE, costMillis = 6),
        )
        sim.changeMode("60fps")
        assertTrue(sim.showOverlay())
        sim.beats(4)

        sim.down(at = floatArrayOf(10f, 20f), rawX = 100f, rawY = 200f)
        sim.advance(350)
        sim.move(at = floatArrayOf(12f, 22f), rawX = 104f, rawY = 204f)
        sim.advance(10)
        sim.move(at = floatArrayOf(50f, 22f), rawX = 142f, rawY = 204f)
        sim.advance(10)
        sim.up(at = floatArrayOf(50f, 22f), rawX = 142f, rawY = 204f)
        sim.flushTouch()
        sim.beats(2)

        sim.advance(1200)
        assertTrue(sim.bringOverlayToFront())
        sim.beats(2)

        assertTrue(sim.fetch())
        sim.bumpGeneration("重挂打断了在途帧")
        assertNull(sim.land())

        sim.screenOff()
        sim.screenOn()
        sim.beats(2)
        return sim
    }

    /** 时间线里的触摸序列，去掉逐条「事件」行，只留 effect 与拖动端口的调用顺序。 */
    private fun VirtualScreenHeadlessSimulator.touchLog(): List<String> =
        touchEffects.map { it.substringAfter("] ") }.filterNot { it.startsWith("事件") }

    /** 窗口 effect 的落地序列（去掉时间戳前缀）。 */
    private fun VirtualScreenHeadlessSimulator.windowLog(): List<String> =
        windowEffects.map { it.substringAfter("] ") }
}

private val FRAME_SHOW = VirtualScreenPolicy.PreviewFrameAction.SHOW
private val FRAME_KEEP = VirtualScreenPolicy.PreviewFrameAction.KEEP
private val FRAME_CLEAR = VirtualScreenPolicy.PreviewFrameAction.CLEAR
