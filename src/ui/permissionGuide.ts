/**
 * 系统权限指引的受控判别值与「是否已解除」判定。
 *
 * 从 `App.tsx` 提出来：对话框渲染与外壳的状态清理都要用同一套判定——外壳在可见性刷新时
 * 判断弹窗该不该关，对话框决定显示哪段指引。留在任一侧都会让另一侧反向依赖。
 * 正文零改动。
 */
import type { KeepAliveState, OverlayBallState } from '../platform/types'

/**
 * 权限指引的判别值。
 *
 * - overlay-permission：悬浮窗权限没给，去系统页开启；
 * - notification-permission：Android 13+ 通知权限被关，前台服务无法驻留；
 * - background-blocked：被厂商 ROM 的自启动 / 省电策略拦截（小米 HyperOS 最常见）；
 * - overlay-internal：悬浮球界面创建失败，没有系统页可去，只能重启应用 / 反馈日志。
 * 这层映射只消费原生侧下发的受控令牌，不接收任何自由文本。
 */
export type PermissionGuide =
  | { kind: 'overlay-permission' }
  | { kind: 'notification-permission' }
  | { kind: 'background-blocked'; feature: 'keepAlive' | 'overlayBall' }
  | { kind: 'overlay-internal' }
  | { kind: 'shizuku-permission' }

/**
 * 把悬浮球服务的失败令牌映射成指引；未知 / 缺省一律按「厂商后台拦截」处理——
 * 这是该设备上最常见、且指引步骤最可操作的一类。
 */
export function overlayFailureGuide(token: string | null | undefined): PermissionGuide {
  if (token === 'overlay_denied') return { kind: 'overlay-permission' }
  if (token === 'inflate_failed' || token === 'attach_failed') return { kind: 'overlay-internal' }
  return { kind: 'background-blocked', feature: 'overlayBall' }
}

/**
 * 后台保持类指引是否已被最新原生状态解除。
 *
 * 用户在系统设置里补齐通知权限或后台策略后返回，可见性刷新读到「服务已运行」或
 * 「权限已授予」时应当关闭滞留的弹窗；尚未解除时返回 false，保持原样。
 */
export function keepAliveGuideResolved(guide: PermissionGuide | null, state: KeepAliveState): boolean {
  if (guide === null) return false
  if (guide.kind === 'notification-permission') return state.notificationPermission === 'granted'
  if (guide.kind === 'background-blocked' && guide.feature === 'keepAlive') {
    return state.keepRuntimeInBackground !== true || state.foregroundServiceActive
  }
  return false
}

/**
 * 悬浮球类指引是否已被最新原生状态解除（服务已运行，或用户已把开关关掉）。
 */
export function overlayGuideResolved(guide: PermissionGuide | null, state: OverlayBallState): boolean {
  if (guide === null) return false
  if (guide.kind === 'overlay-permission' || guide.kind === 'overlay-internal') {
    return state.enabled !== true || state.serviceActive
  }
  if (guide.kind === 'background-blocked' && guide.feature === 'overlayBall') {
    return state.enabled !== true || state.serviceActive
  }
  return false
}
