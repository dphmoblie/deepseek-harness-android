import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

const root = new URL('../', import.meta.url)

test('Capacitor native runtime, bridge and CLI stay on the reviewed security patch together', async () => {
  const manifest = JSON.parse(await readFile(new URL('package.json', root), 'utf8'))
  const lock = await readFile(new URL('pnpm-lock.yaml', root), 'utf8')
  for (const [name, version] of Object.entries({
    ...manifest.dependencies,
    ...manifest.devDependencies,
  }).filter(([name]) => name.startsWith('@capacitor/'))) {
    assert.equal(version, '7.6.9', name)
    assert.ok(lock.includes(`${name}@${version}`), `${name} must also be locked`)
  }
})

test('embedded runtime locks the reviewed cache and ZIP security patches', async () => {
  const workspace = await readFile(new URL('scripts/runtime-profile/pnpm-workspace.yaml', root), 'utf8')
  const lock = await readFile(new URL('scripts/runtime-profile/pnpm-lock.yaml', root), 'utf8')
  for (const [name, version] of [['http-cache-semantics', '4.3.0'], ['fflate', '0.8.3']]) {
    assert.match(workspace, new RegExp(`^  ${name}: ${version.replaceAll('.', '\\.')}\\r?$`, 'm'))
    assert.ok(lock.includes(`${name}@${version}:`), `${name} must also be locked`)
  }
  assert.doesNotMatch(lock, /(?:http-cache-semantics@4\.2\.0|fflate@0\.8\.[012]):/)
})
