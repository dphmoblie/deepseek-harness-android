import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { ApplicationPicker } from './ApplicationPicker'

describe('已安装应用选择器', () => {
  it('分页去重，增加选择时保留超过旧上限的白名单', async () => {
    const app = (name: string) => ({ label: name, packageName: `com.example.${name}`, selectable: true })
    const list = vi.fn().mockResolvedValueOnce({ apps: [app('reader')], nextOffset: 1, total: 2 })
      .mockResolvedValueOnce({ apps: [app('reader'), app('notes')], nextOffset: null, total: 2 })
    const selected = Array.from({ length: 40 }, (_, index) => `com.example.app${index}`)
    const onChange = vi.fn()
    render(<ApplicationPicker bridge={{ listInstalledApplications: list } as unknown as RuntimeBridge}
      selected={selected} onChange={onChange} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    await screen.findByText('reader')
    fireEvent.click(screen.getByRole('button', { name: '加载更多应用' }))
    await screen.findByText('notes')
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    fireEvent.click(screen.getByRole('checkbox', { name: /notes/ }))
    expect(onChange).toHaveBeenCalledWith([...selected, 'com.example.notes'])
    expect(list).toHaveBeenNthCalledWith(2, '', 1)
  })

  it('搜索更新后忽略上一条慢请求的返回值', async () => {
    let resolve!: (value: unknown) => void
    const list = vi.fn().mockImplementationOnce(() => new Promise(done => { resolve = done }))
      .mockResolvedValue({ apps: [{ label: '新结果', packageName: 'com.example.newapp', selectable: true }], nextOffset: null, total: 1 })
    render(<ApplicationPicker bridge={{ listInstalledApplications: list } as unknown as RuntimeBridge}
      selected={[]} onChange={vi.fn()} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '从已安装应用选择' }))
    await waitFor(() => expect(list).toHaveBeenCalledOnce())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '新' } })
    await screen.findByText('新结果')
    resolve({ apps: [{ label: '旧结果', packageName: 'com.example.oldapp', selectable: true }], nextOffset: null, total: 1 })
    await waitFor(() => expect(screen.queryByText('旧结果')).not.toBeInTheDocument())
    expect(screen.getByText('新结果')).toBeInTheDocument()
  })
})
