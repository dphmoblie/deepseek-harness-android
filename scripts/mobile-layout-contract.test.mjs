// 浏览器布局回归的两个脚本必须始终覆盖同一组视口、写同一个产物目录，并且能在这台
// 没有网络、没有 Playwright 的机器上跑起来。这些断言只读源码文本（本机跑不起浏览器时
// 也能守卫口径），配合 scripts/check-mobile-layout-cdp.mjs 的实际运行结果使用。
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const read = path => readFile(resolve(appRoot, path), 'utf8')

const PLAYWRIGHT = 'scripts/check-mobile-layout.py'
const CDP = 'scripts/check-mobile-layout-cdp.mjs'

test('两套布局回归脚本覆盖同一组视口', async () => {
  const python = await read(PLAYWRIGHT)
  const javascript = await read(CDP)
  const pythonViewports = [...python.matchAll(/\((\d+), (\d+)\)/gu)].map(match => `${match[1]}x${match[2]}`)
  // 只取 VIEWPORTS 常量本身：诊断模式（--probe）里还有一处写死的 363×800。
  const block = /const VIEWPORTS = \[([\s\S]*?)\n\]/u.exec(javascript)
  assert.ok(block, 'check-mobile-layout-cdp.mjs 里找不到 VIEWPORTS 常量')
  const javascriptViewports = [...block[1].matchAll(/width: (\d+), height: (\d+)/gu)].map(match => `${match[1]}x${match[2]}`)
  const expected = ['363x800', '800x363', '1280x900']
  assert.deepEqual(pythonViewports, expected, 'check-mobile-layout.py 的视口档位变了')
  assert.deepEqual(javascriptViewports, expected, 'check-mobile-layout-cdp.mjs 的视口档位变了')
  // 防恒真：正则必须真的抓到三档，而不是两边都空。
  assert.equal(pythonViewports.length, 3)
  assert.equal(javascriptViewports.length, 3)
})

test('两套布局回归脚本写同一个输出目录与同一份报告', async () => {
  const python = await read(PLAYWRIGHT)
  const javascript = await read(CDP)
  for (const source of [python, javascript]) {
    // 两处都要出现 output 与 mobile-layout（写法可以不同，目录口径必须一致）。
    assert.ok(source.includes('output') && source.includes('mobile-layout'), '输出目录不一致')
    assert.match(source, /report\.json/u, '缺少 report.json 产物')
  }
  // 报告的四个字段两边都要有，方便直接 diff 两次运行的结果。
  for (const key of ['width', 'height', '外壳', '官方设置结构夹具']) {
    assert.ok(python.includes(key), `check-mobile-layout.py 缺少报告字段 ${key}`)
    assert.ok(javascript.includes(key), `check-mobile-layout-cdp.mjs 缺少报告字段 ${key}`)
  }
})

test('CDP 版本只用 Node 内置模块，不引第三方依赖', async () => {
  const source = await read(CDP)
  const imports = [...source.matchAll(/from '([^']+)'/gu)].map(match => match[1])
  assert.ok(imports.length >= 6, `只解析到 ${imports.length} 条 import，解析可能失效`)
  const thirdParty = imports.filter(name => !name.startsWith('node:'))
  assert.deepEqual(thirdParty, [], `CDP 版本引入了第三方依赖：${thirdParty.join(', ')}`)
})

test('CDP 版本保留踩过的坑：隔离上下文、真实点击、连子进程一起收掉', async () => {
  const source = await read(CDP)
  // 每档视口一个独立浏览器上下文，否则引导状态会从上一档漏过来。
  assert.match(source, /Target\.createBrowserContext/u)
  assert.match(source, /Target\.disposeBrowserContext/u)
  // 只有真实鼠标事件才会移动焦点（Playwright 的 click() 就是这个语义）。
  assert.match(source, /Input\.dispatchMouseEvent/u)
  // 只 kill 启动器会留下浏览器子进程，下次跑会连上它、带着上次的引导状态。
  assert.match(source, /taskkill/u)
  assert.match(source, /Browser\.close/u)
  // 随机端口：固定端口在旧实例没退干净时会连错对象。
  assert.match(source, /9300 \+ Math\.floor\(Math\.random\(\) \* 400\)/u)
})

test('CDP 版本的成功与失败标记、退出码是分开的', async () => {
  const source = await read(CDP)
  assert.match(source, /console\.log\(`LAYOUT_OK: /u)
  assert.match(source, /console\.error\(`LAYOUT_FAILED: /u)
  assert.match(source, /process\.exitCode = 1/u)
  assert.match(source, /process\.exitCode = 2/u)
  // 防恒真：两个标记都必须真的出现在打印语句里，而不是只出现在注释里。
  assert.ok(!/^\s*\/\/.*LAYOUT_OK/u.test(source))
})

test('主题矩阵用的状态栏色与 src/theme.ts 的常量逐字一致', async () => {
  const theme = await read('src/theme.ts')
  const source = await read(CDP)
  // 从 src/theme.ts 抓 THEME_COLORS 块，再从脚本抓 THEME_COLORS 字面量，两边必须同值。
  const block = /const THEME_COLORS: Readonly<Record<ResolvedTheme, string>> = \{([\s\S]*?)\n\}/u.exec(theme)
  assert.ok(block, 'src/theme.ts 里找不到 THEME_COLORS 常量')
  const expected = Object.fromEntries([...block[1].matchAll(/(light|dark): '(#[0-9a-f]{6})'/gu)].map(match => [match[1], match[2]]))
  assert.deepEqual(Object.keys(expected).sort(), ['dark', 'light'], 'src/theme.ts 的 THEME_COLORS 解析失败')
  const actual = Object.fromEntries([...source.matchAll(/(light|dark): '(#[0-9a-f]{6})'/gu)].map(match => [match[1], match[2]]))
  assert.deepEqual(actual, expected, 'CDP 脚本里的状态栏色与 src/theme.ts 不一致')
  // 防恒真：两边都必须真的抓到两个色值，而不是都空。
  assert.equal(Object.keys(actual).length, 2)
})

test('主题矩阵用 CDP 模拟系统偏好，并且明暗底色必须不同', async () => {
  const source = await read(CDP)
  // 系统偏好只能用媒体特性模拟；写 localStorage 会绕开「跟随系统」这条路径。
  assert.match(source, /Emulation\.setEmulatedMedia/u)
  assert.match(source, /name: 'prefers-color-scheme'/u)
  assert.match(source, /const THEMES = \['light', 'dark'\]/u)
  // 两种偏好下的页面底色相同 = 外壳没有跟随系统主题，必须判失败。
  assert.match(source, /backgrounds\.light !== backgrounds\.dark/u)
  assert.match(source, /document\.documentElement\.dataset\.theme/u)
  // 每档视口 / 每种主题一个独立上下文（否则引导状态与主题会互相漏档）。
  assert.match(source, /async function withPage\(/u)
  assert.equal([...source.matchAll(/Target\.createBrowserContext/gu)].length, 1, '建上下文只应有一处（withPage）')
})
