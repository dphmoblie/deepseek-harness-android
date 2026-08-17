import { ArrowUp, Square } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import type { PromptMode } from '../state/chat'

const MAX_PROMPT_LENGTH = 12_000

export function Composer(props: {
  running: boolean
  queuedCount?: number
  onSend: (text: string, mode: PromptMode) => void
  onCancel: () => void
}): ReactElement {
  const { running, queuedCount = 0, onSend, onCancel } = props
  const [text, setText] = useState('')
  const [mode, setMode] = useState<PromptMode>('queue')
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  useEffect(() => {
    if (!running) setMode('queue')
  }, [running])

  const submit = (): void => {
    const trimmed = text.trim()
    if (trimmed === '') return
    onSend(trimmed, running ? mode : 'queue')
    setText('')
    const textarea = textareaRef.current
    if (textarea !== null) textarea.style.height = 'auto'
  }

  const autoGrow = (element: HTMLTextAreaElement): void => {
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 160)}px`
  }

  return (
    <div className="composer-shell">
      <div className="composer-toolbar">
        <div className="segmented-control" role="group" aria-label="消息发送方式">
          <button
            type="button"
            className={mode === 'queue' ? 'segment segment-active' : 'segment'}
            aria-pressed={mode === 'queue'}
            title="当前轮次结束后按顺序处理"
            onClick={() => setMode('queue')}
          >
            排队
          </button>
          <button
            type="button"
            className={mode === 'steer' ? 'segment segment-active' : 'segment'}
            aria-pressed={mode === 'steer'}
            title="让正在运行的代理调整当前方向"
            disabled={!running}
            onClick={() => setMode('steer')}
          >
            引导
          </button>
        </div>
        <span className="composer-state" aria-live="polite">
          {running ? (queuedCount > 0 ? `运行中 · ${queuedCount} 条待处理` : '运行中') : '就绪'}
        </span>
      </div>
      <div className="composer">
        <textarea
          ref={textareaRef}
          className="composer-input"
          rows={1}
          maxLength={MAX_PROMPT_LENGTH}
          aria-label="发送给 DeepSeek Harness 的消息"
          placeholder={running ? '继续输入，可排队或引导当前任务' : '给 DeepSeek Harness 发送消息'}
          value={text}
          onChange={(event) => {
            setText(event.target.value)
            autoGrow(event.target)
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault()
              submit()
            }
          }}
        />
        {running && (
          <button
            type="button"
            className="icon-button icon-button-danger"
            aria-label="停止当前任务"
            title="停止当前任务"
            onClick={onCancel}
          >
            <Square size={17} fill="currentColor" aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          className="icon-button icon-button-primary"
          disabled={text.trim() === ''}
          aria-label={mode === 'queue' ? '发送并排队' : '发送并引导'}
          title={mode === 'queue' ? '发送并排队' : '发送并引导'}
          onClick={submit}
        >
          <ArrowUp size={20} strokeWidth={2.2} aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}
