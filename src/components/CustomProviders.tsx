import { useRef, useState } from 'react'
import { AlertTriangle, Loader2, Plus, RefreshCw, RotateCcw, Trash2, Wifi } from 'lucide-react'
import { t } from '../i18n'
import type { CustomModelProvider, CustomProviderApi, CustomProviderModel, ModelCatalogResult, ModelConnectivityResult } from '../platform/types'
import { MAX_CUSTOM_MODELS, MAX_CUSTOM_PROVIDERS } from '../platform/customProviders'
import { mergeCatalogModels } from '../platform/modelCatalog'
import { ApiKeyInput } from './ApiKeyInput'

interface Props {
  providers: CustomModelProvider[]
  /** App 加密存储中的凭据；只有这些凭据能由本组件清除。 */
  configured: string[]
  /** Harness 自己的凭据文件中的只读配置状态。 */
  harnessConfigured?: string[]
  credentials: Record<string, string>
  cleared: string[]
  onChange: (providers: CustomModelProvider[]) => void
  /** 用最新草稿计算变更，避免异步获取模型结果覆盖用户随后编辑的内容。 */
  onUpdate?: (update: (providers: CustomModelProvider[]) => CustomModelProvider[]) => void
  onCredentials: (credentials: Record<string, string>) => void
  onClear: (ids: string[]) => void
  onTestConnectivity?: (provider: CustomModelProvider, draftKey?: string) => Promise<ModelConnectivityResult>
  /** 拉取上游模型列表；结果由本组件决定怎么并进模型表。 */
  onFetchModels?: (provider: CustomModelProvider, draftKey?: string) => Promise<ModelCatalogResult>
}

export function CustomProviders({
  providers,
  configured,
  harnessConfigured = [],
  credentials,
  cleared,
  onChange,
  onUpdate,
  onCredentials,
  onClear,
  onTestConnectivity,
  onFetchModels,
}: Props) {
  const [testingId, setTestingId] = useState<string | null>(null)
  const [testResults, setTestResults] = useState<Record<string, ModelConnectivityResult>>({})
  const [fetchingId, setFetchingId] = useState<string | null>(null)
  const [catalogResults, setCatalogResults] = useState<Record<string, ModelCatalogResult>>({})

  // Provider ids are editable, so they cannot identify a card (or an in-flight request).
  // Keep an instance key for each array position; delete explicitly removes the key so a
  // later provider with the same id starts with a fresh request/result namespace.
  const cardKeysRef = useRef<string[]>([])
  const requestVersionsRef = useRef({ test: new Map<string, number>(), fetch: new Map<string, number>() })
  const loadingVersionsRef = useRef({ test: new Map<string, number>(), fetch: new Map<string, number>() })
  const nextCardKeyRef = useRef(0)
  const providersRef = useRef(providers)
  const credentialsRef = useRef(credentials)
  providersRef.current = providers
  credentialsRef.current = credentials

  const cardKeyAt = (index: number): string => {
    const existing = cardKeysRef.current[index]
    if (existing) return existing
    const key = `custom-provider-${nextCardKeyRef.current++}`
    cardKeysRef.current[index] = key
    return key
  }

  // The parent normally changes providers through update/delete below. Trimming here also
  // protects against an external removal, preventing a later same-id card from reusing state.
  if (cardKeysRef.current.length > providers.length) cardKeysRef.current.length = providers.length

  const invalidate = (cardKey: string): void => {
    for (const versions of Object.values(requestVersionsRef.current)) {
      versions.set(cardKey, (versions.get(cardKey) ?? 0) + 1)
    }
    loadingVersionsRef.current.test.delete(cardKey)
    loadingVersionsRef.current.fetch.delete(cardKey)
    setTestingId(current => current === cardKey ? null : current)
    setFetchingId(current => current === cardKey ? null : current)
  }

  const beginRequest = (kind: 'test' | 'fetch', cardKey: string): number => {
    const versions = requestVersionsRef.current[kind]
    const version = (versions.get(cardKey) ?? 0) + 1
    versions.set(cardKey, version)
    return version
  }

  const isCurrentRequest = (kind: 'test' | 'fetch', cardKey: string, version: number): boolean => {
    if (requestVersionsRef.current[kind].get(cardKey) !== version) return false
    const index = cardKeysRef.current.indexOf(cardKey)
    return index >= 0 && providersRef.current[index] !== undefined
  }

  const forgetResult = (cardKey: string): void => {
    setTestResults(current => {
      if (current[cardKey] === undefined) return current
      const next = { ...current }
      delete next[cardKey]
      return next
    })
    setCatalogResults(current => {
      if (current[cardKey] === undefined) return current
      const next = { ...current }
      delete next[cardKey]
      return next
    })
  }

  const handleTest = async (provider: CustomModelProvider, index: number) => {
    if (!onTestConnectivity) return
    const cardKey = cardKeyAt(index)
    const version = beginRequest('test', cardKey)
    const snapshot = { id: provider.id, api: provider.api, baseUrl: provider.baseUrl, draftKey: credentials[provider.id] }
    setTestingId(cardKey)
    loadingVersionsRef.current.test.set(cardKey, version)
    try {
      const res = await onTestConnectivity(provider, snapshot.draftKey)
      if (!isCurrentRequest('test', cardKey, version)) return
      const currentIndex = cardKeysRef.current.indexOf(cardKey)
      const currentProvider = currentIndex >= 0 ? providersRef.current[currentIndex] : undefined
      if (!currentProvider || currentProvider.id !== snapshot.id || currentProvider.api !== snapshot.api || currentProvider.baseUrl !== snapshot.baseUrl || credentialsRef.current[currentProvider.id] !== snapshot.draftKey) return
      setTestResults(current => ({ ...current, [cardKey]: res }))
    } finally {
      if (loadingVersionsRef.current.test.get(cardKey) === version) {
        loadingVersionsRef.current.test.delete(cardKey)
        setTestingId(current => current === cardKey ? null : current)
      }
    }
  }

  /**
   * 取回上游模型并并进模型表。
   *
   * 不经过 [update]：那个函数会顺手丢掉「测试连接」的结果，而获取模型不改变端点与密钥，
   * 上一次连接成功的结论依然成立。
   */
  const handleFetch = async (provider: CustomModelProvider, index: number) => {
    if (!onFetchModels) return
    const cardKey = cardKeyAt(index)
    const version = beginRequest('fetch', cardKey)
    const snapshot = { id: provider.id, api: provider.api, baseUrl: provider.baseUrl, draftKey: credentials[provider.id] }
    setFetchingId(cardKey)
    loadingVersionsRef.current.fetch.set(cardKey, version)
    try {
      const result = await onFetchModels(provider, snapshot.draftKey)
      if (!isCurrentRequest('fetch', cardKey, version)) return
      const currentIndex = cardKeysRef.current.indexOf(cardKey)
      const currentProvider = currentIndex >= 0 ? providersRef.current[currentIndex] : undefined
      if (!currentProvider || currentProvider.id !== snapshot.id || currentProvider.api !== snapshot.api || currentProvider.baseUrl !== snapshot.baseUrl || credentialsRef.current[currentProvider.id] !== snapshot.draftKey) return
      setCatalogResults(current => ({ ...current, [cardKey]: result }))
      if (result.ok && result.models.length > 0) {
        const updateModelsFromLatest = (current: CustomModelProvider[]): CustomModelProvider[] => {
          const latestIndex = cardKeysRef.current.indexOf(cardKey)
          if (latestIndex < 0 || !current[latestIndex]) return current
          const latest = current[latestIndex]
          const models = mergeCatalogModels(latest.models, result.models)
          return current.map((item, i) => i === latestIndex ? { ...item, models } : item)
        }
        if (onUpdate) onUpdate(updateModelsFromLatest)
        else onChange(updateModelsFromLatest(providersRef.current))
      }
    } finally {
      if (loadingVersionsRef.current.fetch.get(cardKey) === version) {
        loadingVersionsRef.current.fetch.delete(cardKey)
        setFetchingId(current => current === cardKey ? null : current)
      }
    }
  }

  const update = (index: number, changes: Partial<CustomModelProvider>): void => {
    const cardKey = cardKeyAt(index)
    invalidate(cardKey)
    forgetResult(cardKey)
    onChange(providers.map((provider, i) => i === index ? { ...provider, ...changes } : provider))
  }

  /** 只改模型表：不动「测试连接」的结论（端点与密钥都没变）。 */
  const updateModels = (index: number, models: CustomProviderModel[]): void => {
    onChange(providers.map((provider, i) => i === index ? { ...provider, models } : provider))
  }

  return <div className="custom-providers">
    {providers.map((provider, index) => {
      const cardKey = cardKeyAt(index)
      return <div className="custom-provider" key={cardKey}>
      <div className="custom-provider-heading">
        <h3>{provider.name || t('自定义供应商')}</h3>
        <button type="button" className="icon-button" title={t('删除供应商')} aria-label={t('删除供应商')} onClick={() => {
          invalidate(cardKey)
          cardKeysRef.current.splice(index, 1)
          forgetResult(cardKey)
          onChange(providers.filter((_, i) => i !== index))
          onCredentials(Object.fromEntries(Object.entries(credentials).filter(([id]) => id !== provider.id)))
          onClear(cleared.filter(id => id !== provider.id))
        }}><Trash2 size={18} /></button>
      </div>
      <div className="custom-provider-fields">
        <label className="field"><span>{t('供应商标识')}</span><input required maxLength={48} pattern="[a-z][a-z0-9]*(?:-[a-z0-9]+)*" title={t('小写字母与数字，用连字符分隔，例如 gateway-1')} value={provider.id} disabled={configured.includes(provider.id) || harnessConfigured.includes(provider.id)} onChange={event => {
          const nextId = event.target.value
          if (credentials[provider.id]) {
            const next = { ...credentials, [nextId]: credentials[provider.id] }
            delete next[provider.id]
            onCredentials(next)
          }
          update(index, { id: nextId })
        }} /></label>
        <label className="field"><span>{t('供应商名称')}</span><input required maxLength={80} value={provider.name} onChange={event => update(index, { name: event.target.value })} /></label>
      </div>
      <label className="field"><span>{t('API 协议')}</span><select value={provider.api} onChange={event => update(index, { api: event.target.value as CustomProviderApi })}>
        <option value="openai-completions">OpenAI Chat Completions</option>
        <option value="openai-responses">OpenAI Responses</option>
        <option value="anthropic-messages">Anthropic Messages</option>
      </select></label>
      <label className="field"><span>Base URL</span><input required type="url" maxLength={2048} placeholder="https://api.example.com/v1" value={provider.baseUrl} onChange={event => update(index, { baseUrl: event.target.value })} /></label>
      {(() => {
        try {
          return new URL(provider.baseUrl.trim()).protocol === 'http:'
        } catch {
          return false
        }
      })() && <div className="inline-alert warning" role="alert">
        <AlertTriangle size={18} aria-hidden="true" />
        <div><span>{t('HTTP 连接不安全：API Key 和请求内容可能以明文传输，建议改用 HTTPS。')}</span></div>
      </div>}
      <label className="field"><span>{provider.name || provider.id} API Key</span><ApiKeyInput
        ariaLabel={`${provider.name || provider.id} API Key`}
        value={credentials[provider.id] ?? ''}
        placeholder={harnessConfigured.includes(provider.id) && (!configured.includes(provider.id) || cleared.includes(provider.id))
          ? t('已在 Harness 中配置，留空保持不变')
          : configured.includes(provider.id) && !cleared.includes(provider.id)
            ? t('已配置，留空保持不变')
            : t('输入 API Key')}
        onValueChange={value => {
          const next = { ...credentials }
          if (value) next[provider.id] = value
          else delete next[provider.id]
          onCredentials(next)
          onClear(cleared.filter(id => id !== provider.id))
          invalidate(cardKey)
          forgetResult(cardKey)
        }} /></label>
      <div className="credential-actions">
        {onTestConnectivity && (
          <button
            type="button"
            className="button button-secondary compact-button"
            disabled={testingId === cardKey}
            onClick={() => { void handleTest(provider, index) }}
          >
            {testingId === cardKey ? <Loader2 className="spin" size={16} /> : <Wifi size={16} />}
            {testingId === cardKey ? t('正在测试…') : t('测试连接')}
          </button>
        )}
        {onFetchModels && (
          <button
            type="button"
            className="button button-secondary compact-button"
            disabled={fetchingId !== null}
            onClick={() => { void handleFetch(provider, index) }}
          >
            {fetchingId === cardKey ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
            {fetchingId === cardKey ? t('正在获取…') : t('获取模型')}
          </button>
        )}
        {configured.includes(provider.id) && <button type="button" className="button button-danger-quiet compact-button" onClick={() => {
          onCredentials(Object.fromEntries(Object.entries(credentials).filter(([id]) => id !== provider.id)))
          onClear(cleared.includes(provider.id) ? cleared.filter(id => id !== provider.id) : [...cleared, provider.id])
          invalidate(cardKey)
          forgetResult(cardKey)
        }}>{cleared.includes(provider.id) ? <RotateCcw size={16} /> : <Trash2 size={16} />}{cleared.includes(provider.id) ? t('撤销清除密钥') : t('清除密钥')}</button>}
      </div>
      {testResults[cardKey] && (
        <div className={`connectivity-result ${testResults[cardKey].ok ? 'success' : 'error'}`}>
          {testResults[cardKey].message}
        </div>
      )}
      {catalogResults[cardKey] && (
        <div className={`connectivity-result ${catalogResults[cardKey].ok ? 'success' : 'error'}`}>
          {catalogResults[cardKey].message}
        </div>
      )}
      {catalogResults[cardKey]?.ok && catalogResults[cardKey].models.length > 1 && (
        <div className="model-catalog-list" role="list" aria-label={t('上游模型')}>
          {catalogResults[cardKey].models.map(entry => <button
            key={entry.id}
            type="button"
            role="listitem"
            className={`model-catalog-item${provider.models[0]?.id === entry.id ? ' is-selected' : ''}`}
            aria-pressed={provider.models[0]?.id === entry.id}
            title={t('设为第一个模型')}
            onClick={() => {
              // 点哪一条，哪一条就排到最前面：与「自动选第一个」是同一套规则，只是由用户指定。
              updateModels(index, mergeCatalogModels(provider.models, [entry]))
            }}
          >
            <span className="model-catalog-name">{entry.name}</span>
            {entry.name !== entry.id && <span className="model-catalog-id">{entry.id}</span>}
          </button>)}
        </div>
      )}
      {provider.models.map((model, modelIndex) => <fieldset className="custom-model" key={modelIndex}>
        <legend>{t('模型 {0}', modelIndex + 1)}</legend>
        <div className="custom-provider-fields">
          <label className="field"><span>{t('模型 ID')}</span><input required maxLength={200} value={model.id} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, id: event.target.value } : item) })} /></label>
          <label className="field"><span>{t('模型名称')}</span><input required maxLength={100} value={model.name} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, name: event.target.value } : item) })} /></label>
          <label className="field"><span>{t('上下文长度')}</span><input required type="number" min={1} max={10000000} value={model.contextWindow} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, contextWindow: Number(event.target.value) } : item) })} /></label>
          <label className="field"><span>{t('最大输出长度')}</span><input required type="number" min={1} max={model.contextWindow} value={model.maxTokens} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, maxTokens: Number(event.target.value) } : item) })} /></label>
        </div>
        <button type="button" className="icon-button" title={t('删除模型')} aria-label={t('删除模型')} disabled={provider.models.length === 1} onClick={() => update(index, { models: provider.models.filter((_, i) => i !== modelIndex) })}><Trash2 size={16} /></button>
      </fieldset>)}
      <button type="button" className="button button-secondary compact-button" disabled={provider.models.length >= MAX_CUSTOM_MODELS} onClick={() => update(index, { models: [...provider.models, { id: '', name: '', contextWindow: 131072, maxTokens: 8192 }] })}><Plus size={16} />{t('添加模型')}</button>
    </div>})}
    <button type="button" className="button button-secondary" disabled={providers.length >= MAX_CUSTOM_PROVIDERS} onClick={() => {
      let number = 1
      while (providers.some(provider => provider.id === `custom-${number}`)) number++
      cardKeysRef.current.push(`custom-provider-${nextCardKeyRef.current++}`)
      onChange([...providers, { id: `custom-${number}`, name: '', api: 'openai-completions', baseUrl: '', models: [{ id: '', name: '', contextWindow: 131072, maxTokens: 8192 }] }])
    }}><Plus size={18} />{t('添加自定义供应商')}</button>
  </div>
}
