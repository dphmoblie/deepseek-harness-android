package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenOverlayMachine] 的生命周期语义：闸门顺序、幂等、失败回滚、置前节流 1200 毫秒、
 * 重挂的「保留取帧循环」窗口与收起 / 销毁的 effect 顺序。
 *
 * 时钟、权限、会话与几何读数都是注入的假值（照 [VirtualScreenPreviewPumpTest] 的范式），
 * 所以这些断言既不用真机，也不用真的建窗口。真机上的可见性、叠放顺序、闪屏、重挂后首帧时延
 * 与 HardwareBuffer 泄漏不在覆盖范围内 —— 这里保证的是「发出去的 effect 与顺序」。
 */
class VirtualScreenOverlayMachineTest {
    /** 假现场：屏幕 1080×1920、上下系统栏 60/90，小窗默认放在 (100, 200)、400×800。 */
    private class Fake {
        var uptime = 0L
        var canDraw = true
        var sessionReady = true
        var density = 2f
        var spec = VirtualScreenSpec.PORTRAIT
        var viewWidth = 0
        var viewHeight = 0

        /** 布局被算过几次：幂等与闸门顺序都靠它断言「连屏幕读数都没发生」。 */
        var layoutCalls = 0

        val area = OverlayArea(widthPx = 1080, heightPx = 1920, topInsetPx = 60, bottomInsetPx = 90,
            sizingWidthPx = 1080, sizingHeightPx = 1770)
        val requested = OverlayLayout(x = 100, y = 200, widthPx = 400, heightPx = 800, area = area)

        val machine = VirtualScreenOverlayMachine(
            uptimeMillis = { uptime },
            canDrawOverlays = { canDraw },
            sessionReady = { sessionReady },
            density = { density },
            spec = { spec },
            viewWidthPx = { viewWidth },
            viewHeightPx = { viewHeight },
            frontThrottleMillis = 1200L,
        )

        /** 走完一次成功的显示（建窗 → 窗口真的进了队列）。 */
        fun shown(x: Int = 100, y: Int = 200): Fake {
            machine.show { layoutCalls++; requested.copy(x = x, y = y) }
            machine.viewAdded()
            return this
        }
    }

    @Test fun `没有悬浮窗权限时显示请求直接抛错，连布局都不算`() {
        val fake = Fake().apply { canDraw = false }
        val error = assertThrows(IllegalStateException::class.java) { fake.machine.show { fake.layoutCalls++; fake.requested } }
        assertEquals("请先授予悬浮窗权限", error.message)
        assertEquals(0, fake.layoutCalls)
        assertEquals(OverlayState.ABSENT, fake.machine.state)
        assertNull(fake.machine.layout)
    }

    @Test fun `会话未就绪的闸门排在幂等之前：已经显示过也照样抛错`() {
        val fake = Fake().shown()
        fake.sessionReady = false
        val error = assertThrows(IllegalStateException::class.java) { fake.machine.show { fake.layoutCalls++; fake.requested } }
        assertEquals("副屏尚未就绪", error.message)
        assertEquals("只算过第一次", 1, fake.layoutCalls)
        assertEquals(OverlayState.SHOWN, fake.machine.state)
    }

    @Test fun `重复显示是幂等的：第二份 effect 不会发出，也不会重算布局`() {
        val fake = Fake()
        assertEquals(listOf(OverlayEffect.AddView(fake.requested)), fake.machine.show { fake.layoutCalls++; fake.requested })
        fake.machine.viewAdded()
        assertEquals(OverlayState.SHOWN, fake.machine.state)
        assertTrue(fake.machine.show { fake.layoutCalls++; fake.requested }.isEmpty())
        assertEquals(1, fake.layoutCalls)
    }

    @Test fun `建窗失败回滚：没有窗口、没有待落地的布局，之后也不接受任何窗口动作`() {
        val fake = Fake()
        assertEquals(listOf(OverlayEffect.AddView(fake.requested)), fake.machine.show { fake.requested })
        fake.machine.viewAddFailed()
        assertEquals(OverlayState.ABSENT, fake.machine.state)
        assertNull(fake.machine.layout)

        // 失败之后窗口并不存在：置前、拖动、缩放都不该有任何动作。
        fake.uptime = 1200L
        assertTrue(fake.machine.bringToFront().isEmpty())
        assertFalse(fake.machine.beginDrag())
        assertTrue(fake.machine.resize(600).isEmpty())
    }

    @Test fun `置前节流 1200 毫秒：窗口内吞掉，之后放行`() {
        val fake = Fake().shown()
        fake.uptime = 1200L
        assertEquals(
            listOf(OverlayEffect.KeepFrameLoop, OverlayEffect.RemoveAndReAddView, OverlayEffect.ReleaseFrameLoop),
            fake.machine.bringToFront(),
        )
        fake.machine.viewReAdded()

        fake.uptime = 2399L
        assertTrue("节流窗口内必须一次都不重建", fake.machine.bringToFront().isEmpty())
        fake.uptime = 2400L
        assertEquals(3, fake.machine.bringToFront().size)
        fake.machine.viewReAdded()
    }

    @Test fun `重挂中间态是 SUSPENDED，只有回报成功才回到 SHOWN`() {
        val fake = Fake().shown()
        fake.uptime = 1200L
        fake.machine.bringToFront()
        assertEquals(OverlayState.SUSPENDED, fake.machine.state)
        fake.machine.viewReAdded()
        assertEquals(OverlayState.SHOWN, fake.machine.state)
    }

    @Test fun `没有窗口时置前不产生任何 effect`() {
        val fake = Fake()
        fake.uptime = 1200L
        assertTrue(fake.machine.bringToFront().isEmpty())
        assertEquals(OverlayState.ABSENT, fake.machine.state)
    }

    @Test fun `重挂失败的 effect 顺序：复位保留开关 → 摘窗 → 显式释放帧循环 → 再复位`() {
        val fake = Fake().shown()
        fake.uptime = 1200L
        fake.machine.bringToFront()
        assertEquals(
            listOf(
                OverlayEffect.ReleaseFrameLoop,
                OverlayEffect.RemoveView,
                OverlayEffect.ReleaseDetachedFrameLoop,
                OverlayEffect.ReleaseFrameLoop,
            ),
            fake.machine.viewReAddFailed(),
        )
        assertEquals(OverlayState.HIDDEN, fake.machine.state)
        assertNull(fake.machine.layout)
    }

    @Test fun `收起先复位保留开关再摘窗，并把几何与节流一起清掉`() {
        val fake = Fake().shown()
        fake.uptime = 1200L
        fake.machine.bringToFront()
        fake.machine.viewReAdded()

        assertEquals(listOf(OverlayEffect.ReleaseFrameLoop, OverlayEffect.RemoveView), fake.machine.hide())
        assertNull(fake.machine.layout)
        assertEquals(OverlayState.HIDDEN, fake.machine.state)

        // 收起把节流计时归零：立刻重新显示并置前，不会被刚才那一次挡住。
        fake.uptime = 1250L
        assertEquals(1, fake.machine.show { fake.layoutCalls++; fake.requested }.size)
        fake.machine.viewAdded()
        assertEquals(3, fake.machine.bringToFront().size)
    }

    @Test fun `从未显示过时收起不产生 effect，状态也不会变成「显示过」`() {
        val fake = Fake()
        assertTrue(fake.machine.hide().isEmpty())
        assertEquals(OverlayState.ABSENT, fake.machine.state)
    }

    @Test fun `缩放尺寸没变时不动窗口`() {
        val fake = Fake().shown()
        val first = fake.machine.resize(600).single() as OverlayEffect.UpdateLayout
        assertEquals(600, first.widthPx)
        assertTrue("宽度没变就该一次窗口更新都不发", fake.machine.resize(first.widthPx).isEmpty())
    }

    @Test fun `缩放只认宽度：高度按画面比例反算，位置只夹一次`() {
        val fake = Fake().shown()
        val effect = fake.machine.resize(600).single() as OverlayEffect.UpdateLayout
        assertEquals(600, effect.widthPx)
        assertEquals(Math.round(600 / VirtualScreenSpec.PORTRAIT.aspectRatio).toInt(), effect.heightPx)
        assertEquals(100, effect.x)
        assertEquals(200, effect.y)
    }

    @Test fun `缩放放大之后位置被夹回可用区域`() {
        val fake = Fake().shown(x = 900, y = 900)
        val effect = fake.machine.resize(4096).single() as OverlayEffect.UpdateLayout
        // 按 84% 屏高反算出的尺寸：右下都贴住可用区域，不会有一半在屏幕外。
        assertEquals(1080 - effect.widthPx, effect.x)
        assertEquals(1920 - 90 - effect.heightPx, effect.y)
    }

    @Test fun `拖动要先按下过，位移相对按下原点换算而不是累加`() {
        val fake = Fake()
        assertFalse("还没有窗口时拖不动", fake.machine.beginDrag())
        fake.shown()
        assertTrue(fake.machine.beginDrag())
        assertTrue("位置没变就不该更新窗口", fake.machine.dragBy(0, 0).isEmpty())

        val moved = fake.machine.dragBy(40, 30).single() as OverlayEffect.UpdateLayout
        assertEquals(140, moved.x)
        assertEquals(230, moved.y)
        // 同样的位移再来一次不会继续累加：基准一直是按下那一刻的原点，被节流丢掉的中间点不会攒出偏差。
        assertTrue(fake.machine.dragBy(40, 30).isEmpty())
    }

    @Test fun `没按下过就发位移不会动窗口`() {
        val fake = Fake().shown()
        assertTrue(fake.machine.dragBy(100, 100).isEmpty())
        assertEquals(100, fake.machine.layout?.x)
    }

    @Test fun `拖动结束补发被节流丢掉的末位置，结束之后不再动`() {
        val fake = Fake().shown()
        fake.machine.beginDrag()
        fake.machine.dragBy(10, 10)
        val last = fake.machine.endDrag(300, 300).single() as OverlayEffect.UpdateLayout
        assertEquals(400, last.x)
        assertEquals(500, last.y)
        assertTrue("拖动已经结束", fake.machine.endDrag(300, 300).isEmpty())
    }

    @Test fun `视图量出尺寸之后按视图尺寸夹取，而不是窗口参数`() {
        val fake = Fake().shown()
        fake.viewWidth = 500
        fake.viewHeight = 900
        fake.machine.beginDrag()
        val moved = fake.machine.dragBy(1_000, 1_000).single() as OverlayEffect.UpdateLayout
        assertEquals(1080 - 500, moved.x)
        assertEquals(1920 - 90 - 900, moved.y)
    }

    @Test fun `会话结束只记账不摘窗，销毁才收起并记到 DEAD`() {
        val fake = Fake().shown()
        fake.machine.stop()
        assertEquals(OverlayState.STOPPING, fake.machine.state)
        assertNotNull("会话结束不摘窗：改造前也是等 onDestroy 才收起", fake.machine.layout)

        assertEquals(listOf(OverlayEffect.ReleaseFrameLoop, OverlayEffect.RemoveView), fake.machine.destroy())
        assertEquals(OverlayState.DEAD, fake.machine.state)
        assertNull(fake.machine.layout)
        // 再销毁一次是空操作（窗口已经收掉了）。
        assertTrue(fake.machine.destroy().isEmpty())
        assertEquals(OverlayState.DEAD, fake.machine.state)
    }

    @Test fun `一条会话的效果序列：建窗 → 置前重建 → 收起`() {
        val fake = Fake()
        val sequence = mutableListOf<String>()
        fun record(effects: List<OverlayEffect>) {
            effects.forEach { sequence += it::class.simpleName!! }
        }

        record(fake.machine.show { fake.requested })
        fake.machine.viewAdded()
        fake.uptime = 1200L
        record(fake.machine.bringToFront())
        fake.machine.viewReAdded()
        record(fake.machine.hide())

        assertEquals(
            listOf("AddView", "KeepFrameLoop", "RemoveAndReAddView", "ReleaseFrameLoop", "ReleaseFrameLoop", "RemoveView"),
            sequence,
        )
        // 窗口层的三个动作按会话顺序各来一次：建窗 → 摘下来重挂 → 收起。
        assertEquals(listOf("AddView", "RemoveAndReAddView", "RemoveView"), sequence.filter { it != "KeepFrameLoop" && it != "ReleaseFrameLoop" })
    }
}
