import { useEffect, useState } from 'react'
import { Moon, Palette, Sun, SunMoon, RotateCcw } from 'lucide-react'
import { t } from '../i18n'
import { applyTextColor, applyTheme, clearCustomAccentColor, clearCustomTextColor, effectiveAccentColor, readCustomAccentColor, readCustomTextColor, readThemeMode, saveAccentMode, saveAccentSource, saveCustomAccentColor, saveCustomTextColor, saveThemeMode, useAccentMode, useAccentSource, useResolvedTheme, useThemeMode } from '../theme'
import { clearAdaptiveAccent, deriveSurface, extractAccentFromBackground, readAccentSync, readAdaptiveAccent, saveAccentSync, saveAdaptiveAccent, clearAccentSync, type AccentSource, type AdaptiveAccent, type SyncedTheme } from '../adaptiveAccent'
import { runtimeBridge } from '../platform/native'
import { prepareBackgroundForSync } from '../backgroundSync'
import type { AccentMode, ThemeMode } from '../theme'
import { applyCard, clearBackground, DEFAULT_CARD, readBackground, readCard, saveBackground, saveCard, type CardAppearance } from '../appearance'

// 顺序固定为「跟随系统 / 浅色 / 深色」：默认项排在最前，避免让人以为必须二选一。
const OPTIONS: readonly { value: ThemeMode; label: string; Icon: typeof Sun }[] = [
  { value: 'system', label: '跟随系统', Icon: SunMoon },
  { value: 'light', label: '浅色', Icon: Sun },
  { value: 'dark', label: '深色', Icon: Moon },
]

const ACCENT_SOURCES: readonly { value: AccentSource; label: string }[] = [
  { value: 'default', label: '预置色板' },
  { value: 'custom', label: '自定义颜色' },
  { value: 'adaptive', label: '自适应背景色' },
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
  const [card, setCard] = useState<CardAppearance>(() => readCard())
  const [backgroundError, setBackgroundError] = useState('')
  const [customAccent, setCustomAccent] = useState(() => readCustomAccentColor())
  const [customTextColor, setCustomTextColor] = useState(() => readCustomTextColor())
  const source = useAccentSource()
  const [adaptive, setAdaptive] = useState<AdaptiveAccent | null>(() => readAdaptiveAccent())
  const [extracting, setExtracting] = useState(false)
  const [accentError, setAccentError] = useState('')
  const [syncRecord, setSyncRecord] = useState(() => readAccentSync())
  const [syncing, setSyncing] = useState(false)
  const [syncMessage, setSyncMessage] = useState('')
  // 背景默认不跟着颜色走：它体积大、还要解码，得用户自己勾。
  const [withBackground, setWithBackground] = useState(false)

  const changeSource = (value: AccentSource) => {
    if (saveAccentSource(value)) { setFailed(null); setAccentError('') } else setFailed('accent')
  }

  /** 只读背景首帧，提取失败时保留当前强调色，不把界面切到一个空颜色上。 */
  const extract = async () => {
    setExtracting(true)
    setAccentError('')
    try {
      const blob = await readBackground()
      if (!blob) { setAccentError('还没有设置背景，请先选择背景图片或视频'); return }
      const result = await extractAccentFromBackground(blob, { theme: resolved, surfaceOpacity: card.opacity })
      if (!result) { setAccentError('没从背景里提取到合适的颜色，已保留当前强调色'); return }
      if (!saveAdaptiveAccent(result) || !saveAccentSource('adaptive')) { setAccentError('无法保存提取结果，请重试'); return }
      setAdaptive(result)
    } catch { setAccentError('读取背景失败，请重试') } finally { setExtracting(false) }
  }

  /** 同步只传固定字段：明暗模式 + 三个颜色 + 面板透明度。 */
  const sync = async () => {
    setSyncing(true)
    setSyncMessage('')
    try {
      const preset = ACCENT_OPTIONS.find(option => option.value === accent)?.color ?? ACCENT_OPTIONS[0].color
      const accentColor = effectiveAccentColor() || preset
      const surface = adaptive ? { background: adaptive.background, surface: adaptive.surface } : deriveSurface(accentColor, resolved)
      const theme: SyncedTheme = { mode: readThemeMode(), accent: accentColor, background: surface.background, surface: surface.surface, surfaceOpacity: card.opacity }
      await runtimeBridge.applyHarnessTheme(theme)
      let note = '已让 DSH 使用当前主题'
      if (withBackground) {
        const blob = await readBackground()
        const prepared = blob ? await prepareBackgroundForSync(blob) : null
        if (prepared) {
          await runtimeBridge.applyHarnessBackground(prepared)
          note = '已让 DSH 使用当前主题与背景'
        } else {
          // 压不到可同步的体积就只同步颜色，并把原因说清楚，不静默丢内容。
          note = '背景没能缩小到可同步的大小，已只同步颜色'
        }
      }
      setSyncRecord(saveAccentSync(theme))
      setSyncMessage(note)
    } catch (error) {
      setSyncMessage(error instanceof Error ? error.message : '同步失败，请重试')
    } finally { setSyncing(false) }
  }

  const unsync = async () => {
    setSyncing(true)
    setSyncMessage('')
    try {
      await runtimeBridge.clearHarnessTheme()
      await runtimeBridge.clearHarnessBackground()
      clearAccentSync()
      setSyncRecord(null)
      setSyncMessage('DSH 已回到默认主题')
    } catch (error) {
      setSyncMessage(error instanceof Error ? error.message : '恢复失败，请重试')
    } finally { setSyncing(false) }
  }
  const updateCard = (next: CardAppearance) => {
    try { saveCard(next); setCard(next); setBackgroundError('') }
    catch { setBackgroundError(t('无法保存外观，请检查本地存储空间')) }
  }

  // index.html 的首帧脚本已经落过一次主题。这里再落一次，是为了覆盖「宿主页面
  // 没有那段脚本」或「脚本被 CSP 拦下」的情形：否则用户存过深色、界面却是浅色，
  // 而设置页显示的选中项与实际外观对不上。
  useEffect(() => { applyTheme(readThemeMode()) }, [])
  useEffect(() => { applyCard(card); applyTextColor() }, [card])

  const change = (value: ThemeMode) => {
    // 存储不可用时如实报错：静默失败会让用户以为选择已生效，下次打开又变回去。
    setFailed(saveThemeMode(value) ? null : 'theme')
  }

  const changeAccent = (value: AccentMode) => {
    const saved = saveAccentMode(value)
    if (saved) setCustomAccent('')
    setFailed(saved ? null : 'accent')
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
            <label key={value} className={!customAccent && accent === value ? 'appearance-accent-option active' : 'appearance-accent-option'}>
              <input
                type="radio"
                name="app-accent"
                value={value}
                checked={!customAccent && accent === value}
                onChange={() => changeAccent(value)}
              />
              <span className="accent-swatch" style={{ backgroundColor: color }} aria-hidden="true" />
              <span>{t(label)}</span>
            </label>
          ))}
        </div>
        <label className="appearance-custom-accent"><span>{t('自定义强调色')}</span><input type="color" value={customAccent || '#315fcf'} onChange={event => {
          const value = event.target.value
          if (saveCustomAccentColor(value)) setCustomAccent(value)
        }} /><button type="button" className="compact-button" onClick={() => { clearCustomAccentColor(); setCustomAccent('') }}>{t('使用默认')}</button></label>
      </div>
      {failed !== null && <p role="alert">{t(failed === 'accent' ? '无法保存强调色，请重试。' : '无法保存主题，请重试。')}</p>}
      <div className="appearance-customization">
        <div className="appearance-accent-heading"><strong>{t('应用背景与卡片')}</strong><span>{t('背景文件只保存在本机，不会上传。')}</span></div>
        <label className="field"><span>{t('背景图片或视频')}</span><input type="file" accept="image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm" onChange={event => {
          const file = event.target.files?.[0]
          if (!file) return
          void saveBackground(file).then(() => setBackgroundError('')).catch(() => setBackgroundError(t('无法保存背景，请检查文件格式和存储空间')))
          // React 异步回调不保留输入节点；下一次选择由系统选择器处理。
        }} /><small>{t('支持 PNG、JPG、WebP、GIF、MP4、WebM，最大 50 MB')}</small></label>
        <div className="appearance-range-grid">
          <label className="range-field"><span><strong>{t('卡片不透明度')}</strong><small>{card.opacity}%</small></span><input type="range" min="30" max="100" step="1" value={card.opacity} onChange={event => { const next = { ...card, opacity: Number(event.target.value) }; updateCard(next) }} /></label>
          <label className="range-field"><span><strong>{t('卡片背景模糊')}</strong><small>{card.blur}px</small></span><input type="range" min="0" max="24" step="1" value={card.blur} onChange={event => { const next = { ...card, blur: Number(event.target.value) }; updateCard(next) }} /></label>
        </div>
        <label className="field"><span>{t('卡片背景色')}</span><input type="color" value={card.color || (resolved === 'dark' ? '#171d24' : '#ffffff')} onChange={event => { const next = { ...card, color: event.target.value }; updateCard(next) }} /><button type="button" className="compact-button" onClick={() => { updateCard({ ...DEFAULT_CARD }); void clearBackground().catch(() => setBackgroundError(t('无法清除背景，请重试'))) }}><RotateCcw size={16} />{t('恢复默认')}</button></label>
        <label className="field"><span>{t('文字颜色')}</span><input aria-label={t('文字颜色')} type="color" value={customTextColor || (resolved === 'dark' ? '#e8eef5' : '#17202b')} onChange={event => { const value = event.target.value; if (saveCustomTextColor(value)) setCustomTextColor(value) }} /><button type="button" className="compact-button" onClick={() => { clearCustomTextColor(); setCustomTextColor('') }}>{t('使用默认')}</button></label>
        {backgroundError && <p role="alert">{t(backgroundError)}</p>}
      </div>
      <div className="appearance-customization appearance-adaptive">
        <div className="appearance-accent-heading"><strong>{t('自适应主题色')}</strong><span>{t('从背景图片或视频首帧提取，只影响外壳外观。')}</span></div>
        <div className="appearance-accent-options" role="radiogroup" aria-label={t('强调色来源')}>
          {ACCENT_SOURCES.map(({ value, label }) => (
            <label key={value} className={source === value ? 'appearance-accent-option active' : 'appearance-accent-option'}>
              <input type="radio" name="accent-source" checked={source === value} onChange={() => changeSource(value)} />
              <span>{t(label)}</span>
            </label>
          ))}
        </div>
        <p>{adaptive ? `${t('当前提取色')} ${adaptive.accent} · ${new Date(adaptive.extractedAt).toLocaleString()}` : t('还没有从背景提取过主题色')}</p>
        <button type="button" className="compact-button" onClick={() => void extract()} disabled={extracting}>
          {extracting ? t('正在提取…') : adaptive ? t('重新提取') : t('从背景提取主题色')}
        </button>
        {adaptive && <button type="button" className="compact-button" onClick={() => { clearAdaptiveAccent(); setAdaptive(null); if (source === 'adaptive') changeSource('default') }}>{t('清除提取结果')}</button>}
        {accentError && <p role="alert">{t(accentError)}</p>}
      </div>
      <div className="appearance-customization appearance-sync">
        <div className="appearance-accent-heading"><strong>{t('同步到 DSH 页面')}</strong><span>{t('外壳与 DSH 是两个独立页面，主题不会自动共享；只同步颜色与明暗模式。')}</span></div>
        <label className="appearance-sync-background"><input type="checkbox" checked={withBackground} onChange={event => setWithBackground(event.target.checked)} /><span>{t('连背景一起同步')}</span><small>{t('按最长边 1280 缩小后发送，视频只取首帧')}</small></label>
        <button type="button" className="compact-button" onClick={() => void sync()} disabled={syncing}>{t('让 DSH 使用当前主题')}</button>
        <button type="button" className="compact-button" onClick={() => void unsync()} disabled={syncing}>{t('恢复 DSH 默认主题')}</button>
        <p>{syncRecord ? `${t('上次同步')} ${new Date(syncRecord.syncedAt).toLocaleString()}` : t('还没有同步过')}</p>
        {syncMessage && <p role="status">{t(syncMessage)}</p>}
      </div>
    </section>
  )
}
