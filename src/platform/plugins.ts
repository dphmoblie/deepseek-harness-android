import type { PluginCatalog, PluginRequest } from './types'

const packageId = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/
const entryId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
function invalid(): never { throw new Error('插件数据格式无效') }
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
  return value as Record<string, unknown>
}
function text(value: unknown, pattern: RegExp, max: number): string {
  if (typeof value !== 'string' || value.length > max || !pattern.test(value)) invalid()
  return value
}
function bool(value: unknown): boolean { if (typeof value !== 'boolean') invalid(); return value }

/** 原生接口和浏览器预览共用输入校验，防止任意路径或命令进入设备。 */
export function validatePluginRequest(request: PluginRequest): PluginRequest {
  if (!['list', 'enable', 'child', 'update'].includes(request.operation)) invalid()
  if (request.operation === 'list') return { operation: 'list' }
  const id = text(request.id, packageId, 214)
  if (id.includes('..')) invalid()
  if (request.operation === 'update') return { operation: 'update', id }
  const enabled = bool(request.enabled)
  return request.operation === 'enable' ? { operation: 'enable', id, enabled }
    : { operation: 'child', id, enabled, childId: text(request.childId, entryId, 128) }
}

/** 仅保留显示所需字段；React 负责文本转义，禁止解释服务端 HTML。 */
export function validatePluginCatalog(value: unknown): PluginCatalog {
  const payload = record(value)
  if (!Array.isArray(payload.plugins) || payload.plugins.length > 32) invalid()
  let childrenCount = 0
  const ids = new Set<string>()
  return { plugins: payload.plugins.map((raw: unknown) => {
    const group = record(raw)
    const id = text(group.id, packageId, 214)
    if (id.includes('..') || ids.has(id)) invalid()
    ids.add(id)
    const file = text(group.file, /^[A-Za-z0-9_./-]+\.(?:json|ya?ml)$/, 160)
    if (file.startsWith('/') || file.split('/').some(part => part === '..')) invalid()
    if (!Array.isArray(group.children) || (childrenCount += group.children.length) > 1024) invalid()
    const childIds = new Set<string>()
    return {
      id, file,
      version: group.version === null ? null : text(group.version, /^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$/, 64),
      enabled: bool(group.enabled), protected: bool(group.protected), official: bool(group.official),
      installed: bool(group.installed), readable: bool(group.readable),
      children: group.children.map((rawChild: unknown) => {
        const child = record(rawChild)
        const childId = text(child.id, entryId, 128)
        if (childIds.has(childId)) invalid()
        childIds.add(childId)
        return { id: childId, name: text(child.name, /^[A-Za-z0-9@_./:-]+$/, 214), enabled: bool(child.enabled), effectiveEnabled: bool(child.effectiveEnabled), protected: bool(child.protected) }
      }),
    }
  }) }
}
