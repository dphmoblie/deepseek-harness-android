/**
 * 壳字号：主题页的「字号」管整个应用壳子，dsh 自己界面的字号仍由 dsh 自己设置。
 *
 * 落地只能靠缩放根字号：`src/styles.css` 的排版几乎全是 rem（89 处 rem 字号对 7 处
 * px 字号），根字号一动，整个壳子的文字与行高一起变；那 7 处 px 已按 16px 基准
 * 换算成 rem，所以字号保持默认值时，界面与改动前逐像素一致。
 *
 * 中性基准取 14px 而不是 16px：这个值原本就是应用内终端的字号，默认 14。
 * 缩放系数 1 意味着「和以前一样」，用户不动它就不会有任何视觉变化。
 */

/** 中性基准：取它时缩放系数为 1，界面与改动前完全一致。 */
export const SHELL_FONT_BASELINE_PX = 14
export const MIN_SHELL_FONT_PX = 11
export const MAX_SHELL_FONT_PX = 24

/**
 * 纯函数：字号 → 根字号缩放系数。
 *
 * 非法值与越界值都收敛到合法区间，而不是原样写进 CSS——存储里的内容可能被任何
 * 脚本改写，一个 NaN 会让整个壳子的字号变成浏览器默认值。
 */
export function shellFontScale(fontSizePx: number): number {
  if (!Number.isFinite(fontSizePx)) return 1
  const clamped = Math.min(MAX_SHELL_FONT_PX, Math.max(MIN_SHELL_FONT_PX, Math.round(fontSizePx)))
  return clamped / SHELL_FONT_BASELINE_PX
}

/**
 * 把缩放写到根元素的自定义属性上，CSS 侧由
 * `html { font-size: calc(16px * var(--app-font-scale, 1)) }` 消费。
 * 返回实际生效的系数，调用方不必再算一遍。
 */
export function applyShellFontScale(
  fontSizePx: number,
  root: HTMLElement = document.documentElement,
): number {
  const scale = shellFontScale(fontSizePx)
  root.style.setProperty('--app-font-scale', String(scale))
  return scale
}
