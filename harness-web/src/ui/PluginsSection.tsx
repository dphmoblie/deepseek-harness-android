import { LoaderCircle, Plug, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'
import type { PluginInventoryEntry } from '../api/types'

type PluginsState =
  | { status: 'loading' }
  | { status: 'unavailable'; reason: string }
  | { status: 'ready'; entries: PluginInventoryEntry[] }

function phaseLabel(entry: PluginInventoryEntry): { text: string; tone: 'ok' | 'bad' | 'warn' | 'dim' } {
  if (!entry.enabled) return { text: '已停用', tone: 'dim' }
  switch (entry.fiberPhase) {
    case 'active': return { text: '运行中', tone: 'ok' }
    case 'failed': return { text: '加载失败', tone: 'bad' }
    case 'pending':
    case 'loading': return { text: '加载中', tone: 'warn' }
    case 'unloading': return { text: '卸载中', tone: 'warn' }
    default: return { text: '未激活', tone: 'dim' }
  }
}

/** 插件管理（查看状态）——对齐桌面端 settings-plugins 的清单视图。 */
export function PluginsSection(): ReactElement {
  const [state, setState] = useState<PluginsState>({ status: 'loading' })
  const generationRef = useRef(0)

  const reload = useCallback(async () => {
    const generation = ++generationRef.current
    setState({ status: 'loading' })
    try {
      const value = await callUnary(window.location.origin, 'pluginInventory.list', {})
      if (generationRef.current !== generation) return
      setState({ status: 'ready', entries: value.entries })
    } catch (failure) {
      if (generationRef.current !== generation) return
      setState({ status: 'unavailable', reason: rpcErrorMessage('载入插件清单', failure) })
    }
  }, [])

  useEffect(() => { void reload() }, [reload])

  const failed = state.status === 'ready'
    ? state.entries.filter((entry) => entry.enabled && entry.fiberPhase === 'failed')
    : []

  return (
    <section className="plugins-section">
      <h2 className="section-title"><Plug size={16} aria-hidden="true" />插件管理</h2>
      <p className="hint">查看已安装插件及其运行状态。启用/停用需在服务器配置中调整；加载失败的插件可通过重启应用重试。</p>
      {state.status === 'loading' && <p className="hint"><LoaderCircle className="spin" size={14} aria-hidden="true" />载入中…</p>}
      {state.status === 'unavailable' && <p className="hint">{state.reason}</p>}
      {state.status === 'ready' && (
        <>
          <div className="plugin-list">
            {state.entries.map((entry) => {
              const badge = phaseLabel(entry)
              return (
                <div key={entry.entryId} className="list-row">
                  <span className="plugin-name" title={entry.moduleName}>{entry.moduleName}</span>
                  <span className={`plugin-badge plugin-badge-${badge.tone}`}>{badge.text}</span>
                </div>
              )
            })}
          </div>
          {failed.length > 0 && (
            <div className="secret-row">
              <p className="session-title-error">{failed.length} 个插件加载失败，可能影响对应功能（可重启应用重试）</p>
              <button type="button" className="btn" onClick={() => window.location.reload()}>
                <RefreshCw size={16} aria-hidden="true" />重启应用
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
