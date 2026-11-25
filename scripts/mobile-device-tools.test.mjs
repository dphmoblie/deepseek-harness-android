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

test('AI 可主动启动副屏，但必须提供合法包名且复用已保存设置', async t => {
  const f = fixture(t, request => {
    assert.equal(request.command, 'virtualScreenStart')
    assert.deepEqual(JSON.parse(request.param), { packageName: 'com.example.app' })
    return ok('{"action":"start","state":"starting"}')
  })
  const tool = f.tools.get('mobile_virtual_screen_start')
  assert.ok(tool)
  await tool.execute({ packageName: 'com.example.app' }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenStart', param: JSON.stringify({ packageName: 'com.example.app' }) }])
  for (const packageName of ['', 'com', 'Example App', '1com.example', 'com.example/Activity']) {
    await assert.rejects(tool.execute({ packageName }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 1)
})

test('统一设备能力工具只读当前快照并透传受控桥命令', async t => {
  const payload = JSON.stringify({ atMs: 1700000000000, sdkInt: 34, capabilities: [] })
  const f = fixture(t, request => {
    assert.equal(request.command, 'deviceCapabilities')
    assert.equal(request.param, '')
    return ok(payload)
  })
  const tool = f.tools.get('mobile_device_capabilities')
  assert.ok(tool)
  const result = await tool.execute({}, {})
  assert.equal(result.output, payload)
})

test('副屏动作只发送会话与受控动作参数，不接受任意显示编号或主屏回退', async t => {
  const f = fixture(t, () => ok('{"active":true}'))
  const tool = f.tools.get('mobile_virtual_screen_action')
  await tool.execute({ sessionId: virtualSession, action: 'tap', x: 10, y: 20 }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'tap', x: 10, y: 20, settleMs: 3000 }) }])
  for (const request of [
    { sessionId: '0', action: 'back' },
    { sessionId: virtualSession, action: 'tap', x: -1, y: 0 },
    { sessionId: virtualSession, action: 'tap', x: 0.5, y: 0 },
    { sessionId: virtualSession, action: 'tap', x: 10, y: 20, settleMs: -1 },
    { sessionId: virtualSession, action: 'tap', x: 10, y: 20, settleMs: 10001 },
    { sessionId: virtualSession, action: 'tap', x: 10, y: 20, settleMs: 250.5 },
    { sessionId: virtualSession, action: 'text', text: 'hi', settleMs: 500 },
    { sessionId: virtualSession, action: 'swipe', x: 0, y: 0, endX: 1, endY: 1, durationMs: 3000 },
  ]) await assert.rejects(tool.execute(request, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  assert.equal(f.requests.length, 1)
  await tool.execute({ sessionId: virtualSession, action: 'long_press', x: 10, y: 20, durationMs: 600, settleMs: 8000 }, {})
  await tool.execute({ sessionId: virtualSession, action: 'keyevent', key: 'ENTER', settleMs: 0 }, {})
  await tool.execute({ sessionId: virtualSession, action: 'text', text: 'hello world' }, {})
  assert.deepEqual(f.requests.slice(1).map(request => JSON.parse(request.param).action), ['long_press', 'keyevent', 'text'])
  assert.equal(JSON.parse(f.requests[1].param).settleMs, 8000)
  assert.equal(JSON.parse(f.requests[2].param).settleMs, 0)
  assert.equal(JSON.parse(f.requests[3].param).settleMs, undefined)
  assert.deepEqual(await f.hook({ name: 'mobile_virtual_screen_action' }, () => ({ kind: 'allow' })), { kind: 'allow' })
  assert.deepEqual(await f.hook({ name: 'mobile_virtual_screen_action' }, () => ({ kind: 'deny', reason: '用户已禁用' })), { kind: 'deny', reason: '用户已禁用' })
})

test('副屏尚未启动时保留错误，不调用主屏工具', async t => {
  const f = fixture(t, () => ({ ...ok(''), ok: false, exitCode: 1, errorCode: 'VIRTUAL_SCREEN_NOT_STARTED' }))
  await assert.rejects(f.tools.get('mobile_virtual_screen_action').execute({ sessionId: virtualSession, action: 'back' }, {}), /VIRTUAL_SCREEN_NOT_STARTED/)
  assert.deepEqual(f.requests.map(request => request.command), ['virtualScreenAction'])
})

test('副屏错误给固定恢复建议，不转发设备原文或自动重放动作', async t => {
  let code = 'VIRTUAL_SCREEN_BUSY'
  const f = fixture(t, () => ({ ...ok('DEVICE_PRIVATE_TEXT_DO_NOT_FORWARD'), ok: false, exitCode: 1, errorCode: code }))
  const tool = f.tools.get('mobile_virtual_screen_action')
  for (const [errorCode, advice] of [
    ['VIRTUAL_SCREEN_BUSY', /短暂等待后重新观察/],
    ['VIRTUAL_SCREEN_STALE_FRAME', /重试一次观察/],
    ['VIRTUAL_SCREEN_TARGET_LEFT', /不要操作主屏/],
    ['VIRTUAL_SCREEN_TARGET_TIMEOUT', /不要直接重复启动/],
    ['VIRTUAL_SCREEN_STOPPED', /当前有效 sessionId/],
    ['VIRTUAL_SCREEN_SESSION_DEAD', /原生入口重新开始/],
    ['VIRTUAL_SCREEN_TEXT_UNSUPPORTED', /可能已有部分写入/],
    ['VIRTUAL_SCREEN_INJECTION_FAILED', /不要直接重复补点/],
  ]) {
    code = errorCode
    const before = f.requests.length
    await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'text', text: 'LOCAL_INPUT_DO_NOT_FORWARD' }, {}), error => {
      assert.ok(error.message.startsWith(`${errorCode}:`))
      assert.match(error.message, advice)
      assert.doesNotMatch(error.message, /DEVICE_PRIVATE_TEXT_DO_NOT_FORWARD|LOCAL_INPUT_DO_NOT_FORWARD/)
      return true
    })
    assert.equal(f.requests.length, before + 1)
  }
  assert.ok(f.requests.every(request => request.command === 'virtualScreenAction'))
  code = 'VIRTUAL_SCREEN_UNKNOWN'
  await assert.rejects(tool.execute({ sessionId: virtualSession, action: 'back' }, {}), { message: code })
})

test('任务台账的错误给固定恢复建议，不转发设备原文也不自动重放', async t => {
  let code = 'AUTOMATION_TASK_TARGET_LEFT'
  const f = fixture(t, () => ({ ...ok('DEVICE_PRIVATE_TEXT_DO_NOT_FORWARD'), ok: false, exitCode: 1, errorCode: code }))
  const tool = f.tools.get('mobile_device_task')
  for (const [errorCode, advice] of [
    ['AUTOMATION_TASK_TARGET_LEFT', /重新读控件树/],
    ['AUTOMATION_TASK_TARGET_STALE', /新指纹/],
    ['AUTOMATION_TASK_NOT_VERIFIED', /不要重发/],
    ['AUTOMATION_TASK_TIMEOUT', /先确认现场/],
    ['AUTOMATION_TASK_ATTEMPTS_EXHAUSTED', /不要再加重试/],
    ['AUTOMATION_TASK_NEEDS_CHECK', /判定不了/],
    ['AUTOMATION_TASK_BUSY', /不要同时开第二个任务/],
    ['AUTOMATION_TASK_RESUME_REQUIRED', /不要重复 resume/],
    ['AUTOMATION_TASK_MANUAL_PREEMPTED', /手动抢占/],
    ['AUTOMATION_TASK_SERVICE_DISCONNECTED', /请用户到系统设置里开启/],
    ['AUTOMATION_TASK_PARAM_INVALID', /按工具参数表重写/],
  ]) {
    code = errorCode
    const before = f.requests.length
    await assert.rejects(tool.execute({ op: 'gate', step: 's1' }, {}), error => {
      assert.ok(error.message.startsWith(`${errorCode}:`))
      assert.match(error.message, advice)
      assert.doesNotMatch(error.message, /DEVICE_PRIVATE_TEXT_DO_NOT_FORWARD/)
      return true
    })
    assert.equal(f.requests.length, before + 1)
  }
  assert.ok(f.requests.every(request => request.command === 'automationTask'))
  code = 'AUTOMATION_TASK_UNKNOWN'
  await assert.rejects(tool.execute({ op: 'status' }, {}), { message: code })
})

test('副屏流程按需选择树或截图，但保留同屏观察与动作后验证', t => {
  const f = fixture(t, () => ok('{}'))
  const prompt = f.prompts[0].text
  assert.match(prompt, /For main-screen operations only/)
  assert.match(prompt, /must not be used to observe a virtual-screen task/)
  assert.match(prompt, /首次读取 mobile_virtual_screen_state/)
  assert.match(prompt, /不必每步重复查状态/)
  assert.match(prompt, /不要无条件同时读取树和截图/)
  assert.match(prompt, /STOPPED 或 SESSION_DEAD 时重新取状态，不复用旧 sessionId/)
  assert.match(prompt, /动作完成后重新观察验证/)
  assert.match(prompt, /敏感窗口整棵拒绝，不能用截图绕过/)
  assert.match(prompt, /严禁沿用选中前的旧坐标/)
  assert.match(prompt, /单选圆点/)
  assert.match(prompt, /settle\.frameAfterInput=false/)
  const action = f.tools.get('mobile_virtual_screen_action').description
  assert.match(action, /同一副屏的最近一次观察/)
  assert.match(action, /动作后用树或截图验证结果/)
  assert.match(action, /settleMs=3000/)
  assert.doesNotMatch(action, /动作后重新截图/)
  assert.match(f.tools.get('mobile_virtual_screen_target').description, /无需再查 state/)
  assert.match(f.tools.get('mobile_virtual_screen_tree').description, /不调用主屏无障碍动作/)
  assert.match(f.tools.get('mobile_virtual_screen_tree').description, /checked\/selected/)
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
  assert.match(text, /不.*反复截图/)
  assert.match(text, /不能仅凭该帧确认刚才的动作已完成/)
})

test('旧版副屏截图缺少帧元数据时不宣称拿到了新帧', t => {
  const f = fixture(t, () => ok('{}'))
  const tool = f.tools.get('mobile_virtual_screen_screenshot')
  const image = { attachmentId: 'legacy-frame', mediaType: 'image/png', bytes: 8, width: 720, height: 1600 }
  const text = tool.output.render({ sessionId: virtualSession }, { ok: true, image })[0].text
  assert.match(text, /未提供帧新鲜度信息/)
  assert.doesNotMatch(text, /取得了新帧/)
  assert.match(tool.output.render({ sessionId: virtualSession }, { ok: true, image: { ...image, frameReused: false } })[0].text, /取得了新帧/)
  const staleBeforeInput = tool.output.render(
    { sessionId: virtualSession },
    { ok: true, image: { ...image, frameReused: false, frameAtElapsedMs: 1000, lastInputAtElapsedMs: 2500, frameBeforeLastInput: true } },
  )[0].text
  assert.match(staleBeforeInput, /采集于最近一次输入之前/)
  assert.match(staleBeforeInput, /不要直接重复补点/)
  assert.doesNotMatch(staleBeforeInput, /本次取得了新帧/)
})

test('副屏截图支持可选 waitMs，副屏动作和状态回执渲染清晰提示', async t => {
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).toString('base64')
  const f = fixture(t, req => {
    if (req.command === 'virtualScreenCapture') {
      return ok(JSON.stringify({
        imageBase64: png, packageName: 'com.example.target',
        frameAtElapsedMs: 5200, frameReused: false, lastInputAtElapsedMs: 4900, frameBeforeLastInput: false,
      }))
    }
    return ok('{}')
  }, {
    llm: { resolveModelInfo: async () => ({ inputModalities: ['image'] }) },
    attachments: { saveImage: async () => ({ attachmentId: 'v-wait', mediaType: 'image/png', bytes: 10, width: 720, height: 1600 }) },
  })
  const shot = f.tools.get('mobile_virtual_screen_screenshot')
  const shotRes = await shot.execute({ sessionId: virtualSession, waitMs: 7500 }, { agent: { session: { requestHeader: () => ({ config: { provider: 'p', model: 'm' } }) } } })
  assert.deepEqual(f.requests, [{ command: 'virtualScreenCapture', param: JSON.stringify({ sessionId: virtualSession, waitMs: 7500 }) }])
  assert.equal(shotRes.image.frameBeforeLastInput, false)
  assert.equal(shotRes.image.lastInputAtElapsedMs, 4900)
  for (const waitMs of [-1, 10001, 500.5, '5000']) {
    await assert.rejects(shot.execute({ sessionId: virtualSession, waitMs }, { agent: { session: { requestHeader: () => ({ config: { provider: 'p', model: 'm' } }) } } }), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }

  const actionTool = f.tools.get('mobile_virtual_screen_action')
  const settledText = actionTool.output.render({}, { output: JSON.stringify({ settle: { frameAfterInput: true, quiet: true, waitedMs: 450, firstFrameDelayMs: 120 } }) })[0].text
  assert.match(settledText, /120 ms 内出现新帧且画面已稳定/)
  const missedText = actionTool.output.render({}, { output: JSON.stringify({ settle: { frameAfterInput: false, quiet: false, waitedMs: 3000 } }) })[0].text
  assert.match(missedText, /frameAfterInput=false/)
  assert.match(missedText, /请勿立刻原地补点/)

  const stateTool = f.tools.get('mobile_virtual_screen_state')
  const stateText = stateTool.output.render({}, { output: JSON.stringify({ virtualForegroundPackage: 'com.quiz.app', autoFollow: 'off', frameAtElapsedMs: 1000, lastInputAtElapsedMs: 3000, frameBeforeLastInput: true, inputFrameMissed: 2 }) })[0].text
  assert.match(stateText, /尚未采集到新画面/)
  assert.match(stateText, /2 次输入在响应窗口内未等到画面变化/)
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

test('副屏帧率配置接受六种预览模式', async t => {
  const f = fixture(t, () => ok('{"previewMode":"realtime-30fps"}'))
  const tool = f.tools.get('mobile_virtual_screen_config')
  await tool.execute({ sessionId: virtualSession, previewMode: '30fps' }, {})
  await tool.execute({ sessionId: virtualSession, previewMode: '120fps' }, {})
  await tool.execute({ sessionId: virtualSession, previewMode: '185fps' }, {})
  assert.deepEqual(f.requests, [
    { command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'config', previewMode: '30fps' }) },
    { command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'config', previewMode: '120fps' }) },
    { command: 'virtualScreenAction', param: JSON.stringify({ sessionId: virtualSession, action: 'config', previewMode: '185fps' }) },
  ])
  for (const previewMode of ['60', 'realtime', 'limited-fps', '']) {
    await assert.rejects(tool.execute({ sessionId: virtualSession, previewMode }, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 3)
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
    param: JSON.stringify({ sessionId: virtualSession, action: 'gesture', points: [{ x: 10, y: 20 }, { x: 300, y: 400 }], durationMs: 300, settleMs: 3000 }),
  }])
  // 省略 durationMs 时不替调用方补默认值：设备端自己取 300，两边只保留一个默认值来源。
  await tool.execute({ sessionId: virtualSession, action: 'gesture', points: [{ x: 0, y: 0 }, { x: 1, y: 1 }], settleMs: 0 }, {})
  assert.equal(JSON.parse(f.requests[1].param).durationMs, undefined)
  assert.equal(JSON.parse(f.requests[1].param).settleMs, 0)
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

test('没有 Shizuku 不等于没有 Shell：说明面与受控码都要写出两条通道', async t => {
  const f = fixture(t, () => ok(''))
  const tool = f.tools.get('mobile_device_shell')
  // 说明面必须同时给出根通道与 Shizuku 通道，并点名能力清单字段；
  // 旧文案只说「需要 AI Shell + Shizuku」，模型在没有 Shizuku 的机器上会直接放弃。
  assert.match(tool.description, /根通道/u)
  assert.match(tool.description, /不需要 Shizuku/u)
  assert.match(tool.description, /rootEngineStatus/u)
  const prompt = f.prompts[0].text
  assert.match(prompt, /根通道/u)
  assert.match(prompt, /rootEngineEnabled/u)
  // 两条通道都不通时，受控码必须带可执行的下一步，而不是只丢一个码。
  const disabled = fixture(t, () => ({ ok: false, exitCode: -1, text: '', truncated: false, errorCode: 'DEVICE_SHELL_DISABLED' }))
  await assert.rejects(disabled.tools.get('mobile_device_shell').execute({ script: 'pwd' }, {}), error => {
    assert.match(error.message, /DEVICE_SHELL_DISABLED/u)
    assert.match(error.message, /根通道/u)
    assert.match(error.message, /AI Shell/u)
    return true
  })
})

test('原生受控失败的中文说明随码一起回给模型', async t => {
  const f = fixture(t, () => ({
    ok: false, exitCode: -1, text: '请先开启 AI Shell，或授权可用的根通道', truncated: false,
    errorCode: 'DEVICE_COMMAND_PROTOCOL_ERROR',
  }))
  await assert.rejects(f.tools.get('mobile_device_shell').execute({ script: 'pwd' }, {}), error => {
    assert.match(error.message, /^DEVICE_COMMAND_PROTOCOL_ERROR: /u)
    assert.match(error.message, /授权可用的根通道/u)
    return true
  })
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

test('任务台账工具按 op 拼参数，resume 把步骤标识映射成 steps', async t => {
  const f = fixture(t, () => ok('{"ok":true}'))
  const tool = f.tools.get('mobile_device_task')
  // 这个工具只记账、不动手：它不该多问一次审批，动作仍由各自的工具单独走闸门。
  assert.deepEqual(await f.hook({ name: 'mobile_device_task' }, () => ({ kind: 'allow' })), { kind: 'allow' })

  const steps = [{ id: 's1', action: 'click', target: 'com.example:id/ok' }]
  await tool.execute({ op: 'plan', taskId: 't1', packageName: 'com.example', steps }, {})
  await tool.execute({ op: 'gate', step: 's1' }, {})
  await tool.execute({ op: 'record', step: 's1', status: 'ok', code: 'AUTOMATION_ACTION_PERFORMED', verified: true }, {})
  await tool.execute({ op: 'resume', resumeSteps: ['s1', 's2'] }, {})
  await tool.execute({ op: 'status' }, {})

  assert.deepEqual(f.requests.map(request => [request.command, JSON.parse(request.param)]), [
    ['automationTask', { op: 'plan', taskId: 't1', packageName: 'com.example', steps }],
    ['automationTask', { op: 'gate', step: 's1' }],
    ['automationTask', { op: 'record', step: 's1', status: 'ok', code: 'AUTOMATION_ACTION_PERFORMED', verified: true }],
    ['automationTask', { op: 'resume', steps: ['s1', 's2'] }],
    ['automationTask', { op: 'status' }],
  ])
  assert.match(tool.description, /不执行任何动作/u)
})

test('副屏直通 Shell 默认沿用会话副屏并原样传送脚本', async t => {
  const f = fixture(t, () => ok('已点击副屏'))
  const tool = f.tools.get('mobile_virtual_screen_shell')
  const script = 'input -d "$DISPLAY_ID" tap 622 880\nam start --display "$DISPLAY_ID" -a android.intent.action.VIEW -d https://example.com'
  const result = await tool.execute({ script }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenShell', param: JSON.stringify({ script }) }])
  assert.deepEqual(result, { ok: true, exitCode: 0, output: '已点击副屏', truncated: false })
  assert.deepEqual(Object.keys(tool.output.schema.properties), ['ok', 'output', 'truncated', 'exitCode'])
  assert.equal(tool.output.schema.properties.exitCode.type, 'integer')
  assert.match(tool.output.render({}, { ...result, exitCode: 7 })[0].text, /副屏 Shell 退出码：7/u)
  assert.match(tool.parameters.properties.displayId.description, /主屏/u)
  assert.match(tool.description, /DISPLAY_ID/u)
  assert.equal(tool.presentCall({ script }).rawInput, undefined)
})

test('副屏直通 Shell 只在显式传入时带上 displayId', async t => {
  const f = fixture(t, () => ok(''))
  await f.tools.get('mobile_virtual_screen_shell').execute({ script: 'pwd', displayId: 38 }, {})
  assert.deepEqual(f.requests, [{ command: 'virtualScreenShell', param: JSON.stringify({ script: 'pwd', displayId: 38 }) }])
})

test('副屏直通 Shell 复用脚本校验并拒绝非法 displayId', async t => {
  const f = fixture(t, () => ok(''))
  const tool = f.tools.get('mobile_virtual_screen_shell')
  for (const args of [{}, { script: ' ' }, { script: '中'.repeat(5500) }, { script: 'a\u0000b' }, { script: 'a\rb' }]) {
    await assert.rejects(tool.execute(args, {}), /DEVICE_COMMAND_INVALID|invalid arguments/u)
  }
  for (const displayId of [-1, '38', 1.5, null]) {
    await assert.rejects(tool.execute({ script: 'pwd', displayId }, {}), /DEVICE_COMMAND_INVALID|invalid arguments/u)
  }
  assert.equal(f.requests.length, 0)
})

test('副屏直通 Shell 保留设备侧错误码，不伪装成已执行', async t => {
  let code = 'VIRTUAL_SCREEN_DISPLAY_INVALID'
  const f = fixture(t, () => ({ ok: false, exitCode: -1, text: '主屏不接受本通道', truncated: false, errorCode: code }))
  const tool = f.tools.get('mobile_virtual_screen_shell')
  await assert.rejects(tool.execute({ script: 'pwd', displayId: 0 }, {}), /VIRTUAL_SCREEN_DISPLAY_INVALID/u)
  code = 'VIRTUAL_SCREEN_UNAVAILABLE'
  await assert.rejects(tool.execute({ script: 'pwd' }, {}), /VIRTUAL_SCREEN_UNAVAILABLE/u)
  code = 'DEVICE_SHELL_DISABLED'
  await assert.rejects(tool.execute({ script: 'pwd' }, {}), /DEVICE_SHELL_DISABLED/u)
})

test('副屏直通 Shell 不进入逐次确认钩子，提示文案说明通道用法', async t => {
  const f = fixture(t, () => ok(''))
  assert.deepEqual(await f.hook({ name: 'mobile_virtual_screen_shell' }, () => ({ kind: 'allow' })), { kind: 'allow' })
  assert.match(f.prompts[0].text, /mobile_virtual_screen_shell/u)
  assert.match(f.prompts[0].text, /DISPLAY_ID/u)
  assert.match(f.prompts[0].text, /VIRTUAL_SCREEN_DISPLAY_INVALID/u)
})

test('老运行时没有 AbortSignal.any 时设备工具仍然可用', async t => {
  // 模拟 Node < 20.3 的访客运行时：AbortSignal.any 不存在，但工具调用带着 exec.signal。
  const original = Object.getOwnPropertyDescriptor(AbortSignal, 'any')
  delete AbortSignal.any
  t.after(() => {
    if (original) Object.defineProperty(AbortSignal, 'any', original)
  })
  const payload = JSON.stringify({ atMs: 1700000000000, sdkInt: 34, capabilities: [] })
  const f = fixture(t, request => {
    assert.equal(request.command, 'deviceCapabilities')
    return ok(payload)
  })
  const controller = new AbortController()
  const result = await f.tools.get('mobile_device_capabilities').execute({}, { signal: controller.signal })
  assert.equal(f.requests.length, 1)
  assert.equal(JSON.parse(result.output).sdkInt, 34)
  assert.equal(typeof AbortSignal.any, 'undefined')
})

test('回退组合信号仍然跟着会话取消中止请求', async t => {
  const original = Object.getOwnPropertyDescriptor(AbortSignal, 'any')
  delete AbortSignal.any
  t.after(() => {
    if (original) Object.defineProperty(AbortSignal, 'any', original)
  })
  const seen = []
  const previous = { port: process.env.DSH_DEVICE_BRIDGE_PORT, token: process.env.DSH_DEVICE_BRIDGE_TOKEN }
  process.env.DSH_DEVICE_BRIDGE_PORT = '3081'
  process.env.DSH_DEVICE_BRIDGE_TOKEN = randomBytes(32).toString('base64url')
  t.after(() => {
    for (const [key, value] of [['port', previous.port], ['token', previous.token]].map(([k, v]) => [`DSH_DEVICE_BRIDGE_${k.toUpperCase()}`, v])) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  })
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    seen.push(options.signal)
    return Response.json({ ok: true, exitCode: 0, text: '{}', truncated: false, errorCode: null })
  })
  const tools = new Map()
  apply({ systemPrompt: { section() {} }, tools: { register(tool) { tools.set(tool.name, tool) } }, on() {} })
  const controller = new AbortController()
  await tools.get('mobile_device_capabilities').execute({}, { signal: controller.signal })
  assert.equal(seen.length, 1)
  assert.equal(seen[0].aborted, false)
  controller.abort()
  assert.equal(seen[0].aborted, true)
})

test('AI 主动启动副屏支持可选同步等待、切换与悬浮窗预览参数，并渲染就绪摘要', async t => {
  const readyPayload = JSON.stringify({
    action: 'start',
    state: 'active',
    ready: true,
    sessionId: virtualSession,
    displayId: 7,
    targetPackage: 'com.example.quiz',
    treeSupported: true,
    elementCount: 12,
  })
  const f = fixture(t, () => ok(readyPayload))
  const start = f.tools.get('mobile_virtual_screen_start')
  const result = await start.execute({
    packageName: 'com.example.quiz',
    waitReadyMs: 5000,
    switchIfActive: true,
    showOverlay: true,
  }, {})
  assert.deepEqual(JSON.parse(f.requests[0].param), {
    packageName: 'com.example.quiz',
    waitReadyMs: 5000,
    switchIfActive: true,
    showOverlay: true,
  })
  const rendered = start.output.render({}, result)[0].text
  assert.match(rendered, /副屏已就绪.*displayId=7/u)
  assert.match(rendered, /首屏 12 个语义控件/u)
  for (const invalid of [
    { packageName: 'com.example.quiz', waitReadyMs: -1 },
    { packageName: 'com.example.quiz', waitReadyMs: 15001 },
    { packageName: 'com.example.quiz', switchIfActive: 'yes' },
    { packageName: 'com.example.quiz', showOverlay: 1 },
  ]) {
    await assert.rejects(start.execute(invalid, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }
})

test('副屏无 OCR 语义树 compact 模式、语义控件动作与内联 Diff 渲染契约', async t => {
  const actionPayload = JSON.stringify({
    ok: true,
    action: 'node_click',
    method: 'accessibility_node_click',
    observe: {
      source: 'accessibility_diff',
      changed: true,
      kind: 'delta',
      addedCount: 1,
      removedCount: 0,
      updatedCount: 2,
    },
  })
  const f = fixture(t, () => ok(actionPayload))
  const tree = f.tools.get('mobile_virtual_screen_tree')
  await tree.execute({ sessionId: virtualSession, compact: true }, {})
  assert.deepEqual(JSON.parse(f.requests[0].param), {
    sessionId: virtualSession,
    action: 'tree',
    maxDepth: 4,
    compact: true,
  })

  const action = f.tools.get('mobile_virtual_screen_action')
  const res = await action.execute({
    sessionId: virtualSession,
    action: 'node_click',
    textContains: 'D. 无需另行告知',
    matchIndex: 0,
    observe: true,
  }, {})
  assert.deepEqual(JSON.parse(f.requests[1].param), {
    sessionId: virtualSession,
    action: 'node_click',
    textContains: 'D. 无需另行告知',
    matchIndex: 0,
    observe: true,
  })
  const rendered = action.output.render({}, res)[0].text
  assert.match(rendered, /无障碍结构 Diff：新增 1、移除 0、变更 2 处/u)

  // node_scroll 可以不带选择器（滚动首个可滚动容器），也支持 direction。
  await action.execute({ sessionId: virtualSession, action: 'node_scroll', direction: 'forward' }, {})
  assert.deepEqual(JSON.parse(f.requests[2].param), {
    sessionId: virtualSession,
    action: 'node_scroll',
    direction: 'forward',
  })

  // node_click 必须提供至少一个选择器；非法参数一律拒绝。
  for (const invalid of [
    { sessionId: virtualSession, action: 'node_click' },
    { sessionId: virtualSession, action: 'node_click', text: '   ' },
    { sessionId: virtualSession, action: 'node_click', text: 'ok', matchIndex: -1 },
    { sessionId: virtualSession, action: 'node_scroll', direction: 'sideways' },
  ]) {
    await assert.rejects(action.execute(invalid, {}), /VIRTUAL_SCREEN_INVALID|invalid arguments/u)
  }

  // 无障碍关闭时的 tile_diff 同样直接渲染零 OCR 反馈摘要。
  const tileRendered = action.output.render({}, {
    ok: true,
    output: JSON.stringify({
      observe: { source: 'tile_diff', changed: true, kind: 'delta', changeRatio: 0.125 },
    }),
    truncated: false,
  })[0].text
  assert.match(tileRendered, /tile_diff，变化占比 12\.5%/u)
})

test('Shizuku Binder 与用户服务连接失败全部携带中文恢复提示，不向模型裸抛错误码', async t => {
  for (const code of [
    'SHIZUKU_BINDER_TIMEOUT',
    'SHIZUKU_BINDER_UNAVAILABLE',
    'SHIZUKU_BINDER_INTERRUPTED',
    'SHIZUKU_SERVICE_TIMEOUT',
    'SHIZUKU_SERVICE_FAILED',
    'SHIZUKU_SERVICE_INTERRUPTED',
  ]) {
    const f = fixture(t, () => ({
      ok: false,
      exitCode: 1,
      text: '未能连接 Shizuku，请确认服务已启动',
      truncated: false,
      errorCode: code,
    }))
    const info = f.tools.get('mobile_device_info')
    await assert.rejects(
      info.execute({}, {}),
      error => {
        assert.ok(error.message.startsWith(`${code}: `), `${code} 缺少恢复提示，实际：${error.message}`)
        assert.match(error.message, /不要.*重试|根通道|无障碍/u)
        return true
      },
    )
  }
})

test('高风险根命令（Tier 1）在 ask 策略下调取 dsh 官方审批，日志原因脱敏且界面展示完整命令', async t => {
  const challengeId = '11111111-2222-3333-4444-555555555555'
  const approvalCalls = []
  const approval = {
    effectivePolicy: () => 'ask',
    async request(req) {
      approvalCalls.push(req)
      return 'allowed-once'
    },
  }
  const f = fixture(t, request => {
    if (request.command === 'shell') {
      return {
        ok: false,
        exitCode: 1,
        truncated: false,
        errorCode: 'ROOT_APPROVAL_REQUIRED',
        text: JSON.stringify({
          schemaVersion: 1,
          channel: 'root',
          tier: 'elevated',
          route: 'dsh_approval',
          requiresHostConfirm: false,
          actions: ['挂载或卸载'],
          snippet: 'mount -o remount,rw /data',
          challengeId,
          guardProfile: 'strict',
          degraded: true,
          degradations: ['NAMESPACE_UNAVAILABLE'],
        }),
      }
    }
    assert.equal(request.command, 'shellApproved')
    return ok('mounted-ok')
  }, { get: name => (name === 'approval' ? approval : undefined) })

  const script = 'mount -o remount,rw /data # \u202Esecret-token'
  const fakeAgent = { session: { id: 'sess-1' } }
  const res = await f.tools.get('mobile_device_shell').execute(
    { script, justification: '挂载数据分区以调试日志' },
    { agent: fakeAgent, callId: 'call-1' },
  )

  assert.equal(res.output, 'mounted-ok')
  assert.equal(approvalCalls.length, 1)
  assert.equal(approvalCalls[0].toolName, 'mobile_device_shell')
  assert.equal(approvalCalls[0].callId, 'call-1')
  // reason 会写入会话持久化审计日志，不得含明文脚本。
  assert.doesNotMatch(approvalCalls[0].reason, /secret-token/u)
  assert.match(approvalCalls[0].reason, /tier=elevated/u)
  // displayReason 只展示给前端审批面板：必须含脚本预览（且剔除 BiDi 欺骗字符）、降级提示与 AI 说明。
  assert.match(approvalCalls[0].displayReason.zh, /mount -o remount,rw \/data/u)
  assert.match(approvalCalls[0].displayReason.zh, /挂载数据分区以调试日志/u)
  assert.match(approvalCalls[0].displayReason.zh, /沙箱降级/u)
  assert.equal(approvalCalls[0].displayReason.zh.includes('\u202E'), false)
  assert.deepEqual(JSON.parse(f.requests[1].param), {
    script,
    challengeId,
    approvalMode: 'dsh_approved',
    justification: '挂载数据分区以调试日志',
  })
})

test('高风险根命令被用户拒绝/取消/无应答通道时立即终止，绝不提交 shellApproved', async t => {
  const challengePayload = JSON.stringify({
    schemaVersion: 1,
    channel: 'root',
    tier: 'elevated',
    route: 'dsh_approval',
    requiresHostConfirm: false,
    actions: ['修改 Android 系统属性'],
    snippet: 'setprop foo bar',
    challengeId: '22222222-2222-3333-4444-555555555555',
    guardProfile: 'strict',
    degraded: false,
    degradations: [],
  })
  for (const [outcome, expectedCode] of [
    ['rejected', /ROOT_APPROVAL_REJECTED/u],
    ['cancelled', /ROOT_APPROVAL_CANCELLED/u],
    ['unavailable', /ROOT_APPROVAL_UNAVAILABLE/u],
  ]) {
    const f = fixture(
      t,
      () => ({ ok: false, exitCode: 1, truncated: false, errorCode: 'ROOT_APPROVAL_REQUIRED', text: challengePayload }),
      { get: name => (name === 'approval' ? { effectivePolicy: () => 'ask', request: async () => outcome } : undefined) },
    )
    await assert.rejects(
      f.tools.get('mobile_device_shell').execute({ script: 'setprop foo bar' }, { agent: { session: {} } }),
      expectedCode,
    )
    assert.deepEqual(f.requests.map(r => r.command), ['shell'])
  }
})

test('dsh 完全权限（approval=never）下：Tier 1 尊重 dsh 直接放行，Tier 3 跳过静默拒绝直达 App 警告页', async t => {
  let approvalAsked = 0
  const approval = {
    effectivePolicy: () => 'never',
    async request() {
      approvalAsked += 1
      return 'rejected'
    },
  }
  const tier1Challenge = '33333333-2222-3333-4444-555555555555'
  const tier3Challenge = '44444444-2222-3333-4444-555555555555'
  const f = fixture(t, request => {
    if (request.command === 'shell' && request.param.startsWith('setprop')) {
      return {
        ok: false,
        exitCode: 1,
        truncated: false,
        errorCode: 'ROOT_APPROVAL_REQUIRED',
        text: JSON.stringify({
          schemaVersion: 1,
          channel: 'root',
          tier: 'elevated',
          route: 'dsh_approval',
          requiresHostConfirm: false,
          actions: ['修改 Android 系统属性'],
          snippet: 'setprop a 1',
          challengeId: tier1Challenge,
          guardProfile: 'strict',
          degraded: false,
          degradations: [],
        }),
      }
    }
    if (request.command === 'shell' && request.param.startsWith('rm -rf /data')) {
      return {
        ok: false,
        exitCode: 1,
        truncated: false,
        errorCode: 'ROOT_APPROVAL_REQUIRED',
        text: JSON.stringify({
          schemaVersion: 1,
          channel: 'root',
          tier: 'critical',
          route: 'app_warning',
          requiresHostConfirm: true,
          actions: ['删除数据根'],
          snippet: 'rm -rf /data',
          challengeId: tier3Challenge,
          guardProfile: 'maximum',
          degraded: false,
          degradations: [],
        }),
      }
    }
    assert.equal(request.command, 'shellApproved')
    return ok('executed-after-policy')
  }, { get: name => (name === 'approval' ? approval : undefined) })

  const tool = f.tools.get('mobile_device_shell')
  const agent = { session: { id: 'full-access' } }
  const r1 = await tool.execute({ script: 'setprop a 1' }, { agent })
  assert.equal(r1.output, 'executed-after-policy')
  assert.equal(approvalAsked, 0)
  assert.equal(JSON.parse(f.requests[1].param).approvalMode, 'dsh_full_access')

  const r3 = await tool.execute({ script: 'rm -rf /data', justification: '清空测试数据' }, { agent })
  assert.equal(r3.output, 'executed-after-policy')
  assert.equal(approvalAsked, 0)
  assert.deepEqual(JSON.parse(f.requests[3].param), {
    script: 'rm -rf /data',
    challengeId: tier3Challenge,
    approvalMode: 'dsh_full_access',
    justification: '清空测试数据',
  })
})

test('极危根命令在 ask 策略下先经 dsh 官方询问，取消信号时联动发送 shellApprovalCancel', async t => {
  const challengeId = '55555555-2222-3333-4444-555555555555'
  const prompts = []
  const controller = new AbortController()
  const f = fixture(t, request => {
    if (request.command === 'shell') {
      return {
        ok: false,
        exitCode: 1,
        truncated: false,
        errorCode: 'ROOT_APPROVAL_REQUIRED',
        text: JSON.stringify({
          schemaVersion: 1,
          channel: 'root',
          tier: 'critical',
          route: 'app_warning',
          requiresHostConfirm: true,
          actions: ['直写物理块设备'],
          snippet: 'dd of=/dev/block/sda',
          challengeId,
          guardProfile: 'maximum',
          degraded: false,
          degradations: [],
        }),
      }
    }
    if (request.command === 'shellApproved') {
      // 模拟宿主正在等待用户确认期间，用户在聊天里点了停止。
      controller.abort()
      return Promise.reject(new Error('aborted'))
    }
    return ok('')
  }, {
    get: name => (name === 'approval' ? {
      effectivePolicy: () => 'ask',
      request: async req => {
        prompts.push(req)
        return 'allowed-once'
      },
    } : undefined),
  })

  await assert.rejects(
    f.tools.get('mobile_device_shell').execute(
      { script: 'dd if=/dev/zero of=/dev/block/sda', justification: '刷写测试镜像' },
      { agent: { session: {} }, signal: controller.signal },
    ),
    /DEVICE_COMMAND_CANCELLED/u,
  )
  assert.equal(prompts.length, 1)
  assert.match(prompts[0].displayReason.zh, /打开应用极危警告页/u)
  assert.ok(f.requests.some(r => r.command === 'shellApprovalCancel' && r.param === challengeId))
})

test('宿主放开能力在插件侧可透传（root=device 绝对路径、自定义 URI Scheme 与 android 系统弹窗），由宿主硬编码开关兜底', async t => {
  const f = fixture(t, () => ok('{"ok":true}'))

  // 1. root=device 支持绝对路径，inbox 仍拒绝绝对路径与 .. 穿越
  const fileList = f.tools.get('mobile_device_file_list')
  await fileList.execute({ root: 'device', path: '/data/local/tmp' }, {})
  assert.deepEqual(JSON.parse(f.requests[0].param), { root: 'device', path: '/data/local/tmp' })
  await assert.rejects(fileList.execute({ root: 'inbox', path: '/data/local/tmp' }, {}), /DEVICE_FILE_INVALID/u)
  await assert.rejects(fileList.execute({ root: 'device', path: '/data/../etc' }, {}), /DEVICE_FILE_INVALID/u)

  // 2. 副屏 launch 透传自定义应用 URI Scheme，仍硬拒绝 file/javascript/data
  const vsAction = f.tools.get('mobile_virtual_screen_action')
  await vsAction.execute({ sessionId: virtualSession, action: 'launch', uri: 'weixin://dl/scan' }, {})
  assert.deepEqual(JSON.parse(f.requests[1].param), { sessionId: virtualSession, action: 'launch', uri: 'weixin://dl/scan' })
  for (const blocked of ['file:///sdcard/a', 'javascript:alert(1)', 'data:text/html,x']) {
    await assert.rejects(vsAction.execute({ sessionId: virtualSession, action: 'launch', uri: blocked }, {}), /VIRTUAL_SCREEN_INVALID/u)
  }

  // 3. 无障碍透传 android 系统框架弹窗及系统应用内的 android:id/* 通用按钮，仍拒绝任意单段非法包名
  const a11yAction = f.tools.get('mobile_accessibility_action')
  await a11yAction.execute({ packageName: 'android', action: 'click', viewId: 'android:id/button1' }, {})
  await a11yAction.execute({ packageName: 'com.android.settings', action: 'click', viewId: 'android:id/button1' }, {})
  assert.deepEqual(JSON.parse(f.requests[2].param), {
    packageName: 'android',
    action: 'click',
    selector: { viewId: 'android:id/button1' },
  })
  assert.deepEqual(JSON.parse(f.requests[3].param), {
    packageName: 'com.android.settings',
    action: 'click',
    selector: { viewId: 'android:id/button1' },
  })
  await assert.rejects(
    a11yAction.execute({ packageName: 'foo', action: 'click', viewId: 'foo:id/btn' }, {}),
    /ACCESSIBILITY_ACTION_INVALID/u,
  )
})
