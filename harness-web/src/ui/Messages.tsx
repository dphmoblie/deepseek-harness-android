import { Image as ImageIcon } from 'lucide-react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import type { ChatEntry } from '../state/fold'
import type { ContentBlock, ImageAttachmentRef, SessionId } from '../api/types'

/** 内容块列表渲染：text 段落、思考折叠、内嵌工具调用卡片、图片懒加载。 */
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
      {blocks.map((block, index) => {
        // ContentBlock 含 merge-extensible 透传分支，switch 不窄化，case 内显式收窄
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
              <div key={index} className="block-tool-call">
                <span className="tool-name">{name}</span>
                <span className="tool-args">{summarizeArguments(args)}</span>
              </div>
            )
          }
          case 'tool-result': {
            const { isError, content } = block as { isError?: boolean; content?: ContentBlock[] }
            return (
              <details key={index} className="block-tool-result">
                <summary>工具结果{isError === true ? '（失败）' : ''}</summary>
                {Array.isArray(content) && <Blocks blocks={content} sessionId={sessionId} depth={depth + 1} />}
              </details>
            )
          }
          case 'image': {
            const { attachment } = block as { attachment: ImageAttachmentRef }
            return (
              <LazyImage key={index} sessionId={sessionId} attachmentId={attachment.attachmentId} name={attachment.name} />
            )
          }
          default:
            return <div key={index} className="unsupported-block">暂不支持的内容块：{shorten(block.type)}</div>
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
        components={{
          a: ({ children }) => <span className="markdown-link">{children}</span>,
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

  useEffect(() => {
    let cancelled = false
    if (sessionId === undefined) return
    void (async () => {
      try {
        const value = await callUnary(window.location.origin, 'session.attachment', { sessionId, attachmentId })
        if (!cancelled) setSrc(`data:${value.attachment.mediaType};base64,${value.data}`)
      } catch {
        // 图片拉取失败保持占位
      }
    })()
    return () => {
      cancelled = true
    }
  }, [sessionId, attachmentId])

  if (src === null) {
    return <span className="block-image-placeholder"><ImageIcon size={15} />{name ?? '图片'}</span>
  }
  return <img className="block-image" src={src} alt={name ?? '图片'} />
}

/** 单条渲染条目（用户/助手/工具/通知）。 */
export function MessageItem(props: { entry: ChatEntry; sessionId: SessionId }): ReactElement | null {
  const { entry, sessionId } = props
  switch (entry.kind) {
    case 'user':
      return (
        <div className="msg msg-user">
          <div className="bubble bubble-user">
            <Blocks blocks={entry.message.content} sessionId={sessionId} />
          </div>
        </div>
      )
    case 'assistant':
      return (
        <div className="msg msg-assistant">
          <div className="bubble bubble-assistant">
            <Blocks blocks={entry.message.content} sessionId={sessionId} />
            {entry.usage !== undefined && entry.usage.outputTokens > 0 && (
              <p className="usage">{entry.usage.outputTokens} tokens</p>
            )}
          </div>
        </div>
      )
    case 'tool-call':
      return (
        <div className="msg msg-tool">
          <details className="bubble tool-card">
            <summary>
              <span className="tool-name">{entry.name}</span>
            </summary>
            <pre className="tool-json">{prettyJson(entry.arguments)}</pre>
          </details>
        </div>
      )
    case 'tool-result':
      return (
        <div className="msg msg-tool">
          <details className="bubble tool-card">
            <summary>
              <span className="tool-name">工具结果{entry.isError ? '（失败）' : ''}</span>
            </summary>
            {typeof entry.view?.view.card === 'string' && <p className="tool-view-label">{entry.view.view.card}</p>}
            <Blocks blocks={entry.message.content} />
          </details>
        </div>
      )
    case 'notice':
      return <p className="notice">{entry.text}</p>
  }
}

function prettyJson(raw: string): string {
  const bounded = raw.slice(0, 200_000)
  try {
    return JSON.stringify(JSON.parse(bounded), null, 2)
  } catch {
    return bounded
  }
}
