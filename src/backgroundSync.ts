/**
 * 第二阶段：把背景一起同步给 DSH 页面。
 *
 * 走的是**缩小后的一次性 data URL**，而不是把文件或 `content://` 授权交给访客 WebView：
 * - 访客页面是运行时起的 HTTP 源，拿不到应用私有目录；给 `content://` 又要放开文件读取；
 * - 视频进访客页面意味着两个 WebView 同时解码，耗电且两边滚动不同步；
 * - 缩小到 1280 长边、JPEG 后一般 200–400 KB，作为 CSS 变量塞进去是可控的。
 *
 * 所以视频在这里只取首帧当静态背景用——与「主题色只读首帧」是同一套取舍。
 */

/** 同步过去的最长边：1280 在手机屏上足够，再大只是白占内存。 */
export const MAX_SYNC_EDGE = 1280
/** 只接受 JPEG：它没有透明通道，也不像 WebP 那样在老 WebView 上可能缺解码器。 */
export const SYNC_MIME = 'image/jpeg'
/** base64 载荷上限（约 1.5 MB 原图），超了就让调用方重新压。 */
export const MAX_SYNC_BASE64 = 2 * 1024 * 1024
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/u

export interface SyncedBackground {
  image: string
  mime: typeof SYNC_MIME
}

/** 纯计算：等比缩到最长边不超过 `maxEdge`，不放大小图。 */
export function targetSize(width: number, height: number, maxEdge = MAX_SYNC_EDGE): { width: number, height: number } {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return { width: 1, height: 1 }
  const longest = Math.max(width, height)
  if (longest <= maxEdge) return { width: Math.round(width), height: Math.round(height) }
  const scale = maxEdge / longest
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

/**
 * 校验过桥载荷：只允许 base64 字符、长度上限、固定 mime。
 *
 * 这条校验同时存在于 Web 侧与原生侧——它决定了一段字符串会不会被拼进
 * `evaluateJavascript` 的 `url("…")` 里。
 */
export function validateSyncedBackground(value: unknown): SyncedBackground {
  if (!value || typeof value !== 'object') throw new Error('背景参数无效')
  const data = value as Record<string, unknown>
  const image = data.image
  if (typeof image !== 'string' || image.length === 0 || image.length > MAX_SYNC_BASE64 || !BASE64.test(image)) {
    throw new Error('背景内容无效')
  }
  if (data.mime !== SYNC_MIME) throw new Error('背景格式无效')
  return { image, mime: SYNC_MIME }
}

/**
 * 把背景 Blob 压成可同步的 JPEG。
 *
 * 解码失败、画布不可用或体积仍超限时返回 null：调用方据此保留「只同步颜色」的结果，
 * 而不是把半张图送过去。
 */
export async function prepareBackgroundForSync(
  blob: Blob,
  options: { maxEdge?: number, quality?: number } = {},
): Promise<SyncedBackground | null> {
  // jsdom、部分老 WebView、以及没有 Blob URL 的宿主都不具备解码条件：如实返回 null。
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return null
  const maxEdge = options.maxEdge ?? MAX_SYNC_EDGE
  const quality = options.quality ?? 0.82
  const url = URL.createObjectURL(blob)
  try {
    const source = await decode(blob, url)
    if (!source) return null
    const { width, height } = targetSize(source.width, source.height, maxEdge)
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) return null
    // JPEG 没有 alpha：先铺白底，否则透明 PNG 同步过去会变成黑块。
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, width, height)
    context.drawImage(source, 0, 0, width, height)
    const dataUrl = canvas.toDataURL(SYNC_MIME, quality)
    const image = dataUrl.slice(dataUrl.indexOf(',') + 1)
    if (!image || image.length > MAX_SYNC_BASE64) return null
    return validateSyncedBackground({ image, mime: SYNC_MIME })
  } catch {
    return null
  } finally {
    URL.revokeObjectURL(url)
  }
}

type Decoded = ImageBitmap | HTMLImageElement | HTMLVideoElement

async function decode(blob: Blob, url: string): Promise<Decoded | null> {
  if (blob.type.startsWith('video/')) return firstFrame(url)
  if (typeof createImageBitmap === 'function') {
    try { return await createImageBitmap(blob) } catch { /* 退回 <img> */ }
  }
  return new Promise(resolve => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => resolve(null)
    image.src = url
  })
}

/** 视频只取首帧：访客页面不播放视频，避免两个 WebView 同时解码。 */
function firstFrame(url: string): Promise<HTMLVideoElement | null> {
  return new Promise(resolve => {
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.preload = 'auto'
    video.src = url
    const finish = (value: HTMLVideoElement | null) => resolve(value)
    video.onloadeddata = () => {
      try { video.currentTime = 0 } catch { finish(video) }
    }
    video.onseeked = () => finish(video)
    video.onerror = () => finish(null)
    setTimeout(() => finish(video.readyState >= 2 ? video : null), 3000)
  })
}
