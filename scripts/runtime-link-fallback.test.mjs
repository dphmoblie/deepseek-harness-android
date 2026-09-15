// 访客侧 dsh 写入回退的回归防线（登记册 P0-1 / P0-2）。
//
// 背景：Android 16 / PRoot 访客内 `link(2)` 一律被拒（同目录、跨目录都是 EACCES），
// `rename` 正常。上游 `@deepseek-ai/dsh 0.1.5-rc.2` 有两处写入路径依赖硬链接：
//   * `dsh-fs-local` 的 `writeFileAtomic()`：`createIfAbsent` 分支用 `linkFile()` 新建文件；
//   * `dsh-attachment-local` 的 `publishStagedObject()`：用 `link()` 发布内容寻址对象；
//   * `dsh-attachment-local` 的 `publishImmutableAlias()`：用 `link()` 给同一个对象再挂一个只读名字，
//     通用文件附件走的就是它（`saveFileVerbatim` / `saveFileStreamVerbatim`）。这一条**只能** copy 回退：
//     `source` 是内容寻址对象，rename 会把对象库里那条记录本身搬走（测试用「源对象必须还在」钉住这一点）。
// 仓库用既有的 pnpm 补丁通道（`scripts/runtime-profile/pnpm-workspace.yaml` 的
// `patchedDependencies`）给这两处加了 rename 回退。本文件是三件事的防线：
//   1. 补丁在通道里登记正确、且版本键跟着 dsh 版本钉走（升级不改补丁会被立刻发现）；
//   2. 补丁文件本身只改到回退必需的行（最小化），且带 EACCES / EPERM / EXDEV 判定；
//   3. 从 `scripts/runtime-profile/node_modules` 里取出**运行时实际加载的那一份**被修补模块，
//      把它的 `node:fs/promises` 换成 `link` 抛错的桩，驱动两条写入路径断言行为：
//      回退生效、目标内容正确、`createIfAbsent` 语义仍在、其它 errno 不被吞掉。
//
// 夹具边界（为什么不是直接 require 原模块）：
//   * 模块只导出 cordis 插件，`writeFileAtomic` / `publishStagedObject` 都不是公开导出，
//     因此夹具把**整份补丁后的模块源码**复制到临时目录，只改两处导入：
//     `node:fs/promises` 换成本地桩（唯一的目的是接管 `link`），`sharp` 换成空实现
//     （原生模块：工作区的 supportedArchitectures 只装 linux/arm64，本机 Windows 与 CI 的
//     x64 都没有平台二进制；被测的两条路径不经过 sharp）。其余依赖按补丁后模块里的
//     真实解析结果改写为绝对 file: URL，所以夹具跑的是补丁后的真实函数体，不是重写的副本。
//   * 真机/CI rootfs 内是否真的生效无法在本机验证（构建 rootfs 需要 arm64 runner），
//     本文件只覆盖「补丁通道 + 补丁后的代码行为」。

import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { createHash, randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

const runtimeProfile = path.join(import.meta.dirname, 'runtime-profile')
const virtualStore = path.join(runtimeProfile, 'node_modules', '.pnpm')

// 回退分支在补丁后的源码里一定有的唯一标识（上游没有这个名字）。仅用于默认的
// `link-fallback` 类补丁；`static-cache` 类各自在元数据里给 `marker`。
const FALLBACK_MARKER = 'isLinkUnavailableError'

// 每个包的补丁元数据：通道键、补丁文件名、被改的入口函数、必须出现在补丁新增行里的语句。
// `kind` 决定还要做哪些类型专属断言：`link-fallback` 要有三个 errno 判定；`static-cache`
// 不需要（它改的是响应头，与 errno 无关）。`marker` 是「补丁是否落到这一份安装副本上」的判据。
const PATCHED_PACKAGES = [
  {
    name: '@deepseek-ai/dsh-fs-local',
    kind: 'link-fallback',
    marker: 'isLinkUnavailableError',
    key: '@deepseek-ai/dsh-fs-local@0.1.5-rc.2',
    patchFile: '@deepseek-ai__dsh-fs-local@0.1.5-rc.2.patch',
    entry: 'writeFileAtomic',
    addedLines: [
      'function isLinkUnavailableError(error) {',
      'if (!isLinkUnavailableError(error)) await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);',
      'if (existing !== void 0) await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);',
      'await rename(tempPath, absolutePath);',
    ],
  },
  {
    name: '@deepseek-ai/dsh-attachment-local',
    kind: 'link-fallback',
    marker: 'isLinkUnavailableError',
    key: '@deepseek-ai/dsh-attachment-local@0.1.5-rc.2',
    patchFile: '@deepseek-ai__dsh-attachment-local@0.1.5-rc.2.patch',
    entry: 'publishStagedObject',
    // 同一个包里有两条被补的发布路径：对象发布（rename 回退）与别名发布（copy 回退）。
    entries: ['publishStagedObject', 'publishImmutableAlias'],
    addedLines: [
      'function isLinkUnavailableError(error) {',
      'async function renameStagedObject(staged, target) {',
      'async function copyImmutableAlias(source, target) {',
      'if (isLinkUnavailableError(error)) {',
      'await renameStagedObject(staged, target);',
      'await copyImmutableAlias(source, target);',
      'await removeTemporary(staged.path);',
    ],
  },
  {
    // 登记册 5.6-H：上游对控制台静态资源不发任何缓存头，宿主侧改 WebView 缓存策略的收益
    // 因此接近 0。本补丁按「产物是否内容寻址」分岔补上 cache-control。
    name: '@deepseek-ai/dsh-host-frontend-static',
    kind: 'static-cache',
    marker: 'cacheControlFor',
    key: '@deepseek-ai/dsh-host-frontend-static@0.1.5-rc.2',
    patchFile: '@deepseek-ai__dsh-host-frontend-static@0.1.5-rc.2.patch',
    entry: 'cacheControlFor',
    entries: ['cacheControlFor', 'serveStatic'],
    addedLines: [
      'function cacheControlFor(target, distRoot, distIndex) {',
      'if (target === distRoot || target === distIndex) return "no-cache";',
      'return "public, max-age=31536000, immutable";',
    ],
  },
]

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

function tempDir(t, prefix) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  t.after(() => fs.rmSync(dir, { recursive: true, force: true, maxRetries: 5 }))
  return dir
}

function patchedPackage(name) {
  const found = PATCHED_PACKAGES.find(entry => entry.name === name)
  assert.ok(found, `测试元数据里没有 ${name}`)
  return found
}

// 运行时实际加载的那一份：dsh-base 的真实目录旁边就是它的依赖（pnpm 的 peer 根）。
// 这条路径与 Node 解析 `import "@deepseek-ai/dsh-fs-local"` 时走的目录完全一致，
// 因此它同时验证了「打完补丁的那一份就在运行时会加载的位置上」。
function liveInstalledSource(name) {
  const dshBase = path.join(runtimeProfile, 'node_modules', '@deepseek-ai', 'dsh-base')
  if (!fs.existsSync(dshBase)) return undefined
  const candidate = path.join(path.dirname(path.dirname(fs.realpathSync(dshBase))), name, 'lib', 'index.js')
  return fs.existsSync(candidate) ? fs.realpathSync(candidate) : undefined
}

/**
 * 取「打了这个补丁的那一份」安装副本。
 *
 * 优先走 peer 根（与 Node 的实际解析一致，且能证明补丁落在运行时会加载的位置上）。
 * 但**不是每个被补的包都是 `dsh-base` 的依赖**（`dsh-host-frontend-static` 就不是），
 * 这时 peer 根里没有它；而且打过补丁与没打过补丁的两份会同时留在 `.pnpm` 下
 * （目录名只差一个哈希后缀），所以扫描时要**按内容里的 `marker` 认**，
 * 不能按目录名或时间挑——挑错了会让「补丁没生效」这类问题被静默放过。
 */
function installedSourceFor(name, marker) {
  const direct = liveInstalledSource(name)
  if (direct !== undefined && fs.readFileSync(direct, 'utf8').includes(marker)) return direct
  const store = path.join(runtimeProfile, 'node_modules', '.pnpm')
  if (!fs.existsSync(store)) return direct
  const suffix = path.join('node_modules', name, 'lib', 'index.js')
  for (const entry of fs.readdirSync(store)) {
    const candidate = path.join(store, entry, suffix)
    if (!fs.existsSync(candidate)) continue
    if (fs.readFileSync(candidate, 'utf8').includes(marker)) return fs.realpathSync(candidate)
  }
  return direct
}

// 夹具：把补丁后的整份模块源码复制到临时目录，只替换 `link` 的来源与 sharp。
const FS_PROMISES_STUB = [
  'import { link as realLink } from "node:fs/promises";',
  'export * from "node:fs/promises";',
  '// 唯一的控制面：夹具通过同一个模块实例改 mode，被测代码里的 link 走的就是它。',
  'export const linkControl = { mode: "real", calls: 0 };',
  'export async function link(from, to) {',
  '	linkControl.calls += 1;',
  '	if (linkControl.mode === "real") return realLink(from, to);',
  '	throw Object.assign(new Error("link 桩拒绝：" + linkControl.mode), { code: linkControl.mode.toUpperCase(), syscall: "link", path: from, dest: to });',
  '}',
  '',
].join('\n')

const SHARP_STUB = '// sharp 是原生模块；被测的发布路径不经过它，夹具只要求它能被导入。\nexport default function sharp() {\n	throw new Error("sharp 桩不应被调用");\n}\n'

async function loadPatchedModule(t, patchedPackage) {
  const { name, entry, entries = [entry], marker = FALLBACK_MARKER } = patchedPackage
  const sourceFile = installedSourceFor(name, marker)
  assert.ok(
    sourceFile,
    `未能从 scripts/runtime-profile/node_modules 解析 ${name}：请先运行 pnpm --dir scripts/runtime-profile install`,
  )
  const source = fs.readFileSync(sourceFile, 'utf8')
  assert.ok(
    source.includes(marker),
    `${name} 的安装副本没有找到补丁标记 ${marker}：补丁没有落到运行时会加载的那一份上`,
  )

  const require = createRequire(sourceFile)
  const dir = tempDir(t, 'dsh-link-fallback-')
  const rewritten = source.replace(/from "([^"]+)"/gu, (whole, specifier) => {
    if (specifier === 'node:fs/promises') return 'from "./fs-promises-stub.mjs"'
    if (specifier === 'sharp') return 'from "./sharp-stub.mjs"'
    if (specifier.startsWith('node:') || specifier.startsWith('.') || specifier.startsWith('file:')) return whole
    return `from ${JSON.stringify(pathToFileURL(require.resolve(specifier)).href)}`
  })
  assert.notEqual(rewritten, source, `${name} 的 node:fs/promises 导入未找到，夹具无法接管 link`)
  assert.ok(rewritten.includes('from "./fs-promises-stub.mjs"'), `${name} 的 link 未被桩接管`)

  fs.writeFileSync(path.join(dir, 'fs-promises-stub.mjs'), FS_PROMISES_STUB)
  fs.writeFileSync(path.join(dir, 'sharp-stub.mjs'), SHARP_STUB)
  const fixtureFile = path.join(dir, 'patched-module.mjs')
  // 上游有的包**本来就导出了**我们要驱动的函数（`dsh-host-frontend-static` 就导出 `serveStatic`），
  // 无条件追加 `export { ... }` 会撞成 `Duplicate export` 语法错误。因此先解析已有的导出语句，
  // 只补真正缺的那些。
  const alreadyExported = new Set(
    [...rewritten.matchAll(/export\s*\{([^}]*)\}/gu)]
      .flatMap(match => match[1].split(',').map(part => part.trim().split(/\s+as\s+/u).pop()))
      .filter(Boolean),
  )
  const pending = entries.filter(exported => !alreadyExported.has(exported))
  fs.writeFileSync(fixtureFile, `${rewritten}${pending.length === 0 ? '' : `\nexport { ${pending.join(', ')} };\n`}`)

  const patched = await import(pathToFileURL(fixtureFile).href)
  for (const exported of entries) {
    assert.equal(typeof patched[exported], 'function', `${name} 的 ${exported} 不是函数`)
  }
  const stub = await import(pathToFileURL(path.join(dir, 'fs-promises-stub.mjs')).href)
  return { patched, linkControl: stub.linkControl, sourceFile }
}

test('补丁通道登记了 3 个运行时补丁（2 个写入回退 + 1 个静态缓存头），且键跟着 dsh 版本钉走', () => {
  const workspace = fs.readFileSync(path.join(runtimeProfile, 'pnpm-workspace.yaml'), 'utf8')
  const lockfile = fs.readFileSync(path.join(runtimeProfile, 'pnpm-lock.yaml'), 'utf8')
  const runtimePackage = JSON.parse(fs.readFileSync(path.join(runtimeProfile, 'package.json'), 'utf8'))
  const pinnedDshVersion = runtimePackage.dependencies['@deepseek-ai/dsh']
  const overrideVersion = workspace.match(/^\s+'@deepseek-ai\/dsh-\*':\s*(\S+)\s*$/mu)?.[1]
  assert.equal(overrideVersion, pinnedDshVersion, 'pnpm-workspace.yaml 的 dsh overrides 与 package.json 版本钉不一致')

  for (const { name, kind, key, patchFile, addedLines } of PATCHED_PACKAGES) {
    assert.match(
      workspace,
      new RegExp(`^\\s+'${escapeRegExp(key)}':\\s*patches/${escapeRegExp(patchFile)}\\s*$`, 'mu'),
      `pnpm-workspace.yaml 的 patchedDependencies 里没有 ${key}`,
    )
    // 版本键必须等于当前钉住的 dsh 版本：升 dsh 却忘记重做补丁时，这个断言先炸，
    // 而不是等到 CI 上 pnpm install 报「补丁未被使用」。
    assert.equal(key, `${name}@${pinnedDshVersion}`, `补丁键 ${key} 与钉住的 dsh 版本 ${pinnedDshVersion} 不一致`)

    const patchPath = path.join(runtimeProfile, 'patches', patchFile)
    const patchBytes = fs.readFileSync(patchPath)
    const patch = patchBytes.toString('utf8')

    // 最小化：只改 lib/index.js 一个文件，不多带任何别的改动。
    assert.equal(patch.match(/^diff --git /gmu)?.length, 1, `${patchFile} 改动了多个文件，不再是最小补丁`)
    assert.match(patch, /^--- a\/lib\/index\.js$/mu, `${patchFile} 的目标不是 lib/index.js`)
    assert.match(patch, /^\+\+\+ b\/lib\/index\.js$/mu, `${patchFile} 的目标不是 lib/index.js`)

    // 补丁类型专属断言：`link-fallback` 必须判到 EACCES / EPERM / EXDEV 三个 errno，
    // 并新增 link 不可用判定函数；其它类型（如 static-cache）改的是响应头，与 errno 无关，
    // 因此这些断言按 kind 分流——否则「加一个非硬链接补丁」会被硬编码的 errno 断言挡住。
    const added = patch
      .split('\n')
      .filter(line => line.startsWith('+') && !line.startsWith('+++'))
      .map(line => line.slice(1).trim())
    if (kind === 'link-fallback') {
      for (const errno of ['"EACCES"', '"EPERM"', '"EXDEV"']) {
        assert.ok(
          added.some(line => line.includes(errno)),
          `${patchFile} 的新增行里缺少 ${errno} 判定`,
        )
      }
      assert.ok(added.includes('function isLinkUnavailableError(error) {'), `${patchFile} 没有新增 link 不可用判定函数`)
    }
    for (const expected of addedLines) {
      assert.ok(added.includes(expected), `${patchFile} 缺少必需的补丁行：${expected}`)
    }

    // lockfile 是实际解析结果：键对应的哈希必须等于补丁文件字节的 sha256，
    // 且快照键里必须带同一个 patch_hash。补丁改了却没重新 install 时，这里先失败，
    // 而不是等到 CI 的 --frozen-lockfile 报 OUTDATED_LOCKFILE。
    const digest = createHash('sha256').update(patchBytes).digest('hex')
    const lockEntry = lockfile.match(new RegExp(`^\\s+'${escapeRegExp(key)}':\\s*([0-9a-f]{64})$`, 'mu'))
    assert.ok(lockEntry, `pnpm-lock.yaml 的 patchedDependencies 里没有 ${key}`)
    assert.equal(lockEntry[1], digest, `pnpm-lock.yaml 里 ${key} 的补丁哈希与补丁文件内容不一致，需要重新 install`)
    assert.ok(lockfile.includes(`patch_hash=${digest}`), `pnpm-lock.yaml 里没有 patch_hash=${digest} 的已打补丁快照`)
  }
})

test('dsh-fs-local：link 被拒时回退 rename 新建文件，且 createIfAbsent 语义与其它 errno 不变', async t => {
  if (!fs.existsSync(virtualStore)) {
    t.skip('未安装 scripts/runtime-profile 依赖（CI 会先 pnpm install --frozen-lockfile，届时必然执行）')
    return
  }
  const { patched, linkControl } = await loadPatchedModule(t, patchedPackage('@deepseek-ai/dsh-fs-local'))
  const work = tempDir(t, 'dsh-fs-local-')

  // 1) link 被拒 + 目标不存在 + createIfAbsent：回退 rename，文件真的被新建出来。
  const created = path.join(work, 'new.txt')
  linkControl.mode = 'eacces'
  linkControl.calls = 0
  await patched.writeFileAtomic(created, '访客内容\n', undefined, undefined, { platform: 'linux' }, { displayPath: 'new.txt' })
  assert.equal(linkControl.calls, 1, '应该先尝试一次 link 再回退')
  assert.equal(fs.readFileSync(created, 'utf8'), '访客内容\n', '回退后的目标内容不正确')
  assert.deepEqual(fs.readdirSync(work), ['new.txt'], '回退后暂存目录没有被清理')

  // 2) EPERM / EXDEV 与 EACCES 同等处理（三者对上层是同一个结论：硬链接不可用）。
  for (const errno of ['eperm', 'exdev']) {
    const target = path.join(work, `${errno}.txt`)
    linkControl.mode = errno
    await patched.writeFileAtomic(target, errno, undefined, undefined, { platform: 'linux' }, { displayPath: `${errno}.txt` })
    assert.equal(fs.readFileSync(target, 'utf8'), errno, `${errno} 没有被当成硬链接不可用`)
  }

  // 3) 回退路径上 createIfAbsent 的语义必须还在：目标已存在时**不能**被 rename 覆盖掉。
  const occupied = path.join(work, 'occupied.txt')
  fs.writeFileSync(occupied, '既有内容')
  linkControl.mode = 'eacces'
  await assert.rejects(
    patched.writeFileAtomic(occupied, '新内容', undefined, undefined, { platform: 'linux' }, { displayPath: 'occupied.txt' }),
    error => {
      assert.equal(error.code, 'FS_NOT_OBSERVED', `目标已存在时应报 FS_NOT_OBSERVED，实际 ${error.code}`)
      return true
    },
  )
  assert.equal(fs.readFileSync(occupied, 'utf8'), '既有内容', '回退路径覆盖了本不该覆盖的既有文件')

  // 4) 目标已存在且不是普通文件：沿用原有分类。
  const notAFile = path.join(work, 'directory.txt')
  fs.mkdirSync(notAFile)
  linkControl.mode = 'eacces'
  await assert.rejects(
    patched.writeFileAtomic(notAFile, '新内容', undefined, undefined, { platform: 'linux' }, { displayPath: 'directory.txt' }),
    error => {
      assert.equal(error.code, 'FS_NOT_REGULAR_FILE', `目标不是普通文件时应报 FS_NOT_REGULAR_FILE，实际 ${error.code}`)
      return true
    },
  )

  // 5) 其它 errno 不被这次回退吞掉：ENOENT 必须照样失败，而不是「悄悄写成功」。
  const missing = path.join(work, 'enoent.txt')
  linkControl.mode = 'enoent'
  await assert.rejects(
    patched.writeFileAtomic(missing, 'x', undefined, undefined, { platform: 'linux' }, { displayPath: 'enoent.txt' }),
    error => {
      assert.equal(error.code, 'FS_IO_ERROR', `ENOENT 应保持原有失败路径，实际 ${error.code}`)
      assert.equal(error.cause?.code, 'ENOENT', 'ENOENT 原因被吞掉了')
      return true
    },
  )
  assert.equal(fs.existsSync(missing), false, 'ENOENT 被回退吞成了成功写入')
  assert.ok(!fs.readdirSync(work).some(entry => entry.includes('.tmpdir')), 'ENOENT 失败后暂存目录没有被清理')

  // 6) EEXIST（目标真的存在）仍是原有语义，没有被回退分支改写。
  linkControl.mode = 'eexist'
  await assert.rejects(
    patched.writeFileAtomic(occupied, '新内容', undefined, undefined, { platform: 'linux' }, { displayPath: 'occupied.txt' }),
    error => {
      assert.equal(error.code, 'FS_NOT_OBSERVED', `EEXIST 应保持原有语义，实际 ${error.code}`)
      return true
    },
  )
  assert.equal(fs.readFileSync(occupied, 'utf8'), '既有内容')

  // 7) 覆盖分支（没有 createIfAbsent）不受影响：它本来就走 rename，不会碰 link。
  linkControl.mode = 'eacces'
  linkControl.calls = 0
  const overwritten = path.join(work, 'overwrite.txt')
  fs.writeFileSync(overwritten, '旧内容')
  await patched.writeFileAtomic(overwritten, '覆盖内容', undefined, undefined, { platform: 'linux' })
  assert.equal(linkControl.calls, 0, '覆盖分支不应该调用 link')
  assert.equal(fs.readFileSync(overwritten, 'utf8'), '覆盖内容')

  // 8) link 正常时仍走原来的硬链接路径（回退没有把原路径换掉）。
  linkControl.mode = 'real'
  linkControl.calls = 0
  const linked = path.join(work, 'linked.txt')
  await patched.writeFileAtomic(linked, '硬链接', undefined, undefined, { platform: 'linux' }, { displayPath: 'linked.txt' })
  assert.equal(linkControl.calls, 1)
  assert.equal(fs.readFileSync(linked, 'utf8'), '硬链接')
})

test('dsh-attachment-local：link 被拒时回退 rename 发布对象，且不吞掉其它 errno', async t => {
  if (!fs.existsSync(virtualStore)) {
    t.skip('未安装 scripts/runtime-profile 依赖（CI 会先 pnpm install --frozen-lockfile，届时必然执行）')
    return
  }
  const { patched, linkControl } = await loadPatchedModule(t, patchedPackage('@deepseek-ai/dsh-attachment-local'))
  const work = tempDir(t, 'dsh-attachment-local-')
  const root = path.join(work, 'v1')
  fs.mkdirSync(path.join(root, 'tmp'), { recursive: true })

  const stage = bytes => {
    const stagedPath = path.join(root, 'tmp', randomUUID())
    fs.writeFileSync(stagedPath, bytes)
    return {
      path: stagedPath,
      boundary: root,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      bytes: bytes.length,
    }
  }
  const targetFor = bytes => {
    const digest = createHash('sha256').update(bytes).digest('hex')
    return path.join(root, 'objects', digest.slice(0, 2), digest)
  }
  const stagedLeftovers = () => fs.readdirSync(path.join(root, 'tmp'))

  // 1) link 被拒 + 目标不存在：回退 rename 把对象发布出去。
  const bytes = Buffer.from('PNG 一样的字节内容')
  const target = targetFor(bytes)
  linkControl.mode = 'eacces'
  linkControl.calls = 0
  await patched.publishStagedObject(root, target, stage(bytes))
  assert.equal(linkControl.calls, 1, '应该先尝试一次 link 再回退')
  assert.ok(fs.readFileSync(target).equals(bytes), '回退发布后的对象内容不正确')
  assert.deepEqual(stagedLeftovers(), [], '回退发布后暂存名没有被清理')
  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(target).mode & 0o777, 0o400, '发布后的对象权限不是 0400')
  }

  // 2) 目标已存在且字节相同：按内容寻址去重，不覆盖、不报错。
  linkControl.mode = 'eacces'
  await patched.publishStagedObject(root, target, stage(bytes))
  assert.ok(fs.readFileSync(target).equals(bytes), '去重时对象被改写')
  assert.deepEqual(stagedLeftovers(), [], '去重后暂存名没有被清理')

  // 3) 目标已存在但字节不同：必须报完整性错误，而不是被 rename 覆盖掉。
  const corrupt = stage(Buffer.from('另一份字节'))
  linkControl.mode = 'eacces'
  await assert.rejects(
    patched.publishStagedObject(root, target, corrupt),
    error => {
      assert.equal(error.code, 'ATTACHMENT_CORRUPT', `字节不一致时应报 ATTACHMENT_CORRUPT，实际 ${error.code}`)
      return true
    },
  )
  assert.ok(fs.readFileSync(target).equals(bytes), '回退路径覆盖了内容不一致的既有对象')

  // 4) 其它 errno 不被这次回退吞掉：ENOENT 仍然以 ATTACHMENT_WRITE_FAILED 失败。
  const missingBytes = Buffer.from('第四份')
  const missingTarget = targetFor(missingBytes)
  const missingStage = stage(missingBytes)
  linkControl.mode = 'enoent'
  await assert.rejects(
    patched.publishStagedObject(root, missingTarget, missingStage),
    error => {
      assert.equal(error.code, 'ATTACHMENT_WRITE_FAILED', `ENOENT 应保持原有失败路径，实际 ${error.code}`)
      assert.equal(error.cause?.code, 'ENOENT', 'ENOENT 原因被吞掉了')
      return true
    },
  )
  assert.equal(fs.existsSync(missingTarget), false, 'ENOENT 被回退吞成了成功发布')
  assert.deepEqual(stagedLeftovers(), [], 'ENOENT 失败后暂存名没有被清理')

  // 5) EEXIST（目标真的存在）仍是原有去重语义。
  linkControl.mode = 'eexist'
  await patched.publishStagedObject(root, target, stage(bytes))
  assert.ok(fs.readFileSync(target).equals(bytes))
  assert.deepEqual(stagedLeftovers(), [], 'EEXIST 去重后暂存名没有被清理')

  // 6) link 正常时仍走原来的硬链接发布：暂存名与目标名同时存在（同一 inode 的两个名字）。
  linkControl.mode = 'real'
  const realBytes = Buffer.from('第六份')
  const realStage = stage(realBytes)
  await patched.publishStagedObject(root, targetFor(realBytes), realStage)
  assert.ok(fs.readFileSync(targetFor(realBytes)).equals(realBytes))
  assert.deepEqual(stagedLeftovers(), [], '硬链接发布后暂存名没有被清理')
})

// 通用文件附件（`saveFileVerbatim` / `saveFileStreamVerbatim`）除了发布对象，还要用
// `publishImmutableAlias()` 为同一对象再挂一个只读名字。这条路径的取舍与上一条不同：
// 别名发布**不能**用 rename 回退（源是内容寻址对象，rename 会把对象库里的记录搬走），
// 只能复制一份字节，再用写后摘要复核保证不可变性。
test('dsh-attachment-local：link 被拒时复制发布别名，且不把源对象从对象库搬走', async t => {
  if (!fs.existsSync(virtualStore)) {
    t.skip('未安装 scripts/runtime-profile 依赖（CI 会先 pnpm install --frozen-lockfile，届时必然执行）')
    return
  }
  const { patched, linkControl } = await loadPatchedModule(t, patchedPackage('@deepseek-ai/dsh-attachment-local'))
  const work = tempDir(t, 'dsh-attachment-alias-')
  // `publishImmutableAlias` 内部会 `ensureDurableHome(dirname(dirname(resolve(root))))`，
  // 也就是把 **root 的上上层**当成 DSH_HOME 并 mkdir + chmod。夹具必须让那一层落在自己的
  // 临时目录里：若直接把 root 放在 mkdtemp 下，上上层就是 `os.tmpdir()`——Windows 本机是
  // 用户自己的 Temp（chmod 近乎空操作，测试假绿），CI 的 Linux 上是 `/tmp`（runner 不是属主，
  // chmod 直接 EPERM）。因此这里显式造出 home/attachments/v1 三层，并断言上上层确实是 home。
  const home = path.join(work, 'home')
  const root = path.join(home, 'attachments', 'v1')
  fs.mkdirSync(root, { recursive: true })
  assert.equal(
    path.dirname(path.dirname(root)),
    home,
    '夹具的 root 层级不对：上上层必须是夹具自己的 home，不能落到 os.tmpdir()',
  )
  const digestOf = bytes => createHash('sha256').update(bytes).digest('hex')
  const objectFor = bytes => {
    const digest = digestOf(bytes)
    return path.join(root, 'file-objects', digest.slice(0, 2), digest)
  }
  const aliasFor = (bytes, name) => {
    const digest = digestOf(bytes)
    return path.join(root, 'storefiles', digest.slice(0, 2), digest, name)
  }
  // 内容寻址对象：别名发布的 `source` 就是它（上游由 publishImmutableObject 写入）。
  const putObject = bytes => {
    const objectPath = objectFor(bytes)
    fs.mkdirSync(path.dirname(objectPath), { recursive: true })
    fs.writeFileSync(objectPath, bytes)
    return objectPath
  }

  // 1) link 被拒 + 别名不存在：复制发布；**源对象必须还在**（rename 回退会在这里露馅）。
  const bytes = Buffer.from('文件型附件的字节内容')
  const source = putObject(bytes)
  const alias = aliasFor(bytes, '报告.pdf')
  linkControl.mode = 'eacces'
  linkControl.calls = 0
  await patched.publishImmutableAlias(root, source, alias, digestOf(bytes))
  assert.equal(linkControl.calls, 1, '应该先尝试一次 link 再回退')
  assert.ok(fs.readFileSync(alias).equals(bytes), '复制发布后的别名内容不正确')
  assert.ok(fs.existsSync(source), '源对象被搬走了：对象库里那条记录必须留着（rename 回退会犯这个错）')
  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(alias).mode & 0o777, 0o400, '别名的权限不是 0400')
    assert.equal(fs.statSync(source).nlink, 1, '复制得到的是独立 inode；硬链接路径下这里才会是 2')
  }

  // 2) 别名已存在且字节相同：不覆盖、不报错（内容寻址去重语义不变）。
  linkControl.mode = 'eacces'
  await patched.publishImmutableAlias(root, source, alias, digestOf(bytes))
  assert.ok(fs.readFileSync(alias).equals(bytes), '去重时别名被改写')
  assert.ok(fs.existsSync(source), '去重时源对象不见了')

  // 3) 别名已存在但字节不同：报 ATTACHMENT_CORRUPT，且**不覆盖**既有别名。
  const other = Buffer.from('另一份字节')
  fs.chmodSync(alias, 0o600)
  fs.writeFileSync(alias, other)
  linkControl.mode = 'eacces'
  await assert.rejects(
    patched.publishImmutableAlias(root, source, alias, digestOf(bytes)),
    error => {
      assert.equal(error.code, 'ATTACHMENT_CORRUPT', `字节不一致时应报 ATTACHMENT_CORRUPT，实际 ${error.code}`)
      return true
    },
  )
  assert.ok(fs.readFileSync(alias).equals(other), '回退路径覆盖了内容不一致的既有别名')
  assert.ok(fs.existsSync(source), '完整性复核失败时源对象被改动')

  // 4) 其它 errno 不被这次回退吞掉：ENOENT 仍然以 ATTACHMENT_WRITE_FAILED 失败。
  const missingAlias = aliasFor(bytes, '不存在.pdf')
  linkControl.mode = 'enoent'
  await assert.rejects(
    patched.publishImmutableAlias(root, source, missingAlias, digestOf(bytes)),
    error => {
      assert.equal(error.code, 'ATTACHMENT_WRITE_FAILED', `ENOENT 应保持原有失败路径，实际 ${error.code}`)
      assert.equal(error.cause?.code, 'ENOENT', 'ENOENT 原因被吞掉了')
      return true
    },
  )
  assert.equal(fs.existsSync(missingAlias), false, 'ENOENT 被回退吞成了成功发布')

  // 5) link 正常时仍走硬链接：别名与源对象是同一个 inode（回退没有把原路径换掉）。
  linkControl.mode = 'real'
  const hardBytes = Buffer.from('硬链接别名')
  const hardSource = putObject(hardBytes)
  const hardAlias = aliasFor(hardBytes, '正常.txt')
  await patched.publishImmutableAlias(root, hardSource, hardAlias, digestOf(hardBytes))
  assert.ok(fs.readFileSync(hardAlias).equals(hardBytes))
  if (process.platform !== 'win32') {
    assert.equal(fs.statSync(hardSource).nlink, 2, 'link 可用时应仍是硬链接（同一 inode 的两个名字）')
  }
})

// 登记册 5.6-H：上游对控制台静态资源**不发任何缓存头**，于是宿主侧无论把 WebView 的
// `cacheMode` 设成什么，每次打开控制台都要把整份前端重新下载并重新解析。
// 本补丁按「产物是否内容寻址」分岔补上 `cache-control`：入口与未哈希资源 `no-cache`
// （允许缓存但每次回源校验），`assets/` 下的内容哈希产物 `immutable` 长缓存。
test('dsh-host-frontend-static：按路径给出 cache-control，且响应真的带上它', async t => {
  if (!fs.existsSync(virtualStore)) {
    t.skip('未安装 scripts/runtime-profile 依赖（CI 会先 pnpm install --frozen-lockfile，届时必然执行）')
    return
  }
  const { patched } = await loadPatchedModule(t, patchedPackage('@deepseek-ai/dsh-host-frontend-static'))
  const work = tempDir(t, 'dsh-static-cache-')
  const distRoot = path.join(work, 'dist')
  fs.mkdirSync(path.join(distRoot, 'assets'), { recursive: true })
  const distIndex = path.join(distRoot, 'index.html')
  fs.writeFileSync(distIndex, '<html>index</html>')
  fs.writeFileSync(path.join(distRoot, 'assets', 'index-Cr2OHXyD.js'), 'console.log(1)')
  fs.writeFileSync(path.join(distRoot, 'favicon.ico'), 'icon')

  // 1) 分类函数本身：入口（dist 根与 index 路径）必须 no-cache。
  assert.equal(patched.cacheControlFor(distRoot, distRoot, distIndex), 'no-cache')
  assert.equal(patched.cacheControlFor(distIndex, distRoot, distIndex), 'no-cache')
  // 2) 内容寻址的 assets/ 才允许 immutable。
  assert.equal(
    patched.cacheControlFor(path.join(distRoot, 'assets', 'index-Cr2OHXyD.js'), distRoot, distIndex),
    'public, max-age=31536000, immutable',
  )
  // 3) 其余路径（favicon 等）不敢假定带哈希 ⇒ 一律 no-cache。
  assert.equal(patched.cacheControlFor(path.join(distRoot, 'favicon.ico'), distRoot, distIndex), 'no-cache')
  // 4) 名字里带 assets 但**不是** assets 目录的兄弟路径不能被误判成长缓存——
  //    这里用前缀比对的实现在这一条上会翻车（`assets-extra` 以 `assets` 开头）。
  assert.equal(
    patched.cacheControlFor(path.join(distRoot, 'assets-extra', 'x.js'), distRoot, distIndex),
    'no-cache',
  )

  // 5) 真正驱动 serveStatic：响应头必须落到 200 响应上，而不只是分类函数返回得对。
  const serve = async (pathname) => {
    const seen = { status: 0, headers: undefined, body: undefined }
    const res = {
      writeHead(status, headers) { seen.status = status; seen.headers = headers },
      end(body) { seen.body = body },
    }
    await patched.serveStatic(pathname, res, distRoot, distIndex, () => true, async () => '<html>index</html>')
    return seen
  }
  const indexResponse = await serve('/')
  assert.equal(indexResponse.status, 200)
  assert.equal(indexResponse.headers['cache-control'], 'no-cache', '入口页必须每次回源校验')

  const assetResponse = await serve('/assets/index-Cr2OHXyD.js')
  assert.equal(assetResponse.status, 200)
  assert.equal(
    assetResponse.headers['cache-control'],
    'public, max-age=31536000, immutable',
    '内容哈希产物应当长缓存，否则这次补丁的核心收益没有生效',
  )
  assert.equal(assetResponse.headers['content-type'], 'text/javascript; charset=utf-8')

  const iconResponse = await serve('/favicon.ico')
  assert.equal(iconResponse.status, 200)
  assert.equal(iconResponse.headers['cache-control'], 'no-cache')

  // 6) 路径穿越仍必须是 403（上游行为，补丁不该动它）。
  const traversal = await serve('/../../etc/passwd')
  assert.equal(traversal.status, 403)
  // 7) 不存在的资源仍是 404。
  const missing = await serve('/nope.js')
  assert.equal(missing.status, 404)
})
