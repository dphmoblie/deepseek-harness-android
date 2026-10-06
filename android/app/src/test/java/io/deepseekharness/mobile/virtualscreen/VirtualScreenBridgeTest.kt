package io.deepseekharness.mobile.virtualscreen

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏设置桥的入参解析与状态合并（[VirtualScreenPreferences] 的纯函数部分）单测。
 *
 * 挑这三件事来测，是因为它们在真机上很难复现、出错又很难看出来：
 * 1. 枚举非法必须**整条拒绝**：设置页一次提交好几项，只拒一半的话，界面显示的配置与真正落盘的就不是一回事；
 * 2. 尺寸越界**夹取不抛异常**，而 0/负数表示「清掉自定义尺寸」（页面靠这个语义退回自适应/预设）；
 * 3. 状态合并的取值优先级：真实读数 > 设备端会话快照 > 本次规格 > 偏好 —— 设置页照着它显示，
 *    回显请求值就会变成「界面骗人」。
 */
class VirtualScreenBridgeTest {
    private val defaults = VirtualScreenSettings(
        previewMode = "60fps",
        autoFollow = "off",
        orientation = "auto",
        adaptive = false,
        widthPx = 0,
        heightPx = 0,
        densityDpi = 0,
    )

    // ---------- 写请求：枚举 ----------

    @Test
    fun `合法写请求逐字保留`() {
        val update = VirtualScreenPreferences.parseUpdate(
            JSONObject()
                .put("previewMode", "120fps")
                .put("autoFollow", "pull_back")
                .put("orientation", "landscape")
                .put("adaptive", true)
                .put("widthPx", 900)
                .put("heightPx", 1600)
                .put("densityDpi", 480),
        )
        assertEquals("120fps", update.previewMode)
        assertEquals("pull_back", update.autoFollow)
        assertEquals("landscape", update.orientation)
        assertEquals(true, update.adaptive)
        assertEquals(900, update.widthPx)
        assertEquals(1600, update.heightPx)
        assertEquals(480, update.densityDpi)
    }

    @Test
    fun `每个档位值都被接受`() {
        for (mode in VirtualScreenPolicy.FRAME_MODES.keys) {
            val update = VirtualScreenPreferences.parseUpdate(JSONObject().put("previewMode", mode))
            assertEquals(mode, update.previewMode)
        }
    }

    @Test
    fun `非法预览模式整条拒绝`() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            // 尺寸是合法的：只拒非法那一项、把尺寸写进去，正是要避免的「半套落盘」。
            VirtualScreenPreferences.parseUpdate(
                JSONObject().put("previewMode", "75fps").put("widthPx", 800).put("heightPx", 1280),
            )
        }
        assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
    }

    @Test
    fun `非法自动切换策略整条拒绝`() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            VirtualScreenPreferences.parseUpdate(JSONObject().put("autoFollow", "follow"))
        }
        assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
    }

    @Test
    fun `非法屏幕方向整条拒绝`() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            VirtualScreenPreferences.parseUpdate(JSONObject().put("orientation", "vertical"))
        }
        assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
    }

    @Test
    fun `数值类型错误整条拒绝而不是夹取`() {
        // 夹取会把 "800" 这种明显写错的入参悄悄变成有效配置，用户无从发现。
        for (data in listOf(
            JSONObject().put("widthPx", "800"),
            JSONObject().put("heightPx", true),
            JSONObject().put("densityDpi", JSONObject().put("nested", 1)),
        )) {
            val failure = assertThrows(RuntimeFailure::class.java) { VirtualScreenPreferences.parseUpdate(data) }
            assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
        }
    }

    @Test
    fun `布尔类型错误整条拒绝`() {
        val failure = assertThrows(RuntimeFailure::class.java) {
            VirtualScreenPreferences.parseUpdate(JSONObject().put("adaptive", "true"))
        }
        assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
    }

    // ---------- 写请求：数值 ----------

    @Test
    fun `正数越界被夹到区间两端`() {
        val update = VirtualScreenPreferences.parseUpdate(
            JSONObject()
                .put("widthPx", 99999)
                .put("heightPx", 1)
                .put("densityDpi", 9999),
        )
        assertEquals(VirtualScreenSpec.MAX_EDGE, update.widthPx)
        assertEquals(VirtualScreenSpec.MIN_EDGE, update.heightPx)
        assertEquals(VirtualScreenSpec.MAX_DPI, update.densityDpi)

        val low = VirtualScreenPreferences.parseUpdate(
            JSONObject()
                .put("widthPx", 100)
                .put("heightPx", 100)
                .put("densityDpi", 10),
        )
        assertEquals(VirtualScreenSpec.MIN_EDGE, low.widthPx)
        assertEquals(VirtualScreenSpec.MIN_EDGE, low.heightPx)
        assertEquals(VirtualScreenSpec.MIN_DPI, low.densityDpi)
    }

    @Test
    fun `零与负数表示清掉自定义尺寸`() {
        // 偏好里 0 就是「没自定义过」；把它夹成 200 会让用户再也回不到自适应与预设。
        val update = VirtualScreenPreferences.parseUpdate(
            JSONObject().put("widthPx", 0).put("heightPx", -5).put("densityDpi", 0),
        )
        assertEquals(0, update.widthPx)
        assertEquals(0, update.heightPx)
        assertEquals(0, update.densityDpi)
    }

    @Test
    fun `缺省字段与 null 都表示这一项不改`() {
        val empty = VirtualScreenPreferences.parseUpdate(JSONObject())
        assertNull(empty.previewMode)
        assertNull(empty.autoFollow)
        assertNull(empty.orientation)
        assertNull(empty.adaptive)
        assertNull(empty.widthPx)
        assertNull(empty.heightPx)
        assertNull(empty.densityDpi)

        val nulled = VirtualScreenPreferences.parseUpdate(
            JSONObject().put("widthPx", JSONObject.NULL).put("orientation", JSONObject.NULL),
        )
        assertNull(nulled.widthPx)
        assertNull(nulled.orientation)
    }

    // ---------- 启动请求 ----------

    @Test
    fun `启动请求缺少包名直接拒绝`() {
        for (data in listOf(JSONObject(), JSONObject().put("packageName", "   "), JSONObject().put("packageName", ""))) {
            val failure = assertThrows(RuntimeFailure::class.java) { VirtualScreenPreferences.parseStart(data) }
            assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
        }
    }

    @Test
    fun `启动请求复用写请求的校验与夹取`() {
        val request = VirtualScreenPreferences.parseStart(
            JSONObject()
                .put("packageName", "  com.example.app  ")
                .put("adaptive", true)
                .put("orientation", "landscape")
                .put("widthPx", 99999)
                .put("heightPx", 1600)
                .put("densityDpi", 480),
        )
        assertEquals("com.example.app", request.target)
        assertEquals(true, request.adaptive)
        assertEquals("landscape", request.orientation)
        assertEquals(VirtualScreenSpec.MAX_EDGE, request.widthPx)
        assertEquals(1600, request.heightPx)
        assertEquals(480, request.densityDpi)

        val failure = assertThrows(RuntimeFailure::class.java) {
            VirtualScreenPreferences.parseStart(JSONObject().put("packageName", "com.example.app").put("orientation", "upside"))
        }
        assertEquals(VirtualScreenPreferences.INVALID_CODE, failure.code)
    }

    @Test
    fun `目标字符串区分组件名与包名`() {
        assertEquals(
            VirtualScreenTargetKind.COMPONENT,
            VirtualScreenPreferences.targetKind("com.example.app/com.example.app.MainActivity"),
        )
        assertEquals(
            VirtualScreenTargetKind.COMPONENT,
            VirtualScreenPreferences.targetKind("com.example.app/.MainActivity"),
        )
        assertEquals(VirtualScreenTargetKind.PACKAGE, VirtualScreenPreferences.targetKind("com.example.app"))
        assertEquals(VirtualScreenTargetKind.INVALID, VirtualScreenPreferences.targetKind("singleword"))
        assertEquals(VirtualScreenTargetKind.INVALID, VirtualScreenPreferences.targetKind("com.1bad.app"))
        assertEquals(VirtualScreenTargetKind.INVALID, VirtualScreenPreferences.targetKind("   "))
    }

    // ---------- 状态合并 ----------

    @Test
    fun `没有会话时状态回偏好与本次规格`() {
        val state = VirtualScreenPreferences.mergeState(null, defaults, VirtualScreenSpec(726, 1600, 320))

        assertEquals(false, state.getBoolean("active"))
        assertEquals("", state.getString("sessionId"))
        assertEquals(-1, state.getInt("displayId"))
        assertEquals("60fps", state.getString("previewMode"))
        assertEquals("off", state.getString("autoFollow"))
        assertEquals("auto", state.getString("orientation"))
        assertEquals(false, state.getBoolean("adaptive"))
        assertEquals(726, state.getInt("widthPx"))
        assertEquals(1600, state.getInt("heightPx"))
        assertEquals(320, state.getInt("densityDpi"))
        assertEquals(0.0, state.getDouble("frameFps"), 0.0)
        // 没有会话就没有刷新率可读：回 0（未知）而不是猜一个屏幕标称值。
        assertEquals(0.0, state.getDouble("displayRefreshRate"), 0.0)
        assertTrue(state.isNull("targetPackage"))
        assertTrue(state.isNull("virtualForegroundPackage"))
        assertTrue(state.isNull("virtualForegroundActivity"))
    }

    @Test
    fun `真实读数优先于会话快照与偏好`() {
        val session = JSONObject()
            .put("active", true)
            .put("sessionId", "s-1")
            .put("displayId", 7)
            .put("width", 726)
            .put("height", 1600)
            .put("previewMode", "realtime-120fps")
            .put("autoFollow", "promote")
            .put("frameFps", 59.9)
            .put("displayRefreshRate", 90.0)
            .put("packageName", "com.example.app/com.example.app.MainActivity")
            .put("virtualForegroundPackage", "com.example.app")
            .put("virtualForegroundActivity", "com.example.app.MainActivity")
            .put("starting", false)
            .put("treeSupported", true)
        val readback = JSONObject()
            .put("widthPx", 1080)
            .put("heightPx", 2340)
            .put("densityDpi", 440)
            .put("refreshRate", 120.0)

        val state = VirtualScreenPreferences.mergeState(session, defaults, VirtualScreenSpec(720, 1280, 320), readback)

        assertEquals(true, state.getBoolean("active"))
        assertEquals("s-1", state.getString("sessionId"))
        assertEquals(7, state.getInt("displayId"))
        assertEquals(1080, state.getInt("widthPx"))
        assertEquals(2340, state.getInt("heightPx"))
        assertEquals(440, state.getInt("densityDpi"))
        assertEquals(120.0, state.getDouble("displayRefreshRate"), 0.0)
        // 会话里的 previewMode 是标签（realtime-120fps），桥的字段必须是档位值。
        assertEquals("120fps", state.getString("previewMode"))
        // 用户可能在副屏页面里直接改过档位与跟随策略，会话里有就以会话为准。
        assertEquals("promote", state.getString("autoFollow"))
        assertEquals(59.9, state.getDouble("frameFps"), 0.001)
        assertEquals("com.example.app", state.getString("targetPackage"))
        assertEquals("com.example.app", state.getString("virtualForegroundPackage"))
        assertEquals("com.example.app.MainActivity", state.getString("virtualForegroundActivity"))
        // starting / treeSupported 这类字段桥只做透传：会话里是什么就带出什么，不做解释。
        assertEquals(true, state.getBoolean("treeSupported"))
    }

    @Test
    fun `没有读数时回落到会话快照的宽高`() {
        val session = JSONObject()
            .put("active", true)
            .put("sessionId", "s-2")
            .put("displayId", 3)
            .put("width", 900)
            .put("height", 1600)
            .put("displayRefreshRate", 60.0)

        val state = VirtualScreenPreferences.mergeState(session, defaults, VirtualScreenSpec(726, 1600, 320))

        assertEquals(900, state.getInt("widthPx"))
        assertEquals(1600, state.getInt("heightPx"))
        // 会话没有 dpi 字段：这一项只能回本次规格。
        assertEquals(320, state.getInt("densityDpi"))
        assertEquals(60.0, state.getDouble("displayRefreshRate"), 0.0)
        assertEquals("60fps", state.getString("previewMode"))
    }

    @Test
    fun `会话的 adaptive 与 orientation 覆盖偏好`() {
        val session = JSONObject()
            .put("active", true)
            .put("orientation", "landscape")
            .put("adaptive", true)
            .put("autoFollow", "not-a-mode")

        val state = VirtualScreenPreferences.mergeState(session, defaults, VirtualScreenSpec(726, 1600, 320))

        assertEquals("landscape", state.getString("orientation"))
        assertEquals(true, state.getBoolean("adaptive"))
        // 会话里的跟随策略不认识时不能原样透传，退回偏好值。
        assertEquals("off", state.getString("autoFollow"))
    }
}
