// 运行时自检脚本（访客侧 runtime-self-check.cjs）的脚本级测试。
//
// 为什么要有这一层：这个脚本跑在 Android 访客里，设备上只能看到一行受控 JSON —— 出错时既没有
// 堆栈也没有路径可看。所以判定逻辑必须能在本机用**假根目录**驱动：同一个 `pathsOf(root)` 接缝，
// 设备上传 `/`，测试传临时目录，判定代码一行不改。
//
// 重点覆盖编译环境三态（确实没有 / 工具自己判了不可用 / 拿不到判决）：
// 「不可用的编译器被误报成已支持」是本项存在的理由，三态里任何一态被算成 ok 都是缺陷。
// 平台差异：POSIX 上假工具是 `#!/bin/sh` 脚本（小、可靠）；Windows 上执行位没有语义，
// 直接用「纯文本文件冒充工具」来证明「定位得到但起不来必须判 unknown」。
import { spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const repositoryRoot = resolve(import.meta.dirname, '..')
const scriptPath = join(repositoryRoot, 'android/app/src/main/assets/support/runtime-self-check.cjs')
const selfCheck = require(scriptPath)

const isWindows = process.platform === 'win32'

/** 冻结契约：前 11 项来自既有版本（顺序不得变动），后 3 项是追加的编译环境能力项。 */
const CONTRACT_IDS = [
  'shell', 'node', 'sandbox_launcher', 'sandbox_probe', 'sandbox_exec',
  'pty', 'pty_sandbox', 'dsh_home', 'attachments', 'hardlink', 'rg',
  'c_compiler', 'make', 'python3',
]

/** 9 个受控码：每个工具三态各一条。 */
const TOOLCHAIN_CODES = [
  'CC_MISSING', 'CC_UNUSABLE', 'CC_PROBE_UNKNOWN',
  'MAKE_MISSING', 'MAKE_UNUSABLE', 'MAKE_PROBE_UNKNOWN',
  'PYTHON3_MISSING', 'PYTHON3_UNUSABLE', 'PYTHON3_PROBE_UNKNOWN',
]

/** 造一个假访客根，测试结束即删。 */
function createRoot(t) {
  const root = mkdtempSync(join(tmpdir(), 'dsh-self-check-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  return root
}

/** 造出访客家目录：编译产物需要落在被检根之下的可写目录里。 */
function createHome(root) {
  const home = join(root, 'root', '.dsh')
  mkdirSync(home, { recursive: true })
  return home
}

/** 放一个「定位得到」的候选：内容无所谓，只有 POSIX 才真的要求执行位。 */
function placeStub(root, relative) {
  const target = join(root, relative)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, '#!/bin/sh\nexit 0\n')
  chmodSync(target, 0o755)
  return target
}

/** 校验一条检查项的形态：ok 不带码，非 ok 必须带受控码。 */
function assertShape(item) {
  assert.equal(typeof item.id, 'string')
  assert.ok(['ok', 'warn', 'fail', 'skipped'].includes(item.status), `未知状态：${item.status}`)
  if (item.status === 'ok') assert.equal(item.code, undefined, `${item.id} 是 ok 却带了结论码`)
  else assert.equal(typeof item.code, 'string', `${item.id} 非 ok 却没有结论码`)
}

test('CLI 契约：单行 JSON、十四项、顺序与受控码不变', () => {
  const result = spawnSync(process.execPath, [scriptPath, 'check'], { cwd: repositoryRoot, encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
  const lines = result.stdout.trim().split('\n')
  assert.equal(lines.length, 1, '脚本只允许输出一行 JSON')
  const payload = JSON.parse(lines[0])
  assert.equal(payload.ok, true)
  assert.deepEqual(payload.checks.map(item => item.id), CONTRACT_IDS)
  for (const item of payload.checks) assertShape(item)
  // 三个能力项的码只能落在自己的三态里；这里不假设设备上有没有编译器，只查契约。
  for (const item of payload.checks.slice(11)) {
    if (item.status !== 'ok') assert.ok(TOOLCHAIN_CODES.includes(item.code), `${item.id} 的码不在受控集合：${item.code}`)
  }
  // 未知操作只回受控码，不回自由文本。
  const invalid = spawnSync(process.execPath, [scriptPath, 'install'], { cwd: repositoryRoot, encoding: 'utf8' })
  assert.equal(invalid.status, 1)
  assert.deepEqual(JSON.parse(invalid.stdout.trim()), { error: 'SELF_CHECK_INPUT_INVALID' })
  // repair 载荷是计数，不是清单：不泄漏被修复的路径。
  const repaired = spawnSync(process.execPath, [scriptPath, 'repair'], { cwd: repositoryRoot, encoding: 'utf8' })
  assert.equal(repaired.status, 0)
  const summary = JSON.parse(repaired.stdout.trim())
  assert.equal(summary.ok, true)
  assert.equal(typeof summary.repaired, 'number')
  assert.equal(typeof summary.candidates, 'number')
})

test('假根目录：没有任何工具时三项都判 missing，且不是 ok', async (t) => {
  const root = createRoot(t)
  const paths = selfCheck.pathsOf(root)
  assert.equal(selfCheck.locateExecutable(paths, ['cc', 'gcc', 'clang'], { PATH: '/nonexistent-bin' }), null)
  assert.deepEqual(selfCheck.runToolchainChecks(paths, { PATH: '/nonexistent-bin' }), [
    { id: 'c_compiler', status: 'warn', code: 'CC_MISSING' },
    { id: 'make', status: 'warn', code: 'MAKE_MISSING' },
    { id: 'python3', status: 'warn', code: 'PYTHON3_MISSING' },
  ])
  // 整次自检的项序也必须跟着这份契约走（前三项之外的部分只要形状合法）。
  const checks = await selfCheck.runChecks(root, { PATH: '/nonexistent-bin' })
  assert.deepEqual(checks.map(item => item.id), CONTRACT_IDS)
  for (const item of checks) assertShape(item)
})

test('三态判定：找不到 / 非 0 退出码 / 没拿到退出码', () => {
  const judge = selfCheck.judgeTool
  assert.deepEqual(judge('c_compiler', 'CC', null, 0), { id: 'c_compiler', status: 'warn', code: 'CC_MISSING' })
  assert.deepEqual(judge('c_compiler', 'CC', '/usr/bin/cc', 0), { id: 'c_compiler', status: 'ok' })
  assert.deepEqual(judge('c_compiler', 'CC', '/usr/bin/cc', 1), { id: 'c_compiler', status: 'warn', code: 'CC_UNUSABLE' })
  // 关键：没有退出码 = 探测本身没跑成，按不可用上报，绝不能当成可用。
  assert.deepEqual(judge('c_compiler', 'CC', '/usr/bin/cc', undefined), { id: 'c_compiler', status: 'warn', code: 'CC_PROBE_UNKNOWN' })
  assert.deepEqual(judge('make', 'MAKE', '/usr/bin/make', 0), { id: 'make', status: 'ok' })
  assert.deepEqual(judge('python3', 'PYTHON3', '/usr/bin/python3', 2), { id: 'python3', status: 'warn', code: 'PYTHON3_UNUSABLE' })
  // 「找得到却起不来」也只能落在 unknown：这一态的正事就是不猜。
  const missingExecutable = join(tmpdir(), 'dsh-self-check-does-not-exist')
  assert.equal(selfCheck.toolStatus(selfCheck.spawnProbe(missingExecutable, ['--version'], 2000, null)), undefined)
  assert.deepEqual(judge('make', 'MAKE', missingExecutable, undefined), { id: 'make', status: 'warn', code: 'MAKE_PROBE_UNKNOWN' })
})

test('启动器失败的细分：EACCES 与「其它启动器级失败」不能混成一个码', () => {
  const denied = selfCheck.launcherFailureCode({
    status: 125,
    stderr: 'landlock-run: exec failed: Permission denied\n',
    stdout: '',
  })
  assert.equal(denied, 'EXEC_LAUNCHER_DENIED')
  // 真机上 loader 落在授权根之外时，报错文本就是这个形状；stdout 里出现同样要认出来。
  assert.equal(
    selfCheck.launcherFailureCode({ status: 125, stderr: '', stdout: 'exec failed: Permission denied' }),
    'EXEC_LAUNCHER_DENIED',
  )
  // 其余启动器级失败（规则集写错、被信号杀死、超时没有退出码）保持原码，不冒充权限结论。
  assert.equal(selfCheck.launcherFailureCode({ status: 125, stderr: 'landlock: invalid ruleset', stdout: '' }), 'EXEC_LAUNCHER_FAILED')
  assert.equal(selfCheck.launcherFailureCode({ status: null, stderr: null, stdout: null }), 'EXEC_LAUNCHER_FAILED')
})

test('pathDirectories：只收绝对路径、去重、限量', () => {
  const entries = Array.from({ length: 40 }, (unused, index) => `/path-${index}`)
  const directories = selfCheck.pathDirectories({
    PATH: ['/usr/bin', 'relative/bin', '', '/usr/bin', '/bin', ...entries].join(':'),
  })
  assert.deepEqual(directories.slice(0, 2), ['/usr/bin', '/bin'])
  assert.ok(directories.length <= 24, `PATH 解析没有上限：${directories.length}`)
  assert.deepEqual(selfCheck.pathDirectories({}), [])
  assert.deepEqual(selfCheck.pathDirectories(undefined), [])
  assert.deepEqual(selfCheck.pathDirectories({ PATH: 'bin:/usr/bin:' }), ['/usr/bin'])
})

test('编译器：定位到了但没有可写的产物目录时，如实报 unknown 而不是 ok', (t) => {
  const root = createRoot(t)
  placeStub(root, 'usr/bin/cc')
  // 既没有 <root>/root/.dsh，os.tmpdir() 也不在被检根之下 —— 没有任何可写候选。
  assert.deepEqual(selfCheck.checkCompiler(selfCheck.pathsOf(root), { PATH: '' }), {
    id: 'c_compiler', status: 'warn', code: 'CC_PROBE_UNKNOWN',
  })
})

test('可执行位缺失的文件不算工具（目录的搜索位也不能当成可执行）', { skip: isWindows }, (t) => {
  const root = createRoot(t)
  const target = join(root, 'usr/bin/make')
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, '#!/bin/sh\nexit 0\n')
  chmodSync(target, 0o644)
  // 目录本身带搜索位：只看 X_OK 会把目录当成可执行文件，所以判定必须带 isFile。
  mkdirSync(join(root, 'usr/bin/python3'), { recursive: true })
  assert.equal(selfCheck.locateExecutable(selfCheck.pathsOf(root), ['make', 'python3'], { PATH: '' }), null)
})

test('POSIX：可用判 ok、非 0 退出码判 unusable、真编译一次最小程序', { skip: isWindows }, (t) => {
  const root = createRoot(t)
  const paths = selfCheck.pathsOf(root)
  const home = createHome(root)
  // 假 cc 记录它收到的 argv 与 stdin，再按 -o 造出产物：这样能证明我们真的喂了源码、真的链接了一次。
  const fake = join(root, 'usr/bin/cc')
  mkdirSync(dirname(fake), { recursive: true })
  writeFileSync(fake, [
    '#!/bin/sh',
    'directory=$(dirname "$0")',
    'printf "%s\\n" "$@" > "$directory/argv.txt"',
    'cat > "$directory/stdin.txt"',
    'out=""',
    'while [ $# -gt 0 ]; do',
    '  if [ "$1" = "-o" ]; then shift; out="$1"; fi',
    '  shift',
    'done',
    '[ -n "$out" ] && printf "fake\\n" > "$out"',
    'exit 0',
    '',
  ].join('\n'))
  chmodSync(fake, 0o755)
  assert.deepEqual(selfCheck.checkCompiler(paths, { PATH: '' }), { id: 'c_compiler', status: 'ok' })
  const argv = readFileSync(join(root, 'usr/bin/argv.txt'), 'utf8').trim().split('\n')
  assert.deepEqual(argv.slice(0, 4), ['-x', 'c', '-', '-o'])
  assert.ok(argv[4].startsWith(home), `产物没有落在可写目录里：${argv[4]}`)
  assert.ok(!existsSync(argv[4]), '编译产物必须当场删掉')
  assert.deepEqual(readdirSync(home), [])
  const source = readFileSync(join(root, 'usr/bin/stdin.txt'), 'utf8')
  assert.ok(source.includes('#include <stdio.h>'), '探针源码必须带标准头文件，只查版本串会漏掉半装工具链')
  assert.ok(source.includes('printf'), '探针要真的调用一次 libc，否则看不出缺 binutils / 缺库')
  // make / python3 只问一次版本：脚本回 0 就是 ok，回非 0 就是 unusable。
  const make = join(root, 'usr/bin/make')
  writeFileSync(make, '#!/bin/sh\nexit 1\n')
  chmodSync(make, 0o755)
  assert.deepEqual(selfCheck.checkVersionTool({ id: 'make', prefix: 'MAKE', names: ['make'] }, paths, { PATH: '' }), {
    id: 'make', status: 'warn', code: 'MAKE_UNUSABLE',
  })
  writeFileSync(make, '#!/bin/sh\necho make-4.3\n')
  chmodSync(make, 0o755)
  assert.deepEqual(selfCheck.checkVersionTool({ id: 'make', prefix: 'MAKE', names: ['make'] }, paths, { PATH: '' }), {
    id: 'make', status: 'ok',
  })
})

test('Windows：定位得到但起不来的工具判 unknown，真跑一次可执行文件拿到退出码', { skip: !isWindows }, (t) => {
  const root = createRoot(t)
  const paths = selfCheck.pathsOf(root)
  const home = createHome(root)
  // Windows 上执行位没有语义：纯文本文件也算「定位得到」，但 CreateProcess 起不来 → 没拿到判决。
  placeStub(root, 'usr/bin/cc')
  assert.deepEqual(selfCheck.checkCompiler(paths, { PATH: '' }), { id: 'c_compiler', status: 'warn', code: 'CC_PROBE_UNKNOWN' })
  assert.deepEqual(readdirSync(home), [])
  placeStub(root, 'usr/bin/make')
  assert.deepEqual(selfCheck.checkVersionTool({ id: 'make', prefix: 'MAKE', names: ['make'] }, paths, { PATH: '' }), {
    id: 'make', status: 'warn', code: 'MAKE_PROBE_UNKNOWN',
  })
  // 「有工具且退出 0 → ok」这条链在本机只能分段证明：spawn 真进程拿退出码这一段是真的，
  // judge 把 0 判成 ok 那一段在纯函数测试里。Windows 上没法把替身放到 `cc`/`make` 这些名字下 ——
  // CreateProcess 要求真实 PE 镜像，改不了名字，所以 ok / unusable 的端到端路径交给 POSIX 测试（CI 上跑）。
  assert.equal(selfCheck.toolStatus(selfCheck.spawnProbe(process.execPath, ['--version'], 2000, null)), 0)
})
