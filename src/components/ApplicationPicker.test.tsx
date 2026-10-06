import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { InstalledApplication, RuntimeBridge } from '../platform/types'
import { ApplicationPicker } from './ApplicationPicker'

function app(label: string, packageName = `com.example.${label}`, extra: Partial<InstalledApplication> = {}): InstalledApplication {
  return { label, packageName, system: false, selectable: true, ...extra }
}

function bridgeReturning(pages: Array<{ apps: InstalledApplication[]; nextOffset: number | null; total: number }>) {
  let index = 0
  const list = vi.fn().mockImplementation(() => Promise.resolve(pages[Math.min(index++, pages.length - 1)]))
  return { list, bridge: { listInstalledApplications: list } as unknown as RuntimeBridge }
}

/**
 * 展开面板。首次展开不走防抖（只有「用户改了输入」才等 200 ms），所以用例不必额外等待。
 * 返回 container 而不是用 screen 全局查询：同一条用例里 render 两次时 screen 会同时看到两份 DOM。
 */
function open(bridge: RuntimeBridge) {
  const view = render(<ApplicationPicker bridge={bridge} selected={[]} onChange={vi.fn()} disabled={false} />)
  fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
  return view
}

/** 只数应用行的复选框：过滤开关自己也是 checkbox，不能一起数进来。 */
function appBoxes(container: HTMLElement): HTMLInputElement[] {
  return Array.from(container.querySelectorAll<HTMLInputElement>('.application-picker-row input[type="checkbox"]'))
}

function listedLabels(container: HTMLElement): string[] {
  return appBoxes(container).map(box => (box.closest('label')?.querySelector('strong')?.textContent ?? '').trim())
}

describe('已安装应用选择器', () => {
  it('分页去重，增加选择时保留超过旧上限的白名单', async () => {
    const list = vi.fn().mockResolvedValueOnce({ apps: [app('reader')], nextOffset: 1, total: 2 })
      .mockResolvedValueOnce({ apps: [app('reader'), app('notes')], nextOffset: null, total: 2 })
    const selected = Array.from({ length: 40 }, (_, index) => `com.example.app${index}`)
    const onChange = vi.fn()
    const { container } = render(<ApplicationPicker bridge={{ listInstalledApplications: list } as unknown as RuntimeBridge}
      selected={selected} onChange={onChange} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    await screen.findByText('reader')
    expect(appBoxes(container)).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '加载更多应用' }))
    await screen.findByText('notes')
    // 两页都含 reader：按包名去重后只留一行。
    expect(listedLabels(container)).toEqual(['reader', 'notes'])
    fireEvent.click(appBoxes(container)[1])
    expect(onChange).toHaveBeenCalledWith([...selected, 'com.example.notes'])
    expect(list).toHaveBeenNthCalledWith(2, '', 1)
    // 已选 40 个照旧一并在白名单里：这些条目只是「已选」，界面不再给任何上限提示。
    expect(screen.getByText('已选择 40 个应用；勾选后点击保存白名单。')).toBeInTheDocument()
  })

  it('搜索更新后忽略上一条慢请求的返回值，并且不会被它清掉读取状态', async () => {
    let resolve!: (value: unknown) => void
    const list = vi.fn().mockImplementationOnce(() => new Promise(done => { resolve = done }))
      .mockResolvedValue({ apps: [app('新结果', 'com.example.newapp')], nextOffset: null, total: 1 })
    const { container } = open({ listInstalledApplications: list } as unknown as RuntimeBridge)
    await waitFor(() => expect(list).toHaveBeenCalledOnce())
    fireEvent.change(container.querySelector<HTMLInputElement>('.field input')!, { target: { value: '新' } })
    await screen.findByText('新结果')
    resolve({ apps: [app('旧结果', 'com.example.oldapp')], nextOffset: null, total: 1 })
    await waitFor(() => expect(screen.queryByText('旧结果')).not.toBeInTheDocument())
    expect(screen.getByText('新结果')).toBeInTheDocument()
    // 改前 `finally` 里的 setLoading(false) 不比 generation：旧请求回来会把新一轮的读取状态抹掉，
    // 或者反过来让它永远停在 true。
    expect(screen.queryByText('正在读取应用列表')).not.toBeInTheDocument()
  })

  it('已选 17 个也只报个数，不再提示上限或被拒绝', () => {
    const { bridge } = bridgeReturning([{ apps: [app('reader')], nextOffset: null, total: 1 }])
    const selected = Array.from({ length: 17 }, (_, index) => `com.example.app${index}`)
    const { container } = render(<ApplicationPicker bridge={bridge} selected={selected} onChange={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    expect(container.textContent).toContain('已选择 17 个应用；勾选后点击保存白名单。')
    expect(container.textContent).not.toContain('白名单最多')
    expect(container.textContent).not.toContain('超过上限')
  })

  it('条目数量不设上限：已选 60 个同样只报个数', () => {
    const { bridge } = bridgeReturning([{ apps: [app('reader')], nextOffset: null, total: 1 }])
    const selected = Array.from({ length: 60 }, (_, index) => `com.example.app${index}`)
    const { container } = render(<ApplicationPicker bridge={bridge} selected={selected} onChange={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    expect(container.textContent).toContain('已选择 60 个应用；勾选后点击保存白名单。')
    expect(container.textContent).not.toContain('上限')
  })

  it('搜索词先规范化再交给桥：全角空格、首尾空白与零宽字符都不原样发出去', async () => {
    const { list, bridge } = bridgeReturning([{ apps: [app('微信支付', 'com.tencent.mm.pay')], nextOffset: null, total: 1 }])
    const { container } = open(bridge)
    await screen.findByText('微信支付')
    fireEvent.change(container.querySelector<HTMLInputElement>('.field input')!, { target: { value: '  \u200b  wx\u3000支付  ' } })
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(list).toHaveBeenLastCalledWith('wx 支付', 0)
  })

  it('已勾选的应用排最前，其余保持桥给的顺序', async () => {
    const { bridge } = bridgeReturning([{
      apps: [app('Alipay', 'com.eg.android.AlipayGphone'), app('微信', 'com.tencent.mm'), app('支付宝', 'com.eg.android.alipay')],
      nextOffset: null, total: 3,
    }])
    const { container } = render(<ApplicationPicker bridge={bridge} selected={['com.eg.android.alipay']} onChange={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    await screen.findByText('Alipay')
    // 已勾选的「支付宝」置顶；未勾选的保持桥给的顺序，前端不重排。
    expect(listedLabels(container)).toEqual(['支付宝', 'Alipay', '微信'])
  })

  it('两个可见性开关只过滤已拿到的这一页，并说清楚隐藏了多少', async () => {
    const { list, bridge } = bridgeReturning([{
      apps: [
        app('Alipay', 'com.eg.android.AlipayGphone'),
        app('计算器', 'com.android.calculator2', { system: true, selectable: false }),
        app('系统日历', 'com.android.calendar', { system: true, selectable: true }),
      ],
      nextOffset: null, total: 3,
    }])
    const { container } = open(bridge)
    await screen.findByText('Alipay')
    expect(listedLabels(container)).toEqual(['Alipay', '计算器', '系统日历'])
    expect(screen.getByText('系统安全组件，不支持无障碍自动化')).toBeInTheDocument()
    expect(screen.getByText('系统应用')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('checkbox', { name: '只看可自动化应用' }))
    expect(listedLabels(container)).toEqual(['Alipay', '系统日历'])
    expect(screen.getByText('已隐藏 1 个应用（当前页面内）。')).toBeInTheDocument()
    // 过滤是纯前端行为：不再发桥调用（这一页本来就没有下一页）。
    expect(list).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('checkbox', { name: '隐藏系统组件' }))
    expect(listedLabels(container)).toEqual(['Alipay'])
    expect(screen.getByText('已隐藏 2 个应用（当前页面内）。')).toBeInTheDocument()
    expect(screen.getByText('当前显示 1 / 3（已加载）')).toBeInTheDocument()

    // 两个开关都取消后回到完整列表。
    fireEvent.click(screen.getByRole('checkbox', { name: '只看可自动化应用' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '隐藏系统组件' }))
    expect(listedLabels(container)).toEqual(['Alipay', '计算器', '系统日历'])
    expect(screen.getByText('当前显示 3 / 3')).toBeInTheDocument()
  })

  it('筛选后一条都不剩时，说明是筛选的问题而不是「还在读」', async () => {
    const { bridge } = bridgeReturning([{
      apps: [
        app('Alipay', 'com.eg.android.AlipayGphone', { selectable: false }),
        app('系统日历', 'com.android.calendar', { system: true, selectable: false }),
      ],
      nextOffset: null, total: 2,
    }])
    const { container } = open(bridge)
    await screen.findByText('Alipay')
    expect(container.textContent).toContain('当前显示 2 / 2')
    fireEvent.click(screen.getByRole('checkbox', { name: '只看可自动化应用' }))
    // 一条都不剩时必须说是筛选造成的，不能落到「没有匹配的应用」或者干脆显示「还在读」。
    expect(container.textContent).toContain('当前筛选条件下没有可显示的应用，取消勾选「只看可自动化应用」或「隐藏系统组件」可看到全部。')
    expect(container.textContent).toContain('已隐藏 2 个应用（当前页面内）。')
    expect(container.textContent).not.toContain('没有匹配的应用')
    expect(screen.queryByText('正在读取应用列表')).not.toBeInTheDocument()
  })

  it('空结果显示「没有匹配的应用」，清空搜索框后回到完整列表', async () => {
    const list = vi.fn().mockImplementation((query: string) => Promise.resolve(query
      ? { apps: [], nextOffset: null, total: 0 }
      : { apps: [app('Alipay', 'com.eg.android.AlipayGphone')], nextOffset: null, total: 1 }))
    const { container } = open({ listInstalledApplications: list } as unknown as RuntimeBridge)
    await screen.findByText('Alipay')
    const input = container.querySelector<HTMLInputElement>('.field input')!

    fireEvent.change(input, { target: { value: 'wx' } })
    await screen.findByText('没有匹配的应用')
    expect(screen.queryByText('正在读取应用列表')).not.toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith('wx', 0)

    fireEvent.change(input, { target: { value: '' } })
    await screen.findByText('Alipay')
    expect(screen.queryByText('没有匹配的应用')).not.toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith('', 0)
  })

  it('父组件每次渲染都新建 bridge 对象时不会重复请求（原「永远在读取」的触发条件）', async () => {
    const first = bridgeReturning([{ apps: [app('reader', 'com.example.reader')], nextOffset: null, total: 1 }])
    const second = bridgeReturning([{ apps: [app('notes', 'com.example.notes')], nextOffset: null, total: 1 }])
    const view = render(<ApplicationPicker bridge={first.bridge} selected={[]} onChange={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    await screen.findByText('reader')
    const callsAfterOpen = first.list.mock.calls.length

    // 模拟父组件重渲染：新的 bridge 引用。effect 不该因此重启。
    view.rerender(<ApplicationPicker bridge={second.bridge} selected={[]} onChange={vi.fn()} disabled={false} />)
    expect(second.list).not.toHaveBeenCalled()
    expect(first.list).toHaveBeenCalledTimes(callsAfterOpen)
    expect(screen.getByText('reader')).toBeInTheDocument()

    // 但真正需要请求时用的是**最新**那个桥。
    fireEvent.change(view.container.querySelector<HTMLInputElement>('.field input')!, { target: { value: 'notes' } })
    await screen.findByText('notes')
    expect(second.list).toHaveBeenCalledWith('notes', 0)
  })

  it('读取失败时给出重试入口，重试后能拿到完整列表', async () => {
    const list = vi.fn().mockRejectedValueOnce(new Error('桥不可用'))
      .mockResolvedValue({ apps: [app('reader', 'com.example.reader')], nextOffset: null, total: 1 })
    open({ listInstalledApplications: list } as unknown as RuntimeBridge)
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await screen.findByText('reader')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(list).toHaveBeenCalledTimes(2)
  })
})
