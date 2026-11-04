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
    if (saving) return
    setSaving(true)
    setFailed(false)
    setSelected(value)
    try {
      await runtimeBridge.setAppLanguage(value)
    } catch {
      /*
       * 原生没记住不算死局：localStorage 记住也足以跳出门禁（下次启动 readLanguage 就有值）。
       * 两者的成败合起来只影响一件事——下次启动会不会再问一次，所以统一在下面那条提示里说。
       */
    }
    // 无论原生结果如何都先让界面切过去：点了没反应比「下次再问一次」更糟。
    if (!saveLanguage(value)) setFailed(true)
    setSaving(false)
  }

  return (
    <section className={initial ? 'language-card' : 'settings-section language-settings'} aria-labelledby="language-title">
      <Languages size={28} aria-hidden="true" />
      <div className="language-copy">
        <h2 id="language-title">{initial ? '选择语言 / Choose your language' : t('界面语言')}</h2>
        <p>{initial ? '请选择应用语言，之后可在设置中更改。' : t('立即生效，下次打开仍使用此语言。')}</p>
        {initial && <p lang="en">Choose an app language. You can change it later in Settings.</p>}
      </div>
      <div className="language-control">
        <label className={initial ? undefined : 'sr-only'} htmlFor="app-language">{initial ? '语言 / Language' : t('语言')}</label>
        <select disabled={saving} id="app-language" value={initial ? selected : language ?? 'zh-CN'} onChange={event => {
          const value = event.target.value
          // 安全校验：拒绝选项白名单以外的值。
          if (value !== 'zh-CN' && value !== 'en') return
          // 首次选择**选中即提交**：少一个「看不见的继续键」就少一处卡死的可能。
          void change(value)
        }}>
          <option value="zh-CN" lang="zh-CN">简体中文</option>
          <option value="en" lang="en">English</option>
        </select>
      </div>
      {failed && <p role="alert">{t('语言已切换，但本机存储不可用，下次启动可能再次询问。')}</p>}
      {initial && (
        <>
          <button className="button button-primary" type="button" disabled={saving} onClick={() => void change(selected)}>继续 / Continue</button>
          {/* 软出口：无论门禁与原生出什么问题，用户都能用默认语言进入应用。 */}
          <button className="button button-secondary" type="button" disabled={saving} onClick={() => void change('zh-CN')}>
            {t('跳过，使用简体中文')}
          </button>
        </>
      )}
    </section>
  )
}
