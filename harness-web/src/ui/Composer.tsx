import { ArrowUp, AtSign, Image as ImageIcon, Paperclip, Sparkles, Square, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, ClipboardEvent, ReactElement } from 'react'
import type { PromptContentPart, SessionId, SkillEntry, SubagentListEntry } from '../api/types'
import { callUnary } from '../api/wire'
import type { PromptMode } from '../state/chat'
import { rpcErrorMessage } from '../state/rpcError'

const MAX_PROMPT_LENGTH = 12_000
const MAX_IMAGES = 4
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const ACCEPTED_MEDIA_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

type ImagePart = Extract<PromptContentPart, { type: 'image' }>
type Suggestion = {
  key: string
  trigger: '/' | '@'
  value: string
  label: string
  description: string
  icon: 'skill' | 'agent'
}

export function Composer(props: {
  sessionId?: SessionId
  running: boolean
  queuedCount?: number
  onSend: (text: string, mode: PromptMode, images?: ImagePart[]) => void
  onCancel: () => void
  onFailure?: (message: string) => void
}): ReactElement {
  const { sessionId, running, queuedCount = 0, onSend, onCancel, onFailure } = props
  const [text, setText] = useState('')
  const [mode, setMode] = useState<PromptMode>('queue')
  const [images, setImages] = useState<ImagePart[]>([])
  const [skills, setSkills] = useState<SkillEntry[]>([])
  const [subagents, setSubagents] = useState<SubagentListEntry[]>([])
  const [highlighted, setHighlighted] = useState(0)
  const [dragActive, setDragActive] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const fileRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    if (!running) setMode('queue')
  }, [running])

  useEffect(() => {
    let cancelled = false
    setSkills([])
    setSubagents([])
    if (sessionId === undefined) return
    void Promise.allSettled([
      callUnary(window.location.origin, 'skill.list', { sessionId }),
      callUnary(window.location.origin, 'subagent.list', { parentSessionId: sessionId }),
    ]).then(([skillResult, subagentResult]) => {
      if (cancelled) return
      if (skillResult.status === 'fulfilled') setSkills(skillResult.value.skills)
      else onFailure?.(rpcErrorMessage('加载技能建议', skillResult.reason))
      if (subagentResult.status === 'fulfilled') setSubagents(subagentResult.value.entries)
      else onFailure?.(rpcErrorMessage('加载子代理建议', subagentResult.reason))
    })
    return () => { cancelled = true }
  }, [onFailure, sessionId])

  const trigger = useMemo(() => findTrigger(text), [text])
  const suggestions = useMemo(
    () => suggestionsFor(trigger, skills, subagents),
    [skills, subagents, trigger],
  )

  useEffect(() => { setHighlighted(0) }, [trigger?.query, trigger?.trigger])

  const submit = (): void => {
    const trimmed = text.trim()
    if (trimmed === '' && images.length === 0) return
    const effectiveMode = running ? mode : 'queue'
    if (images.length > 0) onSend(trimmed, effectiveMode, images)
    else onSend(trimmed, effectiveMode)
    setText('')
    setImages([])
    const textarea = textareaRef.current
    if (textarea !== null) textarea.style.height = 'auto'
  }

  const autoGrow = (element: HTMLTextAreaElement): void => {
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 336)}px`
  }

  const applySuggestion = (suggestion: Suggestion): void => {
    if (trigger === null) return
    const next = `${text.slice(0, trigger.start)}${suggestion.trigger}${suggestion.value} `
    setText(next.slice(0, MAX_PROMPT_LENGTH))
    setHighlighted(0)
    requestAnimationFrame(() => {
      textareaRef.current?.focus()
      if (textareaRef.current !== null) autoGrow(textareaRef.current)
    })
  }

  const addImageFiles = useCallback(async (files: File[]): Promise<void> => {
    if (files.length === 0) return
    const room = MAX_IMAGES - images.length
    if (room <= 0) {
      onFailure?.(`每条消息最多添加 ${MAX_IMAGES} 张图片`)
      return
    }
    const accepted: ImagePart[] = []
    for (const file of files.slice(0, room)) {
      const name = safeImageName(file.name)
      const displayName = name ?? '未命名图片'
      if (!ACCEPTED_MEDIA_TYPES.has(file.type)) {
        onFailure?.(`不支持的图片格式：${displayName}`)
        continue
      }
      if (file.size <= 0 || file.size > MAX_IMAGE_BYTES) {
        onFailure?.(`图片 ${displayName} 超过 10 MB 限制`)
        continue
      }
      try {
        accepted.push({
          type: 'image',
          mediaType: file.type as ImagePart['mediaType'],
          data: await readBase64(file),
          ...(name === undefined ? {} : { name }),
        })
      } catch {
        onFailure?.(`无法读取图片：${displayName}`)
      }
    }
    if (accepted.length > 0) setImages(previous => [...previous, ...accepted].slice(0, MAX_IMAGES))
  }, [images.length, onFailure])

  const addImages = async (event: ChangeEvent<HTMLInputElement>): Promise<void> => {
    const files = [...(event.target.files ?? [])]
    event.target.value = ''
    await addImageFiles(files)
  }

  const pasteImages = (event: ClipboardEvent<HTMLTextAreaElement>): void => {
    const files = [...event.clipboardData.files]
    if (files.length === 0) return
    event.preventDefault()
    void addImageFiles(files)
  }

  useEffect(() => {
    const carriesFiles = (event: DragEvent): boolean => (
      event.dataTransfer?.types.includes('Files') === true
      || (event.dataTransfer?.files.length ?? 0) > 0
    )
    const dragOver = (event: DragEvent): void => {
      if (!carriesFiles(event)) return
      event.preventDefault()
      if (event.dataTransfer !== null) event.dataTransfer.dropEffect = 'copy'
      setDragActive(true)
    }
    const dragLeave = (event: DragEvent): void => {
      if (event.relatedTarget === null) setDragActive(false)
    }
    const drop = (event: DragEvent): void => {
      if (!carriesFiles(event)) return
      event.preventDefault()
      setDragActive(false)
      void addImageFiles([...(event.dataTransfer?.files ?? [])])
    }
    document.addEventListener('dragover', dragOver)
    document.addEventListener('dragleave', dragLeave)
    document.addEventListener('drop', drop)
    return () => {
      document.removeEventListener('dragover', dragOver)
      document.removeEventListener('dragleave', dragLeave)
      document.removeEventListener('drop', drop)
    }
  }, [addImageFiles])

  return (
    <div className="composer-shell">
      {dragActive && <div className="attachment-drop-overlay" role="status"><ImageIcon size={20} />拖放图片以添加到消息</div>}
      {suggestions.length > 0 && (
        <div className="composer-suggestions" role="listbox" aria-label={trigger?.trigger === '/' ? '技能建议' : '子代理建议'}>
          {suggestions.map((suggestion, index) => (
            <button
              key={suggestion.key}
              type="button"
              role="option"
              aria-selected={index === highlighted}
              onPointerDown={event => event.preventDefault()}
              onClick={() => applySuggestion(suggestion)}
            >
              {suggestion.icon === 'skill' ? <Sparkles size={16} /> : <AtSign size={16} />}
              <span><strong>{suggestion.trigger}{suggestion.label}</strong><small>{suggestion.description}</small></span>
            </button>
          ))}
        </div>
      )}
      {images.length > 0 && (
        <div className="attachment-strip" aria-label="待发送图片">
          {images.map((image, index) => (
            <div key={`${image.name ?? 'image'}-${index}`} className="attachment-preview">
              <img src={`data:${image.mediaType};base64,${image.data}`} alt={image.name ?? '待发送图片'} />
              <button type="button" aria-label={`移除 ${image.name ?? '图片'}`} title="移除" onClick={() => setImages(previous => previous.filter((_, itemIndex) => itemIndex !== index))}><X size={13} /></button>
            </div>
          ))}
        </div>
      )}
      <div className="composer-toolbar">
        <div className="segmented-control" role="group" aria-label="消息发送方式">
          <button type="button" className={mode === 'queue' ? 'segment segment-active' : 'segment'} aria-pressed={mode === 'queue'} title="当前轮次结束后按顺序处理" onClick={() => setMode('queue')}>排队</button>
          <button type="button" className={mode === 'steer' ? 'segment segment-active' : 'segment'} aria-pressed={mode === 'steer'} title="让正在运行的代理调整当前方向" disabled={!running} onClick={() => setMode('steer')}>引导</button>
        </div>
        <span className="composer-state" aria-live="polite">
          {running ? (queuedCount > 0 ? `运行中 · ${queuedCount} 条待处理` : '运行中') : '就绪'}
        </span>
      </div>
      <div className="composer">
        <input ref={fileRef} className="visually-hidden" type="file" accept="image/png,image/jpeg,image/webp,image/gif" multiple onChange={event => void addImages(event)} />
        <button type="button" className="icon-button composer-attach" aria-label="添加图片" title="添加图片" disabled={images.length >= MAX_IMAGES} onClick={() => fileRef.current?.click()}>
          {images.length > 0 ? <ImageIcon size={18} /> : <Paperclip size={18} />}
        </button>
        <textarea
          ref={textareaRef}
          className="composer-input"
          rows={1}
          maxLength={MAX_PROMPT_LENGTH}
          aria-label="发送给 DeepSeek Harness 的消息"
          aria-autocomplete="list"
          placeholder={running ? '继续输入，可排队或引导当前任务' : '给 DeepSeek Harness 发送消息'}
          value={text}
          onPaste={pasteImages}
          onChange={(event) => { setText(event.target.value); autoGrow(event.target) }}
          onKeyDown={(event) => {
            if (suggestions.length > 0) {
              if (event.key === 'ArrowDown') {
                event.preventDefault()
                setHighlighted(previous => (previous + 1) % suggestions.length)
                return
              }
              if (event.key === 'ArrowUp') {
                event.preventDefault()
                setHighlighted(previous => (previous - 1 + suggestions.length) % suggestions.length)
                return
              }
              if ((event.key === 'Tab' || event.key === 'Enter') && !event.nativeEvent.isComposing) {
                event.preventDefault()
                const suggestion = suggestions[highlighted]
                if (suggestion !== undefined) applySuggestion(suggestion)
                return
              }
              if (event.key === 'Escape') {
                setText(value => `${value} `)
                return
              }
            }
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault()
              submit()
            }
          }}
        />
        {running && (
          <button type="button" className="icon-button icon-button-danger" aria-label="停止当前任务" title="停止当前任务" onClick={onCancel}>
            <Square size={15} fill="currentColor" aria-hidden="true" />
          </button>
        )}
        <button type="button" className="icon-button icon-button-primary" disabled={text.trim() === '' && images.length === 0} aria-label={mode === 'queue' ? '发送并排队' : '发送并引导'} title={mode === 'queue' ? '发送并排队' : '发送并引导'} onClick={submit}>
          <ArrowUp size={20} strokeWidth={2.2} aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}

function findTrigger(text: string): { trigger: '/' | '@'; query: string; start: number } | null {
  const match = /(^|\s)([/@])([a-zA-Z0-9_-]{0,80})$/.exec(text)
  if (match === null) return null
  const prefix = match[1] ?? ''
  const trigger = match[2]
  if (trigger !== '/' && trigger !== '@') return null
  return { trigger, query: (match[3] ?? '').toLowerCase(), start: match.index + prefix.length }
}

function suggestionsFor(trigger: ReturnType<typeof findTrigger>, skills: SkillEntry[], subagents: SubagentListEntry[]): Suggestion[] {
  if (trigger === null) return []
  if (trigger.trigger === '/') {
    return skills
      .filter(skill => skill.name.length <= 100 && skill.name.toLowerCase().startsWith(trigger.query))
      .slice(0, 8)
      .map(skill => ({ key: `skill-${skill.name}`, trigger: '/', value: skill.name, label: skill.name, description: skill.description.slice(0, 240), icon: 'skill' }))
  }
  return subagents
    .filter((entry): entry is Extract<SubagentListEntry, { kind: 'child' }> => entry.kind === 'child')
    .filter(entry => entry.activity === 'running' && (entry.label ?? entry.id).toLowerCase().startsWith(trigger.query))
    .slice(0, 8)
    .map(entry => ({ key: `agent-${entry.id}`, trigger: '@', value: entry.label ?? entry.id, label: entry.label ?? entry.id.slice(0, 8), description: entry.mode === 'continuable' ? '可继续子代理' : '运行中的子代理', icon: 'agent' }))
}

function readBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error('read failed'))
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        reject(new Error('read failed'))
        return
      }
      const comma = reader.result.indexOf(',')
      if (comma < 0) {
        reject(new Error('invalid data URL'))
        return
      }
      resolve(reader.result.slice(comma + 1))
    }
    reader.readAsDataURL(file)
  })
}

function safeImageName(value: string): string | undefined {
  const cleaned = [...value]
    .filter(character => {
      const code = character.codePointAt(0) ?? 0
      return code > 0x1f
        && !(code >= 0x7f && code <= 0x9f)
        && code !== 0x200b
        && code !== 0x200e
        && code !== 0x200f
        && code !== 0xfeff
        && !(code >= 0x202a && code <= 0x202e)
        && !(code >= 0x2060 && code <= 0x206f)
    })
    .slice(0, 120)
    .join('')
    .trim()
  return cleaned === '' ? undefined : cleaned
}
