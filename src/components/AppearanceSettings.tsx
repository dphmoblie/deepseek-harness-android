import { useEffect, useState } from 'react'
import { Moon, Palette, Sun, SunMoon } from 'lucide-react'
import { t } from '../i18n'
import { applyTheme, readThemeMode, saveAccentMode, saveThemeMode, useAccentMode, useResolvedTheme, useThemeMode } from '../theme'
import type { AccentMode, ThemeMode } from '../theme'

// 顺序固定为「跟随系统 / 浅色 / 深色」：默认项排在最前，避免让人以为必须二选一。
const OPTIONS: readonly { value: ThemeMode; label: string; Icon: typeof Sun }[] = [
  { value: 'system', label: '跟随系统', Icon: SunMoon },
  { value: 'light', label: '浅色', Icon: Sun },
  { value: 'dark', label: '深色', Icon: Moon },
]

const ACCENT_OPTIONS: readonly { value: AccentMode; label: string; color: string }[] = [
  { value: 'ocean', label: '海洋蓝', color: '#315fcf' },
  { value: 'mint', label: '薄荷绿', color: '#087f73' },
  { value: 'amber', label: '琥珀橙', color: '#b45c0a' },
  { value: 'rose', label: '玫瑰红', color: '#b33a61' },
]

/**
 * 外观设置：无 props 的自包含区块，接进设置页的方式与 LanguageSettings 一致。
 * 组件只负责展示与转发，主题的解析与落地全在 src/theme.ts 里（可单独测试）。
 */
export function AppearanceSettings() {
  const mode = useThemeMode()
  const accent = useAccentMode()
  const resolved = useResolvedTheme()
  const [failed, setFailed] = useState<'theme' | 'accent' | null>(null)

  // index.html 的首帧脚本已经落过一次主题。这里再落一次，是为了覆盖「宿主页面
  // 没有那段脚本」或「脚本被 CSP 拦下」的情形：否则用户存过深色、界面却是浅色，
  // 而设置页显示的选中项与实际外观对不上。
  useEffect(() => { applyTheme(readThemeMode()) }, [])

  const change = (value: ThemeMode) => {
    // 存储不可用时如实报错：静默失败会让用户以为选择已生效，下次打开又变回去。
    setFailed(saveThemeMode(value) ? null : 'theme')
  }

  const changeAccent = (value: AccentMode) => {
    setFailed(saveAccentMode(value) ? null : 'accent')
  }

  return (
    <section className="settings-section appearance-settings" aria-labelledby="appearance-title">
      <Palette size={28} aria-hidden="true" />
      <h2 id="appearance-title">{t('界面主题')}</h2>
      <p>{t('立即生效，下次打开仍使用此主题。')}</p>
      <div className="appearance-options" role="radiogroup" aria-labelledby="appearance-title">
        {OPTIONS.map(({ value, label, Icon }) => (
          <label key={value} className={mode === value ? 'appearance-option active' : 'appearance-option'}>
            <input
              type="radio"
              name="app-theme"
              value={value}
              checked={mode === value}
              onChange={() => change(value)}
            />
            <Icon size={16} aria-hidden="true" />
            <span>{t(label)}</span>
          </label>
        ))}
      </div>
      {mode === 'system' && <p className="appearance-hint">{t('当前显示：{0}', t(resolved === 'dark' ? '深色' : '浅色'))}</p>}
      <div className="appearance-accent-block">
        <div className="appearance-accent-heading">
          <strong>{t('强调色')}</strong>
          <span>{t('用于按钮、进度条和状态提示')}</span>
        </div>
        <div className="appearance-accent-options" role="radiogroup" aria-label={t('强调色')}>
          {ACCENT_OPTIONS.map(({ value, label, color }) => (
            <label key={value} className={accent === value ? 'appearance-accent-option active' : 'appearance-accent-option'}>
              <input
                type="radio"
                name="app-accent"
                value={value}
                checked={accent === value}
                onChange={() => changeAccent(value)}
              />
              <span className="accent-swatch" style={{ backgroundColor: color }} aria-hidden="true" />
              <span>{t(label)}</span>
            </label>
          ))}
        </div>
      </div>
      {failed !== null && <p role="alert">{t(failed === 'accent' ? '无法保存强调色，请重试。' : '无法保存主题，请重试。')}</p>}
    </section>
  )
}
