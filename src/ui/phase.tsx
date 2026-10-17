/**
 * 运行阶段徽标。
 *
 * 从 `App.tsx` 提出来是因为它被三处屏幕共用（对话门屏、版本管理、设置首页）。
 * 留在外壳里，任何拆出去的屏幕只要显示一次阶段就要反向 import `App.tsx`。
 */
import type { RuntimePhase } from '../platform/types'
import { t } from '../i18n'
import { PHASE_META } from './phaseMeta'

export function PhaseBadge({ phase }: { phase: RuntimePhase }) {
  const meta = PHASE_META[phase]
  return (
    <span className={`phase-badge phase-${meta.tone}`}>
      <span className="phase-dot" />
      {t(meta.label)}
    </span>
  )
}
