package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外置浏览器清单的纯逻辑契约。
 *
 * 系统返回的 Activity 列表可能同一包名出现多次、混入本应用或非法包名；
 * 这些都在进界面前收敛掉，避免把任意字符串写进显式 Intent 的 setPackage。
 */
class ExternalBrowsersTest {
    private fun browser(packageName: String, label: String) = ExternalBrowsers.Browser(packageName, label)

    @Test
    fun duplicateActivitiesCollapseToOneBrowser() {
        val result = ExternalBrowsers.normalize(
            "io.deepseekharness.mobile",
            listOf(
                browser("com.android.chrome", "Chrome"),
                browser("com.android.chrome", "Chrome"),
                browser("com.honor.browser", "浏览器"),
            ),
        )
        assertEquals(listOf("com.android.chrome", "com.honor.browser"), result.map { it.packageName })
    }

    @Test
    fun selfAndInvalidPackagesAreDropped() {
        val result = ExternalBrowsers.normalize(
            "io.deepseekharness.mobile",
            listOf(
                browser("io.deepseekharness.mobile", "本应用"),
                browser("not a package", "非法"),
                browser(".com.example", "以点开头"),
                browser("com.example", "两段包名"),
                browser("com.example.browser", "合法浏览器"),
            ),
        )
        // 两段包名（com.example）是合法 Android 包名，保留；只有空白、前导点与本应用被剔除。
        assertEquals(setOf("com.example", "com.example.browser"), result.map { it.packageName }.toSet())
    }

    @Test
    fun controlCharactersAreFlattenedAndEmptyLabelsFallBackToPackage() {
        val result = ExternalBrowsers.normalize(
            "io.deepseekharness.mobile",
            listOf(
                browser("com.example.browser", " 浏\n览器 "),
                browser("com.example.blank", "\u0000\t"),
            ),
        )
        assertEquals("浏 览器", result.first { it.packageName == "com.example.browser" }.label)
        assertEquals("com.example.blank", result.first { it.packageName == "com.example.blank" }.label)
    }

    @Test
    fun listIsSortedByLabelIgnoringCase() {
        val result = ExternalBrowsers.normalize(
            "io.deepseekharness.mobile",
            listOf(
                browser("com.example.zeta", "zeta"),
                browser("com.example.alpha", "Alpha"),
                browser("com.example.beta", "beta"),
            ),
        )
        assertEquals(listOf("Alpha", "beta", "zeta"), result.map { it.label })
    }

    @Test
    fun missingLabelsStillProduceUsableEntries() {
        val result = ExternalBrowsers.normalize(
            "io.deepseekharness.mobile",
            listOf(browser("com.example.browser", "")),
        )
        assertTrue(result.single().label.isNotEmpty())
        assertEquals("com.example.browser", result.single().label)
    }
}
