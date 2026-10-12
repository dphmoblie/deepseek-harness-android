/**
 * 「已读过免责说明」这一格确认：只在本机浏览器存储里记一个固定的 `'1'`。
 *
 * 它记的是「用户点过确认」这个事实，不带任何用户内容，也不参与任何权限判断——
 * 总开关能不能开仍由原生侧决定，这里只决定「要不要先把那段说明摆出来」。
 *
 * 读不出来时一律按「没确认过」处理（无痕模式、存储被禁用）：宁可多弹一次说明，
 * 也不假装用户读过。键名带 `-v1`：说明改了口径就升到 `-v2`，让所有人重新确认一次。
 */
export const AUTOMATION_DISCLAIMER_STORAGE_KEY = 'dsh-mobile-automation-disclaimer-v1'

export function isAutomationDisclaimerAcknowledged(): boolean {
  try {
    return window.localStorage.getItem(AUTOMATION_DISCLAIMER_STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

/** 写不进去时返回 `false`（调用方照常往下走，只是下次还会再弹一次）。 */
export function acknowledgeAutomationDisclaimer(): boolean {
  try {
    window.localStorage.setItem(AUTOMATION_DISCLAIMER_STORAGE_KEY, '1')
    return true
  } catch {
    return false
  }
}
