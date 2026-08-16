import { useRef, useState } from 'react'
import type { ReactElement } from 'react'

export function Composer(props: {
  running: boolean
  onSend: (text: string) => void
  onCancel: () => void
}): ReactElement {
  const { running, onSend, onCancel } = props
  const [text, setText] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  const submit = (): void => {
    const trimmed = text.trim()
    if (trimmed === '' || running) return
    onSend(trimmed)
    setText('')
    const textarea = textareaRef.current
    if (textarea !== null) textarea.style.height = 'auto'
  }

  const autoGrow = (element: HTMLTextAreaElement): void => {
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 140)}px`
  }

  return (
    <div className="composer">
      <textarea
        ref={textareaRef}
        className="composer-input"
        rows={1}
        placeholder={running ? '代理正在运行…' : '给 DeepSeek Harness 发送消息'}
        value={text}
        onChange={(event) => {
          setText(event.target.value)
          autoGrow(event.target)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault()
            submit()
          }
        }}
      />
      {running ? (
        <button type="button" className="btn btn-danger composer-send" onClick={onCancel}>
          停止
        </button>
      ) : (
        <button
          type="button"
          className="btn btn-primary composer-send"
          disabled={text.trim() === ''}
          onClick={submit}
        >
          发送
        </button>
      )}
    </div>
  )
}
