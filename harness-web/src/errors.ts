/**
 * RpcFailure.code → 面向用户的中文文案。
 * 未知 code 回退到服务端原始 message（dsh 的 message 为英文，原样展示）。
 */

const CODE_MESSAGES: Record<string, string> = {
  'bad-request': '请求参数无效',
  cancelled: '操作已取消',
  'session-not-found': '会话不存在',
  'model-unavailable': '模型不可用',
  'session-conflict': '会话工作目录冲突',
  'invalid-time-zone': '时区无效',
  'workspace-attach-failed': '无法关联工作区',
  'workspace-not-found': '工作区不存在',
  'workspace-invalid-path': '工作区路径无效',
  'workspace-name-conflict': '工作区名称冲突',
  'workspace-move-invalid': '工作区排序无效',
  'directory-unreadable': '目录不可读',
  'directory-exists': '目录已存在',
  'directory-create-failed': '目录创建失败',
  'directory-picker-unavailable': '目录选择器不可用',
  'agent-preset-read-only': '该代理预设只读',
  'agent-preset-locked': '代理预设已锁定',
  'agent-preset-conflict': '代理预设冲突',
  'agent-preset-not-found': '代理预设不存在',
  'agent-preset-invalid': '代理预设无效',
  'agent-busy': '代理正忙',
  'attachment-error': '附件处理失败',
  'queue-item-not-found': '队列条目不存在',
  'steer-unavailable': '无法接管此条目',
  'command-error': '命令执行失败',
  'unknown-command': '未知命令',
  'settings-rejected': '设置被拒绝',
  'settings-not-exposed': '该设置未开放',
  'settings-conflict': '设置已变更，请刷新后重试',
  'credential-rejected': '凭证被拒绝',
  'model-discovery-failed': '模型发现失败',
  'title-invalid': '标题无效',
  'fork-unavailable': '会话无法分叉',
  'subagent-parent-unavailable': '父会话不可用',
  'subagent-not-found': '子代理不存在',
  'subagent-catalog-diagnostic': '子代理目录异常',
  'subagent-not-resumable': '子代理无法恢复',
  'subagent-unauthorized': '子代理未授权',
  'subagent-delivery-unavailable': '子代理无法投递',
  internal: '内部错误',
}

/** 把业务失败转成用户可见的中文提示。 */
export function describeFailure(code: string, message: string): string {
  return CODE_MESSAGES[code] ?? message
}
