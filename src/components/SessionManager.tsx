import { useMemo, useState } from 'react'
import { ArrowLeft, MessageCircle, Plus, Search, Trash2 } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import './SessionManager.css'

interface SessionEntry {
  id: string
  title: string
}

interface SessionManagerProps {
  onBack: () => void
  onOpenHarness: () => void
  embedded?: boolean
}

const INITIAL_SESSIONS: SessionEntry[] = []

/**
 * 外壳会话管理页。
 *
 * Harness 内部的会话由运行时管理，外壳不读取也不复制会话正文；这里仅提供
 * 轻量入口、筛选和本地导航状态，避免把可能含敏感信息的对话内容写入浏览器存储。
 */
export function SessionManager({ onBack, onOpenHarness, embedded = false }: SessionManagerProps) {
  useLanguage()
  const [sessions, setSessions] = useState<SessionEntry[]>(INITIAL_SESSIONS)
  const [query, setQuery] = useState('')
  const filtered = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase()
    if (normalized === '') return sessions
    return sessions.filter(session => session.title.toLocaleLowerCase().includes(normalized))
  }, [query, sessions])

  const createSession = (): void => {
    const id = `local-${Date.now().toString(36)}`
    const number = sessions.length + 1
    setSessions(current => [{ id, title: t('新会话 {0}', number) }, ...current])
    onOpenHarness()
  }

  const removeSession = (id: string): void => {
    setSessions(current => current.filter(session => session.id !== id))
  }

  return (
    <div className={embedded ? 'session-manager-embedded' : 'screen session-screen'}>
      {!embedded && <div className="screen-heading management-heading session-heading">
        <div>
          <p className="eyebrow">{t('会话工作区')}</p>
          <h1>{t('会话管理')}</h1>
        </div>
        <div className="heading-actions">
          <button className="button button-primary compact-button" type="button" onClick={createSession}>
            <Plus size={17} />{t('新建会话')}
          </button>
          <button className="icon-button" type="button" title={t('返回对话')} aria-label={t('返回对话')} onClick={onBack}>
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>}

      <div className="session-toolbar">
        <label className="session-search">
          <Search size={17} aria-hidden="true" />
          <span className="sr-only">{t('搜索会话')}</span>
          <input value={query} onChange={event => setQuery(event.target.value.slice(0, 80))} placeholder={t('搜索会话')} maxLength={80} />
        </label>
        <span className="session-count">{t('{0} 个会话', sessions.length)}</span>
      </div>

      <section className="session-list" aria-label={t('会话列表')}>
        {filtered.length === 0 ? (
          <div className="session-empty">
            <MessageCircle size={24} />
            <strong>{t(sessions.length === 0 ? '暂无会话' : '没有匹配的会话')}</strong>
            <p>{t(sessions.length === 0 ? '点击「新建会话」开始工作。' : '换一个关键词，或新建会话开始工作。')}</p>
          </div>
        ) : filtered.map(session => (
          <article className="session-card" key={session.id}>
            <button className="session-open" type="button" onClick={onOpenHarness}>
              <span className="session-icon"><MessageCircle size={18} /></span>
              <span className="session-copy"><strong>{session.title}</strong><small>{t('由 Harness 管理会话内容')}</small></span>
            </button>
            <button className="icon-button session-delete" type="button" aria-label={t('删除会话 {0}', session.title)} title={t('删除会话')} onClick={() => removeSession(session.id)}>
              <Trash2 size={17} />
            </button>
          </article>
        ))}
      </section>

      <p className="session-note">{t('会话正文由 Harness 运行时管理，外壳不会复制或上传。')}</p>
    </div>
  )
}
