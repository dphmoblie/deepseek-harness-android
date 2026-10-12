import { useEffect, useId, useState } from 'react'
import { t } from '../i18n'
import './ReleaseNotes.css'

export function ReleaseNotes({ notes }: { notes: string }) {
  const [expanded, setExpanded] = useState(false)
  const contentId = useId()
  const lines = notes.replace(/\r\n?/g, '\n').split('\n')
  const collapsible = Array.from(notes).length > 320 || lines.length > 6
  const preview = Array.from(lines.slice(0, 6).join('\n')).slice(0, 320).join('')

  useEffect(() => { setExpanded(false) }, [notes])

  return (
    <div className="release-notes">
      <div
        id={contentId}
        className={`release-notes-content${collapsible ? expanded ? ' is-expanded' : ' is-collapsed' : ''}`}
        role={collapsible && expanded ? 'region' : undefined}
        aria-label={collapsible && expanded ? t('完整更新公告') : undefined}
        tabIndex={collapsible && expanded ? 0 : undefined}
      >
        {/* 公告来自远端，仅作为 React 文本渲染，不解析 HTML。 */}
        {collapsible && !expanded ? `${preview}…` : notes}
      </div>
      {collapsible && (
        <button
          className="button button-secondary release-notes-toggle"
          type="button"
          aria-expanded={expanded}
          aria-controls={contentId}
          onClick={() => { setExpanded(value => !value) }}
        >
          {expanded ? t('收起更新公告') : t('展开完整更新公告')}
        </button>
      )}
    </div>
  )
}
