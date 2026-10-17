/** 独立 Harness 版本接口；参数只能是操作枚举和精确版本号，不能传入路径或 URL。 */
export type HarnessVersionOperation = 'list' | 'install' | 'select' | 'delete'
export interface HarnessVersionsState {
  active: string
  bundledVersion: string | null
  installed: { version: string; bytes: number; active: boolean; compatible: boolean }[]
}
export interface HarnessReleaseList {
  entries: { version: string; tags: string[] }[]
}
const VERSION = /^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?$/u
function version(value: unknown, bundled = false): string {
  if (bundled && value === 'bundled') return value
  if (typeof value !== 'string' || value.length > 96 || !VERSION.test(value)) throw new Error('Harness 版本号无效')
  return value
}
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Harness 版本状态无效')
  return value as Record<string, unknown>
}
export function harnessVersionRequest(operation: HarnessVersionOperation, selected?: string): { operation: HarnessVersionOperation; version?: string } {
  if (!['list', 'install', 'select', 'delete'].includes(operation)) throw new Error('Harness 版本操作无效')
  return operation === 'list' ? { operation } : { operation, version: version(selected, operation === 'select') }
}
export function validateHarnessVersions(value: unknown): HarnessVersionsState {
  const data = object(value)
  if (!Array.isArray(data.installed) || data.installed.length > 1000) throw new Error('Harness 版本列表无效')
  const active = version(data.active, true)
  const installed = data.installed.map(item => {
    const entry = object(item)
    const name = version(entry.version)
    if (typeof entry.active !== 'boolean' || entry.active !== (active === name) || typeof entry.compatible !== 'boolean' ||
      typeof entry.bytes !== 'number' || !Number.isSafeInteger(entry.bytes) || entry.bytes < 0) throw new Error('Harness 本地版本条目无效')
    return { version: name, bytes: entry.bytes, active: entry.active, compatible: entry.compatible }
  })
  if (new Set(installed.map(entry => entry.version)).size !== installed.length) throw new Error('Harness 本地版本重复')
  if (active !== 'bundled' && !installed.some(entry => entry.active)) throw new Error('Harness 默认版本缺失')
  return { active, bundledVersion: data.bundledVersion === null ? null : version(data.bundledVersion), installed }
}
export function validateHarnessReleases(value: unknown): HarnessReleaseList {
  const data = object(value)
  if (!Array.isArray(data.entries) || data.entries.length > 1000) throw new Error('Harness 上游版本列表无效')
  const entries = data.entries.map(item => {
    const entry = object(item)
    if (!Array.isArray(entry.tags) || entry.tags.length > 4 || entry.tags.some(tag => !['latest', 'next', 'alpha', 'beta'].includes(String(tag)))) throw new Error('Harness 发布标签无效')
    return { version: version(entry.version), tags: entry.tags as string[] }
  })
  if (new Set(entries.map(entry => entry.version)).size !== entries.length) throw new Error('Harness 上游版本重复')
  return { entries }
}
