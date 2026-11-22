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
    {
      language: 'zh-CN',
      requiredPhrases: ['故意或重大过失', '条款修改与版本更新', '权利保留条款', '条款解释与效力'],
      forbidden: /最终解释权/ as RegExp | null,
    },
    {
      language: 'zh-TW',
      requiredPhrases: ['故意或重大過失', '條款修訂與版本更新', '權利保留條款', '條款解釋與效力'],
      forbidden: /移动端|本应用|最終解釋權/,
    },
    {
      language: 'en',
      requiredPhrases: [
        'willful misconduct, gross negligence',
        'Amendments and version updates',
        'Reservation of rights and non-waiver',
        'Interpretation, language, and severability',
      ],
      forbidden: /[\u4e00-\u9fff]/,
    },
    {
      language: 'ja',
      requiredPhrases: ['故意または重過失', '規約の改定およびバージョン更新', '権利の留保および不放棄', '条項の解釈、言語版および分離可能性'],
      forbidden: /本应用|免责条款/,
    },
    {
      language: 'ko',
      requiredPhrases: ['고의 또는 중대한 과실', '약관의 변경 및 버전 업데이트', '권리 유보 및 불포기', '조항 해석, 언어본 및 분리 가능성'],
      forbidden: /[\u4e00-\u9fff]/,
    },
  ])('$language 语言下条款包含法定保留、条款修改、权利保留与合法解释条款，且无混用原文残留', ({ language, requiredPhrases, forbidden }) => {
    window.localStorage.setItem('dsh-mobile-language-v1', language)
    const gate = render(<AppDisclaimer mode="gate" declinedFallback onAccept={vi.fn()} onDecline={vi.fn()} />)
    const gateDialog = screen.getByTestId('app-disclaimer')
    for (const phrase of requiredPhrases) {
      expect(gateDialog).toHaveTextContent(phrase)
    }
    expect(gateDialog.querySelectorAll('ol li strong')).toHaveLength(APP_DISCLAIMER_POINTS.length)
    if (forbidden !== null) expect(gateDialog.textContent ?? '').not.toMatch(forbidden)
    gate.unmount()

    render(<AppDisclaimer mode="read" onClose={vi.fn()} />)
    const readDialog = screen.getByTestId('app-disclaimer')
    for (const phrase of requiredPhrases) {
      expect(readDialog).toHaveTextContent(phrase)
    }
    if (forbidden !== null) expect(readDialog.textContent ?? '').not.toMatch(forbidden)
  })
})
