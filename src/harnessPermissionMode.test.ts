import { describe, expect, it } from 'vitest'
import { validateSettings, validateSettingsUpdate, validateStoredSettings } from './platform/validation'
import type { RuntimeSettings } from './platform/types'
import { validateSelfCheckReport } from './runtimeSelfCheck'

const settings: RuntimeSettings = { manifestUrl: '', manifestSha256: '', keepScreenAwake: false, terminalFontSize: 14, configuredModelProviders: [] }

describe('Harness permission mode input boundaries', () => {
  it.each(['workspace-write', 'danger-full-access'] as const)('preserves %s in settings and self-check reports', mode => {
    for (const validate of [validateSettings, validateSettingsUpdate, validateStoredSettings]) {
      expect(validate({ ...settings, harnessPermissionMode: mode }).harnessPermissionMode).toBe(mode)
    }
    expect(validateSelfCheckReport({ operation: 'check', availableBytes: 0, dshVersion: null, checks: [], harnessPermissionMode: mode })).toHaveProperty('harnessPermissionMode', mode)
  })

  it('preserves omitted updates for older callers', () => {
    expect(validateSettingsUpdate(settings)).not.toHaveProperty('harnessPermissionMode')
  })

  it.each([null, true, 1, {}, '', 'read-only', ' danger-full-access', 'danger-full-access\n', 'x'.repeat(4096)])('rejects invalid modes at every bridge boundary (%j)', value => {
    const invalid = { ...settings, harnessPermissionMode: value } as RuntimeSettings
    for (const validate of [validateSettings, validateSettingsUpdate, validateStoredSettings]) {
      expect(() => validate(invalid)).toThrow('Harness 启动权限格式无效')
    }
    expect(() => validateSelfCheckReport({ operation: 'check', availableBytes: 0, dshVersion: null, checks: [], harnessPermissionMode: value })).toThrow('Harness 启动权限格式无效')
  })
})
