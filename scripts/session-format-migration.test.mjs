// 会话格式 v3 → v4 迁移的回归防线（dsh 0.1.5-rc.2 → 0.2.0-rc.2 升级）。
//
// 背景：0.2.0-rc.2 把会话格式版本从 3 提到 4（`dsh-session/lib/index.js` 的
// `SESSION_FORMAT_VERSION = 4`，旧版是 3）。真机上已经落盘的会话是 v3（旧内核自己写的
// `session.jsonl.zstd`），新内核读不出来就是数据事故——所以这条迁移必须在移动端 profile 里
// 真的会发生。
//
// 迁移机制（侦察结论，本文件用行为钉住它而不是复述文档）：
//   * 触发方式是**读旧文件时被动触发**，不需要显式注册任何插件或服务；
//   * `dsh-session-format-catalog` 是构建期生成的静态汇编（上游注释写明「直接 import 让历史
//     可读性不依赖已挂载的插件」），固定带 v0→v1…v3→v4 五条相邻边与 v0…v4 五个编解码器；
//   * 真正消费它的是 `dsh-session-persistence-jsonl`（正则认 `session[.vN].jsonl[.zstd]`、
//     按最高版本选当前代），而它已经由 `@deepseek-ai/dsh-base` 的 `cordis.patch.yml` 挂载
//     （`- id: session-persistence-jsonl`），移动插件的 patch 只往上加自己一个条目。
//   因此 profile 侧**不需要**新增依赖，也不需要往 patch 里加载任何东西。
//
// 本文件从 `scripts/runtime-profile/node_modules` 里取运行时实际会加载的那一份后端，
// 用上游自己的 v3 编解码器（`dsh-session-format-v2-to-v3` 的 `releasedV3SessionFormatCodec`）
// 造一个 v3 日志——不是手写 JSON 猜格式——然后断言：
//   1) 目录被登记为当前 v4（`list()`）；
//   2) 只读打开解析出 v4 头，且**不落盘**：迁移是惰性发布，光是读不会改写磁盘；
//   3) 取得写权时发布 `session.v4.jsonl[.zstd]` 新代，v3 源文件原样保留；
//   4) 冷启动的新后端仍把 v4 当当前代，且没有多出别的代文件。
// 两种压缩各跑一遍：`zstd` 是真机默认（`DEFAULT_COMPRESSION = "zstd"`，文件名带 `.zstd`），
// `none` 是明文。两者走的是**不同的文件名与不同的解码器**，只测一种会漏掉另一种。
//
// 平台边界（写路径为什么可能跳过）：
//   写打开要先拿跨进程写锁。POSIX 走 `@deepseek-ai/node-addon-system` 里预编译的
//   `flock(2)` 绑定（`bin/<libc>/system.node`，随平台包分发，`--ignore-scripts` 也装得来）；
//   Windows 走 koffi 动态绑定的 kernel32 命名信号量。
//   本工作区的 `supportedArchitectures` 只装 linux/arm64，所以**非 arm64 Linux** 的机器
//   （本机 Windows、CI 的 x64 runner）没有这两者中的任何一个。此时只读路径照常断言
//   （它完全不碰锁，而「旧会话能不能打开」正是用户可见的那个结论），写路径那条单独跳过
//   并打印原因——不假装通过，也不会因为环境缺原生二进制而误报成迁移回归。
//   本机想跑完整的写路径：把 `DSH_TEST_KOFFI_SHIM` 指向一份 win32 版 koffi 的入口
//   （只在测试进程内重定向裸导入 `koffi`，不改仓库也不改 node_modules）。

import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { registerHooks } from 'node:module'
import { pathToFileURL } from 'node:url'
import { test } from 'node:test'

const runtimeProfile = path.join(import.meta.dirname, 'runtime-profile')
const virtualStore = path.join(runtimeProfile, 'node_modules', '.pnpm')
const notInstalled = '未安装 scripts/runtime-profile 依赖（CI 会先 pnpm install --frozen-lockfile，届时必然执行）'

// 本机 Windows 的验证开关：linux/arm64 的 workspace 里没有 win32 版 koffi，
// 写锁的命名信号量起不来。指向一份 win32 koffi 入口即可在本机跑完整写路径。
const koffiShim = process.env['DSH_TEST_KOFFI_SHIM']
if (koffiShim !== undefined && koffiShim !== '' && process.platform === 'win32') {
  const shimUrl = pathToFileURL(fs.realpathSync(koffiShim)).href
  registerHooks({
    resolve(specifier, context, nextResolve) {
      if (specifier === 'koffi') return { url: shimUrl, shortCircuit: true }
      return nextResolve(specifier, context)
    },
  })
}

/**
 * 解析 profile 里运行时实际加载的那一份包目录。
 *
 * 优先沿 `dsh-base` 的真实目录旁边的 peer 根逐级向上找（与 Node 解析 `import` 时走的目录
 * 一致，能证明被测的就是运行时那一份），退化时再看 pnpm 的提升目录。
 * 不能按目录名硬编码：长包名会被 pnpm 截断成 `<前缀>_<哈希>`（例如
 * `@deepseek-ai+dsh-session-pe_032659419d0aff706f84d78b9ee0b0fe`），哈希随 peer 组合变化。
 */
function resolvePackage(name) {
  const dshBase = path.join(runtimeProfile, 'node_modules', '@deepseek-ai', 'dsh-base')
  if (fs.existsSync(dshBase)) {
    let dir = path.dirname(path.dirname(fs.realpathSync(dshBase)))
    for (;;) {
      const candidate = path.join(dir, 'node_modules', ...name.split('/'))
      if (fs.existsSync(candidate)) return fs.realpathSync(candidate)
      const parent = path.dirname(dir)
      if (parent === dir) break
      dir = parent
    }
  }
  const hoisted = path.join(virtualStore, 'node_modules', ...name.split('/'))
  return fs.existsSync(hoisted) ? fs.realpathSync(hoisted) : undefined
}

function entryOf(name) {
  const dir = resolvePackage(name)
  if (dir === undefined) return undefined
  const entry = path.join(dir, 'lib', 'index.js')
  return fs.existsSync(entry) ? entry : undefined
}

/** 写锁环境不可用的判据：都是「这台机器没有该平台的原生绑定」，不是迁移本身失败。 */
function lockUnavailableReason(error) {
  const message = String(error?.message ?? '')
  if (error?.code === 'ERR_FLOCK_UNSUPPORTED_PLATFORM') {
    return `本平台没有 POSIX flock：${message}`
  }
  if (error?.code === 'MODULE_NOT_FOUND' && /node-addon-system-/u.test(message)) {
    return `本平台没有 node-addon-system 的预编译 flock 绑定：${message.split('\n')[0]}`
  }
  if (/Cannot find the native Koffi module/u.test(message)) {
    return `Windows 写锁需要 koffi 的 win32 原生绑定，本机没有：${message}`
  }
  if (error?.code === 'ERR_DLOPEN_FAILED') {
    return `原生绑定加载失败（架构不匹配）：${message.split('\n')[0]}`
  }
  return undefined
}

async function loadRuntime(t) {
  const persistenceEntry = entryOf('@deepseek-ai/dsh-session-persistence-jsonl')
  const cordisEntry = entryOf('@deepseek-ai/cordis')
  const catalogEntry = entryOf('@deepseek-ai/dsh-session-format-catalog')
  const v2to3Entry = entryOf('@deepseek-ai/dsh-session-format-v2-to-v3')
  assert.ok(
    persistenceEntry !== undefined && cordisEntry !== undefined && catalogEntry !== undefined && v2to3Entry !== undefined,
    '未能从 scripts/runtime-profile/node_modules 解析会话持久化相关模块：请先运行 pnpm --dir scripts/runtime-profile install',
  )
  const { Context } = await import(pathToFileURL(cordisEntry).href)
  const { default: JsonlSessionPersistence } = await import(pathToFileURL(persistenceEntry).href)
  const { sessionFormatCatalog } = await import(pathToFileURL(catalogEntry).href)
  const { releasedV3SessionFormatCodec } = await import(pathToFileURL(v2to3Entry).href)
  assert.equal(typeof Context, 'function', 'cordis 没有导出 Context')
  assert.equal(typeof JsonlSessionPersistence, 'function', 'session-persistence-jsonl 没有导出插件类')
  assert.equal(typeof releasedV3SessionFormatCodec?.encodeHeader, 'function', '上游没有导出 v3 编解码器')
  return { Context, JsonlSessionPersistence, sessionFormatCatalog, releasedV3SessionFormatCodec }
}

/** 造一个 v3 日志（放在后端会去找的规范路径上），并起一个读得到它的后端。 */
async function fixture(t, runtime, compression) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-session-format-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 }))

  const id = 'session-format-migration-probe'
  const createdAt = 1750000000000
  // 无 cwd 的会话落在 <root>/_no-cwd/<id>/（见 persistence 的 projectDir / sessionDir）。
  const dir = path.join(root, '_no-cwd', id)
  fs.mkdirSync(dir, { recursive: true })

  const header = runtime.releasedV3SessionFormatCodec.encodeHeader(
    { version: 3, id, createdAt, isSeeded: false, delegationDepth: 0 },
    0,
  )
  assert.equal(header.version, 3, 'v3 编解码器没有写出 version=3 的头')
  const body = Buffer.from(`${JSON.stringify(header)}\n`)
  const suffix = compression === 'zstd' ? '.zstd' : ''
  const v3Path = path.join(dir, `session.v3.jsonl${suffix}`)
  fs.writeFileSync(v3Path, compression === 'zstd' ? zlib.zstdCompressSync(body) : body)
  const v4Path = path.join(dir, `session.v4.jsonl${suffix}`)

  const boot = async () => {
    const ctx = new runtime.Context()
    ctx.plugin(runtime.JsonlSessionPersistence, { root, compression })
    if (typeof ctx.start === 'function') await ctx.start()
    await new Promise(resolve => { setTimeout(resolve, 50) })
    return ctx.sessionPersistence
  }
  const backend = await boot()
  assert.equal(typeof backend?.open, 'function', `ctx.sessionPersistence 没有注册（${backend?.name}）`)
  return { root, dir, id, createdAt, body, v3Path, v4Path, backend, boot }
}

for (const compression of ['zstd', 'none']) {
  test(`会话格式 v3 → v4：新内核把旧会话读成 v4，且只读不改写磁盘（compression=${compression}）`, async t => {
    if (!fs.existsSync(virtualStore)) {
      t.skip(notInstalled)
      return
    }
    const runtime = await loadRuntime(t)
    // 这条断言就是整个升级的核心事实：运行时加载的格式目录当前版本是 4（旧钉是 3）。
    assert.equal(runtime.sessionFormatCatalog.currentVersion, 4, '格式目录的当前版本不是 v4，迁移链没有接上')

    const f = await fixture(t, runtime, compression)

    // 1) 登记：v3 文件被解析成当前格式 v4。
    const listed = await f.backend.list()
    assert.equal(listed.length, 1, `list() 没有登记这一个会话：${JSON.stringify(listed)}`)
    assert.equal(listed[0].header.version, 4, 'v3 文件没有被解析成 v4')
    assert.equal(listed[0].header.id, f.id)
    assert.equal(listed[0].header.createdAt, f.createdAt, '迁移把 createdAt 改掉了')

    // 2) 只读打开：拿到 v4 头，但不该落盘任何新代（读不是写）。
    const readHandle = await f.backend.open(f.id, 'read')
    assert.equal(readHandle.header?.version, 4, '只读打开没有解析出 v4 头')
    assert.equal(readHandle.header?.id, f.id)
    await readHandle.close?.()
    assert.equal(fs.existsSync(f.v4Path), false, '只读打开就把 v4 代写到磁盘上了（迁移应当惰性发布）')
    assert.deepEqual(fs.readdirSync(f.dir).sort(), [path.basename(f.v3Path)], '只读路径产生了额外的文件')

    // 3) 冷启动：新后端仍以 v4 为当前代。
    const coldBackend = await f.boot()
    const coldListed = await coldBackend.list()
    assert.equal(coldListed.length, 1)
    assert.equal(coldListed[0].header.version, 4, '冷启动后不再把该会话当作 v4')
  })

  test(`会话格式 v3 → v4：取得写权时发布 v4 新代并保留 v3 源文件（compression=${compression}）`, async t => {
    if (!fs.existsSync(virtualStore)) {
      t.skip(notInstalled)
      return
    }
    const runtime = await loadRuntime(t)
    const f = await fixture(t, runtime, compression)

    let handle
    try {
      handle = await f.backend.open(f.id, 'write')
    } catch (error) {
      const reason = lockUnavailableReason(error)
      if (reason === undefined) throw error
      t.skip(`写路径需要跨进程写锁，本平台没有原生绑定：${reason}`)
      return
    }
    assert.equal(handle.header?.version, 4, '写打开没有解析出 v4 头')

    // 发布：磁盘上出现 v4 新代，v3 源文件按字节保留（迁移不改写历史代）。
    assert.equal(fs.existsSync(f.v4Path), true, '写打开没有发布 v4 新代')
    assert.deepEqual(
      fs.readdirSync(f.dir).sort(),
      [path.basename(f.v3Path), path.basename(f.v4Path)].sort(),
      '发布后目录里多出了预期之外的文件',
    )
    const published = fs.readFileSync(f.v4Path)
    const v4Text = (compression === 'zstd' ? zlib.zstdDecompressSync(published) : published).toString('utf8')
    const v4Header = JSON.parse(v4Text.trimEnd().split('\n')[0])
    assert.equal(v4Header.version, 4, 'v4 代的物理头不是 version=4')
    assert.equal(v4Header.id, f.id, 'v4 代换了会话 id')
    assert.equal(v4Header.createdAt, f.createdAt, 'v4 代改了 createdAt')
    await handle.close?.()

    const survived = fs.readFileSync(f.v3Path)
    const v3Text = (compression === 'zstd' ? zlib.zstdDecompressSync(survived) : survived).toString('utf8')
    assert.equal(v3Text, f.body.toString('utf8'), 'v3 源文件被就地改写了')

    // 发布之后再冷启动：v4 已经是当前代，不会再产生别的代文件。
    const coldBackend = await f.boot()
    const coldListed = await coldBackend.list()
    assert.equal(coldListed.length, 1)
    assert.equal(coldListed[0].header.version, 4)
    assert.deepEqual(
      fs.readdirSync(f.dir).sort(),
      [path.basename(f.v3Path), path.basename(f.v4Path)].sort(),
      '冷启动复读时又产生了新的代文件',
    )
  })
}
