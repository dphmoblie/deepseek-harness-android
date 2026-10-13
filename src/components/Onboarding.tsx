import { useEffect, useState } from 'react'
import { t } from '../i18n'
import { ArrowLeft, ArrowRight, Blocks, Check, KeyRound, Loader2, Rocket, ShieldCheck, Sparkles, X } from 'lucide-react'
import { CustomProviders } from './CustomProviders'
import { hasConfiguredModelCredential, MODEL_PROVIDERS } from '../modelProviders'
import type { CustomModelProvider, ModelProviderId, RuntimeSettings, RuntimeSettingsUpdate, RuntimeState, ShizukuState } from '../platform/types'

export const ONBOARDING_STORAGE_KEY = 'dsh-mobile-onboarding-v1'

interface OnboardingProps {
  busy: string | null
  runtime: RuntimeState
  shizuku: ShizukuState
  settings: RuntimeSettings | null
  onInstall: () => void
  onAuthorize: () => void
  onOpenShizuku: () => void
  onOpenHarness: () => void
  /** 用户确认「密钥已在 Harness 内配置过」时的放行入口。 */
  onOpenHarnessConfirmed: () => void
  onDone: () => void
  onSaveSettings?: (settings: RuntimeSettingsUpdate) => void
}

const STEPS = [
  { id: 'welcome', title: '欢迎使用 DeepSeek Harness Android', icon: Sparkles },
  { id: 'runtime', title: '安装 Ubuntu 运行时', icon: Blocks },
  { id: 'apikey', title: '配置模型 API Key', icon: KeyRound },
  { id: 'shizuku', title: '设备 Shell（可选）', icon: ShieldCheck },
  { id: 'plugins', title: '界面与插件', icon: Blocks },
  { id: 'ready', title: '开始使用', icon: Rocket },
] as const

function manifestConfigured(settings: RuntimeSettings | null): boolean {
  if (settings === null) return false
  return !settings.manifestUrl.includes('example.invalid') && settings.manifestSha256.length === 64
}

export function Onboarding({
  busy, runtime, shizuku, settings,
  onInstall, onAuthorize, onOpenShizuku, onOpenHarness, onOpenHarnessConfirmed, onDone, onSaveSettings,
}: OnboardingProps) {
  const [step, setStep] = useState(0)
  const [apiKeyDraft, setApiKeyDraft] = useState('')
  const [selectedProvider, setSelectedProvider] = useState<ModelProviderId | 'custom'>('deepseek')
  const [customProviders, setCustomProviders] = useState<CustomModelProvider[]>(settings?.customModelProviders ?? [])
  const [customCredentials, setCustomCredentials] = useState<Record<string, string>>({})
  const [clearedCustomProviders, setClearedCustomProviders] = useState<string[]>([])
  const last = step === STEPS.length - 1

  const installed = ['ready', 'running'].includes(runtime.phase)
  const authorized = shizuku.permission === 'granted'
  // 首次配置必须完成一次模型密钥：没有密钥时 Harness 里的对话必然失败。
  const modelConfigured = hasConfiguredModelCredential(settings)

  useEffect(() => {
    setCustomProviders(settings?.customModelProviders ?? [])
    setCustomCredentials({})
    setClearedCustomProviders([])
  }, [settings])

  const baseSettings = settings ?? {
    manifestUrl: '',
    manifestSha256: '',
    keepScreenAwake: true,
    terminalFontSize: 14,
    configuredModelProviders: [],
    autoLaunch: true,
  }

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title">
        <button className="dialog-close" type="button" aria-label={t("跳过引导")} onClick={onDone}><X size={19} /></button>
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
                <div className="onboarding-body">
                  <p>{t("DeepSeek Harness 移动版在本机运行 Ubuntu 环境，提供对话、终端和应用管理功能。")}</p>
                  <ul>
                    <li>{t("Harness 的本地服务仅供本机访问；模型请求会发送给你配置的模型服务。")}</li>
                    <li>{t("在此保存的模型密钥会在本机加密存储，供 Harness 连接模型服务使用；")}</li>
                    <li>{t("运行环境和审计日志保存在应用私有目录中；")}</li>
                    <li>{t("Ubuntu 内的 root 身份不等于手机 Root 权限，不能绕过 Android 限制修改系统文件。重置运行环境会清除其中的改动。")}</li>
                    <li>{t("已内置 Node.js、Python 3 与常用工具（busybox、jq、unzip）；gcc 等编译工具不在包内；")}</li>
                    <li>{t("如需访问手机文件或系统操作，请配合 Shizuku 设备 Shell。")}</li>
                  </ul>
                </div>
              )}
              {step === 1 && (
                <div className="onboarding-body">
                  <p>{t("首次使用需要安装 Ubuntu 运行环境（数百 MB）。应用内置的安装包可离线安装；使用在线来源时，建议通过 Wi-Fi 下载。")}</p>
                  {!manifestConfigured(settings) && (
                    <p className="onboarding-warn">{t("尚未配置运行时下载地址：请先在「设置 → 后台」填写清单地址与 SHA-256（两者必须成对）。")}</p>
                  )}
                  <p className="onboarding-status">
                    {t("当前状态：")}{installed ? t("已安装") + (runtime.phase === 'running' ? t(" · 运行中") : '') : runtime.phase === 'downloading' || runtime.phase === 'verifying' || runtime.phase === 'extracting' ? t("正在安装…") : t("未安装")}
                  </p>
                  <p className="onboarding-status">{t("运行环境未预装 C 编译器。安装后可在「设置 → 版本管理 → 运行时自检」查看可用工具；缺少可选工具不影响基本使用。如需编译代码，请自行安装所需工具链。")}</p>
                  <button className="button button-primary" type="button" disabled={busy !== null || installed} onClick={onInstall}>
                    {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Blocks size={18} />}
                    {t("安装运行时")}</button>
                </div>
              )}
              {step === 2 && (
                <form className="onboarding-body" onSubmit={event => {
                  event.preventDefault()
                  if (selectedProvider === 'custom') {
                    onSaveSettings?.({
                      ...baseSettings,
                      customModelProviders: customProviders,
                      ...(Object.keys(customCredentials).length === 0 ? {} : { customProviderApiKeys: customCredentials }),
                      ...(clearedCustomProviders.length === 0 ? {} : { clearCustomProviderApiKeys: clearedCustomProviders }),
                    })
                    setCustomCredentials({})
                    setClearedCustomProviders([])
                    return
                  }
                  onSaveSettings?.({
                    ...baseSettings,
                    providerApiKeys: { [selectedProvider]: apiKeyDraft.trim() },
                  })
                  setApiKeyDraft('')
                }}>
                  <p>{t("选择模型供应商并保存 API Key。密钥在本机加密保存，保存后不再显示。")}</p>
                  {!modelConfigured && (
                    <p className="onboarding-warn">{t("首次配置至少要保存一份模型 API Key：没有密钥时「打开 Harness」不可用。")}</p>
                  )}
                  <label className="field">
                    <span>{t("供应商")}</span>
                    <select value={selectedProvider} onChange={event => setSelectedProvider(event.target.value as ModelProviderId | 'custom')}>
                      {MODEL_PROVIDERS.map(provider => (
                        <option key={provider.id} value={provider.id}>{provider.label}</option>
                      ))}
                      <option value="custom">{t('自定义')}</option>
                    </select>
                  </label>
                  {selectedProvider !== 'custom' && <label className="field">
                    <span>{MODEL_PROVIDERS.find(provider => provider.id === selectedProvider)?.label ?? selectedProvider} API Key</span>
                    <input
                      type="password"
                      autoComplete="new-password"
                      spellCheck={false}
                      maxLength={200}
                      placeholder={t("粘贴 API Key（必填）")}
                      value={apiKeyDraft}
                      onChange={event => setApiKeyDraft(event.target.value)}
                    />
                  </label>}
                  {selectedProvider === 'custom' && <CustomProviders
                    providers={customProviders}
                    configured={settings?.configuredCustomModelProviders ?? []}
                    harnessConfigured={settings?.harnessConfiguredCustomModelProviders ?? []}
                    credentials={customCredentials}
                    cleared={clearedCustomProviders}
                    onChange={setCustomProviders}
                    onCredentials={setCustomCredentials}
                    onClear={setClearedCustomProviders}
                  />}
                  <button className="button button-primary" type="submit" disabled={selectedProvider === 'custom' ? customProviders.length === 0 : apiKeyDraft.trim() === ''}>
                    <KeyRound size={18} />{selectedProvider === 'custom' ? t('保存自定义供应商') : t('保存 API Key')}
                  </button>
                  <p className="onboarding-status">{selectedProvider === 'custom'
                    ? t('已配置 {0} 个自定义供应商密钥', new Set([
                        ...(settings?.configuredCustomModelProviders ?? []),
                        ...(settings?.harnessConfiguredCustomModelProviders ?? []),
                      ]).size)
                    : settings?.configuredModelProviders.includes(selectedProvider)
                      ? t('已配置')
                      : settings?.harnessConfiguredModelProviders?.includes(selectedProvider)
                        ? t('已在 Harness 中配置')
                        : t('未配置')}</p>
                </form>
              )}
              {step === 3 && (
                <div className="onboarding-body">
                  <p>{t("Shizuku 让「设备 Shell」终端以 shell 权限执行系统命令（可选，不影响 Ubuntu 终端）。")}</p>
                  <ul>
                    <li>{t("请先安装并启动 Shizuku，再为本应用授权；")}</li>
                    <li>{t("授权可随时在「设置」页撤销；")}</li>
                    <li>{t("获得授权后才能使用设备 Shell。")}</li>
                  </ul>
                  <div className="onboarding-actions-row">
                    {!shizuku.installed || !shizuku.running ? (
                      <button className="button button-primary" type="button" onClick={onOpenShizuku}>{t("安装 / 打开 Shizuku")}</button>
                    ) : (
                      <button className="button button-primary" type="button" disabled={busy !== null || authorized} onClick={onAuthorize}>
                        {busy === 'shizuku-permission' ? <Loader2 className="spin" size={18} /> : <ShieldCheck size={18} />}
                        {authorized ? t("已授权") : t("授权设备 Shell")}
                      </button>
                    )}
                  </div>
                </div>
              )}
              {step === 4 && (
                <div className="onboarding-body">
                  <p>{t("Harness 对话界面基于官方前端，已适配手机屏幕和触控操作。模型、推理强度等选项仍在 Harness 中设置。")}</p>
                  <p>{t("插件统一在设置页的「插件管理」中管理，应用不会静默关闭任何插件；需要更多插件时，可在 Harness 内的市场页面按需安装。")}</p>
                </div>
              )}
              {step === 5 && (
                <div className="onboarding-body">
                  {modelConfigured ? (
                    <>
                      <p>{t("已检测到模型配置。Harness 启动后即可开始对话，也可随时返回这里管理运行环境和终端。")}</p>
                      <div className="onboarding-actions-row">
                        <button className="button button-primary" type="button" disabled={busy !== null || runtime.phase !== 'running'} onClick={onOpenHarness}>
                          <Rocket size={18} />
                          {t("打开 Harness")}</button>
                      </div>
                    </>
                  ) : (
                    <>
                      <p className="onboarding-warn">{t("尚未检测到模型 API Key。请先配置密钥；如果已在 Harness 中配置，也可以直接打开。")}</p>
                      <ul>
                        <li>{t("密钥在本机加密保存，保存后不再显示；")}</li>
                        <li>{t("配置成功后本步骤会出现「打开 Harness」按钮；")}</li>
                        <li>{t("应用只读取 Harness 中的密钥配置状态，无法确认密钥是否有效，也可能无法识别其他来源的凭据。")}</li>
                      </ul>
                      <div className="onboarding-actions-row">
                        <button className="button button-primary" type="button" disabled={busy !== null} onClick={() => setStep(2)}>
                          <KeyRound size={18} />{t("去配置 API Key")}
                        </button>
                        <button className="button button-secondary" type="button" disabled={busy !== null || runtime.phase !== 'running'} onClick={onOpenHarnessConfirmed}>
                          <Rocket size={18} />{t("我已在 Harness 内配置过，仍要打开")}
                        </button>
                      </div>
                    </>
                  )}
                </div>
              )}
              <div className="dialog-actions onboarding-nav">
                {step > 0 && (
                  <button className="button button-secondary" type="button" onClick={() => setStep(step - 1)}><ArrowLeft size={18} />{t("上一步")}</button>
                )}
                <span className="onboarding-spacer" />
                {last ? (
                  <button className="button button-primary" type="button" onClick={onDone}><Check size={18} />{t("完成")}</button>
                ) : (
                  <button className="button button-primary" type="button" onClick={() => setStep(step + 1)}>{t("下一步")}<ArrowRight size={18} /></button>
                )}
              </div>
            </>
          )
        })()}
      </div>
    </div>
  )
}
