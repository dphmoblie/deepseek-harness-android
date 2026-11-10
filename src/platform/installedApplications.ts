import type { InstalledApplicationsPage, InstalledApplicationsPermission } from './types'

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

/**
 * 「读取应用列表」权限状态：两个字段都必须是布尔，缺一个都不补默认值。
 *
 * 补齐默认值会让「旧原生没回这个字段」被读成「已授予」或「不存在」，两种都会让界面
 * 在该引导的时候不引导；宁可整条拒收，让界面按「读不到状态」处理。
 */
export function validateInstalledApplicationsPermission(value: unknown): InstalledApplicationsPermission {
  if (!value || typeof value !== 'object') throw new Error('应用列表权限结果格式无效')
  const state = value as Record<string, unknown>
  if (typeof state.supported !== 'boolean' || typeof state.granted !== 'boolean') {
    throw new Error('应用列表权限结果格式无效')
  }
  return { supported: state.supported, granted: state.granted }
}
