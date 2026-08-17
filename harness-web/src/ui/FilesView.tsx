import { ArrowLeft, File, Folder, Home } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary, RpcFailure, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import type { DirectoryListing } from '../api/types'

/**
 * 目录浏览：遍历 guest Linux 文件系统（host.listDirectory），
 * 支持面包屑导航与新建目录。文件条目 v1 仅展示，不可打开。
 */
export function FilesView(props: { onBack: () => void }): ReactElement {
  const { onBack } = props
  const [listing, setListing] = useState<DirectoryListing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')

  const open = useCallback(async (path?: string) => {
    setError(null)
    try {
      const value = await callUnary(window.location.origin, 'host.listDirectory', path === undefined ? {} : { path })
      setListing(value)
    } catch (failure) {
      if (failure instanceof RpcFailure) setError(describeFailure(failure.code, failure.message))
      else if (failure instanceof TransportError) setError(failure.message)
      else setError(String(failure))
    }
  }, [])

  useEffect(() => {
    void open()
  }, [open])

  const createDirectory = async (): Promise<void> => {
    if (listing === null) return
    const name = newName.trim()
    if (name === '') return
    try {
      await callUnary(window.location.origin, 'host.createDirectory', { path: listing.path, name })
      setNewName('')
      setCreating(false)
      await open(listing.path)
    } catch (failure) {
      if (failure instanceof RpcFailure) setError(describeFailure(failure.code, failure.message))
      else setError(String(failure))
    }
  }

  return (
    <main className="view">
      <header className="view-header secondary-header">
        <button type="button" className="icon-button" aria-label="返回对话" title="返回对话" onClick={onBack}><ArrowLeft size={20} /></button>
        <h1>目录</h1>
        <button type="button" className="btn btn-primary" onClick={() => setCreating((prev) => !prev)}>
          新建目录
        </button>
      </header>
      {error !== null && <p className="error-bar" onClick={() => setError(null)}>{error}</p>}
      {creating && (
        <div className="create-row">
          <input
            className="field"
            placeholder="目录名"
            value={newName}
            autoFocus
            onChange={(event) => setNewName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void createDirectory()
            }}
          />
          <button type="button" className="btn btn-primary" onClick={() => void createDirectory()}>
            创建
          </button>
        </div>
      )}
      <div className="view-body">
        {listing === null ? (
          <p className="hint">正在加载目录…</p>
        ) : (
          <>
            <nav className="crumbs">
              <button type="button" className="crumb" onClick={() => void open(listing.home)}>
                <Home size={14} aria-hidden="true" />主目录
              </button>
              {listing.crumbs.map((crumb) => (
                <button key={crumb.path} type="button" className="crumb" onClick={() => void open(crumb.path)}>
                  {crumb.name}
                </button>
              ))}
            </nav>
            {listing.truncated && <p className="hint">目录条目过多，仅显示部分</p>}
            <ul className="list">
              {listing.entries.map((entry) => (
                <li key={entry.path}>
                  <button type="button" className="list-row" onClick={() => void open(entry.path)}>
                    <span className="entry-icon">{entry.path.endsWith('/') ? <Folder size={18} /> : <File size={18} />}</span>
                    <span className={`list-title${entry.hidden ? ' entry-hidden' : ''}`}>{entry.name}</span>
                    <span className="list-sub">{entry.path}</span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </main>
  )
}
