import { useCallback, useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary, RpcFailure, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import type {
  ConfigurableProviderView,
  HostDescribeValue,
  SettingsDescribeValue,
} from '../api/types'

/**
 * 设置页：模型提供商概览 + 敏感配置项（secrets）编辑 + 运行时信息。
 * 敏感值经 settings.mutate 写入对应 provider 命名空间，永不回显。
 */
export function SettingsView(): ReactElement {
  const [providers, setProviders] = useState<ConfigurableProviderView[]>([])
  const [describe, setDescribe] = useState<SettingsDescribeValue | null>(null)
  const [host, setHost] = useState<HostDescribeValue | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [secretDrafts, setSecretDrafts] = useState<Record<string, string>>({})
  const [expanded, setExpanded] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setError(null)
    try {
      const [providerValue, describeValue, hostValue] = await Promise.all([
        callUnary(window.location.origin, 'llm.providers', {}),
        callUnary(window.location.origin, 'settings.describe', {}),
        callUnary(window.location.origin, 'host.describe', {}),
      ])
      setProviders(providerValue.providers)
      setDescribe(describeValue)
      setHost(hostValue)
    } catch (failure) {
      if (failure instanceof RpcFailure) setError(describeFailure(failure.code, failure.message))
      else if (failure instanceof TransportError) setError(failure.message)
      else setError(String(failure))
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const namespaceFor = (ns: string) => describe?.namespaces.find((item) => item.ns === ns)

  const submitSecret = async (ns: string, path: string[], value: string): Promise<void> => {
    if (value === '') return
    setError(null)
    try {
      await callUnary(window.location.origin, 'settings.mutate', {
        ns,
        ops: [{ op: 'set', path, value }],
      })
      setSecretDrafts((prev) => ({ ...prev, [secretKey(ns, path)]: '' }))
      await reload()
    } catch (failure) {
      if (failure instanceof RpcFailure) setError(describeFailure(failure.code, failure.message))
      else setError(String(failure))
    }
  }

  return (
    <main className="view">
      <header className="view-header">
        <h1>设置</h1>
        <button type="button" className="btn" onClick={() => void reload()}>刷新</button>
      </header>
      {error !== null && <p className="error-bar" onClick={() => setError(null)}>{error}</p>}
      <div className="view-body">
        {describe !== null && !describe.writable && (
          <p className="hint">当前运行时设置只读，无法修改配置</p>
        )}

        <h2 className="section-title">模型提供商</h2>
        {providers.length === 0 ? (
          <p className="hint">没有可配置的提供商</p>
        ) : (
          providers.map((provider) => {
            const namespace = namespaceFor(provider.settingsNs)
            const isExpanded = expanded === provider.settingsNs
            return (
              <div key={provider.settingsNs} className="provider-card">
                <button
                  type="button"
                  className="list-row"
                  onClick={() => setExpanded(isExpanded ? null : provider.settingsNs)}
                >
                  <span className="list-main">
                    <span className="list-title">
                      {provider.displayName}
                      {provider.active && <span className="badge badge-running">已启用</span>}
                    </span>
                    <span className="list-sub">{provider.provider}</span>
                  </span>
                  <span className="list-time">{isExpanded ? '收起' : '配置'}</span>
                </button>
                {isExpanded && namespace !== undefined && (
                  <div className="provider-config">
                    {namespace.secrets.length === 0 ? (
                      <p className="hint">该提供商没有可配置的敏感项</p>
                    ) : (
                      namespace.secrets.map((secret) => {
                        const key = secretKey(namespace.ns, secret.path)
                        return (
                          <div key={key} className="secret-row">
                            <label className="field-label">{secret.path.join('.')}</label>
                            <input
                              className="field"
                              type="password"
                              autoComplete="off"
                              placeholder={secret.set ? '已配置（留空不变）' : '未配置'}
                              value={secretDrafts[key] ?? ''}
                              onChange={(event) =>
                                setSecretDrafts((prev) => ({ ...prev, [key]: event.target.value }))
                              }
                            />
                            <button
                              type="button"
                              className="btn"
                              disabled={(secretDrafts[key] ?? '') === ''}
                              onClick={() => void submitSecret(namespace.ns, secret.path, secretDrafts[key] ?? '')}
                            >
                              保存
                            </button>
                          </div>
                        )
                      })
                    )}
                  </div>
                )}
              </div>
            )
          })
        )}

        {host !== null && (
          <>
            <h2 className="section-title">运行时</h2>
            <dl className="host-info">
              <dt>版本</dt>
              <dd>{host.version}</dd>
              <dt>工作目录</dt>
              <dd>{host.cwd}</dd>
              {host.provider !== undefined && <dt>当前模型</dt>}
              {host.provider !== undefined && <dd>{host.provider} / {host.model ?? ''}</dd>}
              <dt>挂接会话</dt>
              <dd>{host.attachedSessions}</dd>
            </dl>
          </>
        )}
      </div>
    </main>
  )
}

function secretKey(ns: string, path: string[]): string {
  return `${ns}::${path.join('::')}`
}
