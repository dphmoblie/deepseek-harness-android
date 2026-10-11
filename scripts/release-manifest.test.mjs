import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

// 版本清单生成器的夹具测试：用真实的临时文件跑脚本本身，断言它算出的摘要与字节数、
// JSON 结构与失败行为，而不是只匹配源码文本。
const appRoot = resolve(import.meta.dirname, '..')
const generator = resolve(appRoot, 'scripts/release-manifest.py')
const python = process.platform === 'win32' ? 'python' : 'python3'

const APK = 'apk-bytes'
const BUNDLE = 'bundle-bytes'
const MANIFEST = '{"rootfs":{"sha256":"x"}}'
const COMMIT = '1b64abc0d5c2f0d1d0a3b4c5d6e7f8091a2b3c4d'

const sha256 = content => createHash('sha256').update(content).digest('hex')

/** 建一个下载目录夹具；value 为 null 表示故意不写这个文件。 */
async function fixture(t, files = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-release-manifest-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const written = {
    'app-release.apk': APK,
    'rootfs.bundle': BUNDLE,
    'runtime-manifest.json': MANIFEST,
    ...files,
  }
  for (const [name, content] of Object.entries(written)) {
    if (content === null) continue
    await writeFile(join(dir, name), content)
  }
  return dir
}

function run(dir, extra = [], outName = 'release-manifest.json') {
  const out = join(dir, outName)
  const result = spawnSync(
    python,
    [
      generator,
      '--version', '0.2.13',
      '--tag', 'v0.2.13-mobile-42',
      '--release-type', 'preview',
      '--commit', COMMIT,
      '--apk', join(dir, 'app-release.apk'),
      '--bundle', join(dir, 'rootfs.bundle'),
      '--runtime-manifest', join(dir, 'runtime-manifest.json'),
      '--out', out,
      ...extra,
    ],
    {
      cwd: appRoot,
      encoding: 'utf8',
      timeout: 30_000,
      windowsHide: true,
      env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
    },
  )
  return { ...result, out }
}

test('records the version, tag, commit and every asset digest', async t => {
  const dir = await fixture(t)
  const result = run(dir)
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /RELEASE_MANIFEST_OK: assets=3/u)

  const manifest = JSON.parse(await readFile(result.out, 'utf8'))
  assert.equal(manifest.schemaVersion, 1)
  assert.equal(manifest.appVersion, '0.2.13')
  assert.equal(manifest.releaseTag, 'v0.2.13-mobile-42')
  assert.equal(manifest.releaseType, 'preview')
  assert.equal(manifest.commit, COMMIT)
  assert.deepEqual(
    manifest.assets.map(asset => asset.name),
    ['app-release.apk', 'rootfs.bundle', 'runtime-manifest.json'],
  )
  assert.deepEqual(manifest.assets[0], { name: 'app-release.apk', sha256: sha256(APK), bytes: APK.length })
  assert.deepEqual(manifest.assets[1], { name: 'rootfs.bundle', sha256: sha256(BUNDLE), bytes: BUNDLE.length })
  assert.deepEqual(manifest.assets[2], { name: 'runtime-manifest.json', sha256: sha256(MANIFEST), bytes: MANIFEST.length })
  // 文件必须以换行结尾，避免下游工具把「无换行」当成截断。
  assert.ok((await readFile(result.out, 'utf8')).endsWith('\n'))
})

test('fails when an asset that should be recorded is missing', async t => {
  const dir = await fixture(t, { 'rootfs.bundle': null })
  const result = run(dir)
  assert.equal(result.status, 1)
  assert.match(result.stderr, /RELEASE_MANIFEST_FAILED: 缺少要登记的资产：/u)
  await assert.rejects(readFile(result.out, 'utf8'), '失败时不该留下半成品清单')
})

test('fails when the commit is not a hex revision', async t => {
  const dir = await fixture(t)
  const result = run(dir, ['--commit', 'HEAD'])
  assert.equal(result.status, 1)
  assert.match(result.stderr, /RELEASE_MANIFEST_FAILED: 提交号不合法/u)
})

test('fails when the version, tag or release type is blank', async t => {
  const dir = await fixture(t)
  for (const extra of [['--version', ' '], ['--tag', ''], ['--release-type', '']]) {
    const result = run(dir, extra)
    assert.equal(result.status, 1, `${extra.join('=')} 必须被拒绝`)
    assert.match(result.stderr, /RELEASE_MANIFEST_FAILED: .*不能为空/u)
  }
})

test('is reproducible and lowercases the commit', async t => {
  const dir = await fixture(t)
  const first = run(dir, ['--commit', COMMIT.toUpperCase()], 'first.json')
  const second = run(dir, ['--commit', COMMIT.toUpperCase()], 'second.json')
  assert.equal(first.status, 0, first.stderr)
  assert.equal(second.status, 0, second.stderr)

  const firstBytes = await readFile(first.out, 'utf8')
  const secondBytes = await readFile(second.out, 'utf8')
  assert.equal(firstBytes, secondBytes, '同一批输入必须产出逐字节一致的清单')
  assert.equal(JSON.parse(firstBytes).commit, COMMIT)
})

test('creates the output directory when it does not exist yet', async t => {
  const dir = await fixture(t)
  const out = join(dir, 'nested', 'release-manifest.json')
  const result = spawnSync(
    python,
    [
      generator,
      '--version', '0.2.13',
      '--tag', 'v0.2.13-mobile-42',
      '--release-type', 'stable',
      '--commit', COMMIT,
      '--apk', join(dir, 'app-release.apk'),
      '--bundle', join(dir, 'rootfs.bundle'),
      '--runtime-manifest', join(dir, 'runtime-manifest.json'),
      '--out', out,
    ],
    {
      cwd: appRoot,
      encoding: 'utf8',
      timeout: 30_000,
      windowsHide: true,
      env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
    },
  )
  assert.equal(result.status, 0, result.stderr)
  assert.equal(JSON.parse(await readFile(out, 'utf8')).releaseType, 'stable')
})
