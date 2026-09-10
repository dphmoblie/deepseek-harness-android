package io.deepseekharness.mobile.runtime

/** Minimal, comment-preserving update for the locale section in settings.yaml. */
internal object HarnessLocaleSettings {
    fun updateYaml(document: String, language: String): String {
        require(language == "zh" || language == "en") { "不支持的 Harness 语言" }
        val newline = if (document.contains("\r\n")) "\r\n" else "\n"
        val lines = document.replace("\r\n", "\n").split("\n").toMutableList()
        val localeIndex = lines.indexOfFirst { it.matches(Regex("^(?:locale|\\\"locale\\\"|'locale'):\\s*.*$")) }
        if (localeIndex < 0) {
            val prefix = if (document.isEmpty() || document.endsWith("\n") || document.endsWith("\r\n")) "" else newline
            return document + prefix + "locale:" + newline + "  preference: $language" + newline
        }

        val localeLine = lines[localeIndex]
        val localeValue = localeLine.substringAfter(':').trim()
        val flowValue = Regex("""^(\{.*\})(\s+#.*)?$""").matchEntire(localeValue)
        if (flowValue != null) {
            val body = flowValue.groupValues[1].substring(1, flowValue.groupValues[1].length - 1)
            val preference = Regex("((?:^|,)\\s*)([\\\"']?preference[\\\"']?\\s*:\\s*)[^,}]+")
            val replaced = if (preference.containsMatchIn(body)) {
                preference.replace(body) { match -> match.groupValues[1] + match.groupValues[2] + language }
            } else {
                val suffix = if (body.trim().isEmpty()) "" else ","
                body + suffix + " preference: " + language
            }
            lines[localeIndex] = localeLine.substringBefore(':') + ": {" + replaced + "}" + flowValue.groupValues[2]
            return lines.joinToString(newline)
        }
        if (localeValue.isNotEmpty() && !localeValue.startsWith("#")) {
            // Normalize scalar/null values instead of adding a duplicate key.
            lines[localeIndex] = localeLine.substringBefore(':') + ":"
        }

        val sectionEnd = (localeIndex + 1 until lines.size).firstOrNull { index ->
            val line = lines[index]
            line.isNotEmpty() && !line[0].isWhitespace() && !line.trimStart().startsWith("#")
        } ?: lines.size
        val preferenceIndex = (localeIndex + 1 until sectionEnd).firstOrNull { index ->
            lines[index].matches(Regex("^\\s+(?:preference|\\\"preference\\\"|'preference'):\\s*.*$"))
        }
        if (preferenceIndex != null) {
            val indent = lines[preferenceIndex].takeWhile { it.isWhitespace() }
            lines[preferenceIndex] = "$indent" + "preference: $language"
        } else {
            lines.add(sectionEnd, "  preference: $language")
        }
        return lines.joinToString(newline)
    }
}
