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

/**
 * 失败详情：只允许包名、版本号与 semver 范围里出现的字符。
 *
 * 之所以要收紧：`npmInstall` 与路径打交道，一旦把路径或异常原文塞进 detail 就会随
 * 桥接回传到 WebView。这里拒绝引号、反斜杠、冒号、控制字符与超长内容，只留下
 * `包名@版本`、`!=`、范围表达式这类可安全展示、也足够定位问题的信息。
 */
const DETAIL = /^[A-Za-z0-9@/._+, =!<>~^|()\-]{1,300}$/

/** 解析闭包补全的扫描上限：只扫这些扩展名，并限定文件数、单文件与总字节数。 */
const SCAN_EXTENSIONS = ['.js', '.mjs', '.cjs', '.json']
const SCAN_MAX_FILES = 4000
const SCAN_MAX_BYTES = 24 * 1024 * 1024
const SCAN_MAX_FILE_BYTES = 512 * 1024
const MAX_LINKED_DEPENDENCIES = 64

/**
 * 运行时包去重与修复只处理这个作用域。
 *
 * `@deepseek-ai/` 下的包通过 Symbol 在 cordis 注册表里挂服务（例如 dsh-tools 的
 * TOOL_RUNTIME_SCHEDULER），任何一份多余的物理副本都会让 Symbol 身份失配；插件自己的
 * 第三方依赖（zod、zustand 等）保持 npm 装好的真实副本，改动面越小越安全。
 * 详见 docs/插件安装与运行时单例.md。
 */
const RUNTIME_SCOPE = '@deepseek-ai/'
/** 嵌套 node_modules 的深度、目录数与包数上限：避免在大依赖树上遍历爆炸。 */
const RECONCILE_MAX_DEPTH = 4
const RECONCILE_MAX_DIRECTORIES = 256
const RECONCILE_MAX_PACKAGES = 512
/** 一次修复最多扫描的插件版本目录数（暂存目录名固定为 UUID）。 */
const REPAIR_MAX_VERSIONS = 64
const TRANSACTION_ID = /^[a-f0-9-]{36}$/

/**
 * 模块图探测的目标包与它的作用域。
 *
 * `dsh-tools` 是**带 Symbol 服务身份**的模块：`Symbol('@deepseek-ai/dsh-tools.scheduler')`
 * 在每个物理模块副本里各造一个。因此「有几份」不能靠出现次数回答，只能靠**不同真实路径数**回答。
 */
const GRAPH_SCOPE = '@deepseek-ai'
const GRAPH_PACKAGE = 'dsh-tools'

/**
 * 模块图探测的候选根（相对 guest root；root 为 `/` 时即设备上的绝对路径）。
 * 缺失的根直接跳过：探测必须在任何安装形态下都能返回结果，绝不报错。
 */
const GRAPH_ROOTS = [
  'opt/dsh/node_modules',
  'opt/dsh/plugins',
  'root/.dsh/profiles/node_modules',
  'root/.dsh/profiles/web/node_modules',
  'root/.dsh-mobile/plugin-manager',
]

/**
 * 探测的遍历上限：深度、目录数与条目数。
 *
 * 上限是**失控保护**，不是常规节流：真实的 pnpm 虚拟store 有几百个条目（本仓库自身就是 571 个），
 * 一旦预算把扫描截断，计数就会偏小 —— 而「少算一份真实副本」恰好是唯一会让判据失效的错误，
 * 所以上限必须宽到能完整覆盖真实布局。
 *  - 深度 4 刚好够到 pnpm 隔离布局的真实副本：
 *    `node_modules/.pnpm/<pkg>/node_modules/@deepseek-ai/dsh-tools`；
 *  - 目录与条目上限**按候选根各自重置**：带 `.pnpm` 的 `opt/dsh/node_modules` 一个根就能把
 *    共享预算吃光，让其余四个根一个都扫不到，那样的计数不能作为判据。
 */
const GRAPH_MAX_DEPTH = 4
const GRAPH_MAX_DIRECTORIES = 2048
const GRAPH_MAX_ENTRIES = 16384

/** 非 node_modules 候选根（plugins / plugin-manager）里允许自由下探的层数：只为找到 node_modules。 */
const GRAPH_BROWSE_DEPTH = 2

/** 这些目录名下的子项是「包目录」；pnpm 的隔离布局用 `.pnpm` 承担同样的角色。 */
const GRAPH_MODULE_DIRECTORIES = new Set(['node_modules', '.pnpm'])

/** 详情校验：通过返回原文，否则返回 null（调用方据此省略 detail 字段）。 */
function safeDetail(detail) {
  if (typeof detail !== 'string' || !DETAIL.test(detail)) return null
  // 字符集必须允许 `/`（作用域包名 @scope/name 需要它），因此单靠字符集挡不住路径。
  // 包名不可能以 `/`、`~`、`.` 开头，也不会包含 `//`：据此拒绝绝对路径、家目录路径
  // 与相对路径，作为"路径不得离开容器"的兜底。
  if (detail.startsWith('/') || detail.startsWith('~') || detail.startsWith('.')) return null
  if (detail.includes('//')) return null
  return detail
}

/**
 * 从裸导入说明符里取出包名：`a/b/c` → `a`，`@s/p/x` → `@s/p`。
 * 相对/绝对路径、`node:` 内建与子路径导入（`#internal`）一律返回 null。
 */
function packageNameOf(specifier) {
  if (typeof specifier !== 'string' || specifier.length === 0 || specifier.length > 128) return null
  if (specifier.startsWith('.') || specifier.startsWith('/') || specifier.startsWith('#')) return null
  if (specifier.startsWith('node:')) return null
  const parts = specifier.split('/')
  const name = specifier.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]
  return NAME.test(name) ? name : null
}

/**
 * 静态抽取一段源码里的裸导入包名。
 *
 * 只做保守的文本匹配：`from '...'`、`import '...'`、`require('...')`、`import('...')`。
 * 宁可漏掉动态拼接出来的导入，也不做会误判的复杂解析 —— 漏掉的会在 Harness 启动时
 * 照旧报错，而误判会把无关包链进 staging。
 */
function collectBareSpecifiers(text) {
  const found = new Set()
  if (typeof text !== 'string' || text.length === 0) return found
  const patterns = [
    /\bfrom\s*['"]([^'"\n]{1,128})['"]/g,
    /\bimport\s*['"]([^'"\n]{1,128})['"]/g,
    /\brequire\s*\(\s*['"]([^'"\n]{1,128})['"]\s*\)/g,
    /\bimport\s*\(\s*['"]([^'"\n]{1,128})['"]\s*\)/g,
  ]
  for (const pattern of patterns) {
    let match
    while ((match = pattern.exec(text)) !== null) {
      const name = packageNameOf(match[1])
      if (name !== null) found.add(name)
      if (found.size > 512) return found
    }
  }
  return found
}

/**
 * 抛出受控错误码，可选附带受控详情。
 * 只暴露错误码时用户只能看到"更新失败"；带上具体包名与版本差异才能自助定位。
 */
function fail(code, detail) {
  const error = new Error(code)
  const safe = safeDetail(detail)
  if (safe !== null) error.detail = safe
  throw error
}

// ---------------------------------------------------------------------------
// 版本选择
//
// 只认 dist-tags.latest 会装到与运行时 dsh 不兼容的版本：例如某个插件的 latest
// 要求 dsh>=0.1.5-rc.1，而本机运行时是 0.1.5-alpha.1；另一些包的 latest 甚至是
// 更早的预发布。这里按"运行时 dsh 版本 + 插件自己声明的 dsh.engines.dsh 范围"
// 挑出最高兼容版本。
//
// 范围匹配只实现 npm 里实际会出现的写法，并且**严格对齐 npm 默认语义**（预发布
// 版本只有在同 主.次.修订 元组且比较符自身带预发布时才参与匹配）：
//   ||            或
//   空格          与
//   >= > <= < =   比较
//   ^ ~ 与 部分版本（x / x.y / 1.x）
// 本段不依赖任何外部包，因此可以在没有网络与 npm 的环境里完整单测。
// ---------------------------------------------------------------------------

const SEMVER = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/

function parseVersion(text) {
  if (typeof text !== 'string' || text.length === 0 || text.length > 64) return null
  const match = SEMVER.exec(text)
  if (!match) return null
  const numbers = [Number(match[1]), Number(match[2]), Number(match[3])]
  if (numbers.some(value => !Number.isSafeInteger(value))) return null
  return { numbers, prerelease: match[4] ? match[4].split('.') : [] }
}

function comparePrerelease(left, right) {
  // 有预发布 < 无预发布：1.0.0-rc.1 < 1.0.0
  if (left.length === 0 || right.length === 0) {
    if (left.length === right.length) return 0
    return left.length === 0 ? 1 : -1
  }
  const length = Math.max(left.length, right.length)
  for (let index = 0; index < length; index += 1) {
    const a = left[index]
    const b = right[index]
    if (a === undefined) return -1
    if (b === undefined) return 1
    if (a === b) continue
    const aNumber = /^\d+$/.test(a) ? Number(a) : null
    const bNumber = /^\d+$/.test(b) ? Number(b) : null
    if (aNumber !== null && bNumber !== null) return aNumber < bNumber ? -1 : 1
    // 数字标识符优先级低于字母标识符
    if (aNumber !== null) return -1
    if (bNumber !== null) return 1
    return a < b ? -1 : 1
  }
  return 0
}

function compareParsed(left, right) {
  for (let index = 0; index < 3; index += 1) {
    if (left.numbers[index] !== right.numbers[index]) return left.numbers[index] < right.numbers[index] ? -1 : 1
  }
  return comparePrerelease(left.prerelease, right.prerelease)
}

function compareVersions(left, right) {
  const a = parseVersion(left)
  const b = parseVersion(right)
  if (!a || !b) return null
  return compareParsed(a, b)
}

function expandRangeClause(clause) {
  const expanded = []
  for (const part of clause.split(/\s+/).filter(Boolean)) {
    const caret = /^\^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$/.exec(part)
    if (caret) {
      const major = Number(caret[1])
      const minor = Number(caret[2] ?? 0)
      const patch = Number(caret[3] ?? 0)
      const base = `${major}.${minor}.${patch}${caret[4] ? '-' + caret[4] : ''}`
      if (major > 0) expanded.push(`>=${base}`, `<${major + 1}.0.0-0`)
      else if (minor > 0) expanded.push(`>=${base}`, `<0.${minor + 1}.0-0`)
      else expanded.push(`>=${base}`, `<0.0.${patch + 1}-0`)
      continue
    }
    const tilde = /^~(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$/.exec(part)
    if (tilde) {
      const major = Number(tilde[1])
      const minor = Number(tilde[2] ?? 0)
      const patch = Number(tilde[3] ?? 0)
      expanded.push(`>=${major}.${minor}.${patch}${tilde[4] ? '-' + tilde[4] : ''}`, `<${major}.${minor + 1}.0-0`)
      continue
    }
    const partial = /^(\d+)(?:\.(\d+|x|\*))?(?:\.(\d+|x|\*))?$/.exec(part)
    if (partial) {
      const major = Number(partial[1])
      const minor = partial[2]
      const patch = partial[3]
      if (minor === undefined || minor === 'x' || minor === '*') {
        expanded.push(`>=${major}.0.0`, `<${major + 1}.0.0-0`)
      } else if (patch === undefined || patch === 'x' || patch === '*') {
        expanded.push(`>=${major}.${Number(minor)}.0`, `<${major}.${Number(minor) + 1}.0-0`)
      } else {
        expanded.push(`=${major}.${Number(minor)}.${Number(patch)}`)
      }
      continue
    }
    expanded.push(part)
  }
  return expanded.join(' ')
}

function satisfiesComparator(version, operator, operandText) {
  const target = parseVersion(version)
  const operand = parseVersion(operandText)
  if (!target || !operand) return false
  if (target.prerelease.length > 0) {
    // npm 默认语义：预发布版本只有在同一 主.次.修订 元组、且比较符自身带预发布时才参与匹配。
    const sameTuple = target.numbers.every((value, index) => value === operand.numbers[index])
    if (!sameTuple || operand.prerelease.length === 0) return false
  }
  const order = compareParsed(target, operand)
  if (operator === '>') return order > 0
  if (operator === '>=') return order >= 0
  if (operator === '<') return order < 0
  if (operator === '<=') return order <= 0
  return order === 0
}

function satisfiesRange(version, range) {
  if (!parseVersion(version)) return false
  if (typeof range !== 'string' || range.length === 0 || range.length > 256) return false
  const text = range.trim()
  if (text === '' || text === '*') return true
  return text.split('||').some(clause => {
    const trimmed = clause.trim()
    if (trimmed === '') return false
    return expandRangeClause(trimmed).split(/\s+/).filter(Boolean).every(part => {
      const match = /^(>=|<=|>|<|=)?(.*)$/.exec(part)
      if (!match) return false
      return satisfiesComparator(version, match[1] ?? '=', match[2])
    })
  })
}

/**
 * 从候选版本中挑出与 [runtimeDsh] 兼容的最高版本。
 *
 * [rangeOf] 返回该候选版本声明的 dsh 引擎范围；返回 null/空串表示"未声明"，
 * 按兼容处理（与 npm 的宽松默认一致）。
 *
 * 返回 { version, range } 或 null（在探测上限内没有兼容版本）。
 * 探测次数有上限：每个候选都要额外问一次 npm，不设上限会在版本很多的包上退化。
 */
function selectNewestCompatible(versions, runtimeDsh, rangeOf, maxProbes = 6) {
  if (!parseVersion(runtimeDsh) || !Array.isArray(versions)) return null
  const parsed = versions
    .filter(item => typeof item === 'string' && VERSION.test(item))
    .map(item => ({ text: item, parsed: parseVersion(item) }))
    .filter(item => item.parsed !== null)
    .sort((a, b) => compareParsed(b.parsed, a.parsed))
  if (parsed.length === 0) return null
  const prereleaseRuntime = parseVersion(runtimeDsh).prerelease.length > 0
  // 运行时是稳定版时优先稳定候选；稳定候选全不兼容才退回预发布。
  const ordered = prereleaseRuntime ? parsed : [
    ...parsed.filter(item => item.parsed.prerelease.length === 0),
    ...parsed.filter(item => item.parsed.prerelease.length > 0),
  ]
  let probes = 0
  for (const candidate of ordered) {
    if (probes >= maxProbes) return null
    probes += 1
    const range = typeof rangeOf === 'function' ? rangeOf(candidate.text) : null
    if (range === null || range === undefined || range === '' || range === '*') {
      return { version: candidate.text, range: null }
    }
    if (typeof range !== 'string' || range.length > 256) continue
    if (satisfiesRange(runtimeDsh, range)) return { version: candidate.text, range }
  }
  return null
}

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
  /**
   * 把某个 node_modules 目录里的 `@deepseek-ai/*` 条目对齐到当前运行时实例。
   *
   * 为什么必须做：npm 把插件依赖的 `@deepseek-ai/*` 当普通依赖装成**真实副本**（dsh-base、
   * dsh-agent-loop 这类包又各自把 dsh-tools 声明为依赖，`--omit=dev` 不装 peer 但会装它们），
   * 而 dsh-tools 导出的是 `Symbol('@deepseek-ai/dsh-tools.scheduler')`，Symbol 在**每个物理
   * 模块副本里各造一个**。插件目录里留下一份真实副本，就等于让运行时里出现两个不相等的
   * Symbol，`ctx.tools[TOOL_RUNTIME_SCHEDULER]` 取到 undefined，之后**每一次工具调用**都会
   * 在 `.prepare` 上抛 `Cannot read properties of undefined (reading 'prepare')`。
   *
   * 判定一律以「从运行时安装锚点实际解析到的真实目录」为准（resolvePackage 已覆盖 pnpm
   * 隔离布局），绝不从既有链接的字符串里反推版本号或 peer 哈希。三种形态：
   *   - 真实目录副本：删除后改建指向运行时实例的 junction；
   *   - 悬空链接（运行时升级后，被保留的插件目录里旧版本号/peer 哈希路径已不存在）：
   *     重新指向当前运行时解析到的那一份；
   *   - 已指向当前运行时实例的链接：不动（幂等，可反复调用）。
   *
   * 单个包失败只计数、不抛异常：这里跑在安装流程里，绝不能因为一个包把整次更新打断。
   * 全程只做 readdir / lstat / realpath / rm / symlink，不执行任何脚本。
   *
   * [boundary] 是调用方给定的处理范围（插件的版本目录）。返回计数对象；`failed` 含
   * "因安全边界拒绝处理"的条目，`refused` 是其子集。
   */
  function reconcileRuntimePackages(modulesDirectory, boundary) {
    const summary = { scanned: 0, linked: 0, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0 }
    let realBase
    try { realBase = fs.realpathSync(modulesDirectory) } catch { return summary }
    // 安全边界一：处理范围本身必须落在调用方给定目录内，且整体仍在 guest root 内。
    // node_modules 被换成指向别处的链接时，这里直接放弃，什么都不做。
    if (!within(root, realBase) || !within(boundary, realBase)) return summary
    // 安全边界二：被删除或改建的条目，其**真实路径**必须落在本次处理的 node_modules 内；
    // 每一层目录在进入前也要过同一检查。悬空链接无法 realpath，因此改为校验它所在目录的
    // 真实路径（unlink 只删除链接本身，不会跟随）。
    const inside = target => {
      try { return within(realBase, fs.realpathSync(target)) } catch { return false }
    }
    const reject = () => { summary.failed += 1; summary.refused += 1 }
    /**
     * 对齐单个作用域包。返回 true 表示该条目当前已指向当前运行时实例（含本次刚改建），
     * 调用方无需再进入它的 node_modules。
     */
    const reconcile = (target, name, stats) => {
      summary.scanned += 1
      // 名字先过 NAME：resolvePackage 内部用 validName，非法名字会直接抛受控错误。
      if (name.length > 214 || name.includes('..') || !NAME.test(name)) return false
      let installed
      try { installed = resolvePackage(name) } catch { reject(); return false }
      // 运行时没有这个名字：保留插件自带副本，只处理"与运行时重复"的包。
      if (installed === null) return false
      if (stats.isSymbolicLink()) {
        let resolved = null
        try { resolved = fs.realpathSync(target) } catch { resolved = null }
        // 有效链接且已指向当前运行时实例：幂等返回。
        if (resolved !== null && resolved === installed && exists(path.join(resolved, 'package.json'))) {
          summary.unchanged += 1
          return true
        }
        if (!inside(path.dirname(target))) { reject(); return false }
        try { fs.unlinkSync(target) } catch { summary.failed += 1; return false }
      } else {
        if (!stats.isDirectory()) return false
        const staged = versionOf(target)
        const current = versionOf(installed)
        if (staged !== null && current !== null && staged !== current) summary.versionMismatch += 1
        if (!inside(target)) { reject(); return false }
        try { fs.rmSync(target, { recursive: true, force: true }) } catch { summary.failed += 1; return false }
      }
      try {
        fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 })
        fs.symlinkSync(installed, target, 'junction')
        summary.linked += 1
        return true
      } catch {
        // 建链失败留给后续解析报错，不因此让整次安装失败。
        summary.failed += 1
        return false
      }
    }
    let directories = 0
    const visit = (directory, depth) => {
      if (depth > RECONCILE_MAX_DEPTH || directories >= RECONCILE_MAX_DIRECTORIES) return
      if (summary.scanned >= RECONCILE_MAX_PACKAGES || !inside(directory)) return
      directories += 1
      let items
      try { items = fs.readdirSync(directory, { withFileTypes: true }) } catch { return }
      // 本层的包目录：作用域目录只是容器，展开成 `@scope/name` 两级。
      const packages = []
      for (const item of items) {
        if (item.name === '.bin' || item.name.startsWith('.')) continue
        const full = path.join(directory, item.name)
        if (!item.name.startsWith('@')) { packages.push([item.name, full]); continue }
        let stats
        try { stats = fs.lstatSync(full) } catch { continue }
        // 作用域目录只展开真实目录：链接（含悬空）一律不进入，避免把动作带出处理范围。
        if (!stats.isDirectory()) continue
        let children
        try { children = fs.readdirSync(full, { withFileTypes: true }) } catch { continue }
        for (const child of children) {
          if (child.name.startsWith('.') || child.name.startsWith('@')) continue
          packages.push([item.name + '/' + child.name, path.join(full, child.name)])
        }
      }
      for (const [name, full] of packages) {
        if (summary.scanned >= RECONCILE_MAX_PACKAGES) return
        let stats
        try { stats = fs.lstatSync(full) } catch { continue }
        // 作用域包一律交给 reconcile 判定：真实副本与悬空链接都要处理，普通文件它自己会跳过。
        // 已指向运行时实例的条目不再进入：运行时本体不是本次处理的删除对象。
        if (name.startsWith(RUNTIME_SCOPE) && reconcile(full, name, stats)) continue
        if (!stats.isDirectory()) continue
        // 嵌套 node_modules：npm 在 --legacy-peer-deps 下会把冲突的副本嵌在包目录内。
        const nested = path.join(full, 'node_modules')
        if (exists(nested)) visit(nested, depth + 1)
      }
    }
    visit(modulesDirectory, 0)
    return summary
  }
  /** 读取包目录的版本号；读不到返回 null（不抛异常，版本只用于计数）。 */
  function versionOf(directory) {
    try {
      const value = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'))
      return typeof value.version === 'string' && value.version.length <= 64 ? value.version : null
    } catch { return null }
  }
  /**
   * 修复已安装插件：扫描 `versions/<事务目录>/node_modules`，把其中的 `@deepseek-ai/*`
   * 真实副本与悬空链接对齐到当前运行时实例。
   *
   * 用于「不重新下载插件就把已装坏的插件恢复」：运行时升级后插件目录被保留，而里面指向
   * 旧运行时副本的绝对路径（带旧版本号与 peer 哈希）已经不存在，插件加载会直接失败。
   * 幂等、可重复调用，失败只计数（受控错误码由 CLI 层给出，这里不抛未分类异常）。
   */
  function repair() {
    const summary = { versions: 0, scanned: 0, linked: 0, unchanged: 0, versionMismatch: 0, failed: 0, refused: 0 }
    let items
    try { items = fs.readdirSync(path.join(home, 'versions'), { withFileTypes: true }) } catch { return summary }
    for (const item of items) {
      if (summary.versions >= REPAIR_MAX_VERSIONS) break
      if (!TRANSACTION_ID.test(item.name)) continue
      const directory = path.join(home, 'versions', item.name)
      let stats
      try { stats = fs.lstatSync(directory) } catch { continue }
      if (!stats.isDirectory()) continue
      let realDirectory
      try { realDirectory = fs.realpathSync(directory) } catch { continue }
      if (!within(home, realDirectory)) continue
      const modulesDirectory = path.join(directory, 'node_modules')
      // node_modules 被换成指向版本目录之外的链接时整条跳过：不计入处理数，也不动任何东西。
      let realModules
      try { realModules = fs.realpathSync(modulesDirectory) } catch { continue }
      if (!within(realDirectory, realModules)) continue
      summary.versions += 1
      const result = reconcileRuntimePackages(modulesDirectory, directory)
      for (const key of ['scanned', 'linked', 'unchanged', 'versionMismatch', 'failed', 'refused']) summary[key] += result[key]
    }
    return summary
  }
  /**
   * 模块图探测（**只读**）：数一数 `@deepseek-ai/dsh-tools` 在访客里出现了几处、分别是什么形态，
   * 以及**不同真实路径数**（`distinctRealpaths`）。
   *
   * 为什么需要它：`dsh-tools` 的调度器服务用 `Symbol('@deepseek-ai/dsh-tools.scheduler')` 注册，
   * 而 Symbol 在**每个物理模块副本里各造一个身份**。只要同一进程里加载到两份真实副本，
   * `ctx.tools[调度器 Symbol]` 就是 undefined，之后**每一次工具调用**都在 `.prepare` 上抛
   * `Cannot read properties of undefined (reading 'prepare')`（调用点：dsh-agent-loop/lib/index.js:588）。
   * 也就是说：**`distinctRealpaths > 1` 就是「工具调用全部失败」的直接证据**，不必再靠推断。
   *
   * 与 repair 的区别（决定了两者必须分开暴露）：
   *  - repair 会删改文件，且按运行时代次指纹只跑一次；本函数**从不修改任何东西**，
   *    也不参与代次指纹，因此可以在每次 Harness 启动时独立触发；
   *  - repair 只处理「已安装插件的版本目录」，而副本可能出现在别处（profiles、plugins、
   *    pnpm 隔离目录），本函数把候选根整体看一遍。
   *
   * 判据只认真实路径：同一个真实目录被 N 个链接引用就只算一份（出现次数 N 不构成重复），
   * 只有两个**不同的真实目录**才会让 Symbol 身份分裂。悬空链接解析不到任何模块，只计出现次数。
   *
   * 失败一律吞掉：单个候选根不存在、不可读、遍历超限都只影响计数，绝不抛异常——
   * 探测跑在 Harness 启动前，不能因为诊断把启动打断。
   *
   * 只回传计数，不回传路径（与既有受控载荷风格一致）：调用方拿到的是
   * `{ total, realCopies, links, distinctRealpaths }`，其中 `total = realCopies + links`。
   */
  function scanRuntimeGraph() {
    const summary = { total: 0, realCopies: 0, links: 0, distinctRealpaths: 0 }
    const realpaths = new Set()
    /** 链接的「字面目标」→ 真实路径缓存（null = 悬空）。见下面 linkRealpath 的说明。 */
    const linkTargets = new Map()
    /**
     * 求一个（真实存在的）路径的真实路径，求不到只影响 Set，不影响出现次数计数。
     */
    const addRealpath = value => {
      try { realpaths.add(fs.realpathSync(value)) } catch { /* 求不到真实路径时只计出现次数。 */ }
    }
    /**
     * 求一个链接的真实路径，并按「字面目标」缓存结果。
     *
     * 为什么必须缓存：realpath 每次都要打开句柄（实测每次约 3ms），而 pnpm 的几百个虚拟store
     * 条目里绝大多数链接都指向同一份真实目录 —— 逐个 realpath 会把一次探测从几十毫秒拖到一秒
     * 以上（实测 400 个链接 = 1.1s）。readlink + 相对路径解析便宜一个数量级，且同一个绝对目标
     * 解析结果必然相同，因此「先字面目标去重、再 realpath」不改变判据，只去掉重复开销。
     */
    const linkRealpath = literal => {
      if (!linkTargets.has(literal)) {
        try { linkTargets.set(literal, fs.realpathSync(literal)) } catch { linkTargets.set(literal, null) }
      }
      const resolved = linkTargets.get(literal)
      if (resolved !== null) realpaths.add(resolved)
    }
    /**
     * 记一处出现位置：真实目录计入 realCopies，符号链接计入 links；两种形态都先求真实路径再放进
     * Set —— **Set.size 就是「不同真实路径数」，这是唯一判据**。
     */
    const record = candidate => {
      let stats
      try { stats = fs.lstatSync(candidate) } catch { return }
      if (stats.isSymbolicLink()) {
        summary.links += 1
        // 悬空链接（运行时升级后指向旧路径）解析不到任何模块：只计出现次数，不构成第二份副本。
        let literal = null
        try { literal = path.resolve(path.dirname(candidate), fs.readlinkSync(candidate)) } catch { literal = null }
        if (literal !== null) linkRealpath(literal)
      } else if (stats.isDirectory()) {
        summary.realCopies += 1
        addRealpath(candidate)
      } else {
        // 既不是目录也不是链接（同名普通文件）：不是一个模块副本，忽略。
        return
      }
      summary.total += 1
    }
    /**
     * 定点检查一个「模块目录」（node_modules / .pnpm）里的 `@deepseek-ai/dsh-tools`。
     *
     * 只走固定的一段路径、每段先 lstat 判类型：**不 readdir、不跟随链接**。
     * 这是探测能在几百个虚拟store 条目上保持廉价的关键 —— 每个条目 1~2 次 lstat，
     * 而不是把它的依赖清单整个读一遍（pnpm 里每个条目都带几十条依赖链接，读一遍的成本高一个量级）。
     */
    const check = modulesDirectory => {
      // 中间段 @deepseek-ai 本身是链接时不进入：跟随它就可能走到候选根之外。
      let scope
      try { scope = fs.lstatSync(path.join(modulesDirectory, GRAPH_SCOPE)) } catch { return }
      if (scope.isSymbolicLink() || !scope.isDirectory()) return
      record(path.join(modulesDirectory, GRAPH_SCOPE, GRAPH_PACKAGE))
    }
    /**
     * 进入一个「模块目录」：它的子项是包目录（`node_modules`）、作用域目录或嵌套模块目录。
     *
     * 不无差别递归的原因：`node_modules` 下每个包都带 lib/dist 等源码目录，无差别递归会瞬间
     * 吃光目录预算；而**链接目录一律不进入**（readdir 的 Dirent 对链接不跟随，`isDirectory()`
     * 为 false），这正是「不走出候选根之外」的实现点。
     */
    const walk = (modulesDirectory, depth, budget) => {
      if (depth > GRAPH_MAX_DEPTH) return
      if (budget.directories >= GRAPH_MAX_DIRECTORIES || budget.entries >= GRAPH_MAX_ENTRIES) return
      // 模块目录自身可能是链接（例如整个 node_modules 被换成链接）：不跟随，整棵子树跳过。
      let stats
      try { stats = fs.lstatSync(modulesDirectory) } catch { return }
      if (!stats.isDirectory()) return
      budget.directories += 1
      check(modulesDirectory)
      let items
      try { items = fs.readdirSync(modulesDirectory, { withFileTypes: true }) } catch { return }
      // pnpm 的虚拟store：`.pnpm/<条目>/node_modules/` 里除条目自身（唯一的真实目录）外全是链接，
      // 而链接不跟随 ⇒ 每个条目只需定点检查一次。check-runtime-dedupe.mjs 依据同一事实统计副本。
      const store = path.basename(modulesDirectory) === '.pnpm'
      for (const item of items) {
        if (budget.directories >= GRAPH_MAX_DIRECTORIES || budget.entries >= GRAPH_MAX_ENTRIES) return
        budget.entries += 1
        // 点目录（.bin 等）一律跳过；`.pnpm` 是虚拟store，必须进去。
        if (item.name.startsWith('.') && item.name !== '.pnpm') continue
        if (!item.isDirectory()) continue
        const full = path.join(modulesDirectory, item.name)
        if (GRAPH_MODULE_DIRECTORIES.has(item.name)) { walk(full, depth + 1, budget); continue }
        // 作用域目录由 check() 统一定点处理，它不是包目录。
        if (item.name === GRAPH_SCOPE) continue
        // 包目录：store 条目只做定点检查；npm 的嵌套布局必须继续下探
        // （`node_modules/<包>/node_modules/@deepseek-ai/...` 正是 D1 那类嵌套副本的藏身处）。
        if (store) { check(path.join(full, 'node_modules')); continue }
        walk(path.join(full, 'node_modules'), depth + 1, budget)
      }
    }
    /**
     * 浏览一个非 node_modules 的候选根（`opt/dsh/plugins`、`root/.dsh-mobile/plugin-manager`）：
     * 只为找到其中的模块目录与包目录。带 `node_modules` 的目录按包目录处理，其余在浅层继续下探。
     */
    const browse = (directory, depth, budget) => {
      if (depth > GRAPH_MAX_DEPTH) return
      if (budget.directories >= GRAPH_MAX_DIRECTORIES || budget.entries >= GRAPH_MAX_ENTRIES) return
      let stats
      try { stats = fs.lstatSync(directory) } catch { return }
      if (!stats.isDirectory()) return
      budget.directories += 1
      let items
      try { items = fs.readdirSync(directory, { withFileTypes: true }) } catch { return }
      for (const item of items) {
        if (budget.directories >= GRAPH_MAX_DIRECTORIES || budget.entries >= GRAPH_MAX_ENTRIES) return
        budget.entries += 1
        if (item.name.startsWith('.')) continue
        // 链接与普通文件都不进入：不跟随链接目录。
        if (!item.isDirectory()) continue
        const full = path.join(directory, item.name)
        if (GRAPH_MODULE_DIRECTORIES.has(item.name)) { walk(full, depth + 1, budget); continue }
        let nested = null
        try { nested = fs.lstatSync(path.join(full, 'node_modules')) } catch { nested = null }
        if (nested !== null && nested.isDirectory()) { walk(path.join(full, 'node_modules'), depth + 1, budget); continue }
        if (depth < GRAPH_BROWSE_DEPTH) browse(full, depth + 1, budget)
      }
    }
    for (const candidate of GRAPH_ROOTS) {
      // 每个候选根各自一份预算：见 GRAPH_MAX_DIRECTORIES 的说明。
      const directory = path.join(root, candidate)
      const budget = { directories: 0, entries: 0 }
      if (GRAPH_MODULE_DIRECTORIES.has(path.basename(directory))) walk(directory, 0, budget)
      else browse(directory, 0, budget)
    }
    summary.distinctRealpaths = realpaths.size
    return summary
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
      if (!relativeName || !NAME.test(relativeName) || relativeName.includes('..') || protectedPackage(relativeName) || backupParts.length !== 2 || !TRANSACTION_ID.test(backupParts[0]) || !/^[0-9]{1,3}$/.test(backupParts[1])) fail('PLUGIN_RECOVERY_FAILED')
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
  /** 运行时实际安装的 dsh 版本；读不到时返回 null（此时退回旧的 latest 行为）。 */
  function runtimeDshVersion() {
    const installed = resolvePackage('@deepseek-ai/dsh')
    if (!installed) return null
    try {
      const pkg = read(path.join(installed, 'package.json'))
      return typeof pkg.version === 'string' && VERSION.test(pkg.version) ? pkg.version : null
    } catch {
      return null
    }
  }

  /** 问 npm 某个具体版本声明的 dsh 引擎范围；查询失败或未声明都按"未声明"处理。 */
  function engineRangeOf(npm, id, version, common, environment) {
    const probe = spawnSync(
      process.execPath,
      [npm, 'view', `${id}@${version}`, 'dsh.engines.dsh', '--json', ...common],
      { env: environment, encoding: 'utf8', timeout: 20000, maxBuffer: 65536 },
    )
    if (probe.status !== 0) return null
    let range
    try { range = JSON.parse(probe.stdout) } catch { return null }
    return typeof range === 'string' && range.length > 0 && range.length <= 256 ? range : null
  }

  function npmInstall(id, directory) {
    const npm = path.join(root, 'opt/node/lib/node_modules/npm/bin/npm-cli.js')
    safe(npm)
    if (!exists(npm)) fail('PLUGIN_UPDATER_MISSING')
    // 使用隔离配置和参数数组；不继承模型凭据，不执行依赖安装脚本。
    const environment = { HOME: directory, PATH: path.dirname(process.execPath) + ':/usr/bin:/bin', LANG: 'C.UTF-8', TMPDIR: directory }
    const common = ['--registry=https://registry.npmjs.org', '--userconfig=' + path.join(directory, 'npmrc'), '--globalconfig=' + path.join(directory, 'global-npmrc')]
    const runtimeDsh = runtimeDshVersion()
    let version = null
    if (runtimeDsh === null) {
      // 读不到运行时版本（异常安装形态）时保持旧行为，不因为探测失败而拒绝更新。
      const latest = spawnSync(process.execPath, [npm, 'view', id, 'dist-tags.latest', '--json', ...common], { env: environment, encoding: 'utf8', timeout: 45000, maxBuffer: 65536 })
      if (latest.status !== 0) fail('PLUGIN_UPDATE_FAILED')
      let parsed
      try { parsed = JSON.parse(latest.stdout) } catch { fail('PLUGIN_UPDATE_FAILED') }
      if (typeof parsed !== 'string' || parsed.length > 64 || !VERSION.test(parsed)) fail('PLUGIN_UPDATE_FAILED')
      version = parsed
    } else {
      // 按运行时 dsh 版本挑选引擎兼容的最高版本，而不是无脑 latest。
      const listed = spawnSync(process.execPath, [npm, 'view', id, 'versions', '--json', ...common], { env: environment, encoding: 'utf8', timeout: 45000, maxBuffer: 1024 * 1024 })
      if (listed.status !== 0) fail('PLUGIN_UPDATE_FAILED')
      let versions
      try { versions = JSON.parse(listed.stdout) } catch { fail('PLUGIN_UPDATE_FAILED') }
      if (typeof versions === 'string') versions = [versions]
      if (!Array.isArray(versions) || versions.length === 0 || versions.length > 5000) fail('PLUGIN_UPDATE_FAILED')
      const selection = selectNewestCompatible(
        versions,
        runtimeDsh,
        candidate => engineRangeOf(npm, id, candidate, common, environment),
      )
      if (selection === null) fail('PLUGIN_ENGINE_UNSUPPORTED', `${id} dsh=${runtimeDsh}`)
      version = selection.version
    }
    atomic(path.join(directory, 'package.json'), { name: 'dsh-mobile-plugin-update', version: '1.0.0', private: true })
    const result = spawnSync(process.execPath, [npm, 'install', id + '@' + version, '--ignore-scripts', '--legacy-peer-deps', '--bin-links=false', '--no-audit', '--no-fund', '--omit=dev', '--fetch-retries=1', '--fetch-timeout=30000', ...common], {
      cwd: directory, env: environment, encoding: 'utf8', timeout: 180000, maxBuffer: 1024 * 1024,
    })
    if (result.status !== 0) fail('PLUGIN_UPDATE_FAILED')
  }
  /** 解析闭包补全：详见 updateInternal 里的调用点说明。 */
  function linkUnresolvedRuntimeDependencies(stageModules, stagedNames) {
    const present = new Set(stagedNames)
    const candidates = new Set()
    let files = 0
    let bytes = 0
    const scan = directory => {
      let items
      try { items = fs.readdirSync(directory, { withFileTypes: true }) } catch { return }
      for (const item of items) {
        if (files >= SCAN_MAX_FILES || bytes >= SCAN_MAX_BYTES || candidates.size >= MAX_LINKED_DEPENDENCIES * 4) return
        if (item.isSymbolicLink()) continue
        const full = path.join(directory, item.name)
        if (item.isDirectory()) {
          // 不进入已建好链接的依赖，也不进入点目录：它们不是插件自身的源码。
          if (item.name === 'node_modules' || item.name.startsWith('.')) continue
          scan(full)
          continue
        }
        if (!item.isFile() || !SCAN_EXTENSIONS.includes(path.extname(item.name))) continue
        files += 1
        try {
          const info = fs.statSync(full)
          if (info.size === 0 || info.size > SCAN_MAX_FILE_BYTES) continue
          bytes += info.size
          collectBareSpecifiers(fs.readFileSync(full, 'utf8')).forEach(name => candidates.add(name))
        } catch {
          // 单个文件读失败不影响其余扫描。
        }
      }
    }
    scan(stageModules)
    let linked = 0
    for (const name of candidates) {
      if (linked >= MAX_LINKED_DEPENDENCIES) break
      // staging 顶层已有的名字一律不动：那是 npm 的解析结果，必须原样保留。
      if (present.has(name)) continue
      const target = path.join(stageModules, name)
      if (exists(target)) continue
      const installed = resolvePackage(name)
      if (installed === null) continue
      try {
        fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 })
        fs.symlinkSync(installed, target, 'junction')
        present.add(name)
        linked += 1
      } catch {
        // 建链失败留给后续解析报错，不因此让整次更新失败。
      }
    }
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
    // 安装后消重：npm 会把插件依赖的 `@deepseek-ai/*` 装成真实副本，留在插件目录里就是
    // 同一份运行时服务模块的第二份物理副本（Symbol 身份失配 → 所有工具调用失败）。
    // 必须在读取 stagedNames 之前完成：顶层副本会被换成链接，后续切换逻辑认得这种链接。
    const runtimeDedupe = reconcileRuntimePackages(stageModules, directory)
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
    // 解析闭包补全。
    //
    // staging 位于 ~/.dsh-mobile/plugin-manager/versions/<txn>，而 Node 是按真实路径向上
    // 解析依赖的：它永远走不到 profiles/node_modules，于是 react / react-dom /
    // @deepseek-ai/cordis / dsh-settings 这类**宿主提供、未随插件安装**的依赖会全部
    // ERR_MODULE_NOT_FOUND，表现为"模块不完整"。它们既不是插件的依赖，npm 也就不会装。
    //
    // 这里把 staging 内解析不到、但运行时确实存在的裸导入链回运行时实例。只在 staging
    // 内部建链、不写事务日志：失败时整个 staging 会被丢弃，不会影响任何既有安装。
    linkUnresolvedRuntimeDependencies(stageModules, stagedNames)
    const entries = []
    for (const name of stagedNames) {
      const source = safe(path.join(stageModules, name))
      const installed = resolvePackage(name)
      if (fs.lstatSync(source).isSymbolicLink()) {
        // 安装后消重已把运行时包换成指向运行时实例的链接，结果与下面的核心 SDK 分支一致。
        // 只接受确实指向运行时实例的链接：npm 装出的其他链接（例如 file: 依赖）仍旧拒绝。
        let resolved = null
        try { resolved = fs.realpathSync(source) } catch { resolved = null }
        if (!protectedPackage(name) || installed === null || resolved !== installed) fail('PLUGIN_UPDATE_FAILED')
        continue
      }
      const pkg = read(path.join(source, 'package.json'))
      if (pkg.name !== name) fail('PLUGIN_UPDATE_FAILED')
      if (protectedPackage(name)) {
        if (!installed || read(path.join(installed, 'package.json')).version !== pkg.version) {
          fail('PLUGIN_DEPENDENCY_UNSUPPORTED', `${name} ${pkg.version} != ${installed ? read(path.join(installed, 'package.json')).version : 'missing'}`)
        }
        // 核心 SDK 始终使用运行时已有实例，避免加载第二份服务容器。
        fs.rmSync(source, { recursive: true })
        fs.symlinkSync(installed, source, 'junction')
        continue
      }
      if (name !== id && installed) {
        // 不覆盖其他插件正在共享的依赖；不兼容时整次更新失败并保留原版本。
        if (read(path.join(installed, 'package.json')).version !== pkg.version) {
          fail('PLUGIN_DEPENDENCY_UNSUPPORTED', `${name} ${pkg.version} != ${read(path.join(installed, 'package.json')).version}`)
        }
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
      // 消重计数随结果一起回传：只含计数，不含任何路径。
      return { ...result, runtimeDedupe }
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
  return { list, setEnabled, setChildEnabled, update, recover, repair, scanRuntimeGraph }
}

module.exports = {
  createManager,
  validName,
  within,
  // 供单测直接覆盖版本选择语义（无网络、无 npm）。
  parseVersion,
  compareVersions,
  satisfiesRange,
  selectNewestCompatible,
  // 供单测覆盖受控详情过滤（决定哪些字符能回到 WebView）。
  safeDetail,
  // 供单测覆盖裸导入抽取（决定哪些宿主依赖会被链进 staging）。
  packageNameOf,
  collectBareSpecifiers,
  // 供单测断言模块图探测的预算不会截断真实规模的依赖树（截断 = 漏算真实副本 = 判据失效）。
  GRAPH_LIMITS: Object.freeze({
    depth: GRAPH_MAX_DEPTH,
    directories: GRAPH_MAX_DIRECTORIES,
    entries: GRAPH_MAX_ENTRIES,
  }),
}
if (require.main === module) {
  try {
    const manager = createManager('/')
    const [operation, id, flag, childId] = process.argv.slice(2)
    let result
    if (operation === 'list') result = manager.list()
    else if (operation === 'recover') { manager.recover(); result = { recovered: true } }
    else if (operation === 'repair') { manager.recover(); result = manager.repair() }
    // 模块图探测是只读的：不走 recover()，也不参与 repair 的代次指纹，可随时独立触发。
    else if (operation === 'graph') result = manager.scanRuntimeGraph()
    else if (operation === 'enable' && (flag === 'true' || flag === 'false')) { manager.recover(); result = manager.setEnabled(id, flag === 'true') }
    else if (operation === 'child' && (flag === 'true' || flag === 'false')) { manager.recover(); result = manager.setChildEnabled(id, childId, flag === 'true') }
    else if (operation === 'update') result = manager.update(id)
    else fail('PLUGIN_INPUT_INVALID')
    const output = JSON.stringify(result)
    if (Buffer.byteLength(output) > 220000) fail('PLUGIN_CONFIG_INVALID')
    process.stdout.write(output + '\n')
  } catch (error) {
    const code = /^PLUGIN_[A-Z_]+$/.test(error.message) ? error.message : 'PLUGIN_OPERATION_FAILED'
    // 详情再次校验后才回传：错误对象可能来自任何一层，不能假定它已经被过滤过。
    const detail = safeDetail(error.detail)
    process.stdout.write(JSON.stringify(detail === null ? { error: code } : { error: code, detail }) + '\n')
    process.exitCode = 1
  }
}
