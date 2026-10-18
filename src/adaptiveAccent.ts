/**
 * 自适应强调色：从背景图片 / 视频首帧里取一组颜色，算出适合当前明暗模式的强调色。
 *
 * 为什么不直接照搬最亮或最暗的那颗像素：背景图的极值几乎总是纯黑或纯白，落到按钮上要么
 * 眩光、要么在深色底上糊成一片。所以这里先按 HSL 把候选色收进「够深但不到黑、够艳但不刺眼」
 * 的区间，再用 WCAG 对比度挑前景色；挑不出来就整体调亮度，最后仍不达标就回退预置色。
 *
 * 这个文件只做纯计算与存储，不碰 DOM：取像素的那一层在 `extractAccentFromBackground` 里，
 * 它把 Blob 拆成 ImageData 后立刻交回给纯函数，测试只驱动纯函数即可。
 */

/** 强调色来源：预置色板 / 用户指定 / 从背景自适应。三者互斥，各自独立存储。 */
export type AccentSource = 'default' | 'custom' | 'adaptive'

export const ACCENT_SOURCE_KEY = 'dsh-mobile-accent-mode-v2'
export const ADAPTIVE_ACCENT_KEY = 'dsh-mobile-adaptive-accent-v1'
export const ACCENT_SYNC_KEY = 'dsh-mobile-accent-sync-v1'

const SOURCES: readonly AccentSource[] = ['default', 'custom', 'adaptive']
const HEX_COLOR = /^#[0-9a-f]{6}$/i

/** 自适应结果：强调色 + 前景色 + 供 DSH 同步用的面板色。 */
export interface AdaptiveAccent {
  accent: string
  onAccent: string
  background: string
  surface: string
  surfaceOpacity: number
  source: 'image' | 'video'
  extractedAt: number
}

/** 同步给 DSH 页面的受控主题对象：字段固定，不含任何 CSS / JS / URL。 */
export interface SyncedTheme {
  mode: 'light' | 'dark' | 'system'
  accent: string
  background: string
  surface: string
  surfaceOpacity: number
}

export interface AccentSync {
  syncedAt: number
  theme: SyncedTheme
}

/** 亮度与饱和度区间：低于下限会糊进底色，高于上限会在深色模式里发光。 */
export const ACCENT_LIGHTNESS_RANGE = [0.34, 0.62] as const
export const ACCENT_SATURATION_RANGE = [0.42, 0.88] as const
/** 按钮前景色与底色的最低对比度（WCAG AA 正文标准）。 */
export const MIN_CONTRAST = 4.5

export function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && HEX_COLOR.test(value)
}

export function isAccentSource(value: unknown): value is AccentSource {
  return typeof value === 'string' && (SOURCES as readonly string[]).includes(value)
}

export function hexToRgb(hex: string): [number, number, number] {
  if (!isHexColor(hex)) throw new Error('颜色必须是 #RRGGBB')
  return [1, 3, 5].map(offset => Number.parseInt(hex.slice(offset, offset + 2), 16)) as [number, number, number]
}

export function rgbToHex(red: number, green: number, blue: number): string {
  const clamp = (value: number) => Math.max(0, Math.min(255, Math.round(value)))
  return '#' + [red, green, blue].map(channel => clamp(channel).toString(16).padStart(2, '0')).join('')
}

/** CSS Color 4 的相对亮度：先做 sRGB 反伽马，再按人眼敏感度加权。 */
export function relativeLuminance(hex: string): number {
  const [red, green, blue] = hexToRgb(hex).map(channel => {
    const value = channel / 255
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/** WCAG 对比度：(亮 + 0.05) / (暗 + 0.05)，取值 1..21。 */
export function contrastRatio(first: string, second: string): number {
  const a = relativeLuminance(first)
  const b = relativeLuminance(second)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

/** 前景色二选一：黑白里对比度更高的那个。 */
export function pickOnColor(background: string): '#000000' | '#ffffff' {
  return contrastRatio(background, '#000000') >= contrastRatio(background, '#ffffff') ? '#000000' : '#ffffff'
}

export function rgbToHsl(hex: string): [number, number, number] {
  const [red, green, blue] = hexToRgb(hex).map(channel => channel / 255)
  const max = Math.max(red, green, blue)
  const min = Math.min(red, green, blue)
  const lightness = (max + min) / 2
  if (max === min) return [0, 0, lightness]
  const delta = max - min
  const saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min)
  let hue: number
  if (max === red) hue = ((green - blue) / delta + (green < blue ? 6 : 0)) / 6
  else if (max === green) hue = ((blue - red) / delta + 2) / 6
  else hue = ((red - green) / delta + 4) / 6
  return [hue, saturation, lightness]
}

export function hslToHex(hue: number, saturation: number, lightness: number): string {
  const wrap = ((hue % 1) + 1) % 1
  if (saturation === 0) return rgbToHex(lightness * 255, lightness * 255, lightness * 255)
  const q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation
  const p = 2 * lightness - q
  const channel = (offset: number) => {
    let t = wrap + offset
    if (t < 0) t += 1
    if (t > 1) t -= 1
    if (t < 1 / 6) return p + (q - p) * 6 * t
    if (t < 1 / 2) return q
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
    return p
  }
  return rgbToHex(channel(1 / 3) * 255, channel(0) * 255, channel(-1 / 3) * 255)
}

/**
 * 把任意颜色收进可用区间：保留色相，亮度与饱和度夹到
 * [ACCENT_LIGHTNESS_RANGE] / [ACCENT_SATURATION_RANGE]。
 */
export function clampAccent(hex: string): string {
  const [hue, saturation, lightness] = rgbToHsl(hex)
  const clamp = (value: number, [low, high]: readonly [number, number]) => Math.max(low, Math.min(high, value))
  return hslToHex(hue, clamp(saturation, ACCENT_SATURATION_RANGE), clamp(lightness, ACCENT_LIGHTNESS_RANGE))
}

/**
 * 从像素里挑主色：按 32 级量化后统计权重，跳过透明像素与「近黑近白」的像素
 * （它们通常是边框、文字或留白，落地成按钮色会很难看）。
 *
 * 权重相同时取更鲜艳的那个：饱和度高的小面积色块更像是背景的“主题色”，
 * 而灰阶面积大往往只是阴影。
 */
export function deriveAccentFromPixels(pixels: Uint8ClampedArray | Uint8Array, step = 4): string | null {
  const buckets = new Map<number, { count: number, red: number, green: number, blue: number }>()
  const stride = Math.max(1, Math.floor(step))
  for (let index = 0; index + 3 < pixels.length; index += 4 * stride) {
    const alpha = pixels[index + 3]
    if (alpha < 128) continue
    const red = pixels[index]
    const green = pixels[index + 1]
    const blue = pixels[index + 2]
    const [, saturation, lightness] = rgbToHsl(rgbToHex(red, green, blue))
    if (lightness < 0.08 || lightness > 0.94 || saturation < 0.12) continue
    const key = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4)
    const bucket = buckets.get(key) ?? { count: 0, red: 0, green: 0, blue: 0 }
    bucket.count += 1
    bucket.red += red
    bucket.green += green
    bucket.blue += blue
    buckets.set(key, bucket)
  }
  let best: { score: number, hex: string } | null = null
  for (const bucket of buckets.values()) {
    const hex = rgbToHex(bucket.red / bucket.count, bucket.green / bucket.count, bucket.blue / bucket.count)
    const [, saturation] = rgbToHsl(hex)
    const score = bucket.count * (0.5 + saturation)
    if (!best || score > best.score) best = { score, hex }
  }
  return best ? clampAccent(best.hex) : null
}

/**
 * 保证前景与底色够清楚：先在黑白里挑，仍不足 [MIN_CONTRAST] 就把强调色往两个方向
 * 各试若干档亮度，取第一个达标的；都不达标返回 null，交给调用方回退预置色。
 */
export function ensureContrast(accent: string): { accent: string, onAccent: string } | null {
  const [hue, saturation, lightness] = rgbToHsl(accent)
  const candidates = [lightness, lightness - 0.08, lightness + 0.08, lightness - 0.16, lightness + 0.16, ACCENT_LIGHTNESS_RANGE[0], ACCENT_LIGHTNESS_RANGE[1]]
  for (const candidate of candidates) {
    const hex = hslToHex(hue, Math.max(ACCENT_SATURATION_RANGE[0], Math.min(ACCENT_SATURATION_RANGE[1], saturation)), Math.max(0, Math.min(1, candidate)))
    const onColor = pickOnColor(hex)
    if (contrastRatio(hex, onColor) >= MIN_CONTRAST) return { accent: hex, onAccent: onColor }
  }
  return null
}

/**
 * 面板色：以强调色为引子，按明暗模式混出背景与卡片。
 *
 * 深色模式压到底色附近（混 12% 强调色），浅色模式提到接近白（混 8%），
 * 这样同步给 DSH 之后，页面底色和外壳卡片是同一族色，而不是两套配色。
 */
export function deriveSurface(accent: string, theme: 'light' | 'dark'): { background: string, surface: string } {
  const [hue, saturation] = rgbToHsl(accent)
  const tone = (lightness: number, weight: number) => hslToHex(hue, Math.max(0.06, Math.min(0.3, saturation * weight)), lightness)
  return theme === 'dark'
    ? { background: tone(0.09, 0.5), surface: tone(0.13, 0.6) }
    : { background: tone(0.965, 0.35), surface: tone(0.985, 0.3) }
}

/** 完整推导：像素 → 强调色 + 前景 + 面板色。推不出来返回 null（调用方回退预置色）。 */
export function deriveAdaptiveAccent(
  pixels: Uint8ClampedArray | Uint8Array,
  options: { theme: 'light' | 'dark', source: 'image' | 'video', surfaceOpacity: number, now?: number },
): AdaptiveAccent | null {
  const picked = deriveAccentFromPixels(pixels)
  if (!picked) return null
  const checked = ensureContrast(picked)
  if (!checked) return null
  const { background, surface } = deriveSurface(checked.accent, options.theme)
  return {
    accent: checked.accent,
    onAccent: checked.onAccent,
    background,
    surface,
    surfaceOpacity: Math.round(Math.max(0, Math.min(100, options.surfaceOpacity))),
    source: options.source,
    extractedAt: options.now ?? Date.now(),
  }
}

function storage(): Storage | null {
  try { return window.localStorage } catch { return null }
}

export function readAccentSource(): AccentSource {
  const value = storage()?.getItem(ACCENT_SOURCE_KEY) ?? null
  return isAccentSource(value) ? value : 'default'
}

export function saveAccentSource(value: AccentSource): boolean {
  if (!isAccentSource(value)) return false
  const store = storage()
  if (!store) return false
  try { store.setItem(ACCENT_SOURCE_KEY, value) } catch { return false }
  return true
}

function validateAdaptive(value: unknown): AdaptiveAccent {
  if (!value || typeof value !== 'object') throw new Error('自适应主题参数无效')
  const data = value as Record<string, unknown>
  const source = data.source
  if (!isHexColor(data.accent) || !isHexColor(data.onAccent) || !isHexColor(data.background) || !isHexColor(data.surface) ||
    typeof data.surfaceOpacity !== 'number' || !Number.isInteger(data.surfaceOpacity) || data.surfaceOpacity < 0 || data.surfaceOpacity > 100 ||
    (source !== 'image' && source !== 'video') ||
    typeof data.extractedAt !== 'number' || !Number.isFinite(data.extractedAt)) throw new Error('自适应主题参数无效')
  return {
    accent: data.accent,
    onAccent: data.onAccent,
    background: data.background,
    surface: data.surface,
    surfaceOpacity: data.surfaceOpacity,
    source,
    extractedAt: data.extractedAt,
  }
}

export function readAdaptiveAccent(): AdaptiveAccent | null {
  const raw = storage()?.getItem(ADAPTIVE_ACCENT_KEY) ?? null
  if (!raw) return null
  try { return validateAdaptive(JSON.parse(raw)) } catch { return null }
}

export function saveAdaptiveAccent(value: AdaptiveAccent): boolean {
  let checked: AdaptiveAccent
  try { checked = validateAdaptive(value) } catch { return false }
  const store = storage()
  if (!store) return false
  try { store.setItem(ADAPTIVE_ACCENT_KEY, JSON.stringify(checked)) } catch { return false }
  return true
}

export function clearAdaptiveAccent(): void {
  try { storage()?.removeItem(ADAPTIVE_ACCENT_KEY) } catch { /* 存储不可用时只清当前页面 */ }
}

/** 校验同步主题对象：字段固定、类型受控，任何额外内容都不进存储。 */
export function validateSyncedTheme(value: unknown): SyncedTheme {
  if (!value || typeof value !== 'object') throw new Error('同步主题参数无效')
  const data = value as Record<string, unknown>
  if ((data.mode !== 'light' && data.mode !== 'dark' && data.mode !== 'system') ||
    !isHexColor(data.accent) || !isHexColor(data.background) || !isHexColor(data.surface) ||
    typeof data.surfaceOpacity !== 'number' || !Number.isInteger(data.surfaceOpacity) || data.surfaceOpacity < 0 || data.surfaceOpacity > 100) {
    throw new Error('同步主题参数无效')
  }
  return {
    mode: data.mode,
    accent: data.accent,
    background: data.background,
    surface: data.surface,
    surfaceOpacity: data.surfaceOpacity,
  }
}

export function readAccentSync(): AccentSync | null {
  const raw = storage()?.getItem(ACCENT_SYNC_KEY) ?? null
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (typeof parsed.syncedAt !== 'number' || !Number.isFinite(parsed.syncedAt)) return null
    return { syncedAt: parsed.syncedAt, theme: validateSyncedTheme(parsed.theme) }
  } catch { return null }
}

export function saveAccentSync(theme: SyncedTheme, now = Date.now()): AccentSync | null {
  let checked: SyncedTheme
  try { checked = validateSyncedTheme(theme) } catch { return null }
  const store = storage()
  if (!store) return null
  const record: AccentSync = { syncedAt: now, theme: checked }
  try { store.setItem(ACCENT_SYNC_KEY, JSON.stringify(record)) } catch { return null }
  return record
}

export function clearAccentSync(): void {
  try { storage()?.removeItem(ACCENT_SYNC_KEY) } catch { /* 同上 */ }
}

/**
 * 从背景 Blob 取像素：图片直接解码，视频只读首帧。
 *
 * 缩到 64×64 再统计：既够判断主色，也把解码与遍历成本压到可以忽略。
 * `createImageBitmap` 不可用（老 WebView）时退回 `<img>`，两者都失败就返回 null。
 */
export async function extractAccentFromBackground(
  blob: Blob,
  options: { theme: 'light' | 'dark', surfaceOpacity: number, now?: number },
): Promise<AdaptiveAccent | null> {
  const isVideo = blob.type.startsWith('video/')
  const pixels = await readPixels(blob, isVideo)
  if (!pixels) return null
  return deriveAdaptiveAccent(pixels, { ...options, source: isVideo ? 'video' : 'image' })
}

const SAMPLE_SIZE = 64

async function readPixels(blob: Blob, isVideo: boolean): Promise<Uint8ClampedArray | null> {
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return null
  const url = URL.createObjectURL(blob)
  try {
    const canvas = document.createElement('canvas')
    canvas.width = SAMPLE_SIZE
    canvas.height = SAMPLE_SIZE
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) return null
    if (isVideo) {
      const frame = await firstVideoFrame(url)
      if (!frame) return null
      context.drawImage(frame, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
    } else {
      const image = await decodeImage(url)
      if (!image) return null
      context.drawImage(image, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
    }
    return context.getImageData(0, 0, SAMPLE_SIZE, SAMPLE_SIZE).data
  } catch {
    return null
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function decodeImage(url: string): Promise<ImageBitmap | HTMLImageElement | null> {
  if (typeof createImageBitmap === 'function') {
    try { return await createImageBitmap(await (await fetch(url)).blob()) } catch { /* 退回 <img> */ }
  }
  return new Promise(resolve => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => resolve(null)
    image.src = url
  })
}

/** 视频只取首帧：实时跟随每一帧会带来颜色闪烁、额外耗电和 WebView 重绘。 */
function firstVideoFrame(url: string): Promise<HTMLVideoElement | null> {
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
    // 有些 WebM 首帧的解码事件顺序不同：给一个兜底超时，宁可回退也不要卡住界面。
    setTimeout(() => finish(video.readyState >= 2 ? video : null), 3000)
  })
}
