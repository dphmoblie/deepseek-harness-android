import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { eventBus } from './state/events'
import type { SessionId } from './api/types'
import { TabBar } from './ui/TabBar'
import type { TabId } from './ui/TabBar'
import { SessionsView } from './ui/SessionsView'
import { ChatView } from './ui/ChatView'
import { TasksView } from './ui/TasksView'
import { FilesView } from './ui/FilesView'
import { SettingsView } from './ui/SettingsView'

/**
 * 移动端应用骨架：底部四页签（会话/任务/目录/设置），
 * 打开会话时进入全屏聊天视图。事件总线在挂载时启动。
 */
export function App(): ReactElement {
  const [tab, setTab] = useState<TabId>('sessions')
  const [openSession, setOpenSession] = useState<SessionId | null>(null)

  useEffect(() => {
    eventBus.start()
    return () => eventBus.stop()
  }, [])

  if (openSession !== null) {
    return <ChatView sessionId={openSession} onBack={() => setOpenSession(null)} />
  }

  return (
    <main className="app">
      <div className="view">
        {tab === 'sessions' && <SessionsView onOpen={setOpenSession} />}
        {tab === 'tasks' && <TasksView />}
        {tab === 'files' && <FilesView />}
        {tab === 'settings' && <SettingsView />}
      </div>
      <TabBar active={tab} onChange={setTab} />
    </main>
  )
}
