import { readCustomTextColor } from './theme'

/**
 * 卡片外观 + 按钮/输入框的底色透明度。
 *
 * [controlOpacity] 与卡片不透明度分开：卡片管的是承载内容的底板，按钮与输入框是可直接
 * 操作的控件，二者对「看得清」的要求不同（控件半透明过头会连边界都找不到）。
 * 旧版本存储没有这个键，读取时按 100% 迁移，不改变既有外观。
 */
export interface CardAppearance { opacity: number; blur: number; color: string; controlOpacity: number }
const APPEARANCE_KEY = 'dsh-card-appearance-v1'
const APPEARANCE_EVENT = 'dsh-appearance-change'
export const BACKGROUND_EVENT = 'dsh-background-change'
export const DEFAULT_CARD: CardAppearance = { opacity: 100, blur: 0, color: '', controlOpacity: 100 }
const MAX_BACKGROUND_BYTES = 50 * 1024 * 1024
/** 已选背景的轻量标记（只存“有没有”，不存内容）。 */
const BACKGROUND_PRESENT_KEY = 'dsh-background-present-v1'

function validateCard(value: unknown): CardAppearance {
  if (!value || typeof value !== 'object') throw new Error('卡片参数无效')
  const data = value as Record<string, unknown>
  // 旧存储没有 controlOpacity：按 100% 迁移，不改变既有外观。
  const controlOpacity = data.controlOpacity === undefined ? DEFAULT_CARD.controlOpacity : data.controlOpacity
  if (typeof data.opacity !== 'number' || !Number.isInteger(data.opacity) || data.opacity < 30 || data.opacity > 100 ||
    typeof data.blur !== 'number' || !Number.isInteger(data.blur) || data.blur < 0 || data.blur > 24 ||
    typeof controlOpacity !== 'number' || !Number.isInteger(controlOpacity) || controlOpacity < 40 || controlOpacity > 100 ||
    typeof data.color !== 'string' || (data.color !== '' && !/^#[0-9a-f]{6}$/i.test(data.color))) throw new Error('卡片参数无效')
  return { opacity: data.opacity, blur: data.blur, color: data.color, controlOpacity }
}
export function readCard(): CardAppearance {
  try { return validateCard(JSON.parse(localStorage.getItem(APPEARANCE_KEY) ?? 'null')) } catch { return { ...DEFAULT_CARD } }
}
export function applyCard(value: CardAppearance): void {
  const checked = validateCard(value)
  const root = document.documentElement
  root.style.setProperty('--card-opacity', `${checked.opacity}%`)
  root.style.setProperty('--card-blur', `${checked.blur}px`)
  // 按钮与输入框共用这一条：两处控件各写一个变量，迟早出现「按钮透明了、输入框没跟上」。
  root.style.setProperty('--control-opacity', `${checked.controlOpacity}%`)
  if (checked.color) {
    root.style.setProperty('--card-color', checked.color)
    const [r, g, b] = [1, 3, 5].map(offset => parseInt(checked.color.slice(offset, offset + 2), 16))
    const dark = (r * 299 + g * 587 + b * 114) / 1000 < 150
    const customText = readCustomTextColor()
    root.style.setProperty('--card-ink', customText || (dark ? '#f4f7fb' : '#17202b'))
    root.style.setProperty('--card-muted', customText ? `color-mix(in srgb, ${customText} 68%, transparent)` : (dark ? '#c3cbd5' : '#475362'))
  } else {
    for (const name of ['--card-color', '--card-ink', '--card-muted']) root.style.removeProperty(name)
  }
}
export function saveCard(value: CardAppearance): void {
  const checked = validateCard(value)
  localStorage.setItem(APPEARANCE_KEY, JSON.stringify(checked))
  applyCard(checked)
  window.dispatchEvent(new Event(APPEARANCE_EVENT))
}

/** 同时检查大小、MIME 和文件签名，拒绝 HTML、SVG 及伪装的脚本文件。 */
async function backgroundType(file: Blob): Promise<string> {
  if (file.size < 12 || file.size > MAX_BACKGROUND_BYTES) throw new Error('背景文件须在 12 字节至 50 MB 之间')
  const bytes = new Uint8Array(await file.slice(0, 32).arrayBuffer())
  const ascii = (start: number, end: number) => String.fromCharCode(...bytes.slice(start, end))
  let type = ''
  if ([137, 80, 78, 71, 13, 10, 26, 10].every((byte, index) => bytes[index] === byte)) type = 'image/png'
  else if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) type = 'image/jpeg'
  else if (ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') type = 'image/webp'
  else if (['GIF87a', 'GIF89a'].includes(ascii(0, 6))) type = 'image/gif'
  else if (ascii(4, 8) === 'ftyp' && /^(isom|iso2|mp41|mp42|avc1|M4V )$/.test(ascii(8, 12))) type = 'video/mp4'
  else if ([26, 69, 223, 163].every((byte, index) => bytes[index] === byte)) type = 'video/webm'
  if (!type || (file.type !== '' && file.type !== type)) throw new Error('请选择有效的 JPG、PNG、WebP、GIF、MP4 或 WebM 文件')
  return type
}
function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('dsh-local-background-v1', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('background')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new Error('无法打开本地背景存储'))
    request.onblocked = () => reject(new Error('背景存储被占用，请关闭其他页面后重试'))
  })
}
interface EncryptedBackground { key: CryptoKey; iv: Uint8Array; ciphertext: ArrayBuffer; type: string }
async function transaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const database = await openDatabase()
  return new Promise((resolve, reject) => {
    const tx = database.transaction('background', mode)
    const request = operation(tx.objectStore('background'))
    tx.oncomplete = () => { database.close(); resolve(request.result) }
    tx.onabort = tx.onerror = () => { database.close(); reject(new Error('无法保存背景，请检查存储空间')) }
  })
}
export async function saveBackground(file: Blob): Promise<void> {
  const type = await backgroundType(file)
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, await file.arrayBuffer())
  await transaction('readwrite', store => store.put({ key, iv, ciphertext, type } satisfies EncryptedBackground, 'selected'))
  try { window.localStorage.setItem(BACKGROUND_PRESENT_KEY, '1') } catch { /* 隐私模式：状态退化为每次读存储 */ }
  window.dispatchEvent(new Event(BACKGROUND_EVENT))
}

/**
 * 本机是否存过背景的轻量标记。
 *
 * 页面状态文案只问「有没有选过」，不必每次解密整段视频；IndexedDB 读取失败时
 * 也不该把已经选好的背景说成没选。真正的画面仍只由 [readBackground] 决定。
 */
export function hasBackgroundFlag(): boolean {
  try { return window.localStorage.getItem(BACKGROUND_PRESENT_KEY) === '1' } catch { return false }
}
export async function readBackground(): Promise<Blob | null> {
  const entry = await transaction('readonly', store => store.get('selected') as IDBRequest<EncryptedBackground | undefined>)
  if (!entry) return null
  if (!(entry.ciphertext instanceof ArrayBuffer) || entry.ciphertext.byteLength > MAX_BACKGROUND_BYTES + 16 ||
    !['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'video/mp4', 'video/webm'].includes(entry.type)) throw new Error('背景文件无效')
  const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: entry.iv }, entry.key, entry.ciphertext)
  return new Blob([bytes], { type: entry.type })
}
export async function clearBackground(): Promise<void> {
  await transaction('readwrite', store => store.delete('selected'))
  try { window.localStorage.removeItem(BACKGROUND_PRESENT_KEY) } catch { /* 同上 */ }
  window.dispatchEvent(new Event(BACKGROUND_EVENT))
}
