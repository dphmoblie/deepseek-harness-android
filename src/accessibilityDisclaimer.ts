/**
 * 「已读过免责说明」这一格确认：只在本机浏览器存储里记一个固定的 `'1'`。
 *
 * 它记的是「用户点过确认」这个事实，不带任何用户内容，也不参与任何权限判断——
 * 总开关能不能开仍由原生侧决定，这里只决定「要不要先把那段说明摆出来」。
 *
 * 读不出来时一律按「没确认过」处理（无痕模式、存储被禁用）：宁可多弹一次说明，
 * 也不假装用户读过。
 *
 * **版本号是这条确认的一部分**：键名里带着 `AUTOMATION_DISCLAIMER_VERSION`
 * （当前 `dsh-mobile-automation-disclaimer-v1`）。改写 `AutomationDisclaimer` 里那六条要点
 * 时，必须把版本号加一——键名随之变化，旧确认不再被读到，所有人会被重新问一遍。
 * 不做版本号的话，用户当年同意的那段文字被改掉之后，界面会拿旧确认当新条款的同意。
 */
export const AUTOMATION_DISCLAIMER_VERSION = 1

/** 指定版本的存储键；升版本号后旧键原样留在存储里，不影响读取（读的是新键）。 */
export function automationDisclaimerStorageKey(version: number = AUTOMATION_DISCLAIMER_VERSION): string {
  return `dsh-mobile-automation-disclaimer-v${version}`
}

export const AUTOMATION_DISCLAIMER_STORAGE_KEY = automationDisclaimerStorageKey()

export function isAutomationDisclaimerAcknowledged(version: number = AUTOMATION_DISCLAIMER_VERSION): boolean {
  try {
    return window.localStorage.getItem(automationDisclaimerStorageKey(version)) === '1'
  } catch {
    return false
  }
}

/** 写不进去时返回 `false`（调用方照常往下走，只是下次还会再弹一次）。 */
export function acknowledgeAutomationDisclaimer(version: number = AUTOMATION_DISCLAIMER_VERSION): boolean {
  try {
    window.localStorage.setItem(automationDisclaimerStorageKey(version), '1')
    return true
  } catch {
    return false
  }
}
