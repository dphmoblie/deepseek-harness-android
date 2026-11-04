/**
 * 把「主色 + 透明度」算成一个旧 WebView 也认识的实色。
 *
 * 背景：这类半透明色过去用 `color-mix(in srgb, #rrggbb 14%, transparent)` 表达。
 * `color-mix()` 要 Chrome 111（2023）才有；而声明里带 `var()` 时，旧引擎不会在解析阶段
 * 丢弃它，而是在计算值阶段判定无效 —— 最终等于 `unset`，`background` 直接变透明。
 * 旧机上表现为「按钮、卡片、语言引导的继续键整个不见了」。
 *
 * `color-mix(in srgb, X P%, transparent)` 在 sRGB 里等价于「X 取 P% 不透明度」，
 * 所以这里直接写 `rgba(r, g, b, P/100)`：语义相同，支持度从 Chrome 44 起就有。
 */
export function withAlpha(hex: string, alpha: number): string {
  const value = /^#[0-9a-f]{6}$/i.test(hex) ? hex : '#000000'
  const [r, g, b] = [1, 3, 5].map(offset => parseInt(value.slice(offset, offset + 2), 16))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
