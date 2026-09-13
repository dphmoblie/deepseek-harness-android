package io.deepseekharness.mobile.runtime

/**
 * 对运行时 `settings.yaml` 中 `locale` 段的最小改写器。
 *
 * 只做一件事：把界面语言偏好写入 `locale.preference`，其余段落与注释原样保留。
 * 不引入 YAML 解析库——运行时配置结构简单，逐行改写可保证不重排用户文件、不丢注释。
 */
internal object HarnessLocaleSettings {
    fun updateYaml(document: String, language: String): String {
        // 白名单校验：只接受 Harness 支持的两种语言，拒绝任意写入。
        require(language == "zh" || language == "en") { "不支持的 Harness 语言" }
        val newline = if (document.contains("\r\n")) "\r\n" else "\n"
        val lines = document.replace("\r\n", "\n").split("\n").toMutableList()
        // 定位顶层 locale 键（兼容带引号的键名）。
        val localeIndex = lines.indexOfFirst { it.matches(Regex("^(?:locale|\\\"locale\\\"|'locale'):\\s*.*$")) }
        if (localeIndex < 0) {
            // 没有 locale 段：在文末追加，保持文件原有结尾换行习惯。
            val prefix = if (document.isEmpty() || document.endsWith("\n") || document.endsWith("\r\n")) "" else newline
            return document + prefix + "locale:" + newline + "  preference: $language" + newline
        }

        val localeLine = lines[localeIndex]
        val localeValue = localeLine.substringAfter(':').trim()
        // 形态一：行内 flow map，如 `locale: { preference: zh, fallback: en } # 注释`。
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
            // 标量/null 等非映射形态：先归一化为 `locale:`，避免产生重复键。
            lines[localeIndex] = localeLine.substringBefore(':') + ":"
        }

        // 形态二：块式映射。locale 段的范围到下一个顶层键为止。
        val sectionEnd = (localeIndex + 1 until lines.size).firstOrNull { index ->
            val line = lines[index]
            line.isNotEmpty() && !line[0].isWhitespace() && !line.trimStart().startsWith("#")
        } ?: lines.size
        val preferenceIndex = (localeIndex + 1 until sectionEnd).firstOrNull { index ->
            lines[index].matches(Regex("^\\s+(?:preference|\\\"preference\\\"|'preference'):\\s*.*$"))
        }
        if (preferenceIndex != null) {
            // 已存在 preference：保留其缩进，仅替换取值。
            val indent = lines[preferenceIndex].takeWhile { it.isWhitespace() }
            lines[preferenceIndex] = "$indent" + "preference: $language"
        } else {
            // 段内没有 preference：在段末补齐，避免重复键。
            lines.add(sectionEnd, "  preference: $language")
        }
        return lines.joinToString(newline)
    }
}
