/**
 * 运行时自检面板：检查项列表、结论与修复动作。
 *
 * 从 `App.tsx` 整体搬出：它只吃 `runSelfCheck` 与运行时状态，结论文案全部来自
 * `selfCheckAdvice` 的受控映射。正文零改动。
 */
import { useState } from 'react'
import { AlertTriangle, ChevronDown, ChevronUp, Loader2, ShieldCheck, Wrench } from 'lucide-react'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
import { formatRecordedAt } from '../ui/formatTime'
import {
  selfCheckAdvice,
  selfCheckNeedsRepair,
  type SelfCheckCheckReport,
  type SelfCheckItem,
  type SelfCheckOperation,
  type SelfCheckRepairReport,
  type SelfCheckReport,
  type SelfCheckStatus,
} from '../runtimeSelfCheck'
import type { RuntimeState } from '../platform/types'

/** 自检状态徽章：配色与标签都由这里决定，`skipped` 用中性样式，不冒充「通过」。 */
const SELF_CHECK_STATUS_META: Record<SelfCheckStatus, { label: string; chip: string }> = {
  ok: { label: '正常', chip: 'success' },
  warn: { label: '注意', chip: 'warn' },
  fail: { label: '失败', chip: 'danger' },
  skipped: { label: '跳过', chip: '' },
}

/** 自检给出的可用空间低于这一档时提示清理：安装、解压与会话保存都可能因空间失败。 */
const SELF_CHECK_LOW_SPACE_BYTES = 512 * 1024 * 1024

/** 只有探测失败才能确认 Landlock 不可用；单独的 exec/PTY 失败不能推出这一结论。 */
function selfCheckSandboxBlocked(checks: readonly SelfCheckItem[]): boolean {
  return checks.some(item => item.id === 'sandbox_probe' && item.status === 'fail' && item.code === 'PROBE_UNUSABLE')
}

/** 版本槽的显示元数据：标签与徽章配色都在这里定，不把原始槽位名丢给用户看。 */

export interface RuntimeSelfCheckPanelProps {
  /** 当前运行时状态：只取已安装版本，用于「运行时版本」一行。 */
  runtime: RuntimeState
  /** 运行自检；只在用户点击按钮时调用，进入页面不自动跑。 */
  runSelfCheck: (operation: SelfCheckOperation) => Promise<SelfCheckReport>
}

/**
 * 单条自检结果：检查项标签 + 状态徽章 + 结论与下一步。
 *
 * 文案全部来自 [selfCheckAdvice] 的受控映射（检查项 id 与结论码都是枚举），
 * 载荷里没有、也不会渲染任何自由文本，因此这里不存在把访客内容带进界面的路径。
 */
function SelfCheckRow({ item }: { item: SelfCheckItem }) {
  const advice = selfCheckAdvice(item)
  const meta = SELF_CHECK_STATUS_META[item.status]
  return (
    <div className={`self-check-row ${item.status}`}>
      <div className="self-check-row-head">
        <span className="self-check-label">{t(advice.label)}</span>
        <span className={meta.chip === '' ? 'status-chip' : `status-chip ${meta.chip}`}>{t(meta.label)}</span>
      </div>
      {advice.meaning !== '' && <p className="self-check-meaning">{t(advice.meaning)}</p>}
      {advice.nextStep !== '' && <p className="self-check-next">{t("下一步：")}{t(advice.nextStep)}</p>}
    </div>
  )
}

/**
 * 「运行时自检」区块。
 *
 * 为什么需要它：运行时链路断在哪一环，通常要靠 bash 才能查——可 bash 本身可能正是断掉
 * 的那一环。自检由原生侧逐环探测，这里把结果翻译成「哪一环断了 + 下一步」，
 * 并让用户一眼看到非 ok 的那些项。
 *
 * 与日志面板同样的按需原则：进页面**不自动自检**，只有用户点「运行自检」才调用；
 * 「修复运行时权限」也只在结果里出现权限位或缺失目录相关的结论码时才提供，
 * 避免给出一个修不了当前问题的按钮。自检载荷只含枚举、字节数与版本号，
 * 不含路径、命令输出或凭据，所以它会显示在界面上，但不落盘、不进诊断日志。
 */
export function RuntimeSelfCheckPanel({ runSelfCheck, runtime }: RuntimeSelfCheckPanelProps) {
  const [report, setReport] = useState<SelfCheckCheckReport | null>(null)
  const [repair, setRepair] = useState<SelfCheckRepairReport | null>(null)
  const [checkedAt, setCheckedAt] = useState(0)
  const [busy, setBusy] = useState<SelfCheckOperation | null>(null)
  const [failed, setFailed] = useState<SelfCheckOperation | null>(null)
  const [showOk, setShowOk] = useState(false)

  const start = (operation: SelfCheckOperation): void => {
    if (busy !== null) return
    setBusy(operation)
    setFailed(null)
    setRepair(null)
    let activeOperation = operation
    void (async () => {
      if (operation === 'repair') {
        const repaired = await runSelfCheck('repair')
        if (repaired.operation !== 'repair') throw new Error('自检操作类型无效')
        setRepair(repaired)
        // 修复只确认动作完成；自动复检后才展示当前能力，期间清除旧结果。
        activeOperation = 'check'
        setBusy('check')
        setReport(null)
      }
      const next = await runSelfCheck('check')
      if (next.operation !== 'check') throw new Error('自检操作类型无效')
      setReport(next)
      setCheckedAt(Date.now())
      setShowOk(false)
    })()
      .catch(() => {
        if (activeOperation === 'check') {
          setReport(null)
          setCheckedAt(0)
        }
        setFailed(activeOperation)
      })
      .finally(() => setBusy(null))
  }

  const checks = report?.checks ?? []
  const failing = checks.filter(item => item.status !== 'ok')
  const passing = checks.filter(item => item.status === 'ok')
  // 探测失败时补充能力边界；保留 exec 和两组 PTY 的独立结果。
  const sandboxBlocked = report !== null && selfCheckSandboxBlocked(checks)
  // 修复只改权限位与缺失目录，可用空间仍可能变化，因此以最新一次结果为显示值。
  const availableBytes = report?.availableBytes ?? repair?.availableBytes
  const lowSpace = availableBytes !== undefined && availableBytes < SELF_CHECK_LOW_SPACE_BYTES
  const checkedAtLabel = formatRecordedAt(checkedAt)

  return (
    <section className="settings-section" aria-labelledby="runtime-self-check-title">
      <div className="section-title">
        <span className="section-icon"><ShieldCheck size={19} /></span>
        <div>
          <h2 id="runtime-self-check-title">{t("运行时自检")}</h2>
          <p>{t("不需要 bash 也能判断运行时哪一环断了：逐项检查 Shell、Node、沙箱启动器、内核 Landlock、PTY、访客数据目录、附件目录、硬链接、ripgrep 与 C 编译环境（cc/gcc/clang、make、python3），再由宿主补上插件链接、无障碍服务、副屏所需的悬浮窗权限与系统 WebView 四项设备事实，并给出结论与下一步。")}</p>
        </div>
      </div>

      {/* 版本一行不依赖自检：没跑自检时也能看到已安装的运行时版本。 */}
      <div className="settings-status-list">
        <div className="settings-status-row">
          <span>{t("运行时版本")}</span>
          <strong>{runtime.installedVersion ?? t("未安装")}</strong>
        </div>
        <div className="settings-status-row">
          <span>{t("dsh 版本")}</span>
          {/* dsh 版本只有真正进过访客才读得到：自检结果之外不猜、也不为它单独发起重量级调用。 */}
          <strong>{report?.dshVersion ?? t("运行自检后显示")}</strong>
        </div>
        <div className="settings-status-row">
          <span>{t("系统 WebView 版本")}</span>
          {/* 宿主在自检时顺带读到的版本号（纯信息）；读不到或旧版本桥接时显示占位，不影响检查项。 */}
          <strong>{report?.webViewVersion ?? t("运行自检后显示")}</strong>
        </div>
      </div>

      <p className="settings-note">{t("已安装插件列表见「插件管理」")}</p>
      <p className="settings-note">{t("自检会单独测试 Landlock 能力，不随会话权限切换。无沙箱会话可继续运行时，沙箱检查仍可能失败。")}</p>

      <div className="settings-inline-actions self-check-actions">
        <button className="button button-secondary" type="button" onClick={() => start('check')} disabled={busy !== null}>
          {busy === 'check' ? <Loader2 className="spin" size={18} /> : <ShieldCheck size={18} />}{t("运行自检")}
        </button>
        {selfCheckNeedsRepair(checks) && (
          <button className="button button-secondary" type="button" onClick={() => start('repair')} disabled={busy !== null}>
            {busy === 'repair' ? <Loader2 className="spin" size={18} /> : <Wrench size={18} />}{t("修复运行时权限")}
          </button>
        )}
      </div>

      <p className="harness-log-state">
        {busy === 'check'
          ? t("正在运行自检…")
          : busy === 'repair'
            ? t("正在修复运行时权限…")
            : checkedAtLabel === ''
              ? t("尚未自检")
              : t("上次自检：{0}", checkedAtLabel)}
      </p>

      {failed !== null && (
        <p className="harness-log-state" role="alert">
          {failed === 'check' ? t("自检未完成，请稍后重试") : t("修复未完成，请稍后重试")}
        </p>
      )}

      {availableBytes !== undefined && (
        <div className="settings-status-list">
          <div className="settings-status-row">
            <span>{t("可用空间")}</span>
            <strong>{formatBytes(availableBytes)}</strong>
          </div>
        </div>
      )}

      {lowSpace && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("可用空间不足")}</strong>
            <span>{t("设备可用空间低于 512 MB：安装、解压与会话保存都可能失败，请先清理空间。")}</span>
          </div>
        </div>
      )}

      {repair !== null && (
        <>
          <p className="harness-log-state">{t("已修复 {0} 项（检查 {1} 项）", repair.repaired, repair.candidates)}</p>
          <p className="harness-log-state">{t("权限修复只补执行位和附件目录；修复后自动复检，不能安装内核沙箱能力。")}</p>
        </>
      )}

      {/* 跨项汇总放在逐项列表之前：先讲清「谁导致谁」，再看每一条的细节。 */}
      {sandboxBlocked && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("本机 Landlock 沙箱不可用")}</strong>
            <span>{t("修复执行权限后仍出现此结果，表示 Landlock 在当前内核或启动环境中不可用。若没有其他可用后端，要求沙箱的会话无法执行命令；反复修复权限或重装运行时不能补齐内核能力。")}</span>
            <span>{t("下一步：")}{t("在当前 Harness 会话的权限预设中选择 danger-full-access，或输入 /permission danger-full-access，然后重试命令。")}</span>
            <span>{t("danger-full-access 会关闭 dsh 文件系统沙箱和命令审批。Android 应用沙箱仍在；PRoot 不提供额外的内核隔离。")}</span>
          </div>
        </div>
      )}

      {report !== null && (
        <>
          {checks.length === 0 ? (
            <p className="harness-log-state">{t("自检没有返回任何检查项")}</p>
          ) : failing.length === 0 ? (
            <p className="harness-log-state">{t("全部 {0} 项检查通过", checks.length)}</p>
          ) : (
            <div className="self-check-list" role="group" aria-label={t("自检结果")}>
              {failing.map(item => <SelfCheckRow item={item} key={item.id} />)}
            </div>
          )}

          {/* 正常项默认收起：没有非 ok 项时它们本来也不构成信息，需要时再展开核对。 */}
          {passing.length > 0 && (
            <>
              <button
                className="self-check-toggle"
                type="button"
                aria-expanded={showOk}
                aria-controls="runtime-self-check-ok"
                onClick={() => setShowOk(current => !current)}
              >
                <span>{showOk ? t("收起正常项（{0}）", passing.length) : t("正常项（{0}）", passing.length)}</span>
                {showOk ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
              </button>
              <div id="runtime-self-check-ok" hidden={!showOk}>
                {showOk && (
                  <div className="self-check-list">
                    {passing.map(item => <SelfCheckRow item={item} key={item.id} />)}
                  </div>
                )}
              </div>
            </>
          )}
        </>
      )}
    </section>
  )
}