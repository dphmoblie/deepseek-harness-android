import { Capacitor } from '@capacitor/core'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'
import type { AppOrientationMode } from '../platform/types'

const api = vi.hoisted(() => ({ getAppOrientation: vi.fn(), setAppOrientation: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: api }))
import { AppOrientationSettings } from './AppOrientationSettings'

describe('应用方向设置', () => {
  beforeEach(() => {
    saveLanguage('zh-CN')
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true)
    api.getAppOrientation.mockReset().mockResolvedValue('system')
    api.setAppOrientation.mockReset().mockImplementation((mode: AppOrientationMode) => Promise.resolve(mode))
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('读回已保存的方向，切换后再次打开仍按原生返回值显示', async () => {
    let saved: AppOrientationMode = 'portrait'
    api.getAppOrientation.mockImplementation(() => Promise.resolve(saved))
    api.setAppOrientation.mockImplementation((mode: AppOrientationMode) => { saved = mode; return Promise.resolve(saved) })
    const first = render(<AppOrientationSettings />)
    await waitFor(() => expect(screen.getByRole('radio', { name: '竖屏' })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: '横屏' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: '横屏' })).toBeChecked())
    expect(api.setAppOrientation).toHaveBeenCalledOnce()
    expect(api.setAppOrientation).toHaveBeenCalledWith('landscape')
    first.unmount()
    render(<AppOrientationSettings />)
    await waitFor(() => expect(screen.getByRole('radio', { name: '横屏' })).toBeChecked())
    expect(api.getAppOrientation).toHaveBeenCalledTimes(2)
  })

  it('读取完成前禁用控件，保存完成前保留旧选项并阻止重复申请', async () => {
    let read!: (value: AppOrientationMode) => void
    let save!: (value: AppOrientationMode) => void
    api.getAppOrientation.mockImplementation(() => new Promise(resolve => { read = resolve }))
    api.setAppOrientation.mockImplementation(() => new Promise(resolve => { save = resolve }))
    render(<AppOrientationSettings />)
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    fireEvent.click(screen.getByRole('radio', { name: '横屏' }))
    expect(api.setAppOrientation).not.toHaveBeenCalled()
    await act(() => { read('portrait'); return Promise.resolve() })
    const landscape = screen.getByRole('radio', { name: '横屏' })
    fireEvent.click(landscape)
    fireEvent.click(landscape)
    expect(screen.getByRole('radio', { name: '竖屏' })).toBeChecked()
    expect(landscape).not.toBeChecked()
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(api.setAppOrientation).toHaveBeenCalledOnce()
    await act(() => { save('landscape'); return Promise.resolve() })
    expect(landscape).toBeChecked()
    expect(landscape).toBeEnabled()
  })

  it('读取失败时如实显示错误，重试成功后才允许选择', async () => {
    api.getAppOrientation.mockRejectedValueOnce(new Error('read failed')).mockResolvedValue('portrait')
    render(<AppOrientationSettings />)
    expect(await screen.findByRole('alert')).toHaveTextContent('无法读取应用方向，请重试。')
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: '竖屏' })).toBeChecked())
    expect(screen.getByRole('radio', { name: '横屏' })).toBeEnabled()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('保存失败后保留原方向，显示错误并允许重试', async () => {
    api.setAppOrientation.mockRejectedValueOnce(new Error('save failed'))
    render(<AppOrientationSettings />)
    await waitFor(() => expect(screen.getByRole('radio', { name: '跟随系统' })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: '横屏' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('无法保存应用方向，请重试。')
    expect(screen.getByRole('radio', { name: '跟随系统' })).toBeChecked()
    fireEvent.click(screen.getByRole('radio', { name: '横屏' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: '横屏' })).toBeChecked())
    expect(api.setAppOrientation).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('浏览器预览不显示原生方向控件，也不调用设备桥', () => {
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(false)
    const view = render(<AppOrientationSettings />)
    expect(view.container).toBeEmptyDOMElement()
    expect(api.getAppOrientation).not.toHaveBeenCalled()
    expect(api.setAppOrientation).not.toHaveBeenCalled()
  })
})
