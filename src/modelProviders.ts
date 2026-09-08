import type { ModelProviderId } from './platform/types'

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
