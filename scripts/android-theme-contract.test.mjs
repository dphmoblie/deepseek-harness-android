import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { JSDOM } from 'jsdom'

/**
 * 主题两端的契约：原生桥写什么，`android.css` 就必须消费什么。
 *
 * 背景：文字颜色与底色由两个不同来源决定时，用户看到的是「白字深底」配「黑字」，
 * 或者干脆「黑字压深底」。修法是把文字令牌接到外壳自己算出的深浅上
 * （`data-dsh-theme-resolved`），因此这条链路横跨 APK 与子模块两层：
 *
 *   1. `HarnessThemeBridge` 按 surface 亮度写 `data-dsh-theme-resolved` 与
 *      `--dsh-theme-label-*`（document-start 注入 + 运行期重放）；
 *   2. `android.css` 把这些变量接到官方 `--dsw-alias-label-*`，权重压过
 *      `body[data-ds-dark-theme]`（页面自己的 ThemePresenter 也会写那个属性）；
 *   3. 两侧各自硬编码了官方调色板的三档文字色——任一侧改名或改值，这里先炸，
 *      而不是等真机上出现一屏不可读的文字。
 */

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const bridgePath = 'android/app/src/main/java/io/deepseekharness/mobile/HarnessThemeBridge.kt'
const activityPath = 'android/app/src/main/java/io/deepseekharness/mobile/HarnessActivity.kt'
const cssPath = 'harness-web/android.css'

const read = path => readFile(resolve(projectRoot, path), 'utf8')

/** 去掉块注释：注释里会提到令牌名，不能当成「已经开始消费」的证据。 */
const stripComments = css => css.replace(/\/\*[\s\S]*?\*\//gu, '')

/** 枚举简单规则（`选择器 { 体 }`）；@media 的内层规则同样能被这条正则匹配到。 */
function rules(css) {
  const found = []
  for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/gu)) {
    const selector = match[1].trim().replace(/\s+/gu, ' ')
    if (selector.length === 0) continue
    found.push({ selector, body: match[2] })
  }
  return found
}

/** 取出 Kotlin 里 `NAME = PagePalette(...)` 的三档色值（小写十六进制）。 */
function palette(kotlin, name) {
  const match = kotlin.match(new RegExp(`${name} = PagePalette\\(([\\s\\S]*?)\\n\\s*\\)`, 'u'))
  assert.ok(match, `HarnessThemeBridge 里找不到 ${name}`)
  const hexes = [...match[1].matchAll(/#[0-9a-f]{6}/giu)].map(found => found[0].toLowerCase())
  assert.equal(hexes.length, 3, `${name} 必须正好给出 primary/secondary/tertiary 三档文字色`)
  return hexes
}

/** 抽出入注入脚本的正文（Kotlin 原始字符串）。 */
function scriptTemplate(kotlin) {
  const start = kotlin.indexOf('(function () {')
  const end = kotlin.indexOf('""".trimIndent()', start)
  assert.ok(start > 0 && end > start, 'HarnessThemeBridge 里找不到注入脚本正文')
  return kotlin.slice(start, end)
}

/**
 * 把 Kotlin 的 `${...}` / `$name` 插值换成测试值。
 *
 * 只在测试里做这一步：真机上插值由 Kotlin 完成，这里要验证的是**插值之后**那段 JS 的
 * 行为（属性仲裁、body 重建补偿、stop 之后不再抢写）。
 */
function interpolate(template, { mode = 'dark', paletteName = 'dark' } = {}) {
  const hexes = paletteName === 'dark'
    ? ['#f9fafb', '#cfd3d6', '#adb2b8']
    : ['#0f1115', '#61666b', '#81858c']
  let js = template
  for (const [token, value] of [
    ['${page.css}', paletteName],
    ['${page.primary}', hexes[0]],
    ['${page.secondary}', hexes[1]],
    ['${page.tertiary}', hexes[2]],
    ['${page.dark}', paletteName === 'dark' ? 'true' : 'false'],
    ['${opacity / 100.0}', '0.3'],
    ['${controlOpacity / 100.0}', '0.4'],
    ['${sidebarOpacity / 100.0}', '1.0'],
    ['${rightPanelOpacity / 100.0}', '0.4'],
    ['${BACKGROUND_PATH}', '/__shell/background'],
    ['$mode', mode],
    ['$accent', '#315fcf'],
    ['$background', '#101820'],
    ['$surface', '#18222c'],
    ['$hasBackground', 'false'],
    ['$kind', 'null'],
  ]) {
    js = js.split(token).join(value)
  }
  return js
}

/** 挂一份最小页面（自带一个官方侧栏开关，避免脚本再画一个兜底按钮）。 */
function mount(script) {
  // `outside-only` 让 `window.eval` 真正跑在页面上下文里（不需要它执行页面自带的脚本）。
  const dom = new JSDOM(
    '<!doctype html><html><head></head><body><button aria-label="menu"></button></body></html>',
    { runScripts: 'outside-only' },
  )
  dom.window.eval(script)
  return dom
}

const tick = () => new Promise(resolve => setTimeout(resolve, 0))

test('原生桥写出的每个主题锚点都与 android.css 消费的名字一致', async () => {
  const bridge = await read(bridgePath)
  const scriptStart = bridge.indexOf('(function () {')
  const scriptEnd = bridge.indexOf('""".trimIndent()', scriptStart)
  assert.ok(scriptStart > 0 && scriptEnd > scriptStart, 'HarnessThemeBridge 里找不到注入脚本正文')
  const script = bridge.slice(scriptStart, scriptEnd)

  // CSS 的 var() 与属性选择器依赖这些名字；改名必须两端一起改。
  // 属性用 `dataset.dshThemeXxx` 写时字符串是驼峰，映射由 DOM 负责，这里两种写法都认。
  for (const [token, alias] of [
    ['data-dsh-theme-source', 'dshThemeSource'],
    ['data-dsh-theme-resolved', 'dshThemeResolved'],
    ['--dsh-theme-label-primary', null],
    ['--dsh-theme-label-secondary', null],
    ['--dsh-theme-label-tertiary', null],
    // 页面插件与外壳共用的那个属性：桥必须自己对齐，不能只写一个「模式」就算完。
    ['data-ds-dark-theme', null],
    // 幂等标记：诊断时用来确认这段 body 真的同步过（现场判定第 6.2 节）。
    ['data-dsh-theme-applied', null],
  ]) {
    const written = script.includes(token) || (alias !== null && script.includes(alias))
    assert.ok(written, `注入脚本没有写 ${token}，android.css 对应的选择器会静默失效`)
  }
})

test('文字令牌只在外壳源标记下声明，且权重高过官方的 body[data-ds-dark-theme]', async () => {
  const labelRules = rules(stripComments(await read(cssPath)))
    .filter(rule => rule.body.includes('--dsw-alias-label-'))

  assert.ok(labelRules.length >= 2, 'android.css 没有重绑 --dsw-alias-label-*，文字与底色会分家')
  for (const rule of labelRules) {
    assert.ok(
      rule.selector.startsWith('html[') && rule.selector.endsWith(' body'),
      `文字令牌必须落在 html[...] body 上（0,2,2 > body[data-ds-dark-theme] 的 0,1,1），实际是：${rule.selector}`,
    )
    assert.match(rule.selector, /data-dsh-theme-source='shell'/u, '没同步主题的页面不该被碰到')
  }

  const resolvedRules = paletteName => labelRules.filter(rule =>
    rule.selector.includes(`[data-dsh-theme-resolved='${paletteName}']`) && rule.selector.endsWith(' body'))
  assert.equal(resolvedRules('dark').length, 1, '缺少深色调色板的文字令牌规则')
  assert.equal(resolvedRules('light').length, 1, '缺少浅色调色板的文字令牌规则')

  // 旧版桥（不写 resolved）时按显式模式兜底；`system` 无法兜底，这正是要升级桥的原因。
  for (const mode of ['dark', 'light']) {
    const fallback = labelRules.filter(rule =>
      rule.selector.includes(`[data-dsh-theme='${mode}']:not([data-dsh-theme-resolved])`))
    assert.equal(fallback.length, 1, `缺少 ${mode} 模式的旧桥兜底规则`)
  }
})

test('两端的官方调色板取值逐字一致', async () => {
  const kotlin = await read(bridgePath)
  const css = await read(cssPath)
  for (const [name, paletteName] of [['DARK_PAGE', 'dark'], ['LIGHT_PAGE', 'light']]) {
    const hexes = palette(kotlin, name)
    const rule = rules(stripComments(css)).find(candidate =>
      candidate.selector.includes(`html[data-dsh-theme-source='shell'][data-dsh-theme-resolved='${paletteName}'] body`))
    assert.ok(rule, `android.css 缺少 ${paletteName} 的文字令牌规则`)
    for (const hex of hexes) {
      assert.ok(
        rule.body.toLowerCase().includes(hex),
        `${paletteName} 调色板缺 ${hex}：桥给了这个色值，android.css 的 var() 缺省值必须与它一致`,
      )
    }
  }
})

test('color-scheme 由外壳同步，页面改写内联值时由注入脚本对齐', async () => {
  const css = stripComments(await read(cssPath))
  for (const paletteName of ['dark', 'light']) {
    const rule = rules(css).find(candidate =>
      candidate.selector.includes(`[data-dsh-theme-resolved='${paletteName}']`) &&
      candidate.selector.startsWith('html[') &&
      /color-scheme/u.test(candidate.body))
    assert.ok(rule, `缺少 ${paletteName} 的 color-scheme 规则`)
    assert.match(rule.body, new RegExp(`color-scheme:\\s*${paletteName}`, 'u'))
    // android.css 的子模块契约：顶层不许有 !important（桌面/平板保持官方度量）。
    assert.doesNotMatch(rule.body, /!important/u)
  }
  // system 模式不再把决定权交回页面的 prefers-color-scheme（它跟的是宿主 uiMode）。
  assert.doesNotMatch(css, /html\[data-dsh-theme-source='shell'\]\[data-dsh-theme='system'\]\s*\{[^{}]*color-scheme/u)

  // 页面 ThemePresenter 写的是内联样式：样式表压不过它，必须由脚本把内联值对齐。
  const script = scriptTemplate(await read(bridgePath))
  assert.match(script, /root\.style\.colorScheme\s*=\s*resolved/u, '注入脚本没有把内联 color-scheme 对齐到外壳结论')
  assert.match(script, /attributeFilter:\s*\['style'\]/u, '注入脚本没有观察 html 的内联样式，页面改写后不会跟上')
})

test('页面加载前就装好主题脚本，取消同步时把注册与页面状态一起撤掉', async () => {
  const activity = await read(activityPath)
  assert.match(
    activity,
    /HarnessThemeBridge\.installDocumentStart\(webView, originRule\)/u,
    'HarnessActivity 没装 document-start 主题脚本：首帧会先按页面自己的偏好画一次',
  )
  const bridge = await read(bridgePath)
  assert.match(bridge, /handler\?\.remove\(\)/u, '换主题或取消同步时必须撤掉旧的 document-start 注册')
  assert.match(bridge, /removeAttribute\('data-dsh-theme-source'\)/u, '取消同步要清掉外壳源标记，否则页面永远停在旧主题')
  assert.match(bridge, /--dsh-theme-label-primary/u, '取消同步要清掉文字令牌，否则页面回到旧配色')
})

test('注入脚本把页面配色钉在 surface 上，页面自己翻属性也会被拉回来', async () => {
  const script = interpolate(scriptTemplate(await read(bridgePath)))
  const dom = mount(script)
  const { document } = dom.window

  assert.equal(document.documentElement.dataset.dshThemeSource, 'shell')
  assert.equal(document.documentElement.dataset.dshThemeResolved, 'dark')
  assert.equal(document.documentElement.style.getPropertyValue('--dsh-theme-label-primary'), '#f9fafb')
  assert.equal(document.documentElement.style.getPropertyValue('--dsh-theme-label-tertiary'), '#adb2b8')
  assert.ok(document.body.hasAttribute('data-ds-dark-theme'), '深色面板必须让官方调色板走深色那一套')
  assert.ok(document.body.hasAttribute('data-dsh-theme-applied'))

  // 页面 ThemePresenter 按自己的偏好翻面（默认跟随 WebView 的 prefers-color-scheme）。
  document.body.removeAttribute('data-ds-dark-theme')
  await tick()
  assert.ok(document.body.hasAttribute('data-ds-dark-theme'), '外壳的结论必须赢，否则就是黑字压深底')

  // 同一份偏好也会写 html 的内联 color-scheme：滚动条与表单控件不能单独翻面。
  document.documentElement.style.colorScheme = 'light'
  await tick()
  assert.equal(document.documentElement.style.colorScheme, 'dark')
})

test('body 被重建后会补上同一套结论，stop 之后不再抢写', async () => {
  const script = interpolate(scriptTemplate(await read(bridgePath)))
  const dom = mount(script)
  const { document } = dom.window

  const fresh = document.createElement('body')
  fresh.appendChild(document.createElement('div'))
  document.documentElement.replaceChild(fresh, document.body)
  await tick()

  assert.ok(document.body.hasAttribute('data-ds-dark-theme'), '新 body 也要被同步，不能等用户刷新')
  assert.ok(document.body.hasAttribute('data-dsh-theme-applied'))

  dom.window.__dshShellTheme.stop()
  document.body.removeAttribute('data-ds-dark-theme')
  await tick()
  assert.equal(document.body.hasAttribute('data-ds-dark-theme'), false, '停掉之后不能再和页面的写入打架')
})

test('取消同步的脚本把外壳写过的一切撤干净', async () => {
  const kotlin = await read(bridgePath)
  const dom = mount(interpolate(scriptTemplate(kotlin)))
  const { document } = dom.window
  const reset = kotlin.match(/private val RESET_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/u)
  assert.ok(reset, 'HarnessThemeBridge 里找不到 RESET_SCRIPT')

  dom.window.eval(reset[1])

  assert.equal(document.documentElement.hasAttribute('data-dsh-theme-source'), false)
  assert.equal(document.documentElement.hasAttribute('data-dsh-theme-resolved'), false)
  assert.equal(document.documentElement.style.getPropertyValue('--dsh-theme-label-primary'), '')
  assert.equal(document.body.hasAttribute('data-dsh-theme-applied'), false)
  assert.equal(typeof dom.window.__dshShellTheme, 'undefined')

  // 撤掉注册之后，页面自己的写入手到擒来（不再有 observer 把它拉回去）。
  document.body.setAttribute('data-ds-dark-theme', '')
  await tick()
  assert.ok(document.body.hasAttribute('data-ds-dark-theme'))

  // 从没同步过的页面：这段脚本每次主框架加载都会跑，不能动页面自己的内联 color-scheme。
  const plain = new JSDOM('<!doctype html><html><head></head><body></body></html>', { runScripts: 'outside-only' })
  plain.window.document.documentElement.style.colorScheme = 'light'
  plain.window.eval(reset[1])
  assert.equal(plain.window.document.documentElement.style.getPropertyValue('color-scheme'), 'light')
})

test('打包进 rootfs 的 dsh-android.css 与子模块里的源文件一致', async t => {
  let shipped
  try {
    shipped = await read('harness-web/dist/dsh-android.css')
  } catch {
    t.skip('harness-web/dist 还没构建：先跑 `pnpm --dir harness-web build`')
    return
  }
  const source = await read(cssPath)
  assert.equal(
    shipped,
    source,
    'harness-web/dist 里的适配样式落后于 android.css：主题修复不会随运行时一起进设备，先重新构建',
  )
})
