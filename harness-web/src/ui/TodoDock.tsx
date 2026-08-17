import { CheckCircle2, ChevronDown, ChevronUp, Circle, ListChecks, LoaderCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import type { TodoItem } from '../api/types'

export function TodoDock(props: { todos: TodoItem[] }): ReactElement | null {
  const { todos } = props
  const [expanded, setExpanded] = useState(false)
  const completed = todos.filter(todo => todo.status === 'completed').length

  useEffect(() => {
    if (todos.length === 0) setExpanded(false)
  }, [todos.length])

  if (todos.length === 0) return null
  return (
    <section className="dock-card todo-dock" aria-label="执行计划">
      <button type="button" className="dock-summary" aria-expanded={expanded} onClick={() => setExpanded(previous => !previous)}>
        <ListChecks size={15} aria-hidden="true" />
        <span>计划 {completed}/{todos.length}</span>
        {expanded ? <ChevronDown size={15} /> : <ChevronUp size={15} />}
      </button>
      {expanded && (
        <ul className="dock-list todo-dock-list">
          {todos.map((todo, index) => (
            <li key={`${index}-${todo.content}`} className="dock-row">
              {todo.status === 'completed'
                ? <CheckCircle2 className="state-success" size={15} />
                : todo.status === 'in_progress'
                  ? <LoaderCircle className="spin state-active" size={15} />
                  : <Circle size={15} />}
              <span className={todo.status === 'completed' ? 'dock-preview completed-copy' : 'dock-preview'}>{todo.content}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
