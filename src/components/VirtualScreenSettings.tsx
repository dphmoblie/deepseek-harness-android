import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ArrowLeft,
  Loader2,
  Monitor,
  Play,
  RefreshCw,
  RotateCw,
  Save,
  SlidersHorizontal,
  Square,
  TriangleAlert,
} from 'lucide-react'
import { t } from '../i18n'
import { ApplicationPicker } from './ApplicationPicker'
import {
  VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES,
  VIRTUAL_SCREEN_MAX_DPI,
  VIRTUAL_SCREEN_MAX_EDGE,
  VIRTUAL_SCREEN_MIN_DPI,
  VIRTUAL_SCREEN_MIN_EDGE,
  VIRTUAL_SCREEN_ORIENTATION_VALUES,
  VIRTUAL_SCREEN_PREVIEW_MODES,
  clampVirtualScreenSpec,
  normalizeVirtualScreenPreviewMode,
  virtualScreenPreviewModeLabel,
} from '../platform/types'
import type {
  RuntimeBridge,
  VirtualScreenSettings as VirtualScreenSettingsSnapshot,
  VirtualScreenSettingsUpdate,
  VirtualScreenStartRequest,
  VirtualScreenState,
} from '../platform/types'
import './VirtualScreenSettings.css'

const SPEC_FIELDS = ['widthPx', 'heightPx', 'densityDpi'] as const

/** 三项手填规格的字段名。 */
type SpecField = typeof SPEC_FIELDS[number]

/** 只有宽高与 DPI 三项都合法时原生才会当作自定义尺寸，所以界面把它们当成一个整体。 */
type SpecValues = { widthPx: number; heightPx: number; densityDpi: number }

/** 草稿里能单独切换的字段（规格三项走输入框那条路，不从这里改）。 */
type SwitchableField = 'previewMode' | 'autoFollow' | 'orientation' | 'adaptive'

/** 设置读取的三态。失败落 unavailable，不静默成「空设置」。 */
type SettingsLoadState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'unavailable'; reason: string }

/** 状态读取的三态。失败落 unavailable，不静默成「未运行」。 */
type DeviceLoadState =
  | { status: 'loading' }
  | { status: 'ready'; state: VirtualScreenState }
  | { status: 'unavailable'; reason: string }

/** 桥抛出来的原因文本；拿不到就返回空串，由调用处补一句通用文案。 */
function failureReason(error: unknown): string {
  return error instanceof Error ? error.message.trim() : ''
}

function specBounds(field: SpecField): { minimum: number; maximum: number } {
  return field === 'densityDpi'
    ? { minimum: VIRTUAL_SCREEN_MIN_DPI, maximum: VIRTUAL_SCREEN_MAX_DPI }
    : { minimum: VIRTUAL_SCREEN_MIN_EDGE, maximum: VIRTUAL_SCREEN_MAX_EDGE }
}

function specFieldLabel(field: SpecField): string {
  return field === 'densityDpi' ? 'DPI' : field === 'widthPx' ? '宽' : '高'
}

/** 原生侧用 0 表示「这一项没有自定义值」，界面把 0 显示成空格子。 */
function specValues(value: SpecValues): Record<SpecField, string> {
  return {
    widthPx: value.widthPx === 0 ? '' : String(value.widthPx),
    heightPx: value.heightPx === 0 ? '' : String(value.heightPx),
    densityDpi: value.densityDpi === 0 ? '' : String(value.densityDpi),
  }
}

/** 把一格输入夹取成合法值：空串 → 0（没有自定义值）；越界 → 就近的边界值。 */
function clampSpecField(field: SpecField, raw: string): number {
  const trimmed = raw.trim()
  if (trimmed === '') return 0
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed)) return 0
  const { minimum, maximum } = specBounds(field)
  return Math.min(maximum, Math.max(minimum, Math.round(parsed)))
}

/** 三项都落在允许区间内才算一份能生效的自定义规格。 */
function specComplete(value: SpecValues): boolean {
  return SPEC_FIELDS.every(field => {
    const { minimum, maximum } = specBounds(field)
    return value[field] >= minimum && value[field] <= maximum
  })
}

/** 只把规格三项从 source 抄到 target，其余字段原样保留。 */
function withSpecFrom<T extends SpecValues>(target: T, source: SpecValues): T {
  return { ...target, widthPx: source.widthPx, heightPx: source.heightPx, densityDpi: source.densityDpi }
}

/** 换掉规格里的一项；写成显式分支而不是计算属性名，免得类型被推断丢。 */
function withSpec(
  value: VirtualScreenSettingsSnapshot,
  field: SpecField,
  next: number,
): VirtualScreenSettingsSnapshot {
  if (field === 'densityDpi') return { ...value, densityDpi: next }
  if (field === 'widthPx') return { ...value, widthPx: next }
  return { ...value, heightPx: next }
}

/**
 * 用状态区里当前生效的读数预填三格输入。
 *
 * 原生侧「没有自定义尺寸」时三项都是 0，这不是错误，而是「按预设规格运行」：
 * 这时给用户一个可改的起点，比让三个空格子对着他好。
 *
 * 预填**只动界面草稿，不写回原生**，而且草稿与基线用同一份：否则一进页面就被算成
 * 「有未保存的改动」，用户什么都没碰却要按一次保存。
 */
function seeded(
  settings: VirtualScreenSettingsSnapshot,
  live: VirtualScreenState | null,
): VirtualScreenSettingsSnapshot {
  const blank = settings.widthPx === 0 && settings.heightPx === 0 && settings.densityDpi === 0
  if (!blank || live === null) return settings
  if (live.widthPx <= 0 || live.heightPx <= 0 || live.densityDpi <= 0) return settings
  return { ...settings, ...clampVirtualScreenSpec(live.widthPx, live.heightPx, live.densityDpi) }
}

/**
 * 三项都是 0：原生侧「没有自定义尺寸」的表示法。
 *
 * 页面拿它做重置——用户把三格都清空并保存，就等于回到按屏幕与方向算出的预设规格。
 * 与「三项要一起填」是两件事：清空是有意义的完整意图，只填一两项才是半成品。
 */
function specCleared(value: SpecValues): boolean {
  return value.widthPx === 0 && value.heightPx === 0 && value.densityDpi === 0
}

/**
 * 算出这次真正要写给原生的字段。
 *
 * 宽高与 DPI 三项**必须一起发**：原生侧是全有或全无（三项都合法才当作自定义尺寸，
 * 否则整组作废、改用预设规格）。只发其中一两项等于什么都没改，索性一项都不发，
 * 由界面在旁边说明原因；三项都清空则照发 0，那是「清掉自定义尺寸」的完整意图。
 */
function settingsDiff(
  baseline: VirtualScreenSettingsSnapshot,
  draft: VirtualScreenSettingsSnapshot,
): VirtualScreenSettingsUpdate {
  const update: VirtualScreenSettingsUpdate = {}
  if (draft.previewMode !== baseline.previewMode) update.previewMode = draft.previewMode
  if (draft.autoFollow !== baseline.autoFollow) update.autoFollow = draft.autoFollow
  if (draft.orientation !== baseline.orientation) update.orientation = draft.orientation
  if (draft.adaptive !== baseline.adaptive) update.adaptive = draft.adaptive
  const specChanged = draft.widthPx !== baseline.widthPx
    || draft.heightPx !== baseline.heightPx
    || draft.densityDpi !== baseline.densityDpi
  if (specChanged && (specComplete(draft) || specCleared(draft))) {
    update.widthPx = draft.widthPx
    update.heightPx = draft.heightPx
    update.densityDpi = draft.densityDpi
  }
  return update
}

/** 启动入参：方向与自适应按草稿给，规格三项要么一起给、要么都不给（理由同 settingsDiff）。 */
function startRequest(target: string, draft: VirtualScreenSettingsSnapshot): VirtualScreenStartRequest {
  const request: VirtualScreenStartRequest = {
    packageName: target,
    adaptive: draft.adaptive,
    orientation: draft.orientation,
  }
  if (specComplete(draft)) {
    request.widthPx = draft.widthPx
    request.heightPx = draft.heightPx
    request.densityDpi = draft.densityDpi
  }
  return request
}

/**
 * 读数格式化：原生用 0 表示「没采到样」，界面照实说「未知」。
 *
 * 0 在这里绝不显示成「0 FPS」——那看起来像「副屏一帧都没出」，和「没读到」是两回事。
 */
function formatReading(value: number, unit: string): string {
  if (!(value > 0)) return '未知'
  const text = Number.isInteger(value) ? String(value) : value.toFixed(1)
  return unit === '' ? text : `${text} ${unit}`
}

/** 文本读数：空串是「取不到」，显示「未知」而不是留空白。 */
function textOrUnknown(value: string): string {
  return value === '' ? '未知' : value
}

/** 状态区里「实际生效规格」那一行：三项齐全才给数值，否则是未知。 */
function specReading(state: VirtualScreenState): string {
  if (state.widthPx <= 0 || state.heightPx <= 0 || state.densityDpi <= 0) return '未知'
  return `${state.widthPx}×${state.heightPx} @ ${state.densityDpi} DPI`
}

export function VirtualScreenSettings({ bridge, onBack }: { bridge: RuntimeBridge; onBack: () => void }) {
  const [settingsState, setSettingsState] = useState<SettingsLoadState>({ status: 'loading' })
  const [deviceState, setDeviceState] = useState<DeviceLoadState>({ status: 'loading' })
  // draft 是界面上正在编辑的那一份，baseline 是最后一次认为已经落盘的那一份。
  const [draft, setDraft] = useState<VirtualScreenSettingsSnapshot | null>(null)
  const [baseline, setBaseline] = useState<VirtualScreenSettingsSnapshot | null>(null)
  const [target, setTarget] = useState('')
  const [specText, setSpecText] = useState<Record<SpecField, string>>({ widthPx: '', heightPx: '', densityDpi: '' })
  const [specHint, setSpecHint] = useState('')
  const [busy, setBusy] = useState<'' | 'save' | 'start' | 'stop' | 'restart' | 'native'>('')
  const [saveFailure, setSaveFailure] = useState('')
  const [saveNotice, setSaveNotice] = useState('')
  const [actionFailure, setActionFailure] = useState('')
  const [actionNotice, setActionNotice] = useState('')

  // 卸载之后不再写状态：读取是并行的，回来时组件可能已经不在了。
  const active = useRef(true)
  // 两条读取各自带票据，后发的一次为准，避免慢的旧响应覆盖新结果。
  const settingsTicket = useRef(0)
  const stateTicket = useRef(0)
  // 最近一次成功读到的运行状态：设置读得比它晚时用它预填规格。
  const stateRef = useRef<VirtualScreenState | null>(null)
  const draftRef = useRef<VirtualScreenSettingsSnapshot | null>(null)

  /** 草稿、基线与三格输入一起换：分开设的话输入框会显示上一份的值。 */
  const applyLoaded = useCallback((next: VirtualScreenSettingsSnapshot): void => {
    setDraft(next)
    setBaseline(next)
    setSpecText(specValues(next))
  }, [])

  const readSettings = useCallback(async (): Promise<void> => {
    const ticket = ++settingsTicket.current
    setSettingsState({ status: 'loading' })
    try {
      const settings = await bridge.getVirtualScreenSettings()
      if (!active.current || ticket !== settingsTicket.current) return
      applyLoaded(seeded(settings, stateRef.current))
      setSettingsState({ status: 'ready' })
    } catch (error) {
      if (!active.current || ticket !== settingsTicket.current) return
      draftRef.current = null
      setDraft(null)
      setBaseline(null)
      setSettingsState({ status: 'unavailable', reason: failureReason(error) })
    }
  }, [applyLoaded, bridge])

  const readState = useCallback(async (): Promise<void> => {
    const ticket = ++stateTicket.current
    try {
      const state = await bridge.getVirtualScreenState()
      if (!active.current || ticket !== stateTicket.current) return
      stateRef.current = state
      setDeviceState({ status: 'ready', state })
      // 用户没选过目标应用时才用运行中的目标预填；选过之后不再覆盖他的选择。
      if (state.targetPackage !== '') {
        setTarget(current => (current === '' ? state.targetPackage : current))
      }
      // 设置先读完（三项都是 0）而状态后到：这时补一次预填，反过来由 readSettings 负责。
      const current = draftRef.current
      if (current !== null) {
        const next = seeded(current, state)
        if (next !== current) {
          setDraft(next)
          setSpecText(specValues(next))
          // 基线只跟着动规格三项：把它整份换成草稿，会把用户还没保存的其它改动一并算成「已保存」。
          setBaseline(previous => (previous === null ? previous : withSpecFrom(previous, next)))
        }
      }
    } catch (error) {
      if (!active.current || ticket !== stateTicket.current) return
      setDeviceState({ status: 'unavailable', reason: failureReason(error) })
    }
  }, [bridge])

  // 草稿的 ref 跟着状态走：异步读回来的预填判断用它，才不用把 draft 塞进依赖数组里。
  useEffect(() => {
    draftRef.current = draft
  }, [draft])

  useEffect(() => {
    active.current = true
    void readSettings()
    void readState()
    return () => {
      active.current = false
    }
  }, [readSettings, readState])

  /** 改草稿里的枚举/布尔字段。 */
  function patch(update: Partial<Pick<VirtualScreenSettingsSnapshot, SwitchableField>>): void {
    setDraft(current => (current === null ? current : { ...current, ...update }))
  }

  /** 手动刷新：两条读取都重来，顺手把上一条提示清掉。 */
  function reload(): void {
    setSaveFailure('')
    setSaveNotice('')
    setActionFailure('')
    setActionNotice('')
    setSpecHint('')
    void readSettings()
    void readState()
  }

  /**
   * 一边打字一边只在「明显超过上限」时就地夹取。
   *
   * 低于下限的值可能是 3000 打到一半的「3」，这时夹取会让人根本没法把数字打完，
   * 所以低于下限的收敛留到失焦时做（见 commitSpec）。
   */
  function changeSpec(field: SpecField, raw: string): void {
    const { maximum } = specBounds(field)
    const parsed = Number(raw.trim())
    if (raw.trim() !== '' && Number.isFinite(parsed) && parsed > maximum) {
      setSpecText(current => ({ ...current, [field]: String(maximum) }))
      setDraft(current => (current === null ? current : withSpec(current, field, maximum)))
      setSpecHint(t('{0} 超出范围，已夹取到 {1}。', t(specFieldLabel(field)), maximum))
      return
    }
    setSpecText(current => ({ ...current, [field]: raw }))
    setSpecHint('')
  }

  /** 失焦时把那格输入收敛成合法值，并如实说清夹到了哪里。 */
  function commitSpec(field: SpecField): void {
    if (draft === null) return
    const raw = specText[field].trim()
    const parsed = raw === '' ? 0 : Number(raw)
    const clamped = clampSpecField(field, raw)
    if (raw !== '' && clamped !== parsed) {
      setSpecHint(t('{0} 超出范围，已夹取到 {1}。', t(specFieldLabel(field)), clamped))
    }
    setSpecText(current => ({ ...current, [field]: clamped === 0 ? '' : String(clamped) }))
    if (draft[field] !== clamped) setDraft(withSpec(draft, field, clamped))
  }

  async function save(): Promise<void> {
    if (draft === null || baseline === null || busy !== '') return
    const update = settingsDiff(baseline, draft)
    setBusy('save')
    setSaveFailure('')
    setSaveNotice('')
    try {
      if (Object.keys(update).length === 0) {
        if (active.current) setSaveNotice(t('没有需要保存的改动'))
        return
      }
      await bridge.setVirtualScreenSettings(update)
      if (!active.current) return
      // 桥只回 void：把刚发出去的这一份当作新的落盘基线（越界的值界面已经夹过，
      // 与原生 coerceIn 同一口径），避免用户没改过的字段被反复重发。
      setBaseline(draft)
      setSaveNotice(t('副屏设置已保存'))
    } catch (error) {
      if (!active.current) return
      setSaveFailure(failureReason(error) || t('保存副屏设置失败，请稍后重试'))
    } finally {
      if (active.current) setBusy('')
    }
  }

  async function start(): Promise<void> {
    if (draft === null || busy !== '') return
    if (target === '') {
      setActionNotice('')
      setActionFailure(t('请先选择要放到副屏运行的目标应用'))
      return
    }
    setBusy('start')
    setActionFailure('')
    setActionNotice('')
    try {
      await bridge.startVirtualScreen(startRequest(target, draft))
      if (!active.current) return
      setActionNotice(t('已请求启动副屏；画面与帧率以状态区读数为准'))
      await readState()
    } catch (error) {
      if (!active.current) return
      setActionFailure(failureReason(error) || t('启动副屏失败，请确认已连接 Shizuku 后重试'))
    } finally {
      if (active.current) setBusy('')
    }
  }

  /** 停止不依赖草稿：设置读不出来的时候也要能停掉正在跑的副屏。 */
  async function stop(): Promise<void> {
    if (busy !== '') return
    setBusy('stop')
    setActionFailure('')
    setActionNotice('')
    try {
      await bridge.stopVirtualScreen()
      if (!active.current) return
      setActionNotice(t('已请求停止副屏'))
      await readState()
    } catch (error) {
      if (!active.current) return
      setActionFailure(failureReason(error) || t('停止副屏失败，请稍后重试'))
    } finally {
      if (active.current) setBusy('')
    }
  }

  /** 重启 = 先停再起：两条桥方法之间没有原子性，哪一步失败都如实报出来。 */
  async function restart(): Promise<void> {
    if (draft === null || busy !== '') return
    if (target === '') {
      setActionNotice('')
      setActionFailure(t('请先选择要放到副屏运行的目标应用'))
      return
    }
    setBusy('restart')
    setActionFailure('')
    setActionNotice('')
    try {
      await bridge.stopVirtualScreen()
      await bridge.startVirtualScreen(startRequest(target, draft))
      if (!active.current) return
      setActionNotice(t('已请求重启副屏；画面与帧率以状态区读数为准'))
      await readState()
    } catch (error) {
      if (!active.current) return
      setActionFailure(failureReason(error) || t('重启副屏失败，请稍后重试'))
    } finally {
      if (active.current) setBusy('')
    }
  }

  /** 系统侧那个副屏页面（预览与快捷入口用的还是它）；打不开就如实说，不假装已经打开了。 */
  async function openNativePage(): Promise<void> {
    if (busy !== '') return
    setBusy('native')
    setActionFailure('')
    setActionNotice('')
    try {
      await bridge.openVirtualScreen()
    } catch {
      if (!active.current) return
      setActionFailure(t('无法打开副屏页面，请确认当前使用安卓壳且系统为 Android 10 或更高版本'))
    } finally {
      if (active.current) setBusy('')
    }
  }

  const locked = busy !== '' || draft === null
  const specReady = draft !== null && specComplete(draft)

  return (
    <div className="screen virtual-screen-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t('应用管理')}</p>
          <h1>{t('目标应用副屏')}</h1>
        </div>
        <div className="heading-actions">
          <button
            className="button button-secondary compact-button"
            type="button"
            disabled={busy !== ''}
            onClick={reload}
          >
            <RefreshCw size={18} />
            {t('刷新')}
          </button>
          <button
            className="icon-button"
            type="button"
            title={t('返回设置')}
            aria-label={t('返回设置')}
            disabled={busy !== ''}
            onClick={onBack}
          >
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>

      <p className="virtual-screen-description">
        {t('把目标应用放到独立副屏上运行：页面预览与悬浮窗只显示它的画面，取帧档位与方向在这里单独设置。需要 Shizuku 与 Android 10 或更高版本。')}
      </p>

      <section className="settings-subsection virtual-screen-status" aria-labelledby="virtual-screen-status-title">
        <div className="section-title">
          <span className="section-icon"><Monitor size={19} /></span>
          <div>
            <h3 id="virtual-screen-status-title">{t('副屏状态')}</h3>
            <p>{t('读数来自原生侧；取不到时显示「未知」，不补 0。')}</p>
          </div>
        </div>
        {deviceState.status === 'loading' && <p role="status">{t('正在读取副屏状态…')}</p>}
        {deviceState.status === 'unavailable' && (
          <div className="virtual-screen-failure" role="alert">
            <TriangleAlert size={18} />
            <div>
              <strong>{t('读不到副屏状态')}</strong>
              <p>{t('这不代表副屏没有在运行：{0}', deviceState.reason || t('原生桥没有给出原因'))}</p>
              <button className="button button-secondary compact-button" type="button" onClick={() => { void readState() }}>
                {t('重试')}
              </button>
            </div>
          </div>
        )}
        {deviceState.status === 'ready' && (
          <>
            <dl className="virtual-screen-readings">
              <div>
                <dt>{t('运行状态')}</dt>
                <dd>{deviceState.state.active ? t('运行中') : t('未运行')}</dd>
              </div>
              <div>
                <dt>{t('会话 id')}</dt>
                <dd>{t(textOrUnknown(deviceState.state.sessionId))}</dd>
              </div>
              <div>
                <dt>{t('displayId')}</dt>
                <dd>{t(formatReading(deviceState.state.displayId, ''))}</dd>
              </div>
              <div>
                <dt>{t('目标应用')}</dt>
                <dd>{t(textOrUnknown(deviceState.state.targetPackage))}</dd>
              </div>
              <div>
                <dt>{t('实际生效规格')}</dt>
                <dd>{t(specReading(deviceState.state))}</dd>
              </div>
              <div>
                <dt>{t('采集帧率')}</dt>
                <dd>{t(formatReading(deviceState.state.frameFps, 'FPS'))}</dd>
              </div>
              <div>
                <dt>{t('虚拟屏实际刷新率')}</dt>
                <dd>{t(formatReading(deviceState.state.displayRefreshRate, 'Hz'))}</dd>
              </div>
              <div>
                <dt>{t('副屏当前前台应用')}</dt>
                <dd>{t(textOrUnknown(deviceState.state.virtualForegroundPackage))}</dd>
              </div>
              <div>
                <dt>{t('副屏当前前台 Activity')}</dt>
                <dd>{t(textOrUnknown(deviceState.state.virtualForegroundActivity))}</dd>
              </div>
            </dl>
            <p className="settings-note">
              {t('取不到读数时显示「未知」，不会补 0 或留空白；副屏前台应用也不拿主屏顶替。')}
            </p>
          </>
        )}
      </section>

      {settingsState.status === 'loading' && <p role="status">{t('正在读取副屏设置…')}</p>}
      {settingsState.status === 'unavailable' && (
        <div className="virtual-screen-failure" role="alert">
          <TriangleAlert size={18} />
          <div>
            <strong>{t('读不到副屏设置')}</strong>
            <p>{t('这里读不到设置时，界面不会假装它是默认值：{0}', settingsState.reason || t('原生桥没有给出原因'))}</p>
            <button className="button button-secondary compact-button" type="button" onClick={() => { void readSettings() }}>
              {t('重试')}
            </button>
          </div>
        </div>
      )}

      {settingsState.status === 'ready' && draft !== null && (
        <>
          <section className="settings-subsection" aria-labelledby="virtual-screen-target-title">
            <div className="section-title">
              <span className="section-icon"><SlidersHorizontal size={19} /></span>
              <div>
                <h3 id="virtual-screen-target-title">{t('目标应用')}</h3>
                <p>{t('副屏只显示这一个应用的画面。')}</p>
              </div>
            </div>
            <p className="settings-note">
              {target === ''
                ? t('还没有选择目标应用：副屏只会显示这个应用的画面，先选一个再启动。')
                : t('将把 {0} 放到副屏运行。', target)}
            </p>
            <ApplicationPicker
              bridge={bridge}
              selected={target === '' ? [] : [target]}
              onChange={packages => setTarget(packages.length === 0 ? '' : packages[packages.length - 1])}
              disabled={locked}
            />
          </section>

          <section className="settings-subsection" aria-labelledby="virtual-screen-orientation-title">
            <div className="section-title">
              <span className="section-icon"><Monitor size={19} /></span>
              <div>
                <h3 id="virtual-screen-orientation-title">{t('屏幕方向')}</h3>
                <p>{t('「自动」按屏幕当前的物理方向决定。')}</p>
              </div>
            </div>
            <fieldset className="virtual-screen-fieldset" disabled={locked}>
              <legend className="virtual-screen-legend">{t('屏幕方向')}</legend>
              <div className="virtual-screen-choices">
                {VIRTUAL_SCREEN_ORIENTATION_VALUES.map(value => (
                  <label key={value} className="virtual-screen-choice">
                    <input
                      type="radio"
                      name="virtual-screen-orientation"
                      aria-label={t(orientationLabel(value))}
                      checked={draft.orientation === value}
                      disabled={locked}
                      onChange={() => patch({ orientation: value })}
                    />
                    <span>{t(orientationLabel(value))}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          </section>

          <section className="settings-subsection" aria-labelledby="virtual-screen-resolution-title">
            <div className="section-title">
              <span className="section-icon"><Monitor size={19} /></span>
              <div>
                <h3 id="virtual-screen-resolution-title">{t('分辨率')}</h3>
                <p>{t('自适应时宽高与 DPI 由原生按屏幕与方向计算。')}</p>
              </div>
            </div>
            <label className="toggle-row">
              <span>
                <strong>{t('自适应（按屏幕与方向计算）')}</strong>
                <small>{t('关掉后可以手填宽、高与 DPI。')}</small>
              </span>
              <input
                type="checkbox"
                role="switch"
                aria-label={t('自适应（按屏幕与方向计算）')}
                checked={draft.adaptive}
                disabled={locked}
                onChange={event => patch({ adaptive: event.target.checked })}
              />
            </label>
            <p className="settings-note">
              {t('关掉自适应后手填宽、高与 DPI：越界的值会就地夹到 {0}..{1}（宽高）或 {2}..{3}（DPI）。', VIRTUAL_SCREEN_MIN_EDGE, VIRTUAL_SCREEN_MAX_EDGE, VIRTUAL_SCREEN_MIN_DPI, VIRTUAL_SCREEN_MAX_DPI)}
            </p>
            <div className="virtual-screen-spec">
              {SPEC_FIELDS.map(field => (
                <label key={field} className="field">
                  <span>{t(specFieldInputLabel(field))}</span>
                  <input
                    type="number"
                    inputMode="numeric"
                    step={1}
                    min={specBounds(field).minimum}
                    max={specBounds(field).maximum}
                    aria-label={t(specFieldInputLabel(field))}
                    value={specText[field]}
                    disabled={locked || draft.adaptive}
                    onChange={event => changeSpec(field, event.target.value)}
                    onBlur={() => commitSpec(field)}
                  />
                </label>
              ))}
            </div>
            {specHint !== '' && <p className="virtual-screen-notice" role="status">{specHint}</p>}
            {!draft.adaptive && !specReady && !specCleared(draft) && (
              <p className="settings-note" role="status">
                {t('宽、高、DPI 三项要一起填才会生效：只填其中一两项时，原生侧会当作没有自定义尺寸，改用预设规格。')}
              </p>
            )}
            {!draft.adaptive && specCleared(draft) && baseline !== null && !specCleared(baseline) && (
              <p className="settings-note" role="status">
                {t('三项都会写回 0：保存后清掉自定义尺寸，改用按屏幕与方向算出的预设规格。')}
              </p>
            )}
            {!draft.adaptive && specReady && (
              <p className="settings-note">
                {t('原生启动时还会做一次二次收敛（只缩不放），所以实际生效的规格以状态区读数为准。')}
              </p>
            )}
          </section>

          <section className="settings-subsection" aria-labelledby="virtual-screen-preview-title">
            <div className="section-title">
              <span className="section-icon"><SlidersHorizontal size={19} /></span>
              <div>
                <h3 id="virtual-screen-preview-title">{t('取帧档位')}</h3>
                <p>{t('档位越高取帧越密、越耗电。')}</p>
              </div>
            </div>
            <label className="field">
              <span>{t('取帧档位')}</span>
              <select
                aria-label={t('取帧档位')}
                value={draft.previewMode}
                disabled={locked}
                onChange={event => {
                  const next = normalizeVirtualScreenPreviewMode(event.target.value)
                  if (next !== null) patch({ previewMode: next })
                }}
              >
                {VIRTUAL_SCREEN_PREVIEW_MODES.map(mode => (
                  <option key={mode} value={mode}>{t(virtualScreenPreviewModeLabel(mode))}</option>
                ))}
              </select>
            </label>
            <p className="settings-note">{t('档位决定预览取帧节奏；虚拟屏实际刷新率以状态区的读数为准。')}</p>
          </section>

          <section className="settings-subsection" aria-labelledby="virtual-screen-follow-title">
            <div className="section-title">
              <span className="section-icon"><SlidersHorizontal size={19} /></span>
              <div>
                <h3 id="virtual-screen-follow-title">{t('自动跟随')}</h3>
                <p>{t('主屏与副屏之间怎么联动。')}</p>
              </div>
            </div>
            <fieldset className="virtual-screen-fieldset" disabled={locked}>
              <legend className="virtual-screen-legend">{t('自动跟随')}</legend>
              <div className="virtual-screen-choices">
                {VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES.map(value => (
                  <label key={value} className="virtual-screen-choice">
                    <input
                      type="radio"
                      name="virtual-screen-auto-follow"
                      aria-label={t(autoFollowLabel(value))}
                      checked={draft.autoFollow === value}
                      disabled={locked}
                      onChange={() => patch({ autoFollow: value })}
                    />
                    <span>{t(autoFollowLabel(value))}</span>
                    <small>{t(autoFollowHint(value))}</small>
                  </label>
                ))}
              </div>
            </fieldset>
          </section>

          <div className="virtual-screen-actions">
            <button
              className="button button-primary"
              type="button"
              disabled={busy !== ''}
              onClick={() => { void save() }}
            >
              {busy === 'save' ? <Loader2 className="spin" size={18} /> : <Save size={18} />}
              {t('保存设置')}
            </button>
            <span className="settings-note">{t('保存只写入偏好：实际生效的值以状态区读数为准。')}</span>
          </div>
          {saveFailure !== '' && <p className="virtual-screen-failure" role="alert">{saveFailure}</p>}
          {saveNotice !== '' && <p className="virtual-screen-notice" role="status">{t(saveNotice)}</p>}
        </>
      )}

      <section className="settings-subsection" aria-labelledby="virtual-screen-control-title">
        <div className="section-title">
          <span className="section-icon"><Play size={19} /></span>
          <div>
            <h3 id="virtual-screen-control-title">{t('控制')}</h3>
            <p>{t('启动、停止与重启副屏。')}</p>
          </div>
        </div>
        <div className="virtual-screen-actions">
          <button
            className="button"
            type="button"
            disabled={locked || target === ''}
            onClick={() => { void start() }}
          >
            {busy === 'start' ? <Loader2 className="spin" size={18} /> : <Play size={18} />}
            {t('启动副屏')}
          </button>
          <button
            className="button button-secondary"
            type="button"
            disabled={busy !== ''}
            onClick={() => { void stop() }}
          >
            {busy === 'stop' ? <Loader2 className="spin" size={18} /> : <Square size={18} />}
            {t('停止副屏')}
          </button>
          <button
            className="button button-secondary"
            type="button"
            disabled={locked || target === ''}
            onClick={() => { void restart() }}
          >
            {busy === 'restart' ? <Loader2 className="spin" size={18} /> : <RotateCw size={18} />}
            {t('重启副屏')}
          </button>
          <button
            className="button button-secondary"
            type="button"
            disabled={busy !== ''}
            onClick={() => { void openNativePage() }}
          >
            {busy === 'native' ? <Loader2 className="spin" size={18} /> : <Monitor size={18} />}
            {t('打开系统副屏页面')}
          </button>
        </div>
        <p className="settings-note">
          {t('停止不需要设置读取成功：副屏在跑、页面读不到设置时也能停掉它。')}
        </p>
        <p className="settings-note">
          {t('「打开系统副屏页面」是预览与悬浮小窗用的那个原生页面；设置项都在本页，不用再回那里改。')}
        </p>
        {actionFailure !== '' && <p className="virtual-screen-failure" role="alert">{actionFailure}</p>}
        {actionNotice !== '' && <p className="virtual-screen-notice" role="status">{t(actionNotice)}</p>}
      </section>
    </div>
  )
}

/** 屏幕方向的中文标签。 */
function orientationLabel(value: (typeof VIRTUAL_SCREEN_ORIENTATION_VALUES)[number]): string {
  if (value === 'portrait') return '竖屏'
  if (value === 'landscape') return '横屏'
  return '自动'
}

/** 自动跟随的中文标签。 */
function autoFollowLabel(value: (typeof VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES)[number]): string {
  if (value === 'pull_back') return '拉回目标'
  if (value === 'promote') return '提升副屏前台应用'
  return '关闭'
}

/** 自动跟随的一句话解释；三种策略各自「做什么」与「不做什么」都写清楚。 */
function autoFollowHint(value: (typeof VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES)[number]): string {
  if (value === 'pull_back') return '目标应用跑到主屏最前台时，把它拉回副屏继续显示。'
  if (value === 'promote') return '副屏里切到别的应用时，把它提升为主屏前台；副屏停在桌面这类情况不做判断。'
  return '不跟随：副屏一直显示你选的目标应用，不与主屏联动。'
}

/** 三格规格输入的中文标签（与无障碍名称同源）。 */
function specFieldInputLabel(field: SpecField): string {
  if (field === 'densityDpi') return '副屏 DPI'
  return field === 'widthPx' ? '副屏宽度' : '副屏高度'
}
