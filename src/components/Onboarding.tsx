import { useState } from 'react'
import { ArrowLeft, ArrowRight, Blocks, Check, KeyRound, Loader2, Rocket, ShieldCheck, Sparkles, X } from 'lucide-react'
import type { RuntimeSettings, RuntimeState, ShizukuState } from '../platform/types'

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
  onDone: () => void
  onSaveSettings: (settings: RuntimeSettings) => void
  settings: RuntimeSettings | null
}

const STEPS = [
  { id: 'welcome', title: '欢迎使用 DeepSeek Harness Android', icon: Sparkles },
  { id: 'runtime', title: '安装 Ubuntu 运行时', icon: Blocks },
  { id: 'apikey', title: '配置模型 API Key', icon: KeyRound },
  { id: 'shizuku', title: '设备 Shell（可选）', icon: ShieldCheck },
  { id: 'plugins', title: '插件与移动布局', icon: Blocks },
  { id: 'ready', title: '开始使用', icon: Rocket },
] as const

function manifestConfigured(settings: RuntimeSettings | null): boolean {
  if (settings === null) return false
  return !settings.manifestUrl.includes('example.invalid') && settings.manifestSha256.length === 64
}

export function Onboarding({
  busy, runtime, shizuku, settings,
  onInstall, onAuthorize, onOpenShizuku, onOpenHarness, onDone, onSaveSettings,
}: OnboardingProps) {
  const [step, setStep] = useState(0)
  const [apiKeyDraft, setApiKeyDraft] = useState('')
  const last = step === STEPS.length - 1

  const installed = ['ready', 'running'].includes(runtime.phase)
  const authorized = shizuku.permission === 'granted'

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog onboarding-dialog" role="dialog" aria-modal="true" aria-labelledby="onboarding-title">
        <button className="dialog-close" type="button" aria-label="跳过引导" onClick={onDone}><X size={19} /></button>
        <span className="onboarding-step-dots" aria-label={'步骤 ' + (step + 1) + ' / ' + STEPS.length}>
          {STEPS.map((s, index) => (
            <span key={s.id} className={index === step ? 'active' : index < step ? 'done' : ''} />
          ))}
        </span>
        {(() => {
          const Icon = STEPS[step].icon
          return (
            <>
              <h2 id="onboarding-title" className="onboarding-title"><Icon size={22} />{STEPS[step].title}</h2>
              {step === 0 && (
                <div className="onboarding-body">
                  <p>这是一个运行在 <strong>本机</strong> 的 Harness 控制台：</p>
                  <ul>
                    <li>Ubuntu 运行时与 Harness 只监听 <code>127.0.0.1</code>，不出设备；</li>
                    <li>模型凭据留在 Harness 凭据流程内，不经过管理界面；</li>
                    <li>数据与审计都在应用私有目录，可随时重置。</li>
                  </ul>
                </div>
              )}
              {step === 1 && (
                <div className="onboarding-body">
                  <p>首次使用需要安装 Ubuntu 运行时（约几百 MB，可经 Wi-Fi 下载）。</p>
                  {!manifestConfigured(settings) && (
                    <p className="onboarding-warn">尚未配置运行时下载地址：请先在「设置」页填写 manifest 地址与 SHA-256（两者必须成对）。</p>
                  )}
                  <p className="onboarding-status">
                    当前状态：{installed ? '已安装' + (runtime.phase === 'running' ? ' · 运行中' : '') : runtime.phase === 'downloading' || runtime.phase === 'verifying' || runtime.phase === 'extracting' ? '正在安装…' : '未安装'}
                  </p>
                  <button className="button button-primary" type="button" disabled={busy !== null || installed} onClick={onInstall}>
                    {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Blocks size={18} />}
                    安装运行时
                  </button>
                </div>
              )}
              {step === 2 && (
                <div className="onboarding-body">
                  <p>填入模型服务商（如 DeepSeek）的 API Key，Harness 才能对话。密钥只保存在本机，注入运行时环境，不经过网络。</p>
                  <label className="field">
                    <span>DeepSeek API Key（sk-...）</span>
                    <input
                      type="password"
                      autoComplete="off"
                      spellCheck={false}
                      maxLength={200}
                      placeholder="留空则稍后在设置页配置"
                      value={apiKeyDraft}
                      onChange={event => setApiKeyDraft(event.target.value)}
                    />
                  </label>
                  <button className="button button-primary" type="button" disabled={apiKeyDraft.trim() === ''} onClick={() => { onSaveSettings({ ...(settings ?? { manifestUrl: '', manifestSha256: '', keepScreenAwake: true, terminalFontSize: 14, autoLaunch: true }), apiKey: apiKeyDraft.trim() }); setApiKeyDraft('') }}>
                    <KeyRound size={18} />保存 API Key
                  </button>
                  <p className="onboarding-status">{settings?.apiKey ? '已配置（' + settings.apiKey.slice(0, 8) + '…）' : '未配置'}</p>
                </div>
              )}
              {step === 3 && (
                <div className="onboarding-body">
                  <p>Shizuku 让「设备 Shell」终端以 shell 权限执行系统命令（可选，不影响 Ubuntu 终端）。</p>
                  <ul>
                    <li>需要安装 Shizuku 应用并完成一次性引导；</li>
                    <li>授权可随时在「设置」页撤销；</li>
                    <li>未授权时设备 Shell 相关功能自动禁用（fail-closed）。</li>
                  </ul>
                  <div className="onboarding-actions-row">
                    {!shizuku.installed || !shizuku.running ? (
                      <button className="button button-primary" type="button" onClick={onOpenShizuku}>安装 / 打开 Shizuku</button>
                    ) : (
                      <button className="button button-primary" type="button" disabled={busy !== null || authorized} onClick={onAuthorize}>
                        {busy === 'shizuku-permission' ? <Loader2 className="spin" size={18} /> : <ShieldCheck size={18} />}
                        {authorized ? '已授权' : '授权设备 Shell'}
                      </button>
                    )}
                  </div>
                </div>
              )}
              {step === 4 && (
                <div className="onboarding-body">
                  <p>移动端已内置 <code>dsh-mobile-compat</code> 布局：小屏下侧栏变为抽屉、详情变为底部面板，桌面插件（文件/预览/看板/市场等）按移动形态适配。</p>
                  <p>更多插件可在 Harness 内通过市场（dshmarket）按需安装；宠物、实时统计等悬浮组件在手机上默认关闭。</p>
                </div>
              )}
              {step === 5 && (
                <div className="onboarding-body">
                  <p>一切就绪。打开 Harness 开始对话；随时返回本界面管理运行时与终端。</p>
                  <div className="onboarding-actions-row">
                    <button className="button button-primary" type="button" disabled={busy !== null || runtime.phase !== 'running'} onClick={onOpenHarness}>
                      <Rocket size={18} />
                      打开 Harness
                    </button>
                  </div>
                </div>
              )}
              <div className="dialog-actions onboarding-nav">
                {step > 0 && (
                  <button className="button button-secondary" type="button" onClick={() => setStep(step - 1)}><ArrowLeft size={18} />上一步</button>
                )}
                <span className="onboarding-spacer" />
                {last ? (
                  <button className="button button-primary" type="button" onClick={onDone}><Check size={18} />完成</button>
                ) : (
                  <button className="button button-primary" type="button" onClick={() => setStep(step + 1)}>下一步<ArrowRight size={18} /></button>
                )}
              </div>
            </>
          )
        })()}
      </div>
    </div>
  )
}
