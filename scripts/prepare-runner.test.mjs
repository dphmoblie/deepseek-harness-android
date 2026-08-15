import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { isPublicAddress, parseRunnerConfig } from './prepare-runner.mjs'

const validEnvironment = {
  DSH_PROOT_ARM64_URL: 'https://downloads.example.invalid/proot-arm64',
  DSH_PROOT_ARM64_SHA256: 'a'.repeat(64),
  DSH_PROOT_ARM64_BYTES: '1048576',
  DSH_RUNNER_ALLOWED_HOSTS: 'downloads.example.invalid',
}

describe('runner import config', () => {
  it('accepts a pinned HTTPS artifact from an allowlisted host', () => {
    const config = parseRunnerConfig(validEnvironment)
    assert.equal(config.sourceUrl.hostname, 'downloads.example.invalid')
    assert.equal(config.expectedBytes, 1048576)
  })

  it('rejects URL credentials', () => {
    assert.throws(() => parseRunnerConfig({
      ...validEnvironment,
      DSH_PROOT_ARM64_URL: 'https://user@downloads.example.invalid/proot-arm64',
    }), /must use HTTPS/)
  })

  it('rejects hosts that were not explicitly allowlisted', () => {
    assert.throws(() => parseRunnerConfig({
      ...validEnvironment,
      DSH_RUNNER_ALLOWED_HOSTS: 'mirror.example.invalid',
    }), /explicitly include/)
  })

  it('rejects artifacts above the fixed maximum', () => {
    assert.throws(() => parseRunnerConfig({
      ...validEnvironment,
      DSH_PROOT_ARM64_BYTES: String(17 * 1024 * 1024),
    }), /must be an integer/)
  })

  it('rejects local address classes before runner download', () => {
    const ipv4 = (...octets) => octets.join('.')
    assert.equal(isPublicAddress(ipv4(10, 0, 0, 1)), false)
    assert.equal(isPublicAddress(ipv4(192, 168, 1, 1)), false)
    assert.equal(isPublicAddress('::1'), false)
    assert.equal(isPublicAddress('2606:4700:4700::1111'), true)
    assert.throws(() => parseRunnerConfig({
      ...validEnvironment,
      DSH_PROOT_ARM64_URL: `https://${ipv4(127, 0, 0, 1)}/proot-arm64`,
      DSH_RUNNER_ALLOWED_HOSTS: ipv4(127, 0, 0, 1),
    }), /local or private/)
  })
})
