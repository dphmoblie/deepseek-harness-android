import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { APP_DISCLAIMER_POINTS, APP_DISCLAIMER_VERSION } from '../appDisclaimer'
import { resetLanguageSession } from '../i18n'
import { AppDisclaimer } from './AppDisclaimer'

describe('AppDisclaimer 应用级免责条款弹窗', () => {
  beforeEach(() => {
    window.localStorage.clear()
    resetLanguageSession()
    window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
  })

  afterEach(() => vi.restoreAllMocks())

  it('首启门禁模式：展示全部条款与版本号，未勾选不能继续，不同意直接触发退出且不可点背景/Esc 绕过', () => {
    const onAccept = vi.fn()
    const onDecline = vi.fn()
    const onClose = vi.fn()
    const { container } = render(
      <AppDisclaimer mode="gate" onAccept={onAccept} onDecline={onDecline} onClose={onClose} />,
    )

    const dialog = screen.getByTestId('app-disclaimer')
    for (const point of APP_DISCLAIMER_POINTS) {
      expect(dialog).toHaveTextContent(point)
    }
    expect(screen.getByTestId('app-disclaimer-version'))
      .toHaveTextContent(`条款版本：${APP_DISCLAIMER_VERSION}（条款内容更新后会重新请你确认）`)

    // 门禁模式没有右上角关闭按钮，点背景与按 Escape 都不能绕过。
    expect(screen.queryByRole('button', { name: '关闭' })).toBeNull()
    fireEvent.pointerDown(container.querySelector('.dialog-backdrop') as HTMLElement)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    expect(onAccept).not.toHaveBeenCalled()
    expect(onDecline).not.toHaveBeenCalled()

    const acceptButton = screen.getByRole('button', { name: '同意并继续' })
    expect(acceptButton).toBeDisabled()
    fireEvent.click(acceptButton)
    expect(onAccept).not.toHaveBeenCalled()

    // 未勾选也可以直接点「不同意并退出」。
    fireEvent.click(screen.getByRole('button', { name: '不同意并退出' }))
    expect(onDecline).toHaveBeenCalledTimes(1)
    expect(onAccept).not.toHaveBeenCalled()

    // 勾选后才放行「同意并继续」。
    fireEvent.click(screen.getByLabelText('我已完整阅读、充分理解并同意以上免责条款'))
    expect(acceptButton).toBeEnabled()
    fireEvent.click(acceptButton)
    expect(onAccept).toHaveBeenCalledTimes(1)
  })

  it('首启门禁模式：无法自动关闭时展示手动退出提示', () => {
    render(<AppDisclaimer mode="gate" declinedFallback onAccept={vi.fn()} onDecline={vi.fn()} />)
    expect(screen.getByRole('alert'))
      .toHaveTextContent('未同意免责条款无法使用本应用。若应用未自动关闭，请手动关闭当前页面或退出应用。')
  })

  it('设置回看模式：只读展示，支持按钮、右上角关闭、点背景与 Escape 关闭', () => {
    const onClose = vi.fn()
    const { container } = render(<AppDisclaimer mode="read" onClose={onClose} />)

    expect(screen.getByRole('dialog', { name: '免责条款' })).toBeVisible()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: '不同意并退出' })).toBeNull()
    expect(screen.queryByRole('button', { name: '同意并继续' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '知道了' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(onClose).toHaveBeenCalledTimes(2)

    fireEvent.pointerDown(container.querySelector('.dialog-backdrop') as HTMLElement)
    expect(onClose).toHaveBeenCalledTimes(3)

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(4)
  })

  it.each([
    { language: 'zh-CN', statutoryPhrase: '故意或重大过失', forbidden: null as RegExp | null },
    { language: 'zh-TW', statutoryPhrase: '故意或重大過失', forbidden: /移动端|本应用/ },
    { language: 'en', statutoryPhrase: 'willful misconduct, gross negligence', forbidden: /[\u4e00-\u9fff]/ },
    { language: 'ja', statutoryPhrase: '故意または重過失', forbidden: /本应用|免责条款/ },
    { language: 'ko', statutoryPhrase: '고의 또는 중대한 과실', forbidden: /[\u4e00-\u9fff]/ },
  ])('$language 语言下条款包含法定不可免责保留条款与加粗标题，且无混用原文残留', ({ language, statutoryPhrase, forbidden }) => {
    window.localStorage.setItem('dsh-mobile-language-v1', language)
    const gate = render(<AppDisclaimer mode="gate" declinedFallback onAccept={vi.fn()} onDecline={vi.fn()} />)
    const gateDialog = screen.getByTestId('app-disclaimer')
    expect(gateDialog).toHaveTextContent(statutoryPhrase)
    expect(gateDialog.querySelectorAll('ol li strong')).toHaveLength(APP_DISCLAIMER_POINTS.length)
    if (forbidden !== null) expect(gateDialog.textContent ?? '').not.toMatch(forbidden)
    gate.unmount()

    render(<AppDisclaimer mode="read" onClose={vi.fn()} />)
    const readDialog = screen.getByTestId('app-disclaimer')
    expect(readDialog).toHaveTextContent(statutoryPhrase)
    if (forbidden !== null) expect(readDialog.textContent ?? '').not.toMatch(forbidden)
  })
})
