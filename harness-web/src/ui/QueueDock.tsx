import {
  Check,
  ChevronDown,
  ChevronUp,
  Edit3,
  ListOrdered,
  Send,
  Trash2,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { ReactElement } from 'react'
import type { ContentBlock, QueuedInboxItem, SessionId } from '../api/types'
import { callUnary } from '../api/wire'
import { rpcErrorMessage } from '../state/rpcError'

const MAX_QUEUE_TEXT_LENGTH = 12_000

export function QueueDock(props: {
  sessionId: SessionId
  items: QueuedInboxItem[]
  running: boolean
  onFailure: (message: string) => void
}): ReactElement | null {
  const { sessionId, items, running, onFailure } = props
  const queue = useMemo(() => items.filter(item => item.placement === 'queued'), [items])
  const [expanded, setExpanded] = useState(false)
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  useEffect(() => {
    if (queue.length === 0) {
      setExpanded(false)
      setEditing(null)
      return
    }
    if (editing !== null && !queue.some(item => item.id === editing.id)) setEditing(null)
  }, [editing, queue])

  if (queue.length === 0) return null

  const visible = queue.length === 1 || expanded || editing !== null

  const update = async (
    itemId: string,
    action: { kind: 'edit'; content: ContentBlock[] } | { kind: 'remove' } | { kind: 'steer' },
  ): Promise<boolean> => {
    if (busyId !== null) return false
    setBusyId(itemId)
    try {
      await callUnary(window.location.origin, 'session.updateQueue', { sessionId, itemId, action })
      return true
    } catch (failure) {
      onFailure(queueFailureText(failure))
      return false
    } finally {
      setBusyId(null)
    }
  }

  const save = async (): Promise<void> => {
    if (editing === null) return
    const text = editing.text.trim()
    if (text === '' || text.length > MAX_QUEUE_TEXT_LENGTH) return
    if (await update(editing.id, { kind: 'edit', content: [{ type: 'text', text }] })) {
      setEditing(null)
    }
  }

  return (
    <section className="dock-card queue-dock" aria-label="待处理消息">
      {queue.length > 1 && (
        <button
          type="button"
          className="dock-summary"
          aria-expanded={visible}
          onClick={() => setExpanded(previous => !previous)}
        >
          <ListOrdered size={15} aria-hidden="true" />
          <span>{queue.length} 条消息等待处理</span>
          {visible ? <ChevronDown size={15} aria-hidden="true" /> : <ChevronUp size={15} aria-hidden="true" />}
        </button>
      )}
      {visible && (
        <ul className="dock-list">
          {queue.map(item => {
            const editableText = queueText(item)
            const isEditing = editing?.id === item.id
            return (
              <li key={item.id} className="dock-row">
                {queue.length === 1 && <ListOrdered className="dock-leading" size={15} aria-hidden="true" />}
                {isEditing ? (
                  <input
                    className="dock-editor"
                    aria-label="编辑排队消息"
                    autoFocus
                    maxLength={MAX_QUEUE_TEXT_LENGTH}
                    value={editing.text}
                    onChange={event => setEditing({ id: item.id, text: event.target.value })}
                    onKeyDown={event => {
                      if (event.key === 'Escape') setEditing(null)
                      if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                        event.preventDefault()
                        void save()
                      }
                    }}
                  />
                ) : (
                  <span className="dock-preview">{queuePreview(item)}</span>
                )}
                <span className="dock-actions">
                  {isEditing ? (
                    <>
                      <button
                        type="button"
                        className="mini-icon-button"
                        aria-label="保存排队消息"
                        title="保存"
                        disabled={busyId !== null || editing.text.trim() === ''}
                        onClick={() => void save()}
                      ><Check size={14} /></button>
                      <button
                        type="button"
                        className="mini-icon-button"
                        aria-label="取消编辑"
                        title="取消"
                        disabled={busyId !== null}
                        onClick={() => setEditing(null)}
                      ><X size={14} /></button>
                    </>
                  ) : (
                    <>
                      <button
                        type="button"
                        className="mini-icon-button"
                        aria-label="编辑排队消息"
                        title={editableText === null ? '包含非文本内容，无法编辑' : '编辑'}
                        disabled={busyId !== null || editableText === null}
                        onClick={() => {
                          if (editableText !== null) setEditing({ id: item.id, text: editableText })
                        }}
                      ><Edit3 size={14} /></button>
                      <button
                        type="button"
                        className="mini-icon-button"
                        aria-label="删除排队消息"
                        title="删除"
                        disabled={busyId !== null}
                        onClick={() => void update(item.id, { kind: 'remove' })}
                      ><Trash2 size={14} /></button>
                      <button
                        type="button"
                        className="mini-icon-button"
                        aria-label="立即引导当前任务"
                        title={running ? '立即作为引导消息' : '当前没有运行中的任务'}
                        disabled={busyId !== null || !running}
                        onClick={() => void update(item.id, { kind: 'steer' })}
                      ><Send size={14} /></button>
                    </>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

function queueText(item: QueuedInboxItem): string | null {
  if (item.message.content.length === 0 || item.message.content.some(block => block.type !== 'text')) return null
  return item.message.content.map(block => (block as { text: string }).text).join('\n')
}

function queuePreview(item: QueuedInboxItem): string {
  const text = item.message.content
    .filter(block => block.type === 'text')
    .map(block => (block as { text: string }).text)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim()
  if (text !== '') return text.slice(0, 240)
  return item.message.content.some(block => block.type === 'image') ? '图片消息' : '包含结构化内容的消息'
}

function queueFailureText(failure: unknown): string {
  return rpcErrorMessage('更新待处理消息', failure)
}
