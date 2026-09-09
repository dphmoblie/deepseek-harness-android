'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')
const { createRequire } = require('node:module')

const NAME = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/
const VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$/
const officialPackage = name => name.startsWith('@deepseek-ai/') || name === '@deepseek-harness/dsh-mobile-shizuku'
const protectedPackage = name => officialPackage(name) || ['react', 'react-dom', 'cordis'].includes(name)
function fail(code) { throw new Error(code) }
function within(base, value) {
  const relative = path.relative(base, value)
  return relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative)
}
function validName(name) {
  if (typeof name !== 'string' || name.length > 214 || !NAME.test(name) || name.includes('..')) fail('PLUGIN_INPUT_INVALID')
  return name
}

// 管理器只解析包清单，不导入插件，不启动 Harness；插件启动失败时仍可恢复配置。
function createManager(rootDirectory, installPackage, parseYaml) {
  const root = fs.realpathSync(rootDirectory)
  const profile = path.join(root, 'root/.dsh/profiles/web/package.json')
  const home = path.join(root, 'root/.dsh-mobile/plugin-manager')
  const journal = path.join(home, 'transaction.json')
  const modulesRoot = path.join(root, 'opt/dsh/node_modules')
  const fallbackModules = path.join(root, 'root/.dsh/profiles/node_modules')
  const modules = [
    path.join(root, 'opt/dsh/node_modules'),
    path.join(root, 'root/.dsh/profiles/web/node_modules'),
    path.join(root, 'root/.dsh/profiles/node_modules'),
  ]
  const exists = value => { try { fs.lstatSync(value); return true } catch (error) { if (error.code === 'ENOENT') return false; throw error } }
  function safe(value) {
    if (!within(root, path.resolve(value))) fail('PLUGIN_PATH_INVALID')
    let parent = value
    while (!exists(parent)) parent = path.dirname(parent)
    if (!within(root, fs.realpathSync(parent))) fail('PLUGIN_PATH_INVALID')
    return value
  }
  // 按实际 dsh 安装锚点解析，覆盖 pnpm 隔离布局，不能只查看顶层 node_modules。
  const installationAnchor = path.join(modulesRoot, '@deepseek-ai/dsh/package.json')
  if (exists(installationAnchor)) {
    safe(installationAnchor)
    const anchor = fs.realpathSync(installationAnchor)
    if (!within(modulesRoot, anchor)) fail('PLUGIN_PATH_INVALID')
    const search = createRequire(anchor).resolve.paths('dsh-mobile-package-probe') ?? []
    modules.unshift(...search.filter(directory => within(modulesRoot, directory) && !modules.includes(directory)))
  }
  function read(file, maximum = 1024 * 1024) {
    safe(file)
    if (!fs.statSync(file).isFile() || fs.statSync(file).size > maximum) fail('PLUGIN_CONFIG_INVALID')
    const value = JSON.parse(fs.readFileSync(file, 'utf8'))
    if (!value || typeof value !== 'object' || Array.isArray(value)) fail('PLUGIN_CONFIG_INVALID')
    return value
  }
  function atomic(file, value) {
    safe(path.dirname(file))
    // 写入配置只接受真实目录，避免通过祖先链接改写管理区之外的文件。
    let ancestor = path.dirname(file)
    while (ancestor !== root) {
      if (exists(ancestor) && fs.lstatSync(ancestor).isSymbolicLink()) fail('PLUGIN_PATH_INVALID')
      ancestor = path.dirname(ancestor)
    }
    fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 })
    if (exists(file) && !fs.lstatSync(file).isFile()) fail('PLUGIN_PATH_INVALID')
    const temporary = file + '.' + crypto.randomUUID() + '.tmp'
    const descriptor = fs.openSync(temporary, 'wx', 0o600)
    try {
      fs.writeFileSync(descriptor, JSON.stringify(value, null, 2) + '\n')
      fs.fsyncSync(descriptor)
    } finally { fs.closeSync(descriptor) }
    fs.renameSync(temporary, file)
  }
  function manifest() {
    const value = read(profile)
    const enabled = value.dsh?.profile?.bundles
    const disabled = value.dshMobile?.disabledBundles ?? []
    if (!Array.isArray(enabled) || !Array.isArray(disabled) || enabled.length + disabled.length > 32) fail('PLUGIN_CONFIG_INVALID')
    const names = [...new Set([...enabled, ...disabled].map(validName))]
    if (enabled.some(name => disabled.includes(name))) fail('PLUGIN_CONFIG_INVALID')
    return { value, enabled, disabled, names }
  }
  function resolvePackage(name) {
    for (const directory of modules) {
      const candidate = path.join(directory, validName(name))
      if (!exists(candidate)) continue
      safe(candidate)
      const resolved = fs.realpathSync(candidate)
      if (![...modules, home, path.join(root, 'opt/dsh/plugins')].some(base => within(base, resolved))) fail('PLUGIN_PATH_INVALID')
      return resolved
    }
    return null
  }
  function basicList() {
    const config = manifest()
    return { plugins: config.names.map(id => {
      const directory = resolvePackage(id)
      let version = null
      if (directory) {
        try {
          const pkg = read(path.join(directory, 'package.json'))
          if (pkg.name === id && typeof pkg.version === 'string' && pkg.version.length <= 64 && VERSION.test(pkg.version)) version = pkg.version
        } catch { /* 损坏插件仍保留禁用入口。 */ }
      }
      return { id, version, enabled: config.enabled.includes(id), protected: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'].includes(id), official: officialPackage(id), installed: version !== null }
    }) }
  }
  const overrideFile = path.join(root, 'root/.dsh-mobile/launcher-plugins.patch.json')
  const critical = new Set(['sandbox', 'sandbox-policy', 'bash-sandbox', 'approval', 'permission', 'fs-policy', 'credentials'])
  const entryId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)
  function overrides() {
    if (!exists(overrideFile)) return []
    safe(overrideFile)
    if (!fs.lstatSync(overrideFile).isFile() || fs.statSync(overrideFile).size > 65536) fail('PLUGIN_CONFIG_INVALID')
    const rows = JSON.parse(fs.readFileSync(overrideFile, 'utf8'))
    if (!Array.isArray(rows) || rows.length > 512 || rows.some(row => !entryId(row.id) || typeof row.disabled !== 'boolean' || Object.keys(row).some(key => !['id', 'disabled'].includes(key)))) fail('PLUGIN_CONFIG_INVALID')
    return rows
  }
  function loadPatches(file) {
    safe(file)
    if (!fs.statSync(file).isFile() || fs.statSync(file).size > 1024 * 1024) fail('PLUGIN_CONFIG_INVALID')
    // 仅使用运行时自带的 YAML 解析器；!!js 保留为数据，绝不执行表达式或导入插件。
    let parse = parseYaml
    if (!parse) {
      const boot = resolvePackage('@deepseek-ai/dsh-app-boot')
      if (!boot) fail('PLUGIN_CONFIG_INVALID')
      const coreRequire = createRequire(path.join(boot, 'package.json'))
      const yaml = coreRequire('js-yaml')
      const expression = new yaml.Type('tag:yaml.org,2002:js', { kind: 'scalar', construct: value => ({ __jsExpr: value }) })
      parse = text => yaml.load(text, { schema: yaml.JSON_SCHEMA.extend(expression) })
    }
    const rows = parse(fs.readFileSync(file, 'utf8'))
    if (!Array.isArray(rows) || rows.length > 512) fail('PLUGIN_CONFIG_INVALID')
    return rows
  }
  function catalog() {
    const groups = basicList().plugins
    const order = manifest().value.dshMobile?.bundleOrder ?? groups.map(group => group.id)
    if (!Array.isArray(order) || order.length > 32) fail('PLUGIN_CONFIG_INVALID')
    order.forEach(validName)
    groups.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id))
    const entries = new Map()
    let count = 0
    function insert(rows, owner, depth = 0, parent) {
      if (!Array.isArray(rows) || depth > 12) fail('PLUGIN_CONFIG_INVALID')
      for (const row of rows) {
        if (++count > 1024 || !row || typeof row !== 'object') fail('PLUGIN_CONFIG_INVALID')
        if (entryId(row.id)) {
          if (entries.has(row.id)) fail('PLUGIN_CONFIG_INVALID')
          entries.set(row.id, { id: row.id, name: typeof row.name === 'string' && /^[A-Za-z0-9@_./:-]{1,214}$/.test(row.name) ? row.name : row.id, enabled: row.disabled !== true, protected: critical.has(row.id), owner, parent, group: row.group === true })
        }
        if (row.group && Array.isArray(row.config)) insert(row.config, owner, depth + 1, row.id)
      }
    }
    function apply(rows, owner) {
      for (const row of rows) {
        if (!row || typeof row !== 'object') fail('PLUGIN_CONFIG_INVALID')
        if (row.insert) {
          if (!row.id || entries.get(row.id)?.group) insert(row.insert, owner, 0, row.id)
        } else if (entries.has(row.id)) {
          const target = entries.get(row.id)
          if (row.name && row.name !== target.name) continue
          if (typeof row.disabled === 'boolean') target.enabled = !row.disabled
          // 与上游一致：组的 config 是整体替换，来源归到实际声明子项的文件。
          if (target.group && Array.isArray(row.config)) {
            const remove = parent => {
              for (const entry of [...entries.values()]) if (entry.parent === parent) { remove(entry.id); entries.delete(entry.id) }
            }
            remove(row.id)
            insert(row.config, owner, 0, row.id)
          }
        }
      }
    }
    for (const group of groups) {
      group.file = 'cordis.patch.yml'
      group.children = []
      group.readable = true
      const before = new Map([...entries].map(([key, value]) => [key, { ...value }]))
      try {
        const directory = resolvePackage(group.id)
        if (!directory) fail('PLUGIN_NOT_FOUND')
        const pkg = read(path.join(directory, 'package.json'))
        const declared = pkg.dsh?.bundle?.patch
        if (typeof declared !== 'string' || declared.length > 160 || !/^(?:\.\/)?[A-Za-z0-9_./-]+\.(?:json|ya?ml)$/.test(declared)) fail('PLUGIN_CONFIG_INVALID')
        const file = path.resolve(directory, declared)
        if (!within(directory, file) || !within(directory, fs.realpathSync(safe(file)))) fail('PLUGIN_PATH_INVALID')
        group.file = declared.replace(/^\.\//, '')
        apply(loadPatches(file).filter(row => group.enabled || row?.insert), group.id)
      } catch {
        entries.clear(); for (const [key, value] of before) entries.set(key, value)
        group.readable = false
      }
    }
    // 用户配置仍优先于包默认值，应用开关最后覆盖；只返回名称与状态，不返回配置内容。
    for (const file of [path.join(root, 'root/.dsh/profiles/web/cordis.patch.yml'), path.join(root, 'root/.dsh/cordis.patch.yml')]) {
      if (exists(file)) {
        try { apply(loadPatches(file), null) } catch { /* 损坏用户配置不阻断包级恢复入口。 */ }
      }
    }
    apply(overrides(), null)
    for (const entry of entries.values()) {
      if (!entry.protected) continue
      let parent = entry.parent
      const visited = new Set()
      while (parent && entries.has(parent)) {
        if (visited.has(parent)) fail('PLUGIN_CONFIG_INVALID')
        visited.add(parent)
        entries.get(parent).protected = true
        parent = entries.get(parent).parent
      }
    }
    for (const entry of entries.values()) {
      const group = groups.find(item => item.id === entry.owner)
      if (!group) continue
      let parent = entry.parent
      let ancestorsEnabled = true
      const visited = new Set()
      while (parent && entries.has(parent)) {
        if (visited.has(parent)) fail('PLUGIN_CONFIG_INVALID')
        visited.add(parent)
        const ancestor = entries.get(parent)
        ancestorsEnabled &&= ancestor.enabled
        parent = ancestor.parent
      }
      const { owner, parent: unusedParent, group: unusedGroup, ...publicEntry } = entry
      group.children.push({ ...publicEntry, effectiveEnabled: group.enabled && ancestorsEnabled && entry.enabled })
    }
    return { plugins: groups }
  }
  function list() {
    const result = catalog()
    if (Buffer.byteLength(JSON.stringify(result)) > 220000) fail('PLUGIN_CONFIG_INVALID')
    return result
  }
  function setChildEnabled(id, childId, enabled) {
    validName(id)
    if (!entryId(childId) || typeof enabled !== 'boolean') fail('PLUGIN_INPUT_INVALID')
    const group = catalog().plugins.find(item => item.id === id)
    const child = group?.children.find(item => item.id === childId)
    if (!child) fail('PLUGIN_NOT_FOUND')
    if (child.protected) fail('PLUGIN_PROTECTED')
    if (!group.enabled) fail('PLUGIN_GROUP_DISABLED')
    const rows = overrides().filter(row => row.id !== childId)
    if (rows.length >= 512) fail('PLUGIN_CONFIG_INVALID')
    rows.push({ id: childId, disabled: !enabled })
    atomic(overrideFile, rows)
    return list()
  }
  function requireEditable(id) {
    validName(id)
    if (!manifest().names.includes(id)) fail('PLUGIN_NOT_FOUND')
    if (['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'].includes(id)) fail('PLUGIN_PROTECTED')
  }
  function setEnabled(id, enabled) {
    requireEditable(id)
    if (typeof enabled !== 'boolean') fail('PLUGIN_INPUT_INVALID')
    const config = manifest()
    if (enabled && !resolvePackage(id)) fail('PLUGIN_NOT_FOUND')
    const bundles = config.enabled.filter(name => name !== id)
    // 恢复原有顺序，避免依赖组合包的覆盖顺序发生变化。
    const order = config.value.dshMobile?.bundleOrder ?? config.names
    if (!Array.isArray(order) || order.length > 32) fail('PLUGIN_CONFIG_INVALID')
    order.forEach(validName)
    if (enabled) bundles.push(id)
    bundles.sort((left, right) => order.indexOf(left) - order.indexOf(right))
    config.value.dsh.profile.bundles = bundles
    config.value.dshMobile = { ...config.value.dshMobile, bundleOrder: order, disabledBundles: config.names.filter(name => !bundles.includes(name)) }
    atomic(profile, config.value)
    return list()
  }
  function recover() {
    if (!exists(journal)) return
    const transaction = read(journal)
    if (!Array.isArray(transaction.entries) || transaction.entries.length > 256) fail('PLUGIN_RECOVERY_FAILED')
    // 先校验整个事务，避免篡改的恢复记录触碰管理范围之外的文件。
    const entries = transaction.entries.map(entry => {
      const target = path.resolve(root, entry.target ?? '')
      const backup = path.resolve(root, entry.backup ?? '')
      const targetBase = modules.find(base => within(base, target) && target !== base)
      const relativeName = targetBase && path.relative(targetBase, target).split(path.sep).join('/')
      const backupParts = path.relative(path.join(home, 'backups'), backup).split(path.sep)
      if (!relativeName || !NAME.test(relativeName) || relativeName.includes('..') || protectedPackage(relativeName) || backupParts.length !== 2 || !/^[a-f0-9-]{36}$/.test(backupParts[0]) || !/^[0-9]{1,3}$/.test(backupParts[1])) fail('PLUGIN_RECOVERY_FAILED')
      safe(path.dirname(target)); safe(path.dirname(backup))
      return { target, backup, hadOriginal: entry.hadOriginal === true }
    })
    for (const entry of entries.reverse()) {
      if (exists(entry.backup)) {
        if (exists(entry.target)) {
          if (!fs.lstatSync(entry.target).isSymbolicLink()) fail('PLUGIN_RECOVERY_FAILED')
          fs.unlinkSync(entry.target)
        }
        fs.renameSync(entry.backup, entry.target)
      } else if (!entry.hadOriginal && exists(entry.target)) {
        if (!fs.lstatSync(entry.target).isSymbolicLink()) fail('PLUGIN_RECOVERY_FAILED')
        fs.unlinkSync(entry.target)
      }
    }
    fs.unlinkSync(journal)
  }
  function npmInstall(id, directory) {
    const npm = path.join(root, 'opt/node/lib/node_modules/npm/bin/npm-cli.js')
    safe(npm)
    if (!exists(npm)) fail('PLUGIN_UPDATER_MISSING')
    // 使用隔离配置和参数数组；不继承模型凭据，不执行依赖安装脚本。
    const environment = { HOME: directory, PATH: path.dirname(process.execPath) + ':/usr/bin:/bin', LANG: 'C.UTF-8', TMPDIR: directory }
    const common = ['--registry=https://registry.npmjs.org', '--userconfig=' + path.join(directory, 'npmrc'), '--globalconfig=' + path.join(directory, 'global-npmrc')]
    const latest = spawnSync(process.execPath, [npm, 'view', id, 'dist-tags.latest', '--json', ...common], { env: environment, encoding: 'utf8', timeout: 45000, maxBuffer: 65536 })
    if (latest.status !== 0) fail('PLUGIN_UPDATE_FAILED')
    let version
    try { version = JSON.parse(latest.stdout) } catch { fail('PLUGIN_UPDATE_FAILED') }
    if (typeof version !== 'string' || version.length > 64 || !VERSION.test(version)) fail('PLUGIN_UPDATE_FAILED')
    atomic(path.join(directory, 'package.json'), { name: 'dsh-mobile-plugin-update', version: '1.0.0', private: true })
    const result = spawnSync(process.execPath, [npm, 'install', id + '@' + version, '--ignore-scripts', '--legacy-peer-deps', '--bin-links=false', '--no-audit', '--no-fund', '--omit=dev', '--fetch-retries=1', '--fetch-timeout=30000', ...common], {
      cwd: directory, env: environment, encoding: 'utf8', timeout: 180000, maxBuffer: 1024 * 1024,
    })
    if (result.status !== 0) fail('PLUGIN_UPDATE_FAILED')
  }
  function updateInternal(id, directory, transactionId) {
    requireEditable(id)
    if (protectedPackage(id)) fail('PLUGIN_PROTECTED')
    recover()
    fs.mkdirSync(directory, { recursive: true, mode: 0o700 })
    // 先检测符号链接能力；不支持时保留现有插件，绝不降级到破坏性覆盖。
    const probe = path.join(directory, 'link-probe')
    try { fs.symlinkSync(directory, probe, 'junction'); fs.unlinkSync(probe) } catch { fail('PLUGIN_LINK_UNSUPPORTED') }
    ;(installPackage ?? npmInstall)(id, directory)
    const stageModules = path.join(directory, 'node_modules')
    const stagedNames = []
    for (const item of fs.readdirSync(stageModules)) {
      if (item === '.bin' || item.startsWith('.')) continue
      if (item.startsWith('@')) {
        for (const child of fs.readdirSync(path.join(stageModules, item))) stagedNames.push(validName(item + '/' + child))
      } else stagedNames.push(validName(item))
    }
    if (!stagedNames.includes(id) || stagedNames.length > 256) fail('PLUGIN_UPDATE_FAILED')
    const selected = read(path.join(stageModules, id, 'package.json'))
    if (selected.name !== id || !VERSION.test(selected.version ?? '') || typeof selected.dsh?.bundle?.patch !== 'string') fail('PLUGIN_UPDATE_FAILED')
    const patch = path.resolve(stageModules, id, selected.dsh.bundle.patch)
    if (!within(path.join(stageModules, id), patch) || !fs.statSync(safe(patch)).isFile()) fail('PLUGIN_UPDATE_FAILED')
    loadPatches(patch)
    const entries = []
    for (const name of stagedNames) {
      const source = safe(path.join(stageModules, name))
      const pkg = read(path.join(source, 'package.json'))
      if (pkg.name !== name || fs.lstatSync(source).isSymbolicLink()) fail('PLUGIN_UPDATE_FAILED')
      const installed = resolvePackage(name)
      if (protectedPackage(name)) {
        if (!installed || read(path.join(installed, 'package.json')).version !== pkg.version) fail('PLUGIN_DEPENDENCY_UNSUPPORTED')
        // 核心 SDK 始终使用运行时已有实例，避免加载第二份服务容器。
        fs.rmSync(source, { recursive: true })
        fs.symlinkSync(installed, source, 'junction')
        continue
      }
      if (name !== id && installed) {
        // 不覆盖其他插件正在共享的依赖；不兼容时整次更新失败并保留原版本。
        if (read(path.join(installed, 'package.json')).version !== pkg.version) fail('PLUGIN_DEPENDENCY_UNSUPPORTED')
        continue
      }
      const target = modules.map(base => path.join(base, name)).find(exists) ?? safe(path.join(fallbackModules, name))
      if (entries.some(entry => entry.target === target)) fail('PLUGIN_PATH_INVALID')
      entries.push({ target, source, backup: path.join(home, 'backups', transactionId, String(entries.length)), hadOriginal: exists(target) })
    }
    fs.mkdirSync(path.join(home, 'backups', transactionId), { recursive: true, mode: 0o700 })
    for (const entry of entries) fs.mkdirSync(safe(path.dirname(entry.target)), { recursive: true, mode: 0o700 })
    atomic(journal, { entries: entries.map(entry => ({ target: path.relative(root, entry.target), backup: path.relative(root, entry.backup), hadOriginal: entry.hadOriginal })) })
    try {
      for (const entry of entries) {
        if (entry.hadOriginal) fs.renameSync(entry.target, entry.backup)
        fs.symlinkSync(entry.source, entry.target, 'junction')
      }
      const result = list()
      if (result.plugins.find(plugin => plugin.id === id)?.version !== selected.version) fail('PLUGIN_UPDATE_FAILED')
      fs.unlinkSync(journal)
      // 提交点之后仅清理备份，清理失败不得把成功更新报告为失败。
      try { fs.rmSync(path.join(home, 'backups', transactionId), { recursive: true, force: true }) } catch {}
      return result
    } catch (error) {
      recover()
      throw error
    }
  }
  function update(id) {
    const transactionId = crypto.randomUUID()
    const directory = safe(path.join(home, 'versions', transactionId))
    try { return updateInternal(id, directory, transactionId) }
    catch (error) {
      // 恢复记录仍在时保留暂存目录，供下次启动恢复；否则清除失败下载。
      if (!exists(journal) && exists(directory)) fs.rmSync(directory, { recursive: true, force: true })
      throw error
    }
  }
  return { list, setEnabled, setChildEnabled, update, recover }
}

module.exports = { createManager, validName, within }
if (require.main === module) {
  try {
    const manager = createManager('/')
    const [operation, id, flag, childId] = process.argv.slice(2)
    let result
    if (operation === 'list') result = manager.list()
    else if (operation === 'recover') { manager.recover(); result = { recovered: true } }
    else if (operation === 'enable' && (flag === 'true' || flag === 'false')) { manager.recover(); result = manager.setEnabled(id, flag === 'true') }
    else if (operation === 'child' && (flag === 'true' || flag === 'false')) { manager.recover(); result = manager.setChildEnabled(id, childId, flag === 'true') }
    else if (operation === 'update') result = manager.update(id)
    else fail('PLUGIN_INPUT_INVALID')
    const output = JSON.stringify(result)
    if (Buffer.byteLength(output) > 220000) fail('PLUGIN_CONFIG_INVALID')
    process.stdout.write(output + '\n')
  } catch (error) {
    const code = /^PLUGIN_[A-Z_]+$/.test(error.message) ? error.message : 'PLUGIN_OPERATION_FAILED'
    process.stdout.write(JSON.stringify({ error: code }) + '\n')
    process.exitCode = 1
  }
}
