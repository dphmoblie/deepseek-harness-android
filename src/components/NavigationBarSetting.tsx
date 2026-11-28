import { useEffect, useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'

export function NavigationBarSetting() {
  const [hidden, setHidden] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<'read' | 'save' | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    void runtimeBridge.getNavigationBarHidden().then(
      value => { if (active) setHidden(value) },
      () => { if (active) setError('read') },
    )
    return () => { active = false }
  }, [attempt])

  async function save(next: boolean) {
    setSaving(true)
    setError(null)
    try {
      setHidden(await runtimeBridge.setNavigationBarHidden(next))
    } catch {
      setError('save')
    } finally {
      setSaving(false)
    }
  }

  return <>
    <label className="toggle-row">
      <span>
        <strong>{t('隐藏底部导航栏')}</strong>
        <small id="navigation-bar-hint">{t('从导航栏所在的屏幕边缘向内滑动可暂时显示导航栏，顶部状态栏保持可见')}</small>
      </span>
      <input
        type="checkbox"
        role="switch"
        aria-label={t('隐藏底部导航栏')}
        aria-describedby="navigation-bar-hint"
        aria-busy={saving || (hidden === null && error === null)}
        checked={hidden === true}
        disabled={hidden === null || saving}
        onChange={event => void save(event.target.checked)}
      />
    </label>
    {error === 'read' && <>
      <p role="alert">{t('无法读取导航栏设置，请重试。')}</p>
      <button type="button" className="button button-secondary" onClick={() => {
        setError(null)
        setAttempt(previous => previous + 1)
      }}>{t('重试')}</button>
    </>}
    {error === 'save' && <p role="alert">{t('无法保存导航栏设置，请重试。')}</p>}
  </>
}
