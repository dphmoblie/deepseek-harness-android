import { Component, type ErrorInfo, type ReactNode } from 'react'
import { runtimeBridge } from '../platform/native'
import { t } from '../i18n'

/** 导出动作的界面状态。`done` 只表示「已交给系统分享」，不表示用户真的保存了文件。 */
type ExportState = 'idle' | 'busy' | 'done' | 'failed'

/**
 * 顶层渲染错误边界。
 *
 * **为什么必须有**：React 在渲染期抛错时会卸载整棵树，没有边界的表现是**白屏**——
 * 用户看不到原因，开发者也拿不到现场。这个边界是「最后一道」：它只处理
 * **渲染期**的错误，事件处理器与异步代码里的错误不会经过它（那些各自有自己的失败提示）。
 *
 * **为什么这里要放「导出诊断日志」而不只是「重新载入」**：白屏多半出在
 * 「界面依赖的数据结构与运行时实际返回的不一致」这类问题上，重新载入往往只会再白一次。
 * 诊断日志是唯一能把现场带出来的东西，而且它**不依赖已经崩掉的组件树**——
 * 走的是桥调用，因此在这块界面上调用是安全的。
 *
 * **语言**：文案走 `t()` 而不是读 App 的状态。崩溃时 App 的状态可能已经不可用，
 * 而 `t()` 是独立模块（取值直接来自 localStorage），在这里仍然可靠。
 */
export class AppErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean; exportState: ExportState; exportedFileName: string }
> {
  state = { failed: false, exportState: 'idle' as ExportState, exportedFileName: '' }

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 控制台日志是给开发者看的第二现场（真机上是 WebView 的控制台）。
    console.error('应用渲染失败', error, info.componentStack)
  }

  private readonly exportLog = async (): Promise<void> => {
    this.setState({ exportState: 'busy' })
    try {
      const result = await runtimeBridge.shareDiagnosticLog()
      this.setState({ exportState: 'done', exportedFileName: result.fileName })
    } catch {
      // 失败必须说出来：静默失败会让用户以为日志已经导出，排查时白等一场。
      this.setState({ exportState: 'failed' })
    }
  }

  private readonly reload = (): void => {
    window.location.reload()
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children
    const { exportState, exportedFileName } = this.state
    return (
      <main className="error-screen" role="alert">
        <h1>{t('页面暂时无法显示')}</h1>
        <p>{t('应用遇到意外错误，请重新载入后继续。')}</p>
        <div className="error-screen-actions">
          <button className="button button-primary" type="button" onClick={this.reload}>
            {t('重新载入')}
          </button>
          <button
            className="button button-secondary"
            type="button"
            disabled={exportState === 'busy'}
            onClick={() => void this.exportLog()}
          >
            {exportState === 'busy' ? t('正在导出…') : t('导出诊断日志')}
          </button>
        </div>
        {exportState === 'done' && <p role="status">{t('诊断日志已导出：{0}', exportedFileName)}</p>}
        {exportState === 'failed' && <p role="alert">{t('导出诊断日志失败，请重试。')}</p>}
      </main>
    )
  }
}
