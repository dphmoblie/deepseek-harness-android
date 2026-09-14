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
