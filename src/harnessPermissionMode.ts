export type HarnessPermissionMode = 'workspace-write' | 'danger-full-access'

/** 安全校验点：只允许两个完整枚举值；禁止把任意字符串传入原生启动环境。 */
export function validateHarnessPermissionMode(value: unknown): HarnessPermissionMode {
  if (value !== 'workspace-write' && value !== 'danger-full-access') throw new Error('Harness 启动权限格式无效')
  return value
}
