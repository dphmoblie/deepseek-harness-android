// Capacitor 的 `android.captureInput` 必须保持关闭。
//
// 开启后 Capacitor 会用自己的 `CapacitorWebView.onCreateInputConnection` 返回一个
// `BaseInputConnection`——系统提供的最小实现，不参与剪贴板事务。中文输入法（以及 Gboard
// 等）自带的「剪贴板 / 最近复制」按钮走的正是 InputConnection 的粘贴动作，于是点上去毫无
// 反应；而普通打字走的是另一条路径，所以症状表现为「能打字，只有输入法的剪贴板不能用」。
//
// 这个字段是脚手架带过来的默认值改动，android/ 侧没有任何代码依赖它。这条断言只读配置
// 文本（不需要设备、不需要构建），作用是让「顺手把它改回 true」在 CI 阶段就被挡住。
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const read = path => readFile(resolve(appRoot, path), 'utf8')

test('Capacitor 不做 input capture，否则输入法剪贴板失效', async () => {
  const config = await read('capacitor.config.ts')
  // 取 android 块，避免误匹配到注释里提到的同名说明或其他平台配置。
  const androidBlock = /android:\s*\{([\s\S]*?)\n\s*\}/u.exec(config)
  assert.ok(androidBlock, 'capacitor.config.ts 里找不到 android 配置块')

  const capture = /captureInput:\s*(true|false)/u.exec(androidBlock[1])
  assert.ok(capture, 'android 配置块里应显式写出 captureInput，便于这条断言与读者核对')

  // 显式 false 而不是「省略即默认」：默认值虽然就是 false，但写明以后，
  // 有人想改成 true 时必须在同一个位置动手，也就必然会看到这段说明。
  assert.equal(
    capture[1],
    'false',
    'captureInput 必须为 false：开启会让 WebView 改用 BaseInputConnection，输入法的剪贴板按钮随之失效',
  )
})

test('约束说明与实现放在同一处，避免只改配置却不知道原因', async () => {
  const config = await read('capacitor.config.ts')
  assert.match(
    config,
    /BaseInputConnection/u,
    'captureInput 附近应保留「为什么会失效」的说明（BaseInputConnection 不参与剪贴板事务）',
  )
})
