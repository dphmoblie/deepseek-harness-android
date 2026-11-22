#!/usr/bin/env node
/**
 * 旧 WebView 缺口扫描：**上游前端用到的浏览器 API** 与 **目标内核版本** 的差集。
 *
 * 用法：
 *   node scripts/webview-compat-scan.mjs                     # 默认扫官方 dist + 应用外壳 dist
 *   node scripts/webview-compat-scan.mjs --baseline chrome74
 *   node scripts/webview-compat-scan.mjs <运行时目录> [...]    # 也可扫 rootfs 里的插件产物
 *   node scripts/webview-compat-scan.mjs --strict            # 有未覆盖缺口就退出 1
 *   node scripts/webview-compat-scan.mjs --json              # 机读输出
 *
 * 为什么是"扫产物"而不是"读源码"：真正跑在手机上的只有打包后的 JS。上游源码里有没有用
 * 某个 API 不重要——插件被 tree-shake 掉、或者只在某条分支里出现，才是真相。扫描器因此
 * 只吃 `.js` 产物，且对命中次数只做计数、不做语义判断（是否为热点路径由人评估）。
 *
 * 退出码：0 无未覆盖缺口（或有缺口但没开 --strict）；1 有未覆盖缺口且开了 --strict；
 *         2 用法错误 / 没有可扫描的文件。
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  BASELINES,
  WEB_API_FEATURES,
  gapsFor,
  readPolyfillScript,
  resolveBaseline,
  scanUsage,
  syntaxFloor,
} from './webview-compat-contract.mjs'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const polyfillPath = join(projectRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessPolyfill.kt')
const shellPolyfillPath = join(projectRoot, 'src/legacyGlobals.ts')

/** 默认扫描范围：官方前端产物 + 应用外壳（Capacitor WebView 里跑的那份）。 */
const DEFAULT_ROOTS = [
  join(projectRoot, 'harness-web/node_modules/@deepseek-ai/dsh-web-frontend/dist'),
  join(projectRoot, 'dist'),
]

/**
 * 单个文件的上限。语言包 chunk 都在几百 KB 量级；超过这个值基本是 sourcemap 或误选的大文件，
 * 读进来只会拖慢扫描。
 */
const MAX_BYTES = 8 * 1024 * 1024

function parseArgs(argv) {
  const options = {
    baseline: BASELINES['android10-stock'],
    roots: [],
    json: false,
    strict: false,
    syntax: true,
    maxBytes: MAX_BYTES,
  }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--baseline') {
      options.baseline = resolveBaseline(argv[index + 1])
      index += 1
    } else if (arg.startsWith('--baseline=')) {
      options.baseline = resolveBaseline(arg.slice('--baseline='.length))
    } else if (arg === '--json') {
      options.json = true
    } else if (arg === '--strict') {
      options.strict = true
    } else if (arg === '--no-syntax') {
      options.syntax = false
    } else if (arg === '--max-bytes') {
      options.maxBytes = Number(argv[index + 1])
      index += 1
    } else if (arg === '--help' || arg === '-h') {
      options.help = true
    } else if (arg.startsWith('-')) {
      throw new Error(`未知参数 ${arg}`)
    } else {
      options.roots.push(resolve(arg))
    }
  }
  return options
}

const HELP = `旧 WebView 缺口扫描
  node scripts/webview-compat-scan.mjs [选项] [目录...]

选项：
  --baseline <名称|版本>  目标内核主版本，默认 ${BASELINES['android10-stock']}（android10-stock）
                          可用别名：${Object.keys(BASELINES).join(' / ')}
  --strict                存在「用了但补丁没盖」的 API 时退出 1（用于门禁）
  --no-syntax             跳过语法层统计（只关心 API 时更快）
  --json                  输出 JSON
  --max-bytes <n>         单文件上限，默认 ${MAX_BYTES}
目录省略时扫默认范围：官方前端 dist、应用外壳 dist。`

/** 递归收集 `.js` 产物（跳过 `.map`、超限文件）。 */
async function collectFiles(root, maxBytes) {
  const files = []
  const skipped = []
  let entries
  try {
    entries = await readdir(root, { recursive: true, withFileTypes: true })
  } catch (error) {
    return { files, skipped: [{ path: root, reason: `无法读取：${error.code ?? error.message}` }] }
  }
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith('.js')) continue
    const fullPath = join(entry.parentPath, entry.name)
    const info = await stat(fullPath)
    if (info.size > maxBytes) {
      skipped.push({ path: fullPath, reason: `超过 ${maxBytes} 字节` })
      continue
    }
    files.push({ path: fullPath, size: info.size })
  }
  files.sort((left, right) => left.path.localeCompare(right.path))
  return { files, skipped }
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
  const roots = options.roots.length > 0 ? options.roots : DEFAULT_ROOTS.filter(existsSync)
  if (roots.length === 0) {
    console.error('没有可扫描的目录：默认的官方前端 dist 与应用外壳 dist 都不存在。')
    console.error('先构建（pnpm run build / harness-web 的 pnpm build），或显式传入目录。')
    process.exit(2)
  }

  const script = readPolyfillScript(await readFile(polyfillPath, 'utf8'))
  // 外壳的垫片是 TS 源码，扫描器只需要它的文本（查找守卫表达式），不需要编译。
  const shellSource = existsSync(shellPolyfillPath) ? await readFile(shellPolyfillPath, 'utf8') : ''
  const scans = []
  let totalBytes = 0
  let totalFiles = 0
  for (const root of roots) {
    const { files, skipped } = await collectFiles(root, options.maxBytes)
    const hits = new Map()
    let syntax = { floor: 0, hits: [] }
    for (const file of files) {
      const text = await readFile(file.path, 'utf8')
      for (const [id, count] of scanUsage(text)) hits.set(id, (hits.get(id) ?? 0) + count)
      if (options.syntax) {
        const fileSyntax = syntaxFloor(text)
        for (const hit of fileSyntax.hits) {
          const known = syntax.hits.find(entry => entry.feature.id === hit.feature.id)
          if (known === undefined) syntax.hits.push({ ...hit })
          else known.count += hit.count
        }
        if (fileSyntax.floor > syntax.floor) syntax.floor = fileSyntax.floor
      }
    }
    syntax.hits.sort((left, right) => right.feature.need - left.feature.need)
    totalBytes += files.reduce((sum, file) => sum + file.size, 0)
    totalFiles += files.length
    scans.push({ root, files, skipped, gaps: gapsFor({ hits, baseline: options.baseline, harness: script, shell: shellSource }), syntax })
  }

  // 「未覆盖」只算**本该补却没补**的项。`polyfillable: false` 的条目是契约表里做过决定、
  // 写了 reason 的（WeakRef/structuredClone 这类补不齐语义的），它们必须出现在报告里，但不该
  // 让门禁永远红——否则真出问题那天，红的那条早就被当成背景噪声了。
  const gapList = scans.flatMap(scan => scan.gaps)
  const uncovered = gapList.filter(gap => gap.covered === null && gap.feature.polyfillable !== false)
  const accepted = gapList.filter(gap => gap.feature.polyfillable === false)
  const report = {
    baseline: options.baseline,
    polyfillLength: script.length,
    featureCount: WEB_API_FEATURES.length,
    totalFiles,
    totalBytes,
    scans: scans.map(scan => ({
      root: relative(projectRoot, scan.root) || scan.root,
      files: scan.files.length,
      skipped: scan.skipped,
      gaps: scan.gaps.map(gap => ({
        id: gap.feature.id,
        api: gap.feature.api,
        need: gap.feature.need,
        count: gap.count,
        covered: gap.covered,
        reason: gap.feature.reason ?? null,
      })),
      syntax: options.syntax ? scan.syntax : null,
    })),
    uncovered: uncovered.map(gap => ({ id: gap.feature.id, api: gap.feature.api, need: gap.feature.need, count: gap.count })),
    accepted: accepted.map(gap => ({ id: gap.feature.id, api: gap.feature.api, need: gap.feature.need, count: gap.count, reason: gap.feature.reason ?? null })),
  }

  if (options.json) {
    console.log(JSON.stringify(report, null, 2))
  } else {
    printReport(report)
  }

  if (uncovered.length > 0 && options.strict) process.exitCode = 1
}

function printReport(report) {
  console.log('=== 旧 WebView 兼容扫描 ===')
  console.log(
    `基线：Chromium ${report.baseline}　扫描 ${report.totalFiles} 个 JS 文件（${formatBytes(report.totalBytes)}）` +
      `　特性表 ${report.featureCount} 项　补丁 ${report.polyfillLength} 字符`,
  )
  for (const scan of report.scans) {
    console.log(`\n--- ${scan.root}（${scan.files} 个文件）`)
    for (const skipped of scan.skipped) {
      console.log(`  ! 跳过 ${relative(process.cwd(), skipped.path)}：${skipped.reason}`)
    }
    if (scan.gaps.length === 0) {
      console.log(`  基线之上的 API 缺口：无（${report.baseline} 及以上原生都有）`)
    } else {
      console.log('  基线之上的 API 缺口（用了但内核没有）：')
      for (const gap of scan.gaps) {
        const mark =
          gap.covered === 'harness'
            ? '已补(注入)'
            : gap.covered === 'shell'
              ? '已补(外壳)'
              : gap.reason === null
                ? '未补'
                : '补不了'
        console.log(
          `    ${pad(gap.api, 46)} 需要 Chromium ${String(gap.need).padEnd(4)} 命中 ${String(gap.count).padEnd(4)} ${mark}`,
        )
        if (gap.covered === null && typeof gap.reason === 'string') console.log(`      理由：${gap.reason}`)
      }
    }
    if (scan.syntax !== null && scan.syntax.hits.length > 0) {
      console.log('  语法层（polyfill 救不了，旧内核解析期就 SyntaxError；命中数是粗口径，可能含字符串/正则里的同形片段）：')
      for (const hit of scan.syntax.hits) {
        console.log(
          `    ${pad(hit.feature.syntax, 46)} 需要 Chromium ${String(hit.feature.need).padEnd(4)} 命中 ${hit.count}`,
        )
      }
      console.log(`  → 语法字样最高需求：Chromium ${scan.syntax.floor}（粗口径，见上行说明）`)
      if (scan.syntax.floor > report.baseline) {
        console.log(
          `  → 这些产物在 Chromium < ${scan.syntax.floor} 上很可能连解析都过不去（polyfill 无效）：` +
            '用 scripts/downlevel-harness-frontend.mjs 降级后复扫，或对该机型明确不支持。',
        )
      }
    }
  }
  console.log('')
  if (report.accepted.length > 0) {
    console.log(`已记录、明确不补的缺口（契约表里写了 reason，${report.accepted.length} 项）：`)
    for (const gap of report.accepted) console.log(`  - ${gap.api}（需要 Chromium ${gap.need}，命中 ${gap.count}）`)
  }
  if (report.uncovered.length === 0) {
    console.log('结论：基线之上没有「本该补却没补」的 API 缺口。')
  } else {
    console.log(`结论：${report.uncovered.length} 项未覆盖缺口——`)
    for (const gap of report.uncovered) console.log(`  - ${gap.api}（需要 Chromium ${gap.need}，命中 ${gap.count}）`)
    console.log('处理：能补的补进 HarnessPolyfill.SCRIPT 并加 vm 语义测试；补不了的写进契约表 reason 并评估降级路径。')
  }
}

function pad(text, width) {
  const value = String(text)
  // 中文按两个字符宽度估算，避免表格在终端里错位。
  const displayWidth = [...value].reduce((sum, char) => sum + (char.charCodeAt(0) > 0x2e80 ? 2 : 1), 0)
  return displayWidth >= width ? value : value + ' '.repeat(width - displayWidth)
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

await main()
