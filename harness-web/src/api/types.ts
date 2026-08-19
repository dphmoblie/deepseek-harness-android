/**
 * dsh wire 协议类型全集。
 *
 * 固化自 deepseek-harness 0.1.0-rc.5 的 host/apiproxy 契约
 * （rpc.schema.ts / sessions.schema.ts / events.schema.ts 等），
 * 是移动端前端与 Linux 侧 Harness 后端通信的唯一依据。
 * 线上所有品牌类型（Branded<...>）序列化后都是普通字符串。
 *
 * 协议要点：
 * - 单播 RPC：POST /api/<method>，envelope {type:'client-request', rpcId, method, payload}；
 * - 响应：HTTP 200 + {type:'server-response', rpcId, result}，result.ok=false 时携带业务错误；
 *   HTTP 状态码只表达载体层错误（400 非 JSON、415 媒体类型、404 未知路径、500 崩溃）；
 * - 下行事件：WebSocket /api/events.mux（会话多路帧）与 /api/events.host（主机帧），
 *   每帧 {type:'server-request', rpcId, method: 帧类型, payload: 帧}，
 *   下行流只读——客户端发任何消息会被服务端以 1008 关闭；
 * - 审批/提问应答：POST /api/respond，envelope {type:'client-response', rpcId, result}。
 */

// ---------------------------------------------------------------------------
// 标识符（线上均为普通字符串）
// ---------------------------------------------------------------------------

export type SessionId = string
export type MessageId = string
export type RpcId = string
export type CallId = string
export type ApprovalRequestId = string
export type JobId = string
export type WorkspaceId = string

// ---------------------------------------------------------------------------
// envelope 与错误
// ---------------------------------------------------------------------------

export type ClientRequest = {
  type: 'client-request'
  rpcId: RpcId
  method: string
  payload: unknown
}

export type ServerResponse = {
  type: 'server-response'
  rpcId: RpcId
  result: RpcResult<unknown>
}

export type ServerRequest = {
  type: 'server-request'
  rpcId: RpcId
  method: string
  payload: unknown
}

export type ClientResponse = {
  type: 'client-response'
  rpcId: RpcId
  result: RpcResult<unknown>
}

export type RpcError = {
  code: string
  message: string
  details: Record<string, unknown>
}

/** 业务结果：ok=false 时携带结构化错误；void 方法序列化后可能缺失 value 字段。 */
export type RpcResult<T> = { ok: true; value: T } | { ok: false; error: RpcError }

// ---------------------------------------------------------------------------
// 会话域
// ---------------------------------------------------------------------------

/** 会话事件信封：type/data 严格对应，data 内部对未知内容保持宽类型（merge-extensible）。 */
export type SessionEvent = {
  type: string
  /** 会话内单调递增序号。 */
  seq: number
  /** Unix 纪元毫秒。 */
  time: number
  data: Record<string, unknown>
  sourceEventSeqs?: number[]
  surfaceOp?: 'append' | { op: 'replace'; start: number; end: number }
  ignorable?: true
}

/** session.history 的一行：事件 + 可选的主机侧工具视图（卡片渲染线索）。 */
export type HistoryEntry = {
  event: SessionEvent
  view?: ToolEventView
}

export type ToolEventView =
  | { for: 'call'; view: { card: string } & Record<string, unknown> }
  | { for: 'result'; view: { card: string } & Record<string, unknown> }

/** session.list 的一行摘要。 */
export type SessionSummary = {
  sessionId: SessionId
  updatedAt: number
  running: boolean
  blank: boolean
  parentSessionId?: SessionId
  origin?: 'subagent'
  cwd?: string
  agentPreset?: string
  projections?: SessionProjectionsBlock
}

/** 投影基线块：values 是宽记录，各投影值已由主机侧校验。 */
export type SessionProjectionsBlock = {
  /** -1 表示空日志（与 session/subscribed 的 lastSeq 约定一致）。 */
  asOfSeq: number
  values: Record<string, unknown>
}

// -- 消息内容 -----------------------------------------------------------------

/** 消息内容块；核心 5 种，未知类型按 type 透传（插件可扩展）。 */
export type ContentBlock =
  | { type: 'text'; text: string }
  | { type: 'reasoning'; text: string }
  | { type: 'image'; attachment: ImageAttachmentRef }
  | { type: 'tool-call'; id: CallId; name: string; arguments: string }
  | { type: 'tool-result'; toolCallId: CallId; content: ContentBlock[]; isError?: boolean }
  | ({ type: string } & Record<string, unknown>)

export type ImageAttachmentRef = {
  attachmentId: string
  mediaType: 'image/png' | 'image/jpeg' | 'image/webp' | 'image/gif'
  bytes: number
  width: number
  height: number
  name?: string
}

export type MessageSource =
  | { kind: 'user' }
  | { kind: 'plugin'; plugin: string; form?: string }
  | { kind: 'model'; provider: string; model: string }
  | { kind: 'tool'; callId: CallId }
  | ({ kind: string } & Record<string, unknown>)

export type Message = {
  id: MessageId
  role: 'system' | 'user' | 'assistant'
  content: ContentBlock[]
  source: MessageSource
}

// -- 流式分块 -----------------------------------------------------------------

export type TokenUsage = {
  inputTokens: number
  outputTokens: number
  cacheReadTokens?: number
  cacheWriteTokens?: number
  reasoningTokens?: number
}

export type FinishReason =
  | { kind: 'stop' }
  | { kind: 'tool-calls' }
  | { kind: 'max-tokens' }
  | { kind: 'aborted'; failure: LlmFailure }
  | { kind: 'error'; failure: LlmFailure }
  | ({ kind: string } & Record<string, unknown>)

export type LlmFailure = {
  message: string
  code: string
  status?: number
}

/** 适配器原始流分块（assistant/chunk 事件的 data.chunk）。 */
export type StreamChunk =
  | { type: 'block-start'; index: number; blockType: string }
  | { type: 'text-delta'; index: number; text: string }
  | { type: 'reasoning-delta'; index: number; text: string }
  | { type: 'tool-call-delta'; index: number; id: CallId; name?: string; argumentsDelta: string }
  | { type: 'block-end'; index: number; block: ContentBlock }
  | { type: 'usage'; usage: TokenUsage }
  | { type: 'finish'; reason: FinishReason }

// -- 模型目录 -----------------------------------------------------------------

export type ModelSelection = {
  provider: string
  model: string
  reasoningEffort?: string
}

export type ModelReasoningEffort = {
  id: string
  name: string
  description?: string
}

export type ModelReasoning = {
  efforts: ModelReasoningEffort[]
  defaultEffort?: string
}

export type ModelCatalogModel = {
  id: string
  name: string
  description?: string
  reasoning?: ModelReasoning
}

export type ModelProviderGroup = {
  id: string
  name: string
  models: ModelCatalogModel[]
}

export type ModelCatalogFailure = {
  id: string
  name: string
  message: string
}

// -- 提示输入 -----------------------------------------------------------------

export type PromptContentPart =
  | { type: 'text'; text: string }
  | { type: 'image'; mediaType: 'image/png' | 'image/jpeg' | 'image/webp' | 'image/gif'; data: string; name?: string }

/** session.updateQueue 的动作。 */
export type QueueAction =
  | { kind: 'edit'; content: ContentBlock[] }
  | { kind: 'remove' }
  | { kind: 'steer' }

/** todo/write 事件的整表快照条目。 */
export type TodoItem = {
  content: string
  status: 'pending' | 'in_progress' | 'completed'
}

// ---------------------------------------------------------------------------
// 下行事件帧
// ---------------------------------------------------------------------------

export type AskUserQuestionItem = {
  id: string
  question: string
  header?: string
  detail?: string
  options?: { label: string; description?: string }[]
  multiSelect?: boolean
  intent?: { kind: 'plan-review'; approve: string } & Record<string, unknown>
}

/** 任务视图（session/jobs 帧携带，亦为 tasks 面板的唯一数据源）。 */
export type TaskView = {
  id: JobId
  kind: string
  label: string
  status: 'running' | 'stopping' | 'completed' | 'killed' | 'failed'
  detail?: string
  startedAt: number
  finishedAt?: number
}

/** 会话多路帧（/api/events.mux 下行）。 */
export type MuxFrame =
  | { type: 'session/event'; sessionId: SessionId; event: SessionEvent; view?: ToolEventView }
  | { type: 'session/subscribed'; sessionId: SessionId; lastSeq: number }
  | {
    type: 'approval/requested'
    sessionId: SessionId
    approvalId: ApprovalRequestId
    toolName: string
    callId?: CallId
    reason?: string
  }
  | { type: 'approval/resolved'; sessionId: SessionId; approvalId: ApprovalRequestId; outcome: 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable' }
  | { type: 'question/requested'; sessionId: SessionId; questions: AskUserQuestionItem[] }
  | { type: 'question/resolved'; sessionId: SessionId; questionRpcId: RpcId; outcome: 'answered' | 'cancelled' }
  | { type: 'session/queue'; sessionId: SessionId; items: QueuedInboxItem[] }
  | { type: 'session/jobs'; sessionId: SessionId; jobs: TaskView[] }
  | { type: 'session/projection'; sessionId: SessionId; key: string; value: unknown; seq: number }
  | { type: 'stream/error'; error: RpcError }
  | ({ type: string } & Record<string, unknown>)

export type QueuedInboxItem = {
  id: MessageId
  placement: 'queued' | 'steering' | 'context'
  message: Message
}

/** 主机帧（/api/events.host 下行）。 */
export type HostFrame =
  | { type: 'host/session-added'; sessionId: SessionId; blank: boolean; parentSessionId?: SessionId; origin?: 'subagent'; cwd?: string; agentPreset?: string }
  | { type: 'host/session-removed'; sessionId: SessionId }
  | { type: 'host/session-status'; sessionId: SessionId; running: boolean }
  | { type: 'host/agent-error'; sessionId: SessionId; message: string }
  | { type: 'host/workspace-changed'; workspace: WorkspaceView }
  | { type: 'host/workspace-removed'; workspaceId: WorkspaceId }
  | { type: 'host/workspace-order-changed'; workspaceIds: WorkspaceId[] }
  | { type: 'host/archived-sessions-changed'; archivedSessionIds: SessionId[] }
  | { type: 'host/remote-event'; event: string; args: unknown[] }
  | { type: 'stream/error'; error: RpcError }
  | ({ type: string } & Record<string, unknown>)

// ---------------------------------------------------------------------------
// 工作区与目录
// ---------------------------------------------------------------------------

export type WorkspaceView = {
  workspaceId: WorkspaceId
  path: string
  title: string
  sessionIds: SessionId[]
  createdAt: string
  updatedAt: string
}

export type DirectoryEntry = {
  name: string
  path: string
  hidden: boolean
}

export type DirectoryListing = {
  path: string
  home: string
  crumbs: DirectoryEntry[]
  entries: DirectoryEntry[]
  truncated: boolean
}

// ---------------------------------------------------------------------------
// 设置 / 凭证 / LLM
// ---------------------------------------------------------------------------

export type SettingsSecretView = {
  path: string[]
  set: boolean
}

export type SettingsNamespaceView = {
  ns: string
  schema: unknown
  value: unknown
  base?: unknown
  user?: unknown
  applies: 'live' | 'restart'
  secrets: SettingsSecretView[]
  revision: number
}

export type SettingsPathOp =
  | { op: 'set'; path: string[]; value: unknown }
  | { op: 'unset'; path: string[] }

export type CredentialView = {
  configured: boolean
  source?: string
  writable: boolean
}

export type ConfigurableProviderView = {
  provider: string
  displayName: string
  settingsNs: string
  settingsPath: string[]
  active: boolean
  declared?: boolean
}

export type DiscoveredModelView = {
  id: string
  name?: string
  contextWindow?: number
  maxTokens?: number
}

export type SkillEntry = {
  name: string
  description: string
  whenToUse?: string
  modelInvocable: boolean
}

export type SubagentListEntry =
  | { kind: 'child'; id: SessionId; mode: 'one-shot'; activity: 'running' | 'inactive'; hasChildren: boolean; label?: string }
  | { kind: 'child'; id: SessionId; mode: 'continuable'; activity: 'running' | 'inactive'; hasChildren: boolean; label: string }
  | { kind: 'diagnostic'; id: SessionId; reason: 'corrupt' | 'unsupported' | 'unavailable' }

export type AgentPresetEntry = {
  id: string
  trust: 'system' | 'user'
  isDefault: boolean
  name?: string
  description?: string
  broken?: string
}

export type GoalRef = {
  id: string
  revision: number
}

/** 主机插件运行时清单（只读；插件客户端 UI 由前端自行呈现）。 */
export type PluginInventoryEntry = {
  entryId: string
  moduleName: string
  enabled: boolean
  fiberPhase: 'pending' | 'loading' | 'active' | 'failed' | 'unloading' | null
}

/** Typert direct Remote 调用使用 args 信封，即使方法没有参数也不能省略。 */
export type PluginInventoryListRequest = { args: Record<string, never> }
export type PluginInventoryListValue = { entries: PluginInventoryEntry[] }

// ---------------------------------------------------------------------------
// 方法签名总表：method → {payload, value}
// ---------------------------------------------------------------------------

export type SessionListRequest = { cursor?: string }
export type SessionListValue = { items: SessionSummary[] }

export type SessionSearchRequest = { query: string }
export type SessionSearchValue = { items: { sessionId: SessionId; snippet: string }[]; hasMore: boolean }

export type SessionCreateRequest = { workspaceId?: WorkspaceId; cwd?: string; sessionId?: SessionId; agentPreset?: string }
export type SessionCreateValue = { sessionId: SessionId; agentPreset?: string }

export type SessionHistoryRequest = { sessionId: SessionId; beforeSeq?: number; maxMessages?: number }
export type SessionHistoryValue = { events: HistoryEntry[]; hasMore: boolean; projections?: SessionProjectionsBlock }

export type SessionModelsRequest = { sessionId: SessionId }
export type SessionModelsValue = {
  current: ModelSelection
  routable: boolean
  groups: ModelProviderGroup[]
  failures: ModelCatalogFailure[]
}

export type SessionSelectModelRequest = { sessionId: SessionId; provider: string; model: string; reasoningEffort?: string }
export type SessionSelectModelValue = { selected: ModelSelection }

export type SessionRenameRequest = { sessionId: SessionId; title: string }
export type SessionRenameValue = { title: string; seq: number }

export type SessionForkRequest = { sessionId: SessionId; atSeq?: number }
export type SessionForkValue = { sessionId: SessionId }

export type SessionPromptRequest = {
  sessionId: SessionId
  mode: 'queue' | 'steer'
  content: PromptContentPart[]
  clientTimeZone?: string
}
export type SessionPromptValue = { accepted: true; command?: { kind: 'success'; text?: string } }

export type SessionAttachmentRequest = { sessionId: SessionId; attachmentId: string }
export type SessionAttachmentValue = { attachment: ImageAttachmentRef; data: string }

export type SessionUpdateQueueRequest = { sessionId: SessionId; itemId: MessageId; action: QueueAction }
export type SessionUpdateQueueValue = { accepted: true }

export type SessionCancelRequest = { sessionId: SessionId }
export type SessionCancelValue = { accepted: true }

export type SubagentListRequest = { parentSessionId: SessionId }
export type SubagentListValue = { entries: SubagentListEntry[]; parentAvailable: boolean }

export type SubagentHistoryRequest = {
  parentSessionId: SessionId
  childSessionId: SessionId
  mode: 'one-shot' | 'continuable'
  beforeSeq?: number
  maxMessages?: number
}
export type SubagentHistoryValue = { events: HistoryEntry[]; hasMore: boolean; projections?: SessionProjectionsBlock }

export type SubagentPromptRequest = {
  parentSessionId: SessionId
  childSessionId: SessionId
  mode: 'continuable'
  content: ContentBlock[]
  clientTimeZone?: string
}
export type SubagentPromptValue = { messageId: MessageId }

export type SubagentInterruptRequest = { parentSessionId: SessionId; childSessionId: SessionId; mode: 'continuable' }
export type SubagentInterruptValue = { accepted: true }

export type HostDescribeRequest = Record<string, never>
export type HostDescribeValue = {
  version: string
  cwd: string
  provider?: string
  model?: string
  attachedSessions: number
  canOpenPath: boolean
}

export type HostPickDirectoryRequest = Record<string, never>
export type HostPickDirectoryValue = { path: string | null }

export type HostListDirectoryRequest = { path?: string }
export type HostListDirectoryValue = DirectoryListing

export type HostCreateDirectoryRequest = { path: string; name: string }
export type HostCreateDirectoryValue = { path: string }

export type HostOpenPathRequest = { path: string }
export type HostOpenPathValue = { opened: true }

export type WorkspaceListRequest = Record<string, never>
export type WorkspaceListValue = { items: WorkspaceView[]; archivedSessionIds: SessionId[] }

export type WorkspaceCreateRequest = { path: string }
export type WorkspaceCreateValue = { workspace: WorkspaceView; created: boolean }

export type WorkspaceRenameRequest = { workspaceId: WorkspaceId; title: string }
export type WorkspaceRenameValue = { workspace: WorkspaceView }

export type WorkspaceDeleteRequest = { workspaceId: WorkspaceId }
export type WorkspaceDeleteValue = { deleted: true }

export type WorkspaceInsertBeforeRequest = { workspaceId: WorkspaceId; beforeWorkspaceId?: WorkspaceId }
export type WorkspaceInsertBeforeValue = { workspaceIds: WorkspaceId[] }

export type WorkspaceInsertSessionBeforeRequest = {
  workspaceId: WorkspaceId
  sessionId: SessionId
  beforeSessionId?: SessionId
}
export type WorkspaceInsertSessionBeforeValue = { workspace: WorkspaceView }

export type WorkspaceArchiveSessionRequest = { sessionId: SessionId }
export type WorkspaceArchiveSessionValue = { archivedSessionIds: SessionId[] }

export type SkillListRequest = { sessionId: SessionId }
export type SkillListValue = { skills: SkillEntry[] }

export type AgentPresetListRequest = Record<string, never>
export type AgentPresetListValue = { presets: AgentPresetEntry[]; authorable: boolean; hasDocument: boolean }

export type AgentPresetSelectRequest = { sessionId: SessionId; agentPreset: string }
export type AgentPresetSelectValue = { agentPreset: string }

export type AgentPresetReadRequest = { agentPreset: string }
export type AgentPresetReadValue = {
  agentPreset: string
  trust: 'system' | 'user'
  content: string
  name?: string
  description?: string
}

export type AgentPresetCopyRequest = { from: string; agentPreset: string; name?: string }
export type AgentPresetCopyValue = { agentPreset: string }

export type AgentPresetOpenDocumentRequest = { agentPreset: string }
export type AgentPresetOpenDocumentValue = { opened: true } | { opened: false; path: string }

export type AgentPresetRemoveRequest = { agentPreset: string }
export type AgentPresetRemoveValue = Record<string, never>

export type GoalCreateRequest = { sessionId: SessionId; objective: string; maxGoalRounds?: number }
export type GoalCreateValue = { ref: GoalRef }

export type GoalEditRequest = { sessionId: SessionId; ref: GoalRef; objective?: string; maxGoalRounds?: number }
export type GoalEditValue = { ref: GoalRef }

export type GoalPauseRequest = { sessionId: SessionId; ref: GoalRef }
export type GoalPauseValue = { ref: GoalRef }

export type GoalResumeRequest = { sessionId: SessionId; ref: GoalRef }
export type GoalResumeValue = { ref: GoalRef }

export type GoalCompleteRequest = { sessionId: SessionId; ref: GoalRef }
export type GoalCompleteValue = { ref: GoalRef }

export type GoalClearRequest = { sessionId: SessionId; ref: GoalRef }
export type GoalClearValue = { cleared: true }

export type SettingsDescribeRequest = Record<string, never>
export type SettingsDescribeValue = { writable: boolean; hasDocument: boolean; namespaces: SettingsNamespaceView[] }

export type SettingsOpenDocumentRequest = Record<string, never>
export type SettingsOpenDocumentValue = { opened: true }

export type SettingsUpdateRequest = { ns: string; patch: Record<string, unknown>; expectedRevision?: number }
export type SettingsUpdateValue = SettingsNamespaceView

export type SettingsReplaceRequest = { ns: string; section: Record<string, unknown>; expectedRevision?: number }
export type SettingsReplaceValue = SettingsNamespaceView

export type SettingsMutateRequest = { ns: string; ops: SettingsPathOp[]; expectedRevision?: number }
export type SettingsMutateValue = SettingsNamespaceView

export type CredentialsDescribeRequest = { refs: string[] }
export type CredentialsDescribeValue = { credentials: Record<string, CredentialView> }

export type CredentialsSetRequest = { ref: string; value: string }
export type CredentialsSetValue = Record<string, never>

export type CredentialsUnsetRequest = { ref: string }
export type CredentialsUnsetValue = Record<string, never>

export type LlmProvidersRequest = Record<string, never>
export type LlmProvidersValue = { providers: ConfigurableProviderView[] }

export type LlmModelsRequest = Record<string, never>
export type LlmModelsValue = { groups: ModelProviderGroup[]; failures: ModelCatalogFailure[] }

export type LlmDiscoverModelsRequest = {
  settingsNs: string
  provider?: string
  baseURL?: string
  api?: string
  apiKey?: string
}
export type LlmDiscoverModelsValue = { models: DiscoveredModelView[] }

/** 全部客户端单向 RPC 方法签名（respond 是 client-response，不在此表）。 */
export type DshRpcMethods = {
  'session.list': { payload: SessionListRequest; value: SessionListValue }
  'session.search': { payload: SessionSearchRequest; value: SessionSearchValue }
  'session.create': { payload: SessionCreateRequest; value: SessionCreateValue }
  'session.history': { payload: SessionHistoryRequest; value: SessionHistoryValue }
  'session.models': { payload: SessionModelsRequest; value: SessionModelsValue }
  'session.selectModel': { payload: SessionSelectModelRequest; value: SessionSelectModelValue }
  'session.rename': { payload: SessionRenameRequest; value: SessionRenameValue }
  'session.fork': { payload: SessionForkRequest; value: SessionForkValue }
  'session.prompt': { payload: SessionPromptRequest; value: SessionPromptValue }
  'session.attachment': { payload: SessionAttachmentRequest; value: SessionAttachmentValue }
  'session.updateQueue': { payload: SessionUpdateQueueRequest; value: SessionUpdateQueueValue }
  'session.cancel': { payload: SessionCancelRequest; value: SessionCancelValue }
  'subagent.list': { payload: SubagentListRequest; value: SubagentListValue }
  'subagent.history': { payload: SubagentHistoryRequest; value: SubagentHistoryValue }
  'subagent.prompt': { payload: SubagentPromptRequest; value: SubagentPromptValue }
  'subagent.interrupt': { payload: SubagentInterruptRequest; value: SubagentInterruptValue }
  'host.describe': { payload: HostDescribeRequest; value: HostDescribeValue }
  'host.pickDirectory': { payload: HostPickDirectoryRequest; value: HostPickDirectoryValue }
  'host.listDirectory': { payload: HostListDirectoryRequest; value: HostListDirectoryValue }
  'host.createDirectory': { payload: HostCreateDirectoryRequest; value: HostCreateDirectoryValue }
  'host.openPath': { payload: HostOpenPathRequest; value: HostOpenPathValue }
  'workspace.list': { payload: WorkspaceListRequest; value: WorkspaceListValue }
  'workspace.create': { payload: WorkspaceCreateRequest; value: WorkspaceCreateValue }
  'workspace.rename': { payload: WorkspaceRenameRequest; value: WorkspaceRenameValue }
  'workspace.delete': { payload: WorkspaceDeleteRequest; value: WorkspaceDeleteValue }
  'workspace.insertBefore': { payload: WorkspaceInsertBeforeRequest; value: WorkspaceInsertBeforeValue }
  'workspace.insertSessionBefore': { payload: WorkspaceInsertSessionBeforeRequest; value: WorkspaceInsertSessionBeforeValue }
  'workspace.archiveSession': { payload: WorkspaceArchiveSessionRequest; value: WorkspaceArchiveSessionValue }
  'skill.list': { payload: SkillListRequest; value: SkillListValue }
  'agentPreset.list': { payload: AgentPresetListRequest; value: AgentPresetListValue }
  'agentPreset.select': { payload: AgentPresetSelectRequest; value: AgentPresetSelectValue }
  'agentPreset.read': { payload: AgentPresetReadRequest; value: AgentPresetReadValue }
  'agentPreset.copy': { payload: AgentPresetCopyRequest; value: AgentPresetCopyValue }
  'agentPreset.openDocument': { payload: AgentPresetOpenDocumentRequest; value: AgentPresetOpenDocumentValue }
  'agentPreset.remove': { payload: AgentPresetRemoveRequest; value: AgentPresetRemoveValue }
  'pluginInventory/list': { payload: PluginInventoryListRequest; value: PluginInventoryListValue }
  'goal.create': { payload: GoalCreateRequest; value: GoalCreateValue }
  'goal.edit': { payload: GoalEditRequest; value: GoalEditValue }
  'goal.pause': { payload: GoalPauseRequest; value: GoalPauseValue }
  'goal.resume': { payload: GoalResumeRequest; value: GoalResumeValue }
  'goal.complete': { payload: GoalCompleteRequest; value: GoalCompleteValue }
  'goal.clear': { payload: GoalClearRequest; value: GoalClearValue }
  'settings.describe': { payload: SettingsDescribeRequest; value: SettingsDescribeValue }
  'settings.openDocument': { payload: SettingsOpenDocumentRequest; value: SettingsOpenDocumentValue }
  'settings.update': { payload: SettingsUpdateRequest; value: SettingsUpdateValue }
  'settings.replace': { payload: SettingsReplaceRequest; value: SettingsReplaceValue }
  'settings.mutate': { payload: SettingsMutateRequest; value: SettingsMutateValue }
  'credentials.describe': { payload: CredentialsDescribeRequest; value: CredentialsDescribeValue }
  'credentials.set': { payload: CredentialsSetRequest; value: CredentialsSetValue }
  'credentials.unset': { payload: CredentialsUnsetRequest; value: CredentialsUnsetValue }
  'llm.providers': { payload: LlmProvidersRequest; value: LlmProvidersValue }
  'llm.models': { payload: LlmModelsRequest; value: LlmModelsValue }
  'llm.discoverModels': { payload: LlmDiscoverModelsRequest; value: LlmDiscoverModelsValue }
}

export type RpcMethodName = keyof DshRpcMethods

/** 审批应答 payload（/api/respond 的 result.value）。 */
export type ApprovalResponsePayload = {
  sessionId: SessionId
  approvalId: ApprovalRequestId
  outcome: 'allowed-once' | 'rejected'
}

/** 提问应答 payload（/api/respond 的 result.value）。 */
export type QuestionResponsePayload = {
  sessionId: SessionId
  answer: { answers: { id: string; selected: string[]; custom?: string }[] }
}
