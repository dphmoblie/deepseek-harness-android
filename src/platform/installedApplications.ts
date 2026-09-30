import type { InstalledApplicationsPage } from './types'

/** 桥接只传标签及包名；列表分页限制不等于白名单数量限制。 */
export function validateInstalledApplications(value: unknown): InstalledApplicationsPage {
  if (!value || typeof value !== 'object') throw new Error('应用清单格式无效')
  const state = value as Record<string, unknown>
  if (!Array.isArray(state.apps) || state.apps.length > 100 || !Number.isSafeInteger(state.total) || (state.total as number) < 0 ||
      (state.nextOffset !== null && (!Number.isSafeInteger(state.nextOffset) || (state.nextOffset as number) < 1))) {
    throw new Error('应用清单分页格式无效')
  }
  const apps = state.apps.map((raw: unknown) => {
    if (!raw || typeof raw !== 'object') throw new Error('应用信息格式无效')
    const app = raw as Record<string, unknown>
    if (typeof app.packageName !== 'string' || app.packageName.length > 255 || !/^[A-Za-z][A-Za-z0-9_.]*$/u.test(app.packageName) ||
        typeof app.label !== 'string' || app.label.length > 160 || [...app.label].some(char => char.charCodeAt(0) < 0x20 || char.charCodeAt(0) === 0x7f) ||
        typeof app.system !== 'boolean' || typeof app.selectable !== 'boolean') throw new Error('应用信息格式无效')
    return { packageName: app.packageName, label: app.label, system: app.system, selectable: app.selectable }
  })
  if (new Set(apps.map(app => app.packageName)).size !== apps.length || apps.length > (state.total as number) ||
      (state.nextOffset !== null && (state.nextOffset as number) >= (state.total as number))) throw new Error('应用清单分页不一致')
  return { apps, total: state.total as number, nextOffset: state.nextOffset as number | null }
}
