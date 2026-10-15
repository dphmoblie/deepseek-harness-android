package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenPreviewCore] 的逐拍判定：固定帧序列喂进去，动作、状态行与观测状态都必须逐字可预期。
 *
 * 这段判定原先是 [VirtualScreenPreview.tick] 里夹在取帧线程与主线程之间的一小段代码，只能靠真机观察；
 * 抽成纯 Kotlin 之后，用一组固定帧序列（有内容 / 与上帧相同 / 接近纯色 / 读不到帧 / 会话失效）
 * 就能把 0.2.9 那类「真机黑屏」的回归钉在 JVM 上。
 */
class VirtualScreenPreviewCoreTest {
    /** 有内容的帧：亮度极差 100，远超 `VirtualScreenPolicy.PREVIEW_CONTENT_SPREAD`。 */
    private val content = intArrayOf(0, 100, 0, 100)

    /** 接近纯色的帧：亮度极差 2，判成「当前帧接近纯色」，不上屏。 */
    private val flat = intArrayOf(10, 12, 11, 10)

    /** 会话是否仍可观察：真机上是副屏会话被切换或释放，用例里逐拍切换。 */
    private var alive = true

    /** 档位标题真机上是本地化文案；这里直接用档位名，好让断言一眼看出取的是哪一档。 */
    private fun core() = VirtualScreenPreviewCore(sessionAlive = { alive }, modeTitle = { mode -> mode })

    private fun sample(
        sessionId: String = "s-1",
        channel: String = "stream",
        mode: String = "30fps",
        fps: Double = 12.0,
        decoded: Boolean = true,
        samples: IntArray? = null,
        hardwareFrame: Boolean = false,
    ) = PreviewSample(sessionId, channel, mode, fps, decoded, samples, hardwareFrame)

    @Test fun `状态和失效文案每拍使用注入的当前语言`() {
        var language = "en"
        val core = VirtualScreenPreviewCore(
            sessionAlive = { alive },
            modeTitle = { "$language:Battery saver" },
            statusLine = { channel, pause, fps, title, waiting -> "$language:$channel:$pause:$fps:$title:$waiting" },
            goneMessage = { "$language:Waiting for the virtual screen picture" },
        )
        val first = core.accept(sample(decoded = false), 10)
        assertEquals("en:stream:UNREADABLE:12.0:en:Battery saver:true", first.line)
        language = "zh-CN"
        assertEquals("zh-CN:stream:NONE:12.0:zh-CN:Battery saver:false", core.accept(sample(samples = content), 20).line)
        alive = false
        assertEquals("zh-CN:Waiting for the virtual screen picture", core.accept(sample(decoded = false), 30).line)
    }

    @Test fun `固定帧序列的动作与观测状态逐拍可预期`() {
        val core = core()

        // ① 首帧有内容：SHOW，画面时刻前移到本拍，触摸通道与会话一并记下。
        val first = core.accept(sample(samples = content), 1_000)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.SHOW, first.action)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 档位 30fps", first.line)
        assertFalse(first.showWaiting)
        assertTrue(first.refreshObservedAt)
        assertTrue(core.displayed)
        assertEquals(1_000L, core.observedAtMillis)
        assertEquals("s-1", core.observedSession)
        assertEquals("stream", core.observedChannel)

        // ② 与上一帧相同：仍是 SHOW（新帧就是新帧，画面时刻照样前移），但状态行如实说「画面暂无变化」。
        val same = core.accept(sample(samples = content), 1_100)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.SHOW, same.action)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 画面暂无变化 · 档位 30fps", same.line)
        assertEquals(1_100L, core.observedAtMillis)

        // ③ 接近纯色的新帧：KEEP —— 保留最近一张非空白画面，画面时刻不许前移，也不许改口说「等待」。
        val blank = core.accept(sample(samples = flat), 1_200)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, blank.action)
        assertFalse(blank.refreshObservedAt)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 当前帧接近纯色 · 档位 30fps", blank.line)
        assertFalse(blank.showWaiting)
        assertEquals(1_100L, core.observedAtMillis)
        assertTrue("KEEP 绝不清屏", core.displayed)

        // ④ 这一拍读不到帧：KEEP +「未读到新帧」，同样保留画面、不动画面时刻（一次读取失败不等于画面失效）。
        val unreadable = core.accept(sample(decoded = false, samples = null), 1_300)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, unreadable.action)
        assertEquals("副屏预览 · 触摸直传 · 未读到新帧 · 档位 30fps", unreadable.line)
        assertEquals(1_100L, core.observedAtMillis)
        assertTrue(core.displayed)

        // ⑤ 会话不可观察：CLEAR —— 清屏并给出既有提示，观测状态一并归零（这时继续显示旧帧才是假画面）。
        alive = false
        val gone = core.accept(sample(decoded = false, samples = null), 1_400)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.CLEAR, gone.action)
        assertEquals(VirtualScreenPolicy.PREVIEW_GONE_MESSAGE, gone.line)
        assertFalse(core.displayed)
        assertEquals(0L, core.observedAtMillis)
        assertEquals("", core.observedSession)
        // 触摸通道刻意保留：与原来 clearFrame 一致，通道只参与「通道是 stream 且画面新鲜」的直传闸门，
        // 清屏已把画面时刻归零、把闸门关上；多清一个字段反而会改变设备端不报通道时的回落路径。
        assertEquals("stream", core.observedChannel)

        // ⑥ 会话回来后第一帧重新上屏：清屏连「上一帧」采样一起清了，不会拿旧采样冒充「画面暂无变化」。
        alive = true
        val again = core.accept(sample(samples = content), 1_500)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.SHOW, again.action)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 档位 30fps", again.line)
        assertEquals(1_500L, core.observedAtMillis)
    }

    @Test fun `还没有任何画面时状态行如实说等待`() {
        val core = core()

        // 首拍就是接近纯色：没有可显示的画面，要说「等待」并写明在等什么，不能假称「画面暂无变化」。
        val blank = core.accept(sample(samples = flat), 10)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, blank.action)
        assertEquals("副屏预览 · 触摸直传 · 等待副屏画面（当前帧接近纯色） · 档位 30fps", blank.line)
        assertTrue(blank.showWaiting)
        assertFalse(core.displayed)

        // 一直读不到帧：同样说「等待」，并说明是没读到而不是画面空。
        val unreadable = core.accept(sample(decoded = false, samples = null), 20)
        assertEquals("副屏预览 · 触摸直传 · 等待副屏画面（未读到新帧） · 档位 30fps", unreadable.line)
        assertTrue(unreadable.showWaiting)

        // 第一张真画面画上去之后必须立刻改口，不能继续显示「等待副屏画面」。
        val shown = core.accept(sample(samples = content), 30)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 档位 30fps", shown.line)
        assertFalse(shown.showWaiting)
    }

    @Test fun `硬件帧只认解码结果不看采样启发式`() {
        val core = core()

        // 硬件帧没有 CPU 采样（samples 为 null）：解码成功就直接上屏，不能因为「没有采样」把有效帧判成纯色。
        val shown = core.accept(sample(decoded = true, samples = null, hardwareFrame = true), 100)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.SHOW, shown.action)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 档位 30fps", shown.line)
        assertEquals(100L, core.observedAtMillis)

        // 硬件帧解码失败：KEEP +「未读到新帧」，保留上一帧、不清屏，也不会被当成「当前帧接近纯色」。
        val failed = core.accept(sample(decoded = false, samples = null, hardwareFrame = true), 200)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, failed.action)
        assertEquals("副屏预览 · 触摸直传 · 未读到新帧 · 档位 30fps", failed.line)
        assertTrue(core.displayed)
        assertEquals(100L, core.observedAtMillis)
    }

    @Test fun `触摸通道按状态行取值更新且清屏不抹掉通道`() {
        val core = core()

        // 设备端还没报告通道：空串不覆盖已有取值（否则「触摸直传」会被一次空读数打回离散描述）。
        val empty = core.accept(sample(channel = "", samples = content), 1)
        assertFalse(empty.updateObservedChannel)
        assertEquals("", core.observedChannel)
        assertEquals("副屏预览 · 点击或滑动操作 · 12.0 fps · 档位 30fps", empty.line)

        val discrete = core.accept(sample(channel = "discrete", samples = content), 2)
        assertTrue(discrete.updateObservedChannel)
        assertEquals("discrete", core.observedChannel)
        assertEquals("副屏预览 · 点击或滑动操作 · 12.0 fps · 画面暂无变化 · 档位 30fps", discrete.line)

        // KEEP 那一拍也照常更新通道（设备端可能中途改口），状态行跟着改口。
        val kept = core.accept(sample(channel = "stream", samples = flat), 3)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, kept.action)
        assertEquals("stream", core.observedChannel)
        assertEquals("副屏预览 · 触摸直传 · 12.0 fps · 当前帧接近纯色 · 档位 30fps", kept.line)

        // 清屏：这一拍不更新通道（与原来一致，清屏分支本来就不碰通道），已记下的取值也不抹掉。
        alive = false
        val gone = core.accept(sample(channel = "stream", decoded = false, samples = null), 4)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.CLEAR, gone.action)
        assertFalse(gone.updateObservedChannel)
        assertEquals("stream", core.observedChannel)
    }

    @Test fun `画面时刻只在 SHOW 那一拍前移`() {
        val core = core()
        var refreshed = 0
        for (feed in script()) {
            alive = feed.alive
            val before = core.observedAtMillis
            val result = core.accept(feed.sample, feed.nowMillis)
            if (result.refreshObservedAt) {
                refreshed++
                assertEquals("SHOW 才把画面时刻前移到本拍", feed.nowMillis, core.observedAtMillis)
            } else if (result.action == VirtualScreenPolicy.PreviewFrameAction.CLEAR) {
                assertEquals("清屏把画面时刻归零", 0L, core.observedAtMillis)
            } else {
                assertEquals("KEEP 不许动画面时刻", before, core.observedAtMillis)
            }
        }
        assertTrue("这一串里必须真的出现过 SHOW，否则断言是空转的", refreshed >= 3)
    }

    @Test fun `同一序列跑两遍逐字相同`() {
        // 判定必须只由喂进来的事实决定：同一序列跑两遍，动作、状态行与观测状态逐字一致。
        assertEquals(record(), record())
    }

    /** 一次喂入：这一拍的样本、本拍时刻，以及这一拍时会话是否可观察。 */
    private data class Feed(val sample: PreviewSample, val nowMillis: Long, val alive: Boolean)

    /** 固定序列：有内容 → 与上帧相同 → 纯色 → 读不到 → 硬件帧 → 会话失效 → 恢复 → 空通道纯色。 */
    private fun script() = listOf(
        Feed(sample(samples = content), 100, true),
        Feed(sample(samples = content), 200, true),
        Feed(sample(samples = flat), 300, true),
        Feed(sample(decoded = false, samples = null), 400, true),
        Feed(sample(decoded = true, samples = null, hardwareFrame = true), 500, true),
        Feed(sample(decoded = false, samples = null), 600, false),
        Feed(sample(samples = content), 700, true),
        Feed(sample(channel = "", samples = flat), 800, true),
    )

    /** 把固定序列跑一遍，逐拍记下能观察到的一切：两遍的记录必须逐字相同。 */
    private fun record(): List<String> {
        val core = core()
        return script().map { feed ->
            alive = feed.alive
            val result = core.accept(feed.sample, feed.nowMillis)
            "${result.action} | ${result.line} | 前移=${result.refreshObservedAt} 通道=${result.updateObservedChannel}" +
                " 等待=${result.showWaiting} 显示=${core.displayed} 时刻=${core.observedAtMillis}" +
                " 会话=${core.observedSession} 触摸=${core.observedChannel}"
        }
    }
}
