import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AutomationDisclaimer } from './AutomationDisclaimer'
import { AUTOMATION_DISCLAIMER_VERSION } from '../accessibilityDisclaimer'

/**
 * 免责说明弹窗。
 *
 * 两条契约：**确认模式**下勾选是开启的前提（没勾选，确认按钮按不动，也不回调）；
 * **回看模式**下只有关闭出口，不改任何状态。六条要点逐条钉在用例里——它们对应
 * `docs/AI自动化参数设计.md` §7.1 的六条，掉了一条就说明文案被改动了。
 */
describe('免责说明弹窗', () => {
  beforeEach(() => window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN'))
  afterEach(() => vi.restoreAllMocks())

  it('确认模式：六条要点逐条在，未勾选时确认按钮按不动', () => {
    const onConfirm = vi.fn()
    render(<AutomationDisclaimer mode="confirm" onConfirm={onConfirm} onCancel={vi.fn()} />)

    const dialog = screen.getByTestId('automation-disclaimer')
    for (const point of [
      '自动化操作可能误点：界面、文案与控件标识会随应用更新而变化，规则可能在你没有预期的地方生效，也可能在你预期的地方失效。',
      '涉及支付、验证码、密码、生物识别与权限申请的页面一律会被跳过，且这一限制没有开关；但「被跳过」不等于「界面绝对安全」，请不要依赖本功能替你判断风险。',
      '请自行确认每条规则的作用范围与次数上限；因规则配置错误、来源不明或与界面不匹配所导致的后果，由使用者自行承担。',
      '本功能默认关闭，需要你在应用内显式开启，并在系统设置里手动授予无障碍权限；关闭后立即停止自动操作。',
      '本功能不收集、不上传你的界面内容、输入内容与规则内容；本机只记录「发生了一次动作、结果是成功还是失败」这类不含内容的审计条目。',
      '请勿将本功能用于违反应用服务条款、绕过付费或验证、刷量、抢购等用途。',
    ]) {
      expect(dialog).toHaveTextContent(point)
    }

    const confirmButton = screen.getByRole('button', { name: '我已了解，开启自动化' })
    expect(confirmButton).toBeDisabled()
    fireEvent.click(confirmButton)
    expect(onConfirm).not.toHaveBeenCalled()

    // 勾选之后才放行，回调只发生一次。
    fireEvent.click(screen.getByLabelText('我已阅读并理解上述说明，并自行承担规则配置与使用带来的后果'))
    expect(confirmButton).toBeEnabled()
    fireEvent.click(confirmButton)
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('确认模式：取消与点背景都只走取消，不回调确认', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    const { container } = render(<AutomationDisclaimer mode="confirm" onConfirm={onConfirm} onCancel={onCancel} />)

    fireEvent.click(screen.getByRole('button', { name: '暂不开启' }))
    expect(onCancel).toHaveBeenCalledTimes(1)

    // 点弹窗外的背景等于放弃：不能顺手把自动化开起来。
    const backdrop = container.querySelector('.dialog-backdrop') as HTMLElement
    fireEvent.pointerDown(backdrop)
    expect(onCancel).toHaveBeenCalledTimes(2)
    expect(onConfirm).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(onCancel).toHaveBeenCalledTimes(3)
  })

  it('每次打开都要重新勾选：勾选状态不跨次保留', () => {
    const onConfirm = vi.fn()
    const { unmount } = render(<AutomationDisclaimer mode="confirm" onConfirm={onConfirm} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByLabelText('我已阅读并理解上述说明，并自行承担规则配置与使用带来的后果'))
    expect(screen.getByRole('button', { name: '我已了解，开启自动化' })).toBeEnabled()
    unmount()

    render(<AutomationDisclaimer mode="confirm" onConfirm={onConfirm} onCancel={vi.fn()} />)
    expect(screen.getByRole('button', { name: '我已了解，开启自动化' })).toBeDisabled()
  })

  it('回看模式：没有勾选与确认按钮，只有「知道了」', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<AutomationDisclaimer mode="read" onConfirm={onConfirm} onCancel={onCancel} />)

    expect(screen.getByTestId('automation-disclaimer')).toHaveTextContent('关于自动化（无障碍）功能')
    expect(screen.queryByLabelText('我已阅读并理解上述说明，并自行承担规则配置与使用带来的后果')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '我已了解，开启自动化' })).not.toBeInTheDocument()

    // 回看模式的按钮刻意不叫「关闭」：右上角叉的 aria-label 就是「关闭」，同名会撞车。
    fireEvent.click(screen.getByRole('button', { name: '知道了' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })

  /**
   * 版本号要印在弹窗里，而且必须与写进确认记录的那个版本号是同一个常量：
   * 两处各写一份数字的话，改了要点却忘了升版本号，用户看到的是新版文字、
   * 手里拿着的是旧版同意。
   */
  it('弹窗里印着确认记录用的版本号', () => {
    render(<AutomationDisclaimer mode="read" onCancel={vi.fn()} />)
    expect(screen.getByTestId('automation-disclaimer-version'))
      .toHaveTextContent(`说明版本：${AUTOMATION_DISCLAIMER_VERSION}（这六条要点改写后会重新请你确认）`)
  })
})
