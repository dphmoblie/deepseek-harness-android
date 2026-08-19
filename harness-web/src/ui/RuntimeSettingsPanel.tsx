import { Check, ChevronDown, LoaderCircle, RotateCcw, ShieldCheck } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'
import type { SettingsNamespaceView, SettingsDescribeValue } from '../api/types'

const MAX_LEAVES = 64
const MAX_DEPTH = 5
const MAX_STRING_LENGTH = 16_384

type Leaf = {
  path: string[]
  value: string | number | boolean | null
  kind: 'string' | 'number' | 'boolean' | 'null'
}

type ParsedLeafValue =
  | { ok: true; value: string | number | boolean | null }
  | { ok: false; error: string }

/** 编辑 settings.describe 当前明确公开的非敏感叶子字段。 */
export function RuntimeSettingsPanel(props: {
  describe: SettingsDescribeValue | null
  onUpdated: (namespace: SettingsNamespaceView) => void
}): ReactElement {
  const { describe, onUpdated } = props
  const [expanded, setExpanded] = useState<string | null>(null)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    if (describe === null) return
    setExpanded(previous => previous ?? describe.namespaces[0]?.ns ?? null)
  }, [describe])

  const namespaces = useMemo(() => describe?.namespaces ?? [], [describe])

  const commit = async (
    namespace: SettingsNamespaceView,
    leaf: Leaf,
    rawValue: string | boolean,
  ): Promise<void> => {
    if (describe?.writable !== true || busy !== null) return
    const parsed = parseLeafValue(leaf, rawValue)
    if (!parsed.ok) {
      setError(parsed.error)
      return
    }
    const key = leafKey(namespace.ns, leaf.path)
    setBusy(key)
    try {
      const updated = await callUnary(window.location.origin, 'settings.mutate', {
        ns: namespace.ns,
        ops: [{ op: 'set', path: leaf.path, value: parsed.value }],
        expectedRevision: namespace.revision,
      })
      onUpdated(updated)
      setDrafts(previous => { const next = { ...previous }; delete next[key]; return next })
      setNotice(`已保存 ${namespaceLabel(namespace.ns)} · ${leaf.path.join('.')}`)
      setError(null)
    } catch (failure) {
      setError(rpcErrorMessage(`保存 ${namespaceLabel(namespace.ns)} 设置`, failure))
    } finally {
      setBusy(null)
    }
  }

  const reset = async (namespace: SettingsNamespaceView, leaf: Leaf): Promise<void> => {
    if (describe?.writable !== true || busy !== null) return
    const key = leafKey(namespace.ns, leaf.path)
    setBusy(`reset:${key}`)
    try {
      const updated = await callUnary(window.location.origin, 'settings.mutate', {
        ns: namespace.ns,
        ops: [{ op: 'unset', path: leaf.path }],
        expectedRevision: namespace.revision,
      })
      onUpdated(updated)
      setDrafts(previous => { const next = { ...previous }; delete next[key]; return next })
      setNotice(`已重置 ${namespaceLabel(namespace.ns)} · ${leaf.path.join('.')}`)
      setError(null)
    } catch (failure) {
      setError(rpcErrorMessage(`重置 ${namespaceLabel(namespace.ns)} 设置`, failure))
    } finally {
      setBusy(null)
    }
  }

  if (describe === null) return <p className="hint">设置说明尚未载入。</p>
  if (namespaces.length === 0) return <p className="hint">运行时没有公开可编辑的设置命名空间。</p>

  return (
    <section className="runtime-settings-panel" aria-labelledby="runtime-settings-title">
      <h2 id="runtime-settings-title" className="section-title"><ShieldCheck size={16} aria-hidden="true" />运行时公开设置</h2>
      <p className="hint">这里只显示后端通过 settings.describe 公开的命名空间；未接入该接口的插件设置不会出现在这里。带“重启生效”的字段不会自动重启服务。</p>
      {error !== null && <p className="error-bar" role="alert">{error}</p>}
      {notice !== null && <p className="success-bar" role="status"><Check size={15} />{notice}</p>}
      {!describe.writable && <p className="hint">当前 profile 只读，设置编辑已禁用。</p>}
      <div className="runtime-settings-list">
        {namespaces.map(namespace => {
          const isExpanded = expanded === namespace.ns
          const leaves = flattenLeaves(namespace.value, namespace.secrets.map(secret => secret.path))
          const secrets = namespace.secrets.slice(0, MAX_LEAVES)
          return (
            <article className="runtime-settings-card" key={namespace.ns}>
              <button type="button" className="runtime-settings-header" aria-expanded={isExpanded} onClick={() => setExpanded(isExpanded ? null : namespace.ns)}>
                <span><strong>{namespaceLabel(namespace.ns)}</strong><small>{namespace.ns} · {leaves.length} 个公开字段 · {secrets.length} 个敏感字段</small></span>
                <span className="runtime-settings-header-meta">
                  {namespace.applies === 'restart' && <span className="badge">重启生效</span>}
                  <ChevronDown size={16} aria-hidden="true" />
                </span>
              </button>
              {isExpanded && (
                <div className="runtime-settings-body">
                  {secrets.length > 0 && (
                    <div className="settings-secret-list" aria-label={`${namespaceLabel(namespace.ns)} 敏感字段`}>
                      <div className="settings-secret-summary"><ShieldCheck size={15} /><span>敏感字段只显示配置状态，不回显内容，也不在通用编辑器中修改。</span></div>
                      {secrets.map((secret, index) => (
                        <div className="settings-secret-row" key={`${secret.path.join('.')}::${index}`}>
                          <code>{secret.path.length === 0 ? '（命名空间根）' : secret.path.join('.')}</code>
                          <span className={secret.set ? 'badge badge-running' : 'badge'}>{secret.set ? '已设置' : '未设置'}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {leaves.length === 0 ? <p className="hint">没有可编辑的公开字段。</p> : leaves.map(leaf => {
                    const key = leafKey(namespace.ns, leaf.path)
                    const value = drafts[key] ?? formatLeafValue(leaf.value)
                    const saving = busy === key
                    const resetting = busy === `reset:${key}`
                    return (
                      <div className="runtime-setting-row" key={key}>
                        <label htmlFor={`runtime-setting-${key}`}>{leaf.path.join('.')}</label>
                        {leaf.kind === 'boolean' ? (
                          <input id={`runtime-setting-${key}`} type="checkbox" checked={value === 'true'} disabled={!describe.writable || busy !== null} onChange={event => void commit(namespace, leaf, event.target.checked)} />
                        ) : (
                          <input
                            id={`runtime-setting-${key}`}
                            className="field"
                            type={leaf.kind === 'number' ? 'number' : 'text'}
                            inputMode={leaf.kind === 'number' ? 'decimal' : undefined}
                            value={value}
                            maxLength={leaf.kind === 'string' ? MAX_STRING_LENGTH : undefined}
                            disabled={!describe.writable || busy !== null}
                            onChange={event => setDrafts(previous => ({ ...previous, [key]: event.target.value }))}
                            onKeyDown={event => { if (event.key === 'Enter') void commit(namespace, leaf, value) }}
                          />
                        )}
                        <button type="button" className="icon-button" aria-label={`保存 ${leaf.path.join('.')}`} title="保存" disabled={!describe.writable || busy !== null} onClick={() => void commit(namespace, leaf, value)}>
                          {saving ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}
                        </button>
                        <button type="button" className="icon-button" aria-label={`重置 ${leaf.path.join('.')}`} title="重置" disabled={!describe.writable || busy !== null} onClick={() => void reset(namespace, leaf)}>
                          {resetting ? <LoaderCircle className="spin" size={15} /> : <RotateCcw size={15} />}
                        </button>
                      </div>
                    )
                  })}
                  {namespace.schema !== undefined && <details className="settings-schema"><summary>查看字段说明</summary><pre>{safeJson(namespace.schema)}</pre></details>}
                </div>
              )}
            </article>
          )
        })}
      </div>
    </section>
  )
}

function flattenLeaves(value: unknown, secretPaths: string[][], path: string[] = [], depth = 0): Leaf[] {
  if (secretPaths.some(secret => samePath(secret, path) || isPrefix(secret, path))) return []
  if (path.length > 0 && (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' || value === null)) {
    if (typeof value === 'number' && !Number.isFinite(value)) return []
    return [{ path, value, kind: leafKind(value) }]
  }
  if (depth >= MAX_DEPTH || !isRecord(value)) return []
  const leaves: Leaf[] = []
  for (const [key, child] of Object.entries(value)) {
    if (!isSafePathSegment(key)) continue
    leaves.push(...flattenLeaves(child, secretPaths, [...path, key], depth + 1))
    if (leaves.length >= MAX_LEAVES) return leaves.slice(0, MAX_LEAVES)
  }
  return leaves
}

function leafKind(value: string | number | boolean | null): Leaf['kind'] {
  if (value === null) return 'null'
  if (typeof value === 'string') return 'string'
  if (typeof value === 'number') return 'number'
  return 'boolean'
}

function parseLeafValue(leaf: Leaf, raw: string | boolean): ParsedLeafValue {
  if (leaf.kind === 'boolean') return { ok: true, value: raw === true || raw === 'true' }
  if (leaf.kind === 'null') return { ok: true, value: null }
  if (typeof raw !== 'string') return { ok: false, error: '文本设置格式无效' }
  if (leaf.kind === 'number') {
    if (raw.trim() === '') return { ok: false, error: '数字设置不能为空' }
    const value = Number(raw)
    if (!Number.isFinite(value)) return { ok: false, error: '数字设置必须是有限数值' }
    return { ok: true, value }
  }
  if ([...raw].length > MAX_STRING_LENGTH || hasInvisibleCharacters(raw)) {
    return { ok: false, error: '文本设置长度或字符格式无效' }
  }
  return { ok: true, value: raw }
}

function formatLeafValue(value: Leaf['value']): string {
  return value === null ? '' : String(value)
}

function leafKey(namespace: string, path: string[]): string {
  return `${namespace}:${path.join('.')}`
}

function namespaceLabel(namespace: string): string {
  const labels: Record<string, string> = {
    shell: 'Shell',
    'agent-loop': 'Agent Loop',
    'web-search-deepseek': 'DeepSeek 网页搜索',
  }
  return labels[namespace] ?? namespace
}

function safeJson(value: unknown): string {
  try {
    const text = JSON.stringify(value, null, 2)
    return text === undefined ? '{}' : text.slice(0, 12_000)
  } catch {
    return '{}'
  }
}

function samePath(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((part, index) => part === right[index])
}

function isPrefix(prefix: string[], path: string[]): boolean {
  return prefix.length < path.length && prefix.every((part, index) => part === path[index])
}

function isSafePathSegment(value: string): boolean {
  return value.length > 0 && value.length <= 128 && /^[A-Za-z0-9_.-]+$/u.test(value)
}

function hasInvisibleCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) return true
    if (code === 0x200b || code === 0x200e || code === 0x200f || code === 0xfeff) return true
    if ((code >= 0x202a && code <= 0x202e) || (code >= 0x2060 && code <= 0x2064) || (code >= 0x2066 && code <= 0x206f)) return true
  }
  return false
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
