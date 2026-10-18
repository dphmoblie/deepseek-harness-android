/**
 * 第二阶段（视频）：同一源下流式播放外壳背景。
 *
 * 上一版把图片压成 data URL 塞进 CSS，视频走这条路会炸：base64 比原文件大 1/3，
 * 一段 1080p 片段就是几十 MB 的 JS 字符串，桥和 WebView 都撑不住。
 *
 * 改用**同源路径**：原生在 `shouldInterceptRequest` 里把 `/__shell/background.mp4`
 * 映射到应用私有目录里的文件（支持 Range），访客页面用 `<video>` 正常加载它——
 * 对页面来说这就是同源资源，CSP 的 `media-src 'self'` 直接通过，也不用放开文件权限。
 *
 * 上传仍然走桥，但按块：每块 192 KB，原生追加到临时文件，最后一次性收尾。
 */

/** 视频上限：够 10–20 秒的背景循环，再大就只是在手机上烧内存。 */
export const MAX_VIDEO_BYTES = 32 * 1024 * 1024
/** 每块原始字节数；base64 后约 256 KB，单次过桥是安全的。 */
export const SYNC_CHUNK_BYTES = 192 * 1024
export const SYNC_VIDEO_MIME = 'video/mp4'
export const SYNC_IMAGE_MIME = 'image/jpeg'
export const BACKGROUND_PATH = '/__shell/background.mp4'

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/u
const MAX_CHUNK_BASE64 = 512 * 1024

export interface BackgroundUpload {
  mime: string
  index: number
  data: string
  done: boolean
}

/** 校验过桥的每一块：只允许 base64 字符、限长、序号从 0 起。 */
export function validateBackgroundUpload(value: unknown): BackgroundUpload {
  if (!value || typeof value !== 'object') throw new Error('背景分块无效')
  const data = value as Record<string, unknown>
  const mime = data.mime
  if (mime !== SYNC_VIDEO_MIME && mime !== SYNC_IMAGE_MIME) throw new Error('背景格式无效')
  const index = data.index
  if (typeof index !== 'number' || !Number.isInteger(index) || index < 0 || index > 4096) throw new Error('背景分块序号无效')
  const payload = data.data
  if (typeof payload !== 'string' || payload.length === 0 || payload.length > MAX_CHUNK_BASE64 || !BASE64.test(payload)) {
    throw new Error('背景分块内容无效')
  }
  if (typeof data.done !== 'boolean') throw new Error('背景分块状态无效')
  return { mime, index, data: payload, done: data.done }
}

/** 二进制转 base64 时先分小段：`String.fromCharCode(...bytes)` 在 20 万参数上会爆栈。 */
export function toBase64(bytes: Uint8Array): string {
  let binary = ''
  const step = 8192
  for (let offset = 0; offset < bytes.length; offset += step) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + step))
  }
  return btoa(binary)
}

/**
 * 读一块的字节。
 *
 * jsdom 里的 `Blob` 没有 `arrayBuffer()`，老 WebView 也可能只有 `FileReader`，
 * 所以两条路都留着；都失败时如实抛错，不返回空块。
 */
function sliceBytes(slice: Blob): Promise<Uint8Array> {
  if (typeof slice.arrayBuffer === 'function') {
    return slice.arrayBuffer().then(buffer => new Uint8Array(buffer))
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(new Error('读取背景失败'))
    reader.readAsArrayBuffer(slice)
  })
}

/**
 * 按块上传背景，返回实际发送的字节数与类型。
 *
 * 超过上限、类型不受支持或中途失败时抛错——调用方据此保留原来的背景，
 * 而不是留下一个只有前半段文件的坏状态。
 */
export async function uploadBackgroundInChunks(
  blob: Blob,
  send: (chunk: BackgroundUpload) => Promise<void>,
  options: { chunkBytes?: number, maxBytes?: number } = {},
): Promise<{ kind: 'image' | 'video', bytes: number }> {
  const kind = blob.type.startsWith('video/') ? 'video' : 'image'
  const mime = kind === 'video' ? SYNC_VIDEO_MIME : SYNC_IMAGE_MIME
  const maxBytes = options.maxBytes ?? MAX_VIDEO_BYTES
  const chunkBytes = options.chunkBytes ?? SYNC_CHUNK_BYTES
  if (blob.size === 0) throw new Error('背景文件是空的')
  if (blob.size > maxBytes) throw new Error(`背景文件过大，上限 ${Math.round(maxBytes / 1024 / 1024)} MB`)
  let index = 0
  for (let offset = 0; offset < blob.size; offset += chunkBytes) {
    const slice = blob.slice(offset, Math.min(offset + chunkBytes, blob.size))
    const bytes = await sliceBytes(slice)
    const done = offset + chunkBytes >= blob.size
    await send(validateBackgroundUpload({ mime, index, data: toBase64(bytes), done }))
    index += 1
  }
  return { kind, bytes: blob.size }
}

/** 页面侧的媒体标记：原生注入的样式按它决定用图片还是视频层。 */
export function backgroundMediaKind(mime: string): 'image' | 'video' | null {
  if (mime === SYNC_VIDEO_MIME) return 'video'
  if (mime === SYNC_IMAGE_MIME) return 'image'
  return null
}
