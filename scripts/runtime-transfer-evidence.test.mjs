import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const installer = await readFile(new URL('../android/app/src/main/java/io/deepseekharness/mobile/runtime/RuntimeInstaller.kt', import.meta.url), 'utf8')

// Android Context is unavailable here; verify the cleanup boundary, not a simulated disk reading.
test('installation captures free space before workspace cleanup and logs that snapshot', () => {
  const install = installer.slice(installer.indexOf('fun install('), installer.indexOf('fun cancelInstall('))
  const sample = install.indexOf('val failureAvailableBytes = availableBytes(store.runtimeParent)')
  assert.ok(sample > install.indexOf('catch (error: Throwable)'))
  assert.ok(sample < install.indexOf('cleanupWorkspace('))
  assert.match(install, /availableBytes = failureAvailableBytes/)
  assert.equal((install.match(/availableBytes\(store\.runtimeParent\)/g) ?? []).length, 1)
})

test('bundled copy leaves cleanup to the installer so disk-full evidence survives', () => {
  const start = installer.indexOf('private fun copyBundledRootfs(')
  const end = installer.indexOf('private fun readBounded(')
  assert.ok(start >= 0 && end > start)
  const copy = installer.slice(start, end)
  assert.ok(copy.includes('BUNDLED_RUNTIME_READ_FAILED'))
  assert.doesNotMatch(copy, /cleanupIfPresent\(destination\)/)
  assert.match(installer, /cleanupWorkspace\(it, preserveArchive = it\.preserveArchiveOnFailure\)/)
})
