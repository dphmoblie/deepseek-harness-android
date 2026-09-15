/**
 * 日志判读：把已在代码或文档里确诊过的错误特征，映射成一句结论与下一步。
 *
 * 为什么需要它：日志原文对用户几乎不可读——`Cannot read properties of undefined
 * (reading 'prepare')` 看不出是插件目录里多了一份运行时副本，`HARNESS_STOP_TIMEOUT`
 * 也会盖住真正的启动失败原因。这里只做**特征识别 + 已有结论的复述**：
 *
 *  - 只匹配已经确诊并写进 `docs/项目状态.md` 的签名，不做自由猜测；
 *  - 措辞一律是「通常是」「若同时看到」，不写成绝对判断；
 *  - 匹配不到就不显示任何东西，宁可不说，也不给编造的结论。
 *
 * 本模块是纯函数、不依赖 React 与桥接，便于单测。
 */

export interface LogInsight {
  /** 稳定标识：用于 React key 与用例断言。 */
  id: string
  /** 短标签：一眼看出「看到的是什么」。 */
  title: string
  /** 这个特征通常意味着什么。 */
  meaning: string
  /** 用户可以做什么。 */
  nextStep: string
}

interface LogInsightRule extends LogInsight {
  /** 命中即认为出现了该特征；一律按小写文本匹配。 */
  pattern: RegExp
}

/**
 * 规则表。顺序即展示顺序：越靠前的越常见、越值得先看。
 *
 * 每条规则的依据都写在 `meaning` 里（或指向 `docs/项目状态.md` 的结论），
 * 新增规则前必须先确认结论有代码或真机证据，不能凭现象推断。
 */
const RULES: readonly LogInsightRule[] = [
  {
    // 真机确诊（Android 16 / HONOR）：镜像里没有 bwrap，Landlock 启动器探测也拿不到可用结果，
    // dsh 在要求沙箱的模式下 fail-closed —— 命令根本不执行，所以这条要排在所有泛化规则之前。
    id: 'sandbox-backend-unavailable',
    pattern: /no sandbox backend is usable/,
    title: '本机没有可用的沙箱后端，命令没有被执行',
    meaning: '这台设备上没有可用的沙箱后端：dsh 在 Linux 上按 bwrap → Landlock 的顺序探测，而报错原文说没有任何后端可用。dsh 在这里是 fail-closed——要求沙箱的模式下命令根本不会执行，报错发生在命令启动之前，不是命令本身失败。',
    nextStep: '在当前 Harness 会话的权限预设中选择 danger-full-access，或输入 /permission danger-full-access 后重试。此模式关闭 dsh 文件系统沙箱和命令审批；仅重启不会改变已有会话的权限。',
  },
  {
    // 上游缺陷（4/4 复现）：显式 undefined 与「省略该键」语义相同，但校验先判它不可序列化。
    id: 'explicit-undefined-argument',
    pattern: /binding arguments must be lossless json/,
    title: '工具参数被显式传成了 undefined',
    meaning: '这次工具调用把某个参数显式写成了 undefined（不是省略该键），dsh 的参数校验认为它无法无损序列化成 JSON，于是判为非法值。这是已知的上游校验问题，跟设备、运行时版本无关。',
    nextStep: '让这次调用省略该键即可（例如不要写 timeoutMs: undefined）：省略与显式 undefined 语义相同，在上游修复前先按省略处理。',
  },
  {
    id: 'duplicate-runtime-module',
    pattern: /cannot read properties of undefined \(reading 'prepare'\)/,
    title: '工具调用全部失败（模块身份分裂）',
    meaning: '插件目录里存在第二份 @deepseek-ai/dsh-tools 的真实副本，调度器的 Symbol 对不上，之后每一次工具调用都会这样报错。',
    nextStep: '展开「诊断日志」查找 MODULE_GRAPH 记录：count 大于 1 即确认；然后在插件管理里对相关插件执行修复或重装。',
  },
  {
    id: 'module-graph-duplicate',
    pattern: /module_graph\|[^\n]*result=failed/,
    title: '已确认存在两份运行时模块',
    meaning: 'MODULE_GRAPH 探测到不同真实路径数大于 1，与「工具调用全部失败」是同一个根因。',
    nextStep: '在插件管理中修复或重装日志里涉及的插件，然后重启运行时再探测一次。',
  },
  {
    id: 'plugin-tree-failed',
    pattern: /plugin\(s\) failed to load|plugin tree failed to load/,
    title: '插件无法加载，Harness 未能启动',
    meaning: '冒号后列出的插件名就是无法解析的插件；第三方插件把 dsh 依赖声明为 peerDependencies，版本不匹配时就会这样。',
    nextStep: '按日志里列出的名字卸载或重装这些插件，再重新启动。',
  },
  {
    id: 'missing-credential',
    pattern: /missing_credential|no api key|api key is not|api key missing|缺少凭据/,
    title: '缺少模型凭据',
    meaning: '这一轮对话没有拿到可用的模型 API Key，请求在发出前就被拒绝。',
    nextStep: '到「模型与密钥」保存一次 API Key，然后重新启动运行时让它注入。',
  },
  {
    id: 'invalid-credential',
    pattern: /invalid api key|incorrect api key|\b401\b|unauthorized|authenticationerror/,
    title: '密钥被服务端拒绝',
    meaning: '请求发出去了，但凭据无效、过期或没有该模型的权限。',
    nextStep: '核对「模型与密钥」里的 Key 与所选模型是否属于同一家供应商。',
  },
  {
    id: 'port-in-use',
    pattern: /eaddrinuse|address already in use/,
    title: '本机端口被占用',
    meaning: '上一次的 Harness 进程没有退出干净，端口仍被它占着。',
    nextStep: '在设置里停止运行环境，确认没有残留进程后再启动。',
  },
  {
    id: 'module-missing',
    pattern: /err_module_not_found|cannot find module/,
    title: '运行时文件不完整',
    meaning: 'Harness 需要的模块在运行时里找不到，通常是运行时被中断的更新或未完成的安装。',
    nextStep: '重新安装或更新运行环境（内置包可直接离线安装）。',
  },
  {
    id: 'native-module-failed',
    pattern: /err_dlopen_failed|invalid elf|node-pty.*error/,
    title: '原生模块无法在这台设备运行',
    meaning: '内置的原生模块与设备 CPU 架构或内存页大小不兼容。',
    nextStep: '在设置里查看运行时架构与 ABI 信息，并反馈这段日志。',
  },
  {
    id: 'no-space',
    pattern: /enospc|no space left/,
    title: '存储空间不足',
    meaning: '写入被系统拒绝，安装、解压或会话保存都可能因此失败。',
    nextStep: '清理设备空间后重试；运行时占用可在「运行与后台」里查看。',
  },
  {
    id: 'network-failed',
    pattern: /eai_again|enotfound|getaddrinfo|etimedout|econnreset/,
    title: '网络请求失败',
    meaning: '下载或模型请求没能连上，多数是断网、DNS 或代理问题。',
    nextStep: '确认设备网络可用后重试；运行时安装支持从断点续传。',
  },
  {
    id: 'harness-start-failed',
    pattern: /harness_start\|result=failed/,
    title: 'Harness 启动失败',
    meaning: '同一条诊断记录里的 code 字段就是失败原因码，后面的运行日志通常有对应的原始报错。',
    nextStep: '先看同一时刻的「运行日志」，再按原因码处理（缺模块、端口占用、CPU 不支持等）。',
  },
  {
    id: 'harness-stop-timeout',
    pattern: /harness_stop_timeout/,
    title: '停止超时可能盖住了真正原因',
    meaning: '停止旧进程时超时，这条记录本身不是根因，真正失败的信息在它之前。',
    nextStep: '往上翻更早的记录，找第一条 result=failed 或运行日志里的异常栈。',
  },
]

/**
 * 从日志文本里识别已知特征。
 *
 * 命中多条时按规则表顺序返回；文本为空、没有命中或输入不是字符串时返回空数组，
 * 由界面决定不显示任何判读内容。
 */
export function readLogInsights(text: string): LogInsight[] {
  if (typeof text !== 'string' || text === '') return []
  const normalized = text.toLowerCase()
  return RULES
    .filter(rule => rule.pattern.test(normalized))
    .map(({ id, title, meaning, nextStep }) => ({ id, title, meaning, nextStep }))
}
