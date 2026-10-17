import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CustomProviders } from './CustomProviders'
import type { CustomModelProvider } from '../platform/types'
import { MAX_CUSTOM_PROVIDERS } from '../platform/customProviders'

describe('CustomProviders 自定义模型供应商', () => {
  const sampleProviders: CustomModelProvider[] = [
    {
      id: 'custom-glm',
      name: '智谱 GLM',
      api: 'openai-completions',
      baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
      models: [{ id: 'glm-4-flash', name: 'GLM 4 Flash', contextWindow: 128000, maxTokens: 4096 }],
    },
  ]

  it('达到自定义供应商上限时禁用底部添加按钮', () => {
    const onChange = vi.fn()
    render(<CustomProviders
      providers={Array.from({ length: MAX_CUSTOM_PROVIDERS }, (_, index) => ({ ...sampleProviders[0], id: `custom-${index + 1}` }))}
      configured={[]}
      credentials={{}}
      cleared={[]}
      onChange={onChange}
      onCredentials={vi.fn()}
      onClear={vi.fn()}
    />)

    const add = screen.getByRole('button', { name: '添加自定义供应商' })
    expect(add).toBeDisabled()
    fireEvent.click(add)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('点击测试连接按钮触发 onTestConnectivity 并展示测试结果', async () => {
    const onTestConnectivity = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      latencyMs: 142,
      message: '✓ 连接成功 (142ms)',
    })
    const onChange = vi.fn()
    const onCredentials = vi.fn()
    const onClear = vi.fn()

    render(
      <CustomProviders
        providers={sampleProviders}
        credentials={{ 'custom-glm': 'test-secret-key' }}
        cleared={[]}
        configured={[]}
        harnessConfigured={[]}
        onChange={onChange}
        onCredentials={onCredentials}
        onClear={onClear}
        onTestConnectivity={onTestConnectivity}
      />,
    )

    const testBtn = screen.getByRole('button', { name: '测试连接' })
    expect(testBtn).toBeInTheDocument()

    fireEvent.click(testBtn)

    expect(onTestConnectivity).toHaveBeenCalledWith(sampleProviders[0], 'test-secret-key')
    await waitFor(() => {
      expect(screen.getByText('✓ 连接成功 (142ms)')).toBeInTheDocument()
    })
  })

  it('测试连接失败时展示错误结果与响应码', async () => {
    const onTestConnectivity = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      latencyMs: 85,
      message: '401 密钥无效或未授权 (85ms)',
    })

    render(
      <CustomProviders
        providers={sampleProviders}
        credentials={{}}
        cleared={[]}
        configured={[]}
        harnessConfigured={[]}
        onChange={vi.fn()}
        onCredentials={vi.fn()}
        onClear={vi.fn()}
        onTestConnectivity={onTestConnectivity}
      />,
    )

    const testBtn = screen.getByRole('button', { name: '测试连接' })
    fireEvent.click(testBtn)

    await waitFor(() => {
      expect(screen.getByText('401 密钥无效或未授权 (85ms)')).toBeInTheDocument()
    })
  })
})
