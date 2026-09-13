import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { createManager, within, parseVersion, compareVersions, satisfiesRange, selectNewestCompatible, safeDetail, packageNameOf, collectBareSpecifiers } =
  require('../android/app/src/main/assets/support/plugin-manager.cjs')

test('裸导入抽取只认包名，路径与内建模块一律忽略', () => {
  assert.equal(packageNameOf('react'), 'react')
  assert.equal(packageNameOf('react/jsx-runtime'), 'react')
  assert.equal(packageNameOf('@deepseek-ai/dsh-tools'), '@deepseek-ai/dsh-tools')
  assert.equal(packageNameOf('@deepseek-ai/dsh-tools/lib/x.js'), '@deepseek-ai/dsh-tools')
  assert.equal(packageNameOf('./local'), null)
  assert.equal(packageNameOf('../up'), null)
  assert.equal(packageNameOf('/abs/path'), null)
  assert.equal(packageNameOf('node:fs'), null)
  assert.equal(packageNameOf('#internal'), null)
  assert.equal(packageNameOf(''), null)
  assert.equal(packageNameOf('a'.repeat(200)), null)

  const source = [
    "import { z } from 'zustand'",
    "import 'side-effect-pkg'",
    "const a = require('@scope/pkg/deep')",
    "const b = await import('immer')",
    "import local from './local.js'",
    "import fs from 'node:fs'",
    'const notImport = "from \'quoted-in-string\'"',
  ].join('\n')
  const found = collectBareSpecifiers(source)
  // `quoted-in-string` 是已知的误报：文本扫描无法区分字符串字面量里的 `from '...'`。
  // 误报是安全的——只有"运行时确实存在"的名字才会被链进 staging，凭空出现的名字会被
  // resolvePackage() 拒绝，代价仅是一次多余的查找。
  assert.deepEqual([...found].sort(), ['@scope/pkg', 'immer', 'quoted-in-string', 'side-effect-pkg', 'zustand'])
  // 真正危险的是漏报与路径误判，这里确认相对路径没有被当成包名。
  assert.equal(found.has('local.js'), false)
  assert.equal(found.has('fs'), false)
})

test('解析闭包补全：宿主提供的裸导入被链回运行时实例，不改动既有依赖', t => {
  // 复现"模块不完整"：插件只带自己的依赖，而 react / cordis 这类宿主包不在 staging 里。
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-staging-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const write = (relative, content) => {
    const file = path.join(root, relative)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, content)
  }
  const pkg = (name, version, extra = {}) => JSON.stringify({ name, version, ...extra })

  // 运行时：opt/dsh/node_modules 里放宿主包（resolvePackage 的允许根之一）。
  write('opt/dsh/node_modules/react/package.json', pkg('react', '18.3.1'))
  write('opt/dsh/node_modules/@deepseek-ai/cordis/package.json', pkg('@deepseek-ai/cordis', '4.0.2'))
  write('opt/dsh/node_modules/@deepseek-ai/dsh/package.json', pkg('@deepseek-ai/dsh', '0.1.5-rc.1'))
  write('opt/node/lib/node_modules/npm/bin/npm-cli.js', '// npm')
  write('root/.dsh/profiles/web/package.json', JSON.stringify({ dsh: { profile: { bundles: ['demo-plugin'] } } }))

  // 插件：只声明并携带自己的依赖，源码里却导入了宿主包。
  const plugin = pkg('demo-plugin', '1.0.0', { dsh: { bundle: { patch: './cordis.patch.json' } } })
  const install = (id, directory) => {
    const stage = path.join(directory, 'node_modules')
    fs.mkdirSync(path.join(stage, 'demo-plugin'), { recursive: true })
    fs.writeFileSync(path.join(stage, 'demo-plugin/package.json'), plugin)
    fs.writeFileSync(path.join(stage, 'demo-plugin/cordis.patch.json'), '[]')
    fs.writeFileSync(
      path.join(stage, 'demo-plugin/index.js'),
      "import { z } from 'zustand'\nimport { c } from '@deepseek-ai/cordis'\nimport r from 'react'\n",
    )
    fs.mkdirSync(path.join(stage, 'zustand'), { recursive: true })
    fs.writeFileSync(path.join(stage, 'zustand/package.json'), pkg('zustand', '4.5.5'))  }

  // 注入 YAML 解析器：补丁内容就是 JSON 数组，用 JSON.parse 即可，避免依赖运行时里的 js-yaml。
  const manager = createManager(root, install, text => JSON.parse(text))
  manager.update('demo-plugin')

  const stage = path.join(root, 'root/.dsh-mobile/plugin-manager/versions')
  const transaction = fs.readdirSync(stage)[0]
  const modules = path.join(stage, transaction, 'node_modules')

  // 宿主包被链进来（否则 Node 会 ERR_MODULE_NOT_FOUND，表现为"模块不完整"）。
  assert.equal(fs.lstatSync(path.join(modules, 'react')).isSymbolicLink(), true)
  assert.equal(fs.realpathSync(path.join(modules, 'react')), fs.realpathSync(path.join(root, 'opt/dsh/node_modules/react')))
  assert.equal(fs.lstatSync(path.join(modules, '@deepseek-ai/cordis')).isSymbolicLink(), true)
  // 插件自带、staging 里已存在的依赖保持原样，不被链接替换。
  assert.equal(fs.lstatSync(path.join(modules, 'zustand')).isSymbolicLink(), false)
  assert.equal(JSON.parse(fs.readFileSync(path.join(modules, 'zustand/package.json'), 'utf8')).version, '4.5.5')
  // 运行时里不存在的名字不会被凭空造出来。
  assert.equal(fs.existsSync(path.join(modules, 'missing-package')), false)
})

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

// ---------------------------------------------------------------------------
// 运行时包单例：安装后消重与已安装插件修复
//
// 这一组覆盖设备上的真实故障链：npm 把插件依赖的 `@deepseek-ai/*`（尤其是 dsh-base、
// dsh-agent-loop 拖进来的 dsh-tools）装成真实副本，插件目录里因此出现同一份带 Symbol
// 服务模块的第二份物理副本，`ctx.tools[TOOL_RUNTIME_SCHEDULER]` 取到 undefined，
// 每一次工具调用都在 '.prepare' 上抛错。
// ---------------------------------------------------------------------------

/** 在某个 node_modules 里造一个"npm 装出来的"真实副本包目录。 */
function stagedPackage(directory, name, version) {
  const target = path.join(directory, name)
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name, version }))
  return target
}

/** 造一个已安装插件的版本目录，返回它的 node_modules 路径。 */
function installedModules(root, transactionId) {
  const modules = path.join(root, 'root/.dsh-mobile/plugin-manager/versions', transactionId, 'node_modules')
  fs.mkdirSync(modules, { recursive: true })
  return modules
}

/** 取本次更新的事务目录下的 node_modules（暂存目录就是插件实际被加载的位置）。 */
function stagedModules(root, expected = 1) {
  const base = path.join(root, 'root/.dsh-mobile/plugin-manager/versions')
  const entries = fs.readdirSync(base)
  assert.equal(entries.length, expected)
  return path.join(base, entries[0], 'node_modules')
}

test('安装后消重：staging 里的 @deepseek-ai/* 真实副本改成指向运行时实例的链接', t => {
  const { manager, write, root } = fixture(t, (id, directory) => {
    installer(id, directory)
    const stage = path.join(directory, 'node_modules')
    // 插件自己的第三方依赖：必须保持 npm 装好的真实副本。
    stagedPackage(stage, 'zustand', '4.5.5')
    // 顶层真实副本：npm 的提升结果。
    stagedPackage(stage, '@deepseek-ai/dsh-tools', '1.0.0')
    // 嵌套真实副本：--legacy-peer-deps 下版本冲突时 npm 会把副本嵌在包目录里。
    stagedPackage(path.join(stage, 'test-plugin', 'node_modules'), '@deepseek-ai/dsh-tools', '1.0.0')
  })
  write('opt/dsh/node_modules/@deepseek-ai/dsh-tools/package.json', { name: '@deepseek-ai/dsh-tools', version: '1.0.0' })

  const result = manager.update('test-plugin')
  assert.equal(result.plugins[1].version, '2.0.0')
  // 计数随更新结果回传，只含计数不含路径。
  assert.deepEqual(result.runtimeDedupe, { scanned: 2, linked: 2, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0 })

  const modules = stagedModules(root)
  const runtimeCopy = fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-tools'))
  for (const relative of ['@deepseek-ai/dsh-tools', 'test-plugin/node_modules/@deepseek-ai/dsh-tools']) {
    const link = path.join(modules, relative)
    assert.equal(fs.lstatSync(link).isSymbolicLink(), true, relative)
    assert.equal(fs.realpathSync(link), runtimeCopy, relative)
    // 链接是有效的：插件内解析得到运行时那一份，Symbol 身份因此一致。
    assert.equal(JSON.parse(fs.readFileSync(path.join(link, 'package.json'), 'utf8')).name, '@deepseek-ai/dsh-tools')
  }
  // 插件自己的第三方依赖原样保留。
  assert.equal(fs.lstatSync(path.join(modules, 'zustand')).isSymbolicLink(), false)
  assert.equal(JSON.parse(fs.readFileSync(path.join(modules, 'zustand/package.json'), 'utf8')).version, '4.5.5')
  // 运行时本体没有被替换成指向自己的链接。
  assert.equal(fs.lstatSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-tools')).isSymbolicLink(), false)
})

test('修复动作：已安装插件里的真实副本与悬空链接重新指向当前运行时，且幂等', t => {
  const { manager, write, root } = fixture(t)
  write('opt/dsh/node_modules/@deepseek-ai/dsh-tools/package.json', { name: '@deepseek-ai/dsh-tools', version: '1.0.0' })
  write('opt/dsh/node_modules/@deepseek-ai/dsh-base/package.json', { name: '@deepseek-ai/dsh-base', version: '1.0.0' })
  write('opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/package.json', { name: '@deepseek-ai/dsh-app-boot', version: '1.0.0' })

  const modules = installedModules(root, '12345678-1234-1234-1234-123456789abc')
  // 坏形态一：真实副本（安装时留下的第二份 dsh-tools，且版本比运行时旧）。
  stagedPackage(modules, '@deepseek-ai/dsh-tools', '0.1.4')
  // 坏形态二：悬空链接 —— 运行时升级后插件目录被保留，旧版本号 + peer 哈希的路径已不存在。
  const missing = path.join(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-base@0.1.4_oldhash/node_modules/@deepseek-ai/dsh-base')
  fs.symlinkSync(missing, path.join(modules, '@deepseek-ai/dsh-base'), 'junction')
  assert.equal(fs.existsSync(path.join(modules, '@deepseek-ai/dsh-base/package.json')), false)
  // 已经指向当前运行时实例的链接：不该被改动。
  const healthy = fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-app-boot'))
  fs.symlinkSync(healthy, path.join(modules, '@deepseek-ai/dsh-app-boot'), 'junction')
  // 嵌套副本同样处理（不做嵌套就会留下第二份物理副本）。
  stagedPackage(path.join(modules, 'demo-plugin/node_modules'), '@deepseek-ai/dsh-tools', '0.1.4')

  const runtimeTools = fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-tools'))
  assert.deepEqual(manager.repair(), { versions: 1, scanned: 4, linked: 3, unchanged: 1, versionMismatch: 2, failed: 0, refused: 0 })
  for (const relative of ['@deepseek-ai/dsh-tools', 'demo-plugin/node_modules/@deepseek-ai/dsh-tools']) {
    assert.equal(fs.lstatSync(path.join(modules, relative)).isSymbolicLink(), true, relative)
    assert.equal(fs.realpathSync(path.join(modules, relative)), runtimeTools, relative)
  }
  const base = path.join(modules, '@deepseek-ai/dsh-base')
  assert.equal(fs.realpathSync(base), fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-base')))
  assert.equal(fs.realpathSync(path.join(modules, '@deepseek-ai/dsh-app-boot')), healthy)
  assert.equal(fs.lstatSync(path.join(modules, 'demo-plugin')).isSymbolicLink(), false)

  // 幂等：再跑一次不改动任何东西，全部计入 unchanged。
  assert.deepEqual(manager.repair(), { versions: 1, scanned: 4, linked: 0, unchanged: 4, versionMismatch: 0, failed: 0, refused: 0 })
})

test('修复动作拒绝越界路径：链接指向处理范围之外时一处都不动', t => {
  const { manager, write, root } = fixture(t)
  write('opt/dsh/node_modules/@deepseek-ai/dsh-tools/package.json', { name: '@deepseek-ai/dsh-tools', version: '1.0.0' })
  // 处理范围之外的真实副本。它仍在 guest root 内，所以"必须在 root 内"这一层挡不住它，
  // 必须由 node_modules / 作用域目录 / 包目录三层的边界校验挡住。
  const outside = path.join(root, 'tmp-outside')
  stagedPackage(path.join(outside, 'modules'), '@deepseek-ai/dsh-tools', '1.0.0')
  stagedPackage(path.join(outside, 'scope'), '@deepseek-ai/dsh-tools', '1.0.0')
  stagedPackage(path.join(outside, 'nested/node_modules'), '@deepseek-ai/dsh-tools', '1.0.0')

  // 形态一：版本目录的 node_modules 整体是指向处理范围之外的链接。
  const wholeLink = installedModules(root, '11111111-1111-1111-1111-111111111111')
  fs.rmdirSync(wholeLink)
  fs.symlinkSync(path.join(outside, 'modules'), wholeLink, 'junction')
  // 形态二：作用域目录 @deepseek-ai 本身是指向处理范围之外的链接。
  const scopeLink = installedModules(root, '22222222-2222-2222-2222-222222222222')
  fs.symlinkSync(path.join(outside, 'scope'), path.join(scopeLink, '@deepseek-ai'), 'junction')
  // 形态三：嵌套层的包目录是指向处理范围之外的链接。
  const nestedLink = installedModules(root, '33333333-3333-3333-3333-333333333333')
  fs.symlinkSync(path.join(outside, 'nested'), path.join(nestedLink, 'demo-plugin'), 'junction')

  // 形态一被整体拒绝（不计入处理数），形态二、三的链接一律不进入：没有任何包被处理。
  assert.deepEqual(manager.repair(), { versions: 2, scanned: 0, linked: 0, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0 })
  for (const relative of ['modules/@deepseek-ai/dsh-tools', 'scope/@deepseek-ai/dsh-tools', 'nested/node_modules/@deepseek-ai/dsh-tools']) {
    const copy = path.join(outside, relative)
    assert.equal(fs.lstatSync(copy).isSymbolicLink(), false, relative)
    assert.equal(JSON.parse(fs.readFileSync(path.join(copy, 'package.json'), 'utf8')).version, '1.0.0', relative)
  }
})

test('安装后消重：版本与运行时不一致也改用运行时实例，而不是让整次更新失败', t => {
  // dsh-base 是绝大多数功能插件的依赖，npm 会把它解析成范围内的最新版本，与运行时钉住的
  // 版本经常不同 —— 这正是设备上留下真实副本的入口。第二份物理副本必然让工具调用全挂，
  // 因此这里改为一律指向运行时实例（计数里保留 versionMismatch 供排查），不再整次失败。
  const { manager, root } = fixture(t, (id, directory) => {
    installer(id, directory)
    stagedPackage(path.join(directory, 'node_modules'), '@deepseek-ai/dsh-base', '2.0.0')
  })
  const result = manager.update('test-plugin')
  assert.equal(result.plugins[1].version, '2.0.0')
  assert.deepEqual(result.runtimeDedupe, { scanned: 1, linked: 1, unchanged: 0, versionMismatch: 1, failed: 0, refused: 0 })
  const link = path.join(stagedModules(root), '@deepseek-ai/dsh-base')
  assert.equal(fs.lstatSync(link).isSymbolicLink(), true)
  assert.equal(fs.realpathSync(link), fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-base')))
  // 插件加载到的是运行时那一份（1.0.0），不是 npm 装出来的 2.0.0。
  assert.equal(JSON.parse(fs.readFileSync(path.join(link, 'package.json'), 'utf8')).version, '1.0.0')
})

test('运行时没有的 @deepseek-ai/* 依赖保持原样，仍按受控错误拒绝整次更新', t => {
  const { manager } = fixture(t, (id, directory) => {
    installer(id, directory)
    stagedPackage(path.join(directory, 'node_modules'), '@deepseek-ai/dsh-unknown', '1.0.0')
  })
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_DEPENDENCY_UNSUPPORTED/)
})

test('安装后消重不跟随指向 staging 之外的链接', t => {
  const { manager, root } = fixture(t, (id, directory) => {
    installer(id, directory)
    // 插件内部嵌套的 node_modules 是指向 staging 之外的链接：去重必须不进入，
    // 否则会把处理范围之外的目录当成插件自己的副本删掉。
    fs.symlinkSync(path.join(root, 'tmp-outside/nested'), path.join(directory, 'node_modules/test-plugin/node_modules'), 'junction')
  })
  const outside = stagedPackage(path.join(root, 'tmp-outside/nested/node_modules'), '@deepseek-ai/dsh-tools', '1.0.0')

  const result = manager.update('test-plugin')
  assert.equal(result.plugins[1].version, '2.0.0')
  assert.deepEqual(result.runtimeDedupe, { scanned: 0, linked: 0, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0 })
  assert.equal(fs.lstatSync(outside).isSymbolicLink(), false)
  assert.equal(JSON.parse(fs.readFileSync(path.join(outside, 'package.json'), 'utf8')).version, '1.0.0')
})
