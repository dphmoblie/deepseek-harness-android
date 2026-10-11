package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class VirtualScreenPreviewLifecycleTest {
    /** 可手动推进的主线程、帧和触摸队列，让重挂稳定发生在异步任务返回之前。 */
    private class PreviewQueues {
        val lifecycle = VirtualScreenPreviewLifecycle()
        val main = ArrayDeque<() -> Unit>()
        val frames = ArrayDeque<() -> Unit>()
        val touches = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        var shown = 0
        var releases = 0

        fun attach() {
            if (lifecycle.attach()) main.add { tick() }
        }

        fun detach(retain: Boolean) {
            if (lifecycle.detach(retain)) {
                main.clear()
                releases++
            }
        }

        private fun tick() {
            val version = lifecycle.generation
            frames.add {
                main.add {
                    if (version == lifecycle.generation) {
                        shown++
                        main.add { tick() }
                    }
                }
            }
        }

        fun touch(phase: String) {
            val version = lifecycle.generation
            touches.add {
                if (version == lifecycle.generation || phase == "cancel") delivered.add(phase)
            }
        }

        fun reattach() {
            detach(retain = true)
            attach()
        }
    }

    @Test fun `重挂时正在取帧的任务仍能上屏并续排下一帧`() {
        val preview = PreviewQueues()
        preview.attach()
        preview.main.removeFirst().invoke() // tick 已经进入帧线程，尚未返回主线程。
        preview.reattach()
        assertTrue(preview.main.isEmpty()) // 不额外启动第二条取帧循环。
        preview.frames.removeFirst().invoke()
        preview.main.removeFirst().invoke()
        assertEquals(1, preview.shown)
        assertEquals(1, preview.main.size)
        preview.main.removeFirst().invoke()
        preview.frames.removeFirst().invoke()
        preview.main.removeFirst().invoke()
        assertEquals(2, preview.shown)
        assertEquals(0, preview.releases)
    }

    @Test fun `等待下一拍时连续重挂不复制调度`() {
        val preview = PreviewQueues()
        preview.attach()
        repeat(3) { preview.reattach() }
        assertEquals(1, preview.main.size)
        preview.main.removeFirst().invoke()
        assertEquals(1, preview.frames.size)
    }

    @Test fun `已经按下后重挂仍传递排队中的抬起以及离散点击`() {
        val preview = PreviewQueues()
        preview.attach()
        preview.touch("down")
        preview.touches.removeFirst().invoke()
        preview.touch("up")
        preview.reattach() // 主线程重挂先于触摸线程处理 UP。
        preview.touches.removeFirst().invoke()
        preview.touch("tap")
        preview.reattach()
        preview.touches.removeFirst().invoke()
        assertEquals(listOf("down", "up", "tap"), preview.delivered)
    }

    @Test fun `真正卸载作废在途帧与输入但保留取消并可重新挂载`() {
        val preview = PreviewQueues()
        preview.attach()
        preview.main.removeFirst().invoke()
        preview.touch("up")
        preview.touch("cancel")
        preview.detach(retain = false)
        preview.attach()
        preview.frames.removeFirst().invoke()
        preview.touches.removeFirst().invoke()
        preview.touches.removeFirst().invoke()
        preview.main.removeFirst().invoke() // 新一轮首帧。
        preview.main.removeFirst().invoke() // 旧帧返回，必须丢弃。
        assertEquals(0, preview.shown)
        assertEquals(listOf("cancel"), preview.delivered)
        assertEquals(1, preview.frames.size)
        assertEquals(1, preview.releases)
    }

    @Test fun `重挂失败可显式释放而重复释放无副作用`() {
        val lifecycle = VirtualScreenPreviewLifecycle()
        assertTrue(lifecycle.attach())
        val original = lifecycle.generation
        assertFalse(lifecycle.detach(retain = true))
        assertTrue(lifecycle.detach(retain = false))
        assertEquals(original + 1, lifecycle.generation)
        assertFalse(lifecycle.detach(retain = false))
        assertEquals(original + 1, lifecycle.generation)
        assertTrue(lifecycle.attach())
    }
}
