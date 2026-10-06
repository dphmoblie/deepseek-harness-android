package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动规格求解（[VirtualScreenStart]）单测。
 *
 * 这一层是「自适应分辨率 / 屏幕方向真正接到启动路径」的落点：桥与原生页面只往 Intent 里写
 * 「这次要不要自适应、什么方向」，尺寸一律在这里算，所以只要这里错了，副屏启动的规格就全错。
 * 重点测两件事：
 * 1. 五个来源的优先级（请求自适应 > 请求尺寸 > 偏好自适应 > 偏好尺寸 > 方向预设）；
 * 2. 算出来的规格**一定过设备端闸门** [VirtualScreenPolicy.dimensions] —— 那是启动前的硬闸门，
 *    过不去就会在真机上抛「副屏尺寸超出范围」，而这条路径在单测里就能提前发现。
 */
class VirtualScreenStartTest {
    private val defaults = VirtualScreenSettings(
        previewMode = "60fps",
        autoFollow = "off",
        orientation = "auto",
        adaptive = false,
        widthPx = 0,
        heightPx = 0,
        densityDpi = 0,
    )
    private val custom = defaults.copy(widthPx = 900, heightPx = 1600, densityDpi = 480)
    private val adaptiveSettings = defaults.copy(adaptive = true)

    private val portraitScreen = 1080
    private val portraitScreenHeight = 2340
    private val density = 2.75f

    // ---------- 优先级 ----------

    @Test
    fun `请求里的完整尺寸优先于偏好`() {
        val spec = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", widthPx = 720, heightPx = 1280, densityDpi = 320),
            custom,
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        assertEquals(VirtualScreenSpec(720, 1280, 320), spec)
    }

    @Test
    fun `请求自适应时忽略请求里的尺寸`() {
        val spec = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", adaptive = true, widthPx = 720, heightPx = 1280, densityDpi = 320),
            custom,
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        // 自适应是用户这一下明确点的，它必须压过同时带过来的自定义尺寸。
        assertEquals(VirtualScreenSpec.adaptive(portraitScreen, portraitScreenHeight, density, "auto"), spec)
    }

    @Test
    fun `偏好自适应在请求没给尺寸时生效`() {
        val spec = VirtualScreenStart.resolve(adaptiveSettings, portraitScreen, portraitScreenHeight, density)
        assertEquals(VirtualScreenSpec.adaptive(portraitScreen, portraitScreenHeight, density, "auto"), spec)
    }

    @Test
    fun `偏好的自定义尺寸在自适应关闭时生效`() {
        val spec = VirtualScreenStart.resolve(defaults.copy(widthPx = 900, heightPx = 1600, densityDpi = 480),
            portraitScreen, portraitScreenHeight, density)
        assertEquals(VirtualScreenSpec(900, 1600, 480), spec)
    }

    @Test
    fun `自适应压过偏好里的自定义尺寸`() {
        // 否则设置页打开「自适应」后仍然会看到旧的自定义尺寸生效，开关看起来没用。
        val spec = VirtualScreenStart.resolve(adaptiveSettings.copy(widthPx = 900, heightPx = 1600, densityDpi = 480),
            portraitScreen, portraitScreenHeight, density)
        assertEquals(VirtualScreenSpec.adaptive(portraitScreen, portraitScreenHeight, density, "auto"), spec)
    }

    @Test
    fun `请求尺寸不全时整组忽略回落到偏好`() {
        val spec = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", widthPx = 720),
            custom,
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        assertEquals(VirtualScreenSpec(900, 1600, 480), spec)
    }

    @Test
    fun `四个来源都没有时按方向回落到预设`() {
        val portrait = VirtualScreenStart.resolve(defaults, portraitScreen, portraitScreenHeight, density)
        assertEquals(VirtualScreenSpec.PORTRAIT, portrait)

        // 横屏屏幕（宽 > 高）在 auto 下应得横屏预设；闸门不该改动这两个预设。
        val landscapeScreen = VirtualScreenStart.resolve(defaults, portraitScreenHeight, portraitScreen, density)
        assertEquals(VirtualScreenSpec.LANDSCAPE, landscapeScreen)
    }

    @Test
    fun `显式方向决定预设的横竖`() {
        val landscape = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", orientation = "landscape"),
            defaults,
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        assertEquals(VirtualScreenSpec.LANDSCAPE, landscape)

        val portrait = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", orientation = "portrait"),
            defaults,
            portraitScreenHeight,
            portraitScreen,
            density,
        )
        assertEquals(VirtualScreenSpec.PORTRAIT, portrait)
    }

    @Test
    fun `显式方向决定自适应的长边`() {
        val landscape = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", adaptive = true, orientation = "landscape"),
            defaults,
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        // 竖屏手机上要横屏副屏：自适应必须先把屏幕尺寸转成横屏再缩放，否则会得到一块竖屏。
        assertTrue(landscape.widthPx > landscape.heightPx)
        assertEquals(VirtualScreenSpec.adaptive(portraitScreenHeight, portraitScreen, density, "landscape"), landscape)
    }

    @Test
    fun `会话方向优先于偏好方向`() {
        val spec = VirtualScreenStart.resolve(
            VirtualScreenStartRequest("com.example.app", orientation = "landscape"),
            defaults.copy(orientation = "portrait"),
            portraitScreen,
            portraitScreenHeight,
            density,
        )
        assertTrue(VirtualScreenStart.landscape(spec, "landscape"))
        assertEquals(VirtualScreenSpec.LANDSCAPE, spec)
    }

    // ---------- 闸门 ----------

    @Test
    fun `闸门把超上限的边长与 dpi 夹回闸门范围`() {
        val gated = VirtualScreenStart.gate(VirtualScreenSpec(4096, 4096, 640))
        assertTrue(gated.widthPx <= VirtualScreenStart.MAX_GATE_WIDTH)
        assertTrue(gated.heightPx <= VirtualScreenStart.MAX_GATE_HEIGHT)
        assertTrue(gated.densityDpi in VirtualScreenStart.MIN_GATE_DPI..VirtualScreenSpec.MAX_DPI)
        assertTrue(gated.widthPx.toLong() * gated.heightPx <= VirtualScreenStart.MAX_GATE_PIXELS)
        // 1440×2560 超预算 0.5625 倍，等比缩到 1080×1920、dpi 同步缩到 480。
        assertEquals(VirtualScreenSpec(1080, 1920, 480), gated)
    }

    @Test
    fun `闸门把过小的规格抬到下限`() {
        assertEquals(VirtualScreenSpec(320, 320, 160), VirtualScreenStart.gate(VirtualScreenSpec(10, 10, 10)))
    }

    @Test
    fun `闸门不放大预算内的规格`() {
        // 只缩不放：用户要一块小的副屏，不该被放大成整屏。
        val small = VirtualScreenSpec(320, 320, 160)
        assertEquals(small, VirtualScreenStart.gate(small))
    }

    @Test
    fun `complete 只在三项都合法时成立`() {
        assertNotNull(VirtualScreenStart.complete(200, 200, 120))
        assertNull(VirtualScreenStart.complete(199, 200, 120))
        assertNull(VirtualScreenStart.complete(200, 200, 119))
        assertNull(VirtualScreenStart.complete(0, 0, 0))
    }

    @Test
    fun `任何输入算出的启动规格都过设备端闸门`() {
        val screens = listOf(1080 to 2340, 2340 to 1080, 720 to 1280, 1440 to 3200, 320 to 320)
        val settingsList = listOf(
            defaults,
            custom,
            adaptiveSettings,
            defaults.copy(orientation = "landscape"),
            defaults.copy(widthPx = 4096, heightPx = 4096, densityDpi = 640),
        )
        var checked = 0
        for ((width, height) in screens) {
            for (orientation in VirtualScreenPreferences.ORIENTATION_VALUES) {
                for (settings in settingsList) {
                    for (adaptive in listOf(true, false, null)) {
                        val spec = VirtualScreenStart.resolve(
                            VirtualScreenStartRequest("com.example.app", adaptive, orientation),
                            settings,
                            width,
                            height,
                            density,
                        )
                        // 设备端 VirtualScreenPolicy.dimensions 是启动前的硬闸门：过不去就在真机上抛异常。
                        VirtualScreenPolicy.dimensions(spec.widthPx, spec.heightPx, spec.densityDpi)
                        assertTrue(spec.widthPx >= VirtualScreenStart.MIN_GATE_EDGE)
                        assertTrue(spec.heightPx >= VirtualScreenStart.MIN_GATE_EDGE)
                        checked++
                    }
                }
            }
        }
        assertEquals(screens.size * VirtualScreenPreferences.ORIENTATION_VALUES.size * settingsList.size * 3, checked)
    }

    @Test
    fun `按规格判定横竖`() {
        assertTrue(VirtualScreenStart.landscape(VirtualScreenSpec(800, 600, 240), "auto"))
        assertFalse(VirtualScreenStart.landscape(VirtualScreenSpec(600, 800, 240), "auto"))
        assertTrue(VirtualScreenStart.landscape(VirtualScreenSpec(600, 800, 240), "landscape"))
        assertFalse(VirtualScreenStart.landscape(VirtualScreenSpec(800, 600, 240), "portrait"))
    }
}
