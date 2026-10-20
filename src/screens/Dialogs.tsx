/**
 * 三类对话框：重置运行环境的二次确认、系统权限指引、运行环境更新提示。
 *
 * 从 `App.tsx` 整体搬出：三者都是纯展示 + 回调，不读外壳状态。正文零改动。
 */
import { useEffect, useState } from 'react'
import { AlertTriangle, ExternalLink, Loader2, RefreshCw, RotateCcw, Trash2, X } from 'lucide-react'
import { t } from '../i18n'
import type { PermissionGuide } from '../ui/permissionGuide'

/** 点下重置后必须停留的秒数：给用户留出反悔窗口，避免误触直接清空。 */
const RESET_CONSIDERATION_SECONDS = 3

export interface ResetDialogProps {
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}

export function ResetDialog({ busy, onCancel, onConfirm }: ResetDialogProps) {
  const [remaining, setRemaining] = useState(RESET_CONSIDERATION_SECONDS)
  /**
   * 只在还有剩余时间时排下一个 tick：归零后不再有定时器在后台空转。
   * 卸载时清掉定时器，避免对话框关掉后仍然 setState。
   */
  useEffect(() => {
    if (remaining <= 0) return
    const timer = window.setTimeout(() => setRemaining(value => value - 1), 1_000)
    return () => window.clearTimeout(timer)
  }, [remaining])
  const ready = remaining <= 0

  return (
    <div className="dialog-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="reset-title">
        <button className="dialog-close" type="button" aria-label={t("关闭")} onClick={onCancel} disabled={busy}><X size={19} /></button>
        <span className="dialog-danger-icon"><Trash2 size={23} /></span>
        <h2 id="reset-title">{t("重置运行环境")}</h2>
        <p>{t("已安装的 Ubuntu 运行环境会被清除，其中的用户数据（会话、密钥、插件等）一并删除，终端与 Harness 会话将立即结束。")}</p>
        <p className="dialog-countdown" aria-live="polite">
          {ready ? t("确认后立即开始重置。") : t("请先看清上面的影响：{0} 秒后可以确认。", remaining)}
        </p>
        <div className="dialog-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>{t("取消")}</button>
          <button className="button button-danger" type="button" onClick={onConfirm} disabled={busy || !ready}>
            {busy ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
            {busy ? t("正在重置") : ready ? t("确认重置") : t("请稍候 {0} 秒", remaining)}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 悬浮球 / 后台保持「开了但没起来」的五类可操作原因。
 *
 * - overlay-permission：悬浮窗权限被关，只能去系统页手动开；
 * - notification-permission：Android 13+ 通知权限未授予。权限只决定常驻通知是否可见，
 *   前台服务照常启动（`ForegroundServices.kt` 的启动顺序就是按这个事实写的），
 *   因此文案只建议开启以便观察，不声称「没有权限就起不来」；
 * - background-blocked：被厂商 ROM 的自启动 / 后台运行 / 省电策略拦截。四类限制（自启动、
 *   后台运行、电池优化、任务清理）人人都有，只是菜名不同：HyperOS / ColorOS / OriginOS
 *   在步骤里各自点名，荣耀 / 华为 / 三星合成一条并列可勾选项，末尾给「用设置搜索」的兜底；
 * - overlay-internal：悬浮球界面创建失败，没有系统页可去，只能重启应用 / 反馈日志；
 * - shizuku-permission：设备 Shell 走 Shizuku，但尚未在 Shizuku 应用里授权。
 * 这层映射只消费原生侧下发的受控令牌，不接收任何自由文本。
 */

export interface PermissionGuideDialogProps {
  guide: PermissionGuide
  busy: boolean
  onClose: () => void
  onOpenOverlaySettings: () => void
  onOpenNotificationSettings: () => void
  onOpenApplicationSettings: () => void
  onOpenShizuku: () => void
}

export function PermissionGuideDialog({
  guide,
  busy,
  onClose,
  onOpenOverlaySettings,
  onOpenNotificationSettings,
  onOpenApplicationSettings,
  onOpenShizuku,
}: PermissionGuideDialogProps) {
  const title = guide.kind === 'overlay-permission'
    ? t("悬浮球需要悬浮窗权限")
    : guide.kind === 'notification-permission'
      ? t("后台保持的通知权限未开启")
      : guide.kind === 'shizuku-permission'
        ? t("Shizuku 尚未授权")
        : guide.kind === 'background-blocked'
          ? t("前台服务被系统拦截")
          : t("悬浮球启动失败")
  return (
    <div className="dialog-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onClose() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="permission-guide-title">
        <button className="dialog-close" type="button" aria-label={t("关闭")} onClick={onClose} disabled={busy}><X size={19} /></button>
        <span className="dialog-warning-icon"><AlertTriangle size={23} /></span>
        <h2 id="permission-guide-title">{title}</h2>
        {guide.kind === 'overlay-permission' && (
          <p>{t("悬浮球要获得「显示在其他应用上层」权限，才能浮现在其他应用之上。请在系统设置中开启该权限，返回应用后重新打开悬浮球开关。")}</p>
        )}
        {guide.kind === 'notification-permission' && (
          <p>{t("Android 13 及以上，前台服务的常驻通知需要通知权限。缺少它时服务仍能启动，只是不显示这条通知，系统任务管理器里仍能看到本应用在运行；是否生效要看「前台服务」状态行。建议允许通知，方便确认后台保持在跑。")}</p>
        )}
        {guide.kind === 'shizuku-permission' && (
          <p>{t("需要先授权 Shizuku，AI 才能通过它执行设备操作。请点击下方按钮打开 Shizuku 应用完成授权，返回后重新点击授权。")}</p>
        )}
        {guide.kind === 'background-blocked' && (
          <>
            <p>
              {guide.feature === 'keepAlive'
                ? t("系统没有允许「后台保持」的前台服务启动。常见原因是厂商 ROM 的自启动、后台运行或省电策略拦截；小米、OPPO、vivo、荣耀、华为、三星都有这类开关，只是叫法不同。请按下面几项逐项检查：")
                : t("系统没有允许悬浮球的前台服务启动。常见原因是厂商 ROM 的自启动、后台运行或省电策略拦截；小米、OPPO、vivo、荣耀、华为、三星都有这类开关，只是叫法不同。请按下面几项逐项检查：")}
            </p>
            <ul className="dialog-guide-list">
              <li>{t("允许自启动：HyperOS 在「应用设置 → 授权管理 → 自启动管理」，ColorOS 在「应用 → 自启动」，OriginOS 在「i管家 → 权限管理 → 自启动」")}</li>
              <li>{t("放开后台运行：HyperOS 在「应用信息 → 省电策略」选「无限制」，ColorOS 在「应用信息 → 耗电管理」打开「允许后台运行」，OriginOS 在「电池 → 后台耗电管理」允许「后台高耗电」")}</li>
              <li>{t("在系统「电池」里把本应用排除在电池优化之外：ColorOS 叫「耗电异常优化」，多数 ROM 叫「电池优化」，选「不优化」或「无限制」")}</li>
              <li>{t("在最近任务列表里给本应用加锁：下拉卡片，或点卡片右上角菜单里的「锁定」，避免被一键清理")}</li>
              <li>{t("荣耀和华为在「应用启动管理」里关掉自动管理，再勾上「允许自启动」和后台那一项：荣耀叫「允许后台活动」，华为叫「允许后台启动」；三星在「电池 → 后台使用限制 → 从不休眠的应用」里加入本应用，并在应用信息 →「电池」选「无限制」")}</li>
            </ul>
            <p>{t("菜单名会随系统版本变化，在设置顶部搜「自启动」或「耗电管理」通常比逐层找更快。")}</p>
          </>
        )}
        {guide.kind === 'overlay-internal' && (
          <p>{t("悬浮球界面在当前系统上创建失败。请尝试重启应用后再开启；如果反复出现，请在「诊断与日志」中导出记录后反馈。")}</p>
        )}
        <div className="dialog-actions">
          {guide.kind === 'overlay-internal' ? (
            <button className="button button-primary" type="button" onClick={onClose} disabled={busy}>{t("我知道了")}</button>
          ) : (
            <>
              <button className="button button-secondary" type="button" onClick={onClose} disabled={busy}>{t("取消")}</button>
              {guide.kind === 'overlay-permission' && (
                <button className="button button-primary" type="button" onClick={onOpenOverlaySettings} disabled={busy}>
                  <ExternalLink size={18} />{t("前往开启悬浮窗权限")}
                </button>
              )}
              {guide.kind === 'notification-permission' && (
                <button className="button button-primary" type="button" onClick={onOpenNotificationSettings} disabled={busy}>
                  <ExternalLink size={18} />{t("前往开启通知")}
                </button>
              )}
              {guide.kind === 'background-blocked' && (
                <button className="button button-primary" type="button" onClick={onOpenApplicationSettings} disabled={busy}>
                  <ExternalLink size={18} />{t("打开应用详情页")}
                </button>
              )}
              {guide.kind === 'shizuku-permission' && (
                <button className="button button-primary" type="button" onClick={onOpenShizuku} disabled={busy}>
                  <ExternalLink size={18} />{t("前往授权 Shizuku")}
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export interface UpdateDialogProps {
  busy: boolean
  /**
   * 这次要装的运行时版本号；`null` 表示用的是 APK 内置镜像（界面上的「更新运行环境」）。
   * 只在弹窗里做一句说明，不参与任何判断。
   */
  version: string | null
  onCancel: () => void
  onConfirm: () => void
}

export function UpdateDialog({ busy, version, onCancel, onConfirm }: UpdateDialogProps) {
  return (
    <div className="dialog-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="update-title">
        <button className="dialog-close" type="button" aria-label={t("关闭")} onClick={onCancel} disabled={busy}><X size={19} /></button>
        <span className="dialog-danger-icon"><RefreshCw size={23} /></span>
        <h2 id="update-title">{t("更新 Ubuntu 运行环境")}</h2>
        <p>
          {version === null
            ? t("当前 APK 内置了新版运行环境。继续后会替换 Ubuntu 运行时的系统目录：用 apt 等装进系统的软件与其它本地修改会丢失；会话、模型密钥、Harness 设置、附件、技能、默认工作区，以及插件（包括终端安装的第三方插件）会自动备份并尝试恢复，应用设置也不受影响。若恢复发生冲突，请到插件管理检查。")
            : t("即将安装运行时 {0}。继续后会替换 Ubuntu 运行时的系统目录：用 apt 等装进系统的软件与其它本地修改会丢失；会话、模型密钥、Harness 设置、附件、技能、默认工作区，以及插件（包括终端安装的第三方插件）会自动备份并尝试恢复，应用设置也不受影响。若恢复发生冲突，请到插件管理检查。", version)}
        </p>
        <p>{t("开始前会先把当前会话自动备份一份（在「版本管理 → 会话备份」里能看到）。新镜像里的 dsh 版本可能与现在不同，读不出旧会话时可以用那份备份恢复。")}</p>
        <div className="dialog-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>{t("暂不更新")}</button>
          <button className="button button-danger" type="button" onClick={onConfirm} disabled={busy}>
            {busy ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}
            {busy ? t("正在更新") : t("确认更新")}
          </button>
        </div>
      </div>
    </div>
  )
}