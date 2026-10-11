import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

// 发布后自检脚本的夹具测试：用真实文件与真实摘要跑真实进程，
// 覆盖「上传截断、漏传、多传、清单写坏」这四类发布事故。
const appRoot = resolve(import.meta.dirname, '..')
const verifier = resolve(appRoot, 'scripts/verify-release-assets.py')
const python = process.platform === 'win32' ? 'python' : 'python3'

const sha256 = content => createHash('sha256').update(content).digest('hex')

// 一份「正常发布」的字节：三个资产 + 清单。
const APK = 'apk-bytes'
const BUNDLE = 'bundle-bytes'
const MANIFEST = '{"rootfs":{"sha256":"x"}}'
const EXPECT = 'app-release.apk,rootfs.bundle,runtime-manifest.json'

function checksumLines(pairs) {
  return pairs.map(([name, content]) => `${sha256(content)}  ${name}`).join('\n') + '\n'
}

async function fixture(t, files) {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-release-verify-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  for (const [name, content] of Object.entries(files)) {
    await writeFile(join(dir, name), content)
  }
  return dir
}

function run(dir, extra = []) {
  return spawnSync(python, [verifier, '--dir', dir, ...extra], {
    cwd: appRoot,
    encoding: 'utf8',
    timeout: 30_000,
    windowsHide: true,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  })
}

const happy = {
  'app-release.apk': APK,
  'rootfs.bundle': BUNDLE,
  'runtime-manifest.json': MANIFEST,
  'checksums.txt': checksumLines([
    ['app-release.apk', APK],
    ['rootfs.bundle', BUNDLE],
    ['runtime-manifest.json', MANIFEST],
  ]),
}

test('accepts a release whose assets match checksums.txt', async t => {
  const dir = await fixture(t, happy)
  const result = run(dir, ['--expect', EXPECT])
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /RELEASE_ASSETS_OK: files=3 bytes=\d+ checksums=checksums\.txt/u)
})

test('rejects a truncated asset whose digest no longer matches the manifest', async t => {
  const dir = await fixture(t, { ...happy, 'app-release.apk': 'apk-byt' })
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /app-release\.apk 摘要不一致/u)
  assert.match(result.stderr, /RELEASE_VERIFY_FAILED/u)
})

test('rejects a registered asset that was never downloaded', async t => {
  const files = { ...happy }
  delete files['runtime-manifest.json']
  const dir = await fixture(t, files)
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /缺少被登记的文件：runtime-manifest\.json/u)
})

test('rejects an unregistered extra file next to the release assets', async t => {
  const dir = await fixture(t, { ...happy, 'rootfs.bundle.bak': BUNDLE })
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /出现了未登记的文件：rootfs\.bundle\.bak/u)
})

test('rejects a checksums.txt line that is not in coreutils format', async t => {
  const dir = await fixture(t, {
    ...happy,
    'checksums.txt': `${sha256(APK)} app-release.apk\n`,
  })
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /checksums\.txt 第 1 行格式不正确/u)
})

test('accepts the binary-mode checksum form and rejects duplicate entries', async t => {
  const binary = await fixture(t, {
    ...happy,
    'checksums.txt': checksumLines([
      ['app-release.apk', APK],
      ['rootfs.bundle', BUNDLE],
      ['runtime-manifest.json', MANIFEST],
    ]).replace('  app-release.apk', ' *app-release.apk'),
  })
  const accepted = run(binary, ['--expect', EXPECT])
  assert.equal(accepted.status, 0, accepted.stderr)

  const duplicated = await fixture(t, {
    ...happy,
    'checksums.txt':
      checksumLines([
        ['app-release.apk', APK],
        ['rootfs.bundle', BUNDLE],
        ['runtime-manifest.json', MANIFEST],
      ]) + `${sha256(APK)}  app-release.apk\n`,
  })
  const rejected = run(duplicated, ['--expect', EXPECT])
  assert.notEqual(rejected.status, 0)
  assert.match(rejected.stderr, /重复登记了 'app-release\.apk'/u)
})

test('rejects a release whose manifest omits a required asset', async t => {
  const dir = await fixture(t, {
    'app-release.apk': APK,
    'rootfs.bundle': BUNDLE,
    'checksums.txt': checksumLines([
      ['app-release.apk', APK],
      ['rootfs.bundle', BUNDLE],
    ]),
  })
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /清单缺少必需资产：runtime-manifest\.json/u)
})

test('rejects empty assets and path-escaping asset names', async t => {
  const empty = await fixture(t, {
    ...happy,
    'runtime-manifest.json': '',
    'checksums.txt': checksumLines([
      ['app-release.apk', APK],
      ['rootfs.bundle', BUNDLE],
      ['runtime-manifest.json', ''],
    ]),
  })
  const emptyResult = run(empty, ['--expect', EXPECT])
  assert.notEqual(emptyResult.status, 0)
  assert.match(emptyResult.stderr, /被登记的文件是空文件：runtime-manifest\.json/u)

  const escaping = await fixture(t, {
    ...happy,
    'checksums.txt': checksumLines([
      ['app-release.apk', APK],
      ['rootfs.bundle', BUNDLE],
      ['runtime-manifest.json', MANIFEST],
      ['../escape.apk', APK],
    ]),
  })
  const escapeResult = run(escaping, ['--expect', EXPECT])
  assert.notEqual(escapeResult.status, 0)
  assert.match(escapeResult.stderr, /文件名非法/u)
})

test('rejects a download directory without checksums.txt', async t => {
  const files = { ...happy }
  delete files['checksums.txt']
  const dir = await fixture(t, files)
  const result = run(dir, ['--expect', EXPECT])
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /缺少 checksums\.txt/u)
})
