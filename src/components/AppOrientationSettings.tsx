import { useEffect, useRef, useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { RotateCw } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { AppOrientationMode } from '../platform/types'

const OPTIONS: readonly { mode: AppOrientationMode; label: string }[] = [
  { mode: 'system', label: '跟随系统' },
  { mode: 'portrait', label: '竖屏' },
  { mode: 'landscape', label: '横屏' },
]

export function AppOrientationSettings() {
  const native = Capacitor.isNativePlatform()
  const [mode, setMode] = useState<AppOrientationMode | null>(null)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const mounted = useRef(false)
  const pending = useRef(false)

  useEffect(() => {
    if (!native) return
    let active = true
    mounted.current = true
    setError('')
    void runtimeBridge.getAppOrientation().then(
      value => { if (active) setMode(value) },
      () => { if (active) setError('无法读取应用方向，请重试。') },
    )
    return () => { active = false; mounted.current = false }
  }, [native, attempt])

  const change = async (next: AppOrientationMode) => {
    if (pending.current || mode === null || mode === next) return
    pending.current = true
    setSaving(true)
    setError('')
    try {
      const saved = await runtimeBridge.setAppOrientation(next)
      if (mounted.current) setMode(saved)
    } catch {
      if (mounted.current) setError('无法保存应用方向，请重试。')
    } finally {
      pending.current = false
      if (mounted.current) setSaving(false)
    }
  }

  if (!native) return null
  return <section className="settings-section" aria-labelledby="app-orientation-title">
    <div className="section-title">
      <span className="section-icon"><RotateCw size={19} /></span>
      <div><h2 id="app-orientation-title">{t('应用方向')}</h2><p>{t('管理页、终端和 Harness 对话使用同一方向，选择后立即生效并保存。')}</p></div>
    </div>
    <div className="appearance-options" role="radiogroup" aria-label={t('应用方向')} aria-busy={saving || (mode === null && !error)}>
      {OPTIONS.map(option => <label key={option.mode} className={mode === option.mode ? 'appearance-option active' : 'appearance-option'}>
        <input type="radio" name="app-orientation" value={option.mode} checked={mode === option.mode} disabled={mode === null || saving} onChange={() => void change(option.mode)} />
        <span>{t(option.label)}</span>
      </label>)}
    </div>
    <p className="settings-note">{t('“跟随系统”遵循手机的自动旋转设置；选择横屏或竖屏可固定本应用的方向。')}</p>
    {mode === null && !error && <p role="status">{t('正在读取应用方向…')}</p>}
    {saving && <p role="status">{t('正在切换方向…')}</p>}
    {error && <p role="alert">{t(error)}</p>}
    {mode === null && error && <button type="button" className="button button-secondary" onClick={() => setAttempt(previous => previous + 1)}>{t('重试')}</button>}
  </section>
}
