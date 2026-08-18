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
  'permission-denied': '系统权限限制：Android 安全策略拒绝了该操作（如需访问系统文件请用 Shizuku）',
  EACCES: '系统权限限制：无法执行该文件/链接操作（Android SELinux 限制）',
  EPERM: '操作不被允许：当前为受限 root，系统路径受 Android 保护',
  'readonly-filesystem': '文件系统只读：该路径受 Android 保护，无法修改',
  'no-sandbox-backend': '沙箱后端不可用：已降级为受限模式，部分文件操作可能受限',
  'apt-unavailable': '包管理不可用：apt/系统安装受 Android 权限限制，请使用内置工具链',
  internal: '内部错误',
}

/** 把业务失败转成用户可见的中文提示。 */
export function describeFailure(code: string, message: string): string {
  return CODE_MESSAGES[code] ?? message
}
