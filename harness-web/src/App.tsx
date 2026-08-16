import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from './api/wire'
import { RpcFailure, TransportError } from './api/wire'
import type { HostDescribeValue, SessionListValue } from './api/types'

/**
 * 应用骨架：验证协议层连通性（host.describe + session.list）。
 * 四个功能模块（会话/任务/目录/设置）在下一阶段实现。
 */
export function App(): ReactElement {
  const [host, setHost] = useState<HostDescribeValue | null>(null)
  const [sessions, setSessions] = useState<SessionListValue | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const [hostValue, sessionValue] = await Promise.all([
          callUnary(window.location.origin, 'host.describe', {}),
          callUnary(window.location.origin, 'session.list', {}),
        ])
        if (!cancelled) {
          setHost(hostValue)
          setSessions(sessionValue)
        }
      } catch (failure) {
        if (cancelled) return
        if (failure instanceof RpcFailure) {
          setError(`业务失败 ${failure.code}：${failure.message}`)
        } else if (failure instanceof TransportError) {
          setError(failure.message)
        } else {
          setError(String(failure))
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <main className="app">
      <header className="app-header">
        <h1>DeepSeek Harness</h1>
        {host !== null && (
          <span className="app-version">
            已连接 · v{host.version}
          </span>
        )}
      </header>
      {error !== null && <p className="app-error">{error}</p>}
      <section className="app-body">
        <h2>会话</h2>
        {sessions === null ? (
          <p className="app-hint">正在加载会话列表…</p>
        ) : sessions.items.length === 0 ? (
          <p className="app-hint">暂无会话</p>
        ) : (
          <ul className="session-list">
            {sessions.items.map((item) => (
              <li key={item.sessionId} className="session-row">
                <span className="session-id">{item.sessionId.slice(0, 8)}</span>
                <span className="session-state">{item.running ? '运行中' : '空闲'}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}
