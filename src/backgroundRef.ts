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
