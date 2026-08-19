import {
  AlertTriangle, Check, Download, ListTree, LoaderCircle, Power, PowerOff,
  RefreshCw, RotateCcw, Store, Trash2, Wrench, X,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import type { PluginInventoryEntry } from '../api/types'
import { callUnary } from '../api/wire'
import { failureReason } from '../state/errorDisplay'
import {
  APPROVE_BUILDS_PATH, CANCEL_PATH, INSTALLED_PATH, INSTALL_PATH, REGISTRY_PATH,
  RESTART_PATH, SETUP_PNPM_PATH, STATUS_PATH, TOGGLE_PATH, UNINSTALL_PATH,
  UPDATE_PATH, UPDATES_PATH,
} from './pluginMarketplaceConstants'

const MAX_RESPONSE_BYTES = 2 * 1024 * 1024
const MAX_PLUGINS = 1000
const MAX_PACKAGE_NAME_LENGTH = 256
const MAX_URL_LENGTH = 2048
const MAX_TEXT_LENGTH = 4096
const PAGE_SIZE = 24
const ACTIVATION_STATES = new Set(['live', 'restart', 'inert', 'broken', 'missing'])
const FIBER_PHASES = new Set(['pending', 'loading', 'active', 'failed', 'unloading'])
const MARKET_PHASES = new Set(['resolving', 'downloading', 'linking', 'building'])

type FetchLike = typeof fetch
type RegistryPlugin = {
  name: string; owner: string; url: string; npm?: string; category: string
  description?: Record<string, string>; stars?: number; added?: string; deprecated?: boolean
}
type Registry = { count: number; plugins: RegistryPlugin[] }
type ActivationInfo = { state: 'live' | 'restart' | 'inert' | 'broken' | 'missing'; reasons: string[] }
type InstalledSnapshot = {
  installed: Record<string, string>; disabled: Set<string>; activation: Record<string, ActivationInfo>
}
type UpdateStatus = { updateAvailable: boolean; version?: string; kind?: string }
type MarketStatus = {
  active: boolean; busy: boolean; cancelling: boolean; pnpm: boolean; restart: boolean
  boot?: string; target?: string; lastLine?: string
  phase?: 'resolving' | 'downloading' | 'linking' | 'building'
  currentPackage?: string; seconds?: number; done?: number; total?: number
}
type ActionKind = 'install' | 'uninstall' | 'toggle' | 'update' | 'setup' | 'approve' | 'restart'
type Action = { kind: ActionKind; key: string }
type RetryOperation = { kind: 'install'; plugin: RegistryPlugin } | { kind: 'update'; name: string }
type BuildApproval = { packages: string[]; retry: RetryOperation }
type ResponseEnvelope = { status: number; body: unknown }
type AuxiliaryResult<T> = { ok: true; value: T } | { ok: false; error: string }

class MarketRequestError extends Error {
  readonly status: number | undefined
  readonly detail: string | undefined
  constructor(message: string, status?: number, detail?: string) {
    super(message)
    this.name = 'MarketRequestError'
    this.status = status
    this.detail = detail
  }
}

class MarketPayloadError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MarketPayloadError'
  }
}

/** dshmarket 1.10.x 移动客户端；写操作只使用 registry 或已安装清单中的标识。 */
export function PluginMarketplaceView(props: { fetchFn?: FetchLike }): ReactElement {
  const fetchFn = props.fetchFn ?? globalThis.fetch
  const [registry, setRegistry] = useState<Registry | null>(null)
  const [installed, setInstalled] = useState<InstalledSnapshot | null>(null)
  const [updates, setUpdates] = useState<Record<string, UpdateStatus>>({})
  const [status, setStatus] = useState<MarketStatus | null>(null)
  const [inventory, setInventory] = useState<PluginInventoryEntry[] | null>(null)
  const [auxiliaryErrors, setAuxiliaryErrors] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)
  const [inventoryVisibleCount, setInventoryVisibleCount] = useState(PAGE_SIZE)
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [action, setAction] = useState<Action | null>(null)
  const [cancelBusy, setCancelBusy] = useState(false)
  const [confirmUninstall, setConfirmUninstall] = useState<string | null>(null)
  const [confirmRestart, setConfirmRestart] = useState(false)
  const [buildApproval, setBuildApproval] = useState<BuildApproval | null>(null)
  const generationRef = useRef(0)
  const refreshControllerRef = useRef<AbortController | null>(null)
  const actionControllerRef = useRef<AbortController | null>(null)
  const cancelControllerRef = useRef<AbortController | null>(null)

  const request = useCallback(async (
    path: string, init: RequestInit | undefined, signal: AbortSignal,
  ): Promise<ResponseEnvelope> => {
    let response: Response
    try {
      response = await fetchFn(path, {
        ...init,
        signal,
        headers: { accept: 'application/json', ...(init?.headers ?? {}) },
      })
    } catch (failure) {
      if (isAbortError(failure)) throw failure
      throw new MarketRequestError('网络不可用或 Harness 服务未启动')
    }
    let text: string
    try {
      text = await response.text()
    } catch {
      throw new MarketRequestError('无法读取插件市场响应', response.status)
    }
    if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) {
      throw new MarketRequestError('插件市场响应过大，已拒绝解析', response.status)
    }
    let body: unknown = null
    if (text.trim() !== '') {
      try {
        body = JSON.parse(text) as unknown
      } catch {
        throw new MarketRequestError('插件市场返回了无效 JSON', response.status)
      }
    }
    return { status: response.status, body }
  }, [fetchFn])

  const readAuxiliary = useCallback(async <T,>(
    actionLabel: string, operation: () => Promise<T>,
  ): Promise<AuxiliaryResult<T>> => {
    try {
      return { ok: true, value: await operation() }
    } catch (failure) {
      if (isAbortError(failure)) throw failure
      return { ok: false, error: formatMarketError(actionLabel, failure) }
    }
  }, [])

  const refresh = useCallback(async (): Promise<void> => {
    refreshControllerRef.current?.abort()
    const controller = new AbortController()
    refreshControllerRef.current = controller
    const generation = ++generationRef.current
    setLoading(true)
    setError(null)
    try {
      const statusPromise = readAuxiliary('读取插件操作状态', async () => {
        const response = await request(STATUS_PATH, { cache: 'no-store' }, controller.signal)
        if (response.status < 200 || response.status >= 300) throw responseError('读取插件操作状态', response)
        return parseStatus(response.body)
      })
      const updatesPromise = readAuxiliary('读取插件更新', async () => {
        const response = await request(UPDATES_PATH, { cache: 'no-store' }, controller.signal)
        if (response.status < 200 || response.status >= 300) throw responseError('读取插件更新', response)
        return parseUpdates(response.body)
      })
      const inventoryPromise = readAuxiliary('读取运行时插件', async () => {
        const value = await callUnary(window.location.origin, 'pluginInventory/list', { args: {} }, {
          signal: controller.signal,
          deps: { fetchFn },
        })
        return parseInventory(value)
      })
      const [registryResponse, installedResponse, statusResult, updatesResult, inventoryResult] = await Promise.all([
        request(REGISTRY_PATH, { cache: 'no-store' }, controller.signal),
        request(INSTALLED_PATH, { cache: 'no-store' }, controller.signal),
        statusPromise, updatesPromise, inventoryPromise,
      ])
      if (registryResponse.status < 200 || registryResponse.status >= 300) throw responseError('读取插件目录', registryResponse)
      if (installedResponse.status < 200 || installedResponse.status >= 300) throw responseError('读取已安装插件', installedResponse)
      const nextRegistry = parseRegistry(registryResponse.body)
      const nextInstalled = parseInstalled(installedResponse.body)
      if (generation !== generationRef.current) return
      setRegistry(nextRegistry)
      setInstalled(nextInstalled)
      setStatus(statusResult.ok ? statusResult.value : null)
      setUpdates(updatesResult.ok ? updatesResult.value : {})
      setInventory(inventoryResult.ok ? inventoryResult.value : null)
      setAuxiliaryErrors([statusResult, updatesResult, inventoryResult]
        .filter((result): result is { ok: false; error: string } => !result.ok)
        .map(result => result.error))
      setLoaded(true)
      setConfirmUninstall(null)
      setConfirmRestart(false)
    } catch (failure) {
      if (isAbortError(failure) || generation !== generationRef.current) return
      setLoaded(true)
      setError(formatMarketError('刷新插件市场', failure))
    } finally {
      if (generation === generationRef.current) {
        setLoading(false)
        refreshControllerRef.current = null
      }
    }
  }, [fetchFn, readAuxiliary, request])

  const refreshStatus = useCallback(async (signal: AbortSignal): Promise<void> => {
    const response = await request(STATUS_PATH, { cache: 'no-store' }, signal)
    if (response.status < 200 || response.status >= 300) throw responseError('读取插件操作状态', response)
    setStatus(parseStatus(response.body))
  }, [request])

  useEffect(() => {
    const longAction = action !== null && ['install', 'update', 'uninstall'].includes(action.kind)
    if (!loaded || (!longAction && status?.active !== true && status?.busy !== true)) return undefined
    const controller = new AbortController()
    const poll = (): void => {
      void refreshStatus(controller.signal).catch((failure: unknown) => {
        if (!isAbortError(failure)) {
          setAuxiliaryErrors(previous => previous.some(item => item.startsWith('读取插件操作状态失败'))
            ? previous
            : [...previous, formatMarketError('读取插件操作状态', failure)])
        }
      })
    }
    poll()
    const timer = window.setInterval(poll, 1500)
    return () => { window.clearInterval(timer); controller.abort() }
  }, [action, loaded, refreshStatus, status?.active, status?.busy])

  useEffect(() => () => {
    generationRef.current += 1
    refreshControllerRef.current?.abort()
    actionControllerRef.current?.abort()
    cancelControllerRef.current?.abort()
  }, [])

  useEffect(() => { setVisibleCount(PAGE_SIZE) }, [query, registry])

  const runAction = useCallback(async (
    nextAction: Action,
    path: string,
    payload: Record<string, unknown>,
    successText: string,
    options: { retry?: RetryOperation; refreshAfter?: boolean } = {},
  ): Promise<boolean> => {
    if (actionControllerRef.current !== null) return false
    const controller = new AbortController()
    actionControllerRef.current = controller
    setAction(nextAction)
    setError(null)
    setNotice(null)
    try {
      const response = await request(path, {
        method: 'POST', cache: 'no-store',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      }, controller.signal)
      const body = asRecord(response.body)
      const ignoredBuilds = readBuildPackages(body?.ignoredBuilds)
      if (ignoredBuilds.length > 0 && options.retry !== undefined) {
        setBuildApproval({ packages: ignoredBuilds, retry: options.retry })
      }
      if (response.status < 200 || response.status >= 300) throw responseError(nextActionLabel(nextAction.kind), response)
      if (body?.cancelled === true) {
        throw new MarketRequestError(body.partial === true ? '操作被取消，插件状态可能只完成了一部分' : '操作已取消', response.status)
      }
      if (body?.ok !== true) throw responseError(nextActionLabel(nextAction.kind), response)
      setBuildApproval(null)
      setNotice(successText)
      if (options.refreshAfter !== false) await refresh()
      return true
    } catch (failure) {
      if (!isAbortError(failure)) setError(formatMarketError(nextActionLabel(nextAction.kind), failure))
      return false
    } finally {
      actionControllerRef.current = null
      setAction(null)
    }
  }, [refresh, request])

  const installedNames = useMemo(() => installed === null
    ? new Set<string>()
    : new Set(Object.keys(installed.installed)), [installed])

  const filteredPlugins = useMemo(() => {
    if (registry === null) return []
    const needle = query.trim().toLocaleLowerCase()
    if (needle === '') return registry.plugins
    return registry.plugins.filter(plugin => {
      const description = localizedDescription(plugin, 'zh')
      return plugin.name.toLocaleLowerCase().includes(needle)
        || plugin.owner.toLocaleLowerCase().includes(needle)
        || plugin.category.toLocaleLowerCase().includes(needle)
        || description.toLocaleLowerCase().includes(needle)
    })
  }, [query, registry])

  const install = useCallback((plugin: RegistryPlugin): void => {
    if (registry === null || installed === null || actionControllerRef.current !== null) return
    if (!registry.plugins.some(entry => entry.url === plugin.url) || !isSafeRegistryUrl(plugin.url)) {
      setError('安装失败：插件地址不在当前安全 registry 中')
      return
    }
    void runAction({ kind: 'install', key: plugin.url }, INSTALL_PATH, { url: plugin.url },
      `安装完成：${plugin.name}`, { retry: { kind: 'install', plugin } })
  }, [installed, registry, runAction])

  const uninstall = useCallback((name: string): void => {
    if (installed === null || !installedNames.has(name) || isMarketPackage(name)) return
    if (!isSafePackageName(name)) { setError('卸载失败：服务端返回了无效插件名称'); return }
    void runAction({ kind: 'uninstall', key: name }, UNINSTALL_PATH, { name }, `卸载完成：${name}`)
  }, [installed, installedNames, runAction])

  const toggle = useCallback((name: string): void => {
    if (installed === null || !installedNames.has(name) || !isSafePackageName(name) || isMarketPackage(name)) return
    const enabled = installed.disabled.has(name)
    void runAction({ kind: 'toggle', key: name }, TOGGLE_PATH, { name, enabled },
      enabled ? `已启用：${name}` : `已停用：${name}`)
  }, [installed, installedNames, runAction])

  const update = useCallback((name: string): void => {
    if (installed === null || !installedNames.has(name) || !isSafePackageName(name)) return
    void runAction({ kind: 'update', key: name }, UPDATE_PATH, { name },
      `更新完成：${name}`, { retry: { kind: 'update', name } })
  }, [installed, installedNames, runAction])

  const approveBuilds = useCallback(async (): Promise<void> => {
    if (buildApproval === null || actionControllerRef.current !== null) return
    const approval = buildApproval
    const controller = new AbortController()
    actionControllerRef.current = controller
    setAction({ kind: 'approve', key: approval.packages.join(',') })
    setError(null)
    let approved = false
    try {
      const response = await request(APPROVE_BUILDS_PATH, {
        method: 'POST', cache: 'no-store', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ packages: approval.packages }),
      }, controller.signal)
      if (response.status < 200 || response.status >= 300 || asRecord(response.body)?.ok !== true) {
        throw responseError('放行插件构建脚本', response)
      }
      approved = true
      setBuildApproval(null)
      setNotice(`已放行构建脚本：${approval.packages.join('、')}`)
    } catch (failure) {
      if (!isAbortError(failure)) setError(formatMarketError('放行插件构建脚本', failure))
    } finally {
      actionControllerRef.current = null
      setAction(null)
    }
    if (!approved) return
    if (approval.retry.kind === 'install') install(approval.retry.plugin)
    else update(approval.retry.name)
  }, [buildApproval, install, request, update])

  const setupPnpm = useCallback((): void => {
    void runAction({ kind: 'setup', key: 'pnpm' }, SETUP_PNPM_PATH, {}, 'pnpm 环境初始化完成')
  }, [runAction])

  const restart = useCallback(async (): Promise<void> => {
    if (status?.restart !== true || status.busy || status.active) return
    if (!confirmRestart) { setConfirmRestart(true); return }
    const ok = await runAction({ kind: 'restart', key: status.boot ?? 'host' }, RESTART_PATH, {},
      'Harness 已开始重启，请稍后重新打开页面', { refreshAfter: false })
    if (ok) setConfirmRestart(false)
  }, [confirmRestart, runAction, status])

  const cancelOperation = useCallback(async (): Promise<void> => {
    if (cancelControllerRef.current !== null || status?.active !== true) return
    const controller = new AbortController()
    cancelControllerRef.current = controller
    setCancelBusy(true)
    setError(null)
    try {
      const response = await request(CANCEL_PATH, {
        method: 'POST', cache: 'no-store', headers: { 'content-type': 'application/json' }, body: '{}',
      }, controller.signal)
      if (response.status < 200 || response.status >= 300 || asRecord(response.body)?.ok !== true) {
        throw responseError('取消插件操作', response)
      }
      setStatus(previous => previous === null ? previous : { ...previous, cancelling: true })
      setNotice('已请求取消当前插件操作')
    } catch (failure) {
      if (!isAbortError(failure)) setError(formatMarketError('取消插件操作', failure))
    } finally {
      cancelControllerRef.current = null
      setCancelBusy(false)
    }
  }, [request, status?.active])

  const busy = action !== null || loading
  const operationActive = status?.active === true || status?.busy === true
  const visiblePlugins = filteredPlugins.slice(0, visibleCount)
  const inventoryRows = inventory?.slice(0, inventoryVisibleCount) ?? []

  return (
    <section className="extension-panel plugin-market-panel" aria-labelledby="plugin-market-title" aria-busy={busy}>
      <header className="extension-header">
        <div>
          <h2 id="plugin-market-title" className="section-title"><Store size={16} aria-hidden="true" />插件市场</h2>
          <p className="hint">浏览并管理 Harness 插件；安装来源仅接受当前 registry 条目。</p>
        </div>
        <button type="button" className="icon-button" aria-label={loaded ? '刷新插件市场' : '加载插件市场'}
          title={loaded ? '刷新插件市场' : '加载插件市场'} disabled={busy} onClick={() => { void refresh() }}>
          <RefreshCw size={18} className={loading && registry !== null ? 'spin' : undefined} aria-hidden="true" />
        </button>
      </header>
      {error !== null && (
        <p className="error-bar extension-message" role="alert"><span>{error}</span>
          <button type="button" className="icon-button" aria-label="关闭错误" title="关闭错误" onClick={() => setError(null)}>
            <X size={16} aria-hidden="true" />
          </button>
        </p>
      )}
      {notice !== null && <p className="success-bar extension-message" role="status"><Check size={15} aria-hidden="true" />{notice}</p>}
      {auxiliaryErrors.map(message => <p className="hint extension-message" key={message}><AlertTriangle size={14} />{message}</p>)}
      {!loaded && <p className="hint">点击刷新以读取本机 Harness 的插件清单和市场。</p>}
      <div className="plugin-market-body">
        {loaded && (registry === null || installed === null) ? <p className="hint">插件市场暂不可用。</p>
          : loaded && registry !== null && installed !== null ? (
            <>
              <div className="extension-summary" aria-label="插件运行状态">
                <span>目录 {registry.count}</span><span>已安装 {installedNames.size}</span>
                <span>运行时 {inventory?.length ?? '不可用'}</span>
                <span>{status === null ? 'pnpm 状态未知' : status.pnpm ? 'pnpm 已就绪' : 'pnpm 未就绪'}</span>
                {status?.pnpm === false && (
                  <button type="button" className="btn" disabled={busy || operationActive} onClick={setupPnpm}>
                    {action?.kind === 'setup' ? <LoaderCircle size={14} className="spin" /> : <Wrench size={14} />}初始化 pnpm
                  </button>
                )}
                {status?.restart === true && (
                  <button type="button" className={confirmRestart ? 'btn btn-danger' : 'btn'} disabled={busy || operationActive}
                    onClick={() => { void restart() }}>
                    {action?.kind === 'restart' ? <LoaderCircle size={14} className="spin" /> : <RotateCcw size={14} />}
                    {confirmRestart ? '确认重启' : '重启 Harness'}
                  </button>
                )}
              </div>
              {operationActive && (
                <div className="market-progress" role="status"><LoaderCircle size={15} className="spin" />
                  <span>{marketProgressText(status)}</span>
                  {status?.active === true && <button type="button" className="btn" disabled={cancelBusy || status.cancelling}
                    onClick={() => { void cancelOperation() }}>{cancelBusy || status.cancelling ? '正在取消…' : '取消操作'}</button>}
                </div>
              )}
              {buildApproval !== null && (
                <div className="market-progress market-build-approval" role="alert"><AlertTriangle size={15} />
                  <span>pnpm 已拦截以下构建脚本：{buildApproval.packages.join('、')}</span>
                  <button type="button" className="btn btn-primary" disabled={busy || operationActive} onClick={() => { void approveBuilds() }}>
                    {action?.kind === 'approve' ? <LoaderCircle size={14} className="spin" /> : <Wrench size={14} />}放行并重试
                  </button>
                </div>
              )}
              <section aria-labelledby="runtime-inventory-title">
                <h2 className="section-title" id="runtime-inventory-title"><ListTree size={15} />运行时插件 <span className="badge">{inventory?.length ?? 0}</span></h2>
                {inventory === null ? <p className="hint">运行时插件清单不可用。</p>
                  : inventory.length === 0 ? <p className="hint">运行时没有 Loader 插件条目。</p> : (
                    <ul className="inventory-list" aria-label="运行时插件清单">
                      {inventoryRows.map(entry => <li key={entry.entryId}>
                        <span><strong>{entry.moduleName}</strong><small>{entry.entryId}</small></span>
                        <span className={`inventory-state inventory-state-${entry.fiberPhase ?? 'unobserved'}`}>
                          {entry.enabled ? fiberPhaseLabel(entry.fiberPhase) : '已停用'}
                        </span>
                      </li>)}
                    </ul>
                  )}
                {inventory !== null && inventoryVisibleCount < inventory.length && (
                  <button type="button" className="btn btn-block" onClick={() => setInventoryVisibleCount(value => Math.min(value + PAGE_SIZE, inventory.length))}>
                    显示更多运行时插件（剩余 {inventory.length - inventoryVisibleCount}）
                  </button>
                )}
              </section>
              <section aria-labelledby="market-discover-title">
                <h2 className="section-title" id="market-discover-title">发现插件 <span className="badge">{filteredPlugins.length}</span></h2>
                <label className="field-label" htmlFor="plugin-market-search">搜索插件</label>
                <input id="plugin-market-search" className="field" type="search" value={query} maxLength={120}
                  autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder="按名称、作者或分类搜索"
                  onChange={event => setQuery(event.target.value)} />
                {filteredPlugins.length === 0 ? <p className="hint">{query.trim() === '' ? '暂无可用插件' : '没有匹配的插件'}</p> : (
                  <ul className="list market-list" aria-label="插件目录">
                    {visiblePlugins.map(plugin => {
                      const installedName = findInstalledName(plugin, installed.installed)
                      const isInstalling = action?.kind === 'install' && action.key === plugin.url
                      return <li key={plugin.url} className="provider-card"><div className="list-row" role="group" aria-label={plugin.name}>
                        <div className="list-main"><span className="list-title">{plugin.name}{plugin.deprecated === true && <span className="badge">已弃用</span>}</span>
                          <span className="list-sub">{plugin.owner} · {plugin.category}{typeof plugin.stars === 'number' ? ` · ★ ${plugin.stars}` : ''}</span>
                          {localizedDescription(plugin, 'zh') !== '' && <span className="list-sub">{localizedDescription(plugin, 'zh')}</span>}
                        </div>
                        <div className="list-side plugin-action-side">{installedName === null ? (
                          <button type="button" className="btn btn-primary" disabled={busy || operationActive || status?.pnpm === false} onClick={() => install(plugin)}>
                            {isInstalling ? <LoaderCircle size={15} className="spin" /> : <Download size={15} />}{isInstalling ? '安装中…' : '安装'}
                          </button>
                        ) : <span className="badge badge-running">已安装</span>}</div>
                      </div></li>
                    })}
                  </ul>
                )}
                {visibleCount < filteredPlugins.length && <button type="button" className="btn btn-block market-show-more"
                  onClick={() => setVisibleCount(value => Math.min(value + PAGE_SIZE, filteredPlugins.length))}>
                  显示更多插件（剩余 {filteredPlugins.length - visibleCount}）
                </button>}
              </section>
              <section aria-labelledby="market-installed-title">
                <h2 className="section-title" id="market-installed-title">已安装 <span className="badge">{installedNames.size}</span></h2>
                {installedNames.size === 0 ? <p className="hint">暂无已安装插件</p> : (
                  <ul className="list market-list" aria-label="已安装插件">
                    {[...installedNames].sort((a, b) => a.localeCompare(b)).map(name => {
                      const isDisabled = installed.disabled.has(name)
                      const activation = installed.activation[name]
                      const updateStatus = updates[name]
                      const confirming = confirmUninstall === name
                      const manageable = isSafePackageName(name) && !isMarketPackage(name)
                      return <li key={name} className="provider-card"><div className="list-row" role="group" aria-label={name}>
                        <div className="list-main"><span className="list-title">{name}</span><span className="list-sub">{installed.installed[name]}</span>
                          <span className={`list-sub market-state-${activation?.state ?? 'unknown'}`}>{activationLabel(activation, isDisabled)}</span>
                          {activation !== undefined && activation.reasons.length > 0 && <span className="list-sub market-warning"><AlertTriangle size={13} />原因：{activation.reasons.join(' / ')}</span>}
                        </div>
                        <div className="list-side plugin-action-side">
                          {updateStatus?.updateAvailable === true && <button type="button" className="btn btn-primary" disabled={busy || operationActive} onClick={() => update(name)}>
                            {action?.kind === 'update' && action.key === name ? <LoaderCircle size={15} className="spin" /> : <RefreshCw size={15} />}
                            {action?.kind === 'update' && action.key === name ? '更新中…' : `更新${updateStatus.version === undefined ? '' : `到 ${updateStatus.version}`}`}
                          </button>}
                          {manageable && <button type="button" className="btn" disabled={busy || operationActive} onClick={() => toggle(name)}>
                            {action?.kind === 'toggle' && action.key === name ? <LoaderCircle size={15} className="spin" /> : isDisabled ? <Power size={15} /> : <PowerOff size={15} />}
                            {isDisabled ? '启用' : '停用'}
                          </button>}
                          {manageable && (confirming ? <>
                            <button type="button" className="btn btn-danger" disabled={busy || operationActive} onClick={() => uninstall(name)}>
                              {action?.kind === 'uninstall' && action.key === name ? <LoaderCircle size={15} className="spin" /> : <Trash2 size={15} />}
                              {action?.kind === 'uninstall' && action.key === name ? '卸载中…' : '确认卸载'}
                            </button><button type="button" className="btn" disabled={busy} onClick={() => setConfirmUninstall(null)}>取消</button>
                          </> : <button type="button" className="btn" disabled={busy || operationActive} onClick={() => setConfirmUninstall(name)}><Trash2 size={15} />卸载</button>)}
                        </div>
                      </div></li>
                    })}
                  </ul>
                )}
              </section>
            </>
          ) : null}
      </div>
    </section>
  )
}

function parseRegistry(value: unknown): Registry {
  const root = asRecord(value)
  const raw = asRecord(root?.registry)
  const rawPlugins = raw?.plugins
  if (raw === null || !Array.isArray(rawPlugins) || rawPlugins.length > MAX_PLUGINS) throw new MarketPayloadError('插件目录数据格式无效')
  const plugins: RegistryPlugin[] = []
  const urls = new Set<string>()
  for (const item of rawPlugins) {
    const record = asRecord(item)
    const name = safeText(record?.name, '插件名称')
    const owner = safeText(record?.owner, '插件作者')
    const url = safeRegistryUrl(record?.url)
    const category = safeText(record?.category, '插件分类')
    if (urls.has(url)) continue
    urls.add(url)
    const description = parseLocalizedText(record?.description)
    const npm = optionalSafeText(record?.npm)
    const added = optionalSafeText(record?.added)
    const stars = optionalNumber(record?.stars)
    plugins.push({ name, owner, url, category,
      ...(npm === undefined ? {} : { npm }), ...(description === undefined ? {} : { description }),
      ...(added === undefined ? {} : { added }), ...(stars === undefined ? {} : { stars }),
      ...(record?.deprecated === true ? { deprecated: true } : {}) })
  }
  const count = typeof raw.count === 'number' && Number.isSafeInteger(raw.count) && raw.count >= 0 ? raw.count : plugins.length
  return { count: Math.max(count, plugins.length), plugins }
}

function parseInstalled(value: unknown): InstalledSnapshot {
  const root = asRecord(value)
  const rawInstalled = asRecord(root?.installed)
  if (rawInstalled === null || Object.keys(rawInstalled).length > MAX_PLUGINS) throw new MarketPayloadError('已安装插件数据格式无效')
  const installed: Record<string, string> = {}
  for (const [name, spec] of Object.entries(rawInstalled)) {
    if (!isSafePackageName(name) || typeof spec !== 'string' || !isSafeText(spec, MAX_URL_LENGTH)) throw new MarketPayloadError('已安装插件数据包含无效名称或版本')
    installed[name] = spec
  }
  const disabled = new Set(readSafeNameList(root?.disabled))
  const activation: Record<string, ActivationInfo> = {}
  const rawActivation = asRecord(root?.activation)
  if (rawActivation !== null) {
    for (const [name, item] of Object.entries(rawActivation)) {
      if (!isSafePackageName(name)) continue
      const record = asRecord(item)
      if (record === null || typeof record.state !== 'string' || !ACTIVATION_STATES.has(record.state)) continue
      const reasons = Array.isArray(record.reasons)
        ? record.reasons.map(safeDiagnostic).filter((reason): reason is string => reason !== undefined).slice(0, 20) : []
      activation[name] = { state: record.state as ActivationInfo['state'], reasons }
    }
  }
  return { installed, disabled, activation }
}

function parseStatus(value: unknown): MarketStatus {
  const root = asRecord(value)
  if (root === null) throw new MarketPayloadError('插件操作状态格式无效')
  const phase = typeof root.phase === 'string' && MARKET_PHASES.has(root.phase) ? root.phase as MarketStatus['phase'] : undefined
  return { active: root.active === true, busy: root.busy === true, cancelling: root.cancelling === true,
    pnpm: root.pnpm !== false, restart: root.restart === true,
    ...optionalStatusText('boot', root.boot), ...optionalStatusText('target', root.target),
    ...optionalStatusText('lastLine', root.lastLine), ...optionalStatusText('currentPackage', root.currentPackage),
    ...(phase === undefined ? {} : { phase }), ...optionalStatusNumber('seconds', root.seconds),
    ...optionalStatusNumber('done', root.done), ...optionalStatusNumber('total', root.total) }
}

function parseUpdates(value: unknown): Record<string, UpdateStatus> {
  const root = asRecord(value)
  const raw = asRecord(root?.updates)
  if (raw === null || Object.keys(raw).length > MAX_PLUGINS) throw new MarketPayloadError('插件更新数据格式无效')
  const result: Record<string, UpdateStatus> = {}
  for (const [name, item] of Object.entries(raw)) {
    if (!isSafePackageName(name)) continue
    const record = asRecord(item)
    if (record === null) continue
    const version = optionalSafeText(record.version)
    const kind = optionalSafeText(record.kind)
    result[name] = { updateAvailable: record.updateAvailable === true,
      ...(version === undefined ? {} : { version }), ...(kind === undefined ? {} : { kind }) }
  }
  return result
}

function parseInventory(value: unknown): PluginInventoryEntry[] {
  const root = asRecord(value)
  if (root === null || !Array.isArray(root.entries) || root.entries.length > MAX_PLUGINS) throw new MarketPayloadError('运行时插件清单格式无效')
  return root.entries.map(item => {
    const record = asRecord(item)
    const entryId = runtimeText(record?.entryId, 'Loader 条目标识')
    const moduleName = runtimeText(record?.moduleName, '插件模块名')
    const rawPhase = record?.fiberPhase
    if (record === null || typeof record.enabled !== 'boolean' || !(rawPhase === null || typeof rawPhase === 'string' && FIBER_PHASES.has(rawPhase))) {
      throw new MarketPayloadError('运行时插件清单包含无效条目')
    }
    return { entryId, moduleName, enabled: record.enabled, fiberPhase: rawPhase as PluginInventoryEntry['fiberPhase'] }
  })
}

function parseLocalizedText(value: unknown): Record<string, string> | undefined {
  const record = asRecord(value)
  if (record === null) return undefined
  const result: Record<string, string> = {}
  for (const [key, text] of Object.entries(record)) if (/^[a-z]{2,8}$/i.test(key) && isSafeText(text, MAX_TEXT_LENGTH)) result[key] = text
  return Object.keys(result).length === 0 ? undefined : result
}

function localizedDescription(plugin: RegistryPlugin, language: string): string {
  return plugin.description?.[language] ?? plugin.description?.en ?? Object.values(plugin.description ?? {})[0] ?? ''
}

function findInstalledName(plugin: RegistryPlugin, installed: Record<string, string>): string | null {
  const candidates = new Set([plugin.name.toLocaleLowerCase(), plugin.npm?.toLocaleLowerCase()].filter((value): value is string => value !== undefined))
  for (const [name, spec] of Object.entries(installed)) {
    if (candidates.has(name.toLocaleLowerCase())) return name
    const repo = githubRepository(plugin.url)
    if (repo !== null && spec.toLocaleLowerCase().includes(`github:${repo}`)) return name
  }
  return null
}

function githubRepository(value: string): string | null {
  try {
    const url = new URL(value)
    if (url.hostname !== 'github.com') return null
    const parts = url.pathname.split('/').filter(Boolean)
    return parts.length >= 2 ? `${parts[0]}/${parts[1]}`.toLocaleLowerCase() : null
  } catch { return null }
}

function activationLabel(info: ActivationInfo | undefined, disabled: boolean): string {
  if (disabled) return '已停用'
  switch (info?.state) {
    case 'live': return '已启用 · 已生效'
    case 'restart': return '已启用 · 重启后生效'
    case 'broken': return '已启用 · 激活失败'
    case 'inert': return '已启用 · 未激活'
    case 'missing': return '已启用 · 文件缺失'
    default: return '已启用 · 状态未知'
  }
}

function fiberPhaseLabel(phase: PluginInventoryEntry['fiberPhase']): string {
  switch (phase) {
    case 'pending': return '等待依赖'
    case 'loading': return '加载中'
    case 'active': return '运行中'
    case 'failed': return '加载失败'
    case 'unloading': return '卸载中'
    case null: return '未观测'
  }
}

function marketProgressText(status: MarketStatus | null): string {
  if (status === null) return '插件操作正在进行'
  if (!status.active && status.busy) return '正在应用插件变更'
  const phase = status.phase === 'resolving' ? '正在解析依赖' : status.phase === 'downloading' ? '正在下载'
    : status.phase === 'linking' ? '正在链接' : status.phase === 'building' ? '正在构建' : '插件操作正在进行'
  const target = status.currentPackage ?? status.target ?? status.lastLine
  const count = status.total !== undefined && status.total > 0 && status.done !== undefined ? ` ${Math.min(status.done, status.total)}/${status.total}` : ''
  return `${phase}${count}${target === undefined ? '' : ` · ${target}`}${status.seconds === undefined ? '' : ` · ${status.seconds}s`}`
}

function nextActionLabel(kind: ActionKind): string {
  switch (kind) {
    case 'install': return '安装插件'
    case 'uninstall': return '卸载插件'
    case 'toggle': return '切换插件状态'
    case 'update': return '更新插件'
    case 'setup': return '初始化 pnpm'
    case 'approve': return '放行插件构建脚本'
    case 'restart': return '重启 Harness'
  }
}

function responseError(action: string, response: ResponseEnvelope): MarketRequestError {
  const detail = responseDetail(asRecord(response.body))
  return new MarketRequestError(`${action}失败${response.status > 0 ? `（HTTP ${response.status}）` : ''}`, response.status, detail)
}

function responseDetail(record: Record<string, unknown> | null): string | undefined {
  if (record === null) return undefined
  const details: string[] = []
  for (const field of ['reason', 'error', 'message', 'stderr', 'stdout'] as const) {
    const detail = failureReason(diagnosticValue(record[field]))
    if (detail !== null && !details.includes(detail)) details.push(detail)
    if (details.length >= 3) break
  }
  if (details.length > 0) return [...details.join('；')].slice(0, 700).join('')
  return typeof record.exitCode === 'number' && Number.isSafeInteger(record.exitCode) ? `插件命令退出码 ${record.exitCode}` : undefined
}

function diagnosticValue(value: unknown): unknown {
  const record = asRecord(value)
  return typeof record?.text === 'string' ? record.text : value
}

function formatMarketError(action: string, failure: unknown): string {
  if (failure instanceof MarketPayloadError) return `${action}失败：${failure.message}`
  if (failure instanceof MarketRequestError) {
    if (failure.detail !== undefined) return `${action}失败：${failure.detail}`
    return failure.message.startsWith(`${action}失败`) ? failure.message : `${action}失败：${failure.message}`
  }
  return `${action}失败：${failureReason(failure) ?? '未提供详细原因'}`
}

function safeDiagnostic(value: unknown): string | undefined {
  const reason = failureReason(diagnosticValue(value))
  return reason === null ? undefined : reason
}

function safeRegistryUrl(value: unknown): string {
  if (typeof value !== 'string' || !isSafeRegistryUrl(value)) throw new MarketPayloadError('插件目录包含无效安装地址')
  return value
}

function isSafeRegistryUrl(value: string): boolean {
  if (!isSafeText(value, MAX_URL_LENGTH)) return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname !== '' && url.username === '' && url.password === ''
  } catch { return false }
}

function isSafePackageName(value: string): boolean {
  return value.length > 0 && value.length <= MAX_PACKAGE_NAME_LENGTH
    && /^[A-Za-z0-9@][A-Za-z0-9._/@-]*$/.test(value) && !value.includes('..')
}

function isMarketPackage(value: string): boolean { return value === 'dshmarket' || value === 'dsh-market' }
function safeText(value: unknown, label: string): string {
  if (!isSafeText(value, MAX_TEXT_LENGTH)) throw new MarketPayloadError(`插件目录包含无效${label}`)
  return value
}
function runtimeText(value: unknown, label: string): string {
  if (!isSafeText(value, 1024)) throw new MarketPayloadError(`运行时插件清单包含无效${label}`)
  return value
}
function optionalSafeText(value: unknown): string | undefined {
  return value === undefined || value === null ? undefined : isSafeText(value, MAX_TEXT_LENGTH) ? value : undefined
}
function isSafeText(value: unknown, maxLength: number): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) return false
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code !== undefined && (code <= 0x1f || (code >= 0x7f && code <= 0x9f)
      || (code >= 0x200b && code <= 0x200f) || (code >= 0x202a && code <= 0x202e)
      || (code >= 0x2066 && code <= 0x206f))) return false
  }
  return true
}
function optionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined
}
function optionalStatusText<Key extends 'boot' | 'target' | 'lastLine' | 'currentPackage'>(key: Key, value: unknown): Partial<Record<Key, string>> {
  const result = safeDiagnostic(value)
  return result === undefined ? {} : { [key]: result } as Partial<Record<Key, string>>
}
function optionalStatusNumber<Key extends 'seconds' | 'done' | 'total'>(key: Key, value: unknown): Partial<Record<Key, number>> {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? { [key]: value } as Partial<Record<Key, number>> : {}
}
function readSafeNameList(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return [...new Set(value.filter((item: unknown): item is string => typeof item === 'string' && isSafePackageName(item)).slice(0, MAX_PLUGINS))]
}
function readBuildPackages(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return [...new Set(value.map(item => typeof item === 'string' ? stripPackageVersion(item) : '').filter(isSafePackageName).slice(0, 100))]
}
function stripPackageVersion(value: string): string {
  const index = value.lastIndexOf('@')
  return index > 0 ? value.slice(0, index) : value
}
function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
}
function isAbortError(value: unknown): boolean { return value instanceof DOMException && value.name === 'AbortError' }
