import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HighRiskPermissionsCard } from './HighRiskPermissionsCard'
import { resetLanguageSession, saveLanguage } from '../i18n'
import {
  HIGH_RISK_PERMISSION_CATEGORIES,
  type HighRiskPermissionCategory,
  type HighRiskPermissions,
  type HighRiskPermissionsUpdate,
  type RuntimeBridge,
} from '../platform/types'

function makePermissions(
  enabled = false,
  overrides: Partial<Record<HighRiskPermissionCategory, boolean>> = {},
): HighRiskPermissions {
  const categories = Object.fromEntries(
    HIGH_RISK_PERMISSION_CATEGORIES.map(category => [category, overrides[category] === true]),
  ) as Record<HighRiskPermissionCategory, boolean>
  return { enabled, categories }
}

function makeBridge(initial: HighRiskPermissions = makePermissions()) {
  let current = initial
  const getHighRiskPermissions = vi.fn().mockImplementation(() => Promise.resolve(current))
  const setHighRiskPermissions = vi.fn().mockImplementation((update: HighRiskPermissionsUpdate) => {
    current = {
      enabled: update.enabled ?? current.enabled,
      categories: {
        ...current.categories,
        ...(update.categories ?? {}),
      },
    }
    return Promise.resolve(current)
  })
  return {
    bridge: {
      getHighRiskPermissions,
      setHighRiskPermissions,
    } as unknown as RuntimeBridge,
    getHighRiskPermissions,
    setHighRiskPermissions,
  }
}

describe('HighRiskPermissionsCard', () => {
  beforeEach(() => {
    window.localStorage.clear()
    resetLanguageSession()
    saveLanguage('zh-CN')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
    resetLanguageSession()
  })

  it('默认总开关关闭、17 项细分开关全部关闭且不可点击', async () => {
    const { bridge } = makeBridge()
    render(<HighRiskPermissionsCard bridge={bridge} />)

    expect(await screen.findByText('总开关已关 · 全部拦截')).toBeVisible()
    const masterSwitch = screen.getByRole('switch', { name: '允许 AI 申请高危文件与命令权限（总开关）' })
    expect(masterSwitch).not.toBeChecked()
    expect(masterSwitch).toBeEnabled()

    // 总共 1 个总开关 + 17 个细分开关 = 18 个 switch
    const allSwitches = screen.getAllByRole('switch')
    expect(allSwitches).toHaveLength(18)

    const subSwitches = allSwitches.filter(el => el !== masterSwitch)
    expect(subSwitches).toHaveLength(17)
    for (const sw of subSwitches) {
      expect(sw).not.toBeChecked()
      expect(sw).toBeDisabled()
    }

    expect(screen.getByRole('button', { name: '全部开启' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '全部关闭' })).toBeDisabled()
  })

  it('开启总开关后可单独开关细分权限，并支持全部开启/全部关闭', async () => {
    const { bridge, setHighRiskPermissions } = makeBridge()
    render(<HighRiskPermissionsCard bridge={bridge} />)

    const masterSwitch = await screen.findByRole('switch', {
      name: '允许 AI 申请高危文件与命令权限（总开关）',
    })
    fireEvent.click(masterSwitch)

    await waitFor(() => {
      expect(setHighRiskPermissions).toHaveBeenCalledWith({ enabled: true })
      expect(screen.getByText('已放行 0 / 17 项')).toBeVisible()
    })

    const powerSwitch = screen.getByRole('switch', { name: '设备重启、关机与电源触发' })
    expect(powerSwitch).toBeEnabled()
    fireEvent.click(powerSwitch)

    await waitFor(() => {
      expect(setHighRiskPermissions).toHaveBeenLastCalledWith({
        categories: { device_power_control: true },
      })
      expect(screen.getByText('已放行 1 / 17 项')).toBeVisible()
      expect(powerSwitch).toBeChecked()
    })

    fireEvent.click(screen.getByRole('button', { name: '全部开启' }))
    await waitFor(() => {
      expect(screen.getByText('已放行 17 / 17 项')).toBeVisible()
    })
    expect(screen.getByRole('button', { name: '全部开启' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '全部关闭' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: '全部关闭' }))
    await waitFor(() => {
      expect(screen.getByText('已放行 0 / 17 项')).toBeVisible()
    })
  })

  it('保存失败时显示错误并回读设备真实状态；读取失败时提供重试按钮', async () => {
    const { bridge, getHighRiskPermissions, setHighRiskPermissions } = makeBridge()
    setHighRiskPermissions.mockRejectedValueOnce(new Error('磁盘写入失败'))

    render(<HighRiskPermissionsCard bridge={bridge} />)
    const masterSwitch = await screen.findByRole('switch', {
      name: '允许 AI 申请高危文件与命令权限（总开关）',
    })
    fireEvent.click(masterSwitch)

    expect(await screen.findByRole('alert')).toHaveTextContent('保存高危文件与命令权限失败：磁盘写入失败')
    await waitFor(() => {
      expect(getHighRiskPermissions).toHaveBeenCalledTimes(2)
    })

    getHighRiskPermissions.mockRejectedValueOnce(new Error('桥接不可用'))
    const { unmount } = render(<HighRiskPermissionsCard bridge={bridge} />)
    expect(await screen.findByText('无法读取高危文件与命令权限设置')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => {
      expect(screen.getAllByText('总开关已关 · 全部拦截').length).toBeGreaterThanOrEqual(2)
    })
    unmount()
  })

  it('英文界面完整翻译标题、总开关与 17 项细分项', async () => {
    saveLanguage('en')
    const { bridge } = makeBridge(makePermissions(true, { core_partition_write: true }))
    render(<HighRiskPermissionsCard bridge={bridge} />)

    expect(await screen.findByRole('heading', { name: 'AI High-Risk File & Command Permissions' })).toBeVisible()
    expect(screen.getByText('1 / 17 allowed')).toBeVisible()
    expect(screen.getByRole('switch', { name: 'Core system partition write & rw remount' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Physical block devices, partitioning, flashing & raw memory access' })).not.toBeChecked()
  })
})
