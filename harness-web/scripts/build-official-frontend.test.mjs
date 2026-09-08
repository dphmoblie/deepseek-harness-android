import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { readFile, readdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = resolve(projectRoot, 'node_modules/@deepseek-ai/dsh-web-frontend/dist')
const outputRoot = resolve(projectRoot, 'dist')

test('built Android frontend preserves every official resource and has one entrypoint', async () => {
  await promisify(execFile)(process.execPath, ['scripts/build-official-frontend.mjs'], { cwd: projectRoot })
  const officialFiles = await readdir(sourceRoot, { recursive: true, withFileTypes: true })
  for (const entry of officialFiles.filter(entry => entry.isFile())) {
    const sourcePath = resolve(entry.parentPath, entry.name)
    const targetPath = sourcePath.replace(sourceRoot, outputRoot)
    if (entry.name === 'index.html') continue
    assert.deepEqual(await readFile(targetPath), await readFile(sourcePath), sourcePath)
  }
  const index = await readFile(resolve(outputRoot, 'index.html'), 'utf8')
  const original = await readFile(resolve(sourceRoot, 'index.html'), 'utf8')
  const scripts = html => [...html.matchAll(/<script\b[^>]*src="([^"]+)"/gu)].map(match => match[1])
  assert.deepEqual(scripts(index), scripts(original), 'must use the original app entrypoint')
  assert.match(index, /name="dsh-official-frontend" content="android-adapted-v1"/)
  assert.match(index, /viewport-fit=cover/)
  assert.equal(index.match(/href="\/dsh-android.css"/gu)?.length, 1)
  assert.doesNotMatch(index, /dsh-mobile-frontend|plugin-workbench|\/src\//u)
  const outputFiles = await readdir(outputRoot, { recursive: true, withFileTypes: true })
  assert.equal(outputFiles.filter(entry => entry.isFile()).length,
    officialFiles.filter(entry => entry.isFile()).length + 1, 'only the adaptation CSS is added')
  assert.deepEqual(await readFile(resolve(outputRoot, 'dsh-android.css')),
    await readFile(resolve(projectRoot, 'android.css')))
})
