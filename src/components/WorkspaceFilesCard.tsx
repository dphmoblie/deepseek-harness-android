import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, ExternalLink, FileText, Loader2, RefreshCw, Share2 } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RuntimeWorkspaceFileList } from '../platform/types'
import { errorMessage } from '../ui/messages'
import './WorkspaceFilesCard.css'

/**
 * 卡片状态。
 *
 * `idle` / `loading` / `unavailable` 必须分开：把「还没读」和「读不到」显示成「没有文件」
 * 是把两件不同的事混成一句谎话——用户会去找一个根本不存在的问题，或者相反，以为工作区是空的。
 */
type CardState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; list: RuntimeWorkspaceFileList }
  | { status: 'unavailable'; message: string }

/**
 * 「工作区文件」卡片：把工作区（访客 `root/1`）里的文件直接交给系统分享面板或其它应用打开。
 *
 * 为什么要有它：工作区是应用私有目录，用户在手机侧根本看不到；投递区（inbox/outbox）才是
 * 手机侧能看见的地方，但**搬运要靠按钮**——Agent 写出来的文件不会自己出现在 outbox 里。
 * 以前外壳里有一个「工作区文件」入口，收敛时被删掉了，原生侧四条桥方法（列表 / 分享 / 打开 /
 * 删除）却一直保留着。这里把列表、分享、打开三条接回来，用户不必先导出归档再解压。
 *
 * 边界：路径由原生侧给出（`root/1` 下的相对路径），前端只原样回传；MIME、大小上限（64 MiB）
 * 与符号链接拒绝都在原生侧，界面不复制这套规则。
 */
export function WorkspaceFilesCard() {
  const [state, setState] = useState<CardState>({ status: 'idle' })
  const [busyPath, setBusyPath] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const activeRef = useRef(true)

  useEffect(() => {
    activeRef.current = true
    return () => { activeRef.current = false }
  }, [])

  const load = useCallback((): void => {
    setState({ status: 'loading' })
    setActionError(null)
    void runtimeBridge.listRuntimeWorkspaceFiles()
      .then(list => {
        if (activeRef.current) setState({ status: 'ready', list })
      })
      .catch(error => {
        // 读不到就报读不到：不要退成空列表（那会显示成「工作区里还没有文件」）。
        if (activeRef.current) setState({ status: 'unavailable', message: errorMessage(error) })
      })
  }, [])

  const act = (action: 'share' | 'open', path: string): void => {
    if (busyPath !== null) return
    setBusyPath(path)
    setActionError(null)
    const call = action === 'share'
      ? runtimeBridge.shareRuntimeWorkspaceFile(path)
      : runtimeBridge.openRuntimeWorkspaceFile(path)
    void call
      .catch(error => { if (activeRef.current) setActionError(errorMessage(error)) })
      .finally(() => { if (activeRef.current) setBusyPath(null) })
  }

  const files = state.status === 'ready' ? state.list.files : []

  return (
    <section className="workspace-files" aria-labelledby="workspace-files-title">
      <div className="workspace-files-heading">
        <div>
          <h2 id="workspace-files-title">{t('工作区文件')}</h2>
          <p>{t('Agent 读写的工作区文件：可以直接分享或交给其它应用打开，不必先导出到 outbox。')}</p>
        </div>
        <button
          className="button button-secondary compact-button"
          type="button"
          onClick={load}
          disabled={state.status === 'loading'}
        >
          {state.status === 'loading'
            ? <Loader2 className="spin" size={15} />
            : <RefreshCw size={15} />}
          {t(state.status === 'ready' ? '重新读取' : '读取工作区文件')}
        </button>
      </div>

      {state.status === 'idle' && (
        <p className="workspace-files-note">{t('点「读取工作区文件」查看工作区里有哪些文件。')}</p>
      )}
      {state.status === 'loading' && (
        <p className="workspace-files-note">{t('正在读取工作区文件…')}</p>
      )}
      {state.status === 'unavailable' && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={17} />
          <div>
            <strong>{t('读不到工作区文件')}</strong>
            <span>{state.message}</span>
          </div>
        </div>
      )}
      {state.status === 'ready' && files.length === 0 && (
        <p className="workspace-files-note">{t('工作区里还没有文件。')}</p>
      )}
      {state.status === 'ready' && files.length > 0 && (
        <ul className="workspace-files-list" aria-label={t('工作区文件')}>
          {files.map(path => (
            <li key={path} className="workspace-files-item">
              <FileText size={15} aria-hidden="true" />
              <span className="workspace-files-path">{path}</span>
              <span className="workspace-files-actions">
                <button
                  className="button button-secondary compact-button workspace-files-action"
                  type="button"
                  aria-label={t('分享工作区文件 {0}', path)}
                  disabled={busyPath !== null}
                  onClick={() => act('share', path)}
                >
                  {busyPath === path ? <Loader2 className="spin" size={13} /> : <Share2 size={13} />}
                  <span className="workspace-files-action-label">{t('分享')}</span>
                </button>
                <button
                  className="button button-secondary compact-button workspace-files-action"
                  type="button"
                  aria-label={t('打开工作区文件 {0}', path)}
                  disabled={busyPath !== null}
                  onClick={() => act('open', path)}
                >
                  {busyPath === path ? <Loader2 className="spin" size={13} /> : <ExternalLink size={13} />}
                  <span className="workspace-files-action-label">{t('打开')}</span>
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {state.status === 'ready' && state.list.truncated && (
        <p className="workspace-files-note">{t('文件较多，只列出前 {0} 个。', files.length)}</p>
      )}
      {actionError !== null && (
        <div className="inline-alert warning workspace-files-error" role="alert">
          <AlertTriangle size={17} />
          <div>
            <strong>{t('工作区文件操作失败')}</strong>
            <span>{actionError}</span>
          </div>
        </div>
      )}
    </section>
  )
}
