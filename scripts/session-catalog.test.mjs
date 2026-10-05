import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, rmSync, statSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import zlib from 'node:zlib'
import catalog from '../android/app/src/main/assets/support/session-catalog.cjs'

const { MAX_SCAN_BYTES, buildCatalog, decodeSegment, fallbackSessionTitle, normalizeSessionTitle, parseArguments } = catalog

/** 哨兵串：它只出现在会话正文/工具输出/未使用的路径里，任何一次「正文外传」都会让它出现在输出里。 */
const SENTINEL = 'ZZ-SESSION-BODY-SENTINEL-ZZ'
/** 被测脚本本体（与出厂投放进访客的是同一份文件）。 */
const SCRIPT_FILE = fileURLToPath(new URL('../android/app/src/main/assets/support/session-catalog.cjs', import.meta.url))
const BASE_TIME = 1_700_000_000_000
const ZSTD_AVAILABLE = typeof zlib.zstdCompressSync === 'function' && typeof zlib.createZstdDecompress === 'function'

const roots = []
after(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true })
})

function createRoot() {
  const root = mkdtempSync(join(tmpdir(), 'session-catalog-'))
  roots.push(root)
  return root
}

function jsonl(lines) {
  return lines.map((line) => (typeof line === 'string' ? line : JSON.stringify(line))).join('\n') + '\n'
}

/**
 * 写一个会话目录。
 * 头部行故意写成裸对象（脚本只认 `session/title` 与 `user/message`，头部一律忽略），
 * 并带上敏感字段（cwd、正文哨兵）用于验证「不外传」。
 */
function addSession(root, options) {
  const {
    project = '-root-project',
    directory,
    lines,
    updatedAt = BASE_TIME,
    compressed = false,
    file = compressed ? 'session.v4.jsonl.zstd' : 'session.v4.jsonl',
  } = options
  const dir = join(root, project, directory)
  mkdirSync(dir, { recursive: true })
  const target = join(dir, file)
  const text = jsonl([{ version: 4, id: directory, createdAt: BASE_TIME, isSeeded: false, delegationDepth: 0, cwd: `/root/secret-project/${SENTINEL}` }, ...lines])
  writeFileSync(target, compressed ? zlib.zstdCompressSync(Buffer.from(text, 'utf8')) : text)
  const stamp = new Date(updatedAt)
  utimesSync(target, stamp, stamp)
  return target
}

function userMessage(text, kind = 'user') {
  return { type: 'user/message', data: { source: { kind }, content: [{ type: 'text', text }] } }
}

function title(text) {
  return { type: 'session/title', data: { title: text } }
}

function toolResult(text) {
  return { type: 'tool/result', data: { content: [{ type: 'text', text }] } }
}

test('只回元数据：标题取最后一条 session/title，正文与路径不外传', async () => {
  const root = createRoot()
  addSession(root, {
    project: '-root-secret-project',
    directory: 'session-aaaa1111',
    updatedAt: BASE_TIME + 5000,
    lines: [
      title('旧标题'),
      userMessage(`用户输入的正文 ${SENTINEL}`),
      toolResult(`工具输出里的正文 ${SENTINEL}`),
      userMessage(`${SENTINEL} 来源不是用户的输入`, 'tool'),
      title('新标题'),
    ],
  })
  if (!ZSTD_AVAILABLE) return
  addSession(root, {
    directory: 'session-bbbb2222',
    updatedAt: BASE_TIME + 9000,
    compressed: true,
    lines: [userMessage('alpha beta gamma delta epsilon zeta eta')],
  })
  const report = await buildCatalog(50, root)
  assert.equal(report.truncated, false)
  assert.equal(report.sessions.length, 2)
  const plain = report.sessions.find((session) => session.id === 'session-aaaa1111')
  const compressed = report.sessions.find((session) => session.id === 'session-bbbb2222')
  assert.equal(plain?.title, '新标题')
  // 没有标题事件时按 `dsh-base` 的配置折叠回退标题：前 5 个词、最多 40 字节。
  assert.equal(compressed?.title, 'alpha beta gamma delta epsilon')
  // 排序按最近更新倒序，且 updatedAt 来自日志文件修改时间。
  assert.deepEqual(report.sessions.map((session) => session.id), ['session-bbbb2222', 'session-aaaa1111'])
  assert.ok(Math.abs(compressed.updatedAt - (BASE_TIME + 9000)) < 2000)
  // 白名单：只有这三个字段；正文、cwd、文件名一律不出现。
  for (const session of report.sessions) {
    assert.deepEqual(Object.keys(session).sort(), ['id', 'title', 'updatedAt'])
  }
  const serialized = JSON.stringify(report)
  assert.ok(!serialized.includes(SENTINEL), '正文哨兵不得出现在输出里')
  assert.ok(!serialized.includes('secret-project'), '路径不得出现在输出里')
  assert.ok(!serialized.includes('.dsh'), '访客路径不得出现在输出里')
  assert.ok(!serialized.includes('session.v4.jsonl'), '会话文件名不得出现在输出里')
})

test('没有标题事件且日志超过扫描上限时，如实不返回标题（也不超限读取）', async () => {
  const root = createRoot()
  const filler = toolResult(`填充内容 ${'x'.repeat(200)}`)
  const fillerBytes = Buffer.byteLength(JSON.stringify(filler), 'utf8') + 1
  // 把「首条用户消息」推到扫描上限之后：脚本若真的整条读下去，就会折出一个回退标题来。
  const lines = new Array(Math.ceil((MAX_SCAN_BYTES * 1.3) / fillerBytes)).fill(filler)
  lines.push(userMessage('alpha beta gamma delta epsilon zeta'))
  const file = addSession(root, { directory: 'session-huge', lines })
  let scanned = 0
  const report = await buildCatalog(50, root, { onScanned: (bytes) => { scanned = bytes } })
  assert.equal(report.sessions.length, 1)
  assert.equal(report.sessions[0].title, '', '撞上限且没有标题事件时必须如实不返回标题')
  const fileBytes = statSync(file).size
  assert.ok(fileBytes > MAX_SCAN_BYTES, `夹具本身要大于扫描上限，实际 ${fileBytes}`)
  assert.ok(scanned < fileBytes, `不得把整份日志读完：读了 ${scanned}，共 ${fileBytes}`)
  assert.ok(scanned < MAX_SCAN_BYTES * 1.05, `读取量必须贴着上限收口，实际 ${scanned}`)
})

test('已有 session/title 事件时不解析用户消息正文', async () => {
  const root = createRoot()
  addSession(root, {
    directory: 'session-titled',
    lines: [
      // 标题事件之前也放一条用户消息：无论标题事件在前还是在后，正文分支都不该被触发。
      userMessage(`标题事件之前的正文 ${SENTINEL}`),
      title('来自标题事件的标题'),
      userMessage(`标题事件之后的正文 ${SENTINEL}`),
      toolResult(`工具输出里的正文 ${SENTINEL}`),
    ],
  })
  let parsed = 0
  const report = await buildCatalog(50, root, { onContentParsed: () => { parsed += 1 } })
  assert.equal(report.sessions.length, 1)
  assert.equal(report.sessions[0].title, '来自标题事件的标题')
  assert.equal(parsed, 0, '有标题事件时永远不得解析用户消息正文（content 分支）')
  assert.ok(!JSON.stringify(report).includes(SENTINEL))

  // 对照：去掉标题事件后，才允许解析一次正文来折叠回退标题。
  const control = createRoot()
  addSession(control, {
    directory: 'session-untitled',
    lines: [userMessage('alpha beta gamma delta epsilon zeta'), toolResult(SENTINEL)],
  })
  let controlParsed = 0
  const fallback = await buildCatalog(50, control, { onContentParsed: () => { controlParsed += 1 } })
  assert.equal(fallback.sessions[0].title, 'alpha beta gamma delta epsilon')
  assert.equal(controlParsed, 1)
})

test('一个会话有多个 generation 时取最高版本', async () => {
  const root = createRoot()
  addSession(root, { directory: 'session-old', updatedAt: BASE_TIME, compressed: true, file: 'session.v3.jsonl.zstd', lines: [title('旧版本标题')] })
  addSession(root, { directory: 'session-old', updatedAt: BASE_TIME + 1000, file: 'session.v4.jsonl', lines: [title('新版本标题')] })
  const report = await buildCatalog(50, root)
  assert.equal(report.sessions.length, 1)
  assert.equal(report.sessions[0].title, '新版本标题')
})

test('按最近更新时间倒序、超上限截断，并跳过回收站/隐藏目录/软链', async () => {
  const root = createRoot()
  for (const index of [1, 2, 3]) {
    addSession(root, { directory: `session-${index}`, updatedAt: BASE_TIME + index * 1000, lines: [title(`标题 ${index}`)] })
  }
  // 回收站与隐藏目录都不是会话。
  addSession(root, { project: '.sessions-trash', directory: 'session-trashed', updatedAt: BASE_TIME + 99_000, lines: [title('回收站里的会话')] })
  addSession(root, { project: '.hidden', directory: 'session-hidden', updatedAt: BASE_TIME + 99_000, lines: [title('隐藏目录里的会话')] })
  let linked = true
  try {
    symlinkSync(join(root, '-root-project'), join(root, 'link-project'), 'junction')
  } catch {
    linked = false
  }
  const report = await buildCatalog(2, root)
  assert.equal(report.truncated, true)
  assert.deepEqual(report.sessions.map((session) => session.id), ['session-3', 'session-2'])
  const full = await buildCatalog(50, root)
  // 软链目录（连到已列过的 project）不得让同一个会话被列两次；建不出软链时也必须是 3 条。
  assert.equal(full.sessions.length, 3, `软链建出=${linked}`)
  assert.ok(!full.sessions.some((session) => session.title.includes('回收站') || session.title.includes('隐藏')))
})

test('会话根目录不存在时回空列表（未写过会话 ≠ 读不到）', async () => {
  const missing = join(tmpdir(), `session-catalog-missing-${Date.now()}`)
  const report = await buildCatalog(50, missing)
  assert.deepEqual(report, { sessions: [], truncated: false })
})

test('目录名可逆解码，异常目录名一律拒绝', async () => {
  assert.equal(decodeSegment('session~002E1'), 'session.1')
  assert.equal(decodeSegment('session-9f2c1a77'), 'session-9f2c1a77')
  assert.equal(decodeSegment('a~002Fb'), null, '解码出 `/` 必须拒绝')
  assert.equal(decodeSegment('a~005Cb'), null, '解码出 `\\` 必须拒绝')
  assert.equal(decodeSegment('a~0000b'), null, '解码出 NUL 必须拒绝')
  assert.equal(decodeSegment('a~ZZZZ'), null)
  assert.equal(decodeSegment('a~002'), null)
  assert.equal(decodeSegment('..'), null)
  assert.equal(decodeSegment(''), null)
  assert.equal(decodeSegment('x'.repeat(300)), null)
  const root = createRoot()
  addSession(root, { directory: 'session~002Eescaped', lines: [title('被转义的 id')] })
  const report = await buildCatalog(50, root)
  assert.equal(report.sessions.length, 1)
  assert.equal(report.sessions[0].id, 'session.escaped')
})

test('参数解析：条数夹取、根目录必须是绝对路径', () => {
  assert.deepEqual(parseArguments(['node', 'script']).root, '/root/.dsh/sessions')
  assert.equal(parseArguments(['node', 'script']).limit, 50)
  assert.equal(parseArguments(['node', 'script', '999']).limit, 50)
  assert.equal(parseArguments(['node', 'script', '0']).limit, 1)
  assert.equal(parseArguments(['node', 'script', 'abc']).limit, 50)
  assert.equal(parseArguments(['node', 'script', '7', createRoot()]).limit, 7)
  for (const bad of ['relative/dir', '', 'a\u0000b']) {
    assert.throws(() => parseArguments(['node', 'script', '5', bad]), (error) => error.code === 'SESSION_CATALOG_ROOT_INVALID')
  }
})

test('命令行入口输出一行 JSON；读不到时回受控错误码', () => {
  assert.ok(existsSync(SCRIPT_FILE), '脚本文件必须存在')
  const root = createRoot()
  addSession(root, { directory: 'session-cli', lines: [title('命令行标题')] })
  const ok = spawnSync(process.execPath, [SCRIPT_FILE, '50', root], { encoding: 'utf8' })
  assert.equal(ok.status, 0, ok.stderr)
  const payload = JSON.parse(ok.stdout.trim().split('\n').pop())
  assert.equal(payload.sessions.length, 1)
  assert.equal(payload.sessions[0].title, '命令行标题')
  // 根目录指向一个普通文件（损坏状态）→ 明确失败，而不是回空列表。
  const file = join(root, 'not-a-directory')
  writeFileSync(file, 'x')
  const failed = spawnSync(process.execPath, [SCRIPT_FILE, '50', file], { encoding: 'utf8' })
  assert.equal(failed.status, 1)
  assert.deepEqual(JSON.parse(failed.stdout.trim().split('\n').pop()), { error: 'SESSION_CATALOG_FAILED' })
})

test('标题折叠规则与宿主 dsh-session-title 逐条一致', async (context) => {
  const target = new URL('../scripts/runtime-profile/node_modules/.pnpm/node_modules/@deepseek-ai/dsh-session-title/lib/index.js', import.meta.url)
  if (!existsSync(target)) {
    context.skip('未安装 @deepseek-ai/dsh-session-title（离线检出），跳过一致性比对')
    return
  }
  const host = await import(pathToFileURL(fileURLToPath(target)).href)
  const corpus = [
    'Hello world this is a fairly long english sentence that must be truncated somewhere',
    '你好世界 这是一句比较长的中文标题 需要截断到四十个字节以内 后面还有更多内容',
    '\u001B[31m红色\u001B[0m 与控制字符\u0007 混排',
    '  前后有空白   中间   有多个空格  ',
    'a'.repeat(200),
    'Mixed 中英文 mixed 内容 with 数字 12345',
    '\u202E反向字符\u202C 与零宽\u200B字符',
    '换行\n与\t制表符',
    '',
    '   ',
    'one',
    '第一句。第二句也应该保留吗 第三句',
  ]
  for (const input of corpus) {
    assert.equal(normalizeSessionTitle(input, 80), host.normalizeSessionTitle(input, 80), `normalizeSessionTitle: ${JSON.stringify(input)}`)
    assert.equal(
      fallbackSessionTitle(input, 5, 40),
      host.fallbackSessionTitle(input, 5, 40),
      `fallbackSessionTitle: ${JSON.stringify(input)}`,
    )
  }
})
