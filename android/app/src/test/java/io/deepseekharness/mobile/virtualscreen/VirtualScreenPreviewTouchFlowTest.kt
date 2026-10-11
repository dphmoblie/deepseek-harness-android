package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** effect 的短标签：拖动端口的调用与 effect 共用一条时间线，交错顺序才能被断言。 */
private fun touchEffectLabel(effect: PreviewTouchEffect): String = when (effect) {
    PreviewTouchEffect.BringToFront -> "front"
    PreviewTouchEffect.Click -> "click"
    is PreviewTouchEffect.Stream -> "stream:${effect.phase}@${effect.x},${effect.y}"
    is PreviewTouchEffect.Discrete ->
        if (effect.swipe) "swipe@${effect.startX},${effect.startY}->${effect.endX},${effect.endY}ms${effect.durationMs}"
        else "tap@${effect.startX},${effect.startY}"
}

/**
 * [VirtualScreenPreviewTouchFlow] 的手势判定：直传闸门、两条 8 毫秒节流、长按改判拖窗、抬起前补发。
 *
 * 用假时钟 + 假拖动端口（照 [VirtualScreenPreviewPumpTest] 的范式），所以这些时序断言既不用真机，
 * 也不用真的等。真机上「触摸有没有真的到达目标应用」不在覆盖范围内。
 */
class VirtualScreenPreviewTouchFlowTest {
    /** 记录拖动端口被调用的顺序；与 effect 共用一条时间线。 */
    private class RecordingDragHost(private val timeline: MutableList<String>) : VirtualScreenDragHost {
        var accept = true
        override fun beginDrag(): Boolean {
            timeline += "drag:begin"
            return accept
        }

        override fun drag(deltaX: Int, deltaY: Int) {
            timeline += "drag:$deltaX,$deltaY"
        }

        override fun endDrag(deltaX: Int, deltaY: Int) {
            timeline += "drag:end:$deltaX,$deltaY"
        }
    }

    /**
     * 假现场：时钟、密度、会话、直传闸门与拖动端口都可调。
     *
     * 直传/离散两道闸门的**真实**实现读的是服务与判定核心（见 `VirtualScreenPreview` 里的注入），
     * 这里只按同样的语义作答：直传看 [streamable]，离散还要求「会话与按下时一致」并记下问过的会话号。
     */
    private class Fake {
        var uptime = 0L
        var realtime = 1_000L
        var density = 2f
        var session = "s1"
        var streamable = true
        var discreteAllowed = true
        var generation = 3
        var hostAvailable = true

        /** 离散闸门被问过几次、问的是哪个会话。 */
        var claims = 0
        val asked = mutableListOf<String>()

        /** 最近一次 accept 交付的 effect，按原顺序。 */
        val effects = mutableListOf<PreviewTouchEffect>()

        /** effect 与拖动端口调用共用的时间线。 */
        val timeline = mutableListOf<String>()
        val host = RecordingDragHost(timeline)

        private var rawX = 100f
        private var rawY = 200f

        val flow = VirtualScreenPreviewTouchFlow(
            uptimeMillis = { uptime },
            realtimeMillis = { realtime },
            density = { density },
            observedSession = { session },
            streamable = { streamable },
            discreteReady = { requested ->
                claims++
                asked += requested
                // 真闸门里这一条也是短路条件之一：会话与按下时不一致就不发。
                discreteAllowed && requested == session
            },
            generation = { generation },
            dragHost = { if (hostAvailable) host else null },
            dispatch = { effect -> effects += effect; timeline += touchEffectLabel(effect) },
        )

        fun down(at: FloatArray? = floatArrayOf(10f, 20f), eventTime: Long = 0L) {
            effects.clear(); timeline.clear()
            rawX = 100f; rawY = 200f
            flow.accept(PreviewPointer.Down(at, rawX, rawY, eventTime))
        }

        fun move(deltaX: Float = 0f, deltaY: Float = 0f, at: FloatArray? = floatArrayOf(10f + deltaX, 20f + deltaY)) {
            effects.clear(); timeline.clear()
            flow.accept(PreviewPointer.Move(at, rawX + deltaX, rawY + deltaY))
        }

        fun up(deltaX: Float = 0f, deltaY: Float = 0f, at: FloatArray? = floatArrayOf(10f + deltaX, 20f + deltaY), eventTime: Long = 0L) {
            effects.clear(); timeline.clear()
            flow.accept(PreviewPointer.Up(at, rawX + deltaX, rawY + deltaY, eventTime))
        }

        fun cancel(at: FloatArray? = floatArrayOf(10f, 20f)) {
            effects.clear(); timeline.clear()
            flow.accept(PreviewPointer.Cancel(at))
        }
    }

    @Test fun `可直传时按下发 down，移动按 8 毫秒合并，抬起补发最后一个点`() {
        val fake = Fake()
        fake.down()
        assertEquals(listOf(PreviewTouchEffect.Stream("down", "s1", 3, 10, 20)), fake.effects)

        fake.realtime = 1_000L
        fake.move(deltaX = 1f)
        assertEquals(listOf(PreviewTouchEffect.Stream("move", "s1", 3, 11, 20)), fake.effects)

        // 4 毫秒内的第二个点只被记下，不投递。
        fake.realtime = 1_004L
        fake.move(deltaX = 2f)
        assertTrue(fake.effects.isEmpty())

        fake.realtime = 1_010L
        fake.move(deltaX = 3f)
        assertEquals(listOf(PreviewTouchEffect.Stream("move", "s1", 3, 13, 20)), fake.effects)

        // 抬起前那一个被合并掉的点必须补发（手指停下的地方才是用户点的地方）。
        fake.realtime = 1_012L
        fake.move(deltaX = 4f)
        assertTrue(fake.effects.isEmpty())
        fake.up(deltaX = 5f)
        assertEquals(
            listOf(
                PreviewTouchEffect.BringToFront,
                PreviewTouchEffect.Click,
                PreviewTouchEffect.Stream("up", "s1", 3, 14, 20),
            ),
            fake.effects,
        )
    }

    @Test fun `不可直传时按下不发事件，抬起退化为一次离散点击`() {
        val fake = Fake().apply { streamable = false }
        fake.down()
        assertTrue(fake.effects.isEmpty())
        fake.move(deltaX = 3f)
        assertTrue(fake.effects.isEmpty())
        fake.up(deltaX = 3f)
        assertEquals(
            listOf(
                PreviewTouchEffect.BringToFront,
                PreviewTouchEffect.Click,
                PreviewTouchEffect.Discrete("s1", 3, swipe = false, startX = 10, startY = 20, endX = 13, endY = 20, durationMs = 0),
            ),
            fake.effects,
        )
        assertEquals(1, fake.claims)
        assertEquals(listOf("s1"), fake.asked)
    }

    @Test fun `位移超过 12 像素算滑动，时长夹到 100 到 2000 毫秒`() {
        val fake = Fake().apply { streamable = false }
        // 5 秒的慢拖：时长被封在 2000 毫秒。
        fake.down(eventTime = 0L)
        fake.up(deltaX = 100f, eventTime = 5_000L)
        assertEquals(
            PreviewTouchEffect.Discrete("s1", 3, swipe = true, startX = 10, startY = 20, endX = 110, endY = 20, durationMs = 2_000),
            fake.effects.last(),
        )
        // 10 毫秒的快扫：时长被抬到 100 毫秒。
        fake.down(eventTime = 0L)
        fake.up(deltaX = 100f, eventTime = 10L)
        assertEquals(
            PreviewTouchEffect.Discrete("s1", 3, swipe = true, startX = 10, startY = 20, endX = 110, endY = 20, durationMs = 100),
            fake.effects.last(),
        )
        // 恰好 12 像素不算滑动（判定是「大于 12」）。
        fake.down()
        fake.up(deltaX = 12f)
        assertEquals(
            PreviewTouchEffect.Discrete("s1", 3, swipe = false, startX = 10, startY = 20, endX = 22, endY = 20, durationMs = 0),
            fake.effects.last(),
        )
    }

    @Test fun `按下时的会话为空时直传回落到当前会话`() {
        val fake = Fake()
        fake.session = ""
        fake.down()
        assertEquals(PreviewTouchEffect.Stream("down", "", 3, 10, 20), fake.effects.single())
        fake.session = "s9"
        fake.realtime = 1_000L
        fake.move(deltaX = 1f)
        assertEquals(PreviewTouchEffect.Stream("move", "s9", 3, 11, 20), fake.effects.single())
    }

    @Test fun `手势取消补发被合并的点，取消之后再抬起什么都不发（除了置前与点击）`() {
        val fake = Fake()
        fake.down()
        fake.realtime = 1_000L
        fake.move(deltaX = 1f)
        fake.realtime = 1_002L
        fake.move(deltaX = 2f)
        assertTrue(fake.effects.isEmpty())

        fake.cancel()
        assertEquals(listOf(PreviewTouchEffect.Stream("cancel", "s1", 3, 12, 20)), fake.effects)

        // 取消已经把按下点与直传都收掉了：抬起的离散闸门不该被问，也不该补发第二次取消。
        fake.up(deltaX = 3f)
        assertEquals(listOf(PreviewTouchEffect.BringToFront, PreviewTouchEffect.Click), fake.effects)
        assertEquals(0, fake.claims)
    }

    @Test fun `清屏之后再抬起不会发出离散点击`() {
        val fake = Fake().apply { streamable = false }
        fake.down()
        fake.flow.clearDownPoint()
        fake.up(deltaX = 1f)
        assertEquals(listOf(PreviewTouchEffect.BringToFront, PreviewTouchEffect.Click), fake.effects)
        assertEquals(0, fake.claims)
    }

    @Test fun `长按够久且几乎没动才改判拖窗，改判时先补 cancel 再更新窗口`() {
        val fake = Fake()
        fake.down()
        assertEquals(listOf(PreviewTouchEffect.Stream("down", "s1", 3, 10, 20)), fake.effects)

        fake.uptime = 350L
        fake.realtime = 1_000L
        fake.move(deltaX = 0f)
        // 交付顺序：先问拖动端口（这一次手势归谁由它的返回值决定），紧接着把目标应用的按下状态收掉，
        // 最后才改窗口位置 —— 副屏那边不能停在被按住的状态里等这次拖动结束。
        assertEquals(listOf("drag:begin", "stream:cancel@10,20", "drag:0,0"), fake.timeline)

        // 之后的移动都归拖动：4 毫秒内只记末位置，10 毫秒时才真的更新一次。
        fake.realtime = 1_004L
        fake.move(deltaX = 5f)
        assertEquals(listOf<String>(), fake.timeline)
        fake.realtime = 1_010L
        fake.move(deltaX = 5f)
        assertEquals(listOf("drag:5,0"), fake.timeline)

        // 抬手补发被节流丢掉的末位置，不再补 up/tap/swipe，也不置前（这次手势归属是窗口位置）。
        fake.up(deltaX = 7f)
        assertEquals(listOf("drag:end:7,0"), fake.timeline)
    }

    @Test fun `按住够久但位移超过静置容差就不改判，手势继续直传`() {
        val fake = Fake()
        fake.down()
        fake.uptime = 500L
        fake.realtime = 1_000L
        // 密度 2 → 容差 8dp = 16 像素；20 像素已经超出，这一次仍是普通触摸。
        fake.move(deltaX = 20f)
        assertEquals(listOf(PreviewTouchEffect.Stream("move", "s1", 3, 30, 20)), fake.effects)
        assertEquals(listOf("stream:move@30,20"), fake.timeline)
    }

    @Test fun `没到长按门槛就不改判，手势继续直传`() {
        val fake = Fake()
        fake.down()
        fake.uptime = 349L
        fake.realtime = 1_000L
        fake.move(deltaX = 0f)
        assertEquals(listOf(PreviewTouchEffect.Stream("move", "s1", 3, 10, 20)), fake.effects)
        assertEquals(listOf("stream:move@10,20"), fake.timeline)
    }

    @Test fun `没有拖动端口时永远不改判（原生预览页）`() {
        val fake = Fake().apply { hostAvailable = false }
        fake.down()
        fake.uptime = 1_000L
        fake.realtime = 1_000L
        fake.move(deltaX = 0f)
        assertEquals(listOf(PreviewTouchEffect.Stream("move", "s1", 3, 10, 20)), fake.effects)
        assertEquals(listOf("stream:move@10,20"), fake.timeline)
    }

    @Test fun `拖动端口说不能拖时手势继续按直传走`() {
        val fake = Fake().apply { host.accept = false }
        fake.down()
        fake.uptime = 350L
        fake.realtime = 1_000L
        fake.move(deltaX = 0f)
        // beginDrag 被问过、也被拒绝了，于是没有 cancel，MOVE 照常直传。
        assertEquals(listOf("drag:begin", "stream:move@10,20"), fake.timeline)
    }

    @Test fun `摘下控件时补发 cancel 用的是被合并的那个点，而且只补一次`() {
        val fake = Fake()
        fake.down()
        fake.realtime = 1_000L
        fake.move(deltaX = 1f)
        fake.realtime = 1_002L
        fake.move(deltaX = 2f)

        assertEquals(PreviewTouchEffect.Stream("cancel", "s1", 3, 12, 20), fake.flow.cancelForDetach())
        assertNull("没有在途手势时不该再补一次", fake.flow.cancelForDetach())
        // 服务侧紧接着还会走 clearFrame()（releaseOnDetach 的最后一步），按下点随之丢掉。
        fake.flow.clearDownPoint()
        fake.up(deltaX = 3f)
        assertEquals(listOf(PreviewTouchEffect.BringToFront, PreviewTouchEffect.Click), fake.effects)
        assertEquals("控件已经摘下，离散闸门不该被问", 0, fake.claims)
    }

    @Test fun `离散闸门拿的是按下时的会话，会话换了就不发`() {
        val fake = Fake().apply { streamable = false }
        fake.session = "a"
        fake.down()
        fake.session = "b"
        fake.up(deltaX = 1f)
        assertEquals(listOf("a"), fake.asked)
        assertEquals(listOf(PreviewTouchEffect.BringToFront, PreviewTouchEffect.Click), fake.effects)
    }

    @Test fun `投递作废判定：代号变了就丢，唯独 cancel 永不作废`() {
        assertTrue(previewTouchDeliveryAllowed("cancel", 1, 2))
        assertFalse(previewTouchDeliveryAllowed("down", 1, 2))
        assertFalse(previewTouchDeliveryAllowed("move", 1, 2))
        assertFalse(previewTouchDeliveryAllowed("up", 1, 2))
        assertTrue(previewTouchDeliveryAllowed("move", 7, 7))
        assertTrue(previewDiscreteDeliveryAllowed(7, 7))
        assertFalse(previewDiscreteDeliveryAllowed(1, 2))
    }

    @Test fun `直传发的是取整后的坐标与当时的代号`() {
        val fake = Fake()
        fake.generation = 11
        fake.down(at = floatArrayOf(10.7f, 20.2f))
        assertEquals(PreviewTouchEffect.Stream("down", "s1", 11, 10, 20), fake.effects.single())
    }
}
