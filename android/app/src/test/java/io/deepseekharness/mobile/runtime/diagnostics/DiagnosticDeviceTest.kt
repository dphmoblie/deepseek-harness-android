package io.deepseekharness.mobile.runtime.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备与 ROM 文件头测试。
 *
 * 这一层的意义是：这两行会随导出文件离开设备。因此除正常路径外，重点覆盖
 * **绝不能出现的东西**——换行、控制字符、超长构建串，以及读不到时如实略去。
 */
class DiagnosticDeviceTest {
    private fun device(
        manufacturer: String? = "vivo",
        brand: String? = "iQOO",
        model: String? = "V2218A",
        device: String? = "PD2218",
        rom: String? = "V14.0.9.1.UNOCNXM",
        androidRelease: String? = "14",
        securityPatch: String? = "2026-09-01",
    ): DiagnosticDevice = DiagnosticDevice.of(
        manufacturer,
        brand,
        model,
        device,
        rom,
        androidRelease,
        securityPatch,
    )

    @Test
    fun rendersHardwareAndRomLines() {
        assertEquals(
            listOf(
                "# 机型: vivo iQOO V2218A（设备代号 PD2218）",
                "# ROM: V14.0.9.1.UNOCNXM（Android 14，安全补丁 2026-09-01）",
            ),
            device().headerLines(),
        )
    }

    @Test
    fun keepsSubBrandOnlyWhenItAddsInformation() {
        // 子品牌与厂商同名（vivo/vivo、HONOR/HONOR）时只写一次，避免「Xiaomi Xiaomi」这类噪声。
        assertEquals(
            "# 机型: Xiaomi 2211133C（设备代号 duchamp）",
            device(manufacturer = "Xiaomi", brand = "Xiaomi", model = "2211133C", device = "duchamp")
                .headerLines().first(),
        )
        assertEquals(
            "# 机型: Xiaomi Redmi 23013RK75C（设备代号 marble）",
            device(manufacturer = "Xiaomi", brand = "Redmi", model = "23013RK75C", device = "marble")
                .headerLines().first(),
        )
    }

    @Test
    fun omitsBothLinesWhenNothingCanBeRead() {
        assertEquals(emptyList<String>(), device(null, null, null, null, null, null, null).headerLines())
    }

    @Test
    fun omitsOnlyThePartsThatAreMissing() {
        // 只有型号可读：不写「未知」，也不留下空的括号。
        assertEquals(
            listOf("# 机型: M2012K11AC"),
            device(null, null, "M2012K11AC", null, null, null, null).headerLines(),
        )
        // 只有 ROM 与系统版本可读：机型行整条略去，系统信息仍在 ROM 行里。
        assertEquals(
            listOf("# ROM: Android 14，安全补丁 2026-09-01"),
            device(null, null, null, null, null, "14", "2026-09-01").headerLines(),
        )
        // 只有设备代号可读时如实说明这是代号，不把它当成型号。
        assertEquals(
            listOf("# 机型: 设备代号 PD2218"),
            device(null, null, null, "PD2218", null, null, null).headerLines(),
        )
    }

    @Test
    fun neverLetsACustomBuildStringBreakTheHeaderFormat() {
        val lines = device(rom = "V816.0.6.0\n# 伪造: 行").headerLines()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            assertFalse("文件头不允许出现换行", line.contains('\n'))
            assertFalse("文件头不允许出现回车", line.contains('\r'))
            assertTrue("每行都以注释标记开头", line.startsWith("# "))
        }
        assertEquals(
            "# ROM: V816.0.6.0 # 伪造: 行（Android 14，安全补丁 2026-09-01）",
            lines[1],
        )
    }

    @Test
    fun truncatesOverlongValues() {
        val lines = device(model = "A".repeat(200)).headerLines()
        assertTrue(lines.first().contains("A".repeat(DiagnosticDevice.MAX_FIELD_CHARS)))
        assertFalse(lines.first().contains("A".repeat(DiagnosticDevice.MAX_FIELD_CHARS + 1)))
    }

    @Test
    fun collapsesWhitespaceAndDropsControlCharacters() {
        assertEquals("Redmi K60", DiagnosticDevice.clean("  Redmi \t K60  "))
        assertEquals("a b", DiagnosticDevice.clean("a\n\n b"))
        assertEquals("V816.0.6.0", DiagnosticDevice.clean("\u0000V816.0.6.0\u0007"))
    }

    @Test
    fun returnsEmptyTextForUnreadableValues() {
        assertEquals("", DiagnosticDevice.clean(null))
        assertEquals("", DiagnosticDevice.clean(""))
        assertEquals("", DiagnosticDevice.clean(" \t\n "))
    }

    @Test
    fun keepsNonAsciiBuildStringsReadable() {
        // 部分 ROM 的构建串带中文（例如「内测版」），这不是敏感内容，压平后照原样写出。
        assertEquals("V14.0.9.1 内测版", DiagnosticDevice.clean("V14.0.9.1 内测版"))
    }
}
