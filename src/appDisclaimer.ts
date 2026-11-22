/**
 * 应用级「免责条款」的首次同意记录：只在本机浏览器存储里记一个固定的 `'1'`。
 *
 * 与 `src/accessibilityDisclaimer.ts` 采用同一套版本化约定：
 * - 读不到或存储不可用时一律按「未同意」处理（fail-closed），宁可再弹一次也不假装用户同意过；
 * - 键名包含 `APP_DISCLAIMER_VERSION`（当前 `dsh-mobile-app-disclaimer-v2`）。若条款发生实质改写，
 *   将版本号加一即可让老用户重新确认新版条款，而不会把旧版同意直接套到新版条款上。
 */
export const APP_DISCLAIMER_VERSION = 2

/**
 * 应用级免责条款要点：首次启动门禁与「设置 → 免责条款」共用同一份条款正文。
 *
 * 实质改动以下要点时，必须把 `APP_DISCLAIMER_VERSION` 加一，使旧版同意自动失效并重新征询确认。
 */
export const APP_DISCLAIMER_POINTS = [
  '开源免费与按现状提供：本应用是基于 MIT 许可证发布的开源免费客户端软件，按「现状」提供。在适用法律允许的最大范围内，开发者不就本应用的适用性、准确性、连续性、稳定性或不侵权作任何明示或默示担保。官方不会通过本应用收取激活费或代装费。',
  'AI 输出与第三方模型服务：本应用仅为本地客户端工具，不直接提供 AI 模型服务。AI 自动生成的内容、代码与设备操作建议可能存在错误、幻觉或遗漏，不构成法律、医疗、金融等专业意见，请务必在使用或执行前自行核验。您自行配置的第三方模型服务、API 凭据、计费规则及数据处理，均由您与对应服务商依据其服务条款独立承担责任。',
  '本地运行环境与高权限风险：本应用在设备本地运行 Ubuntu/PRoot 环境，并按您主动开启的权限提供文件访问、Shizuku、无障碍自动化、副屏及 KernelSU Root 等可选能力。启用高权限功能或确认执行系统命令可能修改、删除本机数据或影响系统及应用稳定性。在适用法律允许的最大范围内（法律规定不可免除的责任，如因故意或重大过失造成的损害除外），因您自行授权、配置或执行操作导致的数据丢失、设备故障或衍生损失，由使用者自行承担。',
  '合法合规使用：您应严格遵守所适用司法管辖区的法律法规（含网络安全、数据安全与知识产权相关规定）及目标应用的服务条款。严禁将本应用及自动化、设备控制功能用于未授权访问、绕过付费或安全验证、侵害他人合法权益或任何违法违规用途。',
  '本地数据与隐私边界：本应用的配置、运行日志与会话记录默认保存在您的设备本地，开发者不设立云端服务器收集上述用户内容。当您主动将文本、文件、媒体或屏幕内容交由 AI 处理时，相关数据将直接发送至您所选的第三方模型供应商并受其隐私政策与跨境传输规则约束；请勿在未获合法授权或充分评估的情况下传输敏感个人信息或机密资料，并请定期自行备份重要数据。',
  '条款修改与版本更新：开发者有权根据适用法律法规变化、功能演进或安全需要修订本免责条款。如条款发生实质变更，本应用将通过应用内提示或版本更新予以公示，并在递增条款版本后重新征询您的同意；若您不同意修订内容，可随时停止使用并退出本应用。',
  '权利保留条款：开发者未行使或延迟行使本条款项下的任何权利，不构成对该等权利的放弃；本条款及 MIT 许可证未明确授予的一切权利（包括但不限于项目名称、标识等相关权益）均依法予以保留。同时，本条款不影响您依据所在法域强制性法律享有的法定权利。',
  '条款解释与效力：勾选同意并继续使用即表示您已充分阅读、理解并接受本免责条款。各小节标题仅为便于阅读，不影响条款实质含义；对本条款的说明与解释遵循诚实信用原则与通常理解，且不排除强制性法律规定的格式条款解释规则。若某一条款在特定司法管辖区被认定无效，不影响其余条款的效力。多语言版本仅供阅读参考；在不违反您所在法域强制性法律的前提下，不同语言版本存在理解差异时以中文简体文本为基准参考（适用法律要求以当地语言文本为准的，从其规定）。',
] as const

/** 指定条款版本的存储键；升版本号后旧键原样留在存储里，不影响新版本读取。 */
export function appDisclaimerStorageKey(version: number = APP_DISCLAIMER_VERSION): string {
  return `dsh-mobile-app-disclaimer-v${version}`
}

export const APP_DISCLAIMER_STORAGE_KEY = appDisclaimerStorageKey()

export function isAppDisclaimerAcknowledged(version: number = APP_DISCLAIMER_VERSION): boolean {
  try {
    return window.localStorage.getItem(appDisclaimerStorageKey(version)) === '1'
  } catch {
    return false
  }
}

/** 写不进去时返回 `false`（调用方照常放行本次会话，下次启动会再次弹出确认）。 */
export function acknowledgeAppDisclaimer(version: number = APP_DISCLAIMER_VERSION): boolean {
  try {
    window.localStorage.setItem(appDisclaimerStorageKey(version), '1')
    return true
  } catch {
    return false
  }
}
