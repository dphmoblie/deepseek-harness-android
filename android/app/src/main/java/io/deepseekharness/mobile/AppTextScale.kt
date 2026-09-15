package io.deepseekharness.mobile

import kotlin.math.roundToInt

/**
 * 把系统字体缩放折算成 WebView 的 `textZoom` 百分比。
 *
 * **为什么要这一步**：外壳的 CSS 已经把字号全部 rem 化、根字号也不再写死
 * （登记册 5.6-I 的 Web 侧改动），但 **WebView 不会因为系统字号变大就改变 rem 的基准**——
 * 它只认 `WebSettings.textZoom`。也就是说：不接这一步，用户在系统设置里把字体调到最大，
 * 应用内文字仍然纹丝不动，「字号跟着系统走」在真机上等于没做。
 *
 * **为什么夹在 [MIN_PERCENT]..[MAX_PERCENT]**：`fontScale` 来自系统，取值不可全信
 * （异常 ROM、被改过的配置都可能给出离谱的值）。放大到 3 倍会让设置页在窄屏上彻底挤成一团，
 * 那不是「支持无障碍字体」，而是把界面弄坏。因此给它一个明确的上限，并在上下限之外夹紧。
 * 下限 80 而不是「不缩」：系统字号调到最小时也应该跟着变小，否则与「跟随系统」的承诺不符。
 *
 * **非有限值 / 非正值**：当作「跟随系统」不可用，回落到 100（默认大小），
 * 而不是让 `roundToInt()` 抛异常或产生 `Int.MIN_VALUE` 之类的荒谬结果。
 *
 * **应用时机**：`fontScale` 不在两个 Activity 的 `configChanges` 列表里
 * （见 `AndroidManifest.xml` 的 `MainActivity`），所以系统改字号会**重建 Activity**，
 * `onCreate` 里重新读一次即可；不需要额外监听配置变化。
 */
internal object AppTextScale {
    /** 最小百分比：系统字号调到最小时也要跟着变小。 */
    const val MIN_PERCENT = 80

    /** 最大百分比：上限之外不再放大，避免窄屏被挤坏。 */
    const val MAX_PERCENT = 200

    /** 系统未提供可用缩放时的默认值。 */
    const val DEFAULT_PERCENT = 100

    /** 把 `Configuration.fontScale`（1.0 = 系统默认）折算成夹紧后的百分比。 */
    fun percentOf(fontScale: Float): Int {
        if (!fontScale.isFinite() || fontScale <= 0f) return DEFAULT_PERCENT
        return (fontScale * 100f).roundToInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
    }
}
