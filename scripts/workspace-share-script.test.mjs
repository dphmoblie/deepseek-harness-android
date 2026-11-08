import { readFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'

const appRoot = resolve(import.meta.dirname, '..')
const scriptPath = resolve(appRoot, 'android/app/src/main/assets/support/harness-workspace-share.js')

/**
 * 预览分享脚本是注入到官方前端的浏览器脚本，Kotlin 单测覆盖不到。
 * 这里只守两件事：语法必须能过 `node --check`，且回传通道只允许本应用认的
 * `dsh-share://file?path=…` / `?address=…`，不能出现 eval、javascript: 之类可被页面利用的入口。
 */
test('预览分享脚本语法有效，且只通过 dsh-share 自定义 scheme 交回原生', async () => {
  const source = await readFile(scriptPath, 'utf8')
  const checked = spawnSync(process.execPath, ['--check', scriptPath], { encoding: 'utf8' })
  assert.equal(checked.status, 0, checked.stderr)
  assert.match(source, /dsh-resource:\/\/file\/session\//u)
  assert.match(source, /dsh-share:\/\/file\?path=/u)
  assert.match(source, /dsh-share:\/\/file\?address=/u)
  assert.match(source, /requestAnimationFrame/u)
  assert.doesNotMatch(source, /\beval\s*\(/u)
  assert.doesNotMatch(source, /javascript:/iu)
  // 地址只能拼进 location.href，不能在页面里直接发起请求或写进 DOM 属性。
  assert.doesNotMatch(source, /\bfetch\s*\(/u)
  assert.doesNotMatch(source, /XMLHttpRequest/u)
})

/**
 * 标题栏的绝对路径是宿主解析出来的位置，比相对资源地址可靠：会话 cwd 是 `/root`、
 * 工作区还是工作区的子目录，绝对路径都指向同一个文件。
 */
test('分享目标优先使用标题栏的绝对路径，地址只在拿不到时兜底', async () => {
  const source = await readFile(scriptPath, 'utf8')
  const dom = new JSDOM('<!doctype html><body></body>', {
    url: 'http://127.0.0.1/',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  })
  dom.window.eval(source)
  dom.window.document.body.innerHTML =
    '<div data-textpreview-state="text" ' +
    'data-textpreview-url="dsh-resource://file/session/s/1/sub/report.md">' +
    '<div class="panel-header"><span data-textpreview-path="true" title="/root/1/sub/report.md">report.md</span>' +
    '<button type="button" data-textpreview-tool="reload">重载</button></div></div>' +
    '<div data-textpreview-state="unsupported" ' +
    'data-textpreview-url="dsh-resource://file/session/s/a.bin">' +
    '<div><span data-textpreview-path="true" title="a.bin">a.bin</span></div></div>' +
    '<div data-textpreview-state="text" ' +
    'data-textpreview-url="dsh-resource://file/session/s/b.txt">' +
    '<div><span data-textpreview-path="true">/root/1/b.txt</span></div></div>'

  const panels = dom.window.document.querySelectorAll('[data-textpreview-state]')
  const targetFor = dom.window.__dshAndroidShare.targetFor
  assert.equal(
    targetFor(panels[0]),
    'dsh-share://file?path=' + encodeURIComponent('/root/1/sub/report.md') +
      '&address=' + encodeURIComponent('dsh-resource://file/session/s/1/sub/report.md'),
  )
  // 标题是相对路径（宿主还没解析出绝对路径）时退回地址，不能把相对路径当绝对路径发出去。
  assert.equal(
    targetFor(panels[1]),
    'dsh-share://file?address=' + encodeURIComponent('dsh-resource://file/session/s/a.bin'),
  )
  // 老版本 PathLabel 没有 title 时用可见文本兜底，它同样是绝对路径。
  assert.equal(
    targetFor(panels[2]),
    'dsh-share://file?path=' + encodeURIComponent('/root/1/b.txt') +
      '&address=' + encodeURIComponent('dsh-resource://file/session/s/b.txt'),
  )
  // 等观察器把插入的节点处理完再关窗口，否则回调会在已销毁的 document 上执行。
  await new Promise(resolvePromise => setTimeout(resolvePromise, 100))
  dom.window.close()
})


test('脚本只在预览面板顶栏补一个幂等按钮，并复用官方工具位样式', async () => {
  const source = await readFile(scriptPath, 'utf8')
  const dom = new JSDOM('<!doctype html><body></body>', {
    url: 'http://127.0.0.1/',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  })
  dom.window.eval(source)
  // 面板晚于脚本出现：观察器必须在插入后补上按钮。
  dom.window.document.body.innerHTML = '<div data-textpreview-state="text" ' +
    'data-textpreview-url="dsh-resource://file/session/session-abc//root/1/notes.md">' +
    '<div class="panel-header"><span data-textpreview-path="true">notes.md</span>' +
    '<button type="button" data-textpreview-tool="reload" class="tool-slot">重载</button></div></div>'
  await new Promise(resolvePromise => setTimeout(resolvePromise, 100))

  const panel = dom.window.document.querySelector('[data-textpreview-state="text"]')
  const buttons = panel.querySelectorAll('[data-dsh-android-share]')
  assert.equal(buttons.length, 1)
  assert.equal(buttons[0].className, 'tool-slot')
  assert.equal(buttons[0].getAttribute('aria-label'), '分享文件')
  // 重复注入只能补扫，不能叠加按钮或观察器。
  dom.window.eval(source)
  assert.equal(panel.querySelectorAll('[data-dsh-android-share]').length, 1)

  // 无工具按钮的空态也要有可点的入口（使用固定尺寸兜底样式）。
  dom.window.document.body.insertAdjacentHTML('beforeend', '<div data-textpreview-state="unsupported" ' +
    'data-textpreview-url="dsh-resource://file/session/session-abc//root/1/a.bin">' +
    '<div><span data-textpreview-path="true">a.bin</span></div></div>')
  await new Promise(resolvePromise => setTimeout(resolvePromise, 100))
  const fallback = dom.window.document.querySelectorAll('[data-dsh-android-share]')
  assert.equal(fallback.length, 2)
  assert.match(fallback[1].getAttribute('style'), /width:\s*28px/u)
  dom.window.close()
})

test('原生侧从 assets 注入该脚本，并在主框架加载完成后执行', async () => {
  const loader = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessWorkspaceShare.kt'),
    'utf8',
  )
  const activity = await readFile(
    resolve(appRoot, 'android/app/src/main/java/io/deepseekharness/mobile/HarnessActivity.kt'),
    'utf8',
  )
  assert.match(loader, /support\/harness-workspace-share\.js/u)
  assert.match(loader, /evaluateJavascript/u)
  assert.match(activity, /HarnessWorkspaceShare\.apply\(view\)/u)
  assert.match(activity, /onWorkspaceShare = ::shareWorkspaceFile/u)
})
