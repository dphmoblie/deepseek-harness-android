import { describe, expect, it } from 'vitest'
import { validateCustomModelProviders, validateCustomCredentialUpdates } from './customProviders'
import { validateSettingsUpdate } from './validation'

const provider = {
  id: 'gateway', name: 'Gateway', api: 'openai-completions', baseUrl: 'https://api.example.com/v1/',
  models: [{ id: 'org/model', name: 'Model', contextWindow: 32768, maxTokens: 4096 }],
}

describe('custom providers', () => {
  it('normalizes a complete provider declaration', () => {
    expect(validateCustomModelProviders([provider])).toEqual([{ ...provider, baseUrl: 'https://api.example.com/v1' }])
  })
  it.each(['openai', '__proto__', 'UPPER', 'gateway\n'])('rejects reserved or malformed route %s', id => {
    expect(() => validateCustomModelProviders([{ ...provider, id }])).toThrow()
  })
  it.each(['http://api.example.com', 'https://api.example.com:0', 'https://user:secret@example.com', 'https://example.com/?token=value', 'https://example.com/#secret'])('rejects credential-bearing or insecure URL %s', baseUrl => {
    expect(() => validateCustomModelProviders([{ ...provider, baseUrl }])).toThrow()
  })
  it('rejects duplicate routes, oversized model limits and unknown credential targets', () => {
    expect(() => validateCustomModelProviders([provider, provider])).toThrow()
    expect(() => validateCustomModelProviders([{ ...provider, models: [{ ...provider.models[0], maxTokens: 999999 }] }])).toThrow()
    expect(() => validateCustomCredentialUpdates({ unknown: 'placeholder' }, new Set(['gateway']))).toThrow()
  })
  it('rejects conflicting credential writes', () => {
    const base = { manifestUrl: '', manifestSha256: '', keepScreenAwake: false, terminalFontSize: 14, configuredModelProviders: [] }
    expect(() => validateSettingsUpdate({ ...base, customModelProviders: validateCustomModelProviders([provider]), customProviderApiKeys: { gateway: 'placeholder' }, clearCustomProviderApiKeys: ['gateway'] })).toThrow('同时更新和清除')
  })
})
