/**
 * 旧系统 WebView 的能力探测。
 *
 * 为什么需要它：样式表已经把每条 `color-mix()` 声明关进 `@supports` 并留了纯色兜底，
 * 所以旧引擎上不会再有「按钮整个变透明、语言引导看不见继续键」这种硬故障。剩下的只是
 * **外观降级**（半透明卡片变成实色、阴影退成基础投影）。这类降级从界面本身看不出来是
 * 「版本旧」还是「我设置错了」，所以给一句明确提示，而不是让用户去猜。
 *
 * 探测失败一律当作「不支持」：`CSS.supports` 本身在很老的引擎上也可能不存在。
 */
export function colorMixSupported(): boolean {
  if (typeof window === 'undefined') return false
  const css = window.CSS
  if (css === undefined || css === null || typeof css.supports !== 'function') return false
  try {
    return css.supports('color', 'color-mix(in srgb, red, blue)')
  } catch {
    return false
  }
}
