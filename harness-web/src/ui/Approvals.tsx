import { useState } from 'react'
import type { ReactElement } from 'react'
import type { ApprovalRequest, QuestionRequest } from '../state/chat'
import type { AskUserQuestionItem } from '../api/types'

/** 工具调用审批弹窗。 */
export function ApprovalDialog(props: {
  request: ApprovalRequest
  onAnswer: (outcome: 'allowed-once' | 'rejected') => void
}): ReactElement {
  const { request, onAnswer } = props
  return (
    <div className="sheet-backdrop">
      <div className="sheet">
        <p className="sheet-title">工具调用审批</p>
        <p className="sheet-detail">
          代理请求执行 <strong>{request.toolName}</strong>
        </p>
        {request.reason !== undefined && request.reason !== '' && (
          <p className="sheet-detail">{request.reason}</p>
        )}
        <div className="sheet-actions">
          <button type="button" className="btn btn-primary" onClick={() => onAnswer('allowed-once')}>
            允许一次
          </button>
          <button type="button" className="btn btn-danger" onClick={() => onAnswer('rejected')}>
            拒绝
          </button>
        </div>
      </div>
    </div>
  )
}

/** 单个提问的作答控件。 */
function QuestionItem(props: {
  question: AskUserQuestionItem
  answer: { selected: string[]; custom: string }
  onChange: (answer: { selected: string[]; custom: string }) => void
}): ReactElement {
  const { question, answer, onChange } = props
  const toggle = (label: string): void => {
    const selected = question.multiSelect === true
      ? answer.selected.includes(label)
        ? answer.selected.filter((item) => item !== label)
        : [...answer.selected, label]
      : [label]
    onChange({ ...answer, selected })
  }
  return (
    <div className="question-item">
      <p className="question-text">{question.question}</p>
      {question.header !== undefined && <p className="question-header">{question.header}</p>}
      {question.options !== undefined && (
        <div className="question-options">
          {question.options.map((option) => (
            <button
              key={option.label}
              type="button"
              className={answer.selected.includes(option.label) ? 'btn option option-selected' : 'btn option'}
              onClick={() => toggle(option.label)}
            >
              {option.label}
              {option.description !== undefined && (
                <span className="option-desc">{option.description}</span>
              )}
            </button>
          ))}
        </div>
      )}
      <input
        className="field"
        placeholder="补充说明（可选）"
        value={answer.custom}
        onChange={(event) => onChange({ ...answer, custom: event.target.value })}
      />
    </div>
  )
}

/** 代理提问弹窗（可能一次多个问题）。 */
export function QuestionsDialog(props: {
  request: QuestionRequest
  onAnswer: (answers: { id: string; selected: string[]; custom?: string }[]) => void
}): ReactElement {
  const { request, onAnswer } = props
  const [drafts, setDrafts] = useState<Record<string, { selected: string[]; custom: string }>>(
    Object.fromEntries(request.questions.map((question) => [question.id, { selected: [], custom: '' }])),
  )

  const submit = (): void => {
    onAnswer(
      request.questions.map((question) => {
        const draft = drafts[question.id] ?? { selected: [], custom: '' }
        return {
          id: question.id,
          selected: draft.selected,
          ...(draft.custom !== '' ? { custom: draft.custom } : {}),
        }
      }),
    )
  }

  return (
    <div className="sheet-backdrop">
      <div className="sheet">
        <p className="sheet-title">代理提问</p>
        {request.questions.map((question) => (
          <QuestionItem
            key={question.id}
            question={question}
            answer={drafts[question.id] ?? { selected: [], custom: '' }}
            onChange={(answer) => setDrafts((prev) => ({ ...prev, [question.id]: answer }))}
          />
        ))}
        <div className="sheet-actions">
          <button type="button" className="btn btn-primary" onClick={submit}>
            提交
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => onAnswer(request.questions.map((question) => ({ id: question.id, selected: [] })))}
          >
            跳过
          </button>
        </div>
      </div>
    </div>
  )
}
