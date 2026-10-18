import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { version, verifyLock, fingerprint, createManager } = require('../android/app/src/main/assets/support/harness-packages.cjs')
const integrity = 'sha512-' + 'a'.repeat(86) + '=='
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'harness-versions-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const write = (relative, value) => {
    const target = path.join(root, relative)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, typeof value === 'string' ? value : JSON.stringify(value))
  }
  return { root, write }
}
function lock() {
  return { packages: { '': {}, 'node_modules/@deepseek-ai/dsh': { version: '0.2.1-rc.2', resolved: 'https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.1-rc.2.tgz', integrity } } }
}
test('版本参数拒绝范围、Shell 注入与路径穿越', () => {
  assert.equal(version('0.2.1-rc.2'), '0.2.1-rc.2')
  for (const value of ['../x', 'latest', '^0.2.1', '0.2.1;id', '--version', '01.2.3', '1.2.3/evil', '1.2.3\n', '1'.repeat(100)]) assert.throws(() => version(value))
})
test('锁文件拒绝来源替换、摘要变化和第二套核心版本', () => {
  verifyLock(lock(), '0.2.1-rc.2', integrity)
  const wrong = lock()
  wrong.packages['node_modules/@deepseek-ai/dsh'].resolved = 'https://example.com/a.tgz'
  assert.throws(() => verifyLock(wrong, '0.2.1-rc.2', integrity), /HARNESS_PACKAGE_INVALID/u)
  assert.throws(() => verifyLock(lock(), '0.2.1-rc.2', 'sha512-' + 'b'.repeat(86) + '=='), /HARNESS_INTEGRITY_INVALID/u)
  const duplicate = lock()
  duplicate.packages['node_modules/another/node_modules/@deepseek-ai/dsh-tools'] = { ...duplicate.packages['node_modules/@deepseek-ai/dsh'], version: '0.2.0-rc.2' }
  assert.throws(() => verifyLock(duplicate, '0.2.1-rc.2', integrity), /HARNESS_DEPENDENCY_MISMATCH/u)
})
test('离线校验检测包内容变化，额外插件和 ready 元数据不改变安装摘要', t => {
  const { root, write } = fixture(t)
  write('node_modules/package/index.js', 'original')
  const original = fingerprint(root)
  write('ready.json', { runtime: 'new-runtime' })
  write('node_modules/plugin/index.js', '用户新装插件')
  assert.equal(fingerprint(root, original.files).sha256, original.sha256)
  write('node_modules/package/index.js', 'changed')
  assert.notEqual(fingerprint(root, original.files).sha256, original.sha256)
  assert.throws(() => fingerprint(root, ['../escape']), /HARNESS_PATH_INVALID/u)
})
test('目录管理拒绝符号链接逃逸，保留外部文件', t => {
  const { root, write } = fixture(t)
  write('outside/keep.txt', 'keep')
  fs.mkdirSync(path.join(root, 'root/.dsh-mobile'), { recursive: true })
  fs.symlinkSync(path.join(root, 'outside'), path.join(root, 'root/.dsh-mobile/harness-versions'), 'junction')
  assert.throws(() => createManager(root), /HARNESS_PATH_INVALID/u)
  assert.equal(fs.readFileSync(path.join(root, 'outside/keep.txt'), 'utf8'), 'keep')
})
test('安装失败只清理本次暂存，不触碰旧版本、会话、插件与密钥', t => {
  const { root, write } = fixture(t)
  write('root/.dsh/sessions/session.json', '会话')
  write('root/.dsh/.credentials.yaml', '测试占位，不是真实凭据')
  write('root/.dsh-mobile/plugin-manager/user-data.json', '插件数据')
  write('opt/dsh-bundled/package.json', { dependencies: {} })
  const calls = []
  const manager = createManager(root, (bin, args, options) => { calls.push({ bin, args, options }); return { status: 1 } })
  assert.throws(() => manager.install('0.2.1-rc.2', integrity), /HARNESS_INSTALL_FAILED/u)
  assert.equal(calls.length, 1)
  assert.ok(calls[0].args.includes('--ignore-scripts'))
  assert.equal(calls[0].options.shell, undefined)
  assert.deepEqual(Object.keys(calls[0].options.env).sort(), ['HOME', 'LANG', 'PATH', 'TMPDIR'])
  assert.deepEqual(fs.readdirSync(path.join(root, 'root/.dsh-mobile/harness-versions/versions')), [])
  assert.equal(fs.readFileSync(path.join(root, 'root/.dsh/sessions/session.json'), 'utf8'), '会话')
  assert.equal(fs.readFileSync(path.join(root, 'root/.dsh-mobile/plugin-manager/user-data.json'), 'utf8'), '插件数据')
  assert.ok(fs.existsSync(path.join(root, 'root/.dsh/.credentials.yaml')))
})
test('profile 重新接链支持 pnpm 隔离布局并保留用户插件和配置', t => {
  const { root, write } = fixture(t)
  write('opt/dsh/package.json', { dependencies: { '@deepseek-ai/dsh': '0.2.1-rc.2' } })
  write('opt/dsh/node_modules/.pnpm/dsh/node_modules/@deepseek-ai/dsh/package.json', { name: '@deepseek-ai/dsh', dependencies: { '@deepseek-ai/dsh-tools': '0.2.1-rc.2' } })
  write('opt/dsh/node_modules/.pnpm/dsh/node_modules/@deepseek-ai/dsh-tools/package.json', { name: '@deepseek-ai/dsh-tools' })
  fs.mkdirSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai'), { recursive: true })
  fs.symlinkSync(path.join(root, 'opt/dsh/node_modules/.pnpm/dsh/node_modules/@deepseek-ai/dsh'), path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh'), 'junction')
  write('root/.dsh/profiles/web/package.json', { dsh: { profile: { bundles: ['custom-plugin'] } } })
  write('root/.dsh/profiles/web/node_modules/custom-plugin/data.json', '用户数据')
  createManager(root).prepare()
  assert.ok(fs.realpathSync(path.join(root, 'root/.dsh/profiles/node_modules/@deepseek-ai/dsh-tools')).endsWith('dsh-tools'))
  assert.equal(fs.readFileSync(path.join(root, 'root/.dsh/profiles/web/node_modules/custom-plugin/data.json'), 'utf8'), '用户数据')
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, 'root/.dsh/profiles/web/package.json'), 'utf8')).dsh.profile.bundles, ['custom-plugin'])
})

test('安装按阶段上报进度，失败时标出停在哪一步', t => {
  const { root, write } = fixture(t)
  write('opt/dsh-bundled/package.json', { dependencies: {} })
  const events = []
  const manager = createManager(root, () => ({ status: 1 }), (stage, percent) => events.push([stage, percent]))
  assert.throws(() => manager.install('0.2.1-rc.2', integrity), error => error.stage === 'resolve' && /HARNESS_INSTALL_FAILED/u.test(error.message))
  // 阶段只在真正进入时上报：npm 第一步就失败，不应该出现后面的阶段。
  assert.deepEqual(events, [['prepare', 5], ['resolve', 15]])
})