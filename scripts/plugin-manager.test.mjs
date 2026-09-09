import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { createManager, within } = require('../android/app/src/main/assets/support/plugin-manager.cjs')

test('根目录路径校验兼容真实 Ubuntu 根路径', () => {
  assert.equal(within(path.parse(process.cwd()).root, process.cwd()), true)
  assert.equal(within('/sandbox', '/sandbox/child'), true)
  assert.equal(within('/sandbox', '/sandbox-other'), false)
})

function fixture(t, install) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-plugins-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const write = (file, data) => {
    const target = path.join(root, file)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, JSON.stringify(data))
    return target
  }
  const profile = write('root/.dsh/profiles/web/package.json', { dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', 'test-plugin'] } } })
  function pkg(name, rows) {
    write(`opt/dsh/node_modules/${name}/package.json`, { name, version: '1.0.0', dsh: { bundle: { patch: './cordis.patch.json' } } })
    return write(`opt/dsh/node_modules/${name}/cordis.patch.json`, rows)
  }
  pkg('@deepseek-ai/dsh-base', [{ insert: [{ id: 'sandbox', name: 'sandbox' }, { id: 'optional', name: 'optional' }] }])
  const pluginFile = pkg('test-plugin', [{ insert: [{ id: 'tools', name: 'group', group: true, config: [{ id: 'child', name: 'test-plugin/child' }] }] }])
  return { root, write, profile, pluginFile, manager: createManager(root, install, JSON.parse) }
}

test('不启动 Harness 即可列出官方、第三方配置文件及子插件', t => {
  const { manager } = fixture(t)
  const groups = manager.list().plugins
  assert.equal(groups[0].official, true)
  assert.equal(groups[1].official, false)
  assert.equal(groups[1].file, 'cordis.patch.json')
  assert.deepEqual(groups[1].children.map(row => row.id), ['tools', 'child'])
  assert.equal(groups[1].children[1].effectiveEnabled, true)
})

test('文件与子插件开关独立保存，恢复顺序且不写入插件源码', t => {
  const { manager, root, pluginFile } = fixture(t)
  const before = fs.readFileSync(pluginFile, 'utf8')
  manager.setChildEnabled('test-plugin', 'child', false)
  manager.setEnabled('test-plugin', false)
  assert.throws(() => manager.setChildEnabled('test-plugin', 'child', true), /PLUGIN_GROUP_DISABLED/)
  const restarted = createManager(root, undefined, JSON.parse)
  const groups = restarted.setEnabled('test-plugin', true).plugins
  assert.deepEqual(groups.map(group => group.id), ['@deepseek-ai/dsh-base', 'test-plugin'])
  assert.equal(groups[1].children[1].enabled, false)
  assert.equal(fs.readFileSync(pluginFile, 'utf8'), before)
})

test('父插件禁用影响子项生效状态，保留子项独立选择', t => {
  const { manager } = fixture(t)
  const group = manager.setChildEnabled('test-plugin', 'tools', false).plugins[1]
  assert.equal(group.children[1].enabled, true)
  assert.equal(group.children[1].effectiveEnabled, false)
})

test('禁止禁用安全组件及核心文件，允许管理官方可选插件', t => {
  const { manager } = fixture(t)
  assert.throws(() => manager.setEnabled('@deepseek-ai/dsh-base', false), /PLUGIN_PROTECTED/)
  assert.throws(() => manager.setChildEnabled('@deepseek-ai/dsh-base', 'sandbox', false), /PLUGIN_PROTECTED/)
  assert.equal(manager.setChildEnabled('@deepseek-ai/dsh-base', 'optional', false).plugins[0].children[1].enabled, false)
})

test('配置损坏时仍能禁用问题第三方文件', t => {
  const { manager, pluginFile } = fixture(t)
  fs.writeFileSync(pluginFile, 'not valid config')
  assert.equal(manager.list().plugins[1].readable, false)
  assert.equal(manager.setEnabled('test-plugin', false).plugins[1].enabled, false)
})

test('拒绝越界标识与篡改的恢复记录，不触碰原配置', t => {
  const { manager, write, profile } = fixture(t)
  const before = fs.readFileSync(profile, 'utf8')
  assert.throws(() => manager.setEnabled('../package', false), /PLUGIN_INPUT_INVALID/)
  assert.throws(() => manager.setChildEnabled('test-plugin', '../child', true), /PLUGIN_INPUT_INVALID/)
  write('root/.dsh-mobile/plugin-manager/transaction.json', { entries: [{ target: 'opt/dsh/node_modules', backup: 'root/.dsh-mobile/plugin-manager/backups/bad' }] })
  assert.throws(() => manager.recover(), /PLUGIN_RECOVERY_FAILED/)
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
})

function installer(id, directory) {
  const target = path.join(directory, 'node_modules', id)
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: id, version: '2.0.0', dsh: { bundle: { patch: './cordis.patch.json' } } }))
  fs.writeFileSync(path.join(target, 'cordis.patch.json'), JSON.stringify([{ insert: [{ id: 'child', name: 'test-plugin/child' }] }]))
}

test('更新禁用包后仍保持禁用，未替换共享依赖', t => {
  const { manager, write, root } = fixture(t, installer)
  const shared = write('opt/dsh/node_modules/shared/package.json', { name: 'shared', version: '1.0.0' })
  manager.setEnabled('test-plugin', false)
  const group = manager.update('test-plugin').plugins[1]
  assert.equal(group.version, '2.0.0')
  assert.equal(group.enabled, false)
  assert.equal(JSON.parse(fs.readFileSync(shared)).version, '1.0.0')
  const next = createManager(root, installer, JSON.parse).update('test-plugin').plugins[1]
  assert.equal(next.version, '2.0.0')
})

test('下载失败保留旧插件并清理失败暂存目录', t => {
  const { manager, root } = fixture(t, () => { throw new Error('PLUGIN_UPDATE_FAILED') })
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_UPDATE_FAILED/)
  assert.equal(manager.list().plugins[1].version, '1.0.0')
  assert.deepEqual(fs.readdirSync(path.join(root, 'root/.dsh-mobile/plugin-manager/versions')), [])
})

test('共享依赖冲突时拒绝整次更新并保留原版本', t => {
  const { manager, write } = fixture(t, (id, directory) => {
    installer(id, directory)
    const dependency = path.join(directory, 'node_modules/shared')
    fs.mkdirSync(dependency)
    fs.writeFileSync(path.join(dependency, 'package.json'), JSON.stringify({ name: 'shared', version: '2.0.0' }))
  })
  const shared = write('opt/dsh/node_modules/shared/package.json', { name: 'shared', version: '1.0.0' })
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_DEPENDENCY_UNSUPPORTED/)
  assert.equal(manager.list().plugins[1].version, '1.0.0')
  assert.equal(JSON.parse(fs.readFileSync(shared)).version, '1.0.0')
})

test('切换第二个依赖失败时回滚已经替换的插件', t => {
  const { manager, root } = fixture(t, (id, directory) => {
    installer(id, directory)
    const extra = path.join(directory, 'node_modules/zzz-extra')
    fs.mkdirSync(extra)
    fs.writeFileSync(path.join(extra, 'package.json'), JSON.stringify({ name: 'zzz-extra', version: '1.0.0' }))
  })
  const originalSymlink = fs.symlinkSync
  t.after(() => { fs.symlinkSync = originalSymlink })
  fs.symlinkSync = (source, target, type) => {
    if (target === path.join(root, 'root/.dsh/profiles/node_modules/zzz-extra')) throw new Error('PLUGIN_LINK_UNSUPPORTED')
    return originalSymlink(source, target, type)
  }
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_LINK_UNSUPPORTED/)
  assert.equal(manager.list().plugins[1].version, '1.0.0')
  assert.equal(fs.lstatSync(path.join(root, 'opt/dsh/node_modules/test-plugin')).isSymbolicLink(), false)
  assert.equal(fs.existsSync(path.join(root, 'root/.dsh-mobile/plugin-manager/transaction.json')), false)
})

test('进程中断后根据事务记录恢复原插件', t => {
  const { manager, root, write } = fixture(t)
  const original = path.join(root, 'opt/dsh/node_modules/test-plugin')
  const relativeBackup = 'root/.dsh-mobile/plugin-manager/backups/12345678-1234-1234-1234-123456789abc/0'
  const backup = path.join(root, relativeBackup)
  fs.mkdirSync(path.dirname(backup), { recursive: true })
  fs.renameSync(original, backup)
  fs.symlinkSync(backup, original, 'junction')
  write('root/.dsh-mobile/plugin-manager/transaction.json', { entries: [{ target: 'opt/dsh/node_modules/test-plugin', backup: relativeBackup, hadOriginal: true }] })
  manager.recover()
  assert.equal(fs.lstatSync(original).isSymbolicLink(), false)
  assert.equal(manager.list().plugins[1].version, '1.0.0')
})

test('按 pnpm 安装锚点读取和更新实际生效的包', t => {
  const { root, write } = fixture(t)
  const anchor = write('opt/dsh/node_modules/.pnpm/dsh-v1/node_modules/@deepseek-ai/dsh/package.json', { name: '@deepseek-ai/dsh', version: '1.0.0' })
  fs.symlinkSync(path.dirname(anchor), path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh'), 'junction')
  write('opt/dsh/node_modules/.pnpm/dsh-v1/node_modules/test-plugin/package.json', { name: 'test-plugin', version: '1.5.0', dsh: { bundle: { patch: './cordis.patch.json' } } })
  write('opt/dsh/node_modules/.pnpm/dsh-v1/node_modules/test-plugin/cordis.patch.json', [])
  const manager = createManager(root, installer, JSON.parse)
  assert.equal(manager.list().plugins[1].version, '1.5.0')
  assert.equal(manager.update('test-plugin').plugins[1].version, '2.0.0')
  assert.equal(JSON.parse(fs.readFileSync(path.join(root, 'opt/dsh/node_modules/test-plugin/package.json'))).version, '1.0.0')
})

test('真实 YAML 解析保留表达式为数据，不执行插件代码', t => {
  const { root, write, pluginFile } = fixture(t)
  const yaml = createRequire(require.resolve('eslint')).resolve('js-yaml')
  const yamlDirectory = path.dirname(yaml)
  write('opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/package.json', { name: '@deepseek-ai/dsh-app-boot' })
  fs.writeFileSync(pluginFile, '- insert:\n    - id: sample\n      name: sample\n      config:\n        value: !!js process.exit(99)\n')
  // 解析器位于测试工作区，复制到临时根内保持路径边界约束。
  fs.cpSync(yamlDirectory, path.join(root, 'opt/dsh/node_modules/js-yaml'), { recursive: true })
  const manager = createManager(root)
  assert.equal(manager.list().plugins[1].children[0].id, 'sample')
})
