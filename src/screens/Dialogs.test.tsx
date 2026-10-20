import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionGuideDialog, type PermissionGuideDialogProps } from './Dialogs'
import type { PermissionGuide } from '../ui/permissionGuide'

/**
 * 系统权限指引弹窗。
 *
 * 这里钉的是「不同类型的文案各说各的对象」这条契约：
 * 后台拦截列出四类限制与各家入口，通知权限只建议开启、不谎称没有就起不来，
 * 悬浮球界面创建失败没有系统页可去。文案口径的来龙去脉见 `docs/后台保持与恢复.md`
 * 的「厂商后台策略」一节；掉一个厂商或改回「无法启动」都会让用例变红。
 */
function renderGuide(guide: PermissionGuide, overrides: Partial<PermissionGuideDialogProps> = {}) {
  const props = {
    guide,
    busy: false,
    onClose: vi.fn(),
    onOpenOverlaySettings: vi.fn(),
    onOpenNotificationSettings: vi.fn(),
    onOpenApplicationSettings: vi.fn(),
    onOpenShizuku: vi.fn(),
    ...overrides,
  }
  render(<PermissionGuideDialog {...props} />)
  return { props, dialog: screen.getByRole('dialog') }
}

describe('系统权限指引弹窗', () => {
  beforeEach(() => window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN'))
  afterEach(() => vi.restoreAllMocks())

  it('后台保持被拦：五个步骤点名六家 ROM，跳转落在应用详情页', () => {
    const { props, dialog } = renderGuide({ kind: 'background-blocked', feature: 'keepAlive' })

    expect(dialog).toHaveTextContent('前台服务被系统拦截')
    expect(dialog).toHaveTextContent('系统没有允许「后台保持」的前台服务启动')

    // 五条步骤：自启动 / 后台运行 / 电池优化 / 任务锁 / 荣耀·华为·三星。
    const steps = Array.from(dialog.querySelectorAll('li')).map(item => item.textContent ?? '')
    expect(steps).toHaveLength(5)
    for (const vendor of ['HyperOS', 'ColorOS', 'OriginOS', '荣耀', '华为', '三星']) {
      expect(dialog).toHaveTextContent(vendor)
    }
    expect(steps[0]).toContain('自启动管理')
    expect(steps[1]).toContain('后台耗电管理')
    expect(steps[2]).toContain('耗电异常优化')
    expect(steps[3]).toContain('锁定')
    expect(steps[4]).toContain('从不休眠的应用')
    // 华为与荣耀的后台开关名不同，别合并成一个词。
    expect(steps[4]).toContain('允许后台活动')
    expect(steps[4]).toContain('允许后台启动')

    expect(dialog).toHaveTextContent('搜「自启动」或「耗电管理」')

    fireEvent.click(screen.getByRole('button', { name: /打开应用详情页/ }))
    expect(props.onOpenApplicationSettings).toHaveBeenCalledTimes(1)
    expect(props.onOpenApplicationSettings).toHaveBeenCalledWith(expect.anything())
  })

  it('悬浮球被拦：同一套步骤，但主语换成悬浮球', () => {
    const { dialog } = renderGuide({ kind: 'background-blocked', feature: 'overlayBall' })

    expect(dialog).toHaveTextContent('系统没有允许悬浮球的前台服务启动')
    expect(dialog).not.toHaveTextContent('「后台保持」的前台服务')
    expect(dialog.querySelectorAll('li')).toHaveLength(5)
  })

  it('通知权限未开启：只说通知看不到，不说服务起不来', () => {
    const { props, dialog } = renderGuide({ kind: 'notification-permission' })

    expect(dialog).toHaveTextContent('后台保持的通知权限未开启')
    expect(dialog).toHaveTextContent('服务仍能启动')
    expect(dialog).not.toHaveTextContent('无法启动')
    // 诊断建议要指向设置页的状态行，而不是让用户凭通知判断。
    expect(dialog).toHaveTextContent('「前台服务」状态行')

    fireEvent.click(screen.getByRole('button', { name: /前往开启通知/ }))
    expect(props.onOpenNotificationSettings).toHaveBeenCalledTimes(1)
  })

  it('悬浮窗权限缺失：给系统页入口，不提 ROM 限制', () => {
    const { props, dialog } = renderGuide({ kind: 'overlay-permission' })

    expect(dialog).toHaveTextContent('显示在其他应用上层')
    expect(dialog).not.toHaveTextContent('HyperOS')

    fireEvent.click(screen.getByRole('button', { name: /前往开启悬浮窗权限/ }))
    expect(props.onOpenOverlaySettings).toHaveBeenCalledTimes(1)
  })

  it('悬浮球界面创建失败：没有系统页可去，只剩「我知道了」', () => {
    const { dialog } = renderGuide({ kind: 'overlay-internal' })

    expect(dialog).toHaveTextContent('悬浮球界面在当前系统上创建失败')
    expect(screen.getByRole('button', { name: '我知道了' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: /前往|打开应用详情页/ })).toBeNull()
  })

  it('Shizuku 未授权：跳到 Shizuku 完成授权', () => {
    const { props, dialog } = renderGuide({ kind: 'shizuku-permission' })

    expect(dialog).toHaveTextContent('需要先授权 Shizuku')

    fireEvent.click(screen.getByRole('button', { name: /前往授权 Shizuku/ }))
    expect(props.onOpenShizuku).toHaveBeenCalledTimes(1)
  })

  it('忙碌时关闭按钮不可点，避免请求进行中把弹窗关掉', () => {
    const { props } = renderGuide({ kind: 'overlay-permission' }, { busy: true })

    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(props.onClose).not.toHaveBeenCalled()
  })
})
