import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Onboarding } from './Onboarding'
import { saveLanguage } from '../i18n'
import type { RuntimeSettings, RuntimeState } from '../platform/types'

const runtime: RuntimeState = {
  phase: 'not-installed',
  architecture: 'arm64-v8a',
  updateAvailable: false,
  downloadedBytes: 0,
  totalBytes: 0,
  runnerAvailable: true,
}

const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
  configuredModelProviders: [],
}

function renderOnboarding(overrides: Partial<Parameters<typeof Onboarding>[0]> = {}) {
  const props = {
    busy: null,
    runtime,
    settings,
    onInstall: vi.fn(),
    onOpenVersions: vi.fn(),
    onDone: vi.fn(),
    onSaveSettings: vi.fn().mockResolvedValue(true),
    ...overrides,
  }
  const view = render(<Onboarding {...props} />)
  return { ...props, rerender: view.rerender }
}

/** 首次配置只剩两步：模型 → 运行环境。 */
const next = (times = 1) => {
  for (let i = 0; i < times; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
}

describe('Onboarding', () => {
  beforeEach(() => { saveLanguage('zh-CN') })

  it('首屏直接是模型步骤，且密钥输入不遮罩', () => {
    renderOnboarding()
    expect(screen.getByText('配置模型')).toBeDefined()
    // 首次配置不需要内容保护：遮罩会让人看不见输入法到底写进去了什么。
    expect(screen.getByLabelText('DeepSeek API Key')).toHaveAttribute('type', 'text')
    expect(screen.getByText(/首次配置不遮罩密钥/)).toBeDefined()
  })

  it('两步走完引导：模型 → 运行环境 → 完成', () => {
    const props = renderOnboarding()
    next()
    expect(screen.getByText('安装运行环境')).toBeDefined()
    // 「开始使用」那一步已删除：最后一步本身就要能收尾。
    expect(screen.queryByText('开始使用')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /完成/ }))
    expect(props.onDone).toHaveBeenCalledOnce()
  })

  it('模型步骤只留 DeepSeek API Key，没有供应商选择与账号登录入口', () => {
    renderOnboarding({ runtime: { ...runtime, phase: 'ready' } })
    expect(screen.getByLabelText('DeepSeek API Key')).toBeDefined()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('button', { name: /登录 DeepSeek 账号/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /添加自定义供应商/ })).toBeNull()
    // 登录入口搬进运行环境了，这里要如实告诉用户去哪儿登录。
    expect(screen.getByText('这一页只配置 API Key，其它设置装好后按需调整。')).toBeDefined()
  })

  it.each<{ name: string; configured: Partial<RuntimeSettings>; expected: string }>([
    { name: '本机保存的密钥', configured: { configuredModelProviders: ['deepseek'] }, expected: '已配置' },
    { name: 'Harness 里配的密钥', configured: { harnessConfiguredModelProviders: ['deepseek'] }, expected: '已在 Harness 中配置' },
    { name: '都没有', configured: {}, expected: '未配置' },
  ])('$name 如实显示配置状态', ({ configured, expected }) => {
    renderOnboarding({ settings: { ...settings, ...configured } })
    expect(screen.getByText(expected)).toBeDefined()
  })

  it('输入长度实时可见，清理过的粘贴会如实提示', () => {
    renderOnboarding()
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'sk-12345' } })
    expect(screen.getByText('已输入 8 个字符')).toBeDefined()
    fireEvent.change(input, { target: { value: ' sk-12345 ' } })
    expect(screen.getByText(/已自动去掉空格、换行与引号/)).toBeDefined()
  })

  it('输入法组合结束只发 compositionend 也不丢内容', () => {
    const props = renderOnboarding()
    const input = screen.getByLabelText<HTMLInputElement>('DeepSeek API Key')
    fireEvent.compositionStart(input)
    // 组合过程中 DOM 里已有内容，但只有 compositionend 落地。
    input.value = 'sk-composition'
    fireEvent.compositionEnd(input)
    expect(input).toHaveValue('sk-composition')
    expect(screen.getByRole('button', { name: '保存 API Key' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    expect(props.onSaveSettings).toHaveBeenCalledWith(expect.objectContaining({
      providerApiKeys: { deepseek: 'sk-composition' },
    }))
  })

  it.each([
    { name: '折行空格', raw: ' Bearer sk-abc\n ' },
    { name: '外层引号', raw: '"sk-abc"' },
    { name: '零宽字符', raw: 'sk-\u200babc' },
  ])('粘贴的 $name 会被清理后再保存', ({ raw }) => {
    const props = renderOnboarding()
    fireEvent.change(screen.getByLabelText('DeepSeek API Key'), { target: { value: raw } })
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    expect(props.onSaveSettings).toHaveBeenCalledWith(expect.objectContaining({
      providerApiKeys: { deepseek: 'sk-abc' },
    }))
  })

  it('安装忙碌时不会调用保存或清空密钥', () => {
    const props = renderOnboarding({ busy: 'install' })
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    expect(screen.getByRole('button', { name: '保存 API Key' })).toBeDisabled()
    fireEvent.submit(input.closest('form')!)
    expect(props.onSaveSettings).not.toHaveBeenCalled()
    expect(input).toHaveValue('test-placeholder-key')
  })

  it.each([false, new Error('SAVE_FAILED')])('保存未成功时保留密钥并允许重试：%s', async failure => {
    const onSaveSettings = failure instanceof Error ? vi.fn().mockRejectedValue(failure) : vi.fn().mockResolvedValue(failure)
    renderOnboarding({ onSaveSettings })
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    await screen.findByRole('alert')
    expect(input).toHaveValue('test-placeholder-key')
    expect(screen.getByRole('button', { name: '保存 API Key' })).toBeEnabled()
  })

  it('保存完成之前不清空密钥或重复提交，成功后清空', async () => {
    let finish!: (saved: boolean) => void
    const onSaveSettings = vi.fn(() => new Promise<boolean>(resolve => { finish = resolve }))
    renderOnboarding({ onSaveSettings })
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    fireEvent.submit(input.closest('form')!)
    expect(onSaveSettings).toHaveBeenCalledTimes(1)
    expect(input).toHaveValue('test-placeholder-key')
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: /下一步/ })).toBeDisabled()
    finish(true)
    await waitFor(() => expect(input).toHaveValue(''))
  })

  it('切步骤再回来时保留未保存的密钥', () => {
    renderOnboarding()
    fireEvent.change(screen.getByLabelText('DeepSeek API Key'), { target: { value: 'sk-keep-me' } })
    next()
    fireEvent.click(screen.getByRole('button', { name: /上一步/ }))
    expect(screen.getByLabelText('DeepSeek API Key')).toHaveValue('sk-keep-me')
  })

  it('运行环境步骤可安装、可换在线版本', () => {
    const props = renderOnboarding()
    next()
    expect(screen.getByText(/这次从你设置的在线地址下载/)).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /安装运行时/ }))
    expect(props.onInstall).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: /去在线下载其它版本/ }))
    expect(props.onOpenVersions).toHaveBeenCalledOnce()
  })

  it('内置来源时说明不需要联网', () => {
    renderOnboarding({ settings: { ...settings, manifestUrl: '', manifestSha256: '' } })
    next()
    expect(screen.getByText(/这次使用 APK 内置镜像/)).toBeDefined()
  })

  it('已装好运行环境时安装按钮禁用并说明装好即可对话', () => {
    renderOnboarding({ runtime: { ...runtime, phase: 'running' } })
    next()
    expect(screen.getByRole('button', { name: /已安装/ })).toBeDisabled()
    expect(screen.getByText('装好运行环境后打开 Harness 就能直接对话。')).toBeDefined()
  })

  it('skips the wizard via the close button', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /跳过引导/ }))
    expect(props.onDone).toHaveBeenCalledOnce()
  })
})
