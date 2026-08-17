import { describe, expect, it } from 'vitest'
import { RpcFailure, TransportError } from '../api/wire'
import { rpcErrorMessage } from './rpcError'

describe('rpcErrorMessage', () => {
  it('保留具体 RPC 原因和错误码，同时隐藏凭证', () => {
    const fakeCredential = ['sk', 'test-placeholder-not-a-key'].join('-')
    const failure = new RpcFailure({
      code: 'directory-unreadable',
      message: `cannot list /work: api_key=${fakeCredential}`,
      details: {},
    })

    const message = rpcErrorMessage('加载目录', failure)

    expect(message).toContain('目录不可读')
    expect(message).toContain('cannot list /work')
    expect(message).toContain('错误代码：directory-unreadable')
    expect(message).toContain('[已隐藏]')
    expect(message).not.toContain(fakeCredential)
  })

  it('清理 transport 错误中的私网地址', () => {
    const privateAddress = ['192', '168', '1', '9'].join('.')
    const message = rpcErrorMessage(
      '加载会话',
      new TransportError(`connect ECONNREFUSED ${privateAddress}:3080`),
    )

    expect(message).toBe('加载会话失败：connect ECONNREFUSED [私网地址]:3080')
  })

  it('未知错误码回退到脱敏后的服务端消息', () => {
    const fakeCredential = ['sk', 'test-placeholder-not-a-key'].join('-')
    const message = rpcErrorMessage('加载任务', new RpcFailure({
      code: 'plugin-failure',
      message: `provider rejected api_key=${fakeCredential}`,
      details: {},
    }))

    expect(message).toContain('provider rejected api_key=[已隐藏]')
    expect(message).not.toContain(fakeCredential)
  })

  it('未知对象不直接序列化到界面', () => {
    expect(rpcErrorMessage('加载任务', { token: 'secret-value' }))
      .toBe('加载任务失败：未提供详细原因')
  })
})
