/**
 * 第二阶段（最终形态）：背景只存**地址**，由原生按需流式供给。
 *
 * 复制文件这条路有三个绕不过去的问题：视频大小无上限、base64 过桥比原文件大三分之一、
 * 存一份就等于把用户的相册再抄一遍。所以这里只保留 SAF 给的 `content://` 地址
 * （带持久读授权），访客页面从 `/__shell/background` 同源取，由原生转发。
 */
export interface HarnessBackgroundRef {
  /** SAF 内容地址，形如 `content://com.android.providers.media.documents/document/video%3A42`。 */
  uri: string
  /** 原样记录提供者给出的 mime，例如 `video/mp4`。 */
  mime: string
  kind: 'image' | 'video'
}

const CONTENT_URI = /^content:\/\/[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{1,2048}$/u
const MIME = /^[a-z]+\/[a-z0-9.+-]{1,96}$/u

/**
 * 校验原生回来的背景地址。
 *
 * 只认 `content://`：`file://` 会把应用私有路径交给页面，`http(s)://` 会绕开
 * 「背景只来自本机」这个前提，两者都不该出现在这条路里。
 */
export function validateBackgroundRef(value: unknown): HarnessBackgroundRef {
  if (!value || typeof value !== 'object') throw new Error('背景地址无效')
  const data = value as Record<string, unknown>
  const uri = data.uri
  if (typeof uri !== 'string' || !CONTENT_URI.test(uri)) throw new Error('背景地址无效')
  const mime = data.mime
  if (typeof mime !== 'string' || !MIME.test(mime)) throw new Error('背景格式无效')
  const kind = mime.startsWith('video/') ? 'video' : mime.startsWith('image/') ? 'image' : null
  if (kind === null) throw new Error('背景只支持视频或图片')
  return { uri, mime, kind }
}

/** 背景在访客页面里的同源地址；与原生 `HarnessThemeBridge.BACKGROUND_PATH` 保持一致。 */
export const BACKGROUND_PATH = '/__shell/background'

/**
 * 同步背景的完整状态：开关 + 上次同步时间 + 已选文件。
 *
 * 原生的 `SharedPreferences` 是唯一真相；这里的镜像只做两件事：浏览器模式下也有持久值，
 * 以及首屏不必等过桥就能画出上次的选择。
 */
export interface HarnessBackgroundState {
  /** 「连背景一起同步」是否打开。 */
  syncBackground: boolean
  /** 上次同步时间；没有同步过是 0。 */
  syncedAt: number
  /** 已选背景；从未选过是 null（开关可能仍为 false）。 */
  background: HarnessBackgroundRef | null
}

const BACKGROUND_STATE_KEY = 'dsh.appearance.background-state'

/**
 * 校验同步背景状态。
 *
 * 开关只认布尔：字符串 `"true"` 或数字 1 说明序列化出了问题，宁可按无效处理，
 * 也不替用户猜一个「大概是开」的状态。
 */
export function validateBackgroundState(value: unknown): HarnessBackgroundState {
  if (!value || typeof value !== 'object') throw new Error('同步背景状态无效')
  const data = value as Record<string, unknown>
  if (typeof data.syncBackground !== 'boolean') throw new Error('同步背景状态无效')
  const syncedAt = typeof data.syncedAt === 'number' && Number.isFinite(data.syncedAt) && data.syncedAt >= 0 ? data.syncedAt : 0
  const background = data.background === null || data.background === undefined ? null : validateBackgroundRef(data.background)
  return { syncBackground: data.syncBackground, syncedAt, background }
}

function stateStore(): Storage | null {
  try { return window.localStorage } catch { return null }
}

/** 读本地镜像；内容坏掉时回到「未同步过」，不把异常抛给界面。 */
export function readBackgroundState(): HarnessBackgroundState {
  const raw = stateStore()?.getItem(BACKGROUND_STATE_KEY) ?? null
  if (!raw) return { syncBackground: false, syncedAt: 0, background: null }
  try { return validateBackgroundState(JSON.parse(raw)) } catch { return { syncBackground: false, syncedAt: 0, background: null } }
}

export function saveBackgroundState(state: HarnessBackgroundState): boolean {
  let checked: HarnessBackgroundState
  try { checked = validateBackgroundState(state) } catch { return false }
  const store = stateStore()
  if (!store) return false
  try { store.setItem(BACKGROUND_STATE_KEY, JSON.stringify(checked)) } catch { return false }
  return true
}

export function clearBackgroundState(): void {
  try { stateStore()?.removeItem(BACKGROUND_STATE_KEY) } catch { /* 存储不可用时只清当前页面 */ }
}
