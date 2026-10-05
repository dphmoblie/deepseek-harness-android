package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览取帧的处置契约（真机缺陷：小窗悬浮之后一块全黑，且没有任何说明）。
 *
 * 用纯函数 [VirtualScreenPolicy.previewOutcome] 驱动一份与 `VirtualScreenPreview.tick` 同构的
 * 最小模型：只有 SHOW 才更新画面，KEEP 一律保留最后一帧**可显示**的画面（接近纯色的新帧与读取失败
 * 都不许上屏，也不许清屏），只有会话确实不可观察（CLEAR）才清屏；状态行必须如实标注
 * 「画面暂无变化／当前帧接近纯色／未读到新帧」，并且在还没有任何画面时老实说「等待副屏画面」。
 */
class VirtualScreenPreviewTest {
    /** 一拍决策的表驱动用例。 */
    private data class Decision(
        val alive: Boolean,
        val decoded: Boolean,
        val blank: Boolean,
        val changed: Boolean,
        val action: VirtualScreenPolicy.PreviewFrameAction,
        val pause: VirtualScreenPolicy.PreviewPause,
        val label: String,
    )

    /**
     * 与预览组件同构的画面账本：`displayed` 等价于组件里的 `displayed` / `setImageBitmap` 结果。
     * 只有 SHOW 才更新画面并前移画面时刻，KEEP 保留旧帧（也不前移画面时刻），CLEAR 才清屏。
     * 状态行里的「等待」按读数的那一刻算：SHOW 画上画面之后就不再是等待。
     */
    private class PreviewModel {
        var displayed: String? = null
        var observedAt = 0L
        var status = ""
        private var now = 0L

        fun tick(outcome: VirtualScreenPolicy.PreviewOutcome, frame: String?, fps: Double = 0.0): String? {
            now += 100
            when (outcome.action) {
                VirtualScreenPolicy.PreviewFrameAction.SHOW -> {
                    if (frame != null) { displayed = frame; observedAt = now }
                    status = line(outcome.pause, fps)
                }
                VirtualScreenPolicy.PreviewFrameAction.KEEP -> status = line(outcome.pause, fps)
                VirtualScreenPolicy.PreviewFrameAction.CLEAR -> {
                    displayed = null; observedAt = 0
                    status = VirtualScreenPolicy.PREVIEW_GONE_MESSAGE
                }
            }
            return displayed
        }

        private fun line(pause: VirtualScreenPolicy.PreviewPause, fps: Double): String =
            VirtualScreenPolicy.previewStatusLine("stream", pause, fps, "limited-fps", displayed == null)
    }

    private fun outcomeOf(alive: Boolean, decoded: Boolean, blank: Boolean, changed: Boolean) =
        VirtualScreenPolicy.previewOutcome(alive, decoded, blank, changed)

    @Test fun `处置规则表驱动`() {
        val cases = listOf(
            // 有内容且与上一帧不同：立刻显示。
            Decision(true, true, false, true, VirtualScreenPolicy.PreviewFrameAction.SHOW, VirtualScreenPolicy.PreviewPause.NONE, "有内容的新画面"),
            // 有内容但与上一帧一样：仍然显示（新帧就是新帧），只是状态行如实说「暂无变化」。
            Decision(true, true, false, false, VirtualScreenPolicy.PreviewFrameAction.SHOW, VirtualScreenPolicy.PreviewPause.UNCHANGED, "画面没变仍是新帧"),
            // 接近纯色的帧：不许当成有效画面显示，保留上一张并说明原因。
            Decision(true, true, true, false, VirtualScreenPolicy.PreviewFrameAction.KEEP, VirtualScreenPolicy.PreviewPause.BLANK, "纯色帧保留旧帧"),
            Decision(true, true, true, true, VirtualScreenPolicy.PreviewFrameAction.KEEP, VirtualScreenPolicy.PreviewPause.BLANK, "首帧就是纯色也不显示"),
            // 这一拍读不到帧（动作繁忙、锁屏等）：同样保留，一次失败不等于画面失效。
            Decision(true, false, false, true, VirtualScreenPolicy.PreviewFrameAction.KEEP, VirtualScreenPolicy.PreviewPause.UNREADABLE, "读取失败保留旧帧"),
            Decision(true, false, true, false, VirtualScreenPolicy.PreviewFrameAction.KEEP, VirtualScreenPolicy.PreviewPause.UNREADABLE, "读取失败且判定未知时仍保留"),
            // 会话确实不可观察：这时保留旧帧才是假画面。
            Decision(false, true, false, true, VirtualScreenPolicy.PreviewFrameAction.CLEAR, VirtualScreenPolicy.PreviewPause.UNREADABLE, "会话切换或结束才清屏"),
            Decision(false, false, true, false, VirtualScreenPolicy.PreviewFrameAction.CLEAR, VirtualScreenPolicy.PreviewPause.UNREADABLE, "会话不可观察优先于画面判定"),
        )
        for (case in cases) {
            val outcome = outcomeOf(case.alive, case.decoded, case.blank, case.changed)
            assertEquals(case.label, case.action, outcome.action)
            assertEquals(case.label, case.pause, outcome.pause)
        }
    }

    @Test fun `纯色帧不上屏且如实标注当前帧接近纯色`() {
        val model = PreviewModel()
        assertEquals("第一帧", model.tick(outcomeOf(alive = true, decoded = true, blank = false, changed = true), "第一帧", fps = 5.0))
        val frameAtBefore = model.observedAt
        // 连续三拍都是接近纯色的帧：画面原地保留，状态行写明原因，绝不改口成「暂无变化」。
        repeat(3) {
            assertEquals("第一帧", model.tick(outcomeOf(true, true, true, false), null, fps = 5.0))
            assertEquals("副屏预览 · 触摸直传 · 5.0 fps · 当前帧接近纯色 · 档位 limited-fps", model.status)
        }
        // 保留旧帧不等于假装刚更新过：画面时刻不能前移，触摸与「画面新鲜」判定沿用真正的最后一帧。
        assertEquals(frameAtBefore, model.observedAt)
        assertTrue(model.observedAt > 0L)
    }

    @Test fun `画面没有变化时继续显示并如实标注`() {
        val model = PreviewModel()
        model.tick(outcomeOf(true, true, false, true), "第一帧", fps = 12.0)
        val frameAtBefore = model.observedAt
        // 与上一帧一致：这仍是刚取到的新帧，照常上屏并前移画面时刻，但状态行不许冒充「有了新画面」。
        assertEquals("第一帧", model.tick(outcomeOf(true, true, false, false), "第一帧", fps = 12.0))
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 画面暂无变化 · 档位 limited-fps", model.status)
        assertTrue(model.observedAt > frameAtBefore)
        assertFalse(model.status.contains("复用"))
    }

    @Test fun `读取失败保留最后一帧，会话失效才清屏`() {
        val model = PreviewModel()
        model.tick(outcomeOf(true, true, false, true), "第一帧", fps = 8.0)
        assertEquals("第一帧", model.tick(outcomeOf(true, false, false, true), null, fps = 8.0))
        assertEquals("副屏预览 · 触摸直传 · 未读到新帧 · 档位 limited-fps", model.status)
        // 会话切换或结束：清屏并给出既有提示，不继续展示属于上一个会话的画面。
        assertNull(model.tick(outcomeOf(false, false, true, false), null))
        assertEquals(VirtualScreenPolicy.PREVIEW_GONE_MESSAGE, model.status)
        assertEquals(0L, model.observedAt)
    }

    @Test fun `还没有任何画面时状态行如实说等待`() {
        val model = PreviewModel()
        // 首帧就接近纯色：没有可显示的画面，要说「等待」，不能说「画面暂无变化」。
        assertNull(model.tick(outcomeOf(true, true, true, true), null))
        assertEquals("副屏预览 · 触摸直传 · 等待副屏画面（当前帧接近纯色） · 档位 limited-fps", model.status)
        // 一直读不到帧：同样说「等待」，并说明是没读到而不是画面空。
        assertNull(model.tick(outcomeOf(true, false, false, true), null))
        assertEquals("副屏预览 · 触摸直传 · 等待副屏画面（未读到新帧） · 档位 limited-fps", model.status)
        // 第一张真画面画上去之后，状态行必须立刻改口（不能继续显示「等待副屏画面」）。
        assertEquals("第一帧", model.tick(outcomeOf(true, true, false, true), "第一帧", fps = 9.0))
        assertEquals("副屏预览 · 触摸直传 · 9.0 fps · 档位 limited-fps", model.status)
        assertFalse(model.status.contains("等待"))
    }

    @Test fun `状态行按画面状态取词且不复述已关闭的复用行为`() {
        // 有内容时报实测帧率；帧率还没测出来时老实说「帧率待测」，不写 0.0 fps。
        assertEquals("3.5 fps", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.NONE, 3.46))
        assertEquals("帧率待测", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.NONE, 0.0))
        // 画面没变、接近纯色、读不到帧：三种情形是三条不同的文案，不能混。
        assertEquals("12.0 fps · 画面暂无变化", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.UNCHANGED, 12.0))
        assertEquals("12.0 fps · 当前帧接近纯色", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.BLANK, 12.0))
        assertEquals("未读到新帧", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.UNREADABLE, 12.0))
        assertEquals("等待副屏画面", VirtualScreenPolicy.previewFrameLabel(VirtualScreenPolicy.PreviewPause.NONE, 0.0, waiting = true))
        val unchanged = VirtualScreenPolicy.previewStatusLine("stream", VirtualScreenPolicy.PreviewPause.UNCHANGED, 12.0, "realtime-30fps")
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 画面暂无变化 · 档位 realtime-30fps", unchanged)
        assertFalse(unchanged.contains("复用"))
        assertFalse(unchanged.contains("静止"))
        // 触摸通道与状态字段 touchChannel 同一套取值：stream 之外的取值按离散回退描述。
        assertEquals("副屏预览 · 点击或滑动操作 · 帧率待测 · 档位 limited-fps",
            VirtualScreenPolicy.previewStatusLine("discrete", VirtualScreenPolicy.PreviewPause.NONE, 0.0, "limited-fps"))
    }
}
