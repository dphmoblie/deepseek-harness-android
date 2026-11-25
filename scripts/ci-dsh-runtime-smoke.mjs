#!/usr/bin/env node
/**
 * CI 运行时真实加载与启动冒烟校验脚本（双模式，均按 fail-closed 执行）：
 *
 * 1. `--mode cordis-plugins --dsh-root <dir>`
 *    在真实 `@deepseek-ai/cordis` 容器中加载 `@deepseek-harness/dsh-mobile-shizuku`
 *    与 `dshmarket`，验证：
 *    - 只提供顶层 `inject` 声明的服务时插件能无错激活（捕获 Issue #31 的
 *      `cannot get property "workingDirectory" without inject` 这类真实 Proxy 崩溃）；
 *    - 核心移动端工具已注册；
 *    - 延迟注册 `workingDirectory` / `terminals` 时 `pty-guard` 能动态挂载并清理。
 *
 * 2. `--mode dsh-boot --dsh-root <dir> --mobile-profile <json> --auth-preload <cjs>`
 *    按与 Android rootfs 一致的 `$DSH_HOME/profiles/web` + 扁平 `profiles/node_modules`
 *    布局，真实启动 `dsh --profile web --patch <sandbox-overlay> --host 127.0.0.1 --port <port> --no-open`
 *    并预载 `mobile-auth-preload.cjs`，验证：
 *    - 输出中零插件激活失败 / 零 `without inject` / 零 `patch: entry ... not found` / 零模块缺失；
 *    - `dsh web: http://127.0.0.1:<port>/?token=...` 成功打印且进程稳定存活；
 *    - 无凭据请求返回 401（`Basic realm="DeepSeek Harness Mobile", charset="UTF-8"`），带凭据请求返回 200；
 *    - 若传 `--verify-pty-native`，同步验证 `node-pty` 原生模块在当前架构可直接加载。
 */

import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import { existsSync } from 'node:fs'
import { lstat, mkdir, mkdtemp, readdir, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { createRequire } from 'node:module'
import net from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const DSH_LAUNCH_URL_PATTERN = /^dsh web: (http:\/\/127\.0\.0\.1:(\d+)\/\?token=([A-Za-z0-9_-]{43}))(?: \(LAN: [^)\r\n]*\))?$/mu
export const EXPECTED_AUTH_CHALLENGE = 'Basic realm="DeepSeek Harness Mobile", charset="UTF-8"'
const EXPECTED_MOBILE_TOOLS = [
  'mobile_device_capabilities',
  'mobile_device_shell',
  'mobile_virtual_screen_start',
]

export const BOOT_FORBIDDEN_OUTPUT_PATTERNS = [
  { id: 'plugin-not-activated', pattern: /\bentr(?:y|ies) did not activate\b/iu },
  { id: 'bundle-skipped', pattern: /skipping profile bundle/iu },
  { id: 'cordis-missing-inject', pattern: /without inject/iu },
  { id: 'patch-entry-missing', pattern: /patch:\s*entry\b[^\n]*\bnot found/iu },
  { id: 'module-not-found', pattern: /\b(?:ERR_MODULE_NOT_FOUND|Cannot find (?:module|package))\b/iu },
  { id: 'native-dlopen-failed', pattern: /\bERR_DLOPEN_FAILED\b/iu },
  { id: 'cli-option-error', pattern: /(?:unknown option|error:\s*--profile\s+<name>\s+is required)/iu },
  { id: 'unhandled-error', pattern: /\b(?:UnhandledPromiseRejection|uncaughtException)\b/iu },
]

export function detectBootOutputFailures(output) {
  const text = String(output ?? '')
  const hits = []
  for (const { id, pattern } of BOOT_FORBIDDEN_OUTPUT_PATTERNS) {
    if (pattern.test(text)) hits.push(id)
  }
  return hits
}

export function buildSandboxOverlayPatch() {
  return [
    {
      id: 'sandbox',
      config: {
        runnerCommand: ['/bin/sh', '/root/.dsh-mobile/sandbox-runner.sh'],
        runnerFailureSignatures: ['dsh-sandbox-runner: '],
      },
    },
  ]
}

function parseArgs(argv) {
  const args = {
    mode: '',
    dshRoot: '',
    mobileProfile: '',
    authPreload: '',
    timeoutMs: 30_000,
    verifyPtyNative: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i]
    switch (token) {
      case '--mode':
        args.mode = argv[++i] ?? ''
        break
      case '--dsh-root':
        args.dshRoot = argv[++i] ?? ''
        break
      case '--mobile-profile':
        args.mobileProfile = argv[++i] ?? ''
        break
      case '--auth-preload':
        args.authPreload = argv[++i] ?? ''
        break
      case '--timeout-ms':
        args.timeoutMs = Number(argv[++i])
        break
      case '--verify-pty-native':
        args.verifyPtyNative = true
        break
      default:
        throw new Error(`未知参数：${token}`)
    }
  }
  return args
}

async function resolvePackageDir(fromDir, name) {
  let cursor = resolve(fromDir)
  while (true) {
    const candidate = cursor.endsWith('node_modules')
      ? join(cursor, name)
      : join(cursor, 'node_modules', name)
    if (existsSync(candidate)) {
      return await realpath(candidate)
    }
    const parent = dirname(cursor)
    if (parent === cursor) return null
    cursor = parent
  }
}

async function resolveCordisEntry(dshRoot) {
  const direct = await resolvePackageDir(dshRoot, '@deepseek-ai/cordis')
  if (direct) return join(direct, 'lib/index.js')
  const dshDir = await resolvePackageDir(dshRoot, '@deepseek-ai/dsh')
  if (dshDir) {
    const nested = await resolvePackageDir(dshDir, '@deepseek-ai/cordis')
    if (nested) return join(nested, 'lib/index.js')
  }
  const baseDir = await resolvePackageDir(dshRoot, '@deepseek-ai/dsh-base')
  if (baseDir) {
    const nested = await resolvePackageDir(baseDir, '@deepseek-ai/cordis')
    if (nested) return join(nested, 'lib/index.js')
  }
  throw new Error(`无法在 ${dshRoot} 中定位 @deepseek-ai/cordis`)
}

async function resolvePluginEntry(dshRoot, pkgName) {
  const dir = await resolvePackageDir(dshRoot, pkgName)
  if (!dir) throw new Error(`无法在 ${dshRoot} 中定位插件 ${pkgName}`)
  const pkgJson = JSON.parse(await readFile(join(dir, 'package.json'), 'utf8'))
  const exportEntry = typeof pkgJson.exports === 'string'
    ? pkgJson.exports
    : pkgJson.exports?.['.']?.import ?? pkgJson.exports?.['.']?.default ?? pkgJson.exports?.['.'] ?? pkgJson.main
  if (typeof exportEntry !== 'string' || exportEntry.length === 0) {
    throw new Error(`插件 ${pkgName} 未声明入口文件`)
  }
  return { dir, version: pkgJson.version ?? 'unknown', entryPath: join(dir, exportEntry) }
}

export async function runCordisPluginsSmoke(dshRoot) {
  const rootDir = resolve(dshRoot)
  const cordisEntry = await resolveCordisEntry(rootDir)
  const { Context, Service } = await import(pathToFileURL(cordisEntry).href)
  const mobilePluginInfo = await resolvePluginEntry(rootDir, '@deepseek-harness/dsh-mobile-shizuku')
  const mobilePlugin = await import(pathToFileURL(mobilePluginInfo.entryPath).href)
  if (typeof mobilePlugin.apply !== 'function') {
    throw new Error('@deepseek-harness/dsh-mobile-shizuku 缺少 apply() 导出')
  }

  const dshmarketInfo = await resolvePluginEntry(rootDir, 'dshmarket')
  const dshmarket = await import(pathToFileURL(dshmarketInfo.entryPath).href)
  if (typeof dshmarket.apply !== 'function') {
    throw new Error('dshmarket 缺少 apply() 导出')
  }

  const root = new Context()
  const registeredTools = []
  const promptSections = []
  root.provide('tools')
  root.provide('systemPrompt')
  root.provide('attachments')
  root.provide('llm')
  root.set('tools', {
    register(tool) {
      if (tool?.name) registeredTools.push(tool.name)
      return () => {}
    },
  })
  root.set('systemPrompt', {
    section(sec) {
      promptSections.push(sec)
      return () => {}
    },
  })
  root.set('attachments', {
    saveImage: async () => ({ attachmentId: 'att', mediaType: 'image/png', bytes: 1, width: 1, height: 1 }),
  })
  root.set('llm', {
    resolveModelInfo: async () => ({ inputModalities: ['text', 'image'] }),
  })

  let activationError = null
  const fiber = root.plugin({
    name: mobilePlugin.name ?? 'mobile-shizuku',
    inject: mobilePlugin.inject,
    apply(ctx) {
      try {
        return mobilePlugin.apply(ctx)
      } catch (error) {
        activationError = error
        throw error
      }
    },
  })
  await new Promise(r => setTimeout(r, 30))
  if (activationError) {
    throw new Error(`真实 Cordis v4 激活 @deepseek-harness/dsh-mobile-shizuku 失败：${activationError.message}`, {
      cause: activationError,
    })
  }
  for (const toolName of EXPECTED_MOBILE_TOOLS) {
    if (!registeredTools.includes(toolName)) {
      throw new Error(`插件激活后缺少核心工具：${toolName}`)
    }
  }
  if (promptSections.length === 0) {
    throw new Error('插件激活后未注册系统提示词段')
  }

  let origEnsure
  let wdInstance
  class MockWorkingDirectoryService extends Service {
    constructor(ctx) {
      super(ctx, 'workingDirectory', true)
      this.defaultDirectory = '/root'
      origEnsure = this.ensure
      wdInstance = this
    }
    async ensure() { return '/root' }
    async set(_agent, dir) { return dir }
  }

  let origRegisterBackend
  let termInstance
  class MockTerminalsService extends Service {
    constructor(ctx) {
      super(ctx, 'terminals', true)
      this.backends = new Map()
      origRegisterBackend = this.registerBackend
      termInstance = this
    }
    registerBackend(backend) {
      this.backends.set(backend.type, backend)
    }
  }

  root.plugin(MockWorkingDirectoryService)
  root.plugin(MockTerminalsService)
  await new Promise(r => setTimeout(r, 30))

  if (!wdInstance || wdInstance.ensure === origEnsure) {
    throw new Error('pty-guard 未通过真实 Cordis 注入挂接到 workingDirectory.ensure')
  }
  if (!termInstance || termInstance.registerBackend === origRegisterBackend) {
    throw new Error('pty-guard 未通过真实 Cordis 注入挂接到 terminals.registerBackend')
  }

  await fiber.dispose()
  await new Promise(r => setTimeout(r, 20))

  return {
    mobilePluginVersion: mobilePluginInfo.version,
    dshmarketVersion: dshmarketInfo.version,
    registeredToolCount: registeredTools.length,
  }
}

async function createProfileNodeModulesLinks(dshRoot, targetNodeModules) {
  const resolvedRoot = await realpath(dshRoot)
  const links = new Map()
  const queue = []

  async function enqueue(name, fromDir) {
    if (links.has(name)) return
    const real = await resolvePackageDir(fromDir, name)
    if (!real) return
    if (real !== resolvedRoot && !real.startsWith(`${resolvedRoot}${sep}`)) return
    links.set(name, real)
    queue.push(real)
  }

  const rootPkg = JSON.parse(await readFile(join(resolvedRoot, 'package.json'), 'utf8'))
  for (const dep of Object.keys({ ...(rootPkg.dependencies ?? {}), ...(rootPkg.peerDependencies ?? {}) })) {
    await enqueue(dep, resolvedRoot)
  }
  while (queue.length > 0) {
    const pkgDir = queue.pop()
    const pkgPath = join(pkgDir, 'package.json')
    if (!existsSync(pkgPath)) continue
    const manifest = JSON.parse(await readFile(pkgPath, 'utf8'))
    for (const dep of Object.keys({ ...(manifest.dependencies ?? {}), ...(manifest.peerDependencies ?? {}) })) {
      if (!links.has(dep)) await enqueue(dep, pkgDir)
    }
  }

  await mkdir(targetNodeModules, { recursive: true })
  const linkType = process.platform === 'win32' ? 'junction' : 'dir'
  for (const [name, realTarget] of links.entries()) {
    const dest = join(targetNodeModules, ...name.split('/'))
    await mkdir(dirname(dest), { recursive: true })
    try {
      await lstat(dest)
    } catch {
      await symlink(realTarget, dest, linkType)
    }
  }
  return links.size
}

function reserveEphemeralPort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = typeof address === 'object' && address ? address.port : 0
      server.close(err => {
        if (err) reject(err)
        else resolvePort(port)
      })
    })
  })
}

function httpGet(url, headers = {}) {
  return new Promise((resolveResponse, reject) => {
    const req = http.request(url, { method: 'GET', headers, timeout: 5_000 }, res => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', chunk => { body += chunk })
      res.on('end', () => resolveResponse({ statusCode: res.statusCode, headers: res.headers, body }))
    })
    req.once('timeout', () => req.destroy(new Error('HTTP request timed out')))
    req.once('error', reject)
    req.end()
  })
}

export async function verifyAuthenticatedLaunch(launchUrl, basicHeader) {
  const headers = { Accept: 'text/html', Authorization: basicHeader }
  const authed = await httpGet(launchUrl, headers)
  if (authed.statusCode === 200) return
  if (authed.statusCode !== 302 && authed.statusCode !== 303) {
    throw new Error(`鉴权后访问启动页返回异常：status=${authed.statusCode}`)
  }
  if (typeof authed.headers.location !== 'string' || !authed.headers.location) {
    throw new Error('鉴权跳转缺少目标地址')
  }

  const nextUrl = new URL(authed.headers.location, launchUrl)
  if (nextUrl.origin !== new URL(launchUrl).origin || nextUrl.username || nextUrl.password) {
    throw new Error('鉴权跳转目标必须与启动页同源')
  }
  const cookies = (authed.headers['set-cookie'] ?? [])
    .map(cookie => cookie.split(';')[0])
    .filter(Boolean)
    .join('; ')
  const followed = await httpGet(nextUrl, { ...headers, ...(cookies ? { Cookie: cookies } : {}) })
  if (followed.statusCode !== 200) {
    throw new Error(`鉴权跳转后访问首页返回异常：status=${followed.statusCode}`)
  }
}

export async function resolveNodePtyDir(dshRoot) {
  const rootDir = resolve(dshRoot)
  const direct = await resolvePackageDir(rootDir, 'node-pty')
  if (direct) return direct
  const pnpmDir = join(rootDir, 'node_modules', '.pnpm')
  if (existsSync(pnpmDir)) {
    const entries = await readdir(pnpmDir, { withFileTypes: true })
    const matches = entries
      .filter(entry => entry.isDirectory() && entry.name.startsWith('node-pty@'))
      .map(entry => join(pnpmDir, entry.name, 'node_modules', 'node-pty'))
      .filter(candidate => existsSync(join(candidate, 'package.json')))
      .sort()
    if (matches.length === 1) return matches[0]
    if (matches.length > 1) {
      throw new Error(`发现多个 node-pty 物理副本（${matches.length}）：${matches.join(', ')}`)
    }
  }
  throw new Error(`无法在 ${rootDir} 中定位 node-pty`)
}

export async function verifyNodePtyNative(dshRoot) {
  const ptyDir = await resolveNodePtyDir(dshRoot)
  const req = createRequire(join(ptyDir, 'package.json'))
  const pty = req(ptyDir)
  if (typeof pty?.spawn !== 'function') throw new Error('node-pty 未导出 spawn()')
  return ptyDir
}

export async function runDshBootSmoke({
  dshRoot,
  mobileProfilePath,
  authPreloadPath,
  timeoutMs = 30_000,
  verifyPtyNative = false,
}) {
  const rootDir = resolve(dshRoot)
  const profileSource = JSON.parse(await readFile(resolve(mobileProfilePath), 'utf8'))
  const authPreload = resolve(authPreloadPath)
  const dshDir = await resolvePackageDir(rootDir, '@deepseek-ai/dsh')
  if (!dshDir) throw new Error(`无法在 ${rootDir} 中定位 @deepseek-ai/dsh`)
  const dshBin = join(dshDir, 'lib/bin.js')
  if (!existsSync(dshBin)) throw new Error(`dsh 入口不存在：${dshBin}`)

  if (verifyPtyNative) {
    await verifyNodePtyNative(rootDir)
  }

  const tempHome = await mkdtemp(join(tmpdir(), 'dsh-ci-boot-'))
  let child = null
  try {
    const dshHome = join(tempHome, '.dsh')
    const webProfileDir = join(dshHome, 'profiles', 'web')
    const patchDir = join(tempHome, '.dsh-mobile')
    await mkdir(webProfileDir, { recursive: true })
    await mkdir(patchDir, { recursive: true })
    await writeFile(join(webProfileDir, 'package.json'), `${JSON.stringify(profileSource, null, 2)}\n`, 'utf8')
    const profileLinkCount = await createProfileNodeModulesLinks(rootDir, join(dshHome, 'profiles', 'node_modules'))
    const patchPath = join(patchDir, 'launcher-providers.patch.json')
    await writeFile(patchPath, `${JSON.stringify(buildSandboxOverlayPatch(), null, 2)}\n`, 'utf8')

    const port = await reserveEphemeralPort()
    const authToken = crypto.randomBytes(32).toString('base64url')
    const browserToken = crypto.randomBytes(32).toString('base64url')

    let output = ''
    let launchMatch = null
    let exited = false
    let exitCode = null

    child = spawn(
      process.execPath,
      [
        '--require',
        authPreload,
        dshBin,
        '--profile',
        'web',
        '--patch',
        patchPath,
        '--host',
        '127.0.0.1',
        '--port',
        String(port),
        '--no-open',
      ],
      {
        cwd: tempHome,
        env: {
          ...process.env,
          HOME: tempHome,
          USERPROFILE: tempHome,
          DSH_HOME: dshHome,
          DSH_MOBILE_AUTH_TOKEN: authToken,
          DSH_MOBILE_BROWSER_TOKEN: browserToken,
          NO_COLOR: '1',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    )

    const onChunk = chunk => {
      output += chunk.toString('utf8')
      if (!launchMatch) {
        launchMatch = DSH_LAUNCH_URL_PATTERN.exec(output)
      }
    }
    child.stdout.on('data', onChunk)
    child.stderr.on('data', onChunk)
    child.once('exit', code => {
      exited = true
      exitCode = code
    })

    const deadline = Date.now() + timeoutMs
    while (!launchMatch && !exited && Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 100))
    }

    if (exited) {
      throw new Error(`dsh web 启动前提前退出（exit=${exitCode}）：\n${output.slice(-4000)}`)
    }
    if (!launchMatch) {
      throw new Error(`dsh web 在 ${timeoutMs}ms 内未输出启动地址：\n${output.slice(-4000)}`)
    }

    // 稳定性窗口（与原生 HarnessStartupTimingPolicy.STABILITY_MS=200ms 对齐并稍宽裕）
    await new Promise(r => setTimeout(r, 300))
    if (exited) {
      throw new Error(`dsh web 在稳定性复核窗口内退出（exit=${exitCode}）：\n${output.slice(-4000)}`)
    }

    const forbiddenHits = detectBootOutputFailures(output)
    if (forbiddenHits.length > 0) {
      throw new Error(`dsh web 启动输出命中禁止错误特征 [${forbiddenHits.join(', ')}]：\n${output.slice(-4000)}`)
    }

    const launchUrl = launchMatch[1]
    if (Number(launchMatch[2]) !== port) throw new Error('启动地址端口与冒烟服务端口不符')
    const unauth = await httpGet(`http://127.0.0.1:${port}/`, { Accept: 'text/html' })
    if (unauth.statusCode !== 401 || unauth.headers['www-authenticate'] !== EXPECTED_AUTH_CHALLENGE) {
      throw new Error(
        `未鉴权探测返回异常：status=${unauth.statusCode} www-authenticate=${unauth.headers['www-authenticate']}`,
      )
    }

    const basicHeader = `Basic ${Buffer.from(`dsh-mobile:${authToken}`, 'utf8').toString('base64')}`
    await verifyAuthenticatedLaunch(launchUrl, basicHeader)

    // 同步核对外部浏览器交接：携带浏览器一次性参数应返回 302 并签发 dsh_browser_session Cookie。
    const dshToken = launchMatch[3]
    const handoff = await httpGet(
      `http://127.0.0.1:${port}/?dshMobileToken=${browserToken}&token=${dshToken}`,
      { Accept: 'text/html' },
    )
    const setCookies = Array.isArray(handoff.headers['set-cookie']) ? handoff.headers['set-cookie'] : []
    if (handoff.statusCode !== 302 || !setCookies.some(c => c.startsWith('dsh_browser_session='))) {
      throw new Error(
        `外置浏览器令牌交接异常：status=${handoff.statusCode} set-cookie=${JSON.stringify(setCookies)}`,
      )
    }

    return { port, profileLinkCount }
  } finally {
    if (child && child.exitCode === null && !child.killed) {
      child.kill('SIGTERM')
      await new Promise(resolveExit => {
        const timer = setTimeout(() => {
          try { child.kill('SIGKILL') } catch { /* ignore */ }
          resolveExit()
        }, 3_000)
        child.once('exit', () => {
          clearTimeout(timer)
          resolveExit()
        })
      })
    }
    await rm(tempHome, { recursive: true, force: true })
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.dshRoot) {
    throw new Error('缺少 --dsh-root 参数')
  }
  if (args.mode === 'cordis-plugins') {
    const summary = await runCordisPluginsSmoke(args.dshRoot)
    console.log(
      `CI_CORDIS_PLUGINS_OK mobile-shizuku=${summary.mobilePluginVersion} dshmarket=${summary.dshmarketVersion} tools=${summary.registeredToolCount}`,
    )
    return
  }
  if (args.mode === 'dsh-boot') {
    if (!args.mobileProfile || !args.authPreload) {
      throw new Error('dsh-boot 模式必须提供 --mobile-profile 与 --auth-preload')
    }
    const summary = await runDshBootSmoke({
      dshRoot: args.dshRoot,
      mobileProfilePath: args.mobileProfile,
      authPreloadPath: args.authPreload,
      timeoutMs: Number.isFinite(args.timeoutMs) && args.timeoutMs > 0 ? args.timeoutMs : 30_000,
      verifyPtyNative: args.verifyPtyNative,
    })
    console.log(
      `CI_DSH_BOOT_OK port=${summary.port} profileLinks=${summary.profileLinkCount} ptyNative=${args.verifyPtyNative}`,
    )
    return
  }
  throw new Error(`不支持的 --mode：${args.mode}（仅支持 cordis-plugins 或 dsh-boot）`)
}

const isEntrypoint = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (isEntrypoint) {
  main().catch(error => {
    console.error(`CI_RUNTIME_SMOKE_FAILED: ${error?.stack || error?.message || error}`)
    process.exit(1)
  })
}
