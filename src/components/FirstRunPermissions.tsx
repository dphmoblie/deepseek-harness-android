import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, Loader2, ShieldCheck } from 'lucide-react'
import { t } from '../i18n'
import type { RuntimeBridge } from '../platform/types'

type PermissionId = 'notification' | 'media' | 'applications' | 'shizuku' | 'overlay' | 'all-files' | 'accessibility' | 'install-source'
type PermissionStatus = 'unknown' | 'granted' | 'denied' | 'unavailable' | 'failed'

export const FIRST_RUN_PERMISSIONS_STORAGE_KEY = 'dsh-mobile-permissions-v1'

interface PermissionItem {
  id: PermissionId
  title: string
  use: string
  risk: string
  denied: string
  special?: boolean
}

const PERMISSIONS: PermissionItem[] = [
  {
    id: 'notification', title: '通知权限',
    use: '显示后台保持、悬浮球和副屏服务的常驻通知，方便查看状态和停止服务。',
    risk: '服务状态可能显示在锁屏上；这项权限不能读取其他应用的通知。',
    denied: '后台服务仍可运行，但常驻通知可能不可见。',
  },
  {
    id: 'media', title: '照片和视频读取',
    use: '读取共享存储中已获授权的照片和视频，供文件管理和 Harness 工具使用。',
    risk: '照片和视频可能包含隐私。用于对话或 AI 操作时，内容可能发送给你配置的模型供应商。',
    denied: '共享媒体读取受限；仍可使用应用私有文件或系统文件选择器。',
  },
  {
    id: 'applications', title: '读取应用列表',
    use: '部分国产系统需要此权限，才能列出目标应用，供你选择副屏和自动化白名单。',
    risk: '应用可看到已安装应用的名称和包名。完整应用清单不会自动上传。',
    denied: '应用选择器可能只显示本应用；不支持此权限的系统无需授权。',
  },
  {
    id: 'shizuku', title: 'Shizuku 设备操作',
    use: '让设备 Shell 和 AI 通过 Shizuku 执行设备命令与文件操作。',
    risk: '设备命令可能修改文件、应用和系统设置。能力取决于 Shizuku 的启动方式，请只运行你信任的命令。',
    denied: '设备 Shell 和相关 AI 操作不可用；Ubuntu 终端与 Harness 不受影响。',
  },
  {
    id: 'overlay', title: '显示在其他应用上层', special: true,
    use: '在其他应用上显示悬浮球和小型 AI 对话窗口。',
    risk: '悬浮窗口可能遮挡其他应用内容。可随时关闭悬浮球或在系统设置中撤销权限。',
    denied: '悬浮球和悬浮 AI 窗口不可用；主应用仍可使用。',
  },
  {
    id: 'all-files', title: '所有文件访问', special: true,
    use: '访问共享存储中的投递区、附件和你选定的工作区目录。',
    risk: '可读写共享存储中的大量文件，错误操作可能删除或覆盖数据。交给 AI 处理的内容可能发送给模型供应商。',
    denied: '共享工作区和投递区不可用；应用私有文件与 Ubuntu 环境仍可使用。',
  },
  {
    id: 'accessibility', title: '无障碍服务', special: true,
    use: '读取目标应用界面并执行点击、输入、滑动等辅助操作。',
    risk: '屏幕可能包含隐私，AI 操作可能产生实际后果。用于 AI 分析的屏幕内容可能发送给模型供应商，请按需配置白名单。',
    denied: '依赖无障碍的自动化不可用；其他功能不受影响。',
  },
  {
    id: 'install-source', title: '安装未知应用', special: true,
    use: '通过系统安装器安装你下载并确认的应用更新。',
    risk: '安装软件会替换应用程序，仍需在系统安装器中确认。请核对更新来源。',
    denied: '应用内更新安装不可用；可手动从官方发布页下载和安装。',
  },
]

type StatusMap = Record<PermissionId, PermissionStatus>
const emptyStatus = (): StatusMap => Object.fromEntries(PERMISSIONS.map(item => [item.id, 'unknown'])) as StatusMap

interface FirstRunPermissionsProps {
  bridge: RuntimeBridge
  onDone: () => void
}

/** 设置页跳转只代表已打开；确实离开再返回后，才能查询结果并继续。 */
function createSettingsReturnWait() {
  let left = false
  let settled = false
  let resolve!: (returned: boolean) => void
  const promise = new Promise<boolean>(done => { resolve = done })
  const dispose = () => {
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('blur', onBlur)
    window.removeEventListener('focus', onFocus)
  }
  const finish = (returned: boolean) => {
    if (settled) return
    settled = true
    dispose()
    resolve(returned)
  }
  const onBlur = () => { left = true }
  const onFocus = () => { if (left && document.visibilityState !== 'hidden') finish(true) }
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') left = true
    else if (left) finish(true)
  }
  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('blur', onBlur)
  window.addEventListener('focus', onFocus)
  return { promise, finish }
}

export function FirstRunPermissions({ bridge, onDone }: FirstRunPermissionsProps) {
  const [selected, setSelected] = useState<Record<PermissionId, boolean>>(() => Object.fromEntries(PERMISSIONS.map(item => [item.id, true])) as Record<PermissionId, boolean>)
  const [status, setStatus] = useState<StatusMap>(emptyStatus)
  const [checking, setChecking] = useState(true)
  const [running, setRunning] = useState(false)
  const [waitingForSettings, setWaitingForSettings] = useState(false)
  const [finished, setFinished] = useState(false)
  const [current, setCurrent] = useState<PermissionId | null>(null)
  const alive = useRef(true)
  const runPending = useRef(false)
  const settingsWait = useRef<ReturnType<typeof createSettingsReturnWait> | null>(null)

  const readStatus = useCallback(async (id: PermissionId): Promise<PermissionStatus> => {
    switch (id) {
      case 'notification': {
        const result = await bridge.getKeepAliveState()
        return result.notificationPermission === 'unsupported' ? 'unavailable' : result.notificationPermission === 'granted' ? 'granted' : 'denied'
      }
      case 'media': return (await bridge.getStorageAccessState()).mediaGranted ? 'granted' : 'denied'
      case 'applications': {
        const result = await bridge.getInstalledApplicationsPermission()
        return !result.supported ? 'unavailable' : result.granted ? 'granted' : 'denied'
      }
      case 'shizuku': {
        const result = await bridge.getShizukuState()
        return result.permission === 'granted' ? 'granted' : !result.installed || !result.running ? 'unavailable' : 'denied'
      }
      case 'overlay': return (await bridge.getOverlayBallState()).canDrawOverlays ? 'granted' : 'denied'
      case 'all-files': {
        const result = await bridge.getStorageAccessState()
        return !result.allFilesSupported ? 'unavailable' : result.allFilesGranted ? 'granted' : 'denied'
      }
      case 'accessibility': return (await bridge.getAccessibilityAutomationState()).serviceStatus === 'off' ? 'denied' : 'granted'
      case 'install-source': return (await bridge.getAppUpdateInstallPermission()).granted ? 'granted' : 'denied'
    }
  }, [bridge])

  const setOne = useCallback((id: PermissionId, value: PermissionStatus) => {
    if (alive.current) setStatus(previous => ({ ...previous, [id]: value }))
  }, [])

  useEffect(() => {
    alive.current = true
    let cancelled = false
    void Promise.all(PERMISSIONS.map(async item => {
      try {
        const value = await readStatus(item.id)
        if (!cancelled) setOne(item.id, value)
      } catch {
        if (!cancelled) setOne(item.id, 'failed')
      }
    })).finally(() => { if (!cancelled) setChecking(false) })
    return () => {
      cancelled = true
      alive.current = false
      settingsWait.current?.finish(false)
      settingsWait.current = null
    }
  }, [readStatus, setOne])

  const requestOne = async (item: PermissionItem): Promise<PermissionStatus> => {
    if (item.id === 'notification') {
      const next = await bridge.requestNotificationPermission()
      return !next.supported ? 'unavailable' : next.granted ? 'granted' : 'denied'
    }
    if (item.id === 'media') return (await bridge.requestMediaPermission()).granted ? 'granted' : 'denied'
    if (item.id === 'applications') {
      const next = await bridge.requestInstalledApplicationsPermission()
      return !next.supported ? 'unavailable' : next.granted ? 'granted' : 'denied'
    }
    if (item.id === 'shizuku') return (await bridge.requestShizukuPermission()).permission === 'granted' ? 'granted' : 'denied'
    const wait = createSettingsReturnWait()
    settingsWait.current = wait
    setWaitingForSettings(true)
    try {
      if (item.id === 'overlay') await bridge.openOverlaySettings()
      if (item.id === 'all-files') await bridge.openAllFilesAccessSettings()
      if (item.id === 'accessibility') await bridge.openAccessibilitySettings()
      if (item.id === 'install-source') await bridge.openAppUpdateInstallSettings()
      await wait.promise
      return alive.current ? readStatus(item.id) : 'unknown'
    } finally {
      wait.finish(false)
      if (settingsWait.current === wait) settingsWait.current = null
      if (alive.current) setWaitingForSettings(false)
    }
  }

  const run = async () => {
    if (runPending.current || checking) return
    runPending.current = true
    setRunning(true)
    try {
      for (const item of PERMISSIONS) {
        if (!alive.current) return
        if (!selected[item.id]) continue
        setCurrent(item.id)
        try {
          const before = await readStatus(item.id)
          if (!alive.current) return
          setOne(item.id, before === 'granted' || before === 'unavailable' ? before : await requestOne(item))
        } catch {
          setOne(item.id, 'failed')
        }
      }
      if (!alive.current) return
      setCurrent(null)
      setRunning(false)
      setFinished(true)
    } finally {
      runPending.current = false
    }
  }

  const currentItem = PERMISSIONS.find(item => item.id === current)
  return (
    <div className="dialog-backdrop first-run-permissions-backdrop" role="presentation">
      <section className="dialog first-run-permissions-dialog" role="dialog" aria-modal="true" aria-labelledby="first-run-permissions-title">
        <span className="dialog-info-icon"><ShieldCheck size={23} /></span>
        <h2 id="first-run-permissions-title">{t('权限说明与授权')}</h2>
        {!running && !finished && <div className="first-run-permissions-body">
          <p>{t('请先了解每项权限的用途和风险。你可以取消勾选，也可以在系统提示中拒绝；未授权的功能会受限，其他功能仍可使用。')}</p>
          <p>{t('确认后会依次弹出授权提示。悬浮窗、所有文件访问、无障碍和安装权限需要你在系统设置中手动选择，返回后继续下一项。')}</p>
          <div className="first-run-permissions-list">
            {PERMISSIONS.map(item => <label className="first-run-permission-item" key={item.id}>
              <input type="checkbox" aria-label={t(item.title)} checked={selected[item.id]} disabled={checking || status[item.id] === 'granted' || status[item.id] === 'unavailable'} onChange={event => setSelected(previous => ({ ...previous, [item.id]: event.target.checked }))} />
              <span><strong>{t(item.title)}{status[item.id] === 'granted' ? ` · ${t('已授权')}` : status[item.id] === 'unavailable' ? ` · ${t('当前无需授权或不可用')}` : ''}</strong>
                <small>{t('用途：')}{t(item.use)}</small><small>{t('风险：')}{t(item.risk)}</small><small>{t('拒绝后：')}{t(item.denied)}</small></span>
            </label>)}
          </div>
          <div className="first-run-permissions-note"><AlertTriangle size={18} /><div>
            <p>{t('联网用于模型请求、下载与更新；查询应用用于选择目标应用；前台服务、唤醒锁和开机恢复用于保持或恢复你已开启的服务。这些基础权限由系统自动管理，通常不会单独弹窗。联网可能消耗流量，后台运行可能增加耗电。')}</p>
            <p>{t('Root 和屏幕捕获只在你主动使用相关功能时另行说明并确认，本次不会申请。授权不会自动开启悬浮球、自动化或设备命令功能；所有权限都可在系统设置中撤销。')}</p>
          </div>
          </div>
        </div>}
        {running && <div className="first-run-permissions-progress" aria-live="polite">
          <p><Loader2 className="spin" size={18} />{t('正在处理：{0}', t(currentItem?.title ?? '权限'))}</p>
          {currentItem?.special && waitingForSettings && <>
            <p>{t('请在系统设置中授权或返回。若系统页面未打开，或返回后没有继续，可点「继续下一项」重新检查结果。')}</p>
            <button className="button button-secondary" type="button" onClick={() => settingsWait.current?.finish(false)}>{t('继续下一项')}</button>
          </>}
        </div>}
        {finished && <div className="first-run-permissions-results">
          <p>{t('权限检查已完成。未授权的项目可在使用对应功能时重新申请。')}</p>
          {PERMISSIONS.map(item => <p key={item.id}><strong>{t(item.title)}：</strong>{t(status[item.id] === 'granted' ? '已授权' : status[item.id] === 'unavailable' ? '当前无需授权或不可用' : status[item.id] === 'failed' ? '检查或申请失败' : '未授权')}</p>)}
        </div>}
        <div className="dialog-actions">
          {!running && !finished && <>
            <button className="button button-secondary" type="button" onClick={onDone}>{t('暂不授权')}</button>
            <button className="button button-primary" type="button" onClick={() => void run()} disabled={checking}><ShieldCheck size={18} />{checking ? t('正在检查权限…') : t('确认并依次授权')}</button>
          </>}
          {finished && <button className="button button-primary" type="button" onClick={onDone}><CheckCircle2 size={18} />{t('完成')}</button>}
        </div>
      </section>
    </div>
  )
}
