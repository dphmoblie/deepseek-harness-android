import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { createManager, within, parseVersion, compareVersions, satisfiesRange, selectNewestCompatible, safeDetail, packageNameOf, collectBareSpecifiers, validateImportSource, gitAvailability, GRAPH_LIMITS } =
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

function fixture(t, install, probeGit) {
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
  return { root, write, profile, pluginFile, manager: createManager(root, install, JSON.parse, probeGit) }
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
  assert.deepEqual(manager.repair(), { versions: 1, scanned: 4, linked: 3, unchanged: 1, versionMismatch: 2, failed: 0, refused: 0, plugins: 1, relinked: 0, missing: 0 })
  for (const relative of ['@deepseek-ai/dsh-tools', 'demo-plugin/node_modules/@deepseek-ai/dsh-tools']) {
    assert.equal(fs.lstatSync(path.join(modules, relative)).isSymbolicLink(), true, relative)
    assert.equal(fs.realpathSync(path.join(modules, relative)), runtimeTools, relative)
  }
  const base = path.join(modules, '@deepseek-ai/dsh-base')
  assert.equal(fs.realpathSync(base), fs.realpathSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh-base')))
  assert.equal(fs.realpathSync(path.join(modules, '@deepseek-ai/dsh-app-boot')), healthy)
  assert.equal(fs.lstatSync(path.join(modules, 'demo-plugin')).isSymbolicLink(), false)

  // 幂等：再跑一次不改动任何东西，全部计入 unchanged。
  assert.deepEqual(manager.repair(), { versions: 1, scanned: 4, linked: 0, unchanged: 4, versionMismatch: 0, failed: 0, refused: 0, plugins: 1, relinked: 0, missing: 0 })
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
  assert.deepEqual(manager.repair(), { versions: 2, scanned: 0, linked: 0, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0, plugins: 1, relinked: 0, missing: 0 })
  for (const relative of ['modules/@deepseek-ai/dsh-tools', 'scope/@deepseek-ai/dsh-tools', 'nested/node_modules/@deepseek-ai/dsh-tools']) {
    const copy = path.join(outside, relative)
    assert.equal(fs.lstatSync(copy).isSymbolicLink(), false, relative)
    assert.equal(JSON.parse(fs.readFileSync(path.join(copy, 'package.json'), 'utf8')).version, '1.0.0', relative)
  }
})

test('运行时升级把插件链接换掉后，修复动作按保留区的副本接回去，不必重装', t => {
  const { manager, root, profile } = fixture(t, installer)
  // 真机形态：插件不在运行时自带目录里，安装时的链接落点就是 profiles/node_modules（兜底位置）。
  fs.rmSync(path.join(root, 'opt/dsh/node_modules/test-plugin'), { recursive: true, force: true })
  assert.equal(manager.update('test-plugin').plugins[1].version, '2.0.0')
  const link = path.join(root, 'root/.dsh/profiles/node_modules/test-plugin')
  assert.equal(fs.lstatSync(link).isSymbolicLink(), true)
  const kept = fs.realpathSync(link)
  assert.equal(within(path.join(root, 'root/.dsh-mobile/plugin-manager'), kept), true)

  // 升级：profiles 不在保留名单里 —— 清单与链接随新 rootfs 一起重建，包本体留在保留区。
  simulateRuntimeUpgrade(root, profile)
  fs.rmSync(path.join(root, 'root/.dsh/profiles/node_modules'), { recursive: true, force: true })
  const restarted = createManager(root, installer, JSON.parse)
  assert.equal(restarted.recover().plugins, 1)
  // 链接没了，清单里却还登记着它：这正是真机上「已启用 / 未安装」的样子。
  assert.equal(restarted.list().plugins.find(row => row.id === 'test-plugin').installed, false)

  const repaired = restarted.repair()
  assert.equal(repaired.plugins, 1)
  assert.equal(repaired.relinked, 1)
  assert.equal(repaired.missing, 0)
  assert.equal(fs.realpathSync(link), kept)
  const plugin = restarted.list().plugins.find(row => row.id === 'test-plugin')
  assert.equal(plugin.installed, true)
  assert.equal(plugin.version, '2.0.0')

  // 幂等：接回去之后再跑一次什么都不动。
  const again = restarted.repair()
  assert.equal(again.plugins, 1)
  assert.equal(again.relinked, 0)
  assert.equal(fs.realpathSync(link), kept)
})

test('保留区里已经没有副本时只记 missing，不造悬空链接', t => {
  const { manager, root, profile } = fixture(t, installer)
  fs.rmSync(path.join(root, 'opt/dsh/node_modules/test-plugin'), { recursive: true, force: true })
  manager.update('test-plugin')
  const versions = path.join(root, 'root/.dsh-mobile/plugin-manager/versions')
  for (const entry of fs.readdirSync(versions)) fs.rmSync(path.join(versions, entry), { recursive: true, force: true })
  fs.rmSync(path.join(root, 'root/.dsh/profiles/node_modules'), { recursive: true, force: true })
  simulateRuntimeUpgrade(root, profile)
  const restarted = createManager(root, installer, JSON.parse)
  restarted.recover()
  const repaired = restarted.repair()
  assert.equal(repaired.plugins, 1)
  assert.equal(repaired.relinked, 0)
  assert.equal(repaired.missing, 1)
  assert.equal(fs.existsSync(path.join(root, 'root/.dsh/profiles/node_modules/test-plugin')), false)
  assert.equal(restarted.list().plugins.find(row => row.id === 'test-plugin').installed, false)
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

// ---------------------------------------------------------------------------
// 模块图探测（只读）：访客里到底有几份 dsh-tools
//
// 判据只有一条：**不同真实路径数**（distinctRealpaths）。同一个真实目录被多个链接引用多少次
// 都只算一份（链接不改变模块身份）；只有存在两份**不同的真实副本**时，`dsh-tools` 的调度器
// Symbol 才会出现两个不相等的身份，`ctx.tools[调度器 Symbol]` 取到 undefined，
// 之后每一次工具调用都在 `.prepare` 上抛错。
// ---------------------------------------------------------------------------

/** 建一个只含目录布局的临时 root（探测只读目录，不需要任何 package.json 清单）。 */
function graphRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-graph-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  return root
}

/** 在 root 的相对路径下造一个真实的 dsh-tools 包目录，返回它的绝对路径。 */
function toolsCopy(root, relative) {
  const target = path.join(root, relative, '@deepseek-ai/dsh-tools')
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh-tools', version: '0.1.5-rc.2' }))
  return target
}

/** 让 root 的相对路径成为指向 [target] 的 dsh-tools 链接（Windows 上用 junction）。 */
function toolsLink(root, relative, target) {
  const link = path.join(root, relative, '@deepseek-ai/dsh-tools')
  fs.mkdirSync(path.dirname(link), { recursive: true })
  fs.symlinkSync(target, link, 'junction')
  return link
}

/** 目录快照：形态、mtime、大小与链接目标；用来断言探测没有改动任何东西。 */
function snapshot(directory) {
  const rows = []
  const visit = current => {
    const items = fs.readdirSync(current, { withFileTypes: true }).sort((left, right) => (left.name < right.name ? -1 : 1))
    for (const item of items) {
      const full = path.join(current, item.name)
      // 快照自己也用 lstat：链接只记录目标，不进入（否则快照会跟着链接走出临时 root）。
      const stats = fs.lstatSync(full)
      const kind = stats.isSymbolicLink() ? 'link->' + fs.readlinkSync(full) : stats.isDirectory() ? 'dir' : 'file'
      rows.push([path.relative(directory, full).split(path.sep).join('/'), kind, String(stats.mtimeMs), String(stats.size)].join('|'))
      if (stats.isDirectory()) visit(full)
    }
  }
  visit(directory)
  return rows
}

test('模块图探测：同一个真实目录被多处链接引用只算一份', t => {
  const root = graphRoot(t)
  // 运行时本体：pnpm 隔离布局下的那一份真实目录。
  const runtime = toolsCopy(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-tools@0.1.5-rc.2/node_modules')
  // 三处链接（顶层入口、$DSH_HOME 的 profile、插件版本目录）都指向同一份。
  toolsLink(root, 'opt/dsh/node_modules', runtime)
  toolsLink(root, 'root/.dsh/profiles/node_modules', runtime)
  toolsLink(root, 'root/.dsh-mobile/plugin-manager/versions/12345678-1234-1234-1234-123456789abc/node_modules', runtime)

  // 出现 4 次但真实路径只有一个：链接不改变模块身份，因而不是重复副本。
  assert.deepEqual(createManager(root).scanRuntimeGraph(), { total: 4, realCopies: 1, links: 3, distinctRealpaths: 1 })
})

test('模块图探测：两个不同的真实目录即两份模块实例', t => {
  const root = graphRoot(t)
  // 运行时本体。
  toolsCopy(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-tools@0.1.5-rc.2/node_modules')
  // 插件版本目录里 npm 装出来的真实副本（嵌套形态）：第二个不同的真实路径。
  toolsCopy(root, 'root/.dsh-mobile/plugin-manager/versions/12345678-1234-1234-1234-123456789abc/node_modules/demo-plugin/node_modules')

  // 判据 > 1：两份物理副本 ⇒ Symbol 身份分裂 ⇒ 所有工具调用失败。
  assert.deepEqual(createManager(root).scanRuntimeGraph(), { total: 2, realCopies: 2, links: 0, distinctRealpaths: 2 })
})

test('模块图探测：候选根不存在时返回零计数且不报错', t => {
  const root = graphRoot(t)
  assert.deepEqual(createManager(root).scanRuntimeGraph(), { total: 0, realCopies: 0, links: 0, distinctRealpaths: 0 })
  // 候选根存在、但里面没有 dsh-tools（只有作用域目录）时同样是零计数。
  fs.mkdirSync(path.join(root, 'opt/dsh/node_modules/@deepseek-ai'), { recursive: true })
  fs.mkdirSync(path.join(root, 'opt/dsh/plugins'), { recursive: true })
  fs.mkdirSync(path.join(root, 'root/.dsh/profiles/web/node_modules'), { recursive: true })
  assert.deepEqual(createManager(root).scanRuntimeGraph(), { total: 0, realCopies: 0, links: 0, distinctRealpaths: 0 })
})

test('模块图探测只读：不改动任何文件，也不动悬空链接', t => {
  const root = graphRoot(t)
  const runtime = toolsCopy(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-tools@0.1.5-rc.2/node_modules')
  toolsLink(root, 'opt/dsh/node_modules', runtime)
  toolsCopy(root, 'root/.dsh-mobile/plugin-manager/versions/12345678-1234-1234-1234-123456789abc/node_modules/demo-plugin/node_modules')
  // 悬空链接（指向已不存在的旧运行时路径）：只计出现次数，不算第二份副本，更不该被"顺手修好"。
  const dangling = toolsLink(
    root,
    'root/.dsh/profiles/node_modules',
    path.join(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-tools@0.1.5-rc.1/node_modules/@deepseek-ai/dsh-tools'),
  )

  const before = snapshot(root)
  const graph = createManager(root).scanRuntimeGraph()
  assert.equal(graph.distinctRealpaths, 2)
  assert.equal(graph.links, 2)
  assert.equal(fs.lstatSync(dangling).isSymbolicLink(), true)
  // 快照逐项相同：形态、mtime 与大小都没变，是"只读"的直接证据。
  assert.deepEqual(snapshot(root), before)
})

test('模块图探测不跟随指向候选根之外的符号链接目录', t => {
  const root = graphRoot(t)
  // 候选根之外的真实副本：任何计数都不该包含它。
  toolsCopy(root, 'tmp-outside')
  toolsCopy(root, 'tmp-outside/nested/node_modules')

  // 形态一：候选根内的普通链接指向外面。
  fs.mkdirSync(path.join(root, 'opt/dsh/plugins'), { recursive: true })
  fs.symlinkSync(path.join(root, 'tmp-outside'), path.join(root, 'opt/dsh/plugins/linked-plugin'), 'junction')
  // 形态二：@deepseek-ai 作用域目录本身是指向外面的链接。
  fs.mkdirSync(path.join(root, 'opt/dsh/node_modules'), { recursive: true })
  fs.symlinkSync(path.join(root, 'tmp-outside'), path.join(root, 'opt/dsh/node_modules/@deepseek-ai'), 'junction')
  // 形态三：候选根本身是指向外面的链接。
  fs.mkdirSync(path.join(root, 'root/.dsh/profiles'), { recursive: true })
  fs.symlinkSync(path.join(root, 'tmp-outside/nested/node_modules'), path.join(root, 'root/.dsh/profiles/node_modules'), 'junction')

  assert.deepEqual(createManager(root).scanRuntimeGraph(), { total: 0, realCopies: 0, links: 0, distinctRealpaths: 0 })
  // 外面那份副本原样保留：探测没有跟随，也没有改动它。
  assert.equal(fs.lstatSync(path.join(root, 'tmp-outside/@deepseek-ai/dsh-tools')).isSymbolicLink(), false)
})

test('模块图探测的预算按真实规模设定：截断会少算真实副本，等于让判据失效', t => {
  // 真实的 pnpm 虚拟store 有几百个条目（本仓库自身 571 个），而每个声明了该依赖的条目都会带一条
  // dsh-tools 链接。预算一旦截断，计数就会偏小 —— 而「少算一份真实副本」正是唯一会让判据失效的
  // 错误，因此探测不能沿用修复路径那套 256 目录预算（实测 400 个 store 条目就会漏掉第二份副本）。
  assert.equal(GRAPH_LIMITS.depth, 4)
  assert.ok(GRAPH_LIMITS.directories >= 1024, `目录预算过小：${GRAPH_LIMITS.directories}`)
  assert.ok(GRAPH_LIMITS.entries >= 8192, `条目预算过小：${GRAPH_LIMITS.entries}`)

  // 行为侧再钉一次：几十个条目的链接全部计入，不会在中途停下。
  const root = graphRoot(t)
  const runtime = toolsCopy(root, 'opt/dsh/node_modules/.pnpm/@deepseek-ai+dsh-tools@0.1.5-rc.2/node_modules')
  const entries = 40
  for (let index = 0; index < entries; index += 1) {
    toolsLink(root, `opt/dsh/node_modules/.pnpm/dep-${String(index).padStart(3, '0')}@1.0.0/node_modules`, runtime)
  }
  const graph = createManager(root).scanRuntimeGraph()
  assert.equal(graph.links, entries)
  assert.equal(graph.realCopies, 1)
  assert.equal(graph.distinctRealpaths, 1)
})

// ---------------------------------------------------------------------------
// 受控导入通道：npm 包名 / https 直链 / git+https（与更新共用同一条事务路径）
// ---------------------------------------------------------------------------

/** 往 staging 里放一个声明了 dsh 补丁的插件包；返回包目录。 */
function stagePlugin(directory, name, version, files = null) {
  const target = path.join(directory, 'node_modules', name)
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name, version, dsh: { bundle: { patch: './cordis.patch.json' } } }))
  fs.writeFileSync(path.join(target, 'cordis.patch.json'), JSON.stringify([{ insert: [{ id: 'child', name: `${name}/child` }] }]))
  for (const [file, content] of Object.entries(files ?? {})) {
    const full = path.join(target, file)
    fs.mkdirSync(path.dirname(full), { recursive: true })
    fs.writeFileSync(full, content)
  }
  return target
}

/** 不含 dsh 补丁的普通依赖（导入时无法据此推导出插件包名）。 */
function stageLibrary(directory, name) {
  const target = path.join(directory, 'node_modules', name)
  fs.mkdirSync(target, { recursive: true })
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name, version: '1.0.0' }))
  return target
}

/** 固定包名的安装器：记下每次收到的来源字符串（包名或地址）。 */
function pluginInstaller(name, version, seen = null, files = null) {
  return (spec, directory) => {
    if (seen !== null) seen.push(spec)
    return stagePlugin(directory, name, version, files)
  }
}

function capture(run) {
  try { run(); return null } catch (error) { return error }
}

function dataFile(root, id, relative) {
  return path.join(fs.realpathSync(path.join(root, 'opt/dsh/node_modules', id)), relative)
}

test('导入 npm 包名：新包进入清单且重复导入不产生重复条目', t => {
  const { manager, profile } = fixture(t, pluginInstaller('extra-plugin', '2.0.0'))
  const first = manager.importPackage('extra-plugin')
  const imported = first.plugins.find(row => row.id === 'extra-plugin')
  assert.equal(imported.version, '2.0.0')
  assert.equal(imported.enabled, true)
  assert.equal(imported.rollback, null)
  const value = JSON.parse(fs.readFileSync(profile))
  assert.deepEqual(value.dsh.profile.bundles, ['@deepseek-ai/dsh-base', 'test-plugin', 'extra-plugin'])
  assert.deepEqual(value.dshMobile.bundleOrder, ['@deepseek-ai/dsh-base', 'test-plugin', 'extra-plugin'])
  assert.deepEqual(value.dshMobile.disabledBundles, [])
  // 重复导入同一个包等同更新：清单里不得出现第二条，打包顺序也不重排。
  manager.importPackage('extra-plugin')
  const after = JSON.parse(fs.readFileSync(profile))
  assert.deepEqual(after.dsh.profile.bundles, value.dsh.profile.bundles)
  assert.deepEqual(after.dshMobile.bundleOrder, value.dshMobile.bundleOrder)
  assert.equal(manager.list().plugins.filter(row => row.id === 'extra-plugin').length, 1)
})

test('导入 https 直链：原地址交给 npm，包名从安装结果推导，返回值不回传地址', t => {
  const seen = []
  const { manager, profile } = fixture(t, pluginInstaller('linked-plugin', '3.0.0', seen))
  const result = manager.importPackage('https://example.com/linked-plugin-3.0.0.tgz')
  assert.deepEqual(seen, ['https://example.com/linked-plugin-3.0.0.tgz'])
  assert.equal(result.plugins.find(row => row.id === 'linked-plugin').version, '3.0.0')
  assert.ok(JSON.parse(fs.readFileSync(profile)).dsh.profile.bundles.includes('linked-plugin'))
  // 返回值沿用 list() 投影：不含地址全文、路径或凭据。
  assert.equal(JSON.stringify(result).includes('example.com'), false)
})

test('导入清单里已有的包等同更新：保留数据目录且不重复登记', t => {
  const { manager, root, profile } = fixture(t, pluginInstaller('test-plugin', '2.0.0'))
  fs.mkdirSync(path.join(root, 'opt/dsh/node_modules/test-plugin/data'), { recursive: true })
  fs.writeFileSync(path.join(root, 'opt/dsh/node_modules/test-plugin/data/settings.json'), '{"keep":true}')
  const result = manager.importPackage('test-plugin')
  assert.equal(result.plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
  assert.equal(fs.readFileSync(dataFile(root, 'test-plugin', 'data/settings.json'), 'utf8'), '{"keep":true}')
  assert.deepEqual(JSON.parse(fs.readFileSync(profile)).dsh.profile.bundles, ['@deepseek-ai/dsh-base', 'test-plugin'])
})

test('导入 git 地址：git 确实没有与探测不可判定给出不同受控错误码', t => {
  const seen = []
  const missing = fixture(t, pluginInstaller('git-plugin', '1.0.0', seen), () => 'missing')
  assert.throws(() => missing.manager.importPackage('git+https://github.com/u/r.git#v1.2.3'), /PLUGIN_GIT_MISSING/)
  assert.deepEqual(seen, [])
  // 暂存目录整体清掉，只留下空的版本父目录（不能留下半装好的包）。
  assert.deepEqual(fs.readdirSync(path.join(missing.root, 'root/.dsh-mobile/plugin-manager/versions')), [])
  assert.equal(JSON.parse(fs.readFileSync(missing.profile, 'utf8')).dsh.profile.bundles.includes('git-plugin'), false)

  const unverified = fixture(t, pluginInstaller('git-plugin', '1.0.0', seen), () => 'unverified')
  assert.throws(() => unverified.manager.importPackage('git+https://github.com/u/r.git#v1.2.3'), /PLUGIN_GIT_UNVERIFIED/)
  assert.deepEqual(seen, [])

  const available = fixture(t, pluginInstaller('git-plugin', '1.0.0', seen), () => 'available')
  assert.equal(available.manager.importPackage('git+https://github.com/u/r.git#v1.2.3').plugins.find(row => row.id === 'git-plugin').version, '1.0.0')
  assert.deepEqual(seen, ['git+https://github.com/u/r.git#v1.2.3'])
})

test('导入来源校验：只接受包名、https 直链与 git+https，拒绝时一律不回显', t => {
  assert.deepEqual(validateImportSource('@scope/foo'), { kind: 'npm', source: '@scope/foo', name: '@scope/foo' })
  assert.deepEqual(validateImportSource('foo'), { kind: 'npm', source: 'foo', name: 'foo' })
  assert.deepEqual(validateImportSource('https://example.com/x.tgz'), { kind: 'url', source: 'https://example.com/x.tgz', name: null })
  assert.deepEqual(validateImportSource('git+https://github.com/u/r.git#v1.2.3'), { kind: 'git', source: 'git+https://github.com/u/r.git#v1.2.3', name: null })
  const rejected = [
    '-foo',
    'http://example.com/x.tgz',
    'git://example.com/x.git',
    'ssh://git@example.com/x.git',
    'file:///etc/passwd',
    'data:text/plain,x',
    'https://user:pass@example.com/x.tgz',
    'https://example.com/../x.tgz',
    'https://example.com/%2e%2e/x.tgz',
    'foo bar',
    'foo"bar',
    'foo<bar>',
    'foo`bar',
    'foo\nbar',
    '',
    `foo${'a'.repeat(520)}`,
    `https://example.com/${'a'.repeat(520)}.tgz`,
  ]
  for (const source of rejected) {
    const error = capture(() => validateImportSource(source))
    assert.ok(error !== null, `应当拒绝：${JSON.stringify(source.slice(0, 24))}`)
    assert.equal(error.message, 'PLUGIN_SOURCE_INVALID')
    // 被拒内容可能含凭据或路径：错误里不得带上它，连 detail 都不该有。
    assert.equal(error.detail, undefined)
    assert.equal(String(error.message).includes('example.com'), false)
  }
  // 探测函数的结论必须落在三态之内（可用 / 确实没有 / 探测不了），且不抛异常。
  assert.ok(['available', 'missing', 'unverified'].includes(gitAvailability()))
})

test('导入受保护包或无法推导包名时受控失败，且不改动清单', t => {
  const { manager, profile } = fixture(t, (spec, directory) => stageLibrary(directory, 'library-only'))
  const before = fs.readFileSync(profile, 'utf8')
  assert.throws(() => manager.importPackage('@deepseek-ai/dsh-base'), /PLUGIN_PROTECTED/)
  assert.throws(() => manager.importPackage('https://example.com/x.tgz', '@deepseek-ai/dsh-base'), /PLUGIN_PROTECTED/)
  assert.throws(() => manager.importPackage('https://example.com/x.tgz', '../evil'), /PLUGIN_INPUT_INVALID/)
  // 安装结果里没有声明 dsh 补丁的包：推导不出插件包名，拒绝而不是猜一个。
  assert.throws(() => manager.importPackage('https://example.com/x.tgz'), /PLUGIN_IMPORT_UNRESOLVED/)
  assert.throws(() => manager.importPackage('https://example.com/x.tgz', 'other-name'), /PLUGIN_IMPORT_UNRESOLVED/)
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
})

test('导入结果里出现两个候选插件包时拒绝自动推导', t => {
  const { manager, profile } = fixture(t, (spec, directory) => {
    stagePlugin(directory, 'first-plugin', '1.0.0')
    stageLibrary(directory, 'shadow-library')
    // 第二个候选：同为声明了 dsh 补丁的插件包，且都不在清单里 —— 无法判断用户要装哪个。
    const second = stagePlugin(directory, 'second-plugin', '1.0.0')
    return second
  })
  const before = fs.readFileSync(profile, 'utf8')
  assert.throws(() => manager.importPackage('https://example.com/bundle.tgz'), /PLUGIN_IMPORT_UNRESOLVED/)
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
})

// ---------------------------------------------------------------------------
// 插件数据目录：更新/导入前后保留（不跟随符号链接、有累计上限）
// ---------------------------------------------------------------------------

test('更新保留插件数据目录：内容与嵌套子目录一致，新包自带文件不被误删', t => {
  const files = { 'data/shipped-by-new.json': '{"fromNew":true}' }
  const { manager, root } = fixture(t, pluginInstaller('test-plugin', '2.0.0', null, files))
  const old = path.join(root, 'opt/dsh/node_modules/test-plugin')
  const payload = {
    'data/settings.json': '{"keep":1}',
    'data/nested/deep/notes.txt': 'nested',
    'data/shipped-by-new.json': '{"fromNew":true}',
    'config/state.json': '{"c":2}',
    '.config/hidden.json': '{"h":3}',
    'storage/cache.bin': 'cache',
  }
  for (const [file, content] of Object.entries(payload)) {
    const full = path.join(old, file)
    fs.mkdirSync(path.dirname(full), { recursive: true })
    fs.writeFileSync(full, content)
  }
  const result = manager.update('test-plugin')
  assert.equal(result.plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
  const bytes = Object.values(payload).reduce((total, content) => total + Buffer.byteLength(content), 0)
  assert.deepEqual(result.data, { directories: 4, files: 6, bytes })
  for (const [file, content] of Object.entries(payload)) {
    assert.equal(fs.readFileSync(dataFile(root, 'test-plugin', file), 'utf8'), content, file)
  }
})

test('数据目录超限时受控失败且旧版本完好', t => {
  const { manager, root } = fixture(t, pluginInstaller('test-plugin', '2.0.0'))
  const old = path.join(root, 'opt/dsh/node_modules/test-plugin')
  fs.mkdirSync(path.join(old, 'data'))
  // 16 MiB 是约定上限；写超一个字节，验证判据确实按累计大小而不是按单个目录判断。
  fs.writeFileSync(path.join(old, 'data/big.bin'), Buffer.alloc(16 * 1024 * 1024 + 1))
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_DATA_TOO_LARGE/)
  assert.equal(manager.list().plugins.find(row => row.id === 'test-plugin').version, '1.0.0')
  assert.equal(fs.lstatSync(old).isSymbolicLink(), false)
  assert.equal(fs.existsSync(path.join(root, 'root/.dsh-mobile/plugin-manager/transaction.json')), false)
  assert.deepEqual(fs.readdirSync(path.join(root, 'root/.dsh-mobile/plugin-manager/versions')), [])
})

test('数据目录里有符号链接时受控失败，旧版本完好', t => {
  const { manager, root } = fixture(t, pluginInstaller('test-plugin', '2.0.0'))
  const old = path.join(root, 'opt/dsh/node_modules/test-plugin')
  const outside = path.join(root, 'outside')
  fs.mkdirSync(path.join(old, 'data'), { recursive: true })
  fs.mkdirSync(outside)
  fs.writeFileSync(path.join(old, 'data/ok.json'), '{"ok":true}')
  fs.symlinkSync(outside, path.join(old, 'data/link'), 'junction')
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_DATA_UNSAFE/)
  assert.equal(manager.list().plugins.find(row => row.id === 'test-plugin').version, '1.0.0')
  assert.equal(fs.lstatSync(old).isSymbolicLink(), false)
  assert.equal(fs.readFileSync(path.join(old, 'data/ok.json'), 'utf8'), '{"ok":true}')
  // 数据目录本身是链接：同样不接受（复制会把外面的内容带进安装目录）。
  fs.rmSync(path.join(old, 'data'), { recursive: true, force: true })
  fs.symlinkSync(outside, path.join(old, 'data'), 'junction')
  assert.throws(() => manager.update('test-plugin'), /PLUGIN_DATA_UNSAFE/)
  assert.equal(manager.list().plugins.find(row => row.id === 'test-plugin').version, '1.0.0')
})

test('旧包没有数据目录时正常更新，且不误报错误', t => {
  const { manager } = fixture(t, pluginInstaller('test-plugin', '2.0.0'))
  const result = manager.update('test-plugin')
  assert.equal(result.plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
  assert.deepEqual(result.data, { directories: 0, files: 0, bytes: 0 })
})

// ---------------------------------------------------------------------------
// 启停状态跨运行时升级：自身快照 + 清单被重建时幂等回填
// ---------------------------------------------------------------------------

const stateFileOf = root => path.join(root, 'root/.dsh-mobile/plugin-manager/state-snapshot.json')
const previousFileOf = root => path.join(root, 'root/.dsh-mobile/plugin-manager/previous.json')

/** 模拟运行时升级：profiles 清单与 launcher patch 不在保留区，运行时按 --mobile-profile 重建清单。 */
function simulateRuntimeUpgrade(root, profile, bundles = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']) {
  fs.rmSync(profile)
  fs.writeFileSync(profile, JSON.stringify({ dsh: { profile: { bundles } } }))
  fs.rmSync(path.join(root, 'root/.dsh-mobile/launcher-plugins.patch.json'), { force: true })
}

test('运行时升级后幂等回填启停状态：包级与子插件级都恢复', t => {
  const { manager, root, profile } = fixture(t)
  manager.setChildEnabled('test-plugin', 'child', false)
  manager.setEnabled('test-plugin', false)
  simulateRuntimeUpgrade(root, profile)
  const restarted = createManager(root, undefined, JSON.parse)
  const state = restarted.recover()
  assert.equal(state.restored, true)
  assert.equal(state.reason, 'merged')
  assert.equal(state.plugins, 1)
  assert.equal(state.children, 1)
  const plugin = restarted.list().plugins.find(row => row.id === 'test-plugin')
  assert.equal(plugin.enabled, false)
  assert.equal(plugin.children.find(row => row.id === 'child').enabled, false)
  // 幂等：清单里已经有我们的 token，第二次恢复什么都不写，也不产生重复条目。
  const before = fs.readFileSync(profile, 'utf8')
  assert.deepEqual(restarted.recover(), { restored: false, reason: 'current' })
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
  const value = JSON.parse(before)
  assert.deepEqual(value.dsh.profile.bundles, ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'])
  assert.deepEqual(value.dshMobile.disabledBundles, ['test-plugin'])
})

test('清单里已有用户选择时绝不覆盖（升级后用户先选过）', t => {
  const { manager, root, profile } = fixture(t)
  manager.setEnabled('test-plugin', false)
  fs.rmSync(profile)
  // 用户在新运行时里自己重新启用了 test-plugin，并且没有动过子插件开关。
  fs.writeFileSync(profile, JSON.stringify({ dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', 'test-plugin'] } }, dshMobile: { disabledBundles: [] } }))
  const restarted = createManager(root, undefined, JSON.parse)
  const state = restarted.recover()
  assert.equal(state.plugins, 0)
  const value = JSON.parse(fs.readFileSync(profile, 'utf8'))
  assert.deepEqual(value.dsh.profile.bundles, ['@deepseek-ai/dsh-base', 'test-plugin'])
  assert.deepEqual(value.dshMobile.disabledBundles, [])
  assert.equal(restarted.list().plugins.find(row => row.id === 'test-plugin').enabled, true)
})

test('清单文件整个缺失时按快照重建，重建结果仍受后续操作保护', t => {
  const { manager, root, profile } = fixture(t)
  manager.setEnabled('test-plugin', false)
  fs.rmSync(profile)
  const restarted = createManager(root, undefined, JSON.parse)
  const state = restarted.recover()
  assert.equal(state.restored, true)
  assert.equal(state.reason, 'rebuilt')
  assert.deepEqual(JSON.parse(fs.readFileSync(profile, 'utf8')).dshMobile.disabledBundles, ['test-plugin'])
  assert.equal(restarted.list().plugins.find(row => row.id === 'test-plugin').enabled, false)
  // 重建后 token 仍然一致：再恢复一次不再改动清单。
  assert.equal(restarted.recover().reason, 'current')
})

test('快照损坏或清单损坏时受控处理：不崩、不清空用户数据', t => {
  const { manager, root, profile } = fixture(t)
  const broken = [
    '{not json',
    JSON.stringify({ version: 1, token: 'not-a-token' }),
    '[]',
    JSON.stringify({ version: 1, token: '12345678-1234-1234-1234-123456789abc', bundles: ['ok', '../etc'], bundleOrder: [], disabledBundles: [] }),
  ]
  for (const content of broken) {
    fs.mkdirSync(path.dirname(stateFileOf(root)), { recursive: true })
    fs.writeFileSync(stateFileOf(root), content)
    const before = fs.readFileSync(profile, 'utf8')
    assert.deepEqual(createManager(root, undefined, JSON.parse).recover(), { restored: false, reason: 'missing' })
    assert.equal(fs.readFileSync(profile, 'utf8'), before)
    assert.equal(manager.list().plugins.length, 2)
  }
  // 快照可用但清单损坏：只报"没回填"，不覆盖、不清空那份损坏的清单。
  fs.writeFileSync(stateFileOf(root), JSON.stringify({ version: 1, token: '12345678-1234-1234-1234-123456789abc', bundles: ['test-plugin'], bundleOrder: ['test-plugin'], disabledBundles: [], children: [] }))
  fs.writeFileSync(profile, '{broken')
  assert.deepEqual(manager.recover(), { restored: false, reason: 'invalid' })
  assert.equal(fs.readFileSync(profile, 'utf8'), '{broken')
})

test('list 保持只读：既不写快照也不回填清单', t => {
  const { manager, root, profile } = fixture(t)
  const before = fs.readFileSync(profile, 'utf8')
  manager.list()
  assert.equal(fs.existsSync(stateFileOf(root)), false)
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
  // 没有快照时 recover 只做事务恢复，不写清单。
  assert.deepEqual(manager.recover(), { restored: false, reason: 'missing' })
  assert.equal(fs.readFileSync(profile, 'utf8'), before)
})

// ---------------------------------------------------------------------------
// 版本回滚：每个插件保留上一版一份，滚动可再滚动
// ---------------------------------------------------------------------------

test('更新后可回滚到上一版，回滚本身可再回滚', t => {
  let round = 0
  const versions = ['2.0.0', '3.0.0']
  const { manager, root } = fixture(t, (spec, directory) => stagePlugin(directory, 'test-plugin', versions[Math.min(round++, versions.length - 1)]))
  const pluginOf = () => manager.list().plugins.find(row => row.id === 'test-plugin')
  assert.equal(manager.update('test-plugin').plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
  // 首次更新的旧版是运行时自带目录（不在 versions/ 里）：没有版本目录可回滚，如实报 null。
  assert.equal(pluginOf().rollback, null)
  assert.equal(manager.update('test-plugin').plugins.find(row => row.id === 'test-plugin').version, '3.0.0')
  assert.equal(pluginOf().rollback, '2.0.0')
  assert.equal(manager.rollback('test-plugin').plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
  // 刚被换下的那一版成为新的"上一版"：回滚可再回滚。
  assert.equal(pluginOf().rollback, '3.0.0')
  assert.equal(manager.rollback('test-plugin').plugins.find(row => row.id === 'test-plugin').version, '3.0.0')
  assert.equal(pluginOf().rollback, '2.0.0')
  assert.equal(fs.existsSync(path.join(root, 'root/.dsh-mobile/plugin-manager/transaction.json')), false)
})

test('回滚结果不被 repair / recover 改回去', t => {
  let round = 0
  const { manager } = fixture(t, (spec, directory) => stagePlugin(directory, 'test-plugin', round++ === 0 ? '2.0.0' : '3.0.0'))
  manager.update('test-plugin')
  manager.update('test-plugin')
  manager.rollback('test-plugin')
  const pluginOf = () => manager.list().plugins.find(row => row.id === 'test-plugin')
  assert.equal(pluginOf().version, '2.0.0')
  assert.equal(pluginOf().rollback, '3.0.0')
  // repair 只对齐 @deepseek-ai/* 运行时副本，recover 只管未完成事务：都不动已提交的回滚。
  manager.repair()
  assert.equal(pluginOf().version, '2.0.0')
  assert.equal(manager.recover().reason, 'current')
  assert.equal(pluginOf().version, '2.0.0')
  assert.equal(pluginOf().rollback, '3.0.0')
})

test('没有上一版、登记损坏或上一版目录被清掉时都按不可回滚处理', t => {
  const { manager, root } = fixture(t)
  assert.throws(() => manager.rollback('test-plugin'), /PLUGIN_ROLLBACK_UNAVAILABLE/)
  fs.mkdirSync(path.dirname(previousFileOf(root)), { recursive: true })
  fs.writeFileSync(previousFileOf(root), '{not json')
  assert.equal(manager.list().plugins.find(row => row.id === 'test-plugin').rollback, null)
  assert.throws(() => manager.rollback('test-plugin'), /PLUGIN_ROLLBACK_UNAVAILABLE/)
  assert.equal(manager.list().plugins.find(row => row.id === 'test-plugin').version, '1.0.0')

  let round = 0
  const other = fixture(t, (spec, directory) => stagePlugin(directory, 'test-plugin', round++ === 0 ? '2.0.0' : '3.0.0'))
  other.manager.update('test-plugin')
  other.manager.update('test-plugin')
  assert.equal(other.manager.list().plugins.find(row => row.id === 'test-plugin').rollback, '2.0.0')
  const record = JSON.parse(fs.readFileSync(previousFileOf(other.root), 'utf8'))
  fs.rmSync(path.join(other.root, 'root/.dsh-mobile/plugin-manager/versions', record.entries[0].transaction), { recursive: true, force: true })
  assert.equal(other.manager.list().plugins.find(row => row.id === 'test-plugin').rollback, null)
  assert.throws(() => other.manager.rollback('test-plugin'), /PLUGIN_ROLLBACK_UNAVAILABLE/)
  assert.equal(other.manager.list().plugins.find(row => row.id === 'test-plugin').version, '3.0.0')
})

test('回滚受保护包与未安装包一律拒绝', t => {
  const { manager } = fixture(t)
  assert.throws(() => manager.rollback('@deepseek-ai/dsh-base'), /PLUGIN_PROTECTED/)
  assert.throws(() => manager.rollback('react'), /PLUGIN_PROTECTED/)
  assert.throws(() => manager.rollback('absent-plugin'), /PLUGIN_NOT_FOUND/)
  assert.throws(() => manager.rollback('../evil'), /PLUGIN_INPUT_INVALID/)
})

test('回滚失败时先前状态完好且事务记录被清理', t => {
  let round = 0
  const { manager, root } = fixture(t, (spec, directory) => stagePlugin(directory, 'test-plugin', round++ === 0 ? '2.0.0' : '3.0.0'))
  manager.update('test-plugin')
  manager.update('test-plugin')
  const pluginOf = () => manager.list().plugins.find(row => row.id === 'test-plugin')
  assert.equal(pluginOf().version, '3.0.0')
  const target = path.join(root, 'opt/dsh/node_modules/test-plugin')
  const original = fs.symlinkSync
  t.after(() => { fs.symlinkSync = original })
  fs.symlinkSync = (source, dest, type) => {
    if (dest === target) throw new Error('PLUGIN_LINK_UNSUPPORTED')
    return original(source, dest, type)
  }
  assert.throws(() => manager.rollback('test-plugin'), /PLUGIN_LINK_UNSUPPORTED/)
  fs.symlinkSync = original
  assert.equal(pluginOf().version, '3.0.0')
  assert.equal(fs.lstatSync(target).isSymbolicLink(), true)
  assert.equal(fs.existsSync(path.join(root, 'root/.dsh-mobile/plugin-manager/transaction.json')), false)
  // 失败的回滚没有改动登记：这一版仍然可以回滚过去。
  assert.equal(pluginOf().rollback, '2.0.0')
  assert.equal(manager.rollback('test-plugin').plugins.find(row => row.id === 'test-plugin').version, '2.0.0')
})

test('导入与回滚都会刷新状态快照，供下次运行时升级回填', t => {
  let round = 0
  const { manager, root } = fixture(t, (spec, directory) => stagePlugin(directory, 'test-plugin', round++ === 0 ? '2.0.0' : '3.0.0'))
  manager.update('test-plugin')
  manager.update('test-plugin')
  manager.importPackage('test-plugin')
  const saved = JSON.parse(fs.readFileSync(stateFileOf(root), 'utf8'))
  assert.deepEqual(saved.bundles, ['@deepseek-ai/dsh-base', 'test-plugin'])
  assert.equal(typeof saved.token, 'string')
  manager.rollback('test-plugin')
  assert.deepEqual(JSON.parse(fs.readFileSync(stateFileOf(root), 'utf8')).bundles, ['@deepseek-ai/dsh-base', 'test-plugin'])
})
