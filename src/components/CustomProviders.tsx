import { Plus, Trash2, RotateCcw } from 'lucide-react'
import type { CustomModelProvider, CustomProviderApi } from '../platform/types'
import { MAX_CUSTOM_MODELS, MAX_CUSTOM_PROVIDERS } from '../platform/customProviders'

interface Props {
  providers: CustomModelProvider[]
  configured: string[]
  credentials: Record<string, string>
  cleared: string[]
  onChange: (providers: CustomModelProvider[]) => void
  onCredentials: (credentials: Record<string, string>) => void
  onClear: (ids: string[]) => void
}

export function CustomProviders({ providers, configured, credentials, cleared, onChange, onCredentials, onClear }: Props) {
  const update = (index: number, changes: Partial<CustomModelProvider>): void => {
    onChange(providers.map((provider, i) => i === index ? { ...provider, ...changes } : provider))
  }
  return <div className="custom-providers">
    {providers.map((provider, index) => <div className="custom-provider" key={index}>
      <div className="custom-provider-heading">
        <h3>{provider.name || '自定义供应商'}</h3>
        <button type="button" className="icon-button" title="删除供应商" aria-label="删除供应商" onClick={() => {
          onChange(providers.filter((_, i) => i !== index))
          onCredentials(Object.fromEntries(Object.entries(credentials).filter(([id]) => id !== provider.id)))
          onClear(cleared.filter(id => id !== provider.id))
        }}><Trash2 size={18} /></button>
      </div>
      <div className="custom-provider-fields">
        <label className="field"><span>供应商标识</span><input required maxLength={48} pattern="[a-z][a-z0-9]*(?:-[a-z0-9]+)*" value={provider.id} disabled={configured.includes(provider.id)} onChange={event => {
          const nextId = event.target.value
          if (credentials[provider.id]) {
            const next = { ...credentials, [nextId]: credentials[provider.id] }
            delete next[provider.id]
            onCredentials(next)
          }
          update(index, { id: nextId })
        }} /></label>
        <label className="field"><span>供应商名称</span><input required maxLength={80} value={provider.name} onChange={event => update(index, { name: event.target.value })} /></label>
      </div>
      <label className="field"><span>API 协议</span><select value={provider.api} onChange={event => update(index, { api: event.target.value as CustomProviderApi })}>
        <option value="openai-completions">OpenAI Chat Completions</option>
        <option value="openai-responses">OpenAI Responses</option>
        <option value="anthropic-messages">Anthropic Messages</option>
      </select></label>
      <label className="field"><span>Base URL</span><input required type="url" maxLength={2048} placeholder="https://api.example.com/v1" value={provider.baseUrl} onChange={event => update(index, { baseUrl: event.target.value })} /></label>
      <label className="field"><span>API Key</span><input type="password" autoComplete="new-password" spellCheck={false} maxLength={200} value={credentials[provider.id] ?? ''}
        placeholder={configured.includes(provider.id) && !cleared.includes(provider.id) ? '已配置，留空保持不变' : '输入 API Key'}
        onChange={event => {
          const next = { ...credentials }
          if (event.target.value) next[provider.id] = event.target.value
          else delete next[provider.id]
          onCredentials(next)
          onClear(cleared.filter(id => id !== provider.id))
        }} /></label>
      {configured.includes(provider.id) && <button type="button" className="button button-danger-quiet compact-button" onClick={() => {
        onCredentials(Object.fromEntries(Object.entries(credentials).filter(([id]) => id !== provider.id)))
        onClear(cleared.includes(provider.id) ? cleared.filter(id => id !== provider.id) : [...cleared, provider.id])
      }}>{cleared.includes(provider.id) ? <RotateCcw size={16} /> : <Trash2 size={16} />}{cleared.includes(provider.id) ? '撤销清除凭据' : '清除凭据'}</button>}
      {provider.models.map((model, modelIndex) => <fieldset className="custom-model" key={modelIndex}>
        <legend>模型 {modelIndex + 1}</legend>
        <div className="custom-provider-fields">
          <label className="field"><span>模型 ID</span><input required maxLength={200} value={model.id} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, id: event.target.value } : item) })} /></label>
          <label className="field"><span>模型名称</span><input required maxLength={100} value={model.name} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, name: event.target.value } : item) })} /></label>
          <label className="field"><span>上下文长度</span><input required type="number" min={1} max={10000000} value={model.contextWindow} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, contextWindow: Number(event.target.value) } : item) })} /></label>
          <label className="field"><span>最大输出长度</span><input required type="number" min={1} max={model.contextWindow} value={model.maxTokens} onChange={event => update(index, { models: provider.models.map((item, i) => i === modelIndex ? { ...item, maxTokens: Number(event.target.value) } : item) })} /></label>
        </div>
        <button type="button" className="icon-button" title="删除模型" aria-label="删除模型" disabled={provider.models.length === 1} onClick={() => update(index, { models: provider.models.filter((_, i) => i !== modelIndex) })}><Trash2 size={16} /></button>
      </fieldset>)}
      <button type="button" className="button button-secondary compact-button" disabled={provider.models.length >= MAX_CUSTOM_MODELS} onClick={() => update(index, { models: [...provider.models, { id: '', name: '', contextWindow: 131072, maxTokens: 8192 }] })}><Plus size={16} />添加模型</button>
    </div>)}
    <button type="button" className="button button-secondary" disabled={providers.length >= MAX_CUSTOM_PROVIDERS} onClick={() => {
      let number = 1
      while (providers.some(provider => provider.id === `custom-${number}`)) number++
      onChange([...providers, { id: `custom-${number}`, name: '', api: 'openai-completions', baseUrl: '', models: [{ id: '', name: '', contextWindow: 131072, maxTokens: 8192 }] }])
    }}><Plus size={18} />添加自定义供应商</button>
  </div>
}
