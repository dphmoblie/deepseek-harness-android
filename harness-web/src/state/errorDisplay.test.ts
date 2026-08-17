import { describe, expect, it } from 'vitest'
import {
  agentErrorNotice,
  failureReason,
  finishFailureReason,
  MAX_ERROR_REASON_LENGTH,
  modelCatalogFailureNotice,
  streamErrorNotice,
  turnEndFailureNotice,
} from './errorDisplay'
import type { SessionEvent } from '../api/types'

function turnEnd(reason: Record<string, unknown>): SessionEvent {
  return { type: 'turn/end', seq: 1, time: 0, data: { turn: 1, reason } }
}

describe('错误原因安全展示', () => {
  it('读取真实 turn/end LLM 失败原因', () => {
    expect(turnEndFailureNotice(turnEnd({
      kind: 'error',
      error: { message: 'DeepSeek API 请求超时', code: 'TIMEOUT', status: 504 },
    }))).toBe('本轮运行失败：DeepSeek API 请求超时')
  })

  it('兼容流式 FinishReason.failure', () => {
    expect(finishFailureReason({
      kind: 'error',
      failure: { message: '上游连接被重置', code: 'CONNECTION_RESET' },
    })).toBe('上游连接被重置')
  })

  it('认证失败使用固定文案，不回显供应商消息中的凭证', () => {
    const fakeCredential = ['sk', 'test-placeholder-not-a-key'].join('-')
    expect(failureReason({ code: 'AUTH', message: `invalid apiKey=${fakeCredential}` }))
      .toBe('API 密钥无效')
  })

  it('隐藏密钥、堆栈、用户目录和私网地址', () => {
    const privateAddress = ['192', '168', '1', '20'].join('.')
    const reason = failureReason({
      message: [
        'ProviderError: request failed Authorization: Bearer test-placeholder-token',
        `target C:\\Users\\alice\\project ${privateAddress}`,
        '    at request (C:\\Users\\alice\\project\\client.ts:12:4)',
      ].join('\n'),
    })
    expect(reason).toContain('request failed')
    expect(reason).toContain('[已隐藏]')
    expect(reason).toContain('%USERPROFILE%')
    expect(reason).toContain('[私网地址]')
    expect(reason).not.toContain('test-placeholder-token')
    expect(reason).not.toContain('alice')
    expect(reason).not.toContain('client.ts:12')
  })

  it('结构化字符串只提取允许的消息字段，不直接显示对象', () => {
    expect(failureReason('{"message":"配额不足","secret":"do-not-show","debug":{"stack":"hidden"}}'))
      .toBe('配额不足')
    expect(failureReason('{"secret":"unterminated"')).toBeNull()
    expect(failureReason({ nested: { private: 'value' } })).toBeNull()
  })

  it('Python traceback 只展示末尾异常摘要', () => {
    expect(failureReason([
      'Traceback (most recent call last):',
      '  File "/home/alice/client.py", line 8, in request',
      '    await client.send()',
      'TimeoutError: 上游响应超时',
    ].join('\n'))).toBe('TimeoutError: 上游响应超时')
  })

  it('限制展示长度并保持 Unicode 字符完整', () => {
    const reason = failureReason(`服务异常${'错'.repeat(400)}`)
    expect([...(reason ?? '')]).toHaveLength(MAX_ERROR_REASON_LENGTH)
    expect(reason?.endsWith('…')).toBe(true)
  })

  it('为 host agent-error 与 stream/error 保留各自语义并显示具体原因', () => {
    expect(agentErrorNotice('agent crashed\n    at loop (agent.ts:1:1)'))
      .toBe('本轮运行失败：agent crashed')
    expect(streamErrorNotice({ code: 'internal', message: '事件订阅后端已断开' }))
      .toBe('Harness 事件流异常：事件订阅后端已断开')
  })

  it('缺少可展示字段时给出明确回退，不泄露对象内容', () => {
    expect(turnEndFailureNotice(turnEnd({ kind: 'error', error: { internal: 'secret state' } })))
      .toBe('本轮运行失败：未提供详细原因')
  })

  it('忽略旧占位文案并继续读取 details 中的真实原因', () => {
    expect(agentErrorNotice({
      message: '本轮因错误终止',
      details: { message: '模型供应商配额不足' },
    })).toBe('本轮运行失败：模型供应商配额不足')
    expect(agentErrorNotice('本轮因错误终止'))
      .toBe('本轮运行失败：未提供详细原因')
    expect(agentErrorNotice('本轮因错误终止\n    at run (agent.ts:1:1)'))
      .toBe('本轮运行失败：未提供详细原因')
  })

  it('隐藏普通 token 与签名字段', () => {
    const reason = failureReason('provider rejected token=third-party-placeholder signature=signature-placeholder')
    expect(reason).toBe('provider rejected token=[已隐藏] signature=[已隐藏]')
    expect(reason).not.toContain('third-party-placeholder')
    expect(reason).not.toContain('signature-placeholder')
  })

  it('完整隐藏自定义 Authorization 方案与 Cookie 头', () => {
    const authorization = failureReason(
      'provider rejected Authorization: Token sensitive-placeholder-value',
    )
    expect(authorization).toBe('provider rejected Authorization: [已隐藏]')
    expect(authorization).not.toContain('sensitive-placeholder-value')

    const digest = failureReason(
      'provider rejected Authorization: Digest username="placeholder", realm="internal", response="sensitive-placeholder"',
    )
    expect(digest).toBe('provider rejected Authorization: [已隐藏]')
    expect(digest).not.toContain('realm')
    expect(digest).not.toContain('sensitive-placeholder')

    const proxyAuthorization = failureReason(
      'provider rejected Proxy-Authorization: Custom first-part second-sensitive-part',
    )
    expect(proxyAuthorization).toBe('provider rejected Proxy-Authorization: [已隐藏]')
    expect(proxyAuthorization).not.toContain('second-sensitive-part')

    const cookie = failureReason(
      'provider rejected Cookie: sid=session-placeholder; other=secondary-placeholder',
    )
    expect(cookie).toBe('provider rejected Cookie: [已隐藏]')
    expect(cookie).not.toContain('session-placeholder')
    expect(cookie).not.toContain('secondary-placeholder')

    const setCookie = failureReason(
      'provider rejected Set-Cookie: refresh=refresh-placeholder; Path=/; HttpOnly',
    )
    expect(setCookie).toBe('provider rejected Set-Cookie: [已隐藏]')
    expect(setCookie).not.toContain('refresh-placeholder')

    expect(failureReason(JSON.stringify({
      message: 'provider rejected Cookie: sid=session-placeholder; reason=quota',
    }))).toBe('provider rejected Cookie: [已隐藏]')
  })

  it('隐藏错误消息中的个人敏感数据', () => {
    const email = ['alice', 'example.invalid'].join('@')
    const phone = ['138', '0013', '8000'].join('')
    const identityNumber = ['110105', '19900101', '001', 'X'].join('')
    const reason = failureReason(`contact ${email} ${phone} ${identityNumber}`)

    expect(reason).toBe('contact [邮箱已隐藏] [手机号已隐藏] [身份证号已隐藏]')
    expect(reason).not.toContain(email)
    expect(reason).not.toContain(phone)
    expect(reason).not.toContain(identityNumber)
  })

  it('读取 turn/end reason 自身及事件 failure 字段', () => {
    expect(turnEndFailureNotice(turnEnd({
      kind: 'error',
      details: { message: '请求签名时间已过期' },
    }))).toBe('本轮运行失败：请求签名时间已过期')
    expect(turnEndFailureNotice({
      type: 'turn/end',
      seq: 2,
      time: 0,
      data: { turn: 1, reason: { kind: 'error' }, failure: { message: '连接池已关闭' } },
    })).toBe('本轮运行失败：连接池已关闭')
  })

  it('逐项展示模型目录失败并脱敏', () => {
    const notice = modelCatalogFailureNotice([{
      id: 'deepseek',
      name: 'DeepSeek',
      message: 'Authorization: Bearer test-placeholder-token\n请求超时',
    }])
    expect(notice).toContain('模型目录加载失败：DeepSeek')
    expect(notice).toContain('[已隐藏]')
    expect(notice).toContain('请求超时')
    expect(notice).not.toContain('test-placeholder-token')
  })
})
