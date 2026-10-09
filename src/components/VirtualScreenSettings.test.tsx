import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'
import { createBrowserBridge } from '../platform/browser'
import { assertVirtualScreenSettingsUpdate, assertVirtualScreenStartRequest, validateVirtualScreenSettings, validateVirtualScreenState } from '../platform/validation'
import type { VirtualScreenSettings as VirtualScreenSettingsSnapshot, VirtualScreenState } from '../platform/types'
import { VirtualScreenSettings } from './VirtualScreenSettings'

/** 原生设置的默认形状：与 `VirtualScreenPreferences.read()` 的默认逐字一致。 */
function settings(overrides: Partial<VirtualScreenSettingsSnapshot> = {}): VirtualScreenSettingsSnapshot {
  return {
    previewMode: '60fps',
    autoFollow: 'off',
    orientation: 'auto',
    adaptive: false,
    widthPx: 0,
    heightPx: 0,
    densityDpi: 0,
    ...overrides,
  }
}

/**
 * 原生状态的默认形状。
 *
 * 刻意走真实的 `validateVirtualScreenState`：原生侧「取不到」写的是 JSON `null`、
 * 未采样写 0、displayId 未就绪写 -1，这里要验的正是界面把这些显示成「未知」而不是 0。
 */
function state(overrides: Record<string, unknown> = {}): VirtualScreenState {
  return validateVirtualScreenState({
    active: false,
    sessionId: null,
    displayId: -1,
    previewMode: '60fps',
    autoFollow: 'off',
    orientation: 'auto',
    adaptive: false,
    widthPx: 0,
    heightPx: 0,
    densityDpi: 0,
    targetPackage: null,
    frameFps: 0,
    displayRefreshRate: 0,
    virtualForegroundPackage: null,
    virtualForegroundActivity: null,
    ...overrides,
  })
}

/** 只替换副屏那五条桥方法，其余用真实浏览器桥：类型由 RuntimeBridge 兜住，不做 cast。 */
function bridgeWith(overrides: {
  getVirtualScreenSettings?: () => Promise<VirtualScreenSettingsSnapshot>
  setVirtualScreenSettings?: (update: unknown) => Promise<void>
  startVirtualScreen?: (request: unknown) => Promise<void>
  stopVirtualScreen?: () => Promise<void>
  getVirtualScreenState?: () => Promise<VirtualScreenState>
  openVirtualScreen?: () => Promise<void>
}) {
  return { ...createBrowserBridge(), ...overrides }
}

/**
 * 读状态区某一行的读数：结构是 `<div><dt>标签</dt><dd>读数</dd></div>`。
 *
 * 按行取而不是全局查文本：读数与别的区域可能同名（`60 FPS` 也是取帧档位的选项、
 * 「目标应用」也是设置区的标题），全局查会撞上多个元素。
 */
function reading(label: string): string {
  const list = document.querySelector('.virtual-screen-readings')
  if (list === null) throw new Error('状态区还没渲染出来')
  const term = within(list as HTMLElement).getByText(label)
  return term.nextElementSibling?.textContent ?? ''
}

beforeEach(() => {
  saveLanguage('zh-CN')
})

describe('目标应用副屏设置页', () => {
  it('两条读取都在进行时显示加载中，不提前显示默认值', () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: () => new Promise<VirtualScreenSettingsSnapshot>(() => {}),
      getVirtualScreenState: () => new Promise<VirtualScreenState>(() => {}),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    expect(screen.getByText('正在读取副屏设置…')).toBeInTheDocument()
    expect(screen.getByText('正在读取副屏状态…')).toBeInTheDocument()
    // 读之前设置区一格都不渲染：宁可空着，也不拿界面默认值冒充原生设置。
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('读取成功后按原生设置回显档位、方向、自适应开关与目标应用入口', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings({ previewMode: '165fps', orientation: 'landscape', autoFollow: 'pull_back', adaptive: true })),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const mode = await screen.findByRole('combobox', { name: '取帧档位' })
    expect(mode).toHaveValue('165fps')
    expect(screen.getByRole('radio', { name: '横屏' })).toBeChecked()
    expect(screen.getByRole('radio', { name: '拉回目标' })).toBeChecked()
    expect(screen.getByRole('switch', { name: '自适应（按屏幕与方向计算）' })).toBeChecked()
    expect(screen.getByRole('button', { name: '从已安装应用选择' })).toBeEnabled()
    // 还没选目标应用：启动与重启都不可点，停止不受影响（副屏可能在别处已经跑起来了）。
    expect(screen.getByRole('button', { name: '启动副屏' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '重启副屏' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '停止副屏' })).toBeEnabled()
  })

  it('桥不可用时如实说读不到设置与状态，不假装成默认值或未运行', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockRejectedValue(new Error('副屏设置读取被拒绝')),
      getVirtualScreenState: vi.fn().mockRejectedValue(new Error('副屏状态读取被拒绝')),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    expect(await screen.findByText('读不到副屏设置')).toBeInTheDocument()
    expect(screen.getByText('这里读不到设置时，界面不会假装它是默认值：副屏设置读取被拒绝')).toBeInTheDocument()
    expect(screen.getByText('读不到副屏状态')).toBeInTheDocument()
    // 「读不到」与「没在运行」是两回事，文案必须说清。
    expect(screen.getByText('这不代表副屏没有在运行：副屏状态读取被拒绝')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByText('未运行')).toBeNull()
  })

  it('保存失败时报出原生给的原因，且不误报已保存', async () => {
    const save = vi.fn().mockRejectedValue(new Error('副屏取帧档位格式无效'))
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
      setVirtualScreenSettings: save,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const mode = await screen.findByRole('combobox', { name: '取帧档位' })
    fireEvent.change(mode, { target: { value: '30fps' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    // 只发改过的那一项：没动过的字段重发等于用界面值覆盖别处刚改的设置。
    await waitFor(() => expect(save).toHaveBeenCalledWith({ previewMode: '30fps' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('副屏取帧档位格式无效')
    expect(screen.queryByText('副屏设置已保存')).toBeNull()
  })

  it('没有改动时不发桥，只提示没有需要保存的改动', async () => {
    const save = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
      setVirtualScreenSettings: save,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '保存设置' }))
    expect(await screen.findByText('没有需要保存的改动')).toBeInTheDocument()
    expect(save).not.toHaveBeenCalled()
  })

  it('副屏已在运行时把读数逐项显示出来', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({
        active: true,
        sessionId: 'virtual-7',
        displayId: 42,
        widthPx: 726,
        heightPx: 1600,
        densityDpi: 320,
        targetPackage: 'com.example.target',
        frameFps: 60,
        displayRefreshRate: 60,
        virtualForegroundPackage: 'com.example.target',
        virtualForegroundActivity: 'com.example.target/.MainActivity',
      })),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    expect(await screen.findByText('运行中')).toBeInTheDocument()
    expect(reading('会话 id')).toBe('virtual-7')
    expect(reading('displayId')).toBe('42')
    expect(reading('目标应用')).toBe('com.example.target')
    expect(reading('实际生效规格')).toBe('726×1600 @ 320 DPI')
    expect(reading('采集帧率')).toBe('60 FPS')
    expect(reading('虚拟屏实际刷新率')).toBe('60 Hz')
    expect(reading('副屏当前前台应用')).toBe('com.example.target')
    expect(reading('副屏当前前台 Activity')).toBe('com.example.target/.MainActivity')
    // 运行中的目标应用会预填到目标选择里，用户不用再选一遍。
    expect(screen.getByText('将把 com.example.target 放到副屏运行。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '启动副屏' })).toBeEnabled()
  })

  it('取不到的读数显示未知，不补 0 也不留空白', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ active: true })),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    await screen.findByText('运行中')
    // 会话 id、displayId、目标应用、实际规格、帧率、刷新率、前台应用与 Activity 全是「取不到」。
    const unknownRows = ['会话 id', 'displayId', '目标应用', '实际生效规格', '采集帧率', '虚拟屏实际刷新率', '副屏当前前台应用', '副屏当前前台 Activity']
    for (const label of unknownRows) {
      expect(reading(label), label).toBe('未知')
    }
    expect(screen.getAllByText('未知').length).toBeGreaterThanOrEqual(8)
    expect(screen.queryByText('0 FPS')).toBeNull()
    expect(screen.queryByText('0 Hz')).toBeNull()
    expect(screen.queryByText('-1')).toBeNull()
  })

  it('启动副屏按契约发参数：规格齐全时三项一起给', async () => {
    const start = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings({ orientation: 'landscape', widthPx: 720, heightPx: 1600, densityDpi: 320 })),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ targetPackage: 'com.example.target' })),
      startVirtualScreen: start,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '启动副屏' }))
    await waitFor(() => expect(start).toHaveBeenCalledWith({
      packageName: 'com.example.target',
      adaptive: false,
      orientation: 'landscape',
      widthPx: 720,
      heightPx: 1600,
      densityDpi: 320,
    }))
    expect(await screen.findByText('已请求启动副屏；画面与帧率以状态区读数为准')).toBeInTheDocument()
  })

  it('启动副屏在规格不全时不发那三项，避免只改到一半的尺寸', async () => {
    const start = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ targetPackage: 'com.example.target' })),
      startVirtualScreen: start,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '启动副屏' }))
    await waitFor(() => expect(start).toHaveBeenCalledWith({
      packageName: 'com.example.target',
      adaptive: false,
      orientation: 'auto',
    }))
  })

  it('手填规格越界就地夹取：超过上限时立刻夹，低于下限时按失焦收敛', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const width = await screen.findByRole('spinbutton', { name: '副屏宽度' })
    fireEvent.change(width, { target: { value: '99999' } })
    expect(width).toHaveValue(4096)
    expect(screen.getByText('宽 超出范围，已夹取到 4096。')).toBeInTheDocument()
    // 低于下限不立刻夹：否则打到「3」就被改成 200，数字根本没法打完。
    fireEvent.change(width, { target: { value: '10' } })
    expect(width).toHaveValue(10)
    fireEvent.blur(width)
    expect(width).toHaveValue(200)
    expect(screen.getByText('宽 超出范围，已夹取到 200。')).toBeInTheDocument()
  })

  it('规格只填一两项时保存不发尺寸，并在旁边说明为什么不生效', async () => {
    const save = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
      setVirtualScreenSettings: save,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const width = await screen.findByRole('spinbutton', { name: '副屏宽度' })
    fireEvent.change(width, { target: { value: '3000' } })
    fireEvent.blur(width)
    // 只填了宽这一项：这时才该说明「三项要一起填」。
    expect(screen.getByText('宽、高、DPI 三项要一起填才会生效：只填其中一两项时，原生侧会当作没有自定义尺寸，改用预设规格。')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '取帧档位' }), { target: { value: '144fps' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith({ previewMode: '144fps' }))
  })

  it('三格都清空后保存会照发 0，保留「清掉自定义尺寸」这条路', async () => {
    const save = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings({ widthPx: 720, heightPx: 1600, densityDpi: 320 })),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
      setVirtualScreenSettings: save,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const width = await screen.findByRole('spinbutton', { name: '副屏宽度' })
    expect(width).toHaveValue(720)
    for (const label of ['副屏宽度', '副屏高度', '副屏 DPI']) {
      const input = screen.getByRole('spinbutton', { name: label })
      fireEvent.change(input, { target: { value: '' } })
      fireEvent.blur(input)
      expect((input as HTMLInputElement).value).toBe('')
    }
    expect(await screen.findByText('三项都会写回 0：保存后清掉自定义尺寸，改用按屏幕与方向算出的预设规格。')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith({ widthPx: 0, heightPx: 0, densityDpi: 0 }))
  })

  it('设置读不到时也能停止副屏', async () => {
    const stop = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockRejectedValue(new Error('副屏设置读取被拒绝')),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ active: true, sessionId: 'virtual-9' })),
      stopVirtualScreen: stop,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    const button = await screen.findByRole('button', { name: '停止副屏' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    await waitFor(() => expect(stop).toHaveBeenCalledOnce())
    expect(await screen.findByText('已请求停止副屏')).toBeInTheDocument()
  })

  it('重启先等旧显示释放再启动，两步都真的发出去', async () => {
    let release!: () => void
    const stopping = new Promise<void>((resolve) => { release = resolve })
    const stop = vi.fn(() => stopping)
    const start = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ targetPackage: 'com.example.target' })),
      stopVirtualScreen: stop,
      startVirtualScreen: start,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '重启副屏' }))
    await waitFor(() => expect(stop).toHaveBeenCalledOnce())
    expect(start).not.toHaveBeenCalled()
    release()
    await waitFor(() => expect(start).toHaveBeenCalledOnce())
    expect(await screen.findByText('已请求重启副屏；画面与帧率以状态区读数为准')).toBeInTheDocument()
  })

  it('重启等待原生停止完成，停止失败时不启动新副屏', async () => {
    let rejectStop!: (error: Error) => void
    const stopping = new Promise<void>((_, reject) => { rejectStop = reject })
    const stop = vi.fn(() => stopping)
    const start = vi.fn().mockResolvedValue(undefined)
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state({ targetPackage: 'com.example.target' })),
      stopVirtualScreen: stop,
      startVirtualScreen: start,
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '重启副屏' }))
    await waitFor(() => expect(stop).toHaveBeenCalledOnce())
    expect(start).not.toHaveBeenCalled()
    rejectStop(new Error('旧副屏尚未释放'))
    await screen.findByRole('alert')
    expect(start).not.toHaveBeenCalled()
  })

  it('打开系统副屏页面失败时如实报错，不假装打开了', async () => {
    const bridge = bridgeWith({
      getVirtualScreenSettings: vi.fn().mockResolvedValue(settings()),
      getVirtualScreenState: vi.fn().mockResolvedValue(state()),
      openVirtualScreen: vi.fn().mockRejectedValue(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    })
    render(<VirtualScreenSettings bridge={bridge} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '打开系统副屏页面' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('无法打开副屏页面，请确认当前使用安卓壳且系统为 Android 10 或更高版本')
  })
})

describe('副屏桥的入参校验', () => {
  it('非法档位与枚举在发桥之前就被拦掉', () => {
    expect(() => assertVirtualScreenSettingsUpdate({ previewMode: '300fps' })).toThrow('副屏取帧档位格式无效')
    expect(() => assertVirtualScreenSettingsUpdate({ autoFollow: 'follow' })).toThrow('副屏自动跟随策略格式无效')
    expect(() => assertVirtualScreenSettingsUpdate({ orientation: 'sideways' })).toThrow('副屏方向格式无效')
    expect(() => assertVirtualScreenSettingsUpdate({ adaptive: 'yes' })).toThrow('副屏自适应开关格式无效')
    expect(() => assertVirtualScreenSettingsUpdate({ widthPx: '800' })).toThrow('副屏宽高格式无效')
    expect(() => assertVirtualScreenSettingsUpdate({ densityDpi: Number.NaN })).toThrow('副屏像素密度格式无效')
    expect(() => assertVirtualScreenStartRequest({ packageName: '' })).toThrow('目标应用包名格式无效')
    expect(() => assertVirtualScreenStartRequest({ packageName: '没有点的包名' })).toThrow('目标应用包名格式无效')
    expect(() => assertVirtualScreenStartRequest({ packageName: 'com.example.target', previewMode: '300fps' })).not.toThrow()
  })

  it('只处理出现过的字段：没传的不能凭空补上', () => {
    expect(assertVirtualScreenSettingsUpdate({ previewMode: '165fps' })).toEqual({ previewMode: '165fps' })
    expect(assertVirtualScreenSettingsUpdate({})).toEqual({})
    // 原生侧把 null 与缺省都当作「这一项不改」：前端要原样放过，不能拦也不能补默认值。
    expect(assertVirtualScreenSettingsUpdate({ previewMode: null, widthPx: null })).toEqual({})
    expect(assertVirtualScreenStartRequest({ packageName: 'com.example.target' })).toEqual({ packageName: 'com.example.target' })
    expect(assertVirtualScreenStartRequest({ packageName: 'com.example.target', orientation: null })).toEqual({ packageName: 'com.example.target' })
  })

  it('宽高与 DPI 越界就地夹取，0 与负数表示清掉自定义尺寸而不是夹到下限', () => {
    expect(assertVirtualScreenSettingsUpdate({ widthPx: 100 })).toEqual({ widthPx: 200 })
    expect(assertVirtualScreenSettingsUpdate({ heightPx: 99999 })).toEqual({ heightPx: 4096 })
    expect(assertVirtualScreenSettingsUpdate({ densityDpi: 700 })).toEqual({ densityDpi: 640 })
    // 0 在原生侧是「没有自定义过」：夹成 200 会让用户再也回不到自适应或方向预设。
    expect(assertVirtualScreenSettingsUpdate({ widthPx: 0, heightPx: 0, densityDpi: 0 }))
      .toEqual({ widthPx: 0, heightPx: 0, densityDpi: 0 })
    expect(assertVirtualScreenSettingsUpdate({ densityDpi: -1 })).toEqual({ densityDpi: 0 })
    // 启动请求与保存设置共用同一套规则。
    expect(assertVirtualScreenStartRequest({ packageName: 'com.example.target', widthPx: 100 }))
      .toEqual({ packageName: 'com.example.target', widthPx: 200 })
  })

  it('状态里的档位标签被归一化成裸档位，取不到的字段收敛成空串', () => {
    const parsed = validateVirtualScreenState({
      active: false, sessionId: null, displayId: -1, previewMode: 'realtime-60fps', autoFollow: 'off',
      orientation: 'auto', adaptive: false, widthPx: 0, heightPx: 0, densityDpi: 0, targetPackage: null,
      frameFps: 0, displayRefreshRate: 0, virtualForegroundPackage: null, virtualForegroundActivity: null,
    })
    expect(parsed.previewMode).toBe('60fps')
    expect(parsed.sessionId).toBe('')
    expect(parsed.targetPackage).toBe('')
  })

  it('状态缺必填字段时抛错，而不是补成未运行', () => {
    // displayId、帧率、刷新率这些读数是「到底跑没跑」的判断依据：缺了就抛错。
    // 文案类字段取不到是原生侧的正常取值（写 null），收敛成空串，由界面显示成「未知」。
    expect(() => validateVirtualScreenState({ active: false })).toThrow('副屏显示标识格式无效')
    expect(() => validateVirtualScreenState({ ...state(), displayRefreshRate: undefined })).toThrow('副屏实际刷新率格式无效')
    // 设置七项一个都不能少：缺了就抛错，不补成「档位 60 FPS、方向自动」这种看着正常的默认值。
    expect(() => validateVirtualScreenSettings({})).toThrow('副屏取帧档位格式无效')
    expect(() => validateVirtualScreenSettings({ ...settings(), densityDpi: undefined })).toThrow('副屏像素密度格式无效')
  })
})
