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
 * 前 11 项（`shell` … `hardlink` → `rg`，索引 0-10）是冻结契约，顺序与 id 一个字都不能动；
 * 后 3 项（`c_compiler` / `make` / `python3`）是追加的**能力项**：如实探测本环境有没有可用的
 * C 编译环境（含 node-gyp 需要的 make 与 python3）。能力项一律 `warn`、三态必须分得清，
 * 拿不到判决时按「不可用」上报 —— 绝不让「不可用的编译器」看起来像「已支持」。
 *
 * 输出契约（**整个脚本只在最后输出一行**，绝不输出路径或原始报错文本）：
 *   check  → {"ok":true,"checks":[{"id":"shell","status":"ok"}, ...]}
 *   repair → {"ok":true,"repaired":<int>,"candidates":<int>}
 *   失败   → {"error":"<受控码>"}，退出码 1
 *
 * 所有路径只在本脚本内部使用：对外字段只有固定枚举（id / status / code）与计数。
 * 目标缺失、目录不可读、遍历超限都只影响判定结果，绝不抛异常打断整次自检 ——
 * 自检本身失败会让排障失去唯一的第一手证据，比「某项判不出来」代价大得多。
 *
 * 访客根是**参数**（`pathsOf(root)`，设备上永远是 `/`）：脚本测试用假根目录 `require()` 本模块
 * 驱动同一套判定，CLI 入口因此有 `require.main === module` 守卫。
 */

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')
const { createRequire } = require('node:module')

/** 访客根：脚本始终在访客内运行，因此根就是 `/`（与 plugin-manager.cjs 的 CLI 调用一致）。 */
const DEFAULT_ROOT = '/'

/**
 * 本次检查用到的全部访客路径。
 *
 * 根是**参数**而不是模块级常量：设备上永远是 `/`（`path.resolve('/')` 仍是 `/`），
 * 而脚本测试用假根目录驱动同一套判定，范式与 `plugin-manager.cjs` 的 `createManager(root)` 一致。
 */
function pathsOf(rootArgument) {
  const root = path.resolve(rootArgument)
  const home = path.join(root, 'root/.dsh')
  return {
    root,
    home,
    attachments: path.join(home, 'attachments'),
    nodeBinary: path.join(root, 'opt/node/bin/node'),
    shellBinary: path.join(root, 'bin/bash'),
    /** dsh 的安装锚点：node-pty 按它解析，等价于运行时自己加载模块的方式。 */
    dshAnchor: path.join(root, 'opt/dsh/node_modules/@deepseek-ai/dsh/package.json'),
    /**
     * 移动端沙箱运行器：由 App 随每次启动写进这个位置（Kotlin 侧 RuntimeSandboxRunner）。
     * 沙箱内执行与沙箱内 PTY 都应当经它——它把档参数翻译成 landlock-run 的授权参数，
     * 并补上 PRoot loader 真实路径的读 + 执行授权；缺这条授权，内核会拒绝 execve loader。
     */
    sandboxRunner: path.join(root, 'root/.dsh-mobile/sandbox-runner.sh'),
  }
}

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
function fixedCandidates(target, paths) {
  const candidates = []
  for (const modules of MODULES_ROOTS) {
    for (const hint of target.hints) {
      for (const entry of target.entries) candidates.push(path.join(paths.root, modules, hint, entry))
      // 目录型目标（node-pty）：包目录本身就是入口。
      if (target.entries.length === 0) candidates.push(path.join(paths.root, modules, hint))
    }
  }
  for (const modules of MODULES_ROOTS) {
    for (const name of target.names) candidates.push(path.join(paths.root, modules, '.bin', name))
  }
  for (const bin of BIN_ROOTS) {
    for (const name of target.names) candidates.push(path.join(paths.root, bin, name))
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
function storeCandidates(target, modules, paths) {
  const store = path.join(paths.root, modules, '.pnpm')
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
function locate(target, paths) {
  for (const candidate of fixedCandidates(target, paths)) {
    if (matches(target, candidate)) return candidate
  }
  for (const modules of MODULES_ROOTS) {
    for (const candidate of storeCandidates(target, modules, paths)) {
      if (matches(target, candidate)) return candidate
    }
  }
  for (const modules of MODULES_ROOTS) {
    const hit = walk(target, path.join(paths.root, modules), 0, { directories: 0, entries: 0 })
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
// → pty → pty_sandbox → dsh_home → attachments → hardlink → rg。
// 这 11 项之后**追加**的 c_compiler / make / python3 是能力项（见下一节），既有索引一个字不动。
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

function checkShell(paths) {
  if (!isFile(paths.shellBinary) || !isExecutable(paths.shellBinary)) return entry('shell', 'fail', 'SHELL_MISSING')
  return entry('shell', 'ok')
}

function checkNode(paths) {
  if (!isFile(paths.nodeBinary) || !isExecutable(paths.nodeBinary)) return entry('node', 'fail', 'NODE_MISSING')
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
 * 启动器级失败的细分：退出码 125 只说明「启动器自己失败了」，**为什么**失败要到它的输出里看。
 *
 * 真机上出现过的一条是 `landlock-run: exec failed: Permission denied` —— 受限执行时内核拒绝对
 * `PROOT_LOADER` 的 execve（0.2.3 及更早版本的 loader 落在授权根之外，PRoot 会把 tracee 的 exec
 * 改写成对 loader 的 exec）。只报 `EXEC_LAUNCHER_FAILED` 时，「被权限拒绝」与「启动器起不来」
 * 分不开，这正是当初只能靠人工上机复现的原因。
 *
 * 仍然只回受控码、不回原文：本脚本对外只允许固定枚举与计数，判据留在脚本内部。
 */
function launcherFailureCode(result) {
  const stderr = typeof result.stderr === 'string' ? result.stderr : ''
  const stdout = typeof result.stdout === 'string' ? result.stdout : ''
  const output = stderr + stdout
  // 运行器自己的失败（档参数不认识、授权路径打不开）优先归因：它的报错里可能同时含
  // 「Permission denied」，只按关键词判定会把它误判成「内核拒绝了 exec」。
  if (output.includes(SANDBOX_RUNNER_SIGNATURE)) return 'EXEC_LAUNCHER_FAILED'
  return output.includes('Permission denied') ? 'EXEC_LAUNCHER_DENIED' : 'EXEC_LAUNCHER_FAILED'
}

/** 运行器自身的失败签名：与 Kotlin 侧 RuntimeSandboxRunner.FAILURE_SIGNATURE 逐字一致。 */
const SANDBOX_RUNNER_SIGNATURE = 'dsh-sandbox-runner: '

/**
 * 沙箱档参数：**bwrap 参数名**（由运行器翻译成 landlock-run 的授权参数），
 * 形状与上游 workspace-write 档一致：只读整根、共享 `/dev` 与 `/proc`、有临时目录、工作区可写。
 * 省掉 PID/挂载隔离是有意的：上游的 landlock 档本来就没有它们，Android 应用也没有那个权限。
 */
function sandboxProfileArgs(paths) {
  return [
    '--ro-bind', '/', '/',
    '--dev', '/dev',
    '--proc', '/proc',
    '--tmpfs', '/tmp',
    '--bind', paths.home, paths.home,
  ]
}

/**
 * 沙箱内命令的完整 argv。
 *
 * 优先走**运行器**：那才是 Harness 实际使用的形态（`sandbox-local` 的 `runnerCommand`），
 * 只测启动器会漏掉「档参数翻译」这一层。运行器不存在（旧运行时）时退回直接调用启动器，
 * 至少仍能报出启动器级失败，不会假装这一项通过。
 */
function sandboxArgv(paths, launcher, command) {
  if (isFile(paths.sandboxRunner)) {
    return [paths.shellBinary, paths.sandboxRunner, ...sandboxProfileArgs(paths), '--', ...command]
  }
  return [launcher, '--ro', paths.root, '--rw', paths.home, '--', ...command]
}

/**
 * 真实 confine exec：跑 `/bin/true`，argv 由 [sandboxArgv] 给出（优先经运行器）。
 * 启动器级失败一律退出码 125；**没有退出码**（进程没起来、被信号杀死或超时）同属启动器级失败，
 * 其余非零码表示启动器起来了但命令失败 —— 两者混在一起就无法区分「沙箱层」与「命令层」。
 *
 * 与探测同理：启动器不可用时这一项**没有可测的前提**，只报 `skipped`，不报任何「执行失败」。
 */
function checkExec(launcher, paths) {
  if (launcher === null || !isExecutable(launcher)) return entry('sandbox_exec', 'skipped', 'LAUNCHER_MISSING')
  const argv = sandboxArgv(paths, launcher, ['/bin/true'])
  const result = spawnSync(argv[0], argv.slice(1), {
    encoding: 'utf8',
    timeout: EXEC_TIMEOUT_MS,
    maxBuffer: SPAWN_MAX_BUFFER,
  })
  if (result.status === 0) return entry('sandbox_exec', 'ok')
  if (result.status === null || result.status === 125) {
    return entry('sandbox_exec', 'fail', launcherFailureCode(result))
  }
  return entry('sandbox_exec', 'fail', 'EXEC_COMMAND_FAILED')
}

/**
 * 加载 node-pty。
 *
 * 先按运行时安装锚点解析（与 plugin-manager.cjs 的 `createRequire` 一致，能穿过 pnpm 隔离布局），
 * 再退回定位到的包目录。`spawn` 不是函数按“加载失败”处理：拿到了壳但没有可用的 PTY 能力。
 */
function loadPty(packageDirectory, paths) {
  const attempts = []
  if (isFile(paths.dshAnchor)) attempts.push(() => createRequire(paths.dshAnchor)('node-pty'))
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
 * `sandboxed=true` 是把同一条命令经**沙箱运行器**放进 `landlock-run` 的授权根里再跑一遍。
 *
 * 启动器不可用（缺失或没有执行位）时沙箱内那组直接跳过：拿一个不可用的启动器去做冒烟，
 * 只会得到一条误导性的「PTY 秒退」—— 真正的判据在 sandbox_launcher 那一行。
 */
async function checkPty(module, sandboxed, launcher, paths) {
  const id = sandboxed ? 'pty_sandbox' : 'pty'
  if (!module.ok) {
    return module.missing ? entry(id, 'skipped', 'PTY_MODULE_MISSING') : entry(id, 'fail', 'PTY_LOAD_FAILED')
  }
  if (sandboxed && (launcher === null || !isExecutable(launcher))) return entry(id, 'skipped', 'LAUNCHER_MISSING')
  const command = sandboxed
    ? sandboxArgv(paths, launcher, [paths.shellBinary, '-lc', 'echo ' + MARKER])
    : [paths.shellBinary, '-lc', 'echo ' + MARKER]
  const outcome = await ptySmoke(module.pty, command)
  return entry(id, outcome.status, outcome.code)
}

function checkHome(paths) {
  if (!isDirectory(paths.home)) return entry('dsh_home', 'fail', 'HOME_MISSING')
  // 家目录存在但不可写，正是 `Unable to persist attachment.` 一类的成因，必须与“不存在”分开报。
  if (!writable(paths.home)) return entry('dsh_home', 'fail', 'HOME_NOT_WRITABLE')
  return entry('dsh_home', 'ok')
}

/**
 * 附件目录：不存在是 warn（repair 会建），存在却写不进去是 fail —— 后者不是“还没建”，
 * 而是建了也没用，两者的处置完全不同。
 */
function checkAttachments(paths) {
  const stats = statOrNull(paths.attachments)
  if (stats === null) return entry('attachments', 'warn', 'ATTACHMENTS_MISSING')
  if (!stats.isDirectory()) return entry('attachments', 'fail', 'ATTACHMENTS_NOT_WRITABLE')
  if (!writable(paths.attachments)) return entry('attachments', 'fail', 'ATTACHMENTS_NOT_WRITABLE')
  return entry('attachments', 'ok')
}

/**
 * 硬链接探测：在访客数据目录里独占创建一个小文件，再为它建一个硬链接，最后两个都删掉。
 *
 * 为什么必须单独测：dsh 的 `write` 工具**新建**文件走 `dsh-fs-local.writeFileAtomic()` 的
 * `linkFile()`，附件发布走 `dsh-attachment-local.publishStagedObject()` 的 `link()` ——
 * 两者都靠硬链接做原子安装。而「目录可写」（上两项测的）与「能建硬链接」是两件事：
 * 真机上 `open + write + unlink` 全部正常，`link(2)` 却一律被拒，
 * 于是 `attachments=ok` 会给出「附件链路正常」的假信号，这一项就是来补这个盲区的。
 *
 * 失败只回受控码，**不带 errno 文本**：EACCES / EPERM（本机 PRoot 策略拒绝）与 EXDEV（跨设备）
 * 对上层是同一个结论「硬链接在这个环境里不可用」，其中 EXDEV 的含义与权限无关，只是同样落在这里。
 *
 * 目录不存在时同样是 fail（不是抛出）：精确原因由 `dsh_home` / `attachments` 那两行给出，
 * 与 `writable()` 的处理保持一致 —— 探测本身绝不打断整次自检。
 */
function checkHardlink(paths) {
  const source = path.join(paths.home, '.dsh-self-check-' + crypto.randomUUID() + '.tmp')
  const linkName = source + '.link'
  let descriptor = null
  try {
    descriptor = fs.openSync(source, 'wx', 0o600)
    fs.writeFileSync(descriptor, 'ok')
    fs.linkSync(source, linkName)
    return entry('hardlink', 'ok')
  } catch {
    return entry('hardlink', 'fail', 'HARDLINK_DENIED')
  } finally {
    // 两个文件都要清理：链接建不建得成都可能留下源文件。
    if (descriptor !== null) {
      try { fs.closeSync(descriptor) } catch { /* 已经关掉了。 */ }
    }
    try { fs.unlinkSync(linkName) } catch { /* 没建成就没什么可删。 */ }
    try { fs.unlinkSync(source) } catch { /* 同上。 */ }
  }
}

function checkRipgrep(ripgrep) {
  if (ripgrep === null) return entry('rg', 'warn', 'RG_MISSING')
  if (!isExecutable(ripgrep)) return entry('rg', 'warn', 'RG_NOT_EXECUTABLE')
  return entry('rg', 'ok')
}

// ---------------------------------------------------------------------------
// C 编译环境（能力项，不是缺口项）
//
// 这三项回答的是「这台设备能不能现场构建含原生模块的插件」，而不是「运行时哪一环断了」：
//  - 状态一律 `warn`：能力缺失不该把整次自检翻成失败（宿主侧只统计 `fail`，能力项不参加）；
//  - 三态必须分得清：`_MISSING`（确实没有）、`ok`（工具自己用退出码 0 回答了）、
//    `_UNUSABLE`（工具起来了但给出非 0 退出码）、`_PROBE_UNKNOWN`（**没拿到退出码**：
//    进程没起来、超时被杀、被信号杀死、连可写的产物目录都没有）。
//    后两者都不是「可用」—— 拿不到判决时按「不可用」上报，绝不冒充 `ok`。
// ---------------------------------------------------------------------------

/** 版本探测与最小编译的超时：对象都是本机进程，超时只用来防止探测本身把整次自检拖满。 */
const TOOL_VERSION_TIMEOUT_MS = 2000
const CC_COMPILE_TIMEOUT_MS = 4000
/** PATH 兜底解析上限：受控注入的 PATH 只有几项，多出来的一律不看（探测必须廉价且有界）。 */
const MAX_PATH_ENTRIES = 24

/**
 * 最小编译用的源码：**带一个标准头文件、并真的调用一次 libc**。
 *
 * 只看 `--version` 会在 Debian 常见的半装状态上撒谎 —— `gcc` 装上了、`binutils` 或 C 库头文件
 * 没装上时版本串照样打得出来，而真正构建原生模块必然失败。「不可用的编译器被误报成已支持」
 * 正是本项要避免的事，所以这里付一次几十毫秒的编译 + 链接代价，换一个说得出口的结论。
 */
const COMPILER_PROBE_SOURCE = [
  '#include <stdio.h>',
  'int main(void) { printf("dsh-self-check\\n"); return 0; }',
  '',
].join('\n')

/**
 * 工具表：`prefix` 唯一决定受控码（`<prefix>_MISSING` / `_UNUSABLE` / `_PROBE_UNKNOWN`）。
 * `names` 的顺序即优先级：`cc` 是系统默认编译器名，缺了才退 `gcc`、`clang`。
 */
const TOOLCHAIN_TOOLS = [
  { id: 'c_compiler', prefix: 'CC', names: ['cc', 'gcc', 'clang'] },
  { id: 'make', prefix: 'MAKE', names: ['make'] },
  { id: 'python3', prefix: 'PYTHON3', names: ['python3'] },
]

/** PATH 里的目录：只接受绝对路径（`/` 开头）、去重、限量，语义跟着访客实际的执行环境走。 */
function pathDirectories(environment) {
  const source = environment !== null && environment !== undefined && typeof environment.PATH === 'string'
    ? environment.PATH
    : ''
  const seen = new Set()
  const directories = []
  for (const raw of source.split(':')) {
    const directory = raw.trim()
    if (directory.length === 0 || directory[0] !== '/') continue
    if (seen.has(directory)) continue
    seen.add(directory)
    directories.push(directory)
    if (directories.length >= MAX_PATH_ENTRIES) break
  }
  return directories
}

/**
 * 定位一个工具：先在运行时的固定 bin 根里按**名字优先级**找（`cc` 优先于 `gcc`/`clang`），
 * 再按 PATH 兜底 —— node-gyp 找 `make`/`python3` 走的正是 PATH，两者不能分家。
 *
 * 判定必须带 `isFile`：目录本身也带搜索位，只看 `X_OK` 会把目录当成可执行文件。
 * 命中即判、不再回退下一个名字：`cc` 存在却跑不通时改试 `gcc` 只会掩盖真正的损坏，
 * 而「`cc` 是系统默认编译器名」本身就是本项的固定判据。
 */
function locateExecutable(paths, names, environment) {
  for (const name of names) {
    for (const bin of BIN_ROOTS) {
      const candidate = path.join(paths.root, bin, name)
      if (isFile(candidate) && isExecutable(candidate)) return candidate
    }
  }
  for (const directory of pathDirectories(environment)) {
    for (const name of names) {
      const candidate = path.join(directory, name)
      if (isFile(candidate) && isExecutable(candidate)) return candidate
    }
  }
  return null
}

/** spawn 判定：输出一律丢弃（探针只需要退出码），spawn 自己抛异常时返回 null = 没拿到判决。 */
function spawnProbe(executable, args, timeout, options) {
  try {
    return spawnSync(executable, args, { encoding: 'utf8', timeout, maxBuffer: SPAWN_MAX_BUFFER, ...options })
  } catch {
    return null
  }
}

/** 退出码是工具自己的判决：数字才是判决（0 = 可用），null / undefined 一律算「没拿到判决」。 */
function toolStatus(result) {
  return result === null || result === undefined || typeof result.status !== 'number' ? undefined : result.status
}

/**
 * 三态判定（`located === null` 即确实没有这个名字）：
 *  - 找不到 → `<PREFIX>_MISSING`
 *  - 退出码 0 → `ok`（不带 code）
 *  - 其它数字退出码 → `<PREFIX>_UNUSABLE`
 *  - 没有退出码 → `<PREFIX>_PROBE_UNKNOWN`（按不可用上报，绝不当作可用）
 */
function judgeTool(id, prefix, located, status) {
  if (located === null) return entry(id, 'warn', prefix + '_MISSING')
  if (status === 0) return entry(id, 'ok')
  if (typeof status === 'number') return entry(id, 'warn', prefix + '_UNUSABLE')
  return entry(id, 'warn', prefix + '_PROBE_UNKNOWN')
}

/**
 * 编译产物目录：`os.tmpdir()` 与访客家目录，**只接受落在被检根之下**且确实可写的目录。
 *
 * 为什么不去注入 `TMPDIR`/`TMP`/`TEMP`：临时目录能不能用本身就是「这个环境能不能编译」的
 * 一部分，替编译器挑一个我们自己找到的可写目录，只会让结论比真实构建更乐观。一个可写的
 * 候选都没有时如实报「没拿到判决」，而不是换个地方硬编。
 */
function compilerOutputDirectory(paths) {
  for (const candidate of [os.tmpdir(), paths.home]) {
    if (!candidate.startsWith(paths.root)) continue
    if (!isDirectory(candidate)) continue
    if (!writable(candidate)) continue
    return candidate
  }
  return null
}

/** 编译器的完整命令行：`-x c` 是因为源码从 stdin 进来、没有扩展名可判语言。 */
function compilerArguments(artifact) {
  return ['-x', 'c', '-', '-o', artifact]
}

/** C 编译器：真做一次最小「编译 + 链接」，产物无论成败都立刻删掉。 */
function checkCompiler(paths, environment) {
  const located = locateExecutable(paths, TOOLCHAIN_TOOLS[0].names, environment)
  if (located === null) return entry('c_compiler', 'warn', 'CC_MISSING')
  const directory = compilerOutputDirectory(paths)
  if (directory === null) return entry('c_compiler', 'warn', 'CC_PROBE_UNKNOWN')
  const artifact = path.join(directory, '.dsh-self-check-' + crypto.randomUUID() + '.out')
  try {
    const result = spawnProbe(located, compilerArguments(artifact), CC_COMPILE_TIMEOUT_MS, {
      input: COMPILER_PROBE_SOURCE,
    })
    return judgeTool('c_compiler', 'CC', located, toolStatus(result))
  } finally {
    try { fs.unlinkSync(artifact) } catch { /* 没编出来就没什么可删。 */ }
  }
}

/** make / python3：只问一次版本，退出码即判决。 */
function checkVersionTool(tool, paths, environment) {
  const located = locateExecutable(paths, tool.names, environment)
  const result = located === null ? null : spawnProbe(located, ['--version'], TOOL_VERSION_TIMEOUT_MS, null)
  return judgeTool(tool.id, tool.prefix, located, toolStatus(result))
}

/** 三项能力项的判定，顺序与 `TOOLCHAIN_TOOLS` 一致。 */
function runToolchainChecks(paths, environment) {
  return [
    checkCompiler(paths, environment),
    checkVersionTool(TOOLCHAIN_TOOLS[1], paths, environment),
    checkVersionTool(TOOLCHAIN_TOOLS[2], paths, environment),
  ]
}

/**
 * 十四项检查，顺序即契约顺序：前 11 项是冻结契约（`shell` → … → `hardlink` → `rg`，索引不变），
 * 后 3 项是追加的编译环境能力项（`c_compiler` → `make` → `python3`）。
 *
 * `environment` 缺省即 `process.env`：设备上访客脚本拿到的就是受控注入的那份环境，
 * 按 PATH 探测与 node-gyp 的实际解析因此是同一套。
 */
async function runChecks(rootArgument, environment) {
  const paths = pathsOf(rootArgument)
  const environmentSource = environment === undefined ? process.env : environment
  const launcher = locate(targetOf('launcher'), paths)
  const ripgrep = locate(targetOf('ripgrep'), paths)
  const ptyDirectory = locate(targetOf('pty'), paths)
  // node-pty 只加载一次：两组 PTY 冒烟用的是同一个模块实例。
  const module = loadPty(ptyDirectory, paths)
  return [
    checkShell(paths),
    checkNode(paths),
    checkLauncher(launcher),
    checkProbe(launcher),
    checkExec(launcher, paths),
    await checkPty(module, false, launcher, paths),
    await checkPty(module, true, launcher, paths),
    checkHome(paths),
    checkAttachments(paths),
    checkHardlink(paths),
    checkRipgrep(ripgrep),
    ...runToolchainChecks(paths, environmentSource),
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
function runRepair(rootArgument) {
  const paths = pathsOf(rootArgument)
  let candidates = 0
  let repaired = 0
  for (const key of ['launcher', 'ripgrep']) {
    const located = locate(targetOf(key), paths)
    if (located === null) continue
    candidates += 1
    if (isExecutable(located)) continue
    try {
      fs.chmodSync(located, 0o755)
      repaired += 1
    } catch { /* 补执行位失败：不计改动数，也不影响其余目标。 */ }
  }
  candidates += 1
  if (!isDirectory(paths.attachments)) {
    try {
      fs.mkdirSync(paths.attachments, { recursive: true, mode: 0o755 })
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
  if (operation === 'check') {
    return runChecks(DEFAULT_ROOT, process.env).then(checks => ({ payload: { ok: true, checks }, code: 0 }))
  }
  if (operation === 'repair') {
    const summary = runRepair(DEFAULT_ROOT)
    return Promise.resolve({ payload: { ok: true, repaired: summary.repaired, candidates: summary.candidates }, code: 0 })
  }
  return Promise.resolve({ payload: { error: 'SELF_CHECK_INPUT_INVALID' }, code: 1 })
}

// 只有被当作入口脚本执行时才走 CLI：脚本测试 `require()` 进来时绝不碰 stdout。
if (require.main === module) {
  main(process.argv[2]).then(
    result => output(result.payload, result.code),
    // 受控码只有一个：调用方只需要知道「这次自检没跑成」，细节留在设备侧复现。
    () => output({ error: 'SELF_CHECK_FAILED' }, 1),
  )
}

// 导出只为脚本测试服务（假根目录驱动同一套判定）：设备侧仍只通过 CLI 调用本脚本。
module.exports = {
  DEFAULT_ROOT,
  TOOLCHAIN_TOOLS,
  pathsOf,
  pathDirectories,
  locateExecutable,
  spawnProbe,
  toolStatus,
  compilerArguments,
  judgeTool,
  launcherFailureCode,
  checkCompiler,
  checkVersionTool,
  runToolchainChecks,
  runChecks,
  runRepair,
}
