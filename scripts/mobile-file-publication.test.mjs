import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

test('publication path validation and Linux atomic publication', () => {
  const result = spawnSync(process.platform === 'win32' ? 'python' : 'python3', [
    fileURLToPath(new URL('./mobile-file-publication.selfcheck.py', import.meta.url)),
  ], { encoding: 'utf8', timeout: 60_000, windowsHide: true })
  assert.equal(result.error, undefined)
  assert.equal(result.status, 0, result.stdout + result.stderr)
})
