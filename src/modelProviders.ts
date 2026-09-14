import type { ModelProviderId, RuntimeSettings } from './platform/types'

export interface ModelProviderOption {
  id: ModelProviderId
  label: string
  environmentVariable: string
}

/** Mirrors the API-key providers verified in the runtime's pinned pi-ai catalog. */
export const MODEL_PROVIDERS: readonly ModelProviderOption[] = [
  { id: 'deepseek', label: 'DeepSeek', environmentVariable: 'DEEPSEEK_API_KEY' },
  { id: 'openai', label: 'OpenAI', environmentVariable: 'OPENAI_API_KEY' },
  { id: 'anthropic', label: 'Anthropic', environmentVariable: 'ANTHROPIC_API_KEY' },
  { id: 'google', label: 'Google Gemini', environmentVariable: 'GEMINI_API_KEY' },
  { id: 'openrouter', label: 'OpenRouter', environmentVariable: 'OPENROUTER_API_KEY' },
  { id: 'groq', label: 'Groq', environmentVariable: 'GROQ_API_KEY' },
  { id: 'xai', label: 'xAI', environmentVariable: 'XAI_API_KEY' },
  { id: 'mistral', label: 'Mistral', environmentVariable: 'MISTRAL_API_KEY' },
]

export function modelProviderLabel(providerId: ModelProviderId): string {
  return MODEL_PROVIDERS.find(provider => provider.id === providerId)?.label ?? providerId
}

/**
 * 本机是否已保存过至少一份模型凭据。
 *
 * 「打开 Harness」以此为前置条件：没有任何密钥时每一轮对话都会以缺少凭据失败，
 * 打开也没有意义。状态来自原生侧的加密存储，只表示「是否已配置」，不含密钥内容；
 * 读取设置尚未返回（settings 为 null）时不做判断，避免因一次读取失败把用户挡在门外。
 */
export function hasConfiguredModelCredential(settings: RuntimeSettings | null): boolean {
  if (settings === null) return false
  return settings.configuredModelProviders.length > 0
    || (settings.configuredCustomModelProviders?.length ?? 0) > 0
}
