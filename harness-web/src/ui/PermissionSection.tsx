import { Check, LoaderCircle, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'
import type { SettingsNamespaceView } from '../api/types'

const PERMISSION_NS = 'permission'
const FULL_ACCESS_PRESET = 'danger-full-access'

type PermissionOption = { id: string; label: string }

type PermissionState =
  | { status: 'loading' }
  | { status: 'unavailable'; reason: string }
  | { status: 'ready'; writable: boolean; current: string; options: PermissionOption[]; revision: number }
  | { status: 'error'; message: string }

/** 桌面端同款预设名展示：kebab-case 转 Title Case。 */
function displayPresetName(name: string): string {
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(name)) return name
  return name.split('-').map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ')
}

function displayPermissionPreset(value: string, name: string): string {
  return value === FULL_ACCESS_PRESET ? '完全访问' : displayPresetName(name)
}

/** 从 settings 命名空间 schema 中宽松提取 defaultPreset 的枚举。 */
function findDefaultPresetEnum(schema: unknown): string[] | null {
  if (Array.isArray(schema)) {
    for (const item of schema) {
      const found = findDefaultPresetEnum(item)
      if (found !== null) return found
    }
    return null
  }
  if (typeof schema !== 'object' || schema === null) return null
  const record = schema as Record<string, unknown>
  if (typeof record.defaultPreset === 'object' && record.defaultPreset !== null) {
    const preset = record.defaultPreset as Record<string, unknown>
    if (Array.isArray(preset.enum)) {
      const values = preset.enum.filter((item): item is string => typeof item === 'string')
      if (values.length > 0) return values
    }
  }
  if (typeof record.properties === 'object' && record.properties !== null) {
    const found = findDefaultPresetEnum(record.properties)
    if (found !== null) return found
  }
  if ('items' in record) {
    const found = findDefaultPresetEnum(record.items)
    if (found !== null) return found
  }
  for (const value of Object.values(record)) {
    const found = findDefaultPresetEnum(value)
    if (found !== null) return found
  }
  return null
}

function readDefaultPreset(namespace: SettingsNamespaceView): string | null {
  if (typeof namespace.value === 'object' && namespace.value !== null) {
    const value = (namespace.value as Record<string, unknown>).defaultPreset
    if (typeof value === 'string' && value !== '') return value
  }
  if (typeof namespace.value === 'string' && namespace.value !== '') return namespace.value
  return null
}

/**
 * 访问权限（新会话默认）设置 —— 对齐桌面端 dsh web 的权限预设行。
 * 读写容器内 dsh 的 settings 命名空间 "permission"（defaultPreset），
 * 例如 danger-full-access（完全访问）/ workspace-write（仅工作区）等。
 */
export function PermissionSection(): ReactElement {
  const [state, setState] = useState<PermissionState>({ status: 'loading' })
  const [draft, setDraft] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const generationRef = useRef(0)

  const reload = useCallback(async () => {
    const generation = ++generationRef.current
    setState({ status: 'loading' })
    try {
      const describe = await callUnary(window.location.origin, 'settings.describe', {})
      if (generationRef.current !== generation) return
      const namespace = describe.namespaces.find((item) => item.ns === PERMISSION_NS)
      if (namespace === undefined) {
        setState({ status: 'unavailable', reason: '当前版本未提供权限预设配置（settings.permission 不存在）' })
        return
      }
      const current = readDefaultPreset(namespace)
      if (current === null) {
        setState({ status: 'unavailable', reason: '权限预设配置不可用（未声明 defaultPreset）' })
        return
      }
      const enumValues = findDefaultPresetEnum(namespace.schema) ?? [current]
      const options = enumValues.map((id) => ({ id, label: displayPermissionPreset(id, id) }))
      setState({
        status: 'ready',
        writable: describe.writable,
        current,
        options,
        revision: namespace.revision,
      })
      setDraft(current)
    } catch (failure) {
      if (generationRef.current !== generation) return
      setState({ status: 'error', message: rpcErrorMessage('载入权限预设', failure) })
    }
  }, [])

  useEffect(() => { void reload() }, [reload])

  const save = useCallback(async () => {
    if (state.status !== 'ready' || draft === null || draft === state.current) return
    setBusy(true)
    try {
      const updated = await callUnary(window.location.origin, 'settings.mutate', {
        ns: PERMISSION_NS,
        ops: [{ op: 'set', path: ['defaultPreset'], value: draft }],
        expectedRevision: state.revision,
      })
      const current = readDefaultPreset(updated)
      if (current !== null) {
        setState({ ...state, current, revision: updated.revision })
        setDraft(current)
      }
    } catch (failure) {
      setState({ status: 'error', message: rpcErrorMessage('保存权限预设', failure) })
    } finally {
      setBusy(false)
    }
  }, [state, draft])

  return (
    <section className="permission-section">
      <h2 className="section-title"><ShieldCheck size={16} aria-hidden="true" />访问权限（新会话默认）</h2>
      <p className="hint">选择新建会话默认的文件访问程度：完全访问不限制文件读写；其他预设按受限范围执行。修改对之后创建的会话生效。</p>
      {state.status === 'loading' && <p className="hint"><LoaderCircle className="spin" size={14} aria-hidden="true" />载入中…</p>}
      {state.status === 'unavailable' && <p className="hint">{state.reason}</p>}
      {state.status === 'error' && <p className="session-title-error">{state.message}</p>}
      {state.status === 'ready' && (
        <>
          {state.options.map((option) => (
            <label key={option.id} className="list-row">
              <input
                type="radio"
                name="permission-default"
                checked={draft === option.id}
                disabled={!state.writable || busy}
                onChange={() => setDraft(option.id)}
              />
              <span>{option.label}</span>
              {state.current === option.id && <Check size={14} aria-hidden="true" />}
            </label>
          ))}
          <div className="secret-row">
            <button
              type="button"
              className="btn"
              disabled={!state.writable || busy || draft === null || draft === state.current}
              onClick={() => void save()}
            >
              {busy && <LoaderCircle className="spin" size={16} aria-hidden="true" />}
              保存
            </button>
            {state.current === FULL_ACCESS_PRESET && <span className="hint">当前为完全访问（自动提权）</span>}
            {!state.writable && <span className="hint">当前配置为只读，不能修改</span>}
          </div>
        </>
      )}
    </section>
  )
}
