package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessLocaleSettingsTest {
    @Test
    fun addsLocalePreferenceWhenSettingsHaveNoLocaleSection() {
        assertEquals("models:\n  selected: deepseek\nlocale:\n  preference: en\n", HarnessLocaleSettings.updateYaml("models:\n  selected: deepseek\n", "en"))
    }

    @Test
    fun updatesExistingPreferenceAndPreservesOtherSections() {
        val source = "locale:\n  # user choice\n  preference: zh\nmodels:\n  selected: deepseek\n"
        assertEquals("locale:\n  # user choice\n  preference: en\nmodels:\n  selected: deepseek\n", HarnessLocaleSettings.updateYaml(source, "en"))
    }

    @Test
    fun updatesInlineLocaleMapWithoutAddingDuplicateKeys() {
        val source = "locale: { preference: zh, fallback: en } # keep\nmodels:\n  selected: deepseek\n"
        assertEquals(
            "locale: { preference: en, fallback: en } # keep\nmodels:\n  selected: deepseek\n",
            HarnessLocaleSettings.updateYaml(source, "en"),
        )
    }

    @Test
    fun preservesCrLfLineEndingsAndQuotedKeys() {
        val source = "\"locale\":\r\n  \"preference\": zh\r\n"
        assertEquals(
            "\"locale\":\r\n  preference: en\r\n",
            HarnessLocaleSettings.updateYaml(source, "en"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedHarnessLanguage() {
        HarnessLocaleSettings.updateYaml("", "zh-CN")
    }
}
