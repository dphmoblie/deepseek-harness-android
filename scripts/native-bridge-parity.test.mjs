import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { test } from 'node:test'
import assert from 'node:assert/strict'

// 桥接接口契约（WebView 侧声明 vs Android 原生插件）：
// `src/platform/native.ts` 里 `NativeRuntimePlugin` 接口声明的方法名，就是 Capacitor 转发到原生时
// 使用的名字。名字写错或只在一边改名时，tsc 与浏览器模拟桥都不会报错，只有装上真机点下去才会失败，
// 所以在这一层把两侧的名字钉死。
const appRoot = resolve(import.meta.dirname, '..')
const NATIVE = 'src/platform/native.ts'
const BROWSER = 'src/platform/browser.ts'
const PLUGIN = 'android/app/src/main/java/io/deepseekharness/mobile/MobileRuntimePlugin.kt'

// Capacitor 内置方法：`addListener` 由 `@capacitor/core` 在 JS 侧实现（原生只负责 notifyListeners），
// 因此它在 TS 接口里有、在 Kotlin 里没有对应的 `@PluginMethod`。
const CAPACITOR_BUILTINS = new Set(['addListener'])

// 原生有、WebView 侧没有调用点的方法。列出来是为了让「新增或删除这类方法」必须显式表态：
//   1. `virtualScreenShell`：设备桥的同名命令由 `DeviceBridgeServer.kt` 自行分派，不经过这个插件方法；
//   2. `manageRuntimeLibrary`：`src/` 里没有任何调用点，`MobileRuntimeController.manageRuntimeLibrary`
//      也只被这一个插件方法调用（即目前尚未接到界面上）。
const NATIVE_ONLY = new Map([
  ['virtualScreenShell', '设备桥命令由 DeviceBridgeServer 自行分派，不经插件方法'],
  ['manageRuntimeLibrary', 'src/ 里没有调用点，controller 侧只被这个插件方法调用'],
])

async function source(relative) {
  // 仓库文件在 Windows 检出时是 CRLF，断言里写的是 `\n`：先统一换行再匹配，免得契约随平台失效。
  return (await readFile(resolve(appRoot, relative), 'utf8')).replace(/\r\n/gu, '\n')
}

/** TS 侧 `NativeRuntimePlugin` 接口里声明的方法名（接口体内两空格缩进的方法签名）。 */
function webviewMethods(text) {
  const start = text.indexOf('interface NativeRuntimePlugin')
  assert.ok(start >= 0, `${NATIVE} 里找不到 NativeRuntimePlugin 接口`)
  const end = text.indexOf('\n}\n', start)
  assert.ok(end > start, `${NATIVE} 里看不到 NativeRuntimePlugin 接口的结尾`)
  const names = text
    .slice(start, end)
    .split('\n')
    .map(line => /^ {2}(\w+)\(/u.exec(line)?.[1])
    .filter(Boolean)
  return new Set(names)
}

/** Kotlin 侧 `@PluginMethod` 标注的方法名。 */
function nativeMethods(text) {
  const names = [...text.matchAll(/@PluginMethod\s*\n\s*fun\s+(\w+)\s*\(/gu)].map(match => match[1])
  return new Set(names)
}

test('WebView 侧声明的每个插件方法都能在原生侧找到同名 @PluginMethod', async () => {
  const webview = webviewMethods(await source(NATIVE))
  const native = nativeMethods(await source(PLUGIN))
  const missing = [...webview].filter(name => !CAPACITOR_BUILTINS.has(name) && !native.has(name))
  assert.deepEqual(missing, [], `原生插件缺少这些方法：${missing.join('、')}`)
})

test('原生侧每个 @PluginMethod 都要么暴露给 WebView，要么列在已知例外里', async () => {
  const webview = webviewMethods(await source(NATIVE))
  const native = nativeMethods(await source(PLUGIN))
  const unexpected = [...native].filter(name => !webview.has(name) && !NATIVE_ONLY.has(name))
  assert.deepEqual(
    unexpected,
    [],
    `这些原生方法既没有 WebView 调用点、也没登记进 NATIVE_ONLY：${unexpected.join('、')}`,
  )
})

test('已知例外清单不会过期', async () => {
  const webview = webviewMethods(await source(NATIVE))
  const native = nativeMethods(await source(PLUGIN))
  for (const [name, reason] of NATIVE_ONLY) {
    assert.ok(native.has(name), `${name} 已不在原生插件里，请从例外清单删掉（${reason}）`)
    assert.ok(!webview.has(name), `${name} 已经接到 WebView 侧了，请从例外清单删掉（${reason}）`)
  }
})

test('两侧都能解析出方法，避免解析失效后契约恒真', async () => {
  // 断言「缺失集合为空」时，如果正则失效、两边都解析成空集，契约会静默变成恒真。
  const webview = webviewMethods(await source(NATIVE))
  const native = nativeMethods(await source(PLUGIN))
  assert.ok(webview.size > 50, `WebView 侧只解析出 ${webview.size} 个方法，解析规则可能已失效`)
  assert.ok(native.size > 50, `原生侧只解析出 ${native.size} 个方法，解析规则可能已失效`)
})

test('注册名与原生注解名一致', async () => {
  const registered = /registerPlugin<NativeRuntimePlugin>\('([^']+)'\)/u.exec(await source(NATIVE))?.[1]
  const annotated = /@CapacitorPlugin\(\s*\n\s*name = "([^"]+)"/u.exec(await source(PLUGIN))?.[1]
  assert.equal(registered, 'MobileRuntime')
  assert.equal(annotated, registered, '原生的 @CapacitorPlugin(name) 与前端 registerPlugin 的名字必须一致')
})

test('浏览器模拟桥与原生桥受同一个 RuntimeBridge 类型约束', async () => {
  // 两侧都标注成 `RuntimeBridge`，字段/方法是否齐全由 tsc 判定；任何一边漏实现都会编译失败，
  // 这里只钉住「这个约束没有被绕过」（例如把返回类型改成 any 或 as unknown as）。
  assert.match(await source(BROWSER), /export function createBrowserBridge\(\): RuntimeBridge/u)
  assert.match(await source(NATIVE), /export const runtimeBridge: RuntimeBridge =/u)
})
