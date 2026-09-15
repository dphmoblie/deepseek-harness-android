import { describe, expect, it } from 'vitest'
import { readLogInsights } from './logInsights'

/** 判读只做特征识别：命中就给结论，命中不到就什么都不显示。 */
describe('日志判读', () => {
  it('空文本或没有命中特征时不给出任何结论', () => {
    expect(readLogInsights('')).toEqual([])
    expect(readLogInsights('INFO|RUNTIME_PHASE|phase=running')).toEqual([])
    // 输入类型异常时按「没有内容」处理，不抛异常。
    expect(readLogInsights(undefined as unknown as string)).toEqual([])
  })

  it('识别工具调用失败的模块身份分裂特征', () => {
    const text = "TypeError: Cannot read properties of undefined (reading 'prepare')\n    at DshTools.prepare"
    const insights = readLogInsights(text)
    expect(insights.map(insight => insight.id)).toEqual(['duplicate-runtime-module'])
    expect(insights[0].nextStep).toContain('MODULE_GRAPH')
  })

  it('识别「本机没有可用沙箱后端」并给出可执行的能力降级指引', () => {
    const text = 'SandboxUnavailableError: sandbox mode "workspace-write" is requested but no sandbox backend is usable on this host'
    const insights = readLogInsights(text)
    expect(insights.map(insight => insight.id)).toEqual(['sandbox-backend-unavailable'])
    expect(insights[0].meaning).toContain('fail-closed')
    // 下一步是用户显式选择的降级，不是「已修复」：文案必须指向权限预设与重启运行环境。
    expect(insights[0].nextStep).toContain('不启用沙箱')
    expect(insights[0].nextStep).toContain('重启运行环境')
    // 同一特征全大写时也要命中：判读统一按小写文本匹配。
    expect(readLogInsights('NO SANDBOX BACKEND IS USABLE').map(insight => insight.id))
      .toEqual(['sandbox-backend-unavailable'])
  })

  it('识别工具参数被显式传成 undefined 的上游校验报错', () => {
    const text = 'Error: binding arguments must be lossless JSON\n    at validateToolArguments'
    const insights = readLogInsights(text)
    expect(insights.map(insight => insight.id)).toEqual(['explicit-undefined-argument'])
    expect(insights[0].meaning).toContain('undefined')
    expect(insights[0].nextStep).toContain('省略')
  })

  it('相近但未确诊的沙箱与参数日志不产生结论', () => {
    // 后端「可用」与「不可用」是两句相反的话，不能靠关键词猜。
    expect(readLogInsights('INFO|SANDBOX|mode=workspace-write|backend=landlock|usable=true')).toEqual([])
    expect(readLogInsights('TypeError: Cannot read properties of undefined (reading \'length\')')).toEqual([])
  })

  it('两条新规则按规则表顺序排在更泛化的规则之前', () => {
    const text = [
      'no sandbox backend is usable',
      'binding arguments must be lossless JSON',
      'Error: EADDRINUSE: address already in use :::3080',
    ].join('\n')
    expect(readLogInsights(text).map(insight => insight.id))
      .toEqual(['sandbox-backend-unavailable', 'explicit-undefined-argument', 'port-in-use'])
  })

  it('识别诊断日志里的受控记录', () => {
    const text = [
      '2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4',
      '2026-09-12T10:22:20Z|ERROR|HARNESS_START|result=failed|code=HARNESS_MODULE_MISSING',
    ].join('\n')
    expect(readLogInsights(text).map(insight => insight.id))
      .toEqual(['module-graph-duplicate', 'harness-start-failed'])
  })

  it('按规则表顺序返回多条命中的结论', () => {
    const text = 'Error: EADDRINUSE: address already in use :::3080\nMISSING_CREDENTIAL: no api key'
    // 缺凭据比端口占用更靠前：先给用户最可能的结论。
    expect(readLogInsights(text).map(insight => insight.id))
      .toEqual(['missing-credential', 'port-in-use'])
  })

  it('大小写不影响识别，且不猜测未知报错', () => {
    expect(readLogInsights('PLUGIN(S) FAILED TO LOAD: dsh-plugin-demo').map(insight => insight.id))
      .toEqual(['plugin-tree-failed'])
    expect(readLogInsights('SomeError: something nobody diagnosed yet')).toEqual([])
  })

  it('提示停止超时可能盖住真正原因', () => {
    expect(readLogInsights('HARNESS_STOP_TIMEOUT|code=HARNESS_STOP_TIMEOUT').map(insight => insight.id))
      .toEqual(['harness-stop-timeout'])
  })
})
