import { useState } from 'react'
import { ShieldCheck, X } from 'lucide-react'
import { t } from '../i18n'

/**
 * 免责说明的两处入口共用同一段文字，原文在 `docs/AI自动化参数设计.md` §7.1（英文见 §7.2），
 * 逐条对应、不增删要点：
 *
 *  - `confirm`：第一次按「开始 / 继续」时的闸门。**勾选是开启的前提**，确认之后才真的去开总开关。
 *  - `read`：随时回看，只有一个「关闭」——看不看都不影响总开关，也不写任何状态。
 *
 * 这段话刻意不给任何安全承诺：要说清的是「可能误点」「跳过敏感页面不等于界面安全」
 * 「责任由使用者承担」，而不是让人以为开了这个功能就有人替他兜着。
 */
const DISCLAIMER_POINTS = [
  '自动化操作可能误点：界面、文案与控件标识会随应用更新而变化，规则可能在你没有预期的地方生效，也可能在你预期的地方失效。',
  '涉及支付、验证码、密码、生物识别与权限申请的页面一律会被跳过，且这一限制没有开关；但「被跳过」不等于「界面绝对安全」，请不要依赖本功能替你判断风险。',
  '请自行确认每条规则的作用范围与次数上限；因规则配置错误、来源不明或与界面不匹配所导致的后果，由使用者自行承担。',
  '本功能默认关闭，需要你在应用内显式开启，并在系统设置里手动授予无障碍权限；关闭后立即停止自动操作。',
  '本功能不收集、不上传你的界面内容、输入内容与规则内容；本机只记录「发生了一次动作、结果是成功还是失败」这类不含内容的审计条目。',
  '请勿将本功能用于违反应用服务条款、绕过付费或验证、刷量、抢购等用途。',
]

export interface AutomationDisclaimerProps {
  mode: 'confirm' | 'read'
  /** 只有 `confirm` 用得到：勾选后按「我已了解，开启自动化」时回调。 */
  onConfirm?: () => void
  onCancel: () => void
}

export function AutomationDisclaimer({ mode, onConfirm, onCancel }: AutomationDisclaimerProps) {
  // 勾选状态只活在这一次展示里：关掉再开要重新勾，不把「上次勾过」留着。
  const [checked, setChecked] = useState(false)
  const confirming = mode === 'confirm'
  return (
    <div className="dialog-backdrop" role="presentation"
      onPointerDown={event => { if (event.target === event.currentTarget) onCancel() }}>
      <div className="dialog automation-disclaimer" role="dialog" aria-modal="true"
        aria-labelledby="automation-disclaimer-title" data-testid="automation-disclaimer">
        <button className="dialog-close" type="button" aria-label={t('关闭')} onClick={onCancel}><X size={19} /></button>
        <span className="dialog-info-icon"><ShieldCheck size={23} /></span>
        <h2 id="automation-disclaimer-title">{t('关于自动化（无障碍）功能')}</h2>
        <p>
          {t('本功能会按你（或你授权的 AI 助手）写入的规则，在你指定的应用处于前台时自动点击、滑动、按键或等待。规则由你确认后长期生效，因此：')}
        </p>
        <ol>
          {DISCLAIMER_POINTS.map(point => <li key={point}>{t(point)}</li>)}
        </ol>
        {confirming ? (
          <>
            <label className="disclaimer-confirm">
              <input type="checkbox" checked={checked} onChange={event => setChecked(event.target.checked)} />
              <span>{t('我已阅读并理解上述说明，并自行承担规则配置与使用带来的后果')}</span>
            </label>
            <div className="dialog-actions">
              <button className="button button-secondary" type="button" onClick={onCancel}>{t('暂不开启')}</button>
              {/* 没勾选就不给按：这一步要的是「看过了」这个明确动作，不是顺手点两下。 */}
              <button className="button button-primary" type="button" disabled={!checked} onClick={onConfirm}>
                <ShieldCheck size={18} />{t('我已了解，开启自动化')}
              </button>
            </div>
          </>
        ) : (
          <div className="dialog-actions">
            {/*
              回看模式的按钮不叫「关闭」：右上角那个叉的 `aria-label` 就是「关闭」，
              同名会让读屏与用例都指向两个按钮，分不清按到了哪个。
            */}
            <button className="button button-secondary" type="button" onClick={onCancel}>{t('知道了')}</button>
          </div>
        )}
      </div>
    </div>
  )
}
