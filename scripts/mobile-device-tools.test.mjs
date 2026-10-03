import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import test from 'node:test'
import { apply } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/index.js'

// 在协议边界模拟设备，不执行设备命令、不连接外部网络。
function fixture(t, answer, extras = {}) {
  const previous = { port: process.env.DSH_DEVICE_BRIDGE_PORT, token: process.env.DSH_DEVICE_BRIDGE_TOKEN }
  process.env.DSH_DEVICE_BRIDGE_PORT = '3081'
  process.env.DSH_DEVICE_BRIDGE_TOKEN = randomBytes(32).toString('base64url')
  t.after(() => {
    for (const [key, value] of [['DSH_DEVICE_BRIDGE_PORT', previous.port], ['DSH_DEVICE_BRIDGE_TOKEN', previous.token]]) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  })
  const requests = []
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    const request = JSON.parse(options.body)
    requests.push(request)
    return Response.json(await answer(request))
  })
  const tools = new Map()
  let hook
  apply({ systemPrompt: { section() {} }, tools: { register(tool) { tools.set(tool.name, tool) } },
    on(_name, listener) { hook = listener }, ...extras })
  return { tools, requests, hook }
}
const ok = text => ({ ok: true, exitCode: 0, text, truncated: false, errorCode: null })
const virtualSession = '00000000-1111-2222-3333-444444444444'

test('副屏动作只发送会话与受控动作参数，不接受任意显示编号或主屏回退', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'tap', x: 10, y: 20 }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'tap', x: 10, y: 20 }) }])
  for (const request of [
    { sessionId: '0', action: 'back' },
    { sessionId: virtualSession, action: 'tap', x: -1, y: 0 },
    { sessionId: virtualSession, action: 'tap', x: 0.5, y: 0 },
    { sessionId: virtualSession, action: 'swipe', x: 0, y: 0, endX: 1, endY: 1, durationMs: 3000 },
  ]) await assert.rejects(tool.execute(request, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  assert.equal(f.requests.length, 1)
  await tool.execute({ sessionId: virtualSession, action: 'long_press', x: 10, y: 20, durationMs: 600 }, {})
  await tool.execute({ sessionId: virtualSession, action: 'keyevent', key: 'ENTER' }, {})
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello world' }, {})
  assert.deepEqual(f.requests.slice(1).map(request => JSON.parse(request.param).action), ['long_press', 'keyevent', 'text'])
  assert.deepEqual(await f.hook({ name: 'mobile_virtual_screen_action' }, () => ({ kind: 'allow' })), { kind: 'allow' })
  assert.deepEqual(await f.hook({ name: 'mobile_virtual_screen_action' }, () => ({ kind: 'deny', reason: '用户已禁用' })), { kind: 'deny', reason: '用户已禁用' })
})

test('副屏尚未启动时保留错误，不调用主屏工具', async t => {
  const f = fixture(t, () => ({ ...ok(''), ok: false, exitCode: 1, errorCode: 'VIRTUAL_SCREEN_NOT_STARTED' }))
  await assert.rejects(f.tools.get('mobile_virtual_screen_action').execute({ sessionId: virtualSession, action: 'back' }, {}), /VIRTUAL_SCREEN_NOT_STARTED/)
  assert.deepEqual(f.requests.map(request => request.command), ['virtualScreenAction'])
})

test('副屏截图保持真实附件格式，并说明缩放后的副屏操作坐标', async t => {
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).toString('base64')
  let saved
  const f = fixture(t, () => ok(JSON.stringify({ imageBase64: png, packageName: 'com.example.target', frameAtElapsedMs: 1234, frameReused: true })), {
    llm: { resolveModelInfo: async () => ({ inputModalities: ['image'] }) },
    attachments: { saveImage: async options => {
      saved = options
      return { attachmentId: 'virtual-test', mediaType: 'image/webp', bytes: 100, width: 363, height: 800, originalDimensions: { width: 726, height: 1600 } }
    } },
  })
  const tool = f.tools.get('mobile_virtual_screen_screenshot')
  const result = await tool.execute({ sessionId: virtualSession }, { agent: { session: { requestHeader: () => ({ config: { provider: 'test', model: 'vision' } }) } } })
  assert.deepEqual(f.requests, [{ command: 'virtualScreenCapture', param: JSON.stringify({ sessionId: virtualSession }) }])
  assert.equal(saved.name, 'android-virtual-screen.png')
  assert.equal(saved.mediaType, 'image/png')
  assert.equal(result.image.mediaType, 'image/webp')
  const text = tool.output.render({ sessionId: virtualSession }, result)[0].text
  assert.match(text, /mobile_virtual_screen_action/)
  assert.doesNotMatch(text, /mobile_device_tap/)
  assert.match(text, /横坐标按 726\/363、纵坐标按 1600\/800/)
  assert.match(text, /com.example.target/)
  assert.match(text, /frameAtElapsedMs=1234/)
  assert.match(text, /复用了缓存帧/)
})

test('Shell 原样传送多行中文脚本并保留非零退出码', async t => {
  const f = fixture(t, () => ({ ...ok('部分结果'), ok: false, exitCode: 7, errorCode: 'DEVICE_COMMAND_FAILED' }))
  const tool = f.tools.get('mobile_device_shell')
  const script = 'printf "中文\n"\nexit 7'
  const result = await tool.execute({ script }, {})
  assert.deepEqual(f.requests, [{ command: 'shell', param: script }])
  assert.deepEqual(result, { ok: false, exitCode: 7, output: '部分结果', truncated: false })
  assert.equal(tool.output.schema.properties.exitCode.type, 'integer')
  assert.match(tool.output.render({}, result)[0].text, /退出码：7/)
  assert.equal(tool.presentCall({ script }).rawInput, undefined)
})

test('关闭开关与超时保留准确错误码，不伪装成已执行', async t => {
  let code = 'DEVICE_SHELL_DISABLED'
  const f = fixture(t, () => ({ ok: false, exitCode: -1, text: '不可用', truncated: false, errorCode: code }))
  await assert.rejects(f.tools.get('mobile_device_shell').execute({ script: 'pwd' }, {}), /DEVICE_SHELL_DISABLED/)
  code = 'DEVICE_COMMAND_TIMEOUT'
  await assert.rejects(f.tools.get('mobile_device_shell').execute({ script: 'pwd' }, {}), /DEVICE_COMMAND_TIMEOUT/)
})

test('脚本按 UTF-8 字节计限并拒绝空白和协议控制字符', async t => {
  const f = fixture(t, () => ok(''))
  for (const script of [' ', '中'.repeat(5500), 'a\u0000b', 'a\rb']) {
    await assert.rejects(f.tools.get('mobile_device_shell').execute({ script }, {}), /DEVICE_COMMAND_INVALID/)
  }
  assert.equal(f.requests.length, 0)
})

test('设备授权后免逐次确认，宿主明确拒绝仍保持拒绝', async t => {
  const f = fixture(t, () => ok(JSON.stringify({ schemaVersion: 3, allowlistedAutomation: true, directDeviceOperations: true })))
  for (const name of ['mobile_device_tap', 'mobile_device_input_text', 'mobile_accessibility_action',
    'mobile_device_app_launch', 'mobile_device_file_write', 'mobile_device_file_upload', 'mobile_device_file_mkdir', 'mobile_device_shell']) {
    assert.deepEqual(await f.hook({ name }, () => ({ kind: 'allow' })), { kind: 'allow' })
  }
  const count = f.requests.length
  assert.deepEqual(await f.hook({ name: 'mobile_device_shell' }, () => ({ kind: 'deny', reason: '用户已禁用' })),
    { kind: 'deny', reason: '用户已禁用' })
  assert.equal(f.requests.length, count)
})

test('空 UI dump 回退到原生树并明确标识 JSON', async t => {
  const f = fixture(t, request => request.command === 'uiDump'
    ? { ok: false, exitCode: 5, text: '', truncated: false, errorCode: 'UI_DUMP_EMPTY' }
    : ok('{"nodes":[]}'))
  const result = await f.tools.get('mobile_device_ui_dump').execute({}, {})
  assert.match(result.output, /JSON，非 XML/)
  assert.deepEqual(f.requests.map(request => request.command), ['uiDump', 'accessibilityTree'])
})

test('后台任务只接受完整包名与固定查询类型', async t => {
  const f = fixture(t, () => ok(''))
  const tool = f.tools.get('mobile_device_background_tasks')
  await assert.rejects(tool.execute({ packageName: 'com.example;id', kind: 'services' }, {}), /DEVICE_COMMAND_INVALID/)
  await assert.rejects(tool.execute({ packageName: 'com.example', kind: 'shell' }, {}), /DEVICE_COMMAND_INVALID|invalid arguments/u)
  await tool.execute({ packageName: 'com.example', kind: 'processes' }, {})
  assert.deepEqual(f.requests, [{ command: 'backgroundTasks', param: JSON.stringify({ packageName: 'com.example', kind: 'processes' }) }])
})
