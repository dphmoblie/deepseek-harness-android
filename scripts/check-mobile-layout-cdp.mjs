// 无 Playwright 的浏览器布局回归：直接驱动系统自带的 Edge/Chrome（headless）走
// Chrome DevTools Protocol，跑与 scripts/check-mobile-layout.py 相同的三档视口
// （363×800 / 800×363 / 1280×900），断言与截图落盘到 output/mobile-layout/。
// 每档视口另跑一遍主题矩阵（系统偏好在浅色 / 深色下各来一次），确认外壳真的跟随系统
// 主题、状态栏色与 src/theme.ts 的常量一致——这条路径在 jsdom 单测里是假的 matchMedia，
// 只有真浏览器能验。
//
// 为什么另写一个：本机没有（也暂时装不上）Python playwright —— PyPI 与 npm registry
// 都不可达，ms-playwright 浏览器缓存也不存在。系统 Edge/Chrome 本来就在，CDP 是它自带
// 的调试协议，纯 Node（≥22 的全局 WebSocket 与 fetch）就能驱动，不需要任何依赖下载。
// Playwright 版本（check-mobile-layout.py）保留不变：网络恢复后它仍是首选工具，
// 两者的断言口径与输出目录刻意保持一致，便于对照。
//
// 用法：
//   pnpm run build                      # 先生成 dist
//   pnpm run preview -- --port 4173     # 另开一个终端
//   node scripts/check-mobile-layout-cdp.mjs [--url http://127.0.0.1:4173/] [--browser <可执行文件>]
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(ROOT, 'output', 'mobile-layout')
const VIEWPORTS = [
  { width: 363, height: 800 },
  { width: 800, height: 363 },
  { width: 1280, height: 900 },
]
const BROWSER_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
// 主题矩阵：外壳默认「跟随系统」（src/theme.ts 的 DEFAULT_THEME_MODE），所以系统偏好
// 一变，`<html data-theme>` 与状态栏色都必须跟着变。这两个色值必须与 src/theme.ts 的
// THEME_COLORS 一致，scripts/mobile-layout-contract.test.mjs 会比对两处字面量。
const THEMES = ['light', 'dark']
const THEME_COLORS = { light: '#f3f5f8', dark: '#111315' }

class LayoutFailure extends Error {}

// 按钮的可读名字：正文文本或 aria-label。原 Python 版用 Playwright 的 get_by_role(name=…)，
// 它匹配的是无障碍名字——「关闭设置」按钮正文其实写的是「关闭」，只有 aria-label 是
// 「关闭设置」，只比对 textContent 会漏掉它。
const BUTTON_NAMES = 'node => [(node.textContent ?? "").trim(), node.getAttribute("aria-label") ?? ""].filter(Boolean)'

function check(condition, message) {
  if (!condition) throw new LayoutFailure(message)
}

function parseArgs(argv) {
  // 端口默认随机：固定端口一旦上一次的浏览器进程没退干净，新进程会绑定失败，
  // 而脚本会连上那个旧实例（引导状态还停在上次点过的地方），断言就失去意义。
  const options = { url: 'http://127.0.0.1:4173/', browser: process.env.DSH_LAYOUT_BROWSER ?? '', port: 9300 + Math.floor(Math.random() * 400) }
  for (let index = 0; index < argv.length; index += 1) {
    const name = argv[index]
    const value = argv[index + 1]
    if (name === '--url' && value) { options.url = value; index += 1 } else if (name === '--browser' && value) { options.browser = value; index += 1 } else if (name === '--port' && value) { options.port = Number(value); index += 1 } else if (name === '--probe') { options.probe = true } else if (name === '--help' || name === '-h') { options.help = true }
  }
  return options
}

function findBrowser(explicit) {
  const candidates = explicit ? [explicit, ...BROWSER_CANDIDATES] : BROWSER_CANDIDATES
  const found = candidates.find(candidate => existsSync(candidate))
  if (!found) throw new LayoutFailure('找不到 Edge/Chrome 可执行文件，请用 --browser 指定，或设 DSH_LAYOUT_BROWSER')
  return found
}

const sleep = milliseconds => new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))

// ---------------------------------------------------------------- CDP 最小客户端

class Cdp {
  constructor(socket) {
    this.socket = socket
    this.nextId = 1
    this.pending = new Map()
    this.listeners = []
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.id) {
        const entry = this.pending.get(message.id)
        if (!entry) return
        this.pending.delete(message.id)
        if (message.error) entry.reject(new LayoutFailure(`CDP ${entry.method} 失败：${JSON.stringify(message.error)}`))
        else entry.resolve(message.result ?? {})
        return
      }
      for (const listener of this.listeners) listener(message)
    })
  }

  static async connect(baseUrl) {
    const deadline = Date.now() + 30_000
    let lastError = ''
    while (Date.now() < deadline) {
      try {
        const response = await fetch(`${baseUrl}/json/version`)
        if (response.ok) {
          const info = await response.json()
          const socket = new WebSocket(info.webSocketDebuggerUrl)
          await new Promise((resolvePromise, rejectPromise) => {
            socket.addEventListener('open', () => resolvePromise())
            socket.addEventListener('error', () => rejectPromise(new LayoutFailure('调试 WebSocket 连接失败')))
          })
          return new Cdp(socket)
        }
        lastError = `HTTP ${response.status}`
      } catch (error) {
        lastError = String(error)
      }
      await sleep(250)
    }
    throw new LayoutFailure(`浏览器调试端口未就绪（${baseUrl}）：${lastError}`)
  }

  send(method, params = {}, sessionId) {
    const id = this.nextId
    this.nextId += 1
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    return new Promise((resolvePromise, rejectPromise) => {
      this.pending.set(id, { resolve: resolvePromise, reject: rejectPromise, method })
      this.socket.send(JSON.stringify(payload))
    })
  }

  onEvent(listener) { this.listeners.push(listener) }

  close() { try { this.socket.close() } catch { /* 关闭失败不影响结论 */ } }
}

// ---------------------------------------------------------------- 页面操作助手

class Page {
  constructor(cdp, sessionId) {
    this.cdp = cdp
    this.sessionId = sessionId
    this.failures = []
  }

  send(method, params) { return this.cdp.send(method, params, this.sessionId) }

  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) {
      throw new LayoutFailure(`页面脚本报错：${result.exceptionDetails.exception?.description ?? result.exceptionDetails.text}`)
    }
    return result.result?.value
  }

  async waitFor(expression, message, timeoutMillis = 20_000) {
    const deadline = Date.now() + timeoutMillis
    let last
    while (Date.now() < deadline) {
      last = await this.evaluate(expression)
      if (last) return last
      await sleep(200)
    }
    throw new LayoutFailure(`${message}（等了 ${timeoutMillis} 毫秒）`)
  }

  // 按可见文本精确点击一个按钮；返回是否点到。
  clickText(texts) {
    const wanted = JSON.stringify(texts)
    return this.evaluate(`(() => {
      const wanted = ${wanted}
      const buttons = [...document.querySelectorAll('button')]
      const names = ${BUTTON_NAMES}
      const hit = buttons.find(node => wanted.some(text => names(node).includes(text)))
      if (!hit) return false
      hit.click()
      return true
    })()`)
  }

  // 真实鼠标点击（而不是 element.click()）：只有真点击才会移动焦点，Playwright 的
  // click() 就是这一层语义。它点击前会自动把目标滚进可视区，这里如实复刻——
  // 因此「点完 scrollTop > 0」这条断言其实近似恒真，是原脚本的既有口径，不假装更强。
  clickReal(text) {
    return this.evaluate(`(() => {
      const names = ${BUTTON_NAMES}
      const node = [...document.querySelectorAll("button")].find(item => names(item).includes(${JSON.stringify(text)}))
      if (!node) return null
      node.scrollIntoView({ block: "center" })
      const box = node.getBoundingClientRect()
      return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
    })()`).then(async box => {
      if (!box) return false
      const point = { x: Math.round(box.x), y: Math.round(box.y) }
      await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point, button: 'none' })
      await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 })
      await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 })
      return true
    })
  }

  async screenshot(name) {
    const result = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    await writeFile(join(OUT, name), Buffer.from(result.data, 'base64'))
  }
}

// 官方设置面板的结构夹具：与 check-mobile-layout.py 同源（读运行时 profile 里已安装的
// dsh-client-ui-settings-general 客户端包，抽出它的 CSS 再拼一个最小 DOM）。
async function officialFixture() {
  const pnpmDir = join(ROOT, 'scripts', 'runtime-profile', 'node_modules', '.pnpm')
  if (!existsSync(pnpmDir)) return ''
  // pnpm 的目录名带 peer 哈希（例如 @deepseek-ai+dsh-client-ui-_6c0a491d…），
  // 所以按前缀扫，再逐个确认包路径存在——与 Python 版的 glob 口径一致。
  const entries = (await readdir(pnpmDir)).filter(entry => entry.startsWith('@deepseek-ai+dsh-client-ui-'))
  let clientPath = ''
  for (const entry of entries) {
    const candidate = join(pnpmDir, entry, 'node_modules', '@deepseek-ai', 'dsh-client-ui-settings-general', 'lib', 'client.js')
    if (existsSync(candidate)) { clientPath = candidate; break }
  }
  if (!clientPath) return ''
  const source = await readFile(clientPath, 'utf8')
  let css = ''
  for (const match of source.matchAll(/const css(?:\$\d+)? = ("(?:[^"\\]|\\.)*");/gu)) {
    const value = JSON.parse(match[1])
    if (value.includes('_navList')) { css = value; break }
  }
  if (!css) return ''
  const prefix = /\.([A-Za-z0-9]+)_navList/u.exec(css)?.[1]
  if (!prefix) return ''
  const tabs = ['常规设置', '模型供应商', '插件管理', '设备工具', '权限设置', '高级设置']
    .map(name => `<button class="${prefix}_navCell">${name}</button>`).join('')
  const fields = Array.from({ length: 25 }, (_, index) =>
    `<label style="display:block;margin:16px 0">配置项目 ${index}<input style="display:block;width:100%" value="示例设置"></label>`).join('')
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
    <style>:root {--dsw-alias-bg-layer-2:white;--dsw-alias-label-primary:#17202b}
    body {font:16px sans-serif;background:#eef2f8} input {box-sizing:border-box} ${css}</style></head>
    <body><div class="${prefix}_overlay" role="presentation"><div class="${prefix}_mask"></div>
    <div class="${prefix}_panel" role="dialog" aria-modal="true" aria-labelledby="settings-title">
    <nav class="${prefix}_nav"><div class="${prefix}_navTitle" id="settings-title">设置</div>
    <div class="${prefix}_navList">${tabs}</div></nav><div class="${prefix}_content">
    <div class="${prefix}_header"><span>常规设置</span><button id="close" aria-label="关闭设置">关闭</button></div>
    <div class="${prefix}_options"><section data-slot="settings.section">${fields}
    <button id="save">保存设置</button></section></div></div></div></div></body></html>`
}

// ---------------------------------------------------------------- 三档视口各自的检查

// 引导只在全新存储里出现；每个视口 / 每种主题都用独立浏览器上下文，所以正常情况下每档都会走一遍。
// 文案随版本变过（旧版是「继续 / Continue」+「跳过引导」，当前构建是「继续 / Continue」+ 多步
// 「下一步」），所以按一组已知文案逐步推进到引导结束，不写死步数；点不动了就停。
const ONBOARDING_TEXTS = ['继续 / Continue', '下一步', '跳过引导', '开始使用', '完成', '开始对话']

async function advanceOnboarding(page, navSelector) {
  const buttonTexts = () => page.evaluate(`[...document.querySelectorAll("button")].flatMap(${BUTTON_NAMES})`)
  const hasButton = text => `[...document.querySelectorAll("button")].some(node => (${BUTTON_NAMES})(node).includes(${JSON.stringify(text)}))`
  const hasNav = `document.querySelector(${JSON.stringify(navSelector)}) !== null`
  try {
    await page.waitFor(`(${hasButton(ONBOARDING_TEXTS[0])}) || ${hasNav}`, '首屏既没有引导页也没有主导航')
  } catch (error) {
    throw new LayoutFailure(`${error.message}；当前按钮：${JSON.stringify(await buttonTexts())}`)
  }
  if (!await page.evaluate(hasButton(ONBOARDING_TEXTS[0]))) return
  for (let step = 0; step < 10; step += 1) {
    if (!await page.clickText(ONBOARDING_TEXTS)) break
    await sleep(400)
  }
  const onboardingLeft = await page.evaluate(`(${JSON.stringify(ONBOARDING_TEXTS)}).some(text => [...document.querySelectorAll("button")].some(node => (${BUTTON_NAMES})(node).includes(text)))`)
  if (onboardingLeft) throw new LayoutFailure(`引导流程没有走完；当前按钮：${JSON.stringify(await buttonTexts())}`)
}

async function checkShell(page, url, width, height) {
  await page.send('Page.navigate', { url })
  await page.waitFor('document.readyState === "complete"', '外壳页面没有加载完')
  const navSelector = width < 900 ? '.bottom-navigation' : '.sidebar-nav'
  await advanceOnboarding(page, navSelector)

  const mobile = width < 900
  await page.waitFor(`document.querySelector(${JSON.stringify(navSelector)}) !== null`, '预期导航没有显示')
  if (mobile) {
    const count = await page.evaluate('document.querySelectorAll(".bottom-navigation button").length')
    check(count === 3, `手机底栏必须恰好三个图标，实际 ${count} 个`)
    const sidebar = await page.evaluate('(() => { const node = document.querySelector(".app-sidebar"); return node !== null && getComputedStyle(node).display !== "none" })()')
    check(!sidebar, '手机不应保留侧栏')
  }

  check(await page.clickText([mobile ? '设置' : '应用设置']), '点不到设置入口')
  await page.waitFor('[...document.querySelectorAll("h1,h2,h3,[role=heading]")].some(node => (node.textContent ?? "").trim() === "设置")', '没有出现「设置」标题')
  check(await page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), '设置页出现整体横向溢出')
  await page.screenshot(`shell-settings-${width}x${height}.png`)

  check(await page.clickText([mobile ? '插件' : '插件管理']), '点不到插件入口')
  await page.waitFor('[...document.querySelectorAll("h1,h2,h3,[role=heading]")].some(node => (node.textContent ?? "").trim() === "插件管理")', '没有出现「插件管理」标题')
  check(await page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), '插件页出现整体横向溢出')
  await page.screenshot(`shell-plugins-${width}x${height}.png`)
}

// 主题跟随：只有在真浏览器的媒体查询下才跑得到这条路径（单测里 matchMedia 是假的）。
// 断言三件事——`<html data-theme>` 落到偏好值、状态栏色与 src/theme.ts 的常量一致、
// 切换主题不引起整体横向溢出；返回值是该主题下的页面底色，供调用方比对明暗确实不同。
async function checkTheme(page, url, width, height, theme) {
  await page.send('Page.navigate', { url })
  await page.waitFor('document.readyState === "complete"', '外壳页面没有加载完')
  const navSelector = width < 900 ? '.bottom-navigation' : '.sidebar-nav'
  await advanceOnboarding(page, navSelector)
  const applied = await page.evaluate('document.documentElement.dataset.theme')
  check(applied === theme, `系统偏好为 ${theme} 时 <html data-theme> 应为 ${theme}，实际 ${applied}`)
  const meta = await page.evaluate('document.querySelector("meta[name=\'theme-color\']")?.getAttribute("content") ?? ""')
  check(meta.toLowerCase() === THEME_COLORS[theme], `状态栏色应为 ${THEME_COLORS[theme]}，实际 ${meta || '（没有 meta）'}`)
  check(await page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), `${theme} 主题下出现整体横向溢出`)
  await page.screenshot(`theme-${theme}-${width}x${height}.png`)
  return page.evaluate('getComputedStyle(document.body).backgroundColor')
}

async function checkSettingsDialog(page, width, height) {
  const fixture = await officialFixture()
  if (!fixture) return '跳过（未安装运行时 profile 依赖，或找不到官方设置客户端包）'
  const androidCss = await readFile(join(ROOT, 'harness-web', 'android.css'), 'utf8')
  await page.evaluate(`(() => {
    document.open()
    document.write(${JSON.stringify(fixture)})
    document.close()
    const style = document.createElement('style')
    style.textContent = ${JSON.stringify(androidCss)}
    document.head.appendChild(style)
    return true
  })()`)
  await sleep(200)

  const box = await page.evaluate('(() => { const node = document.querySelector("[role=dialog]"); const rect = node.getBoundingClientRect(); return { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })()')
  check(box.x >= 0 && box.y >= 0 && box.x + box.width <= width + 1 && box.y + box.height <= height + 1, '设置弹窗越出视口')
  const optionsWidth = await page.evaluate('document.querySelector("[class$=_options]").getBoundingClientRect().width')
  check(optionsWidth >= (width < 900 ? width - 48 : 580), '设置正文被侧栏挤压')
  const direction = await page.evaluate('getComputedStyle(document.querySelector("[class$=_navList]")).flexDirection')
  check(direction === (width < 900 ? 'row' : 'column'), '分类导航方向错误')
  if (width === 363) {
    const scrollable = await page.evaluate('(() => { const node = document.querySelector("[class$=_navList]"); return node.scrollWidth > node.clientWidth })()')
    check(scrollable, '窄屏分类应可横向滚动')
    const advanced = await page.evaluate('(() => { const node = [...document.querySelectorAll("[class$=_navCell]")].find(item => (item.textContent ?? "").trim() === "高级设置"); if (!node) return false; node.click(); return true })()')
    check(advanced, '点不到「高级设置」分类')
  }
  check(await page.clickReal('保存设置'), '点不到「保存设置」')
  const scrolled = await page.evaluate('document.querySelector("[class$=_options]").scrollTop > 0')
  check(scrolled, '底部设置没有滚动到可操作位置')
  check(await page.clickText(['关闭设置']), '点不到「关闭设置」')
  const closeVisible = await page.evaluate('(() => { const node = document.querySelector("[aria-label=\\"关闭设置\\"]"); return node !== null && node.getBoundingClientRect().width > 0 })()')
  check(closeVisible, '关闭按钮必须持续可见')
  await page.screenshot(`dsh-settings-fixture-${width}x${height}.png`)
  return '通过'
}

// ---------------------------------------------------------------- 主流程

// 开一个隔离页面跑一段检查：每档视口 / 每种系统主题各用一个独立浏览器上下文，
// localStorage 与引导状态不会互相漏档（Playwright 版用的是 new_context，语义一致）。
async function withPage(cdp, { width, height, mobile, theme }, run) {
  const { browserContextId } = await cdp.send('Target.createBrowserContext')
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId })
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
  const page = new Page(cdp, sessionId)
  cdp.onEvent(message => {
    if (message.sessionId !== sessionId) return
    if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params.exceptionDetails
      page.failures.push(details.exception?.description ?? details.text)
    } else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      page.failures.push(message.params.args.map(argument => argument.value ?? argument.description ?? '').join(' '))
    } else if (message.method === 'Log.entryAdded' && message.params.entry.level === 'error') {
      page.failures.push(message.params.entry.text)
    }
  })
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Log.enable')
  await page.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile,
    screenOrientation: mobile ? { type: 'portraitPrimary', angle: 0 } : { type: 'landscapePrimary', angle: 90 },
  })
  // maxTouchPoints 下限是 1：桌面档用 enabled=false 关掉触摸即可，别传 0（CDP 会拒绝）。
  await page.send('Emulation.setTouchEmulationEnabled', { enabled: mobile, maxTouchPoints: 5 })
  // 系统深浅偏好只能这样模拟：写 localStorage 等于绕开「跟随系统」这条路径，测不到它。
  if (theme) await page.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] })
  try {
    return await run(page)
  } finally {
    await cdp.send('Target.closeTarget', { targetId }).catch(() => {})
    await cdp.send('Target.disposeBrowserContext', { browserContextId }).catch(() => {})
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  if (options.help) { console.log('用法：node scripts/check-mobile-layout-cdp.mjs [--url http://127.0.0.1:4173/] [--browser <可执行文件>] [--port 9333]'); return 0 }
  await mkdir(OUT, { recursive: true })
  const browserPath = findBrowser(options.browser)
  const profileDir = await mkdtemp(join(tmpdir(), 'dsh-layout-cdp-'))
  const child = spawn(browserPath, [
    '--headless=new', `--remote-debugging-port=${options.port}`, `--user-data-dir=${profileDir}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--disable-extensions',
    '--disable-features=Translate,MediaRouter', 'about:blank',
  ], { stdio: 'ignore', windowsHide: true })
  const report = []
  let cdp
  try {
    cdp = await Cdp.connect(`http://127.0.0.1:${options.port}`)
    const browserInfo = await cdp.send('Browser.getVersion')
    if (options.probe) {
      // 诊断模式：只看首页在前 15 秒里长什么样，便于确认引导流程的当前文案。
      const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' })
      const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
      const page = new Page(cdp, sessionId)
      await page.send('Page.enable')
      await page.send('Runtime.enable')
      await page.send('Log.enable')
      await page.send('Emulation.setDeviceMetricsOverride', { width: 363, height: 800, deviceScaleFactor: 1, mobile: true })
      await page.send('Page.navigate', { url: options.url })
      for (let tick = 1; tick <= 15; tick += 1) {
        await sleep(1000)
        const snapshot = await page.evaluate(`(() => ({
          buttons: [...document.querySelectorAll("button")].flatMap(${BUTTON_NAMES}),
          bottomNav: document.querySelector(".bottom-navigation") !== null,
          sidebar: document.querySelector(".app-sidebar") !== null,
          text: (document.body.innerText ?? "").replace(/\\s+/gu, " ").slice(0, 160),
        }))()`)
        console.log(`[${tick}s] bottomNav=${snapshot.bottomNav} sidebar=${snapshot.sidebar} buttons=${JSON.stringify(snapshot.buttons)}`)
        console.log(`      文本：${snapshot.text}`)
      }
      return 0
    }
    for (const { width, height } of VIEWPORTS) {
      const mobile = width < 900
      const dialog = await withPage(cdp, { width, height, mobile }, async page => {
        await checkShell(page, options.url, width, height)
        const result = await checkSettingsDialog(page, width, height)
        check(page.failures.length === 0, `页面出现运行错误：${JSON.stringify(page.failures.slice(0, 3))}`)
        return result
      })
      // 主题矩阵：系统偏好浅 / 深各跑一遍，断言主题真的跟着走（而不是只有一套配色）。
      const backgrounds = {}
      for (const theme of THEMES) {
        backgrounds[theme] = await withPage(cdp, { width, height, mobile, theme }, async page => {
          const background = await checkTheme(page, options.url, width, height, theme)
          check(page.failures.length === 0, `${theme} 主题下页面出现运行错误：${JSON.stringify(page.failures.slice(0, 3))}`)
          return background
        })
      }
      check(
        backgrounds.light !== backgrounds.dark,
        `系统偏好浅色与深色时的页面底色相同（都是 ${backgrounds.light}），说明外壳没有跟随系统主题`,
      )
      report.push({
        width,
        height,
        外壳: '通过',
        官方设置结构夹具: dialog,
        主题跟随: `通过（浅色 ${backgrounds.light} / 深色 ${backgrounds.dark}）`,
      })
    }
    await writeFile(join(OUT, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8')
    console.log(JSON.stringify(report, null, 2))
    console.log(`LAYOUT_OK: ${VIEWPORTS.length} 视口全过（${browserInfo.product} ${browserInfo.revision}）`)
    return 0
  } finally {
    // 必须连子进程一起收掉：只 kill 启动器时会留下浏览器子进程，下次跑就会连上它，
    // 而它带着上一次的引导状态。
    try { await cdp?.send('Browser.close') } catch { /* 浏览器可能已经退出 */ }
    cdp?.close()
    child.kill()
    spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on('error', () => {})
    await sleep(300)
    await rm(profileDir, { recursive: true, force: true }).catch(() => {})
  }
}

try {
  process.exitCode = await main()
} catch (error) {
  if (error instanceof LayoutFailure) {
    console.error(`LAYOUT_FAILED: ${error.message}`)
    process.exitCode = 1
  } else {
    console.error(error)
    process.exitCode = 2
  }
}
