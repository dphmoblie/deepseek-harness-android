import { useRef, useState } from 'react'
import { t } from '../i18n'
import { ArrowLeft, ArrowRight, Blocks, Check, KeyRound, Loader2, X } from 'lucide-react'
import type { RuntimeSettings, RuntimeSettingsUpdate, RuntimeState } from '../platform/types'

export const ONBOARDING_STORAGE_KEY = 'dsh-mobile-onboarding-v1'

interface OnboardingProps {
  busy: string | null
  runtime: RuntimeState
  settings: RuntimeSettings | null
  onInstall: () => void
  /**
   * 在线安装入口：APK 没内置镜像时，安装按钮会失败，用户需要去版本管理里下载一个带摘要校验的版本。
   * 缺省时不显示这个按钮（测试与嵌入式用法不需要它）。
   */
  onOpenVersions?: () => void
  onDone: () => void
  onSaveSettings?: (settings: RuntimeSettingsUpdate) => Promise<boolean>
}

/**
 * 首次配置只留两步：配模型 → 装运行环境。
 *
 * 顺序是刻意的：模型凭据不依赖运行环境，先配上，用户就不会卡在「镜像没内置 → 装不上 →
 * 连密钥页都到不了」的死路上；运行环境排在后面，装不上也不影响保存凭据。
 *
 * 这里只放 DeepSeek 的 API Key。账号登录、自定义供应商都属于「运行环境里的事」：
 * 它们要开着 Harness 才有意义，塞进首次配置只会让一个本来能跳过的小窗口变成账号页。
 */
const STEPS = [
  { id: 'model', title: '配置模型', icon: KeyRound },
  { id: 'runtime', title: '安装运行环境', icon: Blocks },
] as const

/**
 * 从聊天软件粘贴的密钥常带折行、空格、外层引号与零宽字符，原样保存会得到一份
 * 「看着对、用起来 401」的凭据。这里统一收窄成一行，并在界面上如实提示做过清理。
 */
function normalizeApiKey(raw: string): string {
  return raw
    .replace(/[\u200b-\u200d\ufeff]/gi, '')
    .replace(/\s+/g, '')
    .replace(/^bearer/i, '')
    .replace(/^["'`]+|["'`]+$/g, '')
}

/** 空来源代表 APK 内置镜像；有来源才是从在线地址下载。 */
function usesBundledSource(settings: RuntimeSettings | null): boolean {
  return settings === null || settings.manifestUrl.trim() === ''
}

export function Onboarding({
  busy, runtime, settings, onInstall, onOpenVersions, onDone, onSaveSettings,
}: OnboardingProps) {
  const [step, setStep] = useState(0)
  const [saving, setSaving] = useState(false)
  const [saveFailed, setSaveFailed] = useState(false)
  /**
   * 密钥输入是**非受控**的：值只留在 DOM 里，React 不回写。
   * 受控输入在 Android 输入法组合态（拼音/联想）下每敲一个字都要回写 value，
   * 组合被打断就会出现「输入法截断后内容凭空消失」。这里只用 state 记长度给按钮用。
   */
  const [keyLength, setKeyLength] = useState(0)
  const [keyCleaned, setKeyCleaned] = useState(false)
  const keyInput = useRef<HTMLInputElement | null>(null)
  /** 切步骤会把输入框卸载再挂回来；用 ref 记住原文，挂回时用 defaultValue 还原。 */
  const keyMirror = useRef('')
  const savePending = useRef(false)
  const last = step === STEPS.length - 1

  const installed = ['ready', 'running'].includes(runtime.phase)
  const installing = runtime.phase === 'downloading' || runtime.phase === 'verifying' || runtime.phase === 'extracting'
  /** 凭据可能在 Harness 里配过，两条路都算「已配置」，界面上分别说明。 */
  const keyConfiguredHere = settings?.configuredModelProviders.includes('deepseek') === true
  const keyConfiguredInHarness = settings?.harnessConfiguredModelProviders?.includes('deepseek') === true

  const baseSettings = settings ?? {
    manifestUrl: '',
    manifestSha256: '',
    keepScreenAwake: true,
    terminalFontSize: 14,
    configuredModelProviders: [],
  }

  /** 从 DOM 读当前密钥，顺手完成清理；不依赖 React state，组合输入丢事件也不会丢内容。 */
  const readApiKey = (): string => normalizeApiKey(keyInput.current?.value ?? '')

  /** 组合输入结束时补一次同步：部分输入法只发 compositionend，不发 change。 */
  const syncApiKey = (input: HTMLInputElement): void => {
    const normalized = normalizeApiKey(input.value)
    keyMirror.current = input.value
    setKeyLength(normalized.length)
    setKeyCleaned(normalized !== input.value)
  }

  const saveModelSettings = async (): Promise<void> => {
    if (busy !== null || savePending.current || settings === null || onSaveSettings === undefined) return
    const apiKey = readApiKey()
    if (apiKey === '') return
    savePending.current = true
    setSaving(true)
    setSaveFailed(false)
    try {
      const saved = await onSaveSettings({ ...baseSettings, providerApiKeys: { deepseek: apiKey } })
      // 凭据只在落盘成功后清除；忙碌或保存失败时保留用户输入以便重试。
      if (saved) {
        if (keyInput.current !== null) keyInput.current.value = ''
        keyMirror.current = ''
        setKeyLength(0)
        setKeyCleaned(false)
      } else setSaveFailed(true)
    } catch {
      setSaveFailed(true)
    } finally {
      savePending.current = false
      setSaving(false)
    }
  }

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title">
        <button className="dialog-close" type="button" aria-label={t("跳过引导")} onClick={onDone} disabled={saving}><X size={19} /></button>
        <span className="onboarding-step-dots" aria-label={t("步骤 ") + (step + 1) + ' / ' + STEPS.length}>
          {STEPS.map((s, index) => (
            <span key={s.id} className={index === step ? 'active' : index < step ? 'done' : ''} />
          ))}
        </span>
        {(() => {
          const Icon = STEPS[step].icon
          return (
            <>
              <h2 id="onboarding-title" className="onboarding-title"><Icon size={22} />{t(STEPS[step].title)}</h2>
              {step === 0 && (
                <form className="onboarding-body" onSubmit={event => {
                  event.preventDefault()
                  void saveModelSettings()
                }}>
                  <fieldset className="onboarding-fields" disabled={saving}>
                  <p>{t("粘贴一份模型 API Key 就能开始用。密钥保存在本机，保存后不再显示。")}</p>
                  <label className="field">
                    <span>DeepSeek API Key</span>
                    <input
                      ref={keyInput}
                      type="text"
                      inputMode="text"
                      autoComplete="off"
                      autoCapitalize="none"
                      autoCorrect="off"
                      spellCheck={false}
                      enterKeyHint="done"
                      maxLength={512}
                      defaultValue={keyMirror.current}
                      placeholder={t("粘贴 API Key")}
                      onChange={event => syncApiKey(event.currentTarget)}
                      onCompositionEnd={event => syncApiKey(event.currentTarget)}
                      onBlur={event => syncApiKey(event.currentTarget)}
                    />
                  </label>
                  <p className="onboarding-status">
                    {keyLength > 0 ? t("已输入 {0} 个字符", String(keyLength)) : t("首次配置不遮罩密钥，方便你确认粘贴完整。")}
                    {keyCleaned && ' ' + t("已自动去掉空格、换行与引号。")}
                  </p>
                  <button className="button button-primary" type="submit" disabled={busy !== null || saving || settings === null || onSaveSettings === undefined || keyLength === 0}>
                    <KeyRound size={18} />{t('保存 API Key')}
                  </button>
                  <p className="onboarding-status">{keyConfiguredHere
                    ? t('已配置')
                    : keyConfiguredInHarness
                      ? t('已在 Harness 中配置')
                      : t('未配置')}</p>
                  {saveFailed && <p role="alert">{t('未能保存配置，输入已保留，请重试。')}</p>}
                  {/* 窗口里只做两件事：配 API Key、装运行环境。其余设置留给 Harness 与设置页。 */}
                  <p className="onboarding-status">{t("这一页只配置 API Key，其它设置装好后按需调整。")}</p>
                  {/* 用第三方接口的人不该在这一步卡住：告诉他们去哪儿配。 */}
                  <p className="onboarding-status">{t("用第三方接口？这步可以跳过，装好环境后在「设置 → 模型供应商 → 自定义供应商」里填 Base URL 与密钥。")}</p>
                  </fieldset>
                </form>
              )}
              {step === 1 && (
                <div className="onboarding-body">
                  <p>{t("应用需要一份 Ubuntu 运行环境，装一次就够，之后可离线使用。")}</p>
                  <p className="onboarding-status">
                    {t("当前状态：")}{installed ? t("已安装") + (runtime.phase === 'running' ? t(" · 运行中") : '') : installing ? t("正在安装…") : t("未安装")}
                  </p>
                  <p className="onboarding-status">{usesBundledSource(settings)
                    ? t("这次使用 APK 内置镜像，不联网也能装完。")
                    : t("这次从你设置的在线地址下载，建议连 Wi-Fi。")}</p>
                  <button className="button button-primary" type="button" disabled={busy !== null || installed || installing} onClick={onInstall}>
                    {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Blocks size={18} />}
                    {installed ? t("已安装") : t("安装运行时")}
                  </button>
                  {!installed && onOpenVersions !== undefined && (
                    <>
                      <button className="button button-secondary" type="button" onClick={onOpenVersions}>{t("安装失败？去在线下载其它版本")}</button>
                      <p className="onboarding-status">{t("自行构建的 APK 里可能没有内置镜像；这时到「版本管理 → 在线可用版本」下载一个带校验的版本即可。")}</p>
                    </>
                  )}
                  <p className="onboarding-status">{t("内置工具已含 Node.js、Python 3、busybox、jq 与 unzip；不含 C 编译器，需要时自行安装。")}</p>
                  <p className="onboarding-status">{t("装好运行环境后打开 Harness 就能直接对话。")}</p>
                </div>
              )}
              <div className="dialog-actions onboarding-nav">
                {step > 0 && (
                  <button className="button button-secondary" type="button" onClick={() => setStep(step - 1)} disabled={saving}><ArrowLeft size={18} />{t("上一步")}</button>
                )}
                <span className="onboarding-spacer" />
                {last ? (
                  <button className="button button-primary" type="button" onClick={onDone}><Check size={18} />{t("完成")}</button>
                ) : (
                  <button className="button button-primary" type="button" onClick={() => setStep(step + 1)} disabled={saving}>{t("下一步")}<ArrowRight size={18} /></button>
                )}
              </div>
            </>
          )
        })()}
      </div>
    </div>
  )
}
