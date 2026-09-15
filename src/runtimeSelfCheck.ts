/**
 * 运行时自检：把原生侧逐项检查的结果，翻译成「哪一环断了 + 下一步」。
 *
 * 为什么需要它：运行时链路（Shell → Node → 沙箱启动器 → 内核 Landlock → 沙箱内执行 →
 * PTY → 访客数据目录 → 附件目录 → 硬链接 → ripgrep）任何一环断掉，用户看到的往往只有一句
 * 「bash 工具不可用」，而排查通常又要靠 bash —— 可 bash 本身可能正是断掉的那一环。
 * 自检由原生侧逐环探测，不依赖 bash；本模块只负责三件事：
 *
 *  - 常量集合：检查项顺序、状态与结论码。载荷里的 id/code 必须命中这些集合，
 *    未知取值一律拒绝整个载荷（界面只渲染这些受控映射，不渲染载荷里的自由文本）；
 *  - 严格校验：[validateSelfCheckReport] 按冻结契约校验形态，包括
 *    「ok 不带 code、非 ok 必须带 code」以及重复项、越界计数这类自相矛盾的组合；
 *  - 文案映射：[selfCheckAdvice] 给出每一项的结论与下一步，界面再包一层 `t()`。
 *
 * 措辞边界：沙箱与内核相关的结论只复述已确认的现象，不把可能的来源写成单一断言
 * （例如「沙箱探测判定为不可用」同时列出「内核不支持」与「启动器无法完成探测」两种可能），
 * 也不写「一定能修好」这类绝对承诺。
 *
 * 本模块是纯函数、不依赖 React 与桥接，便于单测。
 */

/** 检查项顺序：与原生侧固定一致，界面按这个顺序展示。 */
export const SELF_CHECK_IDS = [
  'shell',
  'node',
  'sandbox_launcher',
  'sandbox_probe',
  'sandbox_exec',
  'pty',
  'pty_sandbox',
  'dsh_home',
  'attachments',
  'hardlink',
  'rg',
] as const

export type SelfCheckId = typeof SELF_CHECK_IDS[number]

export const SELF_CHECK_STATUSES = ['ok', 'warn', 'fail', 'skipped'] as const

export type SelfCheckStatus = typeof SELF_CHECK_STATUSES[number]

/**
 * 结论码全集。
 *
 * 与检查项 id 一样，这是校验用的白名单：不在表里的码说明原生侧与前端版本不一致，
 * 界面无法给出可信的结论，因此拒绝整个载荷，而不是把它当成未知文本显示出来。
 */
export const SELF_CHECK_CODES = [
  'SHELL_MISSING',
  'NODE_MISSING',
  'LAUNCHER_MISSING',
  'LAUNCHER_NOT_EXECUTABLE',
  'PROBE_UNUSABLE',
  'PROBE_PARTIAL',
  'EXEC_LAUNCHER_FAILED',
  'EXEC_COMMAND_FAILED',
  'PTY_MODULE_MISSING',
  'PTY_LOAD_FAILED',
  'PTY_EXIT_EARLY',
  'PTY_TIMEOUT',
  'HOME_MISSING',
  'HOME_NOT_WRITABLE',
  'ATTACHMENTS_MISSING',
  'ATTACHMENTS_NOT_WRITABLE',
  'HARDLINK_DENIED',
  'RG_MISSING',
  'RG_NOT_EXECUTABLE',
] as const

export type SelfCheckCode = typeof SELF_CHECK_CODES[number]

/** 自检操作：`check` 只检查，`repair` 修复权限位与缺失目录。 */
export const SELF_CHECK_OPERATIONS = ['check', 'repair'] as const

export type SelfCheckOperation = typeof SELF_CHECK_OPERATIONS[number]

export interface SelfCheckItem {
  id: SelfCheckId
  status: SelfCheckStatus
  /** `ok` 不带结论码；其余状态必须给出一个受控码。 */
  code?: SelfCheckCode
}

export interface SelfCheckCheckReport {
  operation: 'check'
  /** 运行时所在设备的可用空间（字节）；界面用 [formatBytes] 显示。 */
  availableBytes: number
  /** 访客里 dsh 的版本号；读不到时为 null。 */
  dshVersion: string | null
  checks: SelfCheckItem[]
}

export interface SelfCheckRepairReport {
  operation: 'repair'
  availableBytes: number
  /** 实际修复的项数。 */
  repaired: number
  /** 判定为可修复、参与尝试的项数。 */
  candidates: number
}

export type SelfCheckReport = SelfCheckCheckReport | SelfCheckRepairReport

/** 检查项的中文标签；`sandbox_probe` 等项的含义由结论码补足。 */
const SELF_CHECK_LABELS: Readonly<Record<SelfCheckId, string>> = {
  shell: 'Shell 环境（bash）',
  node: 'Node.js',
  sandbox_launcher: '沙箱启动器 landlock-run',
  sandbox_probe: '内核 Landlock 支持',
  sandbox_exec: '沙箱内命令执行',
  pty: 'PTY 模块 node-pty',
  pty_sandbox: '沙箱内 PTY',
  dsh_home: '访客数据目录',
  attachments: '附件目录',
  hardlink: '硬链接（原子写入的前提）',
  rg: 'ripgrep',
}

export interface SelfCheckAdvice {
  /** 检查项的中文标签。 */
  label: string
  /** 结论：这个结果通常意味着什么；`ok` 项为空串。 */
  meaning: string
  /** 下一步：用户可以做什么；`ok` 项为空串。 */
  nextStep: string
}

/**
 * 结论码文案表。
 *
 * 每条都是「结论 + 下一步」，措辞与 `docs/项目状态.md` 的既有结论一致，
 * 不做比现象更绝对的推断。`warn` 与 `fail` 共用同一句：两者都要让用户知道
 * 断在哪里，区别只体现在状态徽章上。
 */
const SELF_CHECK_CODE_ADVICE: Readonly<Record<SelfCheckCode, { meaning: string; nextStep: string }>> = {
  SHELL_MISSING: {
    meaning: '运行时里找不到 bash',
    nextStep: '重新安装或更新运行环境',
  },
  NODE_MISSING: {
    meaning: '运行时里找不到 node',
    nextStep: '重新安装或更新运行环境',
  },
  LAUNCHER_MISSING: {
    meaning: '找不到沙箱启动器 landlock-run',
    nextStep: '先点「修复运行时权限」，仍失败则重装运行时',
  },
  LAUNCHER_NOT_EXECUTABLE: {
    meaning: '沙箱启动器没有执行位，任何被沙箱包裹的命令都无法启动',
    nextStep: '点「修复运行时权限」即可修好，不需要重新下载运行时',
  },
  PROBE_UNUSABLE: {
    // 两种来源：内核确实不支持 Landlock，或启动器被尝试执行但拿不到结果（被信号杀死/超时）。
    // 后者不能说成「内核不支持」，因此文案同时覆盖两种，也不写成绝对断言。
    meaning: '沙箱探测判定为不可用（内核不支持 Landlock，或启动器无法完成探测）',
    nextStep: '确认系统版本后反馈此结果；沙箱不可用时 dsh 的 bash 工具无法启动',
  },
  PROBE_PARTIAL: {
    meaning: '内核只支持部分 Landlock 能力（老 ABI）',
    nextStep: '一般仍可用；若 bash 工具仍失败请反馈此结果',
  },
  EXEC_LAUNCHER_FAILED: {
    meaning: '沙箱启动器在真正执行时失败（授权根目录不存在或启动器级错误）',
    nextStep: '检查工作区目录是否存在，必要时重装运行时（会话、密钥、附件会保留）',
  },
  EXEC_COMMAND_FAILED: {
    meaning: '被沙箱包裹的命令执行失败',
    nextStep: '结合运行日志查看具体报错',
  },
  PTY_MODULE_MISSING: {
    meaning: '运行时里找不到 node-pty，dsh 的 bash 工具无法创建终端',
    nextStep: '重新安装或更新运行环境',
  },
  PTY_LOAD_FAILED: {
    meaning: 'node-pty 无法加载',
    nextStep: '重新安装或更新运行环境',
  },
  PTY_EXIT_EARLY: {
    meaning: 'PTY 子进程在就绪前退出——与 dsh bash 工具报的是同一现象',
    nextStep: '对照上一条「沙箱内 PTY」结果：两者都失败说明问题在 PTY 层，只有它失败说明问题在沙箱',
  },
  PTY_TIMEOUT: {
    meaning: 'PTY 在限定时间内没有就绪',
    nextStep: '关闭其它应用后重试；反复出现请反馈自检结果',
  },
  HOME_MISSING: {
    meaning: '访客数据目录不存在',
    nextStep: '重装运行时（会话、密钥、附件会保留）',
  },
  HOME_NOT_WRITABLE: {
    meaning: '访客数据目录不可写',
    nextStep: '先确认设备剩余空间，再重装运行时',
  },
  ATTACHMENTS_MISSING: {
    meaning: '附件目录不存在，截图等附件无法落盘',
    nextStep: '点「修复运行时权限」会自动补建该目录',
  },
  ATTACHMENTS_NOT_WRITABLE: {
    meaning: '附件目录不可写——这正是「Unable to persist attachment」那类报错的成因',
    nextStep: '确认剩余空间；必要时重装运行时',
  },
  HARDLINK_DENIED: {
    // 与 attachments 分开报的意义：目录能写 ≠ 能建硬链接。真机上 open+write+unlink 正常而
    // link(2) 一律被拒，只有这一项才会如实报出「附件不可能落盘、write 也建不了新文件」。
    // EXDEV（跨设备）也落在这个码上：errno 不同，结论相同 —— 硬链接在这个环境里不可用。
    meaning: '本机不允许创建硬链接（同目录与跨目录都被拒绝）。dsh 的 write 工具新建文件与所有附件落盘都依赖硬链接做原子安装，因此这两条链路会失败。',
    nextStep: '这是运行环境（PRoot/内核策略）层面的限制，应用侧无法绕过；需要写入新文件时改用 bash 重定向或终端里的 cp/mv，图片与截图类附件在当前版本上不可用。',
  },
  RG_MISSING: {
    meaning: '运行时里找不到 ripgrep',
    nextStep: '重新安装或更新运行环境',
  },
  RG_NOT_EXECUTABLE: {
    meaning: 'ripgrep 没有执行位，grep / glob 工具会失败',
    nextStep: '点「修复运行时权限」即可修好',
  },
}

/**
 * 出现这些码时，「修复运行时权限」才有意义：都是权限位或缺失目录，
 * 修复动作只做补权限与补建目录，不会去动运行时里的其它文件。
 */
export const SELF_CHECK_REPAIRABLE_CODES: readonly SelfCheckCode[] = [
  'LAUNCHER_MISSING',
  'LAUNCHER_NOT_EXECUTABLE',
  'ATTACHMENTS_MISSING',
  'RG_MISSING',
  'RG_NOT_EXECUTABLE',
]

/** 是否存在由「修复运行时权限」覆盖的结论码。 */
export function selfCheckNeedsRepair(checks: readonly SelfCheckItem[]): boolean {
  return checks.some(item => item.code !== undefined && SELF_CHECK_REPAIRABLE_CODES.includes(item.code))
}

/**
 * 取一项检查的展示文案（中文原文，界面用 `t()` 包一层）。
 *
 * `ok` 项没有结论与下一步，返回空串：界面据此只显示标签与徽章，
 * 不再编造一句「一切正常」之外的推断。
 */
export function selfCheckAdvice(item: SelfCheckItem): SelfCheckAdvice {
  const label = SELF_CHECK_LABELS[item.id]
  if (item.code === undefined) return { label, meaning: '', nextStep: '' }
  const advice = SELF_CHECK_CODE_ADVICE[item.code]
  return { label, meaning: advice.meaning, nextStep: advice.nextStep }
}

const SELF_CHECK_ID_SET = new Set<string>(SELF_CHECK_IDS)
const SELF_CHECK_STATUS_SET = new Set<string>(SELF_CHECK_STATUSES)
const SELF_CHECK_CODE_SET = new Set<string>(SELF_CHECK_CODES)
const SELF_CHECK_ID_ORDER = new Map<string, number>(SELF_CHECK_IDS.map((id, index) => [id, index]))

/**
 * dsh 版本号：界面会显示它，因此只接受短的可打印标识。
 * 形如 `0.1.5-rc.2`；控制字符、空白与超长文本一律拒绝。
 */
const VERSION_TEXT_PATTERN = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$/

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value as Record<string, unknown>
}

function selfCheckCount(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

function selfCheckItem(value: unknown): SelfCheckItem {
  const record = asRecord(value, '自检项')
  const id = record.id
  if (typeof id !== 'string' || !SELF_CHECK_ID_SET.has(id)) throw new Error('自检项标识无效')
  const status = record.status
  if (typeof status !== 'string' || !SELF_CHECK_STATUS_SET.has(status)) throw new Error('自检项状态无效')
  if (status === 'ok') {
    // 契约：ok 不带 code。带了说明载荷自相矛盾（会渲染出「正常 + 修复建议」）。
    if (record.code !== undefined) throw new Error('自检项状态与结论码不一致')
    return { id: id as SelfCheckId, status: 'ok' }
  }
  const code = record.code
  if (typeof code !== 'string' || !SELF_CHECK_CODE_SET.has(code)) throw new Error('自检项结论码无效')
  return { id: id as SelfCheckId, status: status as SelfCheckStatus, code: code as SelfCheckCode }
}

/**
 * 按冻结契约校验自检结果载荷，形态不符即抛错。
 *
 * 拒绝的情形（除字段类型外）：未知的 id 或 code、重复的检查项、`ok` 带 code、
 * 非 `ok` 不带 code、`repair` 的已修复项数大于可修复项数，以及格式非法的版本号。
 * 未知字段一律忽略：界面只读已知字段，多出来的内容既不显示也不保存。
 */
export function validateSelfCheckReport(value: unknown): SelfCheckReport {
  const record = asRecord(value, '自检结果')
  const availableBytes = selfCheckCount(record.availableBytes, '自检可用空间')
  if (record.operation === 'repair') {
    const repaired = selfCheckCount(record.repaired, '自检已修复项数')
    const candidates = selfCheckCount(record.candidates, '自检可修复项数')
    if (repaired > candidates) throw new Error('自检已修复项数超过可修复项数')
    return { operation: 'repair', availableBytes, repaired, candidates }
  }
  if (record.operation !== 'check') throw new Error('自检操作类型无效')

  let dshVersion: string | null = null
  if (record.dshVersion !== null) {
    if (typeof record.dshVersion !== 'string' || !VERSION_TEXT_PATTERN.test(record.dshVersion)) {
      throw new Error('dsh 版本格式无效')
    }
    dshVersion = record.dshVersion
  }

  const rawChecks: unknown = record.checks
  if (!Array.isArray(rawChecks) || rawChecks.length > SELF_CHECK_IDS.length) throw new Error('自检项列表格式无效')
  const checks: SelfCheckItem[] = []
  const seen = new Set<string>()
  for (const raw of rawChecks) {
    const item = selfCheckItem(raw)
    if (seen.has(item.id)) throw new Error('自检项标识重复')
    seen.add(item.id)
    checks.push(item)
  }
  // 无论载荷里是什么顺序，界面都按固定顺序展示：同一环路的先后关系不能随载荷变化。
  checks.sort((left, right) => (SELF_CHECK_ID_ORDER.get(left.id) ?? 0) - (SELF_CHECK_ID_ORDER.get(right.id) ?? 0))
  return { operation: 'check', availableBytes, dshVersion, checks }
}

/** 非抛错版本：调用方只想知道「这个载荷能不能用」时使用。 */
export function isSelfCheckReport(value: unknown): value is SelfCheckReport {
  try {
    validateSelfCheckReport(value)
    return true
  } catch {
    return false
  }
}

/** 校验自检操作类型；写入桥接前调用，避免把未知操作透传到原生侧。 */
export function validateSelfCheckOperation(value: unknown): SelfCheckOperation {
  if (value !== 'check' && value !== 'repair') throw new Error('自检操作类型无效')
  return value
}
