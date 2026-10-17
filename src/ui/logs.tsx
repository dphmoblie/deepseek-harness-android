/**
 * 日志展示：文本渲染、窗口选择与两块折叠面板（运行日志 / 诊断日志）。
 *
 * 从 `App.tsx` 整体搬出：这些组件只吃 `load*` 回调与类型，不读外壳状态。
 * `LogText` 同时被自检面板复用，所以集中放在这里，避免自检面板反向 import 外壳。
 * 正文零改动。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, ChevronDown, ChevronUp, Copy, Loader2, ScrollText, X } from 'lucide-react'
import { readLogInsights } from '../logInsights'
import { triggerHaptic } from '../platform/haptics'
import { t } from '../i18n'
import {
  DIAGNOSTIC_LOG_WINDOW_OPTIONS,
  HARNESS_LOG_WINDOW_OPTIONS,
  type DiagnosticLogText,
  type HarnessLog,
} from '../platform/types'
interface HarnessLogPanelProps {
  /** 读取访客进程输出尾部；只在用户展开区块或切换窗口时调用。 */
  loadHarnessLog: (maxBytes?: number) => Promise<HarnessLog>
}

interface DiagnosticLogPanelProps {
  /** 读取诊断日志尾部窗口，供应用内查看；只在展开或切换窗口时调用。 */
  loadDiagnosticLog: (maxBytes?: number) => Promise<DiagnosticLogText>
}

/** 窗口字节数的人类可读写法：8 KB / 64 KB / 256 KB。 */
function formatLogWindow(bytes: number): string {
  return `${Math.round(bytes / 1024)} KB`
}

/**
 * 一次渲染的最大行数。
 *
 * 日志窗口最大 256 KB，整段铺进 DOM 会拖慢设置页（机型越旧越明显）。
 * 超出时只渲染**最近**的这些行：排障要看的是最后发生了什么，
 * 更早的部分仍然可以复制全文带走。
 */
const LOG_MAX_RENDERED_LINES = 2000

/** 级别判定只用于着色，不改变任何文本内容。 */
const LOG_ERROR_LINE = /(^|[^a-z])(error|fatal|exception|failed|failure|traceback)([^a-z]|$)/i
const LOG_WARN_LINE = /(^|[^a-z])(warn|warning)([^a-z]|$)/i

function logLineClass(line: string): string {
  if (LOG_ERROR_LINE.test(line)) return 'log-line log-line-error'
  if (LOG_WARN_LINE.test(line)) return 'log-line log-line-warn'
  return 'log-line'
}

interface LogTextProps {
  /** 日志正文。可能来自访客进程或原生诊断目录，一律按纯文本渲染。 */
  text: string
  /** 输出容器的 class，供样式与用例定位（例如 `harness-log-output`）。 */
  outputClassName: string
}

/**
 * 日志正文的阅读器：过滤、级别着色、判读提示与复制。
 *
 * 为什么要有过滤与判读：一段 64 KB 的日志靠肉眼翻，用户得到的往往只是「看起来有很多错」。
 * 过滤让用户能盯着一个关键字，判读提示（[readLogInsights]）把已确诊的签名翻译成结论与下一步。
 *
 * 隐私边界：这里只负责显示调用方已经取到的文本。访客输出可能含会话内容，
 * 因此**不落盘、不写诊断日志、也不随诊断日志导出**；渲染一律走文本节点，
 * 绝不用 dangerouslySetInnerHTML —— 内容不是受控文案。
 */
export function LogText({ text, outputClassName }: LogTextProps) {
  const [filter, setFilter] = useState('')
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)
  const copyResetTimer = useRef(0)

  useEffect(() => () => window.clearTimeout(copyResetTimer.current), [])

  const lines = useMemo(() => text.split('\n'), [text])
  const insights = useMemo(() => readLogInsights(text), [text])
  const query = filter.trim().toLowerCase()
  const matched = useMemo(
    () => (query === '' ? lines : lines.filter(line => line.toLowerCase().includes(query))),
    [lines, query],
  )
  const omitted = Math.max(0, matched.length - LOG_MAX_RENDERED_LINES)
  const visible = omitted === 0 ? matched : matched.slice(omitted)

  // 复用终端面板同一套剪贴板实现：不额外引入依赖。
  const copyLog = useCallback(() => {
    if (text === '') return
    void (async () => {
      try {
        await navigator.clipboard.writeText(text)
        triggerHaptic('success')
        setCopied(true)
        setCopyFailed(false)
        window.clearTimeout(copyResetTimer.current)
        copyResetTimer.current = window.setTimeout(() => setCopied(false), 1500)
      } catch {
        triggerHaptic('error')
        setCopyFailed(true)
      }
    })()
  }, [text])

  return (
    <>
      {insights.length > 0 && (
        <div className="log-insights" role="group" aria-label={t("判读提示")}>
          {insights.map(insight => (
            <div className="log-insight" key={insight.id}>
              <strong>{t(insight.title)}</strong>
              <span>{t(insight.meaning)}</span>
              <span className="log-insight-next">{t("下一步：")}{t(insight.nextStep)}</span>
            </div>
          ))}
        </div>
      )}

      <div className="search-control log-filter" role="search" aria-label={t("过滤日志")}>
        <span className="sr-only">{t("过滤日志")}</span>
        <input
          type="search"
          autoComplete="off"
          spellCheck={false}
          placeholder={t("输入关键字，例如 error、plugin、credential")}
          aria-label={t("过滤日志")}
          value={filter}
          onChange={event => setFilter(event.target.value)}
        />
        {filter !== '' && (
          <button className="search-control-clear" type="button" onClick={() => setFilter('')} aria-label={t("清空查找词")}>
            <X size={16} />
          </button>
        )}
      </div>

      <p className="harness-log-state">
        {query === ''
          ? t("共 {0} 行", lines.length)
          : t("匹配 {0} / {1} 行", matched.length, lines.length)}
        {omitted > 0 ? t("，已省略更早的 {0} 行", omitted) : ''}
      </p>

      {matched.length === 0 && <p className="harness-log-state">{t("没有匹配的行")}</p>}

      {matched.length > 0 && (
        <>
          <pre className={`log-output ${outputClassName}`}>
            {visible.map((line, index) => (
              <span className={logLineClass(line)} key={index}>
                {line}
                {index < visible.length - 1 ? '\n' : ''}
              </span>
            ))}
          </pre>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={copyLog}>
              {copied ? <CheckCircle2 size={18} /> : <Copy size={18} />}
              {copied ? t("已复制") : t("复制")}
            </button>
          </div>
        </>
      )}

      {copyFailed && (
        <p className="harness-log-state" role="alert">{t("复制失败，请长按选择文本后复制")}</p>
      )}
    </>
  )
}

/** 窗口选择器：三档（运行日志）或两档（诊断日志），由调用方给出可选值。 */
export function LogWindowPicker({ options, value, onChange }: {
  options: readonly number[]
  value: number
  onChange: (bytes: number) => void
}) {
  if (options.length < 2) return null
  return (
    <label className="field">
      <span>{t("读取窗口")}</span>
      <select value={value} onChange={event => onChange(Number(event.target.value))}>
        {options.map(bytes => <option key={bytes} value={bytes}>{formatLogWindow(bytes)}</option>)}
      </select>
    </label>
  )
}

/**
 * 「运行日志（最近 8 KB / 64 KB / 256 KB）」折叠区块。
 *
 * 为什么需要它：工具调用失败时界面只显示一句不带栈信息的报错，唯一线索是访客进程
 * 自己打在 stdout/stderr 上的完整输出（异常栈、插件加载报错等）。8 KB 常常只够一段栈，
 * 因此窗口可选：缓冲区按最大档分配，切换窗口只是重新截取尾部。
 *
 * 隐私边界：这段文本来自访客进程，**可能包含会话内容**（工具参数、代码片段等），
 * 因此只在本机界面展示 —— 不写入诊断日志、不新增诊断事件，也不随诊断日志导出。
 * 也正因如此，读取是**按需**的：折叠状态下不碰桥接，展开时才去取一次最新尾部。
 */
export function HarnessLogPanel({ loadHarnessLog }: HarnessLogPanelProps) {
  const [open, setOpen] = useState(false)
  const [windowBytes, setWindowBytes] = useState<number>(HARNESS_LOG_WINDOW_OPTIONS[0])
  const [log, setLog] = useState<HarnessLog | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    setFailed(false)
    void loadHarnessLog(windowBytes)
      .then(next => { if (!cancelled) setLog(next) })
      .catch(() => {
        // 读取失败时不保留上一次的内容：界面要么显示本次真实结果，要么明确说读不到。
        if (!cancelled) { setLog(null); setFailed(true) }
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    // 折叠回来时丢弃在途结果，避免收起后又被异步写回内容。
    return () => { cancelled = true }
  }, [loadHarnessLog, open, windowBytes])

  const hasText = log !== null && log.available && log.text !== ''

  return (
    <section className="settings-section harness-log-section" aria-labelledby="harness-log-title">
      <button
        className="harness-log-toggle"
        type="button"
        aria-expanded={open}
        aria-controls="harness-log-body"
        onClick={() => setOpen(current => !current)}
      >
        <span className="section-icon"><ScrollText size={19} /></span>
        <span className="harness-log-heading">
          <strong id="harness-log-title">{t("运行日志（最近 {0}）", formatLogWindow(windowBytes))}</strong>
          <small>{t("Harness 进程输出尾部，用于排查工具调用失败")}</small>
        </span>
        {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
      </button>

      {/*
        折叠时只保留一个空的隐藏容器：aria-controls 指向的元素始终存在，
        内容与读取都只在展开后发生。
      */}
      <div className="harness-log-body" id="harness-log-body" hidden={!open}>
        {open && (
        <>
          <p className="settings-note">
            {t("这段内容来自 Harness 进程输出，可能包含会话内容，仅供排障；它只在设备界面里显示，不会写入诊断日志，也不随诊断日志导出。")}
          </p>

          <LogWindowPicker options={HARNESS_LOG_WINDOW_OPTIONS} value={windowBytes} onChange={setWindowBytes} />

          {loading && (
            <p className="harness-log-state"><Loader2 className="spin" size={16} />{t("正在读取运行日志…")}</p>
          )}
          {!loading && failed && (
            <p className="harness-log-state" role="alert">{t("读取运行日志失败，请稍后重试")}</p>
          )}
          {!loading && !failed && log !== null && !log.available && (
            <p className="harness-log-state">{t("当前没有可读取的运行日志")}</p>
          )}
          {!loading && !failed && log !== null && log.available && log.text === '' && (
            <p className="harness-log-state">{t("Harness 进程最近没有输出")}</p>
          )}

          {!loading && !failed && hasText && (
            <LogText text={log.text} outputClassName="harness-log-output" />
          )}
        </>
        )}
      </div>
    </section>
  )
}

/**
 * 「诊断日志（最近 64 KB / 256 KB）」折叠区块。
 *
 * 之前这里只能看计数和导出：出了问题要么把文件分享出去，要么凭计数猜。现在可以直接
 * 在应用内查看正文（受控字段：时间、级别、事件、状态码、计数），并配合判读提示判断。
 *
 * 与导出的区别：查看只读**尾部窗口**、不落盘、不产生文件；导出仍然是全部文件，
 * 用于交给别人排查。正文不含 URL、凭据、终端内容或用户数据，因此读进界面不构成新泄露面。
 */
export function DiagnosticLogPanel({ loadDiagnosticLog }: DiagnosticLogPanelProps) {
  const [open, setOpen] = useState(false)
  const [windowBytes, setWindowBytes] = useState<number>(DIAGNOSTIC_LOG_WINDOW_OPTIONS[0])
  const [log, setLog] = useState<DiagnosticLogText | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    setFailed(false)
    void loadDiagnosticLog(windowBytes)
      .then(next => { if (!cancelled) setLog(next) })
      .catch(() => {
        if (!cancelled) { setLog(null); setFailed(true) }
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [loadDiagnosticLog, open, windowBytes])

  const hasText = log !== null && log.text !== ''

  return (
    <section className="settings-section harness-log-section" aria-labelledby="diagnostic-log-title">
      <button
        className="harness-log-toggle"
        type="button"
        aria-expanded={open}
        aria-controls="diagnostic-log-body"
        onClick={() => setOpen(current => !current)}
      >
        <span className="section-icon"><ScrollText size={19} /></span>
        <span className="harness-log-heading">
          <strong id="diagnostic-log-title">{t("诊断日志（最近 {0}）", formatLogWindow(windowBytes))}</strong>
          <small>{t("应用内部状态码与计数，含启动失败原因码")}</small>
        </span>
        {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
      </button>

      <div className="harness-log-body" id="diagnostic-log-body" hidden={!open}>
        {open && (
        <>
          <p className="settings-note">
            {t("只包含应用内部的事件名、级别、状态码与计数，不含 URL、凭据、终端内容或用户数据；这里显示的是最近一段，导出会给出全部文件。")}
          </p>

          <LogWindowPicker options={DIAGNOSTIC_LOG_WINDOW_OPTIONS} value={windowBytes} onChange={setWindowBytes} />

          {loading && (
            <p className="harness-log-state"><Loader2 className="spin" size={16} />{t("正在读取诊断日志…")}</p>
          )}
          {!loading && failed && (
            <p className="harness-log-state" role="alert">{t("读取诊断日志失败，请稍后重试")}</p>
          )}
          {!loading && !failed && log !== null && !hasText && (
            <p className="harness-log-state">{t("当前没有可查看的诊断日志；开启采集后重新操作一次即可产生记录。")}</p>
          )}
          {!loading && !failed && hasText && log !== null && (
            <>
              {log.truncated && (
                <p className="harness-log-state">{t("已按窗口截断：这里是最近一段，导出可获取全部内容。")}</p>
              )}
              <LogText text={log.text} outputClassName="diagnostic-log-output" />
            </>
          )}
        </>
        )}
      </div>
    </section>
  )
}
