import { useEffect, useState } from 'react'
import { FileText, LogOut, ShieldCheck, X } from 'lucide-react'
import { t } from '../i18n'
import { APP_DISCLAIMER_POINTS, APP_DISCLAIMER_VERSION } from '../appDisclaimer'

interface AppDisclaimerProps {
  /** `gate`：首次启动强门禁，必须同意才能进入，不同意则直接退出；`read`：设置页随时回看。 */
  mode: 'gate' | 'read'
  /** 仅 `gate` 模式：用户点「同意并继续」时回调。 */
  onAccept?: () => void
  /** 仅 `gate` 模式：用户点「不同意并退出」时回调。 */
  onDecline?: () => void
  /** 仅 `read` 模式：关闭回看弹窗时回调。 */
  onClose?: () => void
  /** 仅 `gate` 模式：已触发退出但当前环境（如浏览器标签页）无法自动关闭时展示提示。 */
  declinedFallback?: boolean
}

function renderDisclaimerPoint(text: string) {
  const match = /^([^:：]{2,48})([:：]\s*)(.*)$/u.exec(text)
  if (match === null) return text
  return (
    <>
      <strong>{match[1]}{match[2]}</strong>
      {match[3]}
    </>
  )
}

export function AppDisclaimer({
  mode,
  onAccept,
  onDecline,
  onClose,
  declinedFallback = false,
}: AppDisclaimerProps) {
  const [checked, setChecked] = useState(false)
  const gating = mode === 'gate'

  useEffect(() => {
    if (gating || onClose === undefined) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [gating, onClose])

  return (
    <div
      className="dialog-backdrop app-disclaimer-backdrop"
      role="presentation"
      onPointerDown={event => {
        if (!gating && event.target === event.currentTarget) onClose?.()
      }}
    >
      <div
        className="dialog app-disclaimer-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="app-disclaimer-title"
        data-testid="app-disclaimer"
      >
        {!gating && (
          <button className="dialog-close" type="button" aria-label={t('关闭')} onClick={onClose}>
            <X size={19} />
          </button>
        )}
        <span className="dialog-info-icon"><FileText size={23} /></span>
        <h2 id="app-disclaimer-title">{t('免责条款')}</h2>
        <div className="app-disclaimer-body">
          <p>
            {gating
              ? t('在使用 DeepSeek Harness 移动端前，请您仔细阅读并充分理解以下免责条款（特别是涉及风险告知与责任限制的内容）。如您不同意，本应用将直接退出；同意后可随时在「设置 → 关于与反馈」中查看。')
              : t('以下为 DeepSeek Harness 移动端的免责条款与使用风险告知，您可随时在此回看。')}
          </p>
          <ol>
            {APP_DISCLAIMER_POINTS.map(point => <li key={point}>{renderDisclaimerPoint(t(point))}</li>)}
          </ol>
          <p className="app-disclaimer-version" data-testid="app-disclaimer-version">
            {t('条款版本：{0}（条款内容更新后会重新请你确认）', APP_DISCLAIMER_VERSION)}
          </p>
        </div>

        {gating ? (
          <>
            {declinedFallback && (
              <p className="app-disclaimer-exit-note" role="alert">
                {t('未同意免责条款无法使用本应用。若应用未自动关闭，请手动关闭当前页面或退出应用。')}
              </p>
            )}
            <label className="disclaimer-confirm">
              <input
                type="checkbox"
                checked={checked}
                onChange={event => setChecked(event.target.checked)}
              />
              <span>{t('我已完整阅读、充分理解并同意以上免责条款')}</span>
            </label>
            <div className="dialog-actions">
              <button className="button button-secondary" type="button" onClick={onDecline}>
                <LogOut size={18} />{t('不同意并退出')}
              </button>
              <button className="button button-primary" type="button" disabled={!checked} onClick={onAccept}>
                <ShieldCheck size={18} />{t('同意并继续')}
              </button>
            </div>
          </>
        ) : (
          <div className="dialog-actions">
            <button className="button button-secondary" type="button" onClick={onClose}>{t('知道了')}</button>
          </div>
        )}
      </div>
    </div>
  )
}
