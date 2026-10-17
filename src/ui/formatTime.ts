/**
 * 时间写法：持久化记录时间与阶段耗时。
 *
 * 从 `App.tsx` 提出来：自检面板、设置页与诊断区块都要显示「记录于 <本地时间>」，
 * 留在外壳里会让每个拆出去的屏幕反向 import `App.tsx`。
 */

/**
 * 格式化持久化的恢复记录时间。
 * 无记录时返回空串，界面据此隐藏该行，不显示伪造或推断出来的时间。
 */
export function formatRecordedAt(millis: number | undefined): string {
  if (millis === undefined || !Number.isFinite(millis) || millis <= 0) return ''
  try {
    return new Date(millis).toLocaleString()
  } catch {
    return ''
  }
}
