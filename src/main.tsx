import { Component, StrictMode, type ErrorInfo, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import '@xterm/xterm/css/xterm.css'
import './styles.css'
import { App } from './App'

const root = document.getElementById('root')

if (root === null) {
  throw new Error('应用根节点不存在')
}

class AppErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('管理端渲染失败', error, info.componentStack)
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children
    return (
      <main className="error-screen" role="alert">
        <h1>页面暂时无法显示</h1>
        <p>管理端遇到意外错误，请重新载入后继续。</p>
        <button type="button" onClick={() => window.location.reload()}>重新载入</button>
      </main>
    )
  }
}

createRoot(root).render(
  <StrictMode>
    <AppErrorBoundary><App /></AppErrorBoundary>
  </StrictMode>,
)
