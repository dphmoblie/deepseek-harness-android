import type { PluginCatalog, PluginRequest } from './types'

const packageId = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/
const entryId = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
const semver = /^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$/
/*
 * 导入来源的校验必须与访客脚本 `validateImportSource`、原生 Kotlin 侧三处**同一套规则**：
 * 任何一处放松，都会让别的入口把不该进设备的字符串送进 npm。这里刻意保持逐条对应。
 */
const sourceMax = 512
const sourceChars = /^[A-Za-z0-9@/:._~%+?#=&-]+$/
const sourceHost = /^[A-Za-z0-9.-]+(?::[0-9]{1,5})?$/
const sourceRef = /^[A-Za-z0-9._/-]{1,128}$/
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
/**
 * 导入来源：npm 包名（含作用域名）或 `https://` / `git+https://` 地址。
 *
 * 与访客脚本 `validateImportSource` 保持同一条规则线：拒绝 `..`（含 `%2e` 编码形式）、
 * 以 `-` 开头的输入（防 npm 选项注入）、白名单以外的字符（空白、引号、尖括号、反引号、
 * 换行都会在这里被拒），以及 URL 里的 userinfo——主机名正则不接受 `@`，
 * 因此 `https://user:pass@host/x.tgz` 在结构检查处即被拒。
 * 失败一律抛同一句文案，**不回显**用户输入。
 */
function importSource(value: unknown): string {
  const raw = text(value, sourceChars, sourceMax)
  if (raw.startsWith('-') || raw.includes('..') || /%2e/i.test(raw)) invalid()
  if (packageId.test(raw)) return raw
  const prefix = raw.startsWith('git+https://') ? 'git+https://' : raw.startsWith('https://') ? 'https://' : null
  if (prefix === null) invalid()
  const rest = raw.slice(prefix.length)
  if (!sourceHost.test(rest.split(/[/?#]/, 1)[0])) invalid()
  if (prefix === 'git+https://') {
    const hash = rest.indexOf('#')
    if (hash !== -1 && !sourceRef.test(rest.slice(hash + 1))) invalid()
  }
  return raw
}

/** 原生接口和浏览器预览共用输入校验，防止任意路径或命令进入设备。 */
export function validatePluginRequest(request: PluginRequest): PluginRequest {
  if (!['list', 'enable', 'child', 'update', 'import', 'rollback'].includes(request.operation)) invalid()
  if (request.operation === 'list') return { operation: 'list' }
  if (request.operation === 'import') {
    const source = importSource(request.source)
    // `id` 只是「期望的包名」，用于地址形态装完后消歧；不填则由访客脚本按补丁声明推导。
    if (request.id === undefined) return { operation: 'import', source }
    const hint = text(request.id, packageId, 214)
    if (hint.includes('..')) invalid()
    return { operation: 'import', source, id: hint }
  }
  const id = text(request.id, packageId, 214)
  if (id.includes('..')) invalid()
  if (request.operation === 'update') return { operation: 'update', id }
  if (request.operation === 'rollback') return { operation: 'rollback', id }
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
      version: group.version === null ? null : text(group.version, semver, 64),
      // 缺键与 `null` 同义：没有上一版、上一版已被安全清理或该包受保护。
      rollback: group.rollback === undefined || group.rollback === null ? null : text(group.rollback, semver, 64),
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
