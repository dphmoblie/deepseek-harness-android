import { ArrowLeft, Folder, Home } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'
import { hasControlCharacters } from '../state/textSafety'
import type { DirectoryEntry, DirectoryListing } from '../api/types'
import { validateDirectoryName } from './fileValidation'

const MAX_PATH_LENGTH = 32_768
const MAX_DIRECTORY_NAME_CHARACTERS = 255
const MAX_LISTED_ENTRIES = 10_000
const MAX_CRUMBS = 512

type BusyOperation = 'listing' | 'creating' | null
type Operation = { id: number; controller: AbortController }

/**
 * Browse the guest filesystem through the existing directory-picker RPCs.
 * The upstream contract returns direct child directories only (including
 * symlinks to directories); it intentionally has no file-reading primitive.
 */
export function FilesView(props: { onBack: () => void }): ReactElement {
  const { onBack } = props
  const [listing, setListing] = useState<DirectoryListing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)
  const [newName, setNewName] = useState('')
  const [busy, setBusy] = useState<BusyOperation>(null)
  const busyRef = useRef<BusyOperation>(null)
  const operationIdRef = useRef(0)
  const controllerRef = useRef<AbortController | null>(null)

  const beginOperation = useCallback((kind: Exclude<BusyOperation, null>): Operation | null => {
    if (busyRef.current !== null) return null
    const controller = new AbortController()
    const operation = { id: ++operationIdRef.current, controller }
    busyRef.current = kind
    controllerRef.current = controller
    setBusy(kind)
    return operation
  }, [])

  const finishOperation = useCallback((operation: Operation): void => {
    if (operationIdRef.current !== operation.id) return
    busyRef.current = null
    controllerRef.current = null
    setBusy(null)
  }, [])

  const open = useCallback(async (path?: string) => {
    const operation = beginOperation('listing')
    if (operation === null) return
    setError(null)
    try {
      const value = await callUnary(
        window.location.origin,
        'host.listDirectory',
        path === undefined ? {} : { path },
        { signal: operation.controller.signal },
      )
      const parsed = parseDirectoryListing(value)
      if (parsed === null) {
        setError('加载目录失败：Harness 返回了无效的目录数据')
        return
      }
      if (operationIdRef.current === operation.id) setListing(parsed)
    } catch (failure) {
      if (!operation.controller.signal.aborted && operationIdRef.current === operation.id) {
        setError(rpcErrorMessage('加载目录', failure))
      }
    } finally {
      finishOperation(operation)
    }
  }, [beginOperation, finishOperation])

  useEffect(() => {
    void open()
    return () => {
      operationIdRef.current += 1
      controllerRef.current?.abort()
      controllerRef.current = null
      busyRef.current = null
    }
  }, [open])

  const createDirectory = async (): Promise<void> => {
    if (listing === null) return
    const validated = validateDirectoryName(newName)
    if (!validated.ok) {
      setError(validated.message)
      return
    }

    const operation = beginOperation('creating')
    if (operation === null) return
    setError(null)
    let action = '创建目录'
    try {
      await callUnary(
        window.location.origin,
        'host.createDirectory',
        { path: listing.path, name: validated.value },
        { signal: operation.controller.signal },
      )
      action = '刷新目录'
      const refreshed = await callUnary(
        window.location.origin,
        'host.listDirectory',
        { path: listing.path },
        { signal: operation.controller.signal },
      )
      const parsed = parseDirectoryListing(refreshed)
      if (parsed === null) {
        setError('刷新目录失败：Harness 返回了无效的目录数据')
        return
      }
      if (operationIdRef.current === operation.id) {
        setListing(parsed)
        setNewName('')
        setShowCreate(false)
      }
    } catch (failure) {
      if (!operation.controller.signal.aborted && operationIdRef.current === operation.id) {
        setError(rpcErrorMessage(action, failure))
      }
    } finally {
      finishOperation(operation)
    }
  }

  return (
    <main className="view" aria-busy={busy !== null}>
      <header className="view-header secondary-header">
        <button type="button" className="icon-button" aria-label="返回对话" title="返回对话" onClick={onBack}><ArrowLeft size={20} /></button>
        <h1>目录</h1>
        <button
          type="button"
          className="btn btn-primary"
          disabled={listing === null || busy !== null}
          onClick={() => {
            setShowCreate((previous) => !previous)
            setError(null)
          }}
        >
          {showCreate ? '取消' : '新建目录'}
        </button>
      </header>
      {error !== null && <p className="error-bar" role="alert" onClick={() => setError(null)}>{error}</p>}
      {showCreate && (
        <div className="create-row">
          <input
            className="field"
            aria-label="目录名"
            placeholder="目录名"
            value={newName}
            maxLength={MAX_DIRECTORY_NAME_CHARACTERS}
            autoFocus
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            disabled={busy !== null}
            onChange={(event) => {
              setNewName(event.target.value)
              setError(null)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && busy === null) void createDirectory()
            }}
          />
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy !== null}
            onClick={() => void createDirectory()}
          >
            {busy === 'creating' ? '创建中…' : '创建'}
          </button>
        </div>
      )}
      <div className="view-body">
        {listing === null ? (
          <p className="hint">{busy === 'listing' ? '正在加载目录…' : '目录不可用'}</p>
        ) : (
          <>
            <nav className="crumbs" aria-label="目录路径">
              <button
                type="button"
                className="crumb"
                disabled={busy !== null}
                onClick={() => void open(listing.home)}
              >
                <Home size={14} aria-hidden="true" />主目录
              </button>
              {listing.crumbs.map((crumb) => (
                <button
                  key={crumb.path}
                  type="button"
                  className="crumb"
                  disabled={busy !== null || crumb.path === listing.path}
                  aria-current={crumb.path === listing.path ? 'location' : undefined}
                  onClick={() => void open(crumb.path)}
                >
                  {crumb.name}
                </button>
              ))}
            </nav>
            {listing.truncated && <p className="hint">子目录过多，仅显示按名称排序后的前一部分</p>}
            {listing.entries.length === 0 ? (
              <p className="hint">该目录没有子目录</p>
            ) : (
              <ul className="list">
                {listing.entries.map((entry) => (
                  <li key={entry.path}>
                    <button
                      type="button"
                      className="list-row"
                      disabled={busy !== null}
                      aria-label={`打开目录 ${entry.name}`}
                      onClick={() => void open(entry.path)}
                    >
                      <span className="entry-icon"><Folder size={18} aria-hidden="true" /></span>
                      <span className={`list-title${entry.hidden ? ' entry-hidden' : ''}`}>{entry.name}</span>
                      <span className="list-sub">{entry.path}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </main>
  )
}

function parseDirectoryListing(value: unknown): DirectoryListing | null {
  if (!isRecord(value)) return null
  const { path, home, crumbs, entries, truncated } = value
  if (
    !isSafeAbsolutePath(path)
    || !isSafeAbsolutePath(home)
    || !Array.isArray(crumbs)
    || crumbs.length > MAX_CRUMBS
    || !Array.isArray(entries)
    || entries.length > MAX_LISTED_ENTRIES
    || typeof truncated !== 'boolean'
  ) return null

  const parsedCrumbs: DirectoryEntry[] = []
  for (const crumb of crumbs) {
    const parsed = parseDirectoryEntry(crumb, false)
    if (parsed === null) return null
    parsedCrumbs.push(parsed)
  }

  const parsedEntries: DirectoryEntry[] = []
  const paths = new Set<string>()
  for (const entry of entries) {
    const parsed = parseDirectoryEntry(entry, true)
    if (parsed === null || paths.has(parsed.path)) return null
    paths.add(parsed.path)
    parsedEntries.push(parsed)
  }

  return { path, home, crumbs: parsedCrumbs, entries: parsedEntries, truncated }
}

function parseDirectoryEntry(value: unknown, child: boolean): DirectoryEntry | null {
  if (!isRecord(value)) return null
  const { name, path, hidden } = value
  if (
    typeof name !== 'string'
    || name.length === 0
    || name.length > 1_024
    || hasControlCharacters(name, true)
    || !isSafeAbsolutePath(path)
    || typeof hidden !== 'boolean'
  ) return null
  if (child && (name === '.' || name === '..' || /[\\/]/.test(name))) return null
  return { name, path, hidden }
}

function isSafeAbsolutePath(value: unknown): value is string {
  if (
    typeof value !== 'string'
    || value.length === 0
    || value.length > MAX_PATH_LENGTH
    || hasControlCharacters(value)
  ) return false
  return value.startsWith('/')
    || /^[A-Za-z]:[\\/]/.test(value)
    || /^[\\/]{2}[^\\/]+[\\/]+[^\\/]+/.test(value)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
