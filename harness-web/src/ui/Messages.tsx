import { AlertCircle, Check, Copy, GitFork, Image as ImageIcon } from 'lucide-react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import type { ChatEntry } from '../state/fold'
import { rpcErrorMessage } from '../state/rpcError'
import type { ContentBlock, ImageAttachmentRef, Message, SessionId } from '../api/types'

/** Content rendering remains bounded and skips embedded HTML to keep transcripts inert. */
export function Blocks(props: {
  blocks: ContentBlock[]
  sessionId?: SessionId
  streaming?: boolean
  depth?: number
}): ReactElement {
  const { blocks, sessionId, depth = 0 } = props
  if (depth > 4) return <div className="unsupported-block">嵌套内容过深，已停止展开</div>
  return (
    <div className="blocks">
      {blocks.slice(0, 2_000).map((block, index) => {
        switch (block.type) {
          case 'text':
            return <MarkdownText key={index} text={(block as { text: string }).text} />
          case 'reasoning': {
            const { text } = block as { text: string }
            return (
              <details key={index} className="block-reasoning">
                <summary>思考过程</summary>
                <MarkdownText text={text} />
              </details>
            )
          }
          case 'tool-call': {
            const { name, arguments: args } = block as { name: string; arguments: string }
            return (
              <details key={index} className="block-tool-call">
                <summary><span className="tool-name">{name}</span><span className="tool-args">{summarizeArguments(args)}</span></summary>
                <pre className="tool-json">{prettyJson(args)}</pre>
              </details>
            )
          }
          case 'tool-result': {
            const { isError, content } = block as { isError?: boolean; content?: ContentBlock[] }
            return (
              <details key={index} className={isError === true ? 'block-tool-result failed' : 'block-tool-result'}>
                <summary>工具结果{isError === true ? ' · 失败' : ''}</summary>
                {Array.isArray(content) && <Blocks blocks={content} sessionId={sessionId} depth={depth + 1} />}
              </details>
            )
          }
          case 'image': {
            const { attachment } = block as { attachment: ImageAttachmentRef }
            return <LazyImage key={index} sessionId={sessionId} attachmentId={attachment.attachmentId} name={attachment.name} />
          }
          default:
            return <div key={index} className="unsupported-block">暂不支持的扩展内容：{shorten(block.type)}</div>
        }
      })}
    </div>
  )
}

function MarkdownText(props: { text: string }): ReactElement {
  const bounded = props.text.slice(0, 200_000)
  return (
    <div className="block-text markdown-body">
      <Markdown
        remarkPlugins={[remarkGfm]}
        skipHtml
        urlTransform={url => safeHref(url) ?? ''}
        components={{
          a: ({ children, href }) => {
            const safe = safeHref(href)
            return safe === null
              ? <span className="markdown-link">{children}</span>
              : <a className="markdown-link" href={safe} target="_blank" rel="noreferrer noopener">{children}</a>
          },
          img: ({ alt }) => <span className="block-image-placeholder"><ImageIcon size={15} />{alt ?? '图片'}</span>,
        }}
      >
        {bounded}
      </Markdown>
    </div>
  )
}

function summarizeArguments(argumentsJson: string): string {
  try {
    const parsed = JSON.parse(argumentsJson) as Record<string, unknown>
    const entries = Object.entries(parsed)
    if (entries.length === 0) return ''
    const shown = entries.slice(0, 2).map(([key, value]) => `${key}=${shorten(String(value))}`).join(' ')
    return entries.length > 2 ? `${shown}…` : shown
  } catch {
    return shorten(argumentsJson)
  }
}

function shorten(text: string): string {
  const compact = text.replace(/\s+/g, ' ').trim()
  return compact.length > 80 ? `${compact.slice(0, 80)}…` : compact
}

function LazyImage(props: { sessionId?: SessionId; attachmentId: string; name?: string }): ReactElement {
  const { sessionId, attachmentId, name } = props
  const [src, setSrc] = useState<string | null>(null)
  const [failureText, setFailureText] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setSrc(null)
    setFailureText(null)
    if (sessionId === undefined) return
    void (async () => {
      try {
        const value = await callUnary(window.location.origin, 'session.attachment', { sessionId, attachmentId })
        if (!cancelled) setSrc(`data:${value.attachment.mediaType};base64,${value.data}`)
      } catch (failure) {
        if (!cancelled) setFailureText(rpcErrorMessage('读取图片', failure))
      }
    })()
    return () => { cancelled = true }
  }, [sessionId, attachmentId])

  if (src === null) {
    return <span className={failureText === null ? 'block-image-placeholder' : 'block-image-placeholder failed'}><ImageIcon size={15} />{failureText ?? name ?? '加载图片'}</span>
  }
  return <img className="block-image" src={src} alt={name ?? '图片'} loading="lazy" />
}

/** A settled transcript row with the original client actions restored. */
export function MessageItem(props: {
  entry: ChatEntry
  sessionId: SessionId
  onFork?: (atSeq: number) => void
}): ReactElement | null {
  const { entry, sessionId, onFork } = props
  switch (entry.kind) {
    case 'user':
      return (
        <div className="msg msg-user" data-time-hover-root>
          <div className="message-stack user-message-stack">
            <div className="bubble bubble-user"><Blocks blocks={entry.message.content} sessionId={sessionId} /></div>
            <MessageActions message={entry.message} time={entry.time} />
          </div>
        </div>
      )
    case 'assistant':
      return (
        <div className="msg msg-assistant" data-time-hover-root>
          <div className="message-stack assistant-message-stack">
            <div className="bubble bubble-assistant">
              <Blocks blocks={entry.message.content} sessionId={sessionId} />
              {entry.usage !== undefined && entry.usage.outputTokens > 0 && <p className="usage">{entry.usage.outputTokens} tokens</p>}
            </div>
            <MessageActions message={entry.message} time={entry.time} onFork={onFork === undefined ? undefined : () => onFork(entry.seq)} />
          </div>
        </div>
      )
    case 'tool-call':
      return (
        <div className="msg msg-tool">
          <details className="bubble tool-card">
            <summary><span className="tool-name">{entry.name}</span><span className="tool-args">{summarizeArguments(entry.arguments)}</span></summary>
            <pre className="tool-json">{prettyJson(entry.arguments)}</pre>
          </details>
        </div>
      )
    case 'tool-result':
      return (
        <div className="msg msg-tool">
          <details className={entry.isError ? 'bubble tool-card failed' : 'bubble tool-card'}>
            <summary><span className="tool-name">工具结果{entry.isError ? ' · 失败' : ''}</span></summary>
            {typeof entry.view?.view.card === 'string' && <p className="tool-view-label">{entry.view.view.card}</p>}
            <Blocks blocks={entry.message.content} sessionId={sessionId} />
          </details>
        </div>
      )
    case 'notice': {
      const failed = entry.text.startsWith('本轮运行失败')
      const separator = entry.text.indexOf('：')
      return failed ? (
        <div className="turn-error-row" role="status">
          <AlertCircle size={15} aria-hidden="true" />
          <span><strong>{separator > 0 ? entry.text.slice(0, separator) : '本轮运行失败'}</strong>{separator > 0 ? entry.text.slice(separator + 1) : entry.text}</span>
        </div>
      ) : <p className="notice">{entry.text}</p>
    }
  }
}

function MessageActions(props: { message: Message; time: number; onFork?: () => void }): ReactElement {
  const { message, time, onFork } = props
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)
  const text = plainMessageText(message)
  const copy = async (): Promise<void> => {
    if (text === '') return
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setCopyError(null)
      window.setTimeout(() => setCopied(false), 1_500)
    } catch (failure) {
      setCopied(false)
      setCopyError(rpcErrorMessage('复制消息', failure))
    }
  }
  return (
    <div className="message-actions">
      {Number.isFinite(time) && time > 0 && <time dateTime={new Date(time).toISOString()}>{formatMessageTime(time)}</time>}
      {copyError !== null && <span className="message-copy-error" role="status">{copyError}</span>}
      <button type="button" aria-label={copied ? '已复制' : '复制消息'} title={copied ? '已复制' : '复制'} disabled={text === ''} onClick={() => void copy()}>
        {copied ? <Check size={14} /> : <Copy size={14} />}
      </button>
      {onFork !== undefined && (
        <button type="button" aria-label="从此回答分叉会话" title="分叉会话" onClick={onFork}><GitFork size={14} /></button>
      )}
    </div>
  )
}

function plainMessageText(message: Message): string {
  return message.content
    .filter(block => block.type === 'text' || block.type === 'reasoning')
    .map(block => (block as { text: string }).text)
    .join('\n\n')
    .slice(0, 200_000)
}

function formatMessageTime(time: number): string {
  const date = new Date(time)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function prettyJson(raw: string): string {
  const bounded = raw.slice(0, 200_000)
  try { return JSON.stringify(JSON.parse(bounded), null, 2) } catch { return bounded }
}

function safeHref(value: string | undefined): string | null {
  if (value === undefined || value.length > 2_048) return null
  try {
    const url = new URL(value, window.location.origin)
    return url.protocol === 'http:' || url.protocol === 'https:' || url.protocol === 'mailto:' ? url.toString() : null
  } catch {
    return null
  }
}
