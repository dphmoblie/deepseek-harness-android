import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { defaultTargets, readJson, validateManifest } from './validate-manifests.mjs'

// 清单 schema 校验的夹具测试：全部真跑 scripts/validate-manifests.mjs 的校验函数与 CLI，
// 并且真跑 scripts/release-manifest.py 生成发布清单样本，而不是断言源码里的字符串。
// 除「校验通过」外，每个 schema 都配了坏样本用例：只要校验器退化成一律返回空数组，
// 这些用例就会立刻失败——这样「解析失效让用例恒真」不可能悄悄发生。
const appRoot = resolve(import.meta.dirname, '..')
const validator = resolve(appRoot, 'scripts/validate-manifests.mjs')
const python = process.platform === 'win32' ? 'python' : 'python3'

const schemaPath = name => resolve(appRoot, 'scripts/schemas', name)
const runtimeManifestSchema = () => readJson(schemaPath('runtime-manifest.schema.json'))
const releaseManifestSchema = () => readJson(schemaPath('release-manifest.schema.json'))
const pluginPackageSchema = () => readJson(schemaPath('plugin-package.schema.json'))
const mobileProfileSchema = () => readJson(schemaPath('mobile-profile.schema.json'))

const realRuntimeManifest = () =>
  readJson(resolve(appRoot, 'scripts/fixtures/runtime-manifest.v0.2.3-mobile-323.json'))

/** 断言错误列表里存在一条匹配 pattern 的中文报错。 */
function assertReports(errors, pattern, hint) {
  assert.ok(errors.length > 0, `期望校验失败，实际全部通过（${hint}）`)
  for (const error of errors) {
    assert.match(error, /[\u4e00-\u9fa5]/, `报错必须是中文：${error}`)
  }
  assert.ok(
    errors.some(error => pattern.test(error)),
    `期望出现匹配 ${pattern} 的报错，实际是：\n${errors.join('\n')}`,
  )
}

function runCli(args) {
  return spawnSync(process.execPath, [validator, ...args], {
    cwd: appRoot,
    encoding: 'utf8',
    timeout: 60_000,
    windowsHide: true,
  })
}

test('校验器自检：schema 里出现不受支持的关键字必须报错，而不是静默忽略', () => {
  // 这条是「假门禁」防线：如果校验器把不认识的关键字当没看见，
  // 那么 schema 里写 allOf/oneOf 之类的约束就会变成永远通过。
  const errors = validateManifest({ a: 1 }, { type: 'object', allOf: [{ required: ['b'] }] })
  assertReports(errors, /^\[schema\] \$：不支持的关键字 "allOf"/, 'schema 用了校验器不支持的关键字')
})

test('校验器自检：同一份 schema 对合法样本通过、对被改坏的样本报错，用例不会恒真', () => {
  const schema = runtimeManifestSchema()
  const sample = realRuntimeManifest()
  assert.deepEqual(validateManifest(sample, schema), [], '真实产物样本应当通过')

  const broken = structuredClone(sample)
  broken.rootfs.compressedBytes = '311 MB'
  const errors = validateManifest(broken, schema)
  assert.ok(errors.length > 0, '改坏压缩字节数后必须报错，否则校验器是恒真的')
})

test('校验器自检：解析失败要给出中文原因，不能把坏文件当空对象继续校验', () => {
  assert.throws(
    () => readJson(resolve(appRoot, 'docs/项目状态.md')),
    error => /不是合法 JSON/.test(error.message) && /[\u4e00-\u9fa5]/.test(error.message),
  )
  assert.throws(
    () => readJson(resolve(appRoot, 'scripts/fixtures/runtime-manifest.不存在.json')),
    error => /读不到文件/.test(error.message),
  )
})

test('更新清单：docs 里的手写示例通过 schema 校验', () => {
  const manifest = readJson(resolve(appRoot, 'docs/runtime-manifest.example.json'))
  assert.deepEqual(validateManifest(manifest, runtimeManifestSchema()), [])
})

test('更新清单：真实构建产物样本（v0.2.3-mobile-323）通过 schema 校验', () => {
  const manifest = realRuntimeManifest()
  assert.equal(manifest.mobile.dsh.profile.bundles.length, 3)
  assert.ok(manifest.profileLinks > 0)
  assert.deepEqual(validateManifest(manifest, runtimeManifestSchema()), [])
})

test('更新清单坏样本：缺必需字段要逐条点名', () => {
  const schema = runtimeManifestSchema()
  const missingProfileLinks = realRuntimeManifest()
  delete missingProfileLinks.profileLinks
  assertReports(
    validateManifest(missingProfileLinks, schema),
    /\$：缺少必需字段 "profileLinks"/,
    '删掉了 profileLinks',
  )

  const missingRootfsField = realRuntimeManifest()
  delete missingRootfsField.rootfs.extractedBytes
  assertReports(
    validateManifest(missingRootfsField, schema),
    /\$\.rootfs：缺少必需字段 "extractedBytes"/,
    '删掉了 rootfs.extractedBytes',
  )

  const missingEntrypoint = realRuntimeManifest()
  delete missingEntrypoint.entrypoints.harness
  assertReports(
    validateManifest(missingEntrypoint, schema),
    /\$\.entrypoints：缺少必需字段 "harness"/,
    '删掉了 entrypoints.harness',
  )
})

test('更新清单坏样本：类型错要报出期望类型与实际类型', () => {
  const schema = runtimeManifestSchema()
  const cases = [
    ['rootfs.compressedBytes', '311 MB', /\$\.rootfs\.compressedBytes：类型应为 integer，实际 string/],
    ['rootfs', 'not-an-object', /\$\.rootfs：类型应为 object，实际 string/],
    ['entrypoints.shell', '/bin/bash --login', /\$\.entrypoints\.shell：类型应为 array，实际 string/],
    ['harnessUrl', 3080, /\$\.harnessUrl：类型应为 string，实际 integer/],
    ['profileLinks', 515.5, /\$\.profileLinks：类型应为 integer，实际 number/],
    ['schemaVersion', '1', /\$\.schemaVersion：取值必须严格等于 1，实际 "1"/],
  ]
  for (const [field, value, pattern] of cases) {
    const broken = realRuntimeManifest()
    const segments = field.split('.')
    let cursor = broken
    for (const segment of segments.slice(0, -1)) cursor = cursor[segment]
    cursor[segments.at(-1)] = value
    assertReports(validateManifest(broken, schema), pattern, `${field} 类型写错`)
  }
})

test('更新清单坏样本：多出未声明字段要报字段名并列出允许的字段', () => {
  const schema = runtimeManifestSchema()
  const extraTop = realRuntimeManifest()
  extraTop.notes = '顺手加个备注'
  assertReports(
    validateManifest(extraTop, schema),
    /\$：出现未声明的字段 "notes"（schema 只允许：/,
    '顶层多了 notes',
  )

  const extraRootfs = realRuntimeManifest()
  extraRootfs.rootfs.mirror = 'https://example.invalid/rootfs.bundle'
  assertReports(
    validateManifest(extraRootfs, schema),
    /\$\.rootfs：出现未声明的字段 "mirror"/,
    'rootfs 多了 mirror',
  )

  const extraMobile = realRuntimeManifest()
  extraMobile.mobile.mobile.persist = true
  assertReports(
    validateManifest(extraMobile, schema),
    /\$\.mobile\.mobile：出现未声明的字段 "persist"/,
    'mobile.mobile 多了 persist',
  )
})

test('更新清单坏样本：enum 与 const 取值非法要点出合法取值', () => {
  const schema = runtimeManifestSchema()

  const wrongArch = realRuntimeManifest()
  wrongArch.architecture = 'arm64-v7a'
  assertReports(
    validateManifest(wrongArch, schema),
    /\$\.architecture：取值必须属于 "arm64-v8a"，实际 "arm64-v7a"/,
    'ABI 写成了 32 位',
  )

  const wrongCompression = realRuntimeManifest()
  wrongCompression.rootfs.compression = 'zstd'
  assertReports(
    validateManifest(wrongCompression, schema),
    /\$\.rootfs\.compression：取值必须属于 "gzip"，实际 "zstd"/,
    '压缩算法写成了 zstd',
  )

  const wrongSchemaVersion = realRuntimeManifest()
  wrongSchemaVersion.schemaVersion = 2
  assertReports(
    validateManifest(wrongSchemaVersion, schema),
    /\$\.schemaVersion：取值必须严格等于 1，实际 2/,
    '清单格式版本被改成了 2',
  )

  const wrongRuntimeId = realRuntimeManifest()
  wrongRuntimeId.runtimeId = 'ubuntu-22.04-arm64-deepseek-harness'
  assertReports(
    validateManifest(wrongRuntimeId, schema),
    /\$\.runtimeId：取值必须严格等于 "ubuntu-24\.04-arm64-deepseek-harness"/,
    '运行时标识写错',
  )
})

test('更新清单坏样本：正则不匹配要原样回显实际取值', () => {
  const schema = runtimeManifestSchema()

  const publicUrl = realRuntimeManifest()
  publicUrl.harnessUrl = 'http://0.0.0.0:3080/'
  assertReports(
    validateManifest(publicUrl, schema),
    /\$\.harnessUrl：字符串不匹配正则 \^http:\/\/127\\\.0\\\.0\\\.1/,
    'harnessUrl 监听地址改成了 0.0.0.0',
  )

  const badDigest = realRuntimeManifest()
  badDigest.rootfs.sha256 = '1B87A4E0BF'
  assertReports(
    validateManifest(badDigest, schema),
    /\$\.rootfs\.sha256：字符串不匹配正则 \^\[0-9a-f\]\{64\}\$/,
    '摘要写成了大写短串',
  )

  const insecureUrl = realRuntimeManifest()
  insecureUrl.rootfs.url = 'http://downloads.example.invalid/rootfs.bundle'
  assertReports(
    validateManifest(insecureUrl, schema),
    /\$\.rootfs\.url：字符串不匹配正则 \^https:\/\//,
    'bundle 下载地址降级成了 http',
  )
})

test('更新清单坏样本：数组与数值边界要报出实际长度/数值', () => {
  const schema = runtimeManifestSchema()

  const emptyShell = realRuntimeManifest()
  emptyShell.entrypoints.shell = []
  assertReports(
    validateManifest(emptyShell, schema),
    /\$\.entrypoints\.shell：数组长度 0 小于最小长度 1/,
    'shell 入口成了空数组',
  )

  const duplicatedBundles = realRuntimeManifest()
  duplicatedBundles.mobile.dsh.profile.bundles = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-base']
  assertReports(
    validateManifest(duplicatedBundles, schema),
    /\$\.mobile\.dsh\.profile\.bundles：数组存在重复元素/,
    'bundle 列表出现重复项',
  )

  const negativeLinks = realRuntimeManifest()
  negativeLinks.profileLinks = -1
  assertReports(
    validateManifest(negativeLinks, schema),
    /\$\.profileLinks：数值 -1 小于最小值 0/,
    '链接数成了负数',
  )

  const noBundles = realRuntimeManifest()
  noBundles.mobile.dsh.profile.bundles = []
  assertReports(
    validateManifest(noBundles, schema),
    /\$\.mobile\.dsh\.profile\.bundles：数组长度 0 小于最小长度 1/,
    'mobile profile 里一个 bundle 都没有',
  )
})

test('移动端 profile 清单：仓库里的示例通过校验', () => {
  const profile = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  assert.deepEqual(validateManifest(profile, mobileProfileSchema()), [])
})

test('移动端 profile 清单坏样本：缺段、空列表、越界与未声明字段都要报错', () => {
  const schema = mobileProfileSchema()

  const noDsh = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  delete noDsh.dsh
  assertReports(validateManifest(noDsh, schema), /\$：缺少必需字段 "dsh"/, '删掉了 dsh 段')

  const emptyBundles = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  emptyBundles.dsh.profile.bundles = []
  assertReports(
    validateManifest(emptyBundles, schema),
    /\$\.dsh\.profile\.bundles：数组长度 0 小于最小长度 1/,
    'bundles 空列表',
  )

  const outOfRange = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  outOfRange.mobile.idleStopMinutes = 0
  assertReports(
    validateManifest(outOfRange, schema),
    /\$\.mobile\.idleStopMinutes：数值 0 小于最小值 1/,
    'idleStopMinutes 越界',
  )

  const extraField = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  extraField.mobile.persistAcrossReboot = true
  assertReports(
    validateManifest(extraField, schema),
    /\$\.mobile：出现未声明的字段 "persistAcrossReboot"/,
    'mobile 段多了未声明字段',
  )

  const badBundleName = readJson(resolve(appRoot, 'scripts/mobile-profile.example.json'))
  badBundleName.dsh.profile.bundles = ['@DeepSeek-AI/DSH-Base']
  assertReports(
    validateManifest(badBundleName, schema),
    /\$\.dsh\.profile\.bundles\[0\]：字符串不匹配正则/,
    'bundle 标识符写成了大写',
  )
})

test('插件清单：仓库里的真实插件清单通过校验（受管清单由目录动态发现）', () => {
  const targets = defaultTargets()
  const pluginTargets = targets.filter(target => target.schema.endsWith('plugin-package.schema.json'))
  assert.equal(pluginTargets.length, 1, '当前仓库应恰好有一个插件清单')
  assert.equal(
    pluginTargets[0].file,
    'scripts/runtime-profile/plugins/dsh-mobile-shizuku/package.json',
  )
  const manifest = readJson(resolve(appRoot, pluginTargets[0].file))
  assert.deepEqual(validateManifest(manifest, pluginPackageSchema()), [])
})

test('插件清单坏样本：运行时真正读的三处（name/version/dsh.bundle.patch）都要挡住', () => {
  const schema = pluginPackageSchema()
  const real = () =>
    readJson(resolve(appRoot, 'scripts/runtime-profile/plugins/dsh-mobile-shizuku/package.json'))

  const bareName = real()
  bareName.name = 'dsh-mobile-shizuku'
  assertReports(validateManifest(bareName, schema), /\$\.name：字符串不匹配正则/, '包名写成了裸名')

  const noPatch = real()
  delete noPatch.dsh.bundle.patch
  assertReports(
    validateManifest(noPatch, schema),
    /\$\.dsh\.bundle：缺少必需字段 "patch"/,
    '删掉了 dsh.bundle.patch',
  )

  const wrongPatchSuffix = real()
  wrongPatchSuffix.dsh.bundle.patch = './cordis.patch.txt'
  assertReports(
    validateManifest(wrongPatchSuffix, schema),
    /\$\.dsh\.bundle\.patch：字符串不匹配正则/,
    '补丁路径后缀不是 json/yaml',
  )

  const notPrivate = real()
  notPrivate.private = false
  assertReports(
    validateManifest(notPrivate, schema),
    /\$\.private：取值必须严格等于 true，实际 false/,
    'private 被改成了 false',
  )

  const extraDshField = real()
  extraDshField.dsh.permissions = []
  assertReports(
    validateManifest(extraDshField, schema),
    /\$\.dsh：出现未声明的字段 "permissions"/,
    'dsh 段多了运行时读不到的字段',
  )

  const missingMain = real()
  delete missingMain.main
  assertReports(validateManifest(missingMain, schema), /\$：缺少必需字段 "main"/, '删掉了 main')
})

test('受管清单表：插件目录为空时必须直接报错，不能让校验悄悄空转', () => {
  assert.throws(
    () => defaultTargets(join(tmpdir(), 'dsh-不存在的仓库根')),
    /没在 scripts\/runtime-profile\/plugins 下找到任何插件清单/,
  )
})

test('发布清单：真跑 release-manifest.py 产出的清单必须通过 schema 校验', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-validate-manifests-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  await writeFile(join(dir, 'app-release.apk'), 'apk-bytes')
  await writeFile(join(dir, 'rootfs.bundle'), 'bundle-bytes')
  await writeFile(join(dir, 'runtime-manifest.json'), JSON.stringify(realRuntimeManifest()))
  await writeFile(join(dir, 'corresponding-source.tar.gz'), 'source-bytes')
  const out = join(dir, 'release-manifest.json')

  const result = spawnSync(
    python,
    [
      resolve(appRoot, 'scripts/release-manifest.py'),
      '--version', '0.2.13',
      '--tag', 'v0.2.13-mobile-999',
      '--release-type', 'preview',
      '--commit', '1B64ABC0D5C2F0D1D0A3B4C5D6E7F8091A2B3C4D',
      '--apk', join(dir, 'app-release.apk'),
      '--bundle', join(dir, 'rootfs.bundle'),
      '--runtime-manifest', join(dir, 'runtime-manifest.json'),
      '--corresponding-source', join(dir, 'corresponding-source.tar.gz'),
      '--out', out,
    ],
    { cwd: appRoot, encoding: 'utf8', timeout: 30_000, windowsHide: true },
  )
  assert.equal(result.status, 0, `release-manifest.py 应当成功：${result.stderr}`)

  const manifest = JSON.parse(await readFile(out, 'utf8'))
  assert.deepEqual(Object.keys(manifest), [
    'schemaVersion',
    'appVersion',
    'releaseTag',
    'releaseType',
    'commit',
    'assets',
  ])
  assert.equal(manifest.commit, '1b64abc0d5c2f0d1d0a3b4c5d6e7f8091a2b3c4d', '提交号应被转成小写')
  assert.equal(manifest.assets.length, 4)
  assert.deepEqual(validateManifest(manifest, releaseManifestSchema()), [], '真实生成器的产物必须通过 schema')
})

test('发布清单坏样本：发布类型、提交号、资产数量与字段名都要报错', () => {
  const schema = releaseManifestSchema()
  const good = {
    schemaVersion: 1,
    appVersion: '0.2.13',
    releaseTag: 'v0.2.13-mobile-999',
    releaseType: 'preview',
    commit: '1b64abc0d5c2f0d1d0a3b4c5d6e7f8091a2b3c4d',
    assets: [
      { name: 'app-release.apk', sha256: 'a'.repeat(64), bytes: 1 },
      { name: 'rootfs.bundle', sha256: 'b'.repeat(64), bytes: 2 },
      { name: 'runtime-manifest.json', sha256: 'c'.repeat(64), bytes: 3 },
      { name: 'corresponding-source.tar.gz', sha256: 'd'.repeat(64), bytes: 4 },
    ],
  }
  assert.deepEqual(validateManifest(good, schema), [])

  const wrongType = structuredClone(good)
  wrongType.releaseType = 'nightly'
  assertReports(
    validateManifest(wrongType, schema),
    /\$\.releaseType：取值必须属于 "preview"、"stable"，实际 "nightly"/,
    '发布类型写成了 nightly',
  )

  const upperCommit = structuredClone(good)
  upperCommit.commit = '1B64ABC0D5C2'
  assertReports(
    validateManifest(upperCommit, schema),
    /\$\.commit：字符串不匹配正则/,
    '提交号是大写',
  )

  const twoAssets = structuredClone(good)
  twoAssets.assets.pop()
  assertReports(
    validateManifest(twoAssets, schema),
    /\$\.assets：数组长度 3 小于最小长度 4/,
    '资产少了对应源码归档',
  )

  const badAssetField = structuredClone(good)
  badAssetField.assets[0].url = 'https://example.invalid/app-release.apk'
  assertReports(
    validateManifest(badAssetField, schema),
    /\$\.assets\[0\]：出现未声明的字段 "url"/,
    '资产结构里多了 url',
  )

  const zeroBytes = structuredClone(good)
  zeroBytes.assets[2].bytes = 0
  assertReports(
    validateManifest(zeroBytes, schema),
    /\$\.assets\[2\]\.bytes：数值 0 小于最小值 1/,
    '资产字节数为 0',
  )

  const noTagPrefix = structuredClone(good)
  noTagPrefix.releaseTag = '0.2.13-mobile-999'
  assertReports(
    validateManifest(noTagPrefix, schema),
    /\$\.releaseTag：字符串不匹配正则/,
    '发布标签没以 v 开头',
  )
})

test('CLI：默认模式校验全部受管清单并以 0 退出', () => {
  const result = runCli([])
  assert.equal(result.status, 0, `默认模式应当通过：${result.stdout}${result.stderr}`)
  const targets = defaultTargets()
  assert.match(result.stdout, new RegExp(`清单 schema 校验：${targets.length} 个文件全部通过`))
  for (const target of targets) {
    assert.ok(result.stdout.includes(`✓ ${target.file}`), `缺少通过记录：${target.file}`)
  }
})

test('CLI：坏清单以 1 退出并打印中文原因，用法/schema 问题以 2 退出', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-validate-manifests-cli-'))
  t.after(() => rm(dir, { recursive: true, force: true }))

  const bad = structuredClone(realRuntimeManifest())
  delete bad.dshVersion
  const badPath = join(dir, 'runtime-manifest.json')
  await writeFile(badPath, JSON.stringify(bad, null, 2))

  const failed = runCli(['--file', badPath, '--schema', schemaPath('runtime-manifest.schema.json')])
  assert.equal(failed.status, 1)
  assert.match(failed.stderr, /✗ /)
  assert.match(failed.stderr, /缺少必需字段 "dshVersion"/)
  assert.match(failed.stderr, /清单 schema 校验：1 个文件，1 处问题/)

  const missingSchema = runCli(['--file', badPath, '--schema', join(dir, '不存在.schema.json')])
  assert.equal(missingSchema.status, 2)
  assert.match(missingSchema.stderr, /schema 不存在/)

  const missingManifest = runCli([
    '--file',
    join(dir, '不存在.json'),
    '--schema',
    schemaPath('runtime-manifest.schema.json'),
  ])
  assert.equal(missingManifest.status, 2)
  assert.match(missingManifest.stderr, /清单文件不存在/)

  const halfArgs = runCli(['--file', badPath])
  assert.equal(halfArgs.status, 2)
  assert.match(halfArgs.stderr, /--file 与 --schema 必须成对给出/)
  assert.match(halfArgs.stderr, /用法：/)

  const unknownFlag = runCli(['--files', badPath])
  assert.equal(unknownFlag.status, 2)
  assert.match(unknownFlag.stderr, /不认识的参数：--files/)
})

test('CLI：CI 用它校验构建产物，真实产物样本以 0 退出', () => {
  const result = runCli([
    '--file',
    resolve(appRoot, 'scripts/fixtures/runtime-manifest.v0.2.3-mobile-323.json'),
    '--schema',
    schemaPath('runtime-manifest.schema.json'),
  ])
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`)
  assert.match(result.stdout, /✓ /)
})
