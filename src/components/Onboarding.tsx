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
                  <p>{t("这是一个运行在")}<strong>{t("本机")}</strong> {t("的 Harness 控制台（DSH 移动版）：")}</p>
                  <ul>
                    <li>{t("Ubuntu 运行时与 Harness 只监听")}<code>127.0.0.1</code>{t("，不出设备；")}</li>
                    <li>{t("模型密钥只保存在本机，不会回传到管理界面；")}</li>
                    <li>{t("数据与审计都在应用私有目录，可随时重置；")}</li>
                    <li>{t("容器内是")}<strong>{t("受限 root")}</strong>：<code>/system</code>、<code>/data</code> {t("等系统路径受 Android 保护，无法越权修改（apt/系统安装不可用）；")}</li>
                    <li>{t("已内置 Node.js、Python 3 与常用工具（busybox、jq、unzip）；gcc 等编译工具不在包内；")}</li>
                    <li>{t("如需访问手机文件或系统操作，请配合 Shizuku 设备 Shell。")}</li>
                  </ul>
                </div>
              )}
              {step === 1 && (
                <div className="onboarding-body">
                  <p>{t("首次使用需要安装 Ubuntu 运行环境（数百 MB）：内置包直接读取，远程来源可经 Wi-Fi 下载。")}</p>
                  {!manifestConfigured(settings) && (
                    <p className="onboarding-warn">{t("尚未配置运行时下载地址：请先在「设置 → 运行与后台」填写清单地址与 SHA-256（两者必须成对）。")}</p>
                  )}
                  <p className="onboarding-status">
                    {t("当前状态：")}{installed ? t("已安装") + (runtime.phase === 'running' ? t(" · 运行中") : '') : runtime.phase === 'downloading' || runtime.phase === 'verifying' || runtime.phase === 'extracting' ? t("正在安装…") : t("未安装")}
                  </p>
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
                  <p>{t("选择模型供应商并保存 API Key。密钥在设备上加密保存，页面不会回显明文。")}</p>
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
                    ? t('已配置 {0} 个自定义供应商密钥', settings?.configuredCustomModelProviders?.length ?? 0)
                    : settings?.configuredModelProviders.includes(selectedProvider) ? t('已配置') : t('未配置')}</p>
                </form>
              )}
              {step === 3 && (
                <div className="onboarding-body">
                  <p>{t("Shizuku 让「设备 Shell」终端以 shell 权限执行系统命令（可选，不影响 Ubuntu 终端）。")}</p>
                  <ul>
                    <li>{t("需要安装 Shizuku 应用并完成一次性引导；")}</li>
                    <li>{t("授权可随时在「设置」页撤销；")}</li>
                    <li>{t("未授权时设备 Shell 相关功能自动禁用（fail-closed）。")}</li>
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
                  <p>{t("对话与设置界面都使用官方 Harness 前端：只补充了安全区与触控尺寸适配，页面结构、主题与交互保持上游原样；模型、推理强度与插件入口不会被移动端改写。")}</p>
                  <p>{t("插件统一在设置页的「插件管理」中管理，应用不会静默关闭任何插件；需要更多插件时，可在 Harness 内的市场页面按需安装。")}</p>
                </div>
              )}
              {step === 5 && (
                <div className="onboarding-body">
                  {modelConfigured ? (
                    <>
                      <p>{t("一切就绪。打开 Harness 开始对话；随时返回本界面管理运行环境和终端。")}</p>
                      <div className="onboarding-actions-row">
                        <button className="button button-primary" type="button" disabled={busy !== null || runtime.phase !== 'running'} onClick={onOpenHarness}>
                          <Rocket size={18} />
                          {t("打开 Harness")}</button>
                      </div>
                    </>
                  ) : (
                    <>
                      <p className="onboarding-warn">{t("还没配置模型 API Key，因此暂不打开 Harness：没有密钥时每一轮对话都会因缺少凭据失败。")}</p>
                      <ul>
                        <li>{t("密钥加密保存在本机，页面不会回显明文，也不会回传到管理界面；")}</li>
                        <li>{t("配置成功后本步骤会出现「打开 Harness」按钮；")}</li>
                        <li>{t("如果你已在 Harness 页面内配置过密钥，也可以直接打开：应用看不到 Harness 自己保存的凭据，不会代你确认它是否可用。")}</li>
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
