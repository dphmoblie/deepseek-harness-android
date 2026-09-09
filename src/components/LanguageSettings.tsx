import { runtimeBridge } from '../platform/native'
import { useState } from 'react'
import { Languages } from 'lucide-react'
import { type Language, saveLanguage, t, useLanguage } from '../i18n'

export function LanguageSettings({ initial = false }: { initial?: boolean }) {
  const language = useLanguage()
  const [selected, setSelected] = useState<Language>(language ?? 'zh-CN')
  const [failed, setFailed] = useState(false)

  const [saving, setSaving] = useState(false)
  const change = async (value: Language) => {
    setSaving(true)
    setFailed(false)
    try {
      await runtimeBridge.setAppLanguage(value)
      setFailed(!saveLanguage(value))
    } catch {
      setFailed(true)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className={initial ? 'language-card' : 'settings-section language-settings'} aria-labelledby="language-title">
      <Languages size={28} aria-hidden="true" />
      <h2 id="language-title">{initial ? '选择语言 / Choose your language' : t('界面语言')}</h2>
      <p>{initial ? '请选择应用语言，之后可在设置中更改。' : t('立即生效，下次打开仍使用此语言。')}</p>
      {initial && <p lang="en">Choose an app language. You can change it later in Settings.</p>}
      <label htmlFor="app-language">{initial ? '语言 / Language' : t('语言')}</label>
      <select disabled={saving} id="app-language" value={initial ? selected : language ?? 'zh-CN'} onChange={event => {
        const value = event.target.value
        // 安全校验：拒绝选项白名单以外的值。
        if (value !== 'zh-CN' && value !== 'en') return
        if (initial) setSelected(value)
        else void change(value)
      }}>
        <option value="zh-CN" lang="zh-CN">简体中文</option>
        <option value="en" lang="en">English</option>
      </select>
      {failed && <p role="alert">无法保存语言，请重试。 / Unable to save language. Please retry.</p>}
      {initial && <button className="button button-primary" type="button" disabled={saving} onClick={() => void change(selected)}>继续 / Continue</button>}
    </section>
  )
}
