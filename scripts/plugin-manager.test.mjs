import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { createManager, within, parseVersion, compareVersions, satisfiesRange, selectNewestCompatible, safeDetail } =
  require('../android/app/src/main/assets/support/plugin-manager.cjs')

test('失败详情只允许包名与版本字符，路径与异常原文一律丢弃', () => {
  // 能定位问题的正文必须放行。
  assert.equal(safeDetail('commander 7.2.0 != 15.0.0'), 'commander 7.2.0 != 15.0.0')
  assert.equal(safeDetail('@linxin666/dsh-web-all dsh=0.1.5-alpha.1'), '@linxin666/dsh-web-all dsh=0.1.5-alpha.1')
  assert.equal(safeDetail('zustand >=0.1.5-rc.1'), 'zustand >=0.1.5-rc.1')
  // 路径、URL、引号、反斜杠、换行、超长内容全部拒绝 —— 详情会回到 WebView，
  // 不能成为把 guest 路径或异常原文带出容器的通道。
  assert.equal(safeDetail('/root/.dsh-mobile/plugin-manager/versions/txn/node_modules/x'), null)
  assert.equal(safeDetail('~/dsh/x'), null)
  assert.equal(safeDetail('./relative'), null)
  assert.equal(safeDetail('root//double'), null)
  assert.equal(safeDetail('https://registry.npmjs.org/x'), null)
  assert.equal(safeDetail('boom "quoted"'), null)
  assert.equal(safeDetail('back\\slash'), null)
  assert.equal(safeDetail('line\nbreak'), null)
  assert.equal(safeDetail('a'.repeat(301)), null)
  assert.equal(safeDetail(''), null)
  assert.equal(safeDetail(undefined), null)
  assert.equal(safeDetail({ toString: () => 'x' }), null)
})

test('只认 dist-tags.latest 会装错版本：必须按运行时 dsh 版本挑引擎兼容版本', () => {
  // 两个真实反例：latest 要求更高的 dsh；另一些包的 latest 反而是更早的预发布。
  const versions = ['0.3.20', '0.3.19', '0.3.18', '0.3.17-beta.1', '0.3.16']
  const ranges = {
    '0.3.20': '>=0.1.5-rc.1',
    '0.3.19': '>=0.1.5-rc.1',
    '0.3.18': '>=0.1.5-alpha.1',
    '0.3.17-beta.1': '>=0.1.5-alpha.1',
    '0.3.16': '>=0.1.5-alpha.1',
  }
  // 运行时是 0.1.5-alpha.1：0.3.20 / 0.3.19 不满足，落到 0.3.18。
  assert.deepEqual(
    selectNewestCompatible(versions, '0.1.5-alpha.1', version => ranges[version]),
    { version: '0.3.18', range: '>=0.1.5-alpha.1' },
  )
  // 运行时升到 0.1.5-rc.2 后同一个包就能装到最新。
  assert.deepEqual(
    selectNewestCompatible(versions, '0.1.5-rc.2', version => ranges[version]),
    { version: '0.3.20', range: '>=0.1.5-rc.1' },
  )
})

test('未声明 dsh 引擎范围的插件按兼容处理，直接取最高版本', () => {
  assert.deepEqual(
    selectNewestCompatible(['1.2.0', '1.1.0'], '0.1.5-alpha.1', () => null),
    { version: '1.2.0', range: null },
  )
  assert.deepEqual(
    selectNewestCompatible(['1.2.0', '1.1.0'], '0.1.5-alpha.1', () => '*'),
    { version: '1.2.0', range: null },
  )
})

test('稳定版运行时优先稳定候选，预发布只在必要时回退', () => {
  const ranges = { '2.0.0-beta.1': '>=1.0.0', '1.9.0': '>=1.0.0' }
  // 运行时是稳定版：即便 2.0.0-beta.1 更新，也优先取稳定候选 1.9.0。
  assert.equal(
    selectNewestCompatible(['2.0.0-beta.1', '1.9.0'], '1.0.0', version => ranges[version]).version,
    '1.9.0',
  )
  // 稳定候选没有兼容版本时，才回退到预发布候选。
  assert.equal(
    selectNewestCompatible(['2.0.0-beta.1', '1.9.0'], '1.0.0', version => (version === '1.9.0' ? '>=9.0.0' : '>=1.0.0')).version,
    '2.0.0-beta.1',
  )
})

test('没有兼容版本时返回 null，调用方据此报 PLUGIN_ENGINE_UNSUPPORTED', () => {
  assert.equal(selectNewestCompatible(['2.0.0', '1.0.0'], '0.1.5-alpha.1', () => '>=0.1.5-rc.1'), null)
  assert.equal(selectNewestCompatible([], '0.1.5-alpha.1', () => null), null)
  assert.equal(selectNewestCompatible(['not-a-version'], '0.1.5-alpha.1', () => null), null)
  // 运行时版本本身不可解析时不猜：返回 null 由调用方决定降级策略。
  assert.equal(selectNewestCompatible(['1.0.0'], 'unknown', () => null), null)
})

test('探测次数有上限，避免版本很多的包把更新时间拖爆', () => {
  const versions = Array.from({ length: 50 }, (_, index) => `1.0.${50 - index}`)
  let probes = 0
  const result = selectNewestCompatible(versions, '0.1.5-alpha.1', () => {
    probes += 1
    return '>=9.9.9'
  }, 4)
  assert.equal(result, null)
  assert.equal(probes, 4)
})

test('范围匹配严格对齐 npm 语义（含预发布与插入符）', () => {
  // 预发布只有在同元组且比较符自身带预发布时才参与匹配 —— 这正是
  // 「alpha 不满足 rc 下限」与「alpha 不满足 ^0.1.5」的原因。
  assert.equal(satisfiesRange('0.1.5-alpha.1', '>=0.1.5-rc.1'), false)
  assert.equal(satisfiesRange('0.1.5-rc.2', '>=0.1.5-rc.1'), true)
  assert.equal(satisfiesRange('0.1.5-alpha.1', '^0.1.5'), false)
  assert.equal(satisfiesRange('0.1.5', '^0.1.5'), true)
  assert.equal(satisfiesRange('0.1.9', '^0.1.5'), true)
  assert.equal(satisfiesRange('0.2.0', '^0.1.5'), false)
  assert.equal(satisfiesRange('1.5.0', '^1.2.3'), true)
  assert.equal(satisfiesRange('2.0.0', '^1.2.3'), false)
  // 波浪号、部分版本、与、或、通配。
  assert.equal(satisfiesRange('0.1.5', '~0.1.2'), true)
  assert.equal(satisfiesRange('0.2.0', '~0.1.2'), false)
  assert.equal(satisfiesRange('0.3.7', '0.3'), true)
  assert.equal(satisfiesRange('0.4.0', '0.3'), false)
  assert.equal(satisfiesRange('0.1.5', '>=0.1.0 <0.2.0'), true)
  assert.equal(satisfiesRange('0.2.1', '>=0.1.0 <0.2.0'), false)
  assert.equal(satisfiesRange('0.1.5-alpha.1', '>=0.1.5-rc.1 || >=0.1.4'), false)
  assert.equal(satisfiesRange('0.1.4', '>=0.1.5-rc.1 || >=0.1.4'), true)
  assert.equal(satisfiesRange('0.1.5', '*'), true)
  assert.equal(satisfiesRange('0.1.5', ''), false)
  assert.equal(satisfiesRange('0.1.5', 'a'.repeat(300)), false)
})

test('版本比较遵循 semver 的预发布优先级', () => {
  assert.equal(compareVersions('1.0.0', '1.0.0-rc.1'), 1)
  assert.equal(compareVersions('1.0.0-alpha.1', '1.0.0-alpha.2'), -1)
  assert.equal(compareVersions('1.0.0-alpha.1', '1.0.0-beta.1'), -1)
  assert.equal(compareVersions('1.0.0-1', '1.0.0-alpha'), -1)
  assert.equal(compareVersions('1.0.0+build.2', '1.0.0+build.1'), 0)
  assert.equal(compareVersions('1.0', '1.0.0'), null)
  assert.equal(compareVersions('1.0.0', 'x.y.z'), null)
  assert.deepEqual(parseVersion('0.1.5-rc.2'), { numbers: [0, 1, 5], prerelease: ['rc', '2'] })
  assert.deepEqual(parseVersion('1.2.3').prerelease, [])
  assert.equal(parseVersion('x'.repeat(80)), null)
})

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
