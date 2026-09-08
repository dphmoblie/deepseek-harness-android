import { CUSTOM_PROVIDER_APIS, MODEL_PROVIDER_IDS } from './types'
import type { CustomModelProvider, CustomProviderApi } from './types'

export const MAX_CUSTOM_PROVIDERS = 16
export const MAX_CUSTOM_MODELS = 32
const RESERVED_IDS = new Set<string>([...MODEL_PROVIDER_IDS, 'constructor', 'prototype', '__proto__'])
const PROVIDER_ID = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,199}$/
const KEY = /^[\x21-\x7e]{1,200}$/
const hasUnsafeText = (value: string): boolean => [...value].some(character => {
  const code = character.charCodeAt(0)
  return code < 32 || code === 127 || character === '<' || character === '>'
})

function record(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效`)
  return value as Record<string, unknown>
}

function name(value: unknown, maximum: number, label: string): string {
  if (typeof value !== 'string' || hasUnsafeText(value)) throw new Error(`${label}包含非法字符`)
  const normalized = value.trim()
  if (normalized.length === 0 || normalized.length > maximum) throw new Error(`${label}长度必须为 1 到 ${maximum} 个字符`)
  return normalized
}

export function validateCustomProviderId(value: unknown): string {
  if (typeof value !== 'string' || value.length > 48 || !PROVIDER_ID.test(value) || RESERVED_IDS.has(value)) {
    throw new Error('自定义供应商标识须为 1 到 48 位小写字母、数字或连字符，且不能与内置供应商重复')
  }
  return value
}

function tokenCount(value: unknown, label: string): number {
  if (!Number.isInteger(value) || (value as number) < 1 || (value as number) > 10_000_000) {
    throw new Error(`${label}必须为 1 到 10000000 之间的整数`)
  }
  return value as number
}

export function validateCustomProviderUrl(value: unknown): string {
  // Security boundary: credentials belong in encrypted key storage, never URL components.
  if (typeof value !== 'string' || value.length > 2048 || [...value.trim()].some(character => {
    const code = character.charCodeAt(0)
    return code <= 32 || code === 127 || character === '\\'
  })) {
    throw new Error('自定义供应商 Base URL 包含非法字符或长度无效')
  }
  let url: URL
  try { url = new URL(value.trim()) } catch { throw new Error('自定义供应商 Base URL 格式无效') }
  const loopback = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]'
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) || url.port === '0' || url.username || url.password || url.search || url.hash) {
    throw new Error('Base URL 必须使用 HTTPS（本机回环可用 HTTP），且不能包含凭据、查询参数或片段')
  }
  return url.toString().replace(/\/$/, '')
}

export function validateCustomModelProviders(value: unknown): CustomModelProvider[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > MAX_CUSTOM_PROVIDERS) throw new Error('最多可配置 16 个自定义供应商')
  const ids = new Set<string>()
  return value.map(raw => {
    const provider = record(raw, '自定义供应商')
    const id = validateCustomProviderId(provider.id)
    if (ids.has(id)) throw new Error('自定义供应商标识不能重复')
    ids.add(id)
    if (!CUSTOM_PROVIDER_APIS.includes(provider.api as CustomProviderApi)) throw new Error('自定义供应商 API 协议不支持')
    if (!Array.isArray(provider.models) || provider.models.length === 0 || provider.models.length > MAX_CUSTOM_MODELS) {
      throw new Error('每个自定义供应商需要 1 到 32 个模型')
    }
    const models = new Set<string>()
    return {
      id,
      name: name(provider.name, 80, '自定义供应商名称'),
      api: provider.api as CustomProviderApi,
      baseUrl: validateCustomProviderUrl(provider.baseUrl),
      models: provider.models.map(rawModel => {
        const model = record(rawModel, '自定义模型')
        if (typeof model.id !== 'string' || !MODEL_ID.test(model.id)) throw new Error('模型 ID 格式无效，请填写供应商提供的模型标识')
        if (models.has(model.id)) throw new Error('同一供应商的模型 ID 不能重复')
        models.add(model.id)
        const contextWindow = tokenCount(model.contextWindow, '模型上下文长度')
        const maxTokens = tokenCount(model.maxTokens, '模型最大输出长度')
        if (maxTokens > contextWindow) throw new Error('模型最大输出长度不能超过上下文长度')
        return { id: model.id, name: name(model.name, 100, '模型名称'), contextWindow, maxTokens }
      }),
    }
  })
}

export function validateCustomCredentialIds(value: unknown, label: string, allowedIds?: Set<string>): string[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > MAX_CUSTOM_PROVIDERS) throw new Error(`${label}格式无效`)
  const result = value.map(validateCustomProviderId)
  if (new Set(result).size !== result.length) throw new Error(`${label}包含重复供应商`)
  if (allowedIds && result.some(id => !allowedIds.has(id))) throw new Error(`${label}包含不存在的自定义供应商`)
  return result
}

export function validateCustomCredentialUpdates(value: unknown, allowedIds?: Set<string>): Record<string, string> {
  if (value === undefined) return {}
  const updates = record(value, '自定义模型凭据更新')
  if (Object.keys(updates).length > MAX_CUSTOM_PROVIDERS) throw new Error('自定义模型凭据更新数量无效')
  return Object.fromEntries(Object.entries(updates).map(([rawId, rawKey]) => {
    const id = validateCustomProviderId(rawId)
    if (allowedIds && !allowedIds.has(id)) throw new Error('凭据更新包含不存在的自定义供应商')
    if (typeof rawKey !== 'string' || !KEY.test(rawKey.trim())) throw new Error('自定义模型凭据包含非法字符或长度无效')
    return [id, rawKey.trim()]
  }))
}
