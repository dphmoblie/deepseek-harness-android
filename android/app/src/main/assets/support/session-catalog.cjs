'use strict'

/**
 * 会话目录「只读元数据」读取器（访客内运行，一次性输出一行 JSON）。
 *
 * 用途：给壳页「会话工作区」提供真实会话的 `id` / `title` / `updatedAt`。
 * 由原生侧 `SessionCatalog.kt` 投放到访客 `/root/.dsh-mobile/` 下、用 `/opt/node/bin/node`
 * 执行，与 `runtime-self-check.cjs`、`plugin-manager.cjs` 走同一条投递与执行通道。
 *
 * 安全边界（改动前先读 `docs/壳页会话列表接线调研.md`）：
 * - **只读**：不写盘、不改权限、不移动文件，也不解压到磁盘（全程内存流式处理）。
 * - **只回元数据**：输出对象里只有 `id`（会话目录名可逆解码）、`title`（日志里最后一条
 *   `session/title` 事件的标题，或按宿主同款规则从首条用户消息折叠出的回退标题）与
 *   `updatedAt`（会话日志的修改时间）。**不含**会话正文、消息片段、工具输出、`cwd` 或任何路径。
 * - **回退标题只在没有标题事件时才去扫**：整份日志里一条 `session/title` 事件都没有、且日志被
 *   完整扫完（没撞上限）时，才取窗口内读到的首条用户消息折叠成回退标题；折叠结果最多 40 字节，
 *   折叠完原始文本立刻丢弃，因此正文不会随标题漏出去（`scripts/session-catalog.test.mjs` 用哨兵串钉住了这一点）。
 * - **扫描上限 `MAX_SCAN_BYTES`（1 MiB）**：每个会话最多解压读取这么多，到量立即停止读取并正常收尾；
 *   到量时仍未找到标题事件就**如实不返回标题**（界面显示「未命名会话」），绝不为了凑标题继续读。
 *   50 条 × 1 MiB 是最坏情况的解压量，换来的是「外壳读的范围尽量小」。
 * - 标题折叠规则与宿主 `@deepseek-ai/dsh-session-title` 对齐（`cleanTitleText` /
 *   `truncateTitleUtf8` / `fallbackSessionTitle`），配置取 `dsh-base` 的
 *   `fallbackMaxWords: 5`、`fallbackMaxBytes: 40`、`maxTitleBytes: 80`。
 * - **路径写死在访客内**（`/root/.dsh/sessions`）：宿主只传条数上限，不传任何路径。
 *
 * 用法：`node session-catalog.cjs [limit] [rootDir]`
 *   - `limit`   返回条数上限，默认 50，最大 50；
 *   - `rootDir` **仅供本机测试**（默认 `/root/.dsh/sessions`）；应用从不传它。
 *
 * 成功：stdout 一行 `{"sessions":[{id,title,updatedAt}...],"truncated":bool}`；
 * 失败：stdout 一行 `{"error":"<受控码>"}` 且退出码为 1。
 * `truncated` 为真表示「没读全」——会话条数超过上限，或本次时间预算用尽；
 * 它**不**表示「读不到」，读不到一律走 `error`，绝不用空列表冒充「没有会话」。
 */

const fs = require('node:fs')
const path = require('node:path')
const readline = require('node:readline')
const zlib = require('node:zlib')

/** 访客内会话根目录：`$DSH_HOME/sessions`，其中 `$DSH_HOME = /root/.dsh`。 */
const DEFAULT_ROOT = '/root/.dsh/sessions'
const DEFAULT_LIMIT = 50
const MAX_LIMIT = 50
/** 本次扫描的总时间预算（毫秒）：用尽时带上已读到的部分返回，并由 `truncated` 说明没读全。 */
const TIME_BUDGET_MS = 3000
/**
 * 单个会话最多解压读取的字节数（1 MiB）：到量立即停止读取并正常收尾。
 * 标题事件与「首条用户消息」都只在这个窗口里找；窗口内没扫到标题事件就不返回标题（显示「未命名会话」），
 * 不会为了凑标题继续往下读——读的范围越小，越贴合「外壳不读会话正文」这条线。
 */
const MAX_SCAN_BYTES = 1024 * 1024
/** 输出上限（字节）：超过即失败——宁可报一次可重试的失败，也不回一份被截断的列表。 */
const MAX_OUTPUT_BYTES = 64 * 1024
/** 兜底退出：进程因未知原因卡住时也要给宿主一行 JSON，而不是让宿主等到超时。 */
const FORCE_EXIT_MS = 5000
/** 标题折叠参数：与 `dsh-base` 的 `session-title` 配置一致。 */
const MAX_TITLE_BYTES = 80
const FALLBACK_MAX_WORDS = 5
const FALLBACK_MAX_BYTES = 40
/** 首条用户消息只用于折叠标题，拼接前先按字符截断，避免把长正文留在内存里。 */
const USER_MESSAGE_MAX_CHARS = 4096
const MAX_ID_CHARS = 200

/** 会话日志文件名：`session.jsonl`（旧 v0）或 `session.v<N>.jsonl`，zstd 编码再加 `.zstd`。 */
const SESSION_FILE = /^session(?:\.v([0-9]+))?\.jsonl(?:\.zstd)?$/
const SEGMENT_ESCAPE = /^[0-9A-Fa-f]{4}$/
const ID_FORBIDDEN = /[\u0000-\u001F\u007F/\\]/

// 以下四组正则与宿主 `dsh-session-title` 的 `cleanTitleText` 同序同义（去掉终端控制序列）。
const OSC_SEQUENCE = /\u001B\][\s\S]*?(?:\u0007|\u001B\\)/gu
const CSI_SEQUENCE = /\u001B\[[0-9;?]*[ -/]*[@-~]/gu
const ESCAPE_SEQUENCE = /\u001B[@-Z\\-_]/gu
const CONTROL_CHARACTER = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/gu
const DIRECTIONAL_CONTROL = /[\u200B\u200E\u200F\u202A-\u202E\u2060-\u2064\u2066-\u206F\uFEFF]/gu

/** 受控失败：`code` 会原样出现在 stdout 的 JSON 里，必须是固定枚举。 */
function controlledError(code) {
  const error = new Error(code)
  error.code = code
  return error
}

/** 去掉终端控制序列、控制字符与方向性控制字符，把空白折成单空格。 */
function cleanTitleText(input) {
  if (typeof input !== 'string') return ''
  return input
    .replace(OSC_SEQUENCE, '')
    .replace(CSI_SEQUENCE, '')
    .replace(ESCAPE_SEQUENCE, '')
    .replace(CONTROL_CHARACTER, '')
    .replace(DIRECTIONAL_CONTROL, '')
    .replace(/\s+/gu, ' ')
    .trim()
}

/** 按码点截断到 `maxBytes` 个 UTF-8 字节以内（不切断码点）。 */
function truncateTitleUtf8(input, maxBytes) {
  if (typeof input !== 'string' || input.length === 0) return ''
  let used = 0
  let result = ''
  for (const character of input) {
    const size = Buffer.byteLength(character, 'utf8')
    if (used + size > maxBytes) break
    used += size
    result += character
  }
  return result
}

/** 会话标题规范化：清洗 + 按 `maxBytes` 截断。 */
function normalizeSessionTitle(input, maxBytes) {
  return truncateTitleUtf8(cleanTitleText(input), maxBytes).trimEnd()
}

/** 回退标题：清洗后取前 `maxWords` 个词，再按 `maxBytes` 截断。 */
function fallbackSessionTitle(input, maxWords, maxBytes) {
  const words = cleanTitleText(input).split(' ').filter(Boolean).slice(0, maxWords).join(' ')
  return truncateTitleUtf8(words, maxBytes).trimEnd()
}

/**
 * 会话目录名 → 会话 id（可逆解码）。
 *
 * 编码规则来自 `@deepseek-ai/dsh-session-persistence-jsonl`：`[A-Za-z0-9._-]` 原样保留，
 * 其余码点转成 `~XXXX`（四位大写十六进制）；`.` → `~002E`。解码结果必须是像 id 的字符串，
 * 否则返回 `null`（调用方跳过该目录，不猜、不伪造）。
 */
function decodeSegment(name) {
  if (typeof name !== 'string' || name.length === 0 || name.length > MAX_ID_CHARS * 5) return null
  let decoded = ''
  let index = 0
  while (index < name.length) {
    const character = name[index]
    if (character === '~') {
      const hex = name.slice(index + 1, index + 5)
      if (!SEGMENT_ESCAPE.test(hex)) return null
      const codePoint = Number.parseInt(hex, 16)
      if (codePoint === 0 || codePoint > 0x10ffff) return null
      decoded += String.fromCodePoint(codePoint)
      index += 5
    } else {
      decoded += character
      index += 1
    }
  }
  if (decoded.length === 0 || decoded.length > MAX_ID_CHARS) return null
  if (ID_FORBIDDEN.test(decoded)) return null
  if (decoded === '.' || decoded === '..') return null
  return decoded
}

/** 目录项是不是「普通子目录」：跳过软链、文件与点开头的条目（含 `.sessions-trash`）。 */
function isPlainDirectory(entry) {
  if (entry === null || typeof entry !== 'object') return false
  if (typeof entry.isSymbolicLink === 'function' && entry.isSymbolicLink()) return false
  if (typeof entry.isDirectory !== 'function' || !entry.isDirectory()) return false
  const name = entry.name
  return typeof name === 'string' && name.length > 0 && name !== '.' && name !== '..' && !name.startsWith('.')
}

/**
 * 会话目录下的日志文件：一个会话在格式迁移期可能并存多个 generation，
 * 取**最高 generation**；同一 generation 同时存在压缩与未压缩时优先 `.zstd`（当前压缩方式）。
 * 返回 `{ log, updatedAt }`；没有可识别的日志文件时返回 `null`（该目录不算会话）。
 */
function sessionLog(directory) {
  let entries
  try {
    entries = fs.readdirSync(directory, { withFileTypes: true })
  } catch {
    return null
  }
  const files = []
  for (const entry of entries) {
    if (entry === null || typeof entry.isFile !== 'function' || !entry.isFile()) continue
    const match = typeof entry.name === 'string' ? SESSION_FILE.exec(entry.name) : null
    if (match === null) continue
    const full = path.join(directory, entry.name)
    let stats
    try {
      stats = fs.statSync(full)
    } catch {
      continue
    }
    if (!stats.isFile()) continue
    files.push({
      full,
      generation: match[1] === undefined ? 0 : Number.parseInt(match[1], 10),
      compressed: entry.name.endsWith('.zstd'),
      modifiedAt: Number.isFinite(stats.mtimeMs) ? Math.trunc(stats.mtimeMs) : 0,
    })
  }
  if (files.length === 0) return null
  let newest = 0
  for (const file of files) {
    if (file.modifiedAt > newest) newest = file.modifiedAt
  }
  const generation = Math.max(...files.map((file) => file.generation))
  const current = files.filter((file) => file.generation === generation)
  const compressed = current.filter((file) => file.compressed)
  return { log: compressed.length > 0 ? compressed[0] : current[0], updatedAt: newest }
}

/** 收集候选会话（只列目录 + 取 mtime，不读日志内容）。 */
function collectCandidates(root) {
  let projectEntries
  try {
    projectEntries = fs.readdirSync(root, { withFileTypes: true })
  } catch (error) {
    // 目录不存在 = 这台设备上还没有任何会话（dsh 懒创建 `sessions/`），不是「读不到」。
    if (error !== null && typeof error === 'object' && error.code === 'ENOENT') {
      return { candidates: [], unreadable: 0, missing: true }
    }
    throw error
  }
  const candidates = []
  let unreadable = 0
  for (const projectEntry of projectEntries) {
    if (!isPlainDirectory(projectEntry)) continue
    const projectPath = path.join(root, projectEntry.name)
    let sessionEntries
    try {
      sessionEntries = fs.readdirSync(projectPath, { withFileTypes: true })
    } catch {
      unreadable += 1
      continue
    }
    for (const sessionEntry of sessionEntries) {
      if (!isPlainDirectory(sessionEntry)) continue
      const id = decodeSegment(sessionEntry.name)
      if (id === null) continue
      const session = sessionLog(path.join(projectPath, sessionEntry.name))
      if (session === null) continue
      candidates.push({ id, log: session.log, updatedAt: session.updatedAt })
    }
  }
  return { candidates, unreadable, missing: false }
}

/** 从一条 `user/message` 事件里取出纯文本；只认 `source.kind === 'user'` 的用户输入。 */
function userMessageText(event) {
  const data = event.data
  if (data === null || typeof data !== 'object') return null
  const source = data.source
  if (source === null || typeof source !== 'object' || source.kind !== 'user') return null
  if (!Array.isArray(data.content)) return null
  let text = ''
  for (const block of data.content) {
    if (block === null || typeof block !== 'object') continue
    if (block.type !== 'text' || typeof block.text !== 'string') continue
    text += (text === '' ? '' : '\n') + block.text.slice(0, USER_MESSAGE_MAX_CHARS)
    if (text.length >= USER_MESSAGE_MAX_CHARS) break
  }
  return text
}

/**
 * 扫描一份会话日志，取出标题。
 *
 * 标题口径与宿主一致：**最后一条 `session/title` 事件**（`foldSessionTitle` 取 `findLast`）。
 * 回退标题只在**一条标题事件都没有、并且日志被完整扫完**时才折叠：取窗口内读到的首条用户消息，
 * 按 `dsh-base` 的配置折叠成最多 5 个词 / 40 字节。两者都取不到时返回空串（界面显示「未命名会话」）
 * ——不编造、也不用 cwd 冒充标题。
 *
 * 读取量受 `MAX_SCAN_BYTES` 与总时间预算双重约束：到量立即停止读取并正常收尾；**到量时仍未找到
 * 标题事件就不返回标题**，不会继续读下去凑一个。用户消息的正文（`data.content`）只有在确认
 * 「没有标题事件」之后才会被解析——有标题事件时永远不碰正文分支。
 *
 * `hooks` 仅供单测观察扫描行为（运行时从不传，默认零开销）：
 * - `onScanned(bytes)`：每读完一行报告一次累计解压字节数；
 * - `onContentParsed()`：真正去解析用户消息正文时调用一次（有标题事件时永不调用）。
 */
async function readTitle(log, deadline, hooks = null) {
  const raw = fs.createReadStream(log.full, { highWaterMark: 128 * 1024 })
  let source = raw
  if (log.compressed) {
    if (typeof zlib.createZstdDecompress !== 'function') {
      raw.destroy()
      throw controlledError('SESSION_CATALOG_ZSTD_UNSUPPORTED')
    }
    const decompressor = zlib.createZstdDecompress()
    raw.on('error', (error) => decompressor.destroy(error))
    source = raw.pipe(decompressor)
  }
  const lines = readline.createInterface({ input: source, crlfDelay: Infinity })
  let consumed = 0
  let lastTitle = null
  let fallbackLine = null
  // true 表示撞到扫描上限或时间预算，日志**没**读完：此时不允许再折叠回退标题。
  let exhausted = false
  try {
    for await (const line of lines) {
      consumed += Buffer.byteLength(line, 'utf8') + 1
      if (hooks !== null && typeof hooks.onScanned === 'function') hooks.onScanned(consumed)
      // 先用子串粗筛，绝大多数行（工具输出等）连 JSON.parse 都不需要。
      if (line.includes('"session/title"')) {
        const event = parseEvent(line)
        const data = event !== null && event.type === 'session/title' ? event.data : null
        if (data !== null && typeof data === 'object' && typeof data.title === 'string') {
          lastTitle = data.title
        }
      } else if (lastTitle === null && fallbackLine === null && line.includes('"user/message"')) {
        // 只留下这一行的原文；正文解析推迟到「确认没有标题事件」之后（见下方）。
        fallbackLine = line
      }
      if (consumed >= MAX_SCAN_BYTES || Date.now() > deadline) {
        exhausted = true
        break
      }
    }
  } finally {
    lines.close()
    raw.destroy()
    if (source !== raw) source.destroy()
  }
  const normalized = lastTitle === null ? '' : normalizeSessionTitle(lastTitle, MAX_TITLE_BYTES)
  if (normalized !== '') return normalized
  // 撞上限（或预算用尽）仍未找到标题事件：如实不返回标题，绝不继续读下去凑一个。
  if (exhausted || fallbackLine === null) return ''
  const event = parseEvent(fallbackLine)
  if (event === null || event.type !== 'user/message') return ''
  if (hooks !== null && typeof hooks.onContentParsed === 'function') hooks.onContentParsed()
  const text = userMessageText(event)
  if (text === null) return ''
  return fallbackSessionTitle(text, FALLBACK_MAX_WORDS, FALLBACK_MAX_BYTES)
}

/** 单行 JSON → 事件对象；解析失败或不是对象时返回 `null`（坏行跳过，不让整次读取失败）。 */
function parseEvent(line) {
  if (typeof line !== 'string' || line.length === 0 || line.charCodeAt(0) !== 0x7b) return null
  try {
    const event = JSON.parse(line)
    return event !== null && typeof event === 'object' ? event : null
  } catch {
    return null
  }
}

/** 主流程：列目录 → 按最近更新排序取前 N 条 → 逐条读标题。`hooks` 仅供单测观察（见 `readTitle`）。 */
async function buildCatalog(limit, root, hooks = null) {
  const started = Date.now()
  const deadline = started + TIME_BUDGET_MS
  const { candidates, unreadable, missing } = collectCandidates(root)
  if (missing) return { sessions: [], truncated: false }
  if (candidates.length === 0 && unreadable > 0) {
    // 有目录却一个都读不了：这是「读不到」，不是「没有会话」。
    throw controlledError('SESSION_CATALOG_UNREADABLE')
  }
  candidates.sort((left, right) => (right.updatedAt - left.updatedAt) || (left.id < right.id ? -1 : left.id > right.id ? 1 : 0))
  const selected = candidates.slice(0, limit)
  let truncated = candidates.length > selected.length
  const sessions = []
  for (const candidate of selected) {
    let title = ''
    try {
      title = await readTitle(candidate.log, deadline, hooks)
    } catch (error) {
      if (error !== null && typeof error === 'object' && error.code === 'SESSION_CATALOG_ZSTD_UNSUPPORTED') throw error
      // 单个会话读不动（权限、损坏）：仍然列出它的 id 与时间，只是没有标题。
      title = ''
    }
    sessions.push({ id: candidate.id, title, updatedAt: candidate.updatedAt })
    if (Date.now() > deadline) {
      truncated = true
      break
    }
  }
  return { sessions, truncated }
}

/** 参数解析：条数上限夹到 `[1, MAX_LIMIT]`，根目录必须是绝对路径（应用从不传它）。 */
function parseArguments(argv) {
  const rawLimit = Number.parseInt(argv[2] ?? '', 10)
  const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(rawLimit, 1), MAX_LIMIT) : DEFAULT_LIMIT
  const rawRoot = argv[3]
  if (rawRoot === undefined) return { limit, root: DEFAULT_ROOT }
  if (typeof rawRoot !== 'string' || rawRoot.length === 0 || rawRoot.includes('\u0000') || !path.isAbsolute(rawRoot)) {
    throw controlledError('SESSION_CATALOG_ROOT_INVALID')
  }
  return { limit, root: rawRoot }
}

/**
 * 输出一行 JSON 并定好退出码；`setTimeout` 兜底保证进程一定退出，
 * 返回值只用于单测（正常路径下进程会先结束）。
 */
function output(payload, code) {
  const text = JSON.stringify(payload)
  if (Buffer.byteLength(text, 'utf8') > MAX_OUTPUT_BYTES) {
    output({ error: 'SESSION_CATALOG_OUTPUT_TOO_LARGE' }, 1)
    return
  }
  const force = setTimeout(() => process.exit(code), FORCE_EXIT_MS)
  force.unref()
  process.stdout.write(text + '\n', () => process.exit(code))
}

async function main(argv) {
  const { limit, root } = parseArguments(argv)
  const catalog = await buildCatalog(limit, root)
  output(catalog, 0)
}

if (require.main === module) {
  main(process.argv).catch((error) => {
    const code = error !== null && typeof error === 'object' && typeof error.code === 'string' && /^SESSION_CATALOG_[A-Z_]+$/.test(error.code)
      ? error.code
      : 'SESSION_CATALOG_FAILED'
    output({ error: code }, 1)
  })
}

module.exports = { MAX_SCAN_BYTES, buildCatalog, cleanTitleText, decodeSegment, fallbackSessionTitle, normalizeSessionTitle, parseArguments, truncateTitleUtf8 }
