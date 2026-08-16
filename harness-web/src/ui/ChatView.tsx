import { useEffect, useRef } from 'react'
import type { ReactElement, UIEvent } from 'react'
import { useChat } from '../state/chat'
import { draftActive, draftContent } from '../state/draft'
import type { SessionId } from '../api/types'
import { Blocks, MessageItem } from './Messages'
import { Composer } from './Composer'
import { ApprovalDialog, QuestionsDialog } from './Approvals'

export function ChatView(props: {
  sessionId: SessionId
  onBack: () => void
}): ReactElement {
  const { sessionId, onBack } = props
  const chat = useChat(sessionId)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickToBottom = useRef(true)

  const handleScroll = (event: UIEvent<HTMLDivElement>): void => {
    const element = event.currentTarget
    stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 64
    if (element.scrollTop < 40) void chat.loadMore()
  }

  // 新内容到达时贴底滚动
  useEffect(() => {
    const element = scrollRef.current
    if (element !== null && stickToBottom.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [chat.entries, chat.draft])

  // 切换会话后强制回到底部
  useEffect(() => {
    stickToBottom.current = true
    const element = scrollRef.current
    if (element !== null) element.scrollTop = element.scrollHeight
  }, [sessionId])

  const draftBlocks = chat.draft !== null ? draftContent(chat.draft) : []
  const draftStreaming = chat.draft !== null && draftActive(chat.draft)

  return (
    <main className="view chat-view">
      <header className="view-header chat-header">
        <button type="button" className="btn" onClick={onBack}>‹ 返回</button>
        <span className="chat-title">
          {sessionId.slice(0, 8)}
          {chat.running && <span className="badge badge-running">运行中</span>}
        </span>
      </header>
      {chat.error !== null && (
        <p className="error-bar" onClick={chat.dismissError}>{chat.error}</p>
      )}
      <div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
        {chat.hasMore && (
          <p className="load-more">
            {chat.loadingMore ? '加载中…' : '上拉加载更早消息'}
          </p>
        )}
        {chat.loading ? (
          <p className="hint">正在加载会话…</p>
        ) : (
          <>
            {chat.entries.map((entry) => (
              <MessageItem key={entry.seq} entry={entry} sessionId={sessionId} />
            ))}
            {draftBlocks.length > 0 && (
              <div className="msg msg-assistant">
                <div className={`bubble bubble-assistant${draftStreaming ? ' bubble-streaming' : ''}`}>
                  <Blocks blocks={draftBlocks} sessionId={sessionId} streaming={draftStreaming} />
                </div>
              </div>
            )}
            {!chat.running && chat.entries.length === 0 && draftBlocks.length === 0 && (
              <p className="hint">开始和 DeepSeek Harness 对话吧</p>
            )}
          </>
        )}
      </div>
      <Composer
        running={chat.running}
        onSend={(text) => void chat.sendPrompt(text)}
        onCancel={() => void chat.cancelTurn()}
      />
      {chat.approval !== null && (
        <ApprovalDialog request={chat.approval} onAnswer={(outcome) => void chat.answerApproval(outcome)} />
      )}
      {chat.questions !== null && (
        <QuestionsDialog request={chat.questions} onAnswer={(answers) => void chat.answerQuestions(answers)} />
      )}
    </main>
  )
}
