package io.deepseekharness.mobile.virtualscreen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏尺寸规格层（`VirtualScreenSizing.kt`）的单测。
 *
 * 这里覆盖三件在真机上很难手工复现、但一旦写错就会静默降级的事情：
 * 1. 两个既有预设的像素与 dpi 必须逐字不变（改了会让真机上已经跑通的尺寸悄悄变掉）；
 * 2. Intent 编解码的向后兼容（旧页面只发 `landscape` 布尔时仍要得到横屏）；
 * 3. 小窗比例换算的边界（极端长宽比、屏幕上限、取整后的 1 像素误差）。
 *
 * 全部是纯算术与 JVM 可测的 Android 类型，不需要设备。真机观感（黑边是否真的消失）未在此验证。
 */
class VirtualScreenSizingTest {

    // ---------------------------------------------------------------- 预设数值

    @Test
    fun `竖屏预设保持 726x1600 与 320 dpi 不变`() {
        val spec = VirtualScreenSpec.PORTRAIT
        assertEquals(726, spec.widthPx)
        assertEquals(1600, spec.heightPx)
        assertEquals(320, spec.densityDpi)
    }

    @Test
    fun `横屏预设保持 1280x580 与 256 dpi 不变`() {
        val spec = VirtualScreenSpec.LANDSCAPE
        assertEquals(1280, spec.widthPx)
        assertEquals(580, spec.heightPx)
        assertEquals(256, spec.densityDpi)
    }

    @Test
    fun `预设按方向布尔选取且与改造前一致`() {
        assertEquals(VirtualScreenSpec.LANDSCAPE, VirtualScreenSpec.preset(true))
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.preset(false))
        assertEquals(VirtualScreenSpec.LANDSCAPE, VirtualScreenSpec.of(true))
    }

    @Test
    fun `两个预设仍然通过启动前的尺寸闸门`() {
        // VirtualScreenPolicy.dimensions 是 ShellVirtualScreen 启动副屏前的硬校验，
        // 规格层夹取后的值必须仍然过得了它，否则用户会看到「副屏尺寸超出范围」。
        VirtualScreenPolicy.dimensions(VirtualScreenSpec.PORTRAIT.widthPx, VirtualScreenSpec.PORTRAIT.heightPx, VirtualScreenSpec.PORTRAIT.densityDpi)
        VirtualScreenPolicy.dimensions(VirtualScreenSpec.LANDSCAPE.widthPx, VirtualScreenSpec.LANDSCAPE.heightPx, VirtualScreenSpec.LANDSCAPE.densityDpi)
    }

    @Test
    fun `预设换算成 dp 与页面文案一致`() {
        // 改造前页面写死「363 × 800 dp」与「800 × 363 dp」，页面文案现在由这两个数字现算。
        val spec = VirtualScreenSpec.PORTRAIT
        assertEquals(363, spec.dpWidth(2f))
        assertEquals(800, spec.dpHeight(2f))
        val landscape = VirtualScreenSpec.LANDSCAPE
        assertEquals(640, landscape.dpWidth(2f))
        assertEquals(290, landscape.dpHeight(2f))
    }

    @Test
    fun `规格标签同时给出像素与 dpi`() {
        assertEquals("726 × 1600 像素 / 320 dpi", VirtualScreenSpec.PORTRAIT.label())
        assertEquals("1280 × 580 像素 / 256 dpi", VirtualScreenSpec.LANDSCAPE.label())
    }

    @Test
    fun `规格比例按像素宽高计算`() {
        assertEquals(726.0 / 1600.0, VirtualScreenSpec.PORTRAIT.aspectRatio, 1e-12)
        assertTrue(VirtualScreenSpec.LANDSCAPE.aspectRatio > 1.0)
    }

    // ---------------------------------------------------------------- 夹取

    @Test
    fun `夹取把越界边长与 dpi 收到上下限内`() {
        val spec = VirtualScreenSpec.normalize(0, 99_999, 10)
        assertEquals(VirtualScreenSpec.MIN_EDGE, spec.widthPx)
        assertEquals(VirtualScreenSpec.MAX_EDGE, spec.heightPx)
        assertEquals(VirtualScreenSpec.MIN_DPI, spec.densityDpi)
    }

    @Test
    fun `夹取把负数与超大 dpi 收到上下限内`() {
        val spec = VirtualScreenSpec.normalize(-40, -1, 5_000)
        assertEquals(VirtualScreenSpec.MIN_EDGE, spec.widthPx)
        assertEquals(VirtualScreenSpec.MIN_EDGE, spec.heightPx)
        assertEquals(VirtualScreenSpec.MAX_DPI, spec.densityDpi)
    }

    @Test
    fun `合法值经过夹取保持不变`() {
        val spec = VirtualScreenSpec.normalize(726, 1600, 320)
        assertEquals(VirtualScreenSpec.PORTRAIT, spec)
    }

    @Test
    fun `夹取不抛异常`() {
        // 这是规格层与 VirtualScreenPolicy.dimensions 的分工：策略层负责拒绝，规格层负责给出可用值。
        VirtualScreenSpec.normalize(Int.MIN_VALUE, Int.MAX_VALUE, 0)
        VirtualScreenSpec.normalize(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
    }

    // ---------------------------------------------------------------- Intent 编解码规则
    //
    // 注意：本模块的 JVM 单测用的是 mockable android.jar（`:app:testDebugUnitTest`），
    // `Intent.getIntExtra/putExtra` 在这里是 not mocked 的空壳，调用会抛
    // "Method putExtra in android.content.Intent not mocked"。所以单测覆盖的是取值规则本身
    // （`VirtualScreenSpec.fromFields`），`decode(Intent)` / `putVirtualScreenSpec(Intent)`
    // 这两个薄封装只在真机路径上生效，**未在单测里跑到，也未在真机验证**。
    // 它们用的 extra 名由下面的 `Intent 字段名与宿主状态字段名必须保持不变` 这条测试钉死。

    private fun fields(landscape: Boolean = false, width: Int = 0, height: Int = 0, dpi: Int = 0) =
        VirtualScreenSpec.fromFields(width, height, dpi, landscape)

    @Test
    fun `写进 Intent 的规格能原样读回`() {
        val portrait = VirtualScreenSpec.PORTRAIT
        assertEquals(portrait, fields(width = portrait.widthPx, height = portrait.heightPx, dpi = portrait.densityDpi))
        val landscape = VirtualScreenSpec.LANDSCAPE
        assertEquals(landscape, fields(landscape = true, width = landscape.widthPx, height = landscape.heightPx, dpi = landscape.densityDpi))
    }

    @Test
    fun `自定义规格能原样读回`() {
        assertEquals(VirtualScreenSpec(900, 1600, 300), fields(width = 900, height = 1600, dpi = 300))
    }

    @Test
    fun `旧版 Intent 只有 landscape 布尔时仍按原样工作`() {
        // 三个整型字段都读不到 ⇒ 全是 0 ⇒ 回退分支；这正是旧版 Intent 在新服务上的样子。
        assertEquals(VirtualScreenSpec.PORTRAIT, fields(landscape = false))
        assertEquals(VirtualScreenSpec.LANDSCAPE, fields(landscape = true))
    }

    @Test
    fun `缺任意一项都整体回退到方向预设`() {
        // 三个字段必须齐全：只要有一个是 0（读不到），就不拿剩下两个凑一份规格。
        val width = VirtualScreenSpec.LANDSCAPE.widthPx
        val height = VirtualScreenSpec.LANDSCAPE.heightPx
        val dpi = VirtualScreenSpec.LANDSCAPE.densityDpi
        assertEquals(VirtualScreenSpec.LANDSCAPE, fields(landscape = true, width = 0, height = height, dpi = dpi))
        assertEquals(VirtualScreenSpec.LANDSCAPE, fields(landscape = true, width = width, height = 0, dpi = dpi))
        assertEquals(VirtualScreenSpec.LANDSCAPE, fields(landscape = true, width = width, height = height, dpi = 0))
    }

    @Test
    fun `越界或零值的 extra 一律回退到方向预设而不是夹取`() {
        // 越界说明这次跨进程投递本身不可信，此时换一份「恰好能用」的尺寸要比按不可信输入建副屏安全。
        val portrait = VirtualScreenSpec.PORTRAIT
        val cases = listOf(
            fields(width = 0, height = portrait.heightPx, dpi = portrait.densityDpi),
            fields(width = -1, height = portrait.heightPx, dpi = portrait.densityDpi),
            fields(width = VirtualScreenSpec.MAX_EDGE + 1, height = portrait.heightPx, dpi = portrait.densityDpi),
            fields(width = VirtualScreenSpec.MIN_EDGE - 1, height = portrait.heightPx, dpi = portrait.densityDpi),
            fields(width = portrait.widthPx, height = 100, dpi = portrait.densityDpi),
            fields(width = portrait.widthPx, height = Int.MAX_VALUE, dpi = portrait.densityDpi),
            fields(width = portrait.widthPx, height = portrait.heightPx, dpi = 0),
            fields(width = portrait.widthPx, height = portrait.heightPx, dpi = 100),
            fields(width = portrait.widthPx, height = portrait.heightPx, dpi = 1_000)
        )
        for (spec in cases) {
            assertEquals(VirtualScreenSpec.PORTRAIT, spec)
        }
    }

    @Test
    fun `越界宽度搭配 landscape 为真时回退横屏预设`() {
        val landscape = VirtualScreenSpec.LANDSCAPE
        assertEquals(
            VirtualScreenSpec.LANDSCAPE,
            fields(landscape = true, width = 99_999, height = landscape.heightPx, dpi = landscape.densityDpi),
        )
    }

    @Test
    fun `边界值本身是合法的`() {
        assertEquals(
            VirtualScreenSpec(VirtualScreenSpec.MIN_EDGE, VirtualScreenSpec.MIN_EDGE, VirtualScreenSpec.MIN_DPI),
            fields(width = VirtualScreenSpec.MIN_EDGE, height = VirtualScreenSpec.MIN_EDGE, dpi = VirtualScreenSpec.MIN_DPI),
        )
        assertEquals(
            VirtualScreenSpec(VirtualScreenSpec.MAX_EDGE, VirtualScreenSpec.MAX_EDGE, VirtualScreenSpec.MAX_DPI),
            fields(width = VirtualScreenSpec.MAX_EDGE, height = VirtualScreenSpec.MAX_EDGE, dpi = VirtualScreenSpec.MAX_DPI),
        )
    }

    @Test
    fun `Intent 字段名与宿主状态字段名必须保持不变`() {
        // 这几个名字是跨版本、跨进程的约定：改了会让旧版页面/旧版读数与新服务对不上，
        // 而且是静默失效（读不到就回退预设），单测里把字面量钉死。
        assertEquals("widthPx", VirtualScreenSpec.EXTRA_WIDTH)
        assertEquals("heightPx", VirtualScreenSpec.EXTRA_HEIGHT)
        assertEquals("densityDpi", VirtualScreenSpec.EXTRA_DPI)
        assertEquals("landscape", VirtualScreenSpec.EXTRA_LANDSCAPE)
        assertEquals("width", VirtualScreenSpec.STATE_WIDTH)
        assertEquals("height", VirtualScreenSpec.STATE_HEIGHT)
    }

    // ---------------------------------------------------------------- 宿主状态解码

    @Test
    fun `从宿主状态读到本次会话的宽高`() {
        val state = JSONObject().put("width", 1280).put("height", 580)
        val spec = VirtualScreenSpec.decodeState(state)
        assertEquals(1280, spec.widthPx)
        assertEquals(580, spec.heightPx)
    }

    @Test
    fun `宿主状态缺失或非法时回退竖屏预设`() {
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.decodeState(null))
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.decodeState(JSONObject()))
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.decodeState(JSONObject().put("width", 0).put("height", 1600)))
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.decodeState(JSONObject().put("width", 726).put("height", 99_999)))
        assertEquals(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.decodeState(JSONObject().put("width", "726").put("height", 1600)))
    }

    @Test
    fun `宿主状态里的宽高决定小窗比例`() {
        val state = JSONObject().put("width", 1280).put("height", 580)
        assertEquals(1280.0 / 580.0, VirtualScreenSpec.decodeState(state).aspectRatio, 1e-12)
        // 状态里没有 dpi，凑齐规格对象时用竖屏预设的 dpi；小窗几何只吃宽高，不读这个值。
        assertEquals(VirtualScreenSpec.PORTRAIT.densityDpi, VirtualScreenSpec.decodeState(state).densityDpi)
    }

    // ---------------------------------------------------------------- 比例换算

    private fun assertAspect(expected: Double, width: Int, height: Int, tolerance: Double = 0.01) {
        assertEquals(expected, width.toDouble() / height.toDouble(), tolerance)
    }

    @Test
    fun `按比例换算在竖屏副屏上与理论值一致`() {
        val (w, h) = VirtualScreenWindow.fitToAspect(320, 1000, VirtualScreenSpec.PORTRAIT.aspectRatio)
        assertEquals(320, w)
        assertEquals(705, h) // 320 / (726/1600) = 705.2 → 取整 705
        assertAspect(VirtualScreenSpec.PORTRAIT.aspectRatio, w, h)
    }

    @Test
    fun `按比例换算在横屏副屏上宽不变而高很小`() {
        val (w, h) = VirtualScreenWindow.fitToAspect(320, 1000, VirtualScreenSpec.LANDSCAPE.aspectRatio)
        assertEquals(320, w)
        assertEquals(145, h) // 320 / (1280/580) = 145.0
        assertAspect(VirtualScreenSpec.LANDSCAPE.aspectRatio, w, h)
    }

    @Test
    fun `极端长条比例改成按高度顶满`() {
        val (w, h) = VirtualScreenWindow.fitToAspect(320, 1000, 0.05)
        assertEquals(1000, h)
        assertEquals(50, w)
        assertAspect(0.05, w, h)
    }

    @Test
    fun `极端扁宽比例保持按宽度顶满`() {
        val (w, h) = VirtualScreenWindow.fitToAspect(320, 1000, 20.0)
        assertEquals(320, w)
        assertEquals(16, h)
        assertAspect(20.0, w, h)
    }

    @Test
    fun `高度上限只有 1 像素时不出现 0 尺寸`() {
        val (w, h) = VirtualScreenWindow.fitToAspect(320, 1, VirtualScreenSpec.PORTRAIT.aspectRatio)
        assertEquals(1, h)
        assertEquals(1, w)
    }

    @Test
    fun `非法的比例与尺寸不产生 0 或负值`() {
        for (aspect in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val (w, h) = VirtualScreenWindow.fitToAspect(0, -5, aspect)
            assertTrue("宽必须为正：$w", w >= 1)
            assertTrue("高必须为正：$h", h >= 1)
        }
    }

    // ---------------------------------------------------------------- 小窗尺寸

    @Test
    fun `典型竖屏手机上小窗不超上限且画面比例等于副屏比例`() {
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.PORTRAIT, 1080, 2400, 3f)
        // 面板宽 960（= 320dp × 3），高上限 1440（= 480dp × 3，比屏高 70% 的 1680 更紧）。
        // 竖屏比例 726:1600 很窄：按宽度顶满要 960×2116，远超扣掉预算后的 1116 可用高度，
        // 所以反过来按高度顶满，得到 506×1116 的画面（比例 0.4534，与 0.4538 的差异只来自取整）。
        assertEquals(960, size.widthPx)
        assertEquals(1440, size.heightPx)
        assertEquals(506, size.contentWidthPx)
        assertEquals(1116, size.contentHeightPx)
        assertTrue("宽不超 90% 屏宽", size.widthPx <= (1080 * 0.9f).toInt())
        assertTrue("高不超 70% 屏高", size.heightPx <= (2400 * 0.7f).toInt())
        // 这条就是「消黑边」的判据：画面区宽高比必须等于副屏宽高比，否则预览的 FIT_CENTER 会留边。
        assertEquals("画面宽高比等于副屏宽高比", VirtualScreenSpec.PORTRAIT.aspectRatio,
            size.contentWidthPx.toDouble() / size.contentHeightPx.toDouble(), 0.01)
        assertTrue("画面高加预算不超过总高", size.contentHeightPx + (108 * 3) <= size.heightPx)
    }

    @Test
    fun `典型竖屏手机上小窗宽度等于标称宽度`() {
        // 1080 屏的 90% 是 972 > 320dp*3 = 960，所以宽度取标称值，不会被屏幕上限压缩。
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.PORTRAIT, 1080, 2400, 3f)
        assertEquals(960, size.widthPx)
    }

    @Test
    fun `横屏副屏在宽屏上变成矮条且仍满足比例`() {
        // 2000×1520 的机器上高上限 1064（= 70% 屏高），比 480dp × 3 = 1440 更紧。
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.LANDSCAPE, 2000, 1520, 3f)
        // 横屏比例 1280:580 = 2.207 更扁：960 宽只对应 435 高，加上 324 的预算总高才 759，
        // 远不到高上限，所以是「按宽度顶满」，小窗自然成了一条矮条。
        assertEquals(960, size.widthPx)
        assertEquals(759, size.heightPx)
        assertEquals(960, size.contentWidthPx)
        assertEquals(435, size.contentHeightPx)
        assertTrue(size.widthPx <= (2000 * 0.9f).toInt())
        assertTrue(size.heightPx <= (1520 * 0.7f).toInt())
        assertEquals(VirtualScreenSpec.LANDSCAPE.aspectRatio,
            size.contentWidthPx.toDouble() / size.contentHeightPx.toDouble(), 0.01)
    }

    @Test
    fun `面板宽度必须等于画面宽度`() {
        // 消黑边的关键不变量：画面区**在还有高度可分配时**必须按面板宽度顶满（等于面板宽度）。
        // 面板比方框宽时画面才可能比副屏更宽，预览的 FIT_CENTER 一定会在上下留黑边（改造前的表现）。
        for (spec in listOf(VirtualScreenSpec.PORTRAIT, VirtualScreenSpec.LANDSCAPE)) {
            for (screenWidth in listOf(300, 400, 720, 1080, 2000, 3200)) {
                for (screenHeight in listOf(600, 800, 1520, 2400, 3200)) {
                    for (density in listOf(1f, 1.5f, 2f, 3f, 4f)) {
                        val size = VirtualScreenWindow.overlaySize(spec, screenWidth, screenHeight, density)
                        // 可用高度是否够放下「按面板宽度顶满」的画面：够就必须顶满，不够才会被高度收窄。
                        val byWidth = (size.widthPx / spec.aspectRatio).toInt()
                        if (byWidth <= size.contentHeightPx + 1) {
                            assertEquals(
                                "屏 ${screenWidth}x$screenHeight / 密度 $density / 比例 ${spec.aspectRatio}",
                                size.widthPx, size.contentWidthPx,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `极窄屏幕上方可画布的副屏按下限得到可用小窗`() {
        // 屏幕高只有 400px：70% 是 280，宽度也被屏宽 90% 压到 320，扣掉 108 的预算后画面按高度顶满。
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.PORTRAIT, 400, 400, 1f)
        assertEquals(320, size.widthPx)
        assertEquals(280, size.heightPx)
        assertEquals(172, size.contentHeightPx)
        assertTrue("画面高度不小于 1", size.contentHeightPx >= 1)
        assertTrue("画面高度不超过小窗高度", size.contentHeightPx <= size.heightPx)
    }

    @Test
    fun `小窗高度始终不超过屏幕高度上限`() {
        for (screenHeight in listOf(300, 480, 800, 1520, 2400, 3200)) {
            for (density in listOf(1f, 1.5f, 2f, 3f, 4f)) {
                val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.PORTRAIT, 1080, screenHeight, density)
                val heightCap = maxOf(1, (screenHeight * VirtualScreenWindow.MAX_SCREEN_HEIGHT_RATIO).toInt())
                val widthCap = maxOf(1, (1080 * VirtualScreenWindow.MAX_SCREEN_WIDTH_RATIO).toInt())
                assertTrue("屏高 $screenHeight / 密度 $density：${size.heightPx}", size.heightPx <= heightCap)
                assertTrue("屏高 $screenHeight / 密度 $density 宽度", size.widthPx <= widthCap)
                assertTrue("画面必须为正", size.contentWidthPx >= 1 && size.contentHeightPx >= 1)
            }
        }
    }

    @Test
    fun `非常小的屏幕也不会算出 0 尺寸的小窗`() {
        val size = VirtualScreenWindow.overlaySize(VirtualScreenSpec.PORTRAIT, 1, 1, 0.5f)
        assertTrue(size.widthPx >= 1)
        assertTrue(size.heightPx >= 1)
        assertTrue(size.contentWidthPx >= 1)
        assertTrue(size.contentHeightPx >= 1)
    }

    @Test
    fun `屏幕上限比例与改造前逐字一致`() {
        assertEquals(0.9, VirtualScreenWindow.MAX_SCREEN_WIDTH_RATIO, 1e-12)
        assertEquals(0.7, VirtualScreenWindow.MAX_SCREEN_HEIGHT_RATIO, 1e-12)
        assertEquals(320, VirtualScreenWindow.NOMINAL_WIDTH_DP)
        assertEquals(480, VirtualScreenWindow.NOMINAL_HEIGHT_DP)
    }

    // ---------------------------------------------------------------- 预览管线与前景层级

    @Test
    fun `页面大预览与悬浮小窗共用同一套取帧判定`() {
        // 页面与小窗用的是同一个 VirtualScreenPreview 控件类，取帧结论都走 previewOutcome，
        // 状态行文字都走 previewLine。这条测试钉住「两个入口不同文案」不会被各自拼串实现：
        // 同一组输入必须得到逐字相同的状态行，而画面是否清屏由 previewOutcome 唯一决定。
        val cases = listOf(
            // sessionAlive, decoded, blank, changed
            listOf(true, true, false, true),
            listOf(true, true, false, false),
            listOf(true, true, true, false),
            listOf(true, false, false, false),
            listOf(false, false, false, false),
        )
        for (case in cases) {
            val outcome = VirtualScreenPolicy.previewOutcome(case[0], case[1], case[2], case[3])
            val pageLine = VirtualScreenPolicy.previewLine("stream", outcome.pause, 30.0, "30fps", false)
            val overlayLine = VirtualScreenPolicy.previewLine("stream", outcome.pause, 30.0, "30fps", false)
            assertEquals("同一组取帧结论必须得到同一行状态文字", pageLine, overlayLine)
            assertEquals(
                VirtualScreenPolicy.previewStatusLine("stream", outcome.pause, 30.0, "30fps", false),
                pageLine,
            )
        }
    }

    @Test
    fun `接近纯色的帧一律保留画面且如实写进状态行`() {
        // 这是用户真机反馈的那条缺陷：宿主说「白帧」时页面会清屏。客户端判定必须把它变成 KEEP。
        val outcome = VirtualScreenPolicy.previewOutcome(sessionAlive = true, decoded = true, blank = true, changed = false)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, outcome.action)
        assertEquals(VirtualScreenPolicy.PreviewPause.BLANK, outcome.pause)
        assertTrue(
            "状态行必须如实说明当前帧接近纯色",
            VirtualScreenPolicy.previewLine("", outcome.pause, 12.0, "省电", false).contains("接近纯色"),
        )
    }

    @Test
    fun `只有会话不可观察时才允许清屏`() {
        val alive = VirtualScreenPolicy.previewOutcome(sessionAlive = true, decoded = false, blank = false, changed = false)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.KEEP, alive.action)
        val gone = VirtualScreenPolicy.previewOutcome(sessionAlive = false, decoded = false, blank = false, changed = false)
        assertEquals(VirtualScreenPolicy.PreviewFrameAction.CLEAR, gone.action)
    }

    @Test
    fun `档位行与底部按钮必须压在预览之上`() {
        // 页面：预览是 content 的第一个子控件（下标 0），档位行后加（下标 1）。
        assertTrue(VirtualScreenPolicy.foregroundAbove(childCount = 2, previewIndex = 0, foregroundIndex = 1))
        // 小窗：画面最底（下标 0），档位行与状态行都在它上面。
        assertTrue(VirtualScreenPolicy.foregroundAbove(childCount = 3, previewIndex = 0, foregroundIndex = 1))
        assertTrue(VirtualScreenPolicy.foregroundAbove(childCount = 3, previewIndex = 0, foregroundIndex = 2))
        // 顺序写反：档位行还排在预览前面，预览会把它吃掉 —— 必须判为假。
        assertTrue(!VirtualScreenPolicy.foregroundAbove(childCount = 2, previewIndex = 1, foregroundIndex = 0))
        // 越界的下标不当成「在上面」。
        assertTrue(!VirtualScreenPolicy.foregroundAbove(childCount = 2, previewIndex = 0, foregroundIndex = 2))
        // 空容器没有「上面」可言。
        assertTrue(!VirtualScreenPolicy.foregroundAbove(childCount = 0, previewIndex = 0, foregroundIndex = 0))
    }
}
