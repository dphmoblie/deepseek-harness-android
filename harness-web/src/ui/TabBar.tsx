import type { ReactElement } from 'react'

export type TabId = 'sessions' | 'tasks' | 'files' | 'settings'

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'sessions', label: '会话', icon: '💬' },
  { id: 'tasks', label: '任务', icon: '📋' },
  { id: 'files', label: '目录', icon: '📁' },
  { id: 'settings', label: '设置', icon: '⚙️' },
]

export function TabBar(props: { active: TabId; onChange: (tab: TabId) => void }): ReactElement {
  const { active, onChange } = props
  return (
    <nav className="tab-bar">
      {TABS.map((tab) => (
        <button
          key={tab.id}
          type="button"
          className={tab.id === active ? 'tab-item tab-item-active' : 'tab-item'}
          onClick={() => onChange(tab.id)}
        >
          <span className="tab-icon">{tab.icon}</span>
          <span className="tab-label">{tab.label}</span>
        </button>
      ))}
    </nav>
  )
}
