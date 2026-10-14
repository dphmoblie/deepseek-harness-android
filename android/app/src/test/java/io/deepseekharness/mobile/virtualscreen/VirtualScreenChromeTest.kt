package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面芯片的语义测试。
 *
 * View 层没有 Robolectric，[VirtualScreenFrame.installControls] 装不上测试，因此「小窗与预览页各自
 * 有哪些出口、点了会发生什么、顺序如何」这件事全部压在 [VirtualScreenChrome] 这层纯函数上：
 * 这里错了，用户就会在小窗上找不到出口，或者点了「结束」却只是收起。
 */
class VirtualScreenChromeTest {
    @Test fun `小窗给收起与结束两枚出口且顺序固定`() {
        val collapsed = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val controls = VirtualScreenChrome.overlay(onCollapse = { collapsed.add("collapse") }, onStop = { stopped.add("stop") })
        assertEquals(listOf("收起", "结束"), controls.map { it.label })
        controls.forEach { it.onTap() }
        assertEquals(listOf("collapse"), collapsed)
        assertEquals(listOf("stop"), stopped)
    }

    @Test fun `小窗的收起与结束是两件不同的事`() {
        var collapsed = 0
        var stopped = 0
        val controls = VirtualScreenChrome.overlay(onCollapse = { collapsed++ }, onStop = { stopped++ })
        controls.first { it.label == "收起" }.onTap()
        assertEquals(1, collapsed)
        assertEquals(0, stopped)
        controls.first { it.label == "结束" }.onTap()
        assertEquals(1, collapsed)
        assertEquals(1, stopped)
    }

    @Test fun `预览页只给返回一枚出口`() {
        var back = 0
        val controls = VirtualScreenChrome.page(onBack = { back++ })
        assertEquals(listOf("返回"), controls.map { it.label })
        controls.single().onTap()
        assertEquals(1, back)
    }

    @Test fun `每枚芯片都带非空标签以便读屏与点击定位`() {
        val controls = VirtualScreenChrome.overlay(onCollapse = {}, onStop = {}) + VirtualScreenChrome.page(onBack = {})
        assertTrue(controls.all { it.label.isNotBlank() })
        assertEquals(controls.size, controls.map { it.label }.toSet().size)
    }
}
