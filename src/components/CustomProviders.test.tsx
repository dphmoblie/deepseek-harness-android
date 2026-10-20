import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CustomProviders } from './CustomProviders'
import type { CustomModelProvider, ModelCatalogResult } from '../platform/types'
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

  it('改了字段就丢掉上一次的测试结果，密钥框里正在输入的内容不会被回写吃掉', async () => {
    const onTestConnectivity = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      latencyMs: 85,
      message: '401 密钥无效或未授权 (85ms)',
    })
    const onCredentials = vi.fn()
    render(
      <CustomProviders
        providers={sampleProviders}
        credentials={{}}
        cleared={[]}
        configured={[]}
        harnessConfigured={[]}
        onChange={vi.fn()}
        onCredentials={onCredentials}
        onClear={vi.fn()}
        onTestConnectivity={onTestConnectivity}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '测试连接' }))
    await screen.findByText('401 密钥无效或未授权 (85ms)')

    // 上一次的 401 只对改之前那份地址成立，不能一直红在下面当作新输入的报错。
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://gateway.example.invalid/v1' } })
    expect(screen.queryByText('401 密钥无效或未授权 (85ms)')).toBeNull()

    // 密钥框是非受控的：输入的字符原样留着，父组件拿到的也是完整值。
    const key = screen.getByLabelText('API Key')
    fireEvent.change(key, { target: { value: 'sk-typing' } })
    expect(key).toHaveValue('sk-typing')
    expect(onCredentials).toHaveBeenLastCalledWith({ 'custom-glm': 'sk-typing' })
  })
})

/**
 * 「获取模型」把上游列表并进表单的规则由 `src/platform/modelCatalog.test.ts` 逐条钉住；
 * 这里只验证组件真的把结果交回去、并且只在成功时才动模型表。
 */
describe('CustomProviders 获取上游模型', () => {
  const provider: CustomModelProvider = {
    id: 'custom-glm',
    name: '智谱 GLM',
    api: 'openai-completions',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    models: [{ id: 'glm-4-flash', name: 'GLM 4 Flash', contextWindow: 128000, maxTokens: 4096 }],
  }

  function renderProviders(
    onChange: (providers: CustomModelProvider[]) => void,
    onFetchModels: (provider: CustomModelProvider, draftKey?: string) => Promise<ModelCatalogResult>,
  ) {
    render(<CustomProviders
      providers={[provider]}
      configured={[]}
      credentials={{}}
      cleared={[]}
      onChange={onChange}
      onCredentials={vi.fn()}
      onClear={vi.fn()}
      onFetchModels={onFetchModels}
    />)
  }

  it('成功后把上游第一个置顶，其余按 id 追加且保留已有字段', async () => {
    const onFetchModels = vi.fn<(provider: CustomModelProvider, draftKey?: string) => Promise<ModelCatalogResult>>()
      .mockResolvedValue({
        ok: true,
        status: 200,
        latencyMs: 80,
        message: '已获取 3 个模型 (80ms)',
        models: [
          { id: 'glm-4-plus', name: 'GLM 4 Plus' },
          { id: 'glm-4-flash', name: 'GLM 4 Flash' },
          { id: 'glm-4-air', name: 'GLM 4 Air' },
        ],
      })
    const onChange = vi.fn<(providers: CustomModelProvider[]) => void>()
    renderProviders(onChange, onFetchModels)

    fireEvent.click(screen.getByRole('button', { name: '获取模型' }))
    await waitFor(() => expect(onChange).toHaveBeenCalled())

    const models = onChange.mock.calls.at(-1)?.[0][0].models ?? []
    expect(models.map(model => model.id)).toEqual(['glm-4-plus', 'glm-4-flash', 'glm-4-air'])
    // 已有的 glm-4-flash 保持用户填过的上下文窗口与输出上限，不被默认值覆盖。
    expect(models[1]).toMatchObject({ contextWindow: 128000, maxTokens: 4096 })
  })

  it('失败时只显示原因，模型表一个字都不动', async () => {
    const onFetchModels = vi.fn<(provider: CustomModelProvider, draftKey?: string) => Promise<ModelCatalogResult>>()
      .mockResolvedValue({
        ok: false, status: 401, latencyMs: 60, message: '401 密钥无效或未授权 (60ms)', models: [],
      })
    const onChange = vi.fn<(providers: CustomModelProvider[]) => void>()
    renderProviders(onChange, onFetchModels)

    fireEvent.click(screen.getByRole('button', { name: '获取模型' }))
    await screen.findByText('401 密钥无效或未授权 (60ms)')
    expect(onChange).not.toHaveBeenCalled()
  })
})
