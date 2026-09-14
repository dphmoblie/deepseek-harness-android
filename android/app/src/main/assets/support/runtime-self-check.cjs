'use strict'

/**
 * 运行时自检（访客侧，CommonJS）。
 *
 * 运行方式：`/opt/node/bin/node /root/.dsh-mobile/runtime-self-check.cjs <check|repair>`
 *
 * 为什么需要它：设备上 dsh 的 `bash` 工具持续报 `PTY shell exited during startup`，而应用自己的
 * Ubuntu 终端（同一条 PRoot 启动、不经 PTY、不挂沙箱）是正常的 —— 断链只可能落在
 * 「沙箱不可用 / 真实 confine exec 失败 / PTY（node-pty）本身失败」三者之一。
 * 本脚本把三种可能各自单独测一遍，并顺手回答「dsh 家目录与附件目录能不能写」。
 *
 * 输出契约（**整个脚本只在最后输出一行**，绝不输出路径或原始报错文本）：
 *   check  → {"ok":true,"checks":[{"id":"shell","status":"ok"}, ...]}
 *   repair → {"ok":true,"repaired":<int>,"candidates":<int>}
 *   失败   → {"error":"<受控码>"}，退出码 1
 *
 * 所有路径只在本脚本内部使用：对外字段只有固定枚举（id / status / code）与计数。
 * 目标缺失、目录不可读、遍历超限都只影响判定结果，绝不抛异常打断整次自检 ——
 * 自检本身失败会让排障失去唯一的第一手证据，比「某项判不出来」代价大得多。
 */

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')
const { createRequire } = require('node:module')

/** 访客根：脚本始终在访客内运行，因此根就是 `/`（与 plugin-manager.cjs 的 CLI 调用一致）。 */
const root = '/'
const home = path.join(root, 'root/.dsh')
const attachments = path.join(home, 'attachments')
const nodeBinary = path.join(root, 'opt/node/bin/node')
const shellBinary = path.join(root, 'bin/bash')
/** dsh 的安装锚点：node-pty 按它解析，等价于运行时自己加载模块的方式。 */
const dshAnchor = path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh/package.json')

/** PTY 冒烟测试用的标记：只有真正在 PTY 里跑起来的 shell 才会把它写回来。 */
const MARKER = '__DSH_SELF_CHECK_OK__'
const PTY_TIMEOUT_MS = 5000
const PROBE_TIMEOUT_MS = 3000
const EXEC_TIMEOUT_MS = 5000
/** 单次 spawnSync 的输出上限：探针只需要判定退出码，输出一律不回传。 */
const SPAWN_MAX_BUFFER = 65536
/** 写完之后最多再等这么久就强制退出：node-pty 的句柄可能让事件循环迟迟不空。 */
const FORCE_EXIT_MS = 1000

// ---------------------------------------------------------------------------
// 候选根与遍历上限
//
// 定位只做三件事：定点拼路径 → 解码 pnpm 隔离目录 → 有界遍历兜底。
// 前两步在**已验证过的安装形态**上一次命中，不需要 readdir；只有布局超出预期时才启动遍历，
// 而遍历带着深度、目录数、条目数三重上限（失控保护，不是常规节流）。
// ---------------------------------------------------------------------------

/** pnpm/npm 布局的模块根：dsh 本体与插件依赖都在这些根之下。 */
const MODULES_ROOTS = [
  'opt/dsh/node_modules',
  'root/.dsh/profiles/node_modules',
  'root/.dsh/profiles/web/node_modules',
]

/** 系统级 bin 根：apt 装的 rg 在这里。定点 lstat 每个名字一次，代价与目录大小无关。 */
const BIN_ROOTS = [
  'opt/dsh/bin',
  'opt/node/bin',
  'usr/local/bin',
  'bin',
  'usr/bin',
]

/**
 * 搜索目标表。
 *
 * `names` 是文件名/目录名；`hints` 是包名（**不写死版本号**：pnpm 隔离目录按包名解码，
 * 运行时升级换版本也不会找不到）；`entries` 是包目录内的入口相对路径；
 * `kind` 决定命中什么类型算有效（`file` 接受普通文件与符号链接，`directory` 只接受目录）。
 *
 * 包名来自构建期已验证的入口：`scripts/verify-bundle.py` 要求 `opt/dsh/` 下以
 * `/bin/rg`、`/bin/landlock-run` 结尾的条目存在且为 0755 的 ARM64 ELF，
 * 这里沿用同一判据，不另立一套。
 */
const TARGETS = [
  {
    key: 'launcher',
    names: ['landlock-run'],
    hints: ['@deepseek-ai/node-addon-system-linux-arm64', 'node-addon-system-linux-arm64', 'landlock-run'],
    entries: ['bin/landlock-run'],
    kind: 'file',
  },
  {
    key: 'ripgrep',
    names: ['rg'],
    hints: ['@vscode/ripgrep-linux-arm64', '@vscode/ripgrep', 'ripgrep-linux-arm64'],
    entries: ['bin/rg'],
    kind: 'file',
  },
  {
    key: 'pty',
    names: ['node-pty'],
    hints: ['node-pty'],
    entries: [],
    kind: 'directory',
  },
]

/** 遍历上限：按候选根各自重置，避免一个 `.pnpm` 把预算吃光后其余根一个都扫不到。 */
const MAX_DEPTH = 6
const MAX_DIRECTORIES = 192
const MAX_ENTRIES = 2048
/** `.pnpm` 条目扫描上限（一个候选根的虚拟store 条目数）。 */
const MAX_STORE_ENTRIES = 4096

function lstatOrNull(target) {
  try { return fs.lstatSync(target) } catch { return null }
}

function statOrNull(target) {
  try { return fs.statSync(target) } catch { return null }
}

/** 目录判定跟随符号链接：pnpm 里 `node_modules/<包>` 本身就是指向真实目录的链接。 */
function isDirectory(target) {
  const stats = statOrNull(target)
  return stats !== null && stats.isDirectory()
}

function isFile(target) {
  const stats = statOrNull(target)
  return stats !== null && stats.isFile()
}

function isExecutable(target) {
  try { fs.accessSync(target, fs.constants.X_OK); return true } catch { return false }
}

/** 命中判定：文件目标接受普通文件与符号链接（链接指向真实二进制），目录目标必须是目录。 */
function matches(target, candidate) {
  if (target.kind === 'directory') return isDirectory(candidate)
  const stats = lstatOrNull(candidate)
  return stats !== null && (stats.isFile() || stats.isSymbolicLink())
}

/**
 * 定点候选：直接拼出「已验证过的入口路径」再 lstat，**不 readdir、不跟随链接**。
 *
 * 这是定位在 pnpm 隔离布局里保持廉价的关键：`node_modules/@作用域/包` 通常是符号链接，
 * 组合路径的一次 stat 会自动跟到 `.pnpm` 里的真实文件，不必把几百个虚拟store 条目逐个展开。
 * 顺序即优先级：模块根里的运行时副本排在系统 bin 之前（要修的是 dsh 用的那一份）。
 */
function fixedCandidates(target) {
  const candidates = []
  for (const modules of MODULES_ROOTS) {
    for (const hint of target.hints) {
      for (const entry of target.entries) candidates.push(path.join(root, modules, hint, entry))
      // 目录型目标（node-pty）：包目录本身就是入口。
      if (target.entries.length === 0) candidates.push(path.join(root, modules, hint))
    }
  }
  for (const modules of MODULES_ROOTS) {
    for (const name of target.names) candidates.push(path.join(root, modules, '.bin', name))
  }
  for (const bin of BIN_ROOTS) {
    for (const name of target.names) candidates.push(path.join(root, bin, name))
  }
  return candidates
}

/**
 * 解码 pnpm 虚拟store 的条目名：`@vscode+ripgrep-linux-arm64@1.18.0` → `@vscode/ripgrep-linux-arm64`，
 * `node-pty@1.0.0` → `node-pty`；peer 后缀（`pkg@1.0.0_react@18.0.0`）取第一个 `@` 之前的部分。
 * 不是标准条目名（点开头、没有版本段）返回 null。
 */
function decodeStoreEntry(name) {
  if (typeof name !== 'string' || name.length === 0 || name.length > 214) return null
  if (name.startsWith('.')) return null
  const separator = name.indexOf('@', 1)
  if (separator <= 0) return null
  const spec = name.slice(0, separator)
  const rest = name.slice(separator + 1)
  if (rest.length === 0 || !/^[0-9]/.test(rest)) return null
  if (spec.startsWith('@')) {
    const plus = spec.indexOf('+')
    if (plus <= 1 || plus === spec.length - 1) return null
    return spec.slice(0, plus) + '/' + spec.slice(plus + 1)
  }
  return spec.includes('+') ? null : spec
}

/**
 * pnpm 隔离布局候选项：`<根>/.pnpm/<包>@<版本>/node_modules/<包>` 下的入口。
 * 只对包名命中提示的条目拼路径（一次 readdir + 少量 lstat），不做无差别展开。
 */
function storeCandidates(target, modules) {
  const store = path.join(root, modules, '.pnpm')
  if (!isDirectory(store)) return []
  let items
  try { items = fs.readdirSync(store, { withFileTypes: true }) } catch { return [] }
  const candidates = []
  for (const item of items.slice(0, MAX_STORE_ENTRIES)) {
    if (!item.isDirectory()) continue
    const name = decodeStoreEntry(item.name)
    if (name === null || !target.hints.includes(name)) continue
    const packageDirectory = path.join(store, item.name, 'node_modules', name)
    for (const entry of target.entries) candidates.push(path.join(packageDirectory, entry))
    if (target.entries.length === 0) candidates.push(packageDirectory)
  }
  return candidates
}

/**
 * 有界遍历兜底：定点与 pnpm 解码都没命中时才启动（布局超出预期）。
 * 只按**名字**匹配；**链接目录一律不进入**（readdir 的 Dirent 对链接不跟随，这正是不走出候选根的实现点）。
 */
function walk(target, directory, depth, budget) {
  if (depth > MAX_DEPTH) return null
  if (budget.directories >= MAX_DIRECTORIES || budget.entries >= MAX_ENTRIES) return null
  const stats = lstatOrNull(directory)
  if (stats === null || !stats.isDirectory()) return null
  budget.directories += 1
  let items
  try { items = fs.readdirSync(directory, { withFileTypes: true }) } catch { return null }
  for (const item of items) {
    if (budget.directories >= MAX_DIRECTORIES || budget.entries >= MAX_ENTRIES) return null
    budget.entries += 1
    if (item.name.startsWith('.') && item.name !== '.bin' && item.name !== '.pnpm') continue
    const full = path.join(directory, item.name)
    if (target.names.includes(item.name) && matches(target, full)) return full
    if (!item.isDirectory()) continue
    if (!descendable(item.name, depth)) continue
    const hit = walk(target, full, depth + 1, budget)
    if (hit !== null) return hit
  }
  return null
}

/** 哪些目录值得进：模块目录、作用域目录、pnpm 虚拟store 条目、`bin`，以及浅层的包目录。 */
function descendable(name, depth) {
  if (name === 'node_modules' || name === '.pnpm' || name === 'bin') return true
  if (name.startsWith('@')) return true
  if (name.includes('@')) return true
  // npm 布局：`<包>/bin/<名字>`，只在浅层跟进，避免把整个依赖树读一遍。
  return depth < 2
}

/**
 * 定位一个目标：定点 → pnpm 隔离布局 → 有界遍历；命中即返回路径，全都未命中返回 null。
 * 任何一步失败都只当作未命中（缺失就如实报缺失），绝不抛异常。
 */
function locate(target) {
  for (const candidate of fixedCandidates(target)) {
    if (matches(target, candidate)) return candidate
  }
  for (const modules of MODULES_ROOTS) {
    for (const candidate of storeCandidates(target, modules)) {
      if (matches(target, candidate)) return candidate
    }
  }
  for (const modules of MODULES_ROOTS) {
    const hit = walk(target, path.join(root, modules), 0, { directories: 0, entries: 0 })
    if (hit !== null) return hit
  }
  return null
}

function targetOf(key) {
  return TARGETS.find(item => item.key === key)
}

// ---------------------------------------------------------------------------
// 检查项
//
// id 与顺序是冻结契约：shell → node → sandbox_launcher → sandbox_probe → sandbox_exec
// → pty → pty_sandbox → dsh_home → attachments → rg。
// `ok` 不带 code，非 `ok` 必须带 code，且 code 只能取受控集合里的值。
// ---------------------------------------------------------------------------

function entry(id, status, code) {
  return code === undefined ? { id, status } : { id, status, code }
}

/** 写入探测：独占创建一个小文件、写一个字节、立刻删除。写不进去返回 false。 */
function writable(directory) {
  const probe = path.join(directory, '.dsh-self-check-' + crypto.randomUUID() + '.tmp')
  let descriptor = null
  try {
    descriptor = fs.openSync(probe, 'wx', 0o600)
    fs.writeFileSync(descriptor, 'ok')
    return true
  } catch {
    return false
  } finally {
    if (descriptor !== null) {
      try { fs.closeSync(descriptor) } catch { /* 已经关掉了。 */ }
    }
    try { fs.unlinkSync(probe) } catch { /* 没建成就没什么可删。 */ }
  }
}

function checkShell() {
  if (!isFile(shellBinary) || !isExecutable(shellBinary)) return entry('shell', 'fail', 'SHELL_MISSING')
  return entry('shell', 'ok')
}

function checkNode() {
  if (!isFile(nodeBinary) || !isExecutable(nodeBinary)) return entry('node', 'fail', 'NODE_MISSING')
  return entry('node', 'ok')
}

function checkLauncher(launcher) {
  if (launcher === null) return entry('sandbox_launcher', 'fail', 'LAUNCHER_MISSING')
  if (!isExecutable(launcher)) return entry('sandbox_launcher', 'fail', 'LAUNCHER_NOT_EXECUTABLE')
  return entry('sandbox_launcher', 'ok')
}

/**
 * 探测判定：dsh 自己用 `--probe` 决定沙箱后端可用性 —— spawn 失败即 unusable。
 * 因此「探测通过、真实执行失败」正是本功能要切开的那一段，探测结果必须与真实 exec 分开报。
 *
 * 但**启动器不可用（缺失或没有执行位）时绝不能报 `PROBE_UNUSABLE`**：那等于断言「这台设备
 * 的内核不支持 Landlock」，而这一项压根没测过。此时如实报 `skipped`，结论由 sandbox_launcher 那一行给。
 */
function checkProbe(launcher) {
  if (launcher === null || !isExecutable(launcher)) return entry('sandbox_probe', 'skipped', 'LAUNCHER_MISSING')
  const result = spawnSync(launcher, ['--probe'], {
    encoding: 'utf8',
    timeout: PROBE_TIMEOUT_MS,
    maxBuffer: SPAWN_MAX_BUFFER,
  })
  if (result.status !== 0) return entry('sandbox_probe', 'fail', 'PROBE_UNUSABLE')
  const output = typeof result.stdout === 'string' ? result.stdout : ''
  if (output.includes('partially enforced')) return entry('sandbox_probe', 'warn', 'PROBE_PARTIAL')
  return entry('sandbox_probe', 'ok')
}

/**
 * 真实 confine exec：`--ro / --rw <家目录> -- /bin/true`。
 * 启动器级失败一律退出码 125；**没有退出码**（进程没起来、被信号杀死或超时）同属启动器级失败，
 * 其余非零码表示启动器起来了但命令失败 —— 两者混在一起就无法区分「沙箱层」与「命令层」。
 *
 * 与探测同理：启动器不可用时这一项**没有可测的前提**，只报 `skipped`，不报任何「执行失败」。
 */
function checkExec(launcher) {
  if (launcher === null || !isExecutable(launcher)) return entry('sandbox_exec', 'skipped', 'LAUNCHER_MISSING')
  const result = spawnSync(launcher, ['--ro', root, '--rw', home, '--', '/bin/true'], {
    encoding: 'utf8',
    timeout: EXEC_TIMEOUT_MS,
    maxBuffer: SPAWN_MAX_BUFFER,
  })
  if (result.status === 0) return entry('sandbox_exec', 'ok')
  if (result.status === null || result.status === 125) return entry('sandbox_exec', 'fail', 'EXEC_LAUNCHER_FAILED')
  return entry('sandbox_exec', 'fail', 'EXEC_COMMAND_FAILED')
}

/**
 * 加载 node-pty。
 *
 * 先按运行时安装锚点解析（与 plugin-manager.cjs 的 `createRequire` 一致，能穿过 pnpm 隔离布局），
 * 再退回定位到的包目录。`spawn` 不是函数按“加载失败”处理：拿到了壳但没有可用的 PTY 能力。
 */
function loadPty(packageDirectory) {
  const attempts = []
  if (isFile(dshAnchor)) attempts.push(() => createRequire(dshAnchor)('node-pty'))
  if (packageDirectory !== null) attempts.push(() => require(packageDirectory))
  if (attempts.length === 0) return { ok: false, missing: true }
  for (const attempt of attempts) {
    try {
      const module = attempt()
      if (module && typeof module.spawn === 'function') return { ok: true, pty: module }
    } catch { /* 这条解析路径不通：换下一条，全部失败才算加载失败。 */ }
  }
  // 一处都没定位到 ⇒ 模块确实不在；定位到了却加载不进来 ⇒ 模块在但不可用。
  return { ok: false, missing: packageDirectory === null }
}

/**
 * 单次 PTY 冒烟：看到标记即 ok，子进程先退出即 PTY_EXIT_EARLY，超时即 PTY_TIMEOUT。
 * 无论走哪条分支都要杀掉子进程：留着句柄会把宿主侧的等待拖满。
 */
function ptySmoke(pty, command) {
  return new Promise(resolve => {
    let settled = false
    let output = ''
    let child = null
    const finish = outcome => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (child !== null) {
        try { child.kill() } catch { /* 已经退出了。 */ }
      }
      resolve(outcome)
    }
    const timer = setTimeout(() => finish(entry(null, 'fail', 'PTY_TIMEOUT')), PTY_TIMEOUT_MS)
    try {
      child = pty.spawn(command[0], command.slice(1), { name: 'xterm-256color', cols: 80, rows: 24 })
    } catch {
      finish(entry(null, 'fail', 'PTY_LOAD_FAILED'))
      return
    }
    child.onData(data => {
      output += data
      if (output.includes(MARKER)) finish(entry(null, 'ok'))
    })
    child.onExit(() => finish(output.includes(MARKER) ? entry(null, 'ok') : entry(null, 'fail', 'PTY_EXIT_EARLY')))
  })
}

/**
 * PTY 检查项：`sandboxed=false` 是裸 PTY（只测 node-pty 与 /dev/ptmx），
 * `sandboxed=true` 是把同一条命令放进 `landlock-run` 的授权根里再跑一遍。
 *
 * 启动器不可用（缺失或没有执行位）时沙箱内那组直接跳过：拿一个不可用的启动器去做冒烟，
 * 只会得到一条误导性的「PTY 秒退」—— 真正的判据在 sandbox_launcher 那一行。
 */
async function checkPty(module, sandboxed, launcher) {
  const id = sandboxed ? 'pty_sandbox' : 'pty'
  if (!module.ok) {
    return module.missing ? entry(id, 'skipped', 'PTY_MODULE_MISSING') : entry(id, 'fail', 'PTY_LOAD_FAILED')
  }
  if (sandboxed && (launcher === null || !isExecutable(launcher))) return entry(id, 'skipped', 'LAUNCHER_MISSING')
  const command = sandboxed
    ? [launcher, '--ro', root, '--rw', home, '--', shellBinary, '-lc', 'echo ' + MARKER]
    : [shellBinary, '-lc', 'echo ' + MARKER]
  const outcome = await ptySmoke(module.pty, command)
  return entry(id, outcome.status, outcome.code)
}

function checkHome() {
  if (!isDirectory(home)) return entry('dsh_home', 'fail', 'HOME_MISSING')
  // 家目录存在但不可写，正是 `Unable to persist attachment.` 一类的成因，必须与“不存在”分开报。
  if (!writable(home)) return entry('dsh_home', 'fail', 'HOME_NOT_WRITABLE')
  return entry('dsh_home', 'ok')
}

/**
 * 附件目录：不存在是 warn（repair 会建），存在却写不进去是 fail —— 后者不是“还没建”，
 * 而是建了也没用，两者的处置完全不同。
 */
function checkAttachments() {
  const stats = statOrNull(attachments)
  if (stats === null) return entry('attachments', 'warn', 'ATTACHMENTS_MISSING')
  if (!stats.isDirectory()) return entry('attachments', 'fail', 'ATTACHMENTS_NOT_WRITABLE')
  if (!writable(attachments)) return entry('attachments', 'fail', 'ATTACHMENTS_NOT_WRITABLE')
  return entry('attachments', 'ok')
}

function checkRipgrep(ripgrep) {
  if (ripgrep === null) return entry('rg', 'warn', 'RG_MISSING')
  if (!isExecutable(ripgrep)) return entry('rg', 'warn', 'RG_NOT_EXECUTABLE')
  return entry('rg', 'ok')
}

/** 十项检查，顺序即契约顺序。 */
async function runChecks() {
  const launcher = locate(targetOf('launcher'))
  const ripgrep = locate(targetOf('ripgrep'))
  const ptyDirectory = locate(targetOf('pty'))
  // node-pty 只加载一次：两组 PTY 冒烟用的是同一个模块实例。
  const module = loadPty(ptyDirectory)
  return [
    checkShell(),
    checkNode(),
    checkLauncher(launcher),
    checkProbe(launcher),
    checkExec(launcher),
    await checkPty(module, false, launcher),
    await checkPty(module, true, launcher),
    checkHome(),
    checkAttachments(),
    checkRipgrep(ripgrep),
  ]
}

// ---------------------------------------------------------------------------
// 修复
// ---------------------------------------------------------------------------

/**
 * 修复只做三件事：给定位到的 `landlock-run`、`rg` 补 0755（**本来就是可执行就不动**），
 * 以及附件目录不存在时 `mkdir -p`。不写文件内容、不改其它权限、不动其它路径。
 *
 * 计数语义（与 Kotlin 侧一致）：
 *  - `candidates` = 本次涉及的目标数：定位到的 landlock-run、rg 各计 1（**找到了才计**），
 *    固定目标 attachments 目录也计 1（已存在或本次创建都算）；
 *  - `repaired` = 实际改动的数量：真正执行了 chmod 的次数 + 真正创建了附件目录的次数。
 * 单个动作失败只计数、不抛出：修复是尽力而为，失败原因由下一次 check 如实回答。
 */
function runRepair() {
  let candidates = 0
  let repaired = 0
  for (const key of ['launcher', 'ripgrep']) {
    const located = locate(targetOf(key))
    if (located === null) continue
    candidates += 1
    if (isExecutable(located)) continue
    try {
      fs.chmodSync(located, 0o755)
      repaired += 1
    } catch { /* 补执行位失败：不计改动数，也不影响其余目标。 */ }
  }
  candidates += 1
  if (!isDirectory(attachments)) {
    try {
      fs.mkdirSync(attachments, { recursive: true, mode: 0o755 })
      repaired += 1
    } catch { /* 建目录失败：同上。 */ }
  }
  return { repaired, candidates }
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------

/** 唯一的输出点：**一行** JSON；写完立即退出，绝不输出路径或原始报错文本。 */
function output(payload, code) {
  process.stdout.write(JSON.stringify(payload) + '\n', () => process.exit(code))
  // 兜底：写回调万一不触发也必须退出，不能把宿主侧的等待拖满。
  setTimeout(() => process.exit(code), FORCE_EXIT_MS)
}

function main(operation) {
  if (operation === 'check') return runChecks().then(checks => ({ payload: { ok: true, checks }, code: 0 }))
  if (operation === 'repair') {
    const summary = runRepair()
    return Promise.resolve({ payload: { ok: true, repaired: summary.repaired, candidates: summary.candidates }, code: 0 })
  }
  return Promise.resolve({ payload: { error: 'SELF_CHECK_INPUT_INVALID' }, code: 1 })
}

main(process.argv[2]).then(
  result => output(result.payload, result.code),
  // 受控码只有一个：调用方只需要知道「这次自检没跑成」，细节留在设备侧复现。
  () => output({ error: 'SELF_CHECK_FAILED' }, 1),
)
