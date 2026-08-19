import {
  ArrowLeft,
  Blocks,
  Bot,
  Check,
  Eye,
  FolderPlus,
  LoaderCircle,
  Pencil,
  Trash2,
  X,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'
import type {
  AgentPresetEntry,
  AgentPresetReadValue,
  ConfigurableProviderView,
  CredentialView,
  HostDescribeValue,
  SessionId,
  SessionSummary,
  SettingsDescribeValue,
  SettingsNamespaceView,
  WorkspaceView,
} from '../api/types'
import { PluginMarketplaceView } from './PluginMarketplaceView'
import { RuntimeSettingsPanel } from './RuntimeSettingsPanel'

const LAST_SESSION_KEY = 'dsh-mobile-last-session-v1'
const MAX_PATH_CHARACTERS = 4096
const MAX_PATH_BYTES = 16_384
const MAX_TITLE_CHARACTERS = 80
const MAX_TITLE_BYTES = 80
const MAX_SECRET_CHARACTERS = 16_384
const MAX_SECRET_BYTES = 65_536
const MAX_OPAQUE_ID_CHARACTERS = 512
const MAX_OPAQUE_ID_BYTES = 2048

type PresetCapabilities = {
  authorable: boolean
  hasDocument: boolean
}

/** 设置页只调用 Harness 现有 RPC，不承担任何运行时或后端配置逻辑。 */
export function SettingsView(props: { onBack: () => void; currentSessionId?: SessionId | null }): ReactElement {
  const { onBack } = props
  const [providers, setProviders] = useState<ConfigurableProviderView[]>([])
  const [describe, setDescribe] = useState<SettingsDescribeValue | null>(null)
  const [host, setHost] = useState<HostDescribeValue | null>(null)
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([])
  const [presets, setPresets] = useState<AgentPresetEntry[]>([])
  const [presetCapabilities, setPresetCapabilities] = useState<PresetCapabilities | null>(null)
  const [presetDetails, setPresetDetails] = useState<Record<string, AgentPresetReadValue>>({})
  const [currentSession, setCurrentSession] = useState<SessionSummary | null>(null)
  const [credentials, setCredentials] = useState<Record<string, CredentialView>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [credentialDrafts, setCredentialDrafts] = useState<Record<string, string>>({})
  const [expandedProvider, setExpandedProvider] = useState<string | null>(null)
  const [expandedPreset, setExpandedPreset] = useState<string | null>(null)
  const [workspacePath, setWorkspacePath] = useState('')
  const [editingWorkspaceId, setEditingWorkspaceId] = useState<string | null>(null)
  const [workspaceTitle, setWorkspaceTitle] = useState('')
  const [deleteWorkspaceId, setDeleteWorkspaceId] = useState<string | null>(null)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const reloadGenerationRef = useRef(0)
  const reloadControllerRef = useRef<AbortController | null>(null)

  const reload = useCallback(async () => {
    const generation = ++reloadGenerationRef.current
    reloadControllerRef.current?.abort()
    const controller = new AbortController()
    reloadControllerRef.current = controller
    setLoading(true)
    setError(null)
    const results = await Promise.allSettled([
      callUnary(window.location.origin, 'llm.providers', {}, { signal: controller.signal }),
      callUnary(window.location.origin, 'settings.describe', {}, { signal: controller.signal }),
      callUnary(window.location.origin, 'host.describe', {}, { signal: controller.signal }),
      callUnary(window.location.origin, 'workspace.list', {}, { signal: controller.signal }),
      callUnary(window.location.origin, 'agentPreset.list', {}, { signal: controller.signal }),
      callUnary(window.location.origin, 'session.list', {}, { signal: controller.signal }),
    ] as const)
    if (controller.signal.aborted || reloadGenerationRef.current !== generation) return
    const loadErrors: string[] = []

    const [providerResult, describeResult, hostResult, workspaceResult, presetResult, sessionResult] = results
    if (providerResult.status === 'fulfilled') setProviders(providerResult.value.providers)
    else if (!isAbortFailure(providerResult.reason)) loadErrors.push(rpcErrorMessage('载入模型提供商', providerResult.reason))

    if (describeResult.status === 'fulfilled') setDescribe(describeResult.value)
    else if (!isAbortFailure(describeResult.reason)) loadErrors.push(rpcErrorMessage('载入设置说明', describeResult.reason))

    if (hostResult.status === 'fulfilled') setHost(hostResult.value)
    else if (!isAbortFailure(hostResult.reason)) loadErrors.push(rpcErrorMessage('载入运行时信息', hostResult.reason))

    if (workspaceResult.status === 'fulfilled') setWorkspaces(workspaceResult.value.items)
    else if (!isAbortFailure(workspaceResult.reason)) loadErrors.push(rpcErrorMessage('载入工作区', workspaceResult.reason))

    if (presetResult.status === 'fulfilled') {
      setPresets(presetResult.value.presets)
      setPresetCapabilities({
        authorable: presetResult.value.authorable,
        hasDocument: presetResult.value.hasDocument,
      })
      const availableIds = new Set(presetResult.value.presets.map((preset) => preset.id))
      setPresetDetails((previous) => Object.fromEntries(
        Object.entries(previous).filter(([presetId]) => availableIds.has(presetId)),
      ))
    } else {
      if (!isAbortFailure(presetResult.reason)) loadErrors.push(rpcErrorMessage('载入 Agent preset', presetResult.reason))
    }

    if (sessionResult.status === 'fulfilled') {
      setCurrentSession(selectPresetTargetSession(sessionResult.value.items, props.currentSessionId))
    } else {
      if (!isAbortFailure(sessionResult.reason)) loadErrors.push(rpcErrorMessage('定位当前会话', sessionResult.reason))
    }

    if (providerResult.status === 'fulfilled' && describeResult.status === 'fulfilled') {
      const namespaces = new Map(describeResult.value.namespaces.map((item) => [item.ns, item]))
      const refs = [...new Set(providerResult.value.providers.flatMap((provider) => {
        const ref = providerCredentialRef(provider, namespaces.get(provider.settingsNs))
          ?? deriveCredentialRef(provider.provider)
        return validateCredentialRef(ref) === null ? [ref] : []
      }))]
      if (refs.length === 0) {
        setCredentials({})
      } else {
        try {
          const value = await callUnary(
            window.location.origin,
            'credentials.describe',
            { refs },
            { signal: controller.signal },
          )
          if (controller.signal.aborted || reloadGenerationRef.current !== generation) return
          setCredentials(value.credentials)
        } catch (failure) {
          if (controller.signal.aborted || reloadGenerationRef.current !== generation) return
          setCredentials({})
          if (!isAbortFailure(failure)) loadErrors.push(rpcErrorMessage('载入模型凭据状态', failure))
        }
      }
    } else {
      setCredentials({})
    }

    if (controller.signal.aborted || reloadGenerationRef.current !== generation) return
    setError(loadErrors.length === 0 ? null : loadErrors.join('；'))
    setLoading(false)
  }, [props.currentSessionId])

  useEffect(() => {
    void reload()
    return () => {
      reloadGenerationRef.current += 1
      reloadControllerRef.current?.abort()
      reloadControllerRef.current = null
    }
  }, [reload])

  const namespaceFor = (ns: string) => describe?.namespaces.find((item) => item.ns === ns)

  const submitCredential = async (
    provider: ConfigurableProviderView,
    namespace: SettingsNamespaceView,
    value: string,
  ): Promise<void> => {
    const rowKey = providerRowKey(provider)
    const existingRef = providerCredentialRef(provider, namespace)
    const ref = existingRef ?? deriveCredentialRef(provider.provider)
    const validationError = validateOpaqueValue(namespace.ns, '设置命名空间')
      ?? provider.settingsPath.map((segment) => validateOpaqueValue(segment, '设置路径')).find((item) => item !== null)
      ?? validateCredentialRef(ref)
      ?? validateSecret(value)
    if (validationError !== null) {
      setError(validationError)
      return
    }
    if (describe?.writable !== true) {
      setError('当前运行时的模型配置只读，无法修改模型凭据')
      return
    }
    const credential = credentials[ref]
    if (credential !== undefined && !credential.writable) {
      setError('该凭据由环境或只读配置提供，不能在此修改')
      return
    }

    setError(null)
    setBusyAction(`credential:set:${rowKey}`)
    try {
      if (existingRef === null) {
        const updated = await callUnary(window.location.origin, 'settings.mutate', {
          ns: namespace.ns,
          ops: [{ op: 'set', path: [...provider.settingsPath, 'apiKeyEnv'], value: ref }],
          expectedRevision: namespace.revision,
        })
        // Keep the committed revision/ref locally so a credential-store retry
        // never repeats the settings write with a stale revision.
        setDescribe((previous) => previous === null
          ? null
          : {
              ...previous,
              namespaces: previous.namespaces.map((item) => item.ns === updated.ns ? updated : item),
            })
      }
      await callUnary(window.location.origin, 'credentials.set', { ref, value })
      setCredentialDrafts((previous) => ({ ...previous, [rowKey]: '' }))
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage(`保存 ${provider.displayName} API 密钥`, failure))
    } finally {
      setBusyAction(null)
    }
  }

  const unsetCredential = async (
    provider: ConfigurableProviderView,
    namespace: SettingsNamespaceView,
  ): Promise<void> => {
    const rowKey = providerRowKey(provider)
    const ref = providerCredentialRef(provider, namespace)
    const validationError = ref === null ? '该提供商没有可清除的凭据引用' : validateCredentialRef(ref)
    if (validationError !== null) {
      setError(validationError)
      return
    }
    if (ref === null) return
    if (describe?.writable !== true) {
      setError('当前运行时的模型配置只读，无法修改模型凭据')
      return
    }
    const credential = credentials[ref]
    if (credential === undefined || !credential.configured || !credential.writable) {
      setError('该凭据当前不可删除')
      return
    }

    setError(null)
    setBusyAction(`credential:unset:${rowKey}`)
    try {
      await callUnary(window.location.origin, 'credentials.unset', { ref })
      setCredentialDrafts((previous) => ({ ...previous, [rowKey]: '' }))
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage(`清除 ${provider.displayName} API 密钥`, failure))
    } finally {
      setBusyAction(null)
    }
  }

  const createWorkspace = async (): Promise<void> => {
    const validated = validateWorkspacePath(workspacePath)
    if (typeof validated === 'string') {
      setError(validated)
      return
    }

    setError(null)
    setBusyAction('workspace:create')
    try {
      await callUnary(window.location.origin, 'workspace.create', { path: validated.value })
      setWorkspacePath('')
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage('创建工作区', failure))
    } finally {
      setBusyAction(null)
    }
  }

  const startWorkspaceRename = (workspace: WorkspaceView): void => {
    setDeleteWorkspaceId(null)
    setEditingWorkspaceId(workspace.workspaceId)
    setWorkspaceTitle(workspace.title)
    setError(null)
  }

  const renameWorkspace = async (workspaceId: string): Promise<void> => {
    const idError = validateOpaqueValue(workspaceId, '工作区标识')
    const validated = validateWorkspaceTitle(workspaceTitle)
    if (idError !== null) {
      setError(idError)
      return
    }
    if (typeof validated === 'string') {
      setError(validated)
      return
    }

    setError(null)
    setBusyAction(`workspace:rename:${workspaceId}`)
    try {
      await callUnary(window.location.origin, 'workspace.rename', {
        workspaceId,
        title: validated.value,
      })
      setEditingWorkspaceId(null)
      setWorkspaceTitle('')
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage('重命名工作区', failure))
    } finally {
      setBusyAction(null)
    }
  }

  const deleteWorkspace = async (workspaceId: string): Promise<void> => {
    const idError = validateOpaqueValue(workspaceId, '工作区标识')
    if (idError !== null) {
      setError(idError)
      return
    }

    setError(null)
    setBusyAction(`workspace:delete:${workspaceId}`)
    try {
      await callUnary(window.location.origin, 'workspace.delete', { workspaceId })
      setDeleteWorkspaceId(null)
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage('删除工作区', failure))
    } finally {
      setBusyAction(null)
    }
  }

  const readPreset = async (agentPreset: string): Promise<void> => {
    if (expandedPreset === agentPreset) {
      setExpandedPreset(null)
      return
    }
    const idError = validateOpaqueValue(agentPreset, 'Agent preset 标识')
    if (idError !== null) {
      setError(idError)
      return
    }
    if (presetDetails[agentPreset] !== undefined) {
      setExpandedPreset(agentPreset)
      return
    }

    setError(null)
    setBusyAction(`preset:read:${agentPreset}`)
    try {
      const detail = await callUnary(window.location.origin, 'agentPreset.read', { agentPreset })
      setPresetDetails((previous) => ({ ...previous, [agentPreset]: detail }))
      setExpandedPreset(agentPreset)
    } catch (failure) {
      setError(rpcErrorMessage(`读取 Agent preset ${agentPreset}`, failure))
    } finally {
      setBusyAction(null)
    }
  }

  const selectPreset = async (agentPreset: string): Promise<void> => {
    const presetError = validateOpaqueValue(agentPreset, 'Agent preset 标识')
    const sessionError = currentSession === null
      ? '没有可应用 Agent preset 的会话，请先返回对话并创建会话'
      : !currentSession.blank
        ? 'Agent preset 只能应用到尚未发送消息的空白会话'
        : validateOpaqueValue(currentSession.sessionId, '会话标识')
    if (presetError !== null || sessionError !== null) {
      setError(presetError ?? sessionError)
      return
    }
    if (currentSession === null) return

    setError(null)
    setBusyAction(`preset:select:${agentPreset}`)
    try {
      const selected = await callUnary(window.location.origin, 'agentPreset.select', {
        sessionId: currentSession.sessionId,
        agentPreset,
      })
      setCurrentSession((previous) => previous === null
        ? null
        : { ...previous, agentPreset: selected.agentPreset })
    } catch (failure) {
      setError(rpcErrorMessage(`应用 Agent preset ${agentPreset}`, failure))
    } finally {
      setBusyAction(null)
    }
  }

  return (
    <main className="view">
      <header className="view-header secondary-header">
        <button type="button" className="icon-button" aria-label="返回对话" title="返回对话" onClick={onBack}><ArrowLeft size={20} /></button>
        <h1>设置</h1>
        <button type="button" className="btn" disabled={loading || busyAction !== null} onClick={() => void reload()}>
          {loading ? <LoaderCircle className="spin" size={16} aria-hidden="true" /> : null}刷新
        </button>
      </header>
      {error !== null && <p className="error-bar" role="alert" onClick={() => setError(null)}>{error}</p>}
      <div className="view-body">
        <h2 className="section-title">工作区</h2>
        <label className="field-label" htmlFor="workspace-path">工作区绝对路径</label>
        <div className="secret-row">
          <input
            id="workspace-path"
            className="field"
            type="text"
            autoComplete="off"
            maxLength={MAX_PATH_CHARACTERS}
            placeholder="/home/user/project"
            value={workspacePath}
            onChange={(event) => setWorkspacePath(event.target.value)}
          />
          <button
            type="button"
            className="btn"
            disabled={busyAction !== null || workspacePath.trim() === ''}
            onClick={() => void createWorkspace()}
          >
            {busyAction === 'workspace:create'
              ? <LoaderCircle className="spin" size={16} aria-hidden="true" />
              : <FolderPlus size={16} aria-hidden="true" />}
            添加
          </button>
        </div>
        {workspaces.length === 0 ? (
          <p className="hint">尚未添加工作区</p>
        ) : workspaces.map((workspace) => {
          const editing = editingWorkspaceId === workspace.workspaceId
          const confirmingDelete = deleteWorkspaceId === workspace.workspaceId
          const deleting = busyAction === `workspace:delete:${workspace.workspaceId}`
          return (
            <div key={workspace.workspaceId} className="provider-card">
              <div className="list-row">
                <span className="list-main">
                  <span className="list-title">{workspace.title}</span>
                  <span className="list-sub">{workspace.path} · {workspace.sessionIds.length} 个会话</span>
                </span>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`重命名工作区 ${workspace.title}`}
                  title="重命名"
                  disabled={busyAction !== null}
                  onClick={() => startWorkspaceRename(workspace)}
                >
                  <Pencil size={17} />
                </button>
                <button
                  type="button"
                  className="icon-button icon-button-danger"
                  aria-label={`删除工作区 ${workspace.title}`}
                  title="删除"
                  disabled={busyAction !== null}
                  onClick={() => {
                    setEditingWorkspaceId(null)
                    setDeleteWorkspaceId(workspace.workspaceId)
                    setError(null)
                  }}
                >
                  <Trash2 size={17} />
                </button>
              </div>
              {editing && (
                <div className="provider-config">
                  <label className="field-label" htmlFor={`workspace-title-${workspace.workspaceId}`}>工作区名称</label>
                  <div className="secret-row">
                    <input
                      id={`workspace-title-${workspace.workspaceId}`}
                      className="field"
                      type="text"
                      autoComplete="off"
                      maxLength={MAX_TITLE_CHARACTERS}
                      value={workspaceTitle}
                      onChange={(event) => setWorkspaceTitle(event.target.value)}
                    />
                    <button
                      type="button"
                      className="btn btn-primary"
                      aria-label={`保存工作区 ${workspace.title} 的新名称`}
                      disabled={busyAction !== null || workspaceTitle.trim() === ''}
                      onClick={() => void renameWorkspace(workspace.workspaceId)}
                    ><Check size={16} />保存</button>
                    <button
                      type="button"
                      className="icon-button"
                      aria-label="取消重命名"
                      title="取消"
                      disabled={busyAction !== null}
                      onClick={() => setEditingWorkspaceId(null)}
                    ><X size={17} /></button>
                  </div>
                </div>
              )}
              {confirmingDelete && (
                <div className="provider-config">
                  <p className="session-title-error">将从 Harness 中移除此工作区分组，请确认后继续。</p>
                  <div className="secret-row">
                    <button
                      type="button"
                      className="btn btn-danger"
                      disabled={busyAction !== null}
                      onClick={() => void deleteWorkspace(workspace.workspaceId)}
                    >
                      {deleting && <LoaderCircle className="spin" size={16} aria-hidden="true" />}
                      确认删除
                    </button>
                    <button
                      type="button"
                      className="btn"
                      disabled={busyAction !== null}
                      onClick={() => setDeleteWorkspaceId(null)}
                    >取消</button>
                  </div>
                </div>
              )}
            </div>
          )
        })}

        <h2 className="section-title"><Bot size={16} aria-hidden="true" />Agent preset</h2>
        {currentSession === null ? (
          <p className="hint">只有尚未发送消息的空白会话可以切换 preset</p>
        ) : (
          <p className="field-label">应用目标：{shortSessionId(currentSession.sessionId)}</p>
        )}
        {presets.length === 0 ? (
          <p className="hint">没有可用的 Agent preset</p>
        ) : presets.map((preset) => {
          const detail = presetDetails[preset.id]
          const expanded = expandedPreset === preset.id
          const reading = busyAction === `preset:read:${preset.id}`
          const selecting = busyAction === `preset:select:${preset.id}`
          const selected = currentSession !== null
            && (currentSession.agentPreset === preset.id
              || (currentSession.agentPreset === undefined && preset.isDefault))
          return (
            <div key={preset.id} className="provider-card">
              <button
                type="button"
                className="list-row"
                aria-expanded={expanded}
                disabled={busyAction !== null}
                onClick={() => void readPreset(preset.id)}
              >
                <span className="list-main">
                  <span className="list-title">
                    {preset.name ?? preset.id}
                    {preset.isDefault && <span className="badge">默认</span>}
                    {selected && <span className="badge badge-running">当前</span>}
                  </span>
                  <span className="list-sub">{preset.description ?? preset.id}</span>
                </span>
                <span className="list-time">
                  {reading ? <LoaderCircle className="spin" size={16} aria-label="正在读取" /> : expanded ? '收起' : '查看'}
                </span>
              </button>
              {preset.broken !== undefined && (
                <div className="provider-config"><p className="session-title-error">{rpcErrorMessage('载入 Agent preset', preset.broken)}</p></div>
              )}
              {expanded && detail !== undefined && (
                <div className="provider-config">
                  <dl className="host-info">
                    <dt>标识</dt><dd>{detail.agentPreset}</dd>
                    <dt>来源</dt><dd>{detail.trust === 'system' ? '系统' : '用户'}</dd>
                    {detail.description !== undefined && <dt>说明</dt>}
                    {detail.description !== undefined && <dd>{detail.description}</dd>}
                  </dl>
                  <label className="field-label" htmlFor={`preset-content-${preset.id}`}>Preset 内容</label>
                  <textarea
                    id={`preset-content-${preset.id}`}
                    className="field"
                    aria-label={`${preset.name ?? preset.id} preset 内容`}
                    readOnly
                    rows={10}
                    value={detail.content}
                  />
                  <button
                    type="button"
                    className="btn btn-primary btn-block"
                    disabled={busyAction !== null || currentSession === null || selected || preset.broken !== undefined}
                    onClick={() => void selectPreset(preset.id)}
                  >
                    {selecting
                      ? <LoaderCircle className="spin" size={16} aria-hidden="true" />
                      : selected ? <Check size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
                    {selected ? '已应用到当前会话' : '应用到当前会话'}
                  </button>
                </div>
              )}
            </div>
          )
        })}
        {presetCapabilities !== null && (
          <p className="field-label">
            {presetCapabilities.authorable ? '支持用户 preset' : 'Preset 由运行时管理'}
            {' · '}
            {presetCapabilities.hasDocument ? '配置文档可用' : '无外部配置文档'}
          </p>
        )}

        <RuntimeSettingsPanel
          describe={describe}
          onUpdated={(updated) => setDescribe((previous) => previous === null
            ? previous
            : { ...previous, namespaces: previous.namespaces.map((item) => item.ns === updated.ns ? updated : item) })}
        />

        <a className="btn btn-block" href={pluginWorkbenchHref()}>
          <Blocks size={16} aria-hidden="true" />
          打开完整插件工作台
        </a>

        <PluginMarketplaceView />

        {describe !== null && !describe.writable && (
          <p className="hint">当前运行时的模型配置只读；已有凭据仍按各自权限管理</p>
        )}

        <h2 className="section-title">模型提供商</h2>
        {providers.length === 0 ? (
          <p className="hint">没有可配置的提供商</p>
        ) : (
          providers.map((provider) => {
            const namespace = namespaceFor(provider.settingsNs)
            const rowKey = providerRowKey(provider)
            const existingCredentialRef = providerCredentialRef(provider, namespace)
            const credentialRef = existingCredentialRef ?? deriveCredentialRef(provider.provider)
            const credential = credentials[credentialRef]
            const credentialDraft = credentialDrafts[rowKey] ?? ''
            const isExpanded = expandedProvider === rowKey
            const readOnly = describe?.writable !== true || credential?.writable === false
            return (
              <div key={rowKey} className="provider-card">
                <button
                  type="button"
                  className="list-row"
                  aria-expanded={isExpanded}
                  onClick={() => setExpandedProvider(isExpanded ? null : rowKey)}
                >
                  <span className="list-main">
                    <span className="list-title">
                      {provider.displayName}
                      {provider.active && <span className="badge badge-running">已启用</span>}
                      {credential?.configured === true && <span className="badge badge-running">密钥已配置</span>}
                    </span>
                    <span className="list-sub">{provider.provider}</span>
                  </span>
                  <span className="list-time">{isExpanded ? '收起' : '配置'}</span>
                </button>
                {isExpanded && (
                  <div className="provider-config">
                    {namespace === undefined ? (
                      <p className="hint">该提供商的设置命名空间不可用</p>
                    ) : (
                      <>
                        <p className="field-label">
                          凭据引用：{credentialRef}
                          {existingCredentialRef === null ? '（保存时写入当前 profile）' : ''}
                        </p>
                        <div className="secret-row">
                          <label className="field-label" htmlFor={`credential-${rowKey}`}>API 密钥</label>
                          <input
                            id={`credential-${rowKey}`}
                            className="field"
                            type="password"
                            autoComplete="new-password"
                            maxLength={MAX_SECRET_CHARACTERS}
                            placeholder={credential?.configured === true ? '已配置（留空保持不变）' : '输入 API 密钥'}
                            value={credentialDraft}
                            disabled={readOnly || busyAction !== null}
                            onChange={(event) => setCredentialDrafts((previous) => ({
                              ...previous,
                              [rowKey]: event.target.value,
                            }))}
                          />
                          <button
                            type="button"
                            className="btn"
                            disabled={readOnly || busyAction !== null || credentialDraft === ''}
                            onClick={() => void submitCredential(provider, namespace, credentialDraft)}
                          >
                            {busyAction === `credential:set:${rowKey}` && <LoaderCircle className="spin" size={16} aria-hidden="true" />}
                            保存
                          </button>
                          {existingCredentialRef !== null && credential?.configured === true && (
                            <button
                              type="button"
                              className="btn btn-danger"
                              disabled={readOnly || busyAction !== null}
                              onClick={() => void unsetCredential(provider, namespace)}
                            >
                              {busyAction === `credential:unset:${rowKey}` && <LoaderCircle className="spin" size={16} aria-hidden="true" />}
                              删除
                            </button>
                          )}
                        </div>
                        {credential?.writable === false && <p className="hint">该凭据由环境或只读配置提供，不能在此修改</p>}
                        {credential === undefined && <p className="hint">凭据状态暂不可用，保存仍会由 Harness 校验</p>}
                      </>
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

function pluginWorkbenchHref(): string {
  const url = new URL(window.location.href)
  url.searchParams.set('surface', 'plugins')
  url.hash = ''
  return `${url.pathname}${url.search}`
}

function selectPresetTargetSession(
  items: SessionSummary[],
  currentSessionId: SessionId | null | undefined,
): SessionSummary | null {
  const ownerId = currentSessionId === undefined ? readLastSessionId() : currentSessionId
  if (ownerId === null) return null
  const owner = items.find((item) => item.sessionId === ownerId)
  if (owner === undefined || !owner.blank || owner.parentSessionId !== undefined || owner.origin === 'subagent') return null
  return owner
}

function providerRowKey(provider: ConfigurableProviderView): string {
  return JSON.stringify([provider.settingsNs, provider.settingsPath])
}

function providerCredentialRef(
  provider: ConfigurableProviderView,
  namespace: SettingsNamespaceView | undefined,
): string | null {
  if (namespace === undefined || !Array.isArray(provider.settingsPath)) return null
  let value: unknown = namespace.value
  for (const segment of provider.settingsPath) {
    if (typeof segment !== 'string' || !isRecord(value) || !Object.prototype.hasOwnProperty.call(value, segment)) return null
    value = value[segment]
  }
  if (!isRecord(value) || typeof value.apiKeyEnv !== 'string') return null
  const ref = value.apiKeyEnv.trim()
  return validateCredentialRef(ref) === null ? ref : null
}

function deriveCredentialRef(provider: string): string {
  const route = provider.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '')
  return `${route === '' ? 'PROVIDER' : route}_API_KEY`
}

function validateCredentialRef(value: string): string | null {
  const lengthError = validateOpaqueValue(value, '凭据引用')
  if (lengthError !== null) return lengthError
  return /^[A-Za-z_][A-Za-z0-9_]*$/u.test(value)
    ? null
    : '凭据引用必须是有效的环境变量名称'
}

function readLastSessionId(): string | null {
  try {
    const value = localStorage.getItem(LAST_SESSION_KEY)
    return value !== null && validateOpaqueValue(value, '会话标识') === null ? value : null
  } catch {
    return null
  }
}

function validateWorkspacePath(rawValue: string): { value: string } | string {
  const value = rawValue.trim()
  if (value === '') return '工作区路径不能为空'
  const lengthError = validateText(value, '工作区路径', MAX_PATH_CHARACTERS, MAX_PATH_BYTES)
  if (lengthError !== null) return lengthError
  const isAbsolute = value.startsWith('/')
    || /^[A-Za-z]:[\\/]/u.test(value)
    || /^\\\\[^\\]/u.test(value)
  if (!isAbsolute) return '工作区路径必须是绝对路径，例如 /home/user/project'
  return { value }
}

function validateWorkspaceTitle(rawValue: string): { value: string } | string {
  const value = rawValue.trim()
  if (value === '') return '工作区名称不能为空'
  const error = validateText(value, '工作区名称', MAX_TITLE_CHARACTERS, MAX_TITLE_BYTES)
  return error ?? { value }
}

function validateSecret(value: string): string | null {
  if (value === '') return '配置值不能为空'
  return validateText(value, '配置值', MAX_SECRET_CHARACTERS, MAX_SECRET_BYTES)
}

function validateOpaqueValue(value: string, label: string): string | null {
  if (value === '') return `${label}不能为空`
  return validateText(value, label, MAX_OPAQUE_ID_CHARACTERS, MAX_OPAQUE_ID_BYTES)
}

function validateText(
  value: string,
  label: string,
  maxCharacters: number,
  maxBytes: number,
): string | null {
  if ([...value].length > maxCharacters) return `${label}不能超过 ${maxCharacters} 个字符`
  if (new TextEncoder().encode(value).byteLength > maxBytes) return `${label}的 UTF-8 编码不能超过 ${maxBytes} 字节`
  if (hasInvisibleCharacters(value)) return `${label}不能包含控制字符、零宽字符或双向文本控制符`
  return null
}

function hasInvisibleCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) return true
    if (code === 0x200b || code === 0x200e || code === 0x200f || code === 0xfeff) return true
    if ((code >= 0x202a && code <= 0x202e) || (code >= 0x2060 && code <= 0x2064)) return true
    if (code >= 0x2066 && code <= 0x206f) return true
  }
  return false
}

function isAbortFailure(failure: unknown): boolean {
  return failure instanceof Error && failure.name === 'AbortError'
}

function shortSessionId(sessionId: string): string {
  return sessionId.length <= 20 ? sessionId : `${sessionId.slice(0, 8)}…${sessionId.slice(-8)}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
