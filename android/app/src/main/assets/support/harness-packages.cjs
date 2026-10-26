'use strict'

// 独立 Harness 包事务：固定 npm 来源、参数数组调用、安装脚本默认关闭；绝不删除会话或插件数据。
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { createRequire } = require('node:module')
const { spawnSync } = require('node:child_process')
const VERSION = /^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?$/u
const CORE = /^@deepseek-ai\/dsh(?:-[a-z0-9-]+)?$/u
const fail = (code, detail) => {
  const error = new Error(code)
  if (detail) error.detail = detail
  throw error
}
function version(value) {
  if (typeof value !== 'string' || value.length > 96 || !VERSION.test(value)) fail('HARNESS_VERSION_INVALID')
  return value
}
const within = (base, target) => target === base || target.startsWith(base + path.sep)
function read(file) {
  if (!fs.statSync(file).isFile() || fs.statSync(file).size > 8 * 1024 * 1024) fail('HARNESS_PACKAGE_INVALID')
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}
function atomic(file, value) {
  if (fs.existsSync(file) && fs.lstatSync(file).isSymbolicLink()) fail('HARNESS_PATH_INVALID')
  const temporary = file + '.' + crypto.randomUUID() + '.tmp'
  const fd = fs.openSync(temporary, 'wx', 0o600)
  try { fs.writeFileSync(fd, JSON.stringify(value) + '\n'); fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
  fs.renameSync(temporary, file)
}
/** 路径校验逐层拒绝链接，递归删除只能触及本管理器创建的单个版本目录。 */
function directory(root, relative) {
  let cursor = root
  for (const part of relative.split('/')) {
    if (!part || part === '.' || part === '..') fail('HARNESS_PATH_INVALID')
    cursor = path.join(cursor, part)
    if (!fs.existsSync(cursor)) fs.mkdirSync(cursor, { mode: 0o700 })
    if (!fs.lstatSync(cursor).isDirectory() || fs.lstatSync(cursor).isSymbolicLink()) fail('HARNESS_PATH_INVALID')
  }
  return cursor
}
function fingerprint(directoryPath, inventory = null) {
  const hash = crypto.createHash('sha256')
  let bytes = 0
  let count = 0
  const files = []
  function visit(full) {
      const relative = path.relative(directoryPath, full)
      if (relative === 'ready.json' || relative === 'inventory.json') return
      if (++count > 250000 || !within(directoryPath, full)) fail('HARNESS_PACKAGE_TOO_LARGE')
      files.push(relative)
      const stat = fs.lstatSync(full)
      hash.update(relative + '\0')
      if (stat.isSymbolicLink()) {
        const target = fs.readlinkSync(full)
        if (!within(directoryPath, path.resolve(path.dirname(full), target))) fail('HARNESS_PATH_INVALID')
        hash.update('link\0' + target)
      } else if (stat.isDirectory()) { hash.update('dir\0'); if (!inventory) walk(full) }
      else if (stat.isFile()) {
        bytes += stat.size
        if (bytes > 3 * 1024 ** 3 || stat.size > 256 * 1024 ** 2) fail('HARNESS_PACKAGE_TOO_LARGE')
        hash.update('file\0'); hash.update(fs.readFileSync(full))
      } else fail('HARNESS_PATH_INVALID')
  }
  function walk(folder) {
    for (const name of fs.readdirSync(folder).sort()) {
      visit(path.join(folder, name))
    }
  }
  if (inventory) {
    if (!Array.isArray(inventory) || inventory.length > 250000) fail('HARNESS_PACKAGE_INVALID')
    for (const relative of inventory) {
      if (typeof relative !== 'string' || path.isAbsolute(relative) || relative.split(/[\\/]/u).includes('..')) fail('HARNESS_PATH_INVALID')
      const full = path.join(directoryPath, relative)
      if (!within(directoryPath, fs.realpathSync(path.dirname(full)))) fail('HARNESS_PATH_INVALID')
      visit(full)
    }
  } else walk(directoryPath)
  return { sha256: hash.digest('hex'), bytes, files }
}
function verifyLock(lock, selected, integrity) {
  const packages = lock.packages
  if (!packages || Object.keys(packages).length > 10000) fail('HARNESS_PACKAGE_INVALID')
  const primary = packages['node_modules/@deepseek-ai/dsh']
  if (primary?.version !== selected || primary.integrity !== integrity) fail('HARNESS_INTEGRITY_INVALID')
  for (const [location, item] of Object.entries(packages)) {
    if (!location) continue
    if (location.includes('..') || location.startsWith('/') || location.includes('\\') || item.link) fail('HARNESS_PATH_INVALID')
    if (typeof item.resolved !== 'string') fail('HARNESS_PACKAGE_INVALID')
    const url = new URL(item.resolved)
    if (url.protocol !== 'https:' || url.hostname !== 'registry.npmjs.org' || url.port || url.username || url.password) fail('HARNESS_PACKAGE_INVALID')
    if (!/^sha512-[A-Za-z0-9+/]{86}==$/u.test(item.integrity ?? '')) fail('HARNESS_INTEGRITY_INVALID')
    const name = location.split('node_modules/').at(-1)
    if (CORE.test(name) && item.version !== selected) fail('HARNESS_DEPENDENCY_MISMATCH')
  }
}
/** `.pnpm` 目录名 → 版本串：`node-pty@1.2.0-beta.15(react@18.2.0)` 取 `1.2.0-beta.15`。 */
const storedVersion = (entry, prefix) => entry.slice(prefix.length).split('(')[0]
/** 版本串 → [数字段, 预发布段或 null]；非数字段按 0 处理，只用于排序，不用于校验。 */
const versionParts = value => {
  const [core, ...rest] = String(value).split('-')
  return [core.split('.').map(part => Number(part)), rest.length === 0 ? null : rest.join('-')]
}
/**
 * 比较两个版本串，供 `.pnpm` 兜底挑版本用。
 *
 * 不能用字符串排序代替：字典序会认为 `1.10.0` 小于 `1.9.0`，同一个包的多版本共存时就会挑到旧的
 * 那个，随后复用分支上的版本相等校验失配，装好的 prebuilds 被弃用、退化成 node-gyp 现编译。
 * 正式版大于同号预发布版（`1.2.0` > `1.2.0-beta.15`）。
 */
function compareVersion(left, right) {
  const [coreLeft, preLeft] = versionParts(left)
  const [coreRight, preRight] = versionParts(right)
  for (let index = 0; index < Math.max(coreLeft.length, coreRight.length); index += 1) {
    const diff = (coreLeft[index] ?? 0) - (coreRight[index] ?? 0)
    if (Number.isFinite(diff) && diff !== 0) return diff > 0 ? 1 : -1
  }
  if (preLeft === preRight) return 0
  if (preLeft === null) return 1
  if (preRight === null) return -1
  return preLeft > preRight ? 1 : -1
}
/** 在 npm 扁平布局、pnpm 虚拟存储和常规解析三种布局里定位单个包目录；找不到返回 null。 */
function locate(modules, anchor, name) {
  const flat = path.join(modules, name)
  if (fs.existsSync(path.join(flat, 'package.json'))) return flat
  try { return path.dirname(createRequire(path.join(anchor, 'package.json')).resolve(name + '/package.json')) } catch {}
  const store = path.join(modules, '.pnpm')
  const prefix = name.replace('/', '+') + '@'
  const entries = fs.existsSync(store) ? fs.readdirSync(store).filter(entry => entry.startsWith(prefix)) : []
  // 多版本共存时取版本最高的那个，而不是名字排最后的那个。
  return entries
    .sort((left, right) => compareVersion(storedVersion(right, prefix), storedVersion(left, prefix)))
    .map(entry => path.join(store, entry, 'node_modules', name))
    .find(candidate => fs.existsSync(path.join(candidate, 'package.json'))) ?? null
}
function createManager(root, execute = spawnSync, report = () => {}) {
  root = fs.realpathSync(root)
  const home = directory(root, 'root/.dsh-mobile/harness-versions')
  const versions = directory(home, 'versions')
  const bundled = path.join(root, 'opt/dsh-bundled')
  const installed = path.join(root, 'opt/dsh')
  const npm = path.join(root, 'opt/node/lib/node_modules/npm/bin/npm-cli.js')
  // 依赖解析在移动网络下可能超过 4 分钟；240s 会把还在下载的 npm 直接 SIGTERM 掉（实测 exit=null signal=SIGTERM）。
  function run(args, cwd, timeout = 900000) {
    const result = execute(process.execPath, args, {
      cwd, timeout, maxBuffer: 1024 * 1024, encoding: 'utf8',
      // node-gyp 会从 PATH 查找 python3；Ubuntu 运行时把它放在 /usr/local/bin。
      // 这里仍然保留 /opt/node/bin 在最前，避免误用宿主或系统 Node。
      //
      // PRoot 访客里 IPv6 是不可达的（connect 返回 ENETUNREACH），而 Node 24 默认开启的
      // 地址族自动选择会先去试 IPv6，随后整次请求卡到超时：npm 实测 `ETIMEDOUT`，
      // 安装停在「解析依赖版本」阶段。关掉自动选择、显式按 IPv4 优先解析后，
      // registry.npmjs.org 在真机上可正常访问。只影响这些子进程，不改 DSH 自身的网络行为。
      env: {
        HOME: cwd,
        TMPDIR: cwd,
        PATH: path.dirname(process.execPath) + ':/usr/local/bin:/usr/bin:/bin',
        LANG: 'C.UTF-8',
        NODE_OPTIONS: '--dns-result-order=ipv4first --no-network-family-autoselection',
      },
    })
    if (result.status !== 0 || result.error || result.signal) fail('HARNESS_INSTALL_FAILED', failureDetail(result))
    return result.stdout
  }

  /**
   * 把子进程输出收成一行可读线索，交给宿主展示/写诊断。
   *
   * 只留最后几行、压平空白、抹掉 URL 与绝对路径：npm 的报错里常带 registry 地址和内网路径，
   * 这些不该出现在界面或导出日志里。
   */
  function failureDetail(result) {
    // 先给可判定的证据：退出码、信号、spawn 错误、输出字节数。没有这些时，
    // 界面上的「失败」无法区分「npm 报错」「被信号杀」「输出超限」三种情况。
    const head = [
      'exit=' + String(result.status),
      'signal=' + String(result.signal ?? '-'),
      'error=' + String(result.error?.code ?? '-'),
      'stdout=' + String(result.stdout?.length ?? 0) + 'B',
      'stderr=' + String(result.stderr?.length ?? 0) + 'B',
    ].join(' ')
    const raw = [result.stderr, result.stdout].filter(Boolean).join('\n')
    const text = String(raw)
      .replace(/https?:\/\/\S+/gu, '<url>')
      .replace(/\/(?:[A-Za-z0-9._-]+\/){2,}[A-Za-z0-9._-]+/gu, '<path>')
      .replace(/[\u0000-\u001f\u007f]/gu, ' ')
      .replace(/\s+/gu, ' ')
      .trim()
    const tail = text.length > 300 ? text.slice(-300) : text
    return tail ? head + ' | ' + tail : head
  }
  function npmArgs(command, stage) {
    return [npm, command, '--ignore-scripts', '--no-audit', '--no-fund', '--bin-links=false',
      // 移动网络下「30 秒 + 1 次重试」太紧：上游元数据稍慢就整次安装失败。
      '--registry=https://registry.npmjs.org', '--fetch-retries=3', '--fetch-timeout=120000',
      '--userconfig=' + path.join(stage, 'npmrc'), '--globalconfig=' + path.join(stage, 'global-npmrc'), '--cache=' + path.join(stage, 'cache')]
  }
  function verify(selected) {
    const target = path.join(versions, version(selected))
    if (!fs.lstatSync(target).isDirectory() || fs.lstatSync(target).isSymbolicLink()) fail('HARNESS_PATH_INVALID')
    const ready = read(path.join(target, 'ready.json'))
    if (ready.version !== selected || ready.arch !== process.arch || ready.abi !== process.versions.modules) fail('HARNESS_ABI_MISMATCH')
    if (fingerprint(target, read(path.join(target, 'inventory.json'))).sha256 !== ready.sha256) fail('HARNESS_INTEGRITY_INVALID')
    return ready
  }
  function environment() {
    return { ok: true, abi: process.versions.modules, arch: process.arch, node: process.version }
  }
  function install(selected, integrity) {
    version(selected)
    if (!/^sha512-[A-Za-z0-9+/]{86}==$/u.test(integrity ?? '')) fail('HARNESS_INTEGRITY_INVALID')
    const target = path.join(versions, selected)
    if (fs.existsSync(target)) { report('verify', 90); verify(selected); report('done', 100); return { ok: true } }
    const stage = fs.mkdtempSync(path.join(versions, '.install-'))
    // 阶段进度：npm 自己解析依赖、下 tarball 时不吐可用百分比，所以这里只报阶段，
    // 界面按阶段完成度画条并显示已用时间，不假装知道剩余字节。
    let phase = 'prepare'
    try {
      report(phase, 5)
      fs.writeFileSync(path.join(stage, 'npmrc'), '', { mode: 0o600 })
      fs.writeFileSync(path.join(stage, 'global-npmrc'), '', { mode: 0o600 })
      const old = read(path.join(bundled, 'package.json'))
      const dependencies = { '@deepseek-ai/dsh': selected, '@deepseek-ai/dsh-base': selected, '@deepseek-ai/dsh-web-app': selected,
        pnpm: old.dependencies?.pnpm ?? '11.19.0', react: old.dependencies?.react ?? '18.3.1' }
      const spec = { name: 'dsh-mobile-harness-version', version: '1.0.0', private: true, dependencies }
      atomic(path.join(stage, 'package.json'), spec)
      phase = 'resolve'
      report(phase, 15)
      run([...npmArgs('install', stage), '--package-lock-only'], stage)
      let lock = read(path.join(stage, 'package-lock.json'))
      // 固定实际依赖闭包中的整套 dsh 包；不依靠 npm 范围碰巧选到同一版本。
      spec.overrides = Object.fromEntries(Object.keys(lock.packages ?? {}).map(location => location.split('node_modules/').at(-1))
        .filter(name => CORE.test(name)).map(name => [name, selected]))
      atomic(path.join(stage, 'package.json'), spec)
      fs.unlinkSync(path.join(stage, 'package-lock.json'))
      phase = 'lock'
      report(phase, 25)
      run([...npmArgs('install', stage), '--package-lock-only'], stage)
      lock = read(path.join(stage, 'package-lock.json'))
      verifyLock(lock, selected, integrity)
      phase = 'fetch'
      report(phase, 35)
      run(npmArgs('ci', stage), stage, 480000)
      report('bridge', 78)
      const modules = path.join(stage, 'node_modules')
      const bridgeName = '@deepseek-harness/dsh-mobile-shizuku'
      const bridgeSource = fs.realpathSync(path.join(bundled, 'node_modules', bridgeName))
      const bridgeTarget = path.join(modules, bridgeName)
      fs.mkdirSync(bridgeTarget, { recursive: true, mode: 0o700 })
      for (const file of ['package.json', 'lib', 'cordis.patch.yml']) {
        fs.cpSync(path.join(bridgeSource, file), path.join(bridgeTarget, file), { recursive: true, dereference: true })
      }
      const dshManifest = path.join(modules, '@deepseek-ai/dsh/package.json')
      const cli = read(dshManifest)
      cli.dependencies[bridgeName] = read(path.join(bridgeTarget, 'package.json')).version
      atomic(dshManifest, cli)
      phase = 'native'
      report(phase, 84)
      // node-pty 是 dsh-subprocess-local 的间接依赖，内置运行时用 pnpm 隔离安装，
      // 顶层没有它的软链，只有 createRequire 会在这里抛 MODULE_NOT_FOUND 并把安装永久卡住。
      // 发布包自带各平台 prebuilds/<platform>-<arch>/pty.node（lib/utils.js 优先加载），
      // 装好的树里已经有可加载的二进制就不再构建、也不再整包覆盖。
      const bundledModules = path.join(bundled, 'node_modules')
      const bundledAnchor = path.join(bundledModules, '@deepseek-ai/dsh/package.json')
      const ptyDirectory = locate(modules, path.join(modules, '@deepseek-ai/dsh/package.json'), 'node-pty')
      if (!ptyDirectory) fail('HARNESS_PACKAGE_INVALID')
      const nativeArch = process.platform + '-' + process.arch
      const nativePath = path.join('prebuilds', nativeArch, 'pty.node')
      const usable = file => {
        const stat = fs.lstatSync(file, { throwIfNoEntry: false })
        return Boolean(stat?.isFile() && stat.size > 0)
      }
      if (!usable(path.join(ptyDirectory, nativePath))) {
        const bundledPty = locate(bundledModules, bundledAnchor, 'node-pty')
        const reusable = bundledPty && usable(path.join(bundledPty, nativePath)) &&
          read(path.join(bundledPty, 'package.json')).version === read(path.join(ptyDirectory, 'package.json')).version
        if (reusable) {
          // 同版本随包二进制可直接沿用：只补本机架构这一个 prebuilds 子目录。
          fs.cpSync(path.join(bundledPty, 'prebuilds', nativeArch), path.join(ptyDirectory, 'prebuilds', nativeArch),
            { recursive: true, dereference: true })
        } else {
          const gyp = locate(bundledModules, bundledAnchor, 'node-gyp')
          if (!gyp) fail('HARNESS_PACKAGE_INVALID')
          run([path.join(gyp, 'bin/node-gyp.js'), 'rebuild', '--nodedir=' + path.join(root, 'opt/node')], ptyDirectory)
        }
      }
      phase = 'smoke'
      report(phase, 92)
      // 真实加载原生模块并创建 PTY；不以目录存在冒充可执行。
      run(['-e', 'const p=require(process.argv[1]);const t=p.spawn("/bin/true",[],{name:"xterm",cols:80,rows:24});t.onExit(e=>process.exit(e.exitCode));setTimeout(()=>process.exit(1),10000).unref()', ptyDirectory], stage, 20000)
      run([path.join(modules, '@deepseek-ai/dsh/lib/bin.js'), '--version'], stage, 30000)
      // 仅移除本次事务内部的 npm 缓存；锁文件与完整依赖树保留供离线核验。
      fs.rmSync(path.join(stage, 'cache'), { recursive: true, force: true })
      phase = 'record'
      report(phase, 96)
      const digest = fingerprint(stage)
      atomic(path.join(stage, 'inventory.json'), digest.files)
      atomic(path.join(stage, 'ready.json'), { version: selected, abi: process.versions.modules, arch: process.arch, sha256: digest.sha256, bytes: digest.bytes })
      fs.renameSync(stage, target)
      report('done', 100)
      return { ok: true }
    } catch (error) {
      if (error && typeof error === 'object' && error.stage === undefined) error.stage = phase
      throw error
    } finally {
      if (fs.existsSync(stage) && path.dirname(stage) === versions && path.basename(stage).startsWith('.install-')) fs.rmSync(stage, { recursive: true })
    }
  }
  function prepare() {
    const modules = path.join(installed, 'node_modules')
    const packages = new Map()
    const queue = [installed]
    // 遍历真实依赖闭包，兼容 npm 扁平目录和内置 pnpm 隔离目录。
    while (queue.length) {
      const anchor = queue.pop()
      const pkg = read(path.join(anchor, 'package.json'))
      const req = createRequire(path.join(anchor, 'package.json'))
      for (const name of Object.keys({ ...pkg.dependencies, ...pkg.peerDependencies, ...pkg.optionalDependencies })) {
        if (packages.has(name)) continue
        if (!/^(?:@[a-z0-9._-]+\/)?[a-z0-9._-]+$/u.test(name) || name.includes('..')) fail('HARNESS_PACKAGE_INVALID')
        const directory = req.resolve.paths(name)?.map(base => path.join(base, name)).find(candidate => fs.existsSync(path.join(candidate, 'package.json')))
        if (!directory) continue
        const resolved = fs.realpathSync(directory)
        if (!within(installed, resolved)) fail('HARNESS_PATH_INVALID')
        if (packages.size > 10000) fail('HARNESS_PACKAGE_TOO_LARGE')
        packages.set(name, resolved)
        queue.push(resolved)
      }
    }
    const profiles = directory(root, 'root/.dsh/profiles')
    for (const relative of ['node_modules', 'web/node_modules']) {
      const base = directory(profiles, relative)
      for (const [name, source] of packages) {
        const target = path.join(base, name)
        fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 })
        // 已有插件实体保留；核心包实体拒绝静默替换，避免出现两套核心服务。
        if (fs.existsSync(target) || fs.lstatSync(target, { throwIfNoEntry: false })) {
          if (!fs.lstatSync(target).isSymbolicLink()) {
            if (CORE.test(name)) fail('HARNESS_PROFILE_CONFLICT')
            continue
          }
          fs.unlinkSync(target)
        }
        fs.symlinkSync(source, target, 'junction')
      }
    }
    return { ok: true }
  }
  function remove(selected) {
    const target = path.join(versions, version(selected))
    if (!fs.lstatSync(target).isDirectory() || fs.lstatSync(target).isSymbolicLink()) fail('HARNESS_PATH_INVALID')
    fs.rmSync(target, { recursive: true })
    return { ok: true }
  }
  return { install, verify, prepare, remove, environment }
}
module.exports = { version, verifyLock, fingerprint, createManager }
if (require.main === module) {
  const report = (stage, percent) => process.stdout.write('HARNESS-PROGRESS ' + JSON.stringify({ stage, percent }) + '\n')
  try {
    const [operation, selected, integrity] = process.argv.slice(2)
    const manager = createManager('/', spawnSync, report)
    if (!['install', 'verify', 'prepare', 'remove', 'environment'].includes(operation)) fail('HARNESS_VERSION_INVALID')
    const result = manager[operation](selected, integrity)
    process.stdout.write(JSON.stringify(result) + '\n')
  } catch (error) {
    const code = /^HARNESS_[A-Z_]+$/u.test(error.message) ? error.message : 'HARNESS_PACKAGE_FAILED'
    process.stdout.write(JSON.stringify({
      error: code,
      ...(error.stage ? { stage: error.stage } : {}),
      ...(error.detail ? { detail: error.detail } : {}),
    }) + '\n')
    process.exitCode = 1
  }
}
