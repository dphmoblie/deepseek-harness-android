import { useCallback, useEffect, useState } from 'react'
import { ShieldCheck, TerminalSquare } from 'lucide-react'
import { t } from '../i18n'
import type { RuntimeBridge } from '../platform/types'

export function DeviceShellSettings({ bridge, disabled }: { bridge: RuntimeBridge; disabled: boolean }) {
  const [enabled, setEnabled] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [failed, setFailed] = useState(false)
  const [opening, setOpening] = useState(false)
  const [screenError, setScreenError] = useState('')
  const refresh = useCallback(async () => {
    setLoaded(false); setFailed(false)
    try { const state = await bridge.getDeviceShellAccess(); setEnabled(state.enabled); setLoaded(true) }
    catch { setFailed(true) }
  }, [bridge])
  useEffect(() => { void refresh() }, [refresh])
  async function save(next: boolean) {
    setSaving(true); setFailed(false)
    try { const state = await bridge.setDeviceShellAccess(next); setEnabled(state.enabled) }
    catch { setFailed(true); setLoaded(false) }
    finally { setSaving(false) }
  }
  async function openScreen() {
    setOpening(true); setScreenError('')
    try { await bridge.openVirtualScreen() }
    catch { setScreenError(t('无法打开副屏页面，请确认当前使用安卓壳且系统为 Android 10 或更高版本')) }
    finally { setOpening(false) }
  }
  return <div className="settings-subsection" aria-labelledby="device-shell-settings">
    <div className="section-title"><span className="section-icon"><TerminalSquare size={19} /></span>
      <div><h3 id="device-shell-settings">AI Shell</h3><p>{t('通过 Shizuku 执行 Android 命令，支持读写文件和查看后台任务。')}</p></div>
    </div>
    <label className="toggle-row"><span><strong>{t('允许 AI 调用 Shell')}</strong><small>{t('开启后无需逐条确认，关闭后拒绝新的 Shell 请求')}</small></span>
      <input type="checkbox" role="switch" aria-label={t('允许 AI 调用 Shell')} checked={enabled} disabled={disabled || !loaded || saving} onChange={event => { void save(event.target.checked) }} />
    </label>
    <p className="settings-note">{t('Shell 使用 Shizuku 的实际权限，不受无障碍应用白名单限制。命令和输出不写入诊断日志；需要返回给 AI 的内容会进入当前会话及所选模型服务。')}</p>
    <p className="settings-note"><strong>{t('写入、覆盖、删除会修改真实设备数据，请让 AI 只处理本次任务需要的文件。')}</strong></p>
    <button type="button" disabled={disabled || !loaded || !enabled || opening || saving} onClick={() => { void openScreen() }}>{t('目标应用副屏（实验功能）')}</button>
    <p className="settings-note">{t('选择应用在独立副屏运行，可切换页面或小窗查看，并让 AI 截图、点击、滑动。需要 Shizuku 和设备支持；首次使用请先验证目标应用兼容性。')}</p>
    {screenError && <p role="alert">{screenError}</p>}
    {failed && <p role="alert">{t('无法读取或保存 AI Shell 授权状态')} <button type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
    {/* 免责说明：只追加一小块，不动这个子区块的既有结构与文案。 */}
    <div className="settings-subsection" aria-labelledby="accessibility-disclaimer">
      <div className="section-title"><span className="section-icon"><ShieldCheck size={19} /></span>
        <div><h3 id="accessibility-disclaimer">{t('免责说明')}</h3></div>
      </div>
      <p className="settings-note">{t('无障碍自动化是你自行开启、自行选择目标应用的能力；请只对自己有权操作的应用启用。')}</p>
      <p className="settings-note">{t('自动化可能误点或误读界面，涉及支付、验证码与隐私信息的页面请自行确认，由此产生的后果由使用者承担。')}</p>
      <p className="settings-note">{t('该能力只在本机生效：服务不做后台监听，也不批量上传界面；只有你让 AI 执行操作时，读取到的界面节点才会按需进入当前会话与所选模型服务。')}</p>
    </div>
  </div>
}
