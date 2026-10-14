/**
 * 移动端触感反馈辅助模块。
 *
 * 优先使用标准 Web API `navigator.vibrate`（Android WebView 标准支持）；
 * 在不支持环境（如桌面浏览器、未授权、不支持振动的设备）下静默回退为空操作，不抛出异常。
 */

export type HapticFeedbackType = 'light' | 'medium' | 'heavy' | 'success' | 'warning' | 'error'

const HAPTIC_PATTERNS: Record<HapticFeedbackType, number | number[]> = {
  light: 10,
  medium: 25,
  heavy: 45,
  success: [15, 40, 15],
  warning: [25, 50, 25],
  error: [35, 60, 45, 60, 35],
}

export function triggerHaptic(type: HapticFeedbackType = 'light'): void {
  if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return
  try {
    navigator.vibrate(HAPTIC_PATTERNS[type] ?? 10)
  } catch {
    // 忽略任何权限、前台状态或设备硬件层级的限制
  }
}
