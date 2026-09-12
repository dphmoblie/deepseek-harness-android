#!/usr/bin/env node
// 运行时去重检查：确认带 Symbol 的 dsh 服务包在依赖树里只有一份物理副本。
//
// 为什么必须只有一份：
//   @deepseek-ai/dsh-tools 导出 `TOOL_RUNTIME_SCHEDULER = Symbol('@deepseek-ai/dsh-tools.scheduler')`，
//   dsh 的 ToolRuntime 服务用这个 Symbol 作为 key 把调度器挂进 cordis 注册表，
//   dsh-agent-loop 再用 `ctx.tools[TOOL_RUNTIME_SCHEDULER].prepare(...)` 取回来。
//   Symbol 是**每个物理模块副本各造一个**：树里只要存在两份 dsh-tools，两边的 Symbol
//   就不相等，取到的就是 undefined，每次工具调用都会在 '.prepare' 上抛
//   `Cannot read properties of undefined (reading 'prepare')`。
//   同一个包名出现两个不同 version，或者同一 version 因为 peer 组合不同被 pnpm 拆成
//   两个虚拟store条目，都会造成两份物理副本，因此两种都要判失败。
//
// 用法：
//   node scripts/check-runtime-dedupe.mjs                    # 默认检查 scripts/runtime-profile
//   node scripts/check-runtime-dedupe.mjs /tmp/dsh-root      # 检查已构建的运行时根目录
//   node scripts/check-runtime-dedupe.mjs --list <root>      # 额外打印全部物理副本清单
//
// 退出码：0 = 每个包都恰好一份；1 = 存在重复副本（致命，CI 直接失败）。
// 仅使用 Node 内置能力，不引第三方依赖。

import { existsSync, lstatSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'

// 通过 Symbol 在 cordis 注册表里挂服务的包：任何一份多余副本都会让 Symbol 身份失配。
const GUARDED_PACKAGES = [
  '@deepseek-ai/dsh-tools',
  '@deepseek-ai/dsh-llm',
  '@deepseek-ai/dsh-attachment',
  '@deepseek-ai/dsh-system-prompt',
  '@deepseek-ai/dsh-base',
  '@deepseek-ai/dsh-scope',
  '@deepseek-ai/dsh-session',
  '@deepseek-ai/dsh-agent',
  '@deepseek-ai/dsh-fs',
  '@deepseek-ai/dsh-jobs',
]

/** 把 '@scope/name' 转成 pnpm 虚拟store目录名里使用的 '@scope+name'（仅用于打印提示）。 */
const encodeName = name => name.replace('/', '+')

/**
 * 定位 .pnpm 目录。允许传入项目根、node_modules 或 .pnpm 本身。
 * 找不到 .pnpm 时返回 null，由调用方回退到扁平 node_modules 扫描。
 */
const resolvePnpmStore = input => {
  const candidates = [
    path.join(input, 'node_modules', '.pnpm'),
    path.join(input, '.pnpm'),
    input,
  ]
  for (const candidate of candidates) {
    if (existsSync(candidate) && statSync(candidate).isDirectory()) {
      const base = path.basename(candidate)
      if (base === '.pnpm') return candidate
    }
  }
  return null
}

/** 判断某个条目是否是符号链接/联接（pnpm 用它表示「依赖引用」而不是包本体）。 */
const isLink = target => {
  try {
    return lstatSync(target).isSymbolicLink()
  } catch {
    return false
  }
}

/** 读取 package.json 里的真实版本；读不到返回 null。 */
const readVersion = packageJsonPath => {
  try {
    const parsed = JSON.parse(readFileSync(packageJsonPath, 'utf8'))
    return typeof parsed.version === 'string' ? parsed.version : null
  } catch {
    return null
  }
}

/**
 * 在 .pnpm 布局下收集目标包的物理副本。
 *
 * 每个虚拟store目录 `.pnpm/<条目名>/node_modules/` 下都可能出现目标包，但只有两种含义：
 *   - 真实目录 = 该包自己的包本体（一份物理副本）；
 *   - 符号链接/联接 = 别的包对它的依赖引用，最终仍指向某一份包本体。
 * 因此判定「是不是包本体」只依赖文件系统类型，**不依赖目录名**：pnpm 会截断过长的
 * 目录名（例如 `@deepseek-ai+dsh-system-pro_b3e6c8...` 里包名被砍成 `dsh-system-pro`，
 * `@deepseek-ai+dsh-tools@0.1._4c4a17...` 里版本被砍成 `0.1.`），按目录名匹配既会漏也会错。
 * 版本同样一律以包内 package.json 的 version 字段为准。
 */
const collectFromPnpmStore = (store, wanted) => {
  const found = new Map(wanted.map(name => [name, []]))
  for (const entry of readdirSync(store)) {
    const modulesDir = path.join(store, entry, 'node_modules')
    if (!existsSync(modulesDir)) continue
    for (const name of wanted) {
      const packageDir = path.join(modulesDir, name)
      if (!existsSync(packageDir)) continue
      if (isLink(packageDir)) continue
      const version = readVersion(path.join(packageDir, 'package.json'))
      found.get(name).push({ version, location: entry, packageDir })
    }
  }
  return found
}

/** 没有 .pnpm 时的回退：直接看扁平的 node_modules/<包名>。 */
const collectFromFlatModules = (root, wanted) => {
  const found = new Map(wanted.map(name => [name, []]))
  for (const name of wanted) {
    const packageDir = path.join(root, 'node_modules', name)
    if (!existsSync(packageDir)) continue
    const version = readVersion(path.join(packageDir, 'package.json'))
    if (version === null) continue
    found.get(name).push({ version, location: 'node_modules', packageDir })
  }
  return found
}

const main = () => {
  const argv = process.argv.slice(2)
  const listAll = argv.includes('--list')
  const positional = argv.filter(arg => !arg.startsWith('--'))
  const root = path.resolve(positional[0] ?? path.join(import.meta.dirname, 'runtime-profile'))

  if (!existsSync(root)) {
    console.error(`检查根目录不存在：${root}`)
    process.exit(1)
  }

  const store = resolvePnpmStore(root)
  const found = store
    ? collectFromPnpmStore(store, GUARDED_PACKAGES)
    : collectFromFlatModules(root, GUARDED_PACKAGES)

  console.log(`检查根目录：${root}`)
  console.log(store ? `虚拟store：${store}` : '未发现 .pnpm，按扁平 node_modules 检查')

  const failures = []
  for (const name of GUARDED_PACKAGES) {
    const copies = found.get(name)
    const versions = [...new Set(copies.map(copy => copy.version))]
    if (copies.length === 0) {
      console.log(`  缺失  ${name}（不在本依赖树中）`)
      continue
    }
    const versionSummary = versions.map(v => v ?? '<无法读取>').join(', ')
    if (copies.length === 1) {
      console.log(`  OK    ${name}  1 份  版本 ${versionSummary}`)
      continue
    }
    failures.push({ name, copies, versions })
    console.log(`  重复! ${name}  ${copies.length} 份  版本 ${versionSummary}`)
  }

  if (failures.length > 0) {
    console.error('')
    console.error('检测到重复的 dsh 服务包副本；这会让 Symbol 身份失配，导致工具调用全部失败：')
    for (const { name, copies, versions } of failures) {
      console.error(`  ${name}：${copies.length} 份物理副本，${versions.length} 个不同版本`)
      for (const copy of copies) {
        console.error(`    - 版本 ${copy.version ?? '<无法读取>'}  ${copy.packageDir}`)
      }
    }
    console.error('')
    console.error('修复方向：把整个 @deepseek-ai/dsh 家族钉到同一版本，并在 pnpm-workspace.yaml 用 overrides 兜底。')
    process.exit(1)
  }

  if (listAll) {
    console.log('')
    console.log('=== 物理副本清单 ===')
    for (const name of GUARDED_PACKAGES) {
      for (const copy of found.get(name)) {
        console.log(`  ${name}@${copy.version ?? '<无法读取>'}  ${copy.packageDir}`)
      }
    }
  }

  console.log('')
  console.log(`通过：${GUARDED_PACKAGES.length} 个带 Symbol 的服务包各自只有一份物理副本。`)
}

main()
