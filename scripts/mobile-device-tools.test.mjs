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
  const prompts = []
  let hook
  apply({ systemPrompt: { section(value) { prompts.push(value) } }, tools: { register(tool) { tools.set(tool.name, tool) } },
    on(_name, listener) { hook = listener }, ...extras })
  return { tools, requests, hook, prompts }
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

test('副屏动作允许中文文本，仍拒绝控制字符与超长输入', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'text', text: '你好，微信' }, {})
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'a'.repeat(512) }, {})
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param).text), ['你好，微信', 'a'.repeat(512)])
  for (const text of ['', 'a'.repeat(513), 'a\nb', 'a\u0000b', 5]) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'text', text }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 2)
})

test('副屏帧率配置只接受四种预览模式', async t => {
  const f = fixture(t, () => ok('{"previewMode":"realtime-30fps"}'))
  const tool = f.tools.get('mobile_virtual_screen_config')
  await tool.execute({ sessionId: virtualSession, previewMode: '30fps' }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'config', previewMode: '30fps' }) }])
  for (const previewMode of ['60', 'realtime', 'limited-fps', '']) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, previewMode }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 1)
})

test('副屏目标切换只接受完整包名，不发送显示编号', async t => {
  const f = fixture(t, () => ok('{"packageName":"com.tencent.mm"}'))
  const tool = f.tools.get('mobile_virtual_screen_target')
  await tool.execute({ sessionId: virtualSession, packageName: 'com.tencent.mm' }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'target', packageName: 'com.tencent.mm' }) }])
  for (const packageName of ['com.tencent.mm;id', 'com/tencent', 'com.tencent.mm.\u0000', 'A'.repeat(200), 7]) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, packageName }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 1)
})

test('副屏目标切换超时保留语义准确的新错误码，不伪装成副屏不可用', async t => {
  // 切换是「有界轮询确认」：超时必须原样报 VIRTUAL_SCREEN_TARGET_TIMEOUT，
  // 被改写成 VIRTUAL_SCREEN_UNAVAILABLE 会让模型以为副屏功能不存在，从而放弃重试。
  const f = fixture(t, () => ({ ok: false, exitCode: 1, text: '副屏请求未完成，请检查会话与目标应用状态', truncated: false, errorCode: 'VIRTUAL_SCREEN_TARGET_TIMEOUT' }))
  const tool = f.tools.get('mobile_virtual_screen_target')
  await assert.rejects(tool.execute({ sessionId: virtualSession, packageName: 'com.tencent.mm' }, {}), error => {
    assert.match(error.message, /VIRTUAL_SCREEN_TARGET_TIMEOUT/u)
    assert.doesNotMatch(error.message, /VIRTUAL_SCREEN_UNAVAILABLE/u)
    return true
  })
  assert.equal(f.requests.length, 1)
  // 工具描述与系统提示都要把这个码讲清楚，否则模型只会把它当成又一次「副屏不可用」。
  assert.match(tool.description, /VIRTUAL_SCREEN_TARGET_TIMEOUT/u)
  assert.match(tool.description, /仍然正常/u)
  assert.equal(f.prompts.length, 1)
  assert.match(f.prompts[0].text, /VIRTUAL_SCREEN_TARGET_TIMEOUT/u)
  assert.match(f.prompts[0].text, /不要当成副屏不可用/u)
})

test('副屏文本输入只在显式 true 时才带 submit，写入失败不会按回车', async t => {
  const f = fixture(t, () => ok('{"method":"SET_TEXT","chars":5,"submit":true,"steps":"无障碍直接写入成功"}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello', submit: true }, {})
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello', submit: false }, {})
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello' }, {})
  // 只认显式布尔量：缺省与 false 都不把 submit 发给设备端，字符串 'true' 这类模糊输入直接拒绝。
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param)), [
    { sessionId: virtualSession, action: 'text', text: 'hello', submit: true },
    { sessionId: virtualSession, action: 'text', text: 'hello', submit: false },
    { sessionId: virtualSession, action: 'text', text: 'hello' },
  ])
  for (const submit of ['true', 1, 0, null, {}]) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello', submit }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 3)
})

test('副屏文本写不进去时保留 VIRTUAL_SCREEN_TEXT_UNSUPPORTED，不伪装成副屏不可用', async t => {
  // 回退链（无障碍直接写入 → 聚焦后写入 → 剪贴板 → 纯 ASCII 按键兜底）全失败时才报这个码。
  // 被改写成 VIRTUAL_SCREEN_UNAVAILABLE 会让模型以为副屏功能不存在，从而放弃「在副屏上手动输入」这一步。
  const f = fixture(t, () => ({ ok: false, exitCode: 1, text: '副屏请求未完成，请检查会话与目标应用状态', truncated: false, errorCode: 'VIRTUAL_SCREEN_TEXT_UNSUPPORTED' }))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'text', text: '你好，微信' }, {}), error => {
    assert.match(error.message, /VIRTUAL_SCREEN_TEXT_UNSUPPORTED/u)
    assert.doesNotMatch(error.message, /VIRTUAL_SCREEN_UNAVAILABLE/u)
    return true
  })
  assert.equal(f.requests.length, 1)
  // 工具说明与系统提示都要讲清这个码的语义，否则模型只会当成又一次「副屏不可用」。
  // 回退链的逐级细节写在 text 参数说明与系统提示里，这里只钉住「码 + 该干什么」两句。
  assert.match(tool.description, /VIRTUAL_SCREEN_TEXT_UNSUPPORTED|文本输入/u)
  const { text, submit } = tool.parameters.properties
  assert.match(text.description, /VIRTUAL_SCREEN_TEXT_UNSUPPORTED/u)
  assert.match(text.description, /不是副屏不可用/u)
  assert.match(submit.description, /写入失败时不会按回车/u)
  assert.equal(f.prompts.length, 1)
  assert.match(f.prompts[0].text, /VIRTUAL_SCREEN_TEXT_UNSUPPORTED/u)
  assert.match(f.prompts[0].text, /不等于 VIRTUAL_SCREEN_UNAVAILABLE/u)
})

test('副屏节点树默认深度 4，只接受 1～8', async t => {
  const f = fixture(t, () => ok('{"available":false,"reason":"ACCESSIBILITY_DISABLED"}'))
  const tool = f.tools.get('mobile_virtual_screen_tree')
  await tool.execute({ sessionId: virtualSession }, {})
  await tool.execute({ sessionId: virtualSession, maxDepth: 8 }, {})
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param).maxDepth), [4, 8])
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param).action), ['tree', 'tree'])
  for (const maxDepth of [0, 9, 2.5]) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, maxDepth }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 2)
})

test('副屏手势只回传受控的 {x,y} 点列，点数、坐标与时长都在入口拦下', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'gesture', points: [{ x: 10, y: 20 }, { x: 300, y: 400 }], durationMs: 300 }, {})
  assert.deepEqual(f.requests, [{
    command: 'virtualScreenAction',
    param: JSON.stringify({ sessionId: virtualSession, action: 'gesture', points: [{ x: 10, y: 20 }, { x: 300, y: 400 }], durationMs: 300 }),
  }])
  // 省略 durationMs 时不替调用方补默认值：设备端自己取 300，两边只保留一个默认值来源。
  await tool.execute({ sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 1, y: 1 }] }, {})
  assert.equal(JSON.parse(f.requests[1].param).durationMs, undefined)
  for (const request of [
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }] },
    { sessionId: virtualSession, action: 'gesture', points: Array.from({ length: 65 }, () => ({ x: 0, y: 0 })) },
    { sessionId: virtualSession, action: 'gesture', points: 'x' },
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 0.5, y: 1 }] },
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: -1, y: 1 }] },
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 1, y: 1 }], durationMs: 49 },
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 1, y: 1 }], durationMs: 5001 },
    { sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 1, y: 1 }], durationMs: 300.5 },
  ]) await assert.rejects(tool.execute(request, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  assert.equal(f.requests.length, 2)
  assert.equal(tool.parameters.properties.points.type, 'array')
  assert.equal(tool.parameters.properties.points.items.properties.x.type, 'integer')
})

test('副屏触摸直传只放行 down/move/up/cancel，坐标仍受控', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  for (const phase of ['down', 'move', 'up', 'cancel']) {
    await tool.execute({ sessionId: virtualSession, action: 'touch', phase, x: 5, y: 6 }, {})
  }
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param)), [
    { sessionId: virtualSession, action: 'touch', phase: 'down', x: 5, y: 6 },
    { sessionId: virtualSession, action: 'touch', phase: 'move', x: 5, y: 6 },
    { sessionId: virtualSession, action: 'touch', phase: 'up', x: 5, y: 6 },
    { sessionId: virtualSession, action: 'touch', phase: 'cancel', x: 5, y: 6 },
  ])
  for (const request of [
    { sessionId: virtualSession, action: 'touch', phase: 'press', x: 5, y: 6 },
    { sessionId: virtualSession, action: 'touch', phase: 'down', x: 5.5, y: 6 },
    { sessionId: virtualSession, action: 'touch', phase: 'down', x: 1440, y: 0 },
  ]) await assert.rejects(tool.execute(request, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  assert.equal(f.requests.length, 4)
})

test('副屏启动应用三选一：组件、包名与白名单链接各走一种参数形态', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'launch', component: 'com.example.app/.MainActivity' }, {})
  await tool.execute({ sessionId: virtualSession, action: 'launch', package: 'com.example.app' }, {})
  await tool.execute({ sessionId: virtualSession, action: 'launch', uri: 'https://example.com/a?b=1' }, {})
  assert.deepEqual(f.requests.map(request => JSON.parse(request.param)), [
    { sessionId: virtualSession, action: 'launch', component: 'com.example.app/.MainActivity' },
    { sessionId: virtualSession, action: 'launch', package: 'com.example.app' },
    { sessionId: virtualSession, action: 'launch', uri: 'https://example.com/a?b=1' },
  ])
  for (const request of [
    { sessionId: virtualSession, action: 'launch' },
    { sessionId: virtualSession, action: 'launch', component: 'com.example.app/.MainActivity', package: 'com.example.app' },
    { sessionId: virtualSession, action: 'launch', package: 'com.example.app', uri: 'https://example.com' },
    { sessionId: virtualSession, action: 'launch', component: 'com.example.app' },
    { sessionId: virtualSession, action: 'launch', package: '1com.example' },
    { sessionId: virtualSession, action: 'launch', uri: 'file:///sdcard/secret' },
    { sessionId: virtualSession, action: 'launch', uri: 'https://example.com/a;id' },
    { sessionId: virtualSession, action: 'launch', uri: 'https://example.com/a b' },
    { sessionId: virtualSession, action: 'launch', uri: `https://example.com/${'a'.repeat(2048)}` },
  ]) await assert.rejects(tool.execute(request, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  assert.equal(f.requests.length, 3)
})

test('副屏 follow 只发会话；没有可跟随目标时保留 FOLLOW_NONE，不伪装成副屏不可用', async t => {
  let payload = ok('{"active":true}')
  const f = fixture(t, () => payload)
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'follow' }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'follow' }) }])
  // 「主屏没有可跟随的应用」是正常结论：改写成 VIRTUAL_SCREEN_UNAVAILABLE 会让模型以为副屏坏了。
  payload = { ok: false, exitCode: 1, text: '副屏请求未完成，请检查会话与目标应用状态', truncated: false, errorCode: 'VIRTUAL_SCREEN_FOLLOW_NONE' }
  await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'follow' }, {}), error => {
    assert.match(error.message, /VIRTUAL_SCREEN_FOLLOW_NONE/u)
    assert.doesNotMatch(error.message, /VIRTUAL_SCREEN_UNAVAILABLE/u)
    return true
  })
  const { action, phase } = tool.parameters.properties
  for (const name of ['touch', 'gesture', 'launch', 'follow']) assert.ok(action.enum.includes(name), name)
  assert.deepEqual(phase.enum, ['down', 'move', 'up', 'cancel'])
  // 说明面必须点明「在这块副屏上」与「拉回副屏」，否则模型会改用主屏工具。
  assert.match(tool.description, /launch 是「在这块副屏上」/u)
  assert.match(tool.description, /拉回副屏/u)
  assert.equal(f.prompts.length, 1)
  assert.match(f.prompts[0].text, /gesture/u)
  assert.match(f.prompts[0].text, /VIRTUAL_SCREEN_FOLLOW_NONE/u)
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
