import { useEffect, useState } from 'react'
import { Moon, Palette, Sun, SunMoon, RotateCcw } from 'lucide-react'
import { t } from '../i18n'
import { applyTextColor, applyTheme, clearCustomAccentColor, clearCustomTextColor, effectiveAccentColor, readCustomAccentColor, readCustomTextColor, readThemeMode, saveAccentMode, saveAccentSource, saveCustomAccentColor, saveCustomTextColor, saveThemeMode, useAccentMode, useAccentSource, useResolvedTheme, useThemeMode } from '../theme'
import { clearAdaptiveAccent, deriveSurface, extractAccentFromBackground, readAccentSync, readAdaptiveAccent, saveAccentSync, saveAdaptiveAccent, clearAccentSync, type AccentSource, type AdaptiveAccent, type SyncedTheme } from '../adaptiveAccent'
import { runtimeBridge } from '../platform/native'
import { clearBackgroundState, readBackgroundState, saveBackgroundState, type HarnessBackgroundRef, type HarnessBackgroundState } from '../backgroundRef'
import type { AccentMode, ThemeMode } from '../theme'
import { applyCard, clearBackground, DEFAULT_CARD, hasBackgroundFlag, readBackground, readCard, saveBackground, saveCard, type CardAppearance } from '../appearance'

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

/** 一个页面里只显示其中一段：字号与配色、背景、或同步到 DSH。默认全显示（组件单独测试时用）。 */
export type AppearanceScope = 'all' | 'appearance' | 'background' | 'dsh'

/**
 * 外观设置：自包含区块，接进设置页的方式与 LanguageSettings 一致。
 *
 * `scope` 把主题拆成三个设置页：字号与配色、背景、DSH 同步。前两段是应用外壳自己的外观，
 * 最后一段要单独一页是因为它改的是**另一个页面**（DSH），与「本机长什么样」是两件事。
 * 默认 'all' 保持整块渲染，组件自身的规则测试可以直接挂载。
 * 组件只负责展示与转发，主题的解析与落地全在 src/theme.ts 里（可单独测试）。
 */
export function AppearanceSettings({ scope = 'all', loadBackground = readBackground }: { scope?: AppearanceScope; loadBackground?: () => Promise<Blob | null> }) {
  const showAppearance = scope === 'all' || scope === 'appearance'
  const showBackground = scope === 'all' || scope === 'background'
  // DSH 的主题与背景在同一页：两者都改的是另一个页面，分开只会让人来回找。
  const showDsh = scope === 'all' || scope === 'dsh'
  const mode = useThemeMode()
  const accent = useAccentMode()
  const resolved = useResolvedTheme()
  const [failed, setFailed] = useState<'theme' | 'accent' | null>(null)
  const [card, setCard] = useState<CardAppearance>(() => readCard())
  const [backgroundError, setBackgroundError] = useState('')
  /**
   * 本地是否已经存过一份背景。
   *
   * 原生 `<input type="file">` 永远显示「未选择文件」，即使背景已经加密存进 IndexedDB ——
   * 用户重进这一页会以为选择丢了。用这个状态替它显示真实结论，输入框本身只当触发按钮。
   */
  const [hasAppBackground, setHasAppBackground] = useState(() => hasBackgroundFlag())
  useEffect(() => {
    let active = true
    void loadBackground()
      .then(blob => { if (active) setHasAppBackground(blob !== null) })
      // 解密/存储读取失败时保留已有标记：背景可能真的还在（画面由 AppBackground 负责），
      // 不能因为一次读取失败就把「已选择」改口成「未选择」。
      .catch(() => undefined)
    return () => { active = false }
  }, [loadBackground])
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
  // 初值取本地镜像，重启后首屏就是上次的选择；原生回来后立刻对齐（见下面的 effect）。
  const [withBackground, setWithBackground] = useState(() => {
    const saved = readBackgroundState()
    // 开关为真但没有文件不算「开着」：勾选框不能停在一个画不出背景的状态上。
    return saved.syncBackground && saved.background !== null
  })
  // 只记地址（content:// + 持久读授权）：视频多大都不复制、不过桥。
  const [backgroundRef, setBackgroundRef] = useState<HarnessBackgroundRef | null>(() => readBackgroundState().background)

  // 原生是唯一真相：开关与已选背景都从它回来；浏览器预览没有原生可问，退回本地镜像。
  useEffect(() => {
    let active = true
    const local = readBackgroundState()
    const restore = (state: HarnessBackgroundState) => {
      if (!active) return
      setWithBackground(state.syncBackground && state.background !== null)
      setBackgroundRef(state.background)
    }
    Promise.resolve(runtimeBridge.harnessThemeState())
      .then((state: HarnessBackgroundState) => {
        const normalized = { ...state, syncBackground: state.syncBackground && state.background !== null }
        // 回读结果顺手镜像一份：下次首屏不必等过桥。
        saveBackgroundState(normalized)
        restore(normalized)
      })
      .catch(() => restore(local))
    return () => { active = false }
  }, [])

  /** 记住当前背景选择；同步时间沿用上次的，没有就是 0。 */
  const persistBackground = (enabled: boolean, background: HarnessBackgroundRef | null) =>
    saveBackgroundState({ syncBackground: enabled && background !== null, syncedAt: syncRecord?.syncedAt ?? 0, background })

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

  /** 选背景文件：只把 SAF 地址交给原生，视频不复制、不过桥。 */
  const pickBackground = async () => {
    setAccentError('')
    try {
      const picked = await runtimeBridge.pickHarnessBackground()
      setBackgroundRef(picked)
      persistBackground(withBackground, picked)
      setSyncMessage(t('已选择背景：{0}', picked.mime))
      // 开关已经打开时换文件，页面应该立刻换成新背景，不要求用户再点一次同步。
      if (withBackground && syncRecord) await sync(true)
    } catch (error) {
      setAccentError(error instanceof Error ? error.message : t('选择背景失败'))
    }
  }

  /**
   * 「连背景一起同步」：先落盘，再（同步过的话）重放。
   *
   * 只改 useState 是不够的——刷新页面或从后台回来，勾选框会自己弹回未勾，
   * 而原生那边还认为背景在显示。两条路必须一起改。
   */
  const toggleBackground = async (enabled: boolean) => {
    const effective = enabled && backgroundRef !== null
    setWithBackground(enabled)
    setAccentError('')
    try {
      await runtimeBridge.setHarnessSyncBackground(effective)
      persistBackground(effective, backgroundRef)
    } catch (error) {
      setSyncMessage(error instanceof Error ? error.message : t('无法保存背景同步开关'))
      return
    }
    // 没有任何文件时如实说明只同步了颜色，不把「开着但没内容」当成成功。
    if (enabled && !effective) {
      setSyncMessage(t('还没有选择背景文件，已只同步颜色'))
      return
    }
    // 已经同步过：页面立刻按新档位重放，不用等下一次加载。
    if (syncRecord) await sync(effective)
  }

  /**
   * 同步只传固定字段：明暗模式 + 三个颜色 + 面板透明度，外加「要不要带背景」。
   *
   * 背景开关作为参数传入而不是读 state：勾选后立刻调用时，state 还没提交到这一帧，
   * 读到的会是旧值，页面就会少一次重放。
   */
  const sync = async (includeBackground = withBackground) => {
    setSyncing(true)
    setSyncMessage('')
    try {
      const preset = ACCENT_OPTIONS.find(option => option.value === accent)?.color ?? ACCENT_OPTIONS[0].color
      const accentColor = effectiveAccentColor() || preset
      const surface = adaptive ? { background: adaptive.background, surface: adaptive.surface } : deriveSurface(accentColor, resolved)
      const theme: SyncedTheme = { mode: readThemeMode(), accent: accentColor, background: surface.background, surface: surface.surface, surfaceOpacity: card.opacity, controlOpacity: card.controlOpacity, syncBackground: includeBackground && backgroundRef !== null }
      await runtimeBridge.applyHarnessTheme(theme)
      let note = '已让 DSH 使用当前主题'
      if (theme.syncBackground) {
        if (backgroundRef) {
          // 原生只记地址；页面播放时由 /__shell/background 流式取，不经过这里。
          note = backgroundRef.kind === 'video' ? t('已让 DSH 使用当前主题与背景视频') : t('已让 DSH 使用当前主题与背景')
        } else {
          // 没选过背景就只同步颜色，并把原因说清楚，不静默丢内容。
          note = t('还没有选择背景文件，已只同步颜色')
        }
      }
      setSyncRecord(saveAccentSync(theme))
      // 本地镜像跟着主题一起更新：下次首屏直接就是这次同步后的状态。
      persistBackground(theme.syncBackground, backgroundRef)
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
      // 主题都清了，开关与已选文件也不该留在这块界面上：否则下次打开会显示上一条遗留。
      setWithBackground(false)
      setBackgroundRef(null)
      clearBackgroundState()
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
    <section
      className="settings-section appearance-settings"
      aria-labelledby={showAppearance ? 'appearance-title' : undefined}
      aria-label={showAppearance ? undefined : t(scope === 'dsh' ? 'DSH 主题与同步' : '背景与卡片')}
    >
      {showAppearance && (
        <>
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
          {/*
            * 按钮与输入框是同一组：它们都是可直接操作的控件，透明度必须一起调，
            * 否则会出现「按钮半透明、输入框还是实底」的割裂感。选中态的强调色是另一组设置。
            */}
          <div className="appearance-customization">
            <div className="appearance-accent-heading">
              <strong>{t('按钮与输入框')}</strong>
              <span>{t('统一控制按钮与输入框的底色透明度')}</span>
            </div>
            <label className="range-field">
              <span><strong>{t('按钮与输入框不透明度')}</strong><small>{card.controlOpacity}%</small></span>
              <input
                type="range"
                min="40"
                max="100"
                step="1"
                value={card.controlOpacity}
                onChange={event => { updateCard({ ...card, controlOpacity: Number(event.target.value) }) }}
              />
            </label>
          </div>
          <div className="appearance-accent-block">
            <div className="appearance-accent-heading">
              <strong>{t('选中的强调色')}</strong>
              <span>{t('用于选中项、进度条和状态提示')}</span>
            </div>
            <div className="appearance-accent-options" role="radiogroup" aria-label={t('选中的强调色')}>
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
        </>
      )}
      {showBackground && (
        <>
          <div className="appearance-customization">
            <div className="appearance-accent-heading"><strong>{t('应用背景与卡片')}</strong><span>{t('背景文件只保存在本机，不会上传。')}</span></div>
            <label className="field"><span>{t('背景图片或视频')}</span>
              <span className="background-picker">
                <span className="button button-secondary compact-button">{t('选择背景文件')}</span>
                <input
                  className="background-picker-input"
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm"
                  onChange={event => {
                    const file = event.target.files?.[0]
                    if (!file) return
                    void saveBackground(file)
                      .then(() => { setBackgroundError(''); setHasAppBackground(true) })
                      .catch(() => setBackgroundError(t('无法保存背景，请检查文件格式和存储空间')))
                    // React 异步回调不保留输入节点；下一次选择由系统选择器处理。
                  }}
                />
              </span>
              <small>{hasAppBackground
                ? t('已选择背景，重新选择会替换当前背景')
                : t('支持 PNG、JPG、WebP、GIF、MP4、WebM，最大 50 MB')}</small>
            </label>
            <div className="appearance-range-grid">
              <label className="range-field"><span><strong>{t('卡片不透明度')}</strong><small>{card.opacity}%</small></span><input type="range" min="30" max="100" step="1" value={card.opacity} onChange={event => { const next = { ...card, opacity: Number(event.target.value) }; updateCard(next) }} /></label>
              <label className="range-field"><span><strong>{t('卡片背景模糊')}</strong><small>{card.blur}px</small></span><input type="range" min="0" max="24" step="1" value={card.blur} onChange={event => { const next = { ...card, blur: Number(event.target.value) }; updateCard(next) }} /></label>
            </div>
            <label className="field"><span>{t('卡片背景色')}</span><input type="color" value={card.color || (resolved === 'dark' ? '#171d24' : '#ffffff')} onChange={event => { const next = { ...card, color: event.target.value }; updateCard(next) }} /><button type="button" className="compact-button" onClick={() => { updateCard({ ...DEFAULT_CARD }); void clearBackground().then(() => setHasAppBackground(false)).catch(() => setBackgroundError(t('无法清除背景，请重试'))) }}><RotateCcw size={16} />{t('恢复默认')}</button></label>
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
        </>
      )}
      {showDsh && (
          <div className="appearance-customization appearance-sync">
            <div className="appearance-accent-heading"><strong>{t('同步背景')}</strong><span>{t('背景文件只记地址；勾选后才随主题一起画到 DSH 页面。')}</span></div>
            <div className="appearance-sync-background">
              <label><input type="checkbox" checked={withBackground} onChange={event => void toggleBackground(event.target.checked)} /><span>{t('连背景一起同步')}</span></label>
              <button type="button" className="compact-button" onClick={() => void pickBackground()}>{backgroundRef ? t('重新选择背景文件') : t('选择背景文件')}</button>
              <small>{backgroundRef ? t('已选：{0}', backgroundRef.mime) : t('只记录文件地址，视频不复制、不设大小上限')}</small>
              <small>{t('开关与所选文件会记住，重启后仍按上次的选择显示。')}</small>
            </div>
          </div>
      )}
      {showDsh && (
          <div className="appearance-customization appearance-sync">
            <div className="appearance-accent-heading"><strong>{t('同步到 DSH 页面')}</strong><span>{t('外壳与 DSH 是两个独立页面，主题不会自动共享。')}</span></div>
            {/* 这一项与外壳的「按钮与输入框不透明度」是同一个值：改完点同步即可带去 DSH。 */}
            <label className="range-field">
              <span><strong>{t('按钮与输入框不透明度')}</strong><small>{card.controlOpacity}%</small></span>
              <input
                type="range"
                min="40"
                max="100"
                step="1"
                value={card.controlOpacity}
                onChange={event => updateCard({ ...card, controlOpacity: Number(event.target.value) })}
              />
            </label>
            <button type="button" className="compact-button" onClick={() => void sync()} disabled={syncing}>{t('让 DSH 使用当前主题')}</button>
            <button type="button" className="compact-button" onClick={() => void unsync()} disabled={syncing}>{t('恢复 DSH 默认主题')}</button>
            <p>{syncRecord ? `${t('上次同步')} ${new Date(syncRecord.syncedAt).toLocaleString()}` : t('还没有同步过')}</p>
            {syncMessage && <p role="status">{t(syncMessage)}</p>}
          </div>
      )}
    </section>
  )
}
