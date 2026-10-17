/**
 * 运行阶段的展示元数据。
 *
 * 与 `PhaseBadge`（`./phase`）分开放：组件文件只导出组件，
 * 否则 react-refresh 的 fast refresh 语义会退化（整个模块重载而不是热更新）。
 */
import type { RuntimePhase } from '../platform/types'

export const PHASE_META: Record<RuntimePhase, { label: string; tone: string }> = {
  'not-installed': { label: '未安装', tone: 'neutral' },
  preparing: { label: '读取内置环境', tone: 'blue' },
  downloading: { label: '下载中', tone: 'blue' },
  verifying: { label: '正在校验', tone: 'amber' },
  extracting: { label: '正在安装', tone: 'amber' },
  ready: { label: '已就绪', tone: 'green' },
  running: { label: '运行中', tone: 'green' },
  stopping: { label: '正在停止', tone: 'amber' },
  error: { label: '需要处理', tone: 'red' },
}
