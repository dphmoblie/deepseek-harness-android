import { readFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'

const appRoot = resolve(import.meta.dirname, '..')
const scriptPath = resolve(appRoot, 'android/app/src/main/assets/support/harness-files-path.js')
const source = await readFile(scriptPath, 'utf8')

/**
 * 「文件」面板路径面包屑是注入到官方前端的浏览器脚本，Kotlin 单测覆盖不到。
 * 语法与「只靠官方 data-* 锚点、不发请求、不引入可执行字符串」这几条先钉住。
 */
test('面包屑脚本语法有效，且不引入请求 / eval / javascript: 入口', () => {
  const checked = spawnSync(process.execPath, ['--check', scriptPath], { encoding: 'utf8' })
  assert.equal(checked.status, 0, checked.stderr)
  assert.match(source, /data-files-state="tree"/u)
  assert.match(source, /\[data-files-path\]\[data-path-label\]/u)
  assert.match(source, /data-files-entry="directory"/u)
  assert.doesNotMatch(source, /\beval\s*\(/u)
  assert.doesNotMatch(source, /javascript:/iu)
  assert.doesNotMatch(source, /\bfetch\s*\(/u)
  assert.doesNotMatch(source, /XMLHttpRequest/u)
  assert.doesNotMatch(source, /dsh-share:/u)
})

/** 构造一棵「已经展开两层」的官方文件面板树；根是会话工作目录。 */
function treeMarkup() {
  return '<div data-files-state="tree" data-files-root="/root/dsh">' +
    '<div class="files-header">' +
    '<span data-files-path="true" data-path-label="true" title="/root/dsh">' +
    '<span class="dir">/root/</span><span class="name">dsh</span></span>' +
    '<button type="button" class="tool" aria-label="重新读取">重载</button>' +
    '</div>' +
    '<div class="files-body"><ul class="level">' +
    '<li data-files-entry="directory" data-files-path="/root/dsh/audit">' +
    '<button type="button" aria-expanded="true">audit</button>' +
    '<ul class="level">' +
    '<li data-files-entry="directory" data-files-path="/root/dsh/audit/sub">' +
    '<button type="button" aria-expanded="true">sub</button>' +
    '<ul class="level">' +
    '<li data-files-entry="file" data-files-path="/root/dsh/audit/sub/a.md">' +
    '<button type="button">a.md</button></li>' +
    '</ul></li>' +
    '</ul></li>' +
    '</ul></div></div>'
}

function loadDom(body = '') {
  const dom = new JSDOM(`<!doctype html><body>${body}</body>`, {
    url: 'http://127.0.0.1/',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  })
  dom.window.eval(source)
  return dom
}

const tick = (ms = 60) => new Promise(resolvePromise => setTimeout(resolvePromise, ms))

test('路径按目录切成逐级面包屑，工作区之上的层级只显示、不提供跳转', async () => {
  const dom = loadDom(treeMarkup())
  const document = dom.window.document
  const header = document.querySelector('.files-header')
  const nav = header.querySelector('[data-dsh-files-crumbs]')
  assert.ok(nav, '顶栏必须出现面包屑容器')
  assert.equal(header.firstElementChild, nav, '面包屑放在官方路径标签之前')
  const crumbs = [...document.querySelectorAll('[data-dsh-files-crumb]')]
  assert.deepEqual(crumbs.map(crumb => crumb.textContent), ['root', 'dsh', 'audit', 'sub'])
  // 工作区根与它之上的路径：`/root` 在根之外，Host 会拒（workspace-file/outside-workspace），
  // 所以只能显示原因，不能给一个点了会报错的跳转。
  assert.equal(crumbs[0].getAttribute('aria-disabled'), 'true')
  assert.match(crumbs[0].getAttribute('title'), /工作区之外/u)
  // 工作区根与本层目录可点。
  assert.equal(crumbs[1].hasAttribute('aria-disabled'), false)
  assert.match(crumbs[1].getAttribute('title'), /工作区根目录/u)
  assert.equal(crumbs[2].hasAttribute('aria-disabled'), false)
  // 最深层就是当前目录。
  assert.equal(crumbs[3].getAttribute('aria-current'), 'step')
  assert.match(crumbs[3].getAttribute('title'), /当前目录/u)
  // 官方那段静态路径在面包屑存在时被藏掉，避免同一份信息出现两次。
  const style = document.querySelector('style[data-dsh-files-crumbs-style]')
  assert.ok(style)
  assert.match(style.textContent, /\[data-dsh-files-crumbs\]~\[data-files-path\]\[data-path-label\]\{display:none/u)
  await tick()
  dom.window.close()
})

test('工作区之外的层级点了给一条说明，不发起跳转', async () => {
  const dom = loadDom(treeMarkup())
  const document = dom.window.document
  let collapsed = 0
  for (const toggle of document.querySelectorAll('li[data-files-entry="directory"] > button')) {
    toggle.addEventListener('click', () => { collapsed += 1 })
  }
  const outside = [...document.querySelectorAll('[data-dsh-files-crumb]')].find(crumb => crumb.textContent === 'root')
  assert.ok(outside)
  outside.click()
  assert.equal(collapsed, 0, '工作区之外的层级不能触发目录收起')
  const hint = document.querySelector('[data-dsh-files-crumbs-hint]')
  assert.ok(hint, '触屏上没有悬停，点了必须有可见说明')
  assert.match(hint.textContent, /工作区之外/u)
  // 重复点击只重置提示，不叠第二块。
  outside.click()
  assert.equal(document.querySelectorAll('[data-dsh-files-crumbs-hint]').length, 1)
  // 提示会自己消失；等它收尾再关窗口，否则计时器会在已销毁的 document 上执行。
  await tick(2700)
  assert.equal(document.querySelector('[data-dsh-files-crumbs-hint]'), null)
  dom.window.close()
})

test('点上层目录收起更深层的展开，回到那一层的文件列表', async () => {
  const dom = loadDom(treeMarkup())
  const document = dom.window.document
  const auditToggle = document.querySelector('li[data-files-path="/root/dsh/audit"] > button')
  const subToggle = document.querySelector('li[data-files-path="/root/dsh/audit/sub"] > button')
  let auditClicks = 0
  let subClicks = 0
  // 只模拟官方行为里本脚本依赖的部分：点开的行收起子树。
  auditToggle.addEventListener('click', () => {
    auditClicks += 1
    auditToggle.setAttribute('aria-expanded', 'false')
    const nested = auditToggle.parentElement.querySelector('ul')
    if (nested !== null) nested.remove()
  })
  subToggle.addEventListener('click', () => { subClicks += 1 })

  const rootCrumb = [...document.querySelectorAll('[data-dsh-files-crumb]')].find(crumb => crumb.textContent === 'dsh')
  assert.ok(rootCrumb)
  rootCrumb.click()
  // 只点最浅的那一层：一次点击连子树一起收掉，已经脱离文档的节点不再补点。
  assert.equal(auditClicks, 1)
  assert.equal(subClicks, 0)

  await tick()
  const crumbs = [...document.querySelectorAll('[data-dsh-files-crumb]')]
  assert.deepEqual(crumbs.map(crumb => crumb.textContent), ['root', 'dsh'])
  assert.equal(crumbs[1].getAttribute('aria-current'), 'step')
  dom.window.close()
})

test('官方路径标签不存在时不动手，重复注入只补扫一遍', async () => {
  const dom = loadDom('<div data-files-state="tree" data-files-root="/root/dsh"></div>')
  const document = dom.window.document
  assert.equal(document.querySelector('[data-dsh-files-crumbs]'), null)
  // 面板晚于脚本出现：观察器补上，且重复注入不叠加容器或样式。
  document.body.insertAdjacentHTML('beforeend', treeMarkup())
  await tick(120)
  assert.equal(document.querySelectorAll('[data-dsh-files-crumbs]').length, 1)
  dom.window.eval(source)
  await tick(60)
  assert.equal(document.querySelectorAll('[data-dsh-files-crumbs]').length, 1)
  assert.equal(document.querySelectorAll('style[data-dsh-files-crumbs-style]').length, 1)
  dom.window.close()
})

test('纯函数：路径切段、目录包含关系与根目录退化', () => {
  const dom = loadDom()
  const helpers = dom.window.__dshAndroidFilesPath
  assert.deepEqual([...helpers.segmentsOf('/root/dsh/audit').map(entry => entry.path)],
    ['/root', '/root/dsh', '/root/dsh/audit'])
  // 只折叠 Linux 的路径分隔符，反斜杠属于文件名。
  assert.equal(helpers.normalize('/root//dsh/'), '/root/dsh')
  assert.equal(helpers.normalize('/root/dsh/reports\\2026/'), '/root/dsh/reports\\2026')
  assert.equal(helpers.normalize('/'), '/')
  assert.deepEqual([...helpers.segmentsOf('/')], [])
  assert.equal(helpers.contains('/root/dsh', '/root/dsh/audit'), true)
  assert.equal(helpers.contains('/root/dsh', '/root/dsh'), true)
  assert.equal(helpers.contains('/root/dsh', '/root/dsh2'), false)
  assert.equal(helpers.contains('/', '/root'), true)
  dom.window.close()
})

test('Ubuntu 目录名里的反斜杠不会变成虚假的上层目录', async () => {
  const dom = loadDom(treeMarkup().replaceAll('/audit', '/reports\\2026'))
  const document = dom.window.document
  const crumbs = [...document.querySelectorAll('[data-dsh-files-crumb]')]
  assert.deepEqual(crumbs.map(crumb => crumb.textContent), ['root', 'dsh', 'reports\\2026', 'sub'])
  assert.equal(crumbs[2].getAttribute('data-dsh-files-crumb'), '/root/dsh/reports\\2026')
  let collapsed = 0
  document.querySelector('li[data-files-path$="/sub"] > button').addEventListener('click', () => { collapsed += 1 })
  crumbs[2].click()
  assert.equal(collapsed, 1, '点击真实父目录仍可收起子目录')
  await tick()
  dom.window.close()
})

test('原生侧从 assets 注入该脚本，并在主框架加载完成后执行', async () => {
  const loader = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessFilesPath.kt'),
    'utf8',
  )
  const activity = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessActivity.kt'),
    'utf8',
  )
  assert.match(loader, /support\/harness-files-path\.js/u)
  assert.match(loader, /evaluateJavascript/u)
  assert.match(activity, /HarnessFilesPath\.apply\(view\)/u)
})
