import { useState } from 'react'
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

  const handleTest = async (provider: CustomModelProvider) => {
    if (!onTestConnectivity) return
    setTestingId(provider.id)
    try {
      const res = await onTestConnectivity(provider, credentials[provider.id])
      setTestResults(current => ({ ...current, [provider.id]: res }))
    } finally {
      setTestingId(null)
    }
  }

  /**
   * 取回上游模型并并进模型表。
   *
   * 不经过 [update]：那个函数会顺手丢掉「测试连接」的结果，而获取模型不改变端点与密钥，
   * 上一次连接成功的结论依然成立。
   */
  const handleFetch = async (provider: CustomModelProvider) => {
    if (!onFetchModels) return
    setFetchingId(provider.id)
    try {
      const result = await onFetchModels(provider, credentials[provider.id])
      setCatalogResults(current => ({ ...current, [provider.id]: result }))
      if (result.ok && result.models.length > 0) {
        const updateModelsFromLatest = (current: CustomModelProvider[]): CustomModelProvider[] => {
          const index = current.findIndex(item => item.id === provider.id)
          if (index < 0) return current
          const latest = current[index]
          const models = mergeCatalogModels(latest.models, result.models)
          return current.map((item, i) => i === index ? { ...item, models } : item)
        }
        if (onUpdate) onUpdate(updateModelsFromLatest)
        else onChange(updateModelsFromLatest(providers))
      }
    } finally {
      setFetchingId(null)
    }
  }

  /**
   * 丢掉某家供应商的测试结果。
   *
   * 结果只对「当时那一份 Base URL + 密钥」成立：用户改完字段继续往上打字时，
   * 上一次的 401 还红在下面，看起来就像新填的内容也报错了。
   */
  const forgetResult = (id: string): void => {
    setTestResults(current => {
      if (current[id] === undefined) return current
      const next = { ...current }
      delete next[id]
      return next
    })
  }

  const update = (index: number, changes: Partial<CustomModelProvider>): void => {
    onChange(providers.map((provider, i) => i === index ? { ...provider, ...changes } : provider))
    forgetResult(providers[index].id)
  }

  /** 只改模型表：不动「测试连接」的结论（端点与密钥都没变）。 */
  const updateModels = (index: number, models: CustomProviderModel[]): void => {
    onChange(providers.map((provider, i) => i === index ? { ...provider, models } : provider))
  }

  return <div className="custom-providers">
    {providers.map((provider, index) => <div className="custom-provider" key={provider.id}>
      <div className="custom-provider-heading">
        <h3>{provider.name || t('自定义供应商')}</h3>
        <button type="button" className="icon-button" title={t('删除供应商')} aria-label={t('删除供应商')} onClick={() => {
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
          forgetResult(provider.id)
        }} /></label>
      <div className="credential-actions">
        {onTestConnectivity && (
          <button
            type="button"
            className="button button-secondary compact-button"
            disabled={testingId === provider.id}
            onClick={() => { void handleTest(provider) }}
          >
            {testingId === provider.id ? <Loader2 className="spin" size={16} /> : <Wifi size={16} />}
            {testingId === provider.id ? t('正在测试…') : t('测试连接')}
          </button>
        )}
        {onFetchModels && (
          <button
            type="button"
            className="button button-secondary compact-button"
            disabled={fetchingId !== null}
            onClick={() => { void handleFetch(provider) }}
          >
            {fetchingId === provider.id ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
            {fetchingId === provider.id ? t('正在获取…') : t('获取模型')}
          </button>
        )}
        {configured.includes(provider.id) && <button type="button" className="button button-danger-quiet compact-button" onClick={() => {
          onCredentials(Object.fromEntries(Object.entries(credentials).filter(([id]) => id !== provider.id)))
          onClear(cleared.includes(provider.id) ? cleared.filter(id => id !== provider.id) : [...cleared, provider.id])
          forgetResult(provider.id)
        }}>{cleared.includes(provider.id) ? <RotateCcw size={16} /> : <Trash2 size={16} />}{cleared.includes(provider.id) ? t('撤销清除密钥') : t('清除密钥')}</button>}
      </div>
      {testResults[provider.id] && (
        <div className={`connectivity-result ${testResults[provider.id].ok ? 'success' : 'error'}`}>
          {testResults[provider.id].message}
        </div>
      )}
      {catalogResults[provider.id] && (
        <div className={`connectivity-result ${catalogResults[provider.id].ok ? 'success' : 'error'}`}>
          {catalogResults[provider.id].message}
        </div>
      )}
      {catalogResults[provider.id]?.ok && catalogResults[provider.id].models.length > 1 && (
        <div className="model-catalog-list" role="list" aria-label={t('上游模型')}>
          {catalogResults[provider.id].models.map(entry => <button
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
    </div>)}
    <button type="button" className="button button-secondary" disabled={providers.length >= MAX_CUSTOM_PROVIDERS} onClick={() => {
      let number = 1
      while (providers.some(provider => provider.id === `custom-${number}`)) number++
      onChange([...providers, { id: `custom-${number}`, name: '', api: 'openai-completions', baseUrl: '', models: [{ id: '', name: '', contextWindow: 131072, maxTokens: 8192 }] }])
    }}><Plus size={18} />{t('添加自定义供应商')}</button>
  </div>
}
