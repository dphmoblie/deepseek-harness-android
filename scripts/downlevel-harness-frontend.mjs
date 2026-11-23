#!/usr/bin/env node
/**
 * 把官方 Harness 前端产物**语法降级**到旧 WebView 能解析的程度（派生副本，原件不动）。
 *
 * 为什么需要它：官方产物是给新版内核打的包。`scripts/webview-compat-scan.mjs` 实测当前
 * `@deepseek-ai/dsh-web-frontend` 的语法下限是 **Chromium 94**（class static block），
 * 更低的内核（Android 10 出厂 WebView 是 Chromium 74）在**解析期**就 SyntaxError：
 * 页面白屏、控制台一行都没跑，`HarnessPolyfill` 这类注入脚本没有任何机会生效。
 * 语法层只能靠转换产物解决，API 层才归 polyfill。
 *
 * 三条边界：
 * 1. **绝不改原件**。`--from` 只读；产物写进 `--to`（默认仓库根的 `dist-legacy/`）。
 *    `harness-web/scripts/build-official-frontend.mjs` 的"逐字节保留官方产物"契约因此
 *    依然成立：被降级的是另一份派生目录。
 * 2. **产物不写进 `harness-web/` 子模块**：子模块的 `.gitignore` 不归本仓库管，写进去只会让
 *    `git status` 出现 `M harness-web`。派生目录因此落在仓库根，与本仓库的 `dist/` 并列。
 * 3. **文件名不变**。`index.html` 里的 `assets/index-<hash>.js` 是内容哈希命名，转换后
 *    哈希与内容不再匹配——这是可接受的：文件名只用于寻址，项目里没有基于哈希的完整性校验。
 * 4. **不改语义**。只做 esbuild 的语法 lowering（`?.`、`??`、`??=`、class 私有成员、
 *    static block 等），不做压缩、不注入运行时。
 *
 * 用法：
 *   node scripts/downlevel-harness-frontend.mjs                     # → dist-legacy
 *   node scripts/downlevel-harness-frontend.mjs --from <dir> --to <dir>
 *   node scripts/downlevel-harness-frontend.mjs --target chrome74
 *   node scripts/downlevel-harness-frontend.mjs --check             # 只报告会转换多少，不落盘
 *
 * 代价（实测，官方 0.2.1-rc.1 产物）：index 918 KB → 1.24 MB（+35%），vendor 724 KB →
 * 约 1 MB。降级后的产物仍要过一遍 `scripts/webview-compat-scan.mjs <--to 目录>` 确认
 * 语法残留为零，并在真机上验证启动/会话/目录选择三条主链路。
 */
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { syntaxFloor } from './webview-compat-contract.mjs'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const DEFAULT_FROM = join(projectRoot, 'harness-web/dist')
const DEFAULT_TO = join(projectRoot, 'dist-legacy')
const DEFAULT_TARGET = 'chrome74'

const HELP = `官方前端语法降级（派生产物）
  node scripts/downlevel-harness-frontend.mjs [选项]

选项：
  --from <目录>     源产物，默认 harness-web/dist（只读）
  --to <目录>       输出目录，默认 dist-legacy（仓库根，已 gitignore）
  --target <目标>   esbuild 目标，默认 ${DEFAULT_TARGET}（Android 10 出厂 WebView）
  --check           只统计不写盘
  --quiet           只输出结论行`

function parseArgs(argv) {
  const options = { from: DEFAULT_FROM, to: DEFAULT_TO, target: DEFAULT_TARGET, check: false, quiet: false }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--from') { options.from = resolve(argv[index + 1]); index += 1 }
    else if (arg === '--to') { options.to = resolve(argv[index + 1]); index += 1 }
    else if (arg === '--target') { options.target = argv[index + 1]; index += 1 }
    else if (arg === '--check') options.check = true
    else if (arg === '--quiet') options.quiet = true
    else if (arg === '--help' || arg === '-h') options.help = true
    else throw new Error(`未知参数 ${arg}`)
  }
  return options
}

/**
 * 降级一段 JS 代码。单独导出是为了让测试能直接跑一个合成样本，不必依赖真实产物。
 *
 * `format` 默认 `esm`：官方入口是 `<script type="module">`，保持模块格式，`import`/`export`
 * 与动态 `import()` 的 specifier 都会原样保留（esbuild 的 transform 不改模块解析）。
 */
export async function downlevelCode(code, filename, target = DEFAULT_TARGET, format = 'esm') {
  const { transformWithEsbuild } = await import('vite')
  const result = await transformWithEsbuild(code, filename, { target, loader: 'js', format, sourcemap: false })
  return result
}

/**
 * 反复降级到不动点。
 *
 * 单趟不一定够：esbuild 为某些语法生成的替身（例如类私有成员降级后的 helper）本身还会被
 * 下一趟继续改写，vendor 包实测要两趟。判据是"这一趟有没有改动"——transform 幂等，输出与
 * 输入逐字节相同就说明没有可降的语法了。
 */
export async function downlevelUntilStable(code, filename, target) {
  let current = code
  let passes = 0
  for (let round = 0; round < 4; round += 1) {
    const result = await downlevelCode(current, filename, target)
    if (result.code === current) return { code: current, passes, warnings: result.warnings ?? [] }
    current = result.code
    passes += 1
  }
  return { code: current, passes, warnings: [], unstable: true }
}

/** 递归列出 `.js`（跳过 `.map`）。 */
async function collect(dir) {  const entries = await readdir(dir, { recursive: true, withFileTypes: true })
  return entries
    .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
    .map(entry => join(entry.parentPath, entry.name))
    .sort()
}

function isInside(parent, child) {
  const base = parent.endsWith(sep) ? parent : parent + sep
  return child === parent || child.startsWith(base)
}

async function main() {
  let options
  try {
    options = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error(error.message)
    console.error(HELP)
    process.exit(2)
  }
  if (options.help) {
    console.log(HELP)
    return
  }
  if (!existsSync(options.from)) {
    console.error(`源目录不存在：${options.from}`)
    console.error('先构建官方前端（cd harness-web && pnpm build）。')
    process.exit(2)
  }
  if (isInside(options.from, options.to)) {
    console.error(`输出目录不能落在源目录里：${options.to}`)
    process.exit(2)
  }

  const sourceFiles = await collect(options.from)
  if (sourceFiles.length === 0) {
    console.error(`源目录里没有 JS 产物：${options.from}`)
    process.exit(2)
  }
  const before = { bytes: 0, floor: 0, hits: new Map() }
  for (const file of sourceFiles) {
    const text = await readFile(file, 'utf8')
    before.bytes += (await stat(file)).size
    const syntax = syntaxFloor(text)
    if (syntax.floor > before.floor) before.floor = syntax.floor
    for (const hit of syntax.hits) {
      const known = before.hits.get(hit.feature.id)
      before.hits.set(hit.feature.id, { feature: hit.feature, count: (known?.count ?? 0) + hit.count })
    }
  }

  if (options.check) {
    console.log(
      `[check] ${sourceFiles.length} 个 JS，${formatBytes(before.bytes)}；` +
        `源产物语法下限 Chromium ${before.floor}，目标 ${options.target}；未写盘。`,
    )
    return
  }

  await rm(options.to, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  await mkdir(dirname(options.to), { recursive: true })
  await cp(options.from, options.to, { recursive: true })

  let afterBytes = 0
  let afterFloor = 0
  const remaining = new Map()
  const failures = []
  const unstable = []
  const multiPass = []
  for (const file of sourceFiles) {
    const target = join(options.to, file.slice(options.from.length).replace(/^[\\/]/u, ''))
    const text = await readFile(file, 'utf8')
    try {
      const result = await downlevelUntilStable(text, file, options.target)
      await writeFile(target, result.code, 'utf8')
      afterBytes += Buffer.byteLength(result.code)
      if (result.passes > 1) multiPass.push({ file, passes: result.passes })
      const syntax = syntaxFloor(result.code)
      if (syntax.floor > afterFloor) afterFloor = syntax.floor
      for (const hit of syntax.hits) {
        const known = remaining.get(hit.feature.id)
        remaining.set(hit.feature.id, { feature: hit.feature, count: (known?.count ?? 0) + hit.count })
      }
      /*
       * 判据不是数残留 token（压缩代码里 `??`/`?.` 的同形片段可能出现在字符串或正则字面量
       * 里，数不干净），而是「降级到不动点」：见 downlevelUntilStable。
       */
      if (result.unstable === true) unstable.push(file)
      for (const warning of result.warnings) {
        if (warning.text.includes(options.target)) failures.push(`${file}: ${warning.text}`)
      }
    } catch (error) {
      failures.push(`${file}: ${error.message}`)
    }
  }

  if (failures.length > 0) {
    console.error(`降级失败 ${failures.length} 处：`)
    for (const failure of failures.slice(0, 10)) console.error(`  - ${failure}`)
    process.exit(1)
  }

  if (!options.quiet) {
    console.log(`源：${options.from}（${sourceFiles.length} 个 JS，${formatBytes(before.bytes)}）`)
    console.log(`输出：${options.to}（${formatBytes(afterBytes)}，目标 ${options.target}）`)
    console.log(`体积变化：+${formatBytes(Math.max(0, afterBytes - before.bytes))}`)
    if (before.hits.size > 0) {
      console.log('源产物语法残留：')
      for (const { feature, count } of sortByNeed(before.hits)) {
        console.log(`  ${feature.syntax}（需 Chromium ${feature.need}）命中 ${count}`)
      }
    }
  }
  console.log(
    `降级完成：源产物语法下限 Chromium ${before.floor}；输出已到 ${options.target} 的不动点` +
      (remaining.size > 0 ? `（残余字样最高需求 ${afterFloor}，见下）` : '（无残余字样）'),
  )
  if (remaining.size > 0) {
    console.log('仍能数到的语法字样（多为字符串/正则里的同形片段；以「第二遍转换是否改动」为准）：')
    for (const { feature, count } of sortByNeed(remaining)) {
      console.log(`  ${feature.syntax}（需 Chromium ${feature.need}）命中 ${count}`)
    }
  }
  if (unstable.length > 0) {
    console.log(`未降级到位 ${unstable.length} 个文件（4 趟转换后仍在改动）：`)
    for (const file of unstable.slice(0, 10)) console.log(`  - ${file}`)
    process.exitCode = 1
  }
  if (multiPass.length > 0 && !options.quiet) {
    console.log(`需要多趟转换的文件 ${multiPass.length} 个（正常现象，esbuild 的替身本身就还能再降）：`)
    for (const entry of multiPass.slice(0, 5)) console.log(`  - ${entry.file}（${entry.passes} 趟）`)
  }
  console.log('下一步：node scripts/webview-compat-scan.mjs --baseline android10-stock ' + options.to)
}

function sortByNeed(map) {
  return [...map.values()].sort((left, right) => right.feature.need - left.feature.need)
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main()
}
