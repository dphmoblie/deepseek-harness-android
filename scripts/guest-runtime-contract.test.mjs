// 访客侧运行时的 API 下限契约。
//
// 为什么要有这一层：`scripts/runtime-profile/**` 与 `android/app/src/main/assets/support/**`
// 跑在**访客 Node** 上，而根 `package.json` 的 `engines` 只约束宿主（构建与 CI）。真实事故：
// 设备桥接插件用了 `AbortSignal.any`（Node 20.3 才有），用户换过更老的 Node 之后，每个设备
// 命令都在 fetch 之前抛 `TypeError: AbortSignal.any is not a function`；而全部测试都跑在宿主
// Node 24 上，没有一条覆盖到这条路径。
//
// 规则：`since` 超过声明下限（[FLOOR]）的 API，必须**在同一个文件里**带特性探测（[RULES.guard]），
// 否则这条测试失败。注释里的提及不算使用（见 `stripComments`）。
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { test } from 'node:test'

const repositoryRoot = resolve(import.meta.dirname, '..')

/** 访客侧代码的两个根：运行时 profile（插件）与 APK 内置的 support 脚本。 */
const GUEST_ROOTS = ['scripts/runtime-profile', 'android/app/src/main/assets/support']

/** 插件声明的最低访客 Node 版本。改这里必须同时改插件 package.json 的 engines。 */
const FLOOR = '18.0.0'

/**
 * `since` 高于 [FLOOR] 的 API 清单。
 *
 * 只登记**真正会用错**的那些：Node 18 已经有的 API（`AbortSignal.timeout`、`structuredClone`、
 * `Array.prototype.at`、`crypto.randomUUID`、全局 `fetch`）不在此列，写上来只会制造噪音。
 */
const RULES = [
  {
    name: 'AbortSignal.any',
    pattern: /AbortSignal\.any\b/u,
    since: '20.3.0',
    // 只允许出现在防御分支那一行里：`if (typeof AbortSignal.any === 'function') return AbortSignal.any(...)`。
    // 这样「已有回退后又加了一处裸调用」也会被拦下，而不是因为文件里存在 guard 就整体放行。
    lineGuard: /typeof AbortSignal\.any === 'function'/u,
  },
  { name: 'Array.prototype.toSorted', pattern: /\.toSorted\(/u, since: '20.0.0' },
  { name: 'Array.prototype.toReversed', pattern: /\.toReversed\(/u, since: '20.0.0' },
  { name: 'Array.prototype.with', pattern: /\.with\([^)]/u, since: '20.0.0' },
  { name: 'import.meta.dirname', pattern: /import\.meta\.dirname/u, since: '20.11.0' },
  { name: 'Object.groupBy', pattern: /\bObject\.groupBy\(/u, since: '21.0.0' },
  { name: 'Map.groupBy', pattern: /\bMap\.groupBy\(/u, since: '21.0.0' },
  { name: 'Promise.withResolvers', pattern: /Promise\.withResolvers\b/u, since: '22.0.0' },
  { name: 'Array.fromAsync', pattern: /Array\.fromAsync\b/u, since: '22.0.0' },
  { name: 'fs.glob', pattern: /\bfs\.glob(Sync)?\(/u, since: '22.0.0' },
  { name: 'process.getBuiltinModule', pattern: /process\.getBuiltinModule\(/u, since: '22.3.0' },
  { name: 'node:sqlite', pattern: /['"]node:sqlite['"]/u, since: '22.5.0' },
  { name: 'RegExp.escape', pattern: /RegExp\.escape\(/u, since: '24.0.0' },
]

/** 逐段比较 `x.y.z` 形式的版本号。 */
function isAboveFloor(version) {
  const parse = text => text.split('.').map(part => Number.parseInt(part, 10))
  const left = parse(version)
  const right = parse(FLOOR)
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const a = left[index] ?? 0
    const b = right[index] ?? 0
    if (a !== b) return a > b
  }
  return false
}

/**
 * 两个根下**受版本控制**的 `.js` / `.cjs` / `.mjs` 源文件（绝对路径）。
 *
 * 走 `git ls-files` 而不是遍历目录：`scripts/runtime-profile` 是 pnpm 工作区，磁盘上还有
 * `node_modules` 与 pnpm 补丁留下的 `.patch-source` / `.pnpm-pi-ai-patch` 之类未跟踪的第三方
 * 副本（它们自己就用 `AbortSignal.any`，但不属于我们维护的访客代码）。按跟踪清单扫描，
 * 范围等于「我们真正发出去的那些文件」。
 */
function guestSources() {
  const listed = execFileSync('git', ['ls-files', '--', ...GUEST_ROOTS], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  return listed
    .split('\n')
    .filter(Boolean)
    .filter(path => ['.js', '.cjs', '.mjs'].includes(extname(path)))
    .map(path => join(repositoryRoot, path))
    .sort()
}

/**
 * 去掉注释后再扫描。
 *
 * 必须去：`AbortSignal.any` 在说明注释里出现好几次（包括讲解这次事故的注释），把注释算成使用
 * 会让这条契约永远失败或永远被加白名单 —— 两种结果都等于没有契约。
 */
function stripComments(source) {
  const kept = []
  let inBlock = false
  for (const line of source.split('\n')) {
    const trimmed = line.trim()
    if (inBlock) {
      if (trimmed.includes('*/')) inBlock = false
      continue
    }
    if (trimmed.startsWith('/*')) {
      if (!trimmed.includes('*/')) inBlock = true
      continue
    }
    if (trimmed.startsWith('//') || trimmed.startsWith('*')) continue
    kept.push(line)
  }
  return kept.join('\n')
}

/** 单文件内的违规：超过下限、且没有 guard 的 API。 */
function violationsIn(source) {
  const code = stripComments(source)
  const problems = []
  for (const rule of RULES) {
    if (!isAboveFloor(rule.since)) continue
    const lines = code.split('\n').filter(line => rule.pattern.test(line))
    if (lines.length === 0) continue
    // `lineGuard`：每一处使用都必须落在防御分支那一行上，最严格。
    if (rule.lineGuard) {
      if (lines.some(line => !rule.lineGuard.test(line))) problems.push(rule.name)
      continue
    }
    if (rule.guard && rule.guard.test(code)) continue
    problems.push(rule.name)
  }
  return problems
}

test('访客侧用到的每个新 API 都必须带特性探测', () => {
  const failures = []
  for (const path of guestSources()) {
    const problems = violationsIn(readFileSync(path, 'utf8'))
    if (problems.length > 0) failures.push(`${path.replace(`${repositoryRoot}/`, '').replace(/\\/gu, '/')}: ${problems.join(', ')}`)
  }
  assert.deepEqual(failures, [], `这些 API 高于访客下限 ${FLOOR} 却没有特性探测：\n${failures.join('\n')}`)
})

test('注释里提到的 API 不算使用，真正的调用仍然会被拦下', () => {
  const commented = ['/**', ' * `AbortSignal.any` 是 Node 20.3 才有的 API。', ' */', 'const value = 1'].join('\n')
  assert.deepEqual(violationsIn(commented), [])
  assert.deepEqual(violationsIn('const signal = AbortSignal.any([a, b])'), ['AbortSignal.any'])
  assert.deepEqual(violationsIn('if (typeof AbortSignal.any === \'function\') AbortSignal.any(list)'), [])
  // 有回退之后又冒出一处裸调用：不能因为文件里已经有 guard 就整体放行。
  const mixed = ['function anySignal(list) {', '  if (typeof AbortSignal.any === \'function\') return AbortSignal.any(list)', '}', 'const raw = AbortSignal.any(list)'].join('\n')
  assert.deepEqual(violationsIn(mixed), ['AbortSignal.any'])
  assert.deepEqual(violationsIn('const sorted = list.toSorted()'), ['Array.prototype.toSorted'])
  assert.deepEqual(violationsIn('await Promise.withResolvers()'), ['Promise.withResolvers'])
})

test('插件声明了访客 Node 下限，且与契约表一致', () => {
  const path = join(repositoryRoot, 'scripts/runtime-profile/plugins/dsh-mobile-shizuku/package.json')
  const manifest = JSON.parse(readFileSync(path, 'utf8'))
  assert.equal(manifest.engines?.node, `>=${FLOOR}`)
})

test('扫描范围不是空的（规则失效时这条先红）', () => {
  const sources = guestSources().map(path => path.replaceAll('\\', '/'))
  assert.ok(sources.length >= 6, `只扫到 ${sources.length} 个访客源文件`)
  assert.ok(sources.some(path => path.endsWith('dsh-mobile-shizuku/lib/index.js')))
  assert.ok(sources.some(path => path.endsWith('runtime-self-check.cjs')))
  assert.ok(RULES.some(rule => isAboveFloor(rule.since)), '规则表里没有一条高于下限的 API')
})
