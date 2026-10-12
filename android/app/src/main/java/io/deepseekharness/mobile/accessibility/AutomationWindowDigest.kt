package io.deepseekharness.mobile.accessibility

import java.security.MessageDigest

/**
 * 窗口指纹：回答「还是不是刚才那个界面」。
 *
 * 任务台账拿它做两件事：计划做好之后界面是不是已经变了（[AutomationTaskCodes.TARGET_STALE]）、
 * 以及某一步失败之后界面会不会已经换了。所以它必须**对结构敏感、对内容无感**：
 * 只由包名、窗口 id 与一棵结构树（类名 / viewId / 屏幕边界）算出来，
 * **不含 `text`、不含 `contentDescription`、不含任何用户内容**——它要经设备桥回到模型侧，
 * 不能反过来把屏幕上的字带出去。
 *
 * 十六进制前 16 位（64 位）足够区分同一次会话里先后两个界面，又短到能塞进 JSON 回执。
 * 这不是密码学用途：撞了只会让一次「界面变了」被判成没变，代价是那一步记 `unknown`，
 * 而 `unknown` 本来就不允许自动重试。
 */
internal object AutomationWindowDigest {
    private const val HEX_CHARS = 16

    /** 一棵结构树里的一项。索引即位置：同一个界面每次算出来必须一样。 */
    fun entry(
        className: String?,
        viewId: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): String = "${className.orEmpty()}|${viewId.orEmpty()}|$left,$top,$right,$bottom"

    fun of(packageName: String, windowId: Int, structure: List<String>): String {
        val canonical = buildString {
            append(packageName)
            append('|')
            append(windowId)
            structure.forEach {
                append('|')
                append(it)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return buildString(HEX_CHARS) {
            for (index in 0 until HEX_CHARS / 2) {
                append("%02x".format(digest[index]))
            }
        }
    }
}
