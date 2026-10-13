import { useEffect, useId, useRef } from 'react'
import { t } from '../i18n'

interface AppUpdateReminderDialogProps {
  version: string
  onDismiss: () => void
  onUpdate: () => void
}

export function AppUpdateReminderDialog({ version, onDismiss, onUpdate }: AppUpdateReminderDialogProps) {
  const titleId = useId()
  const descriptionId = useId()
  const dismissButton = useRef<HTMLButtonElement>(null)
  const updateButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const previousFocus = document.activeElement
    dismissButton.current?.focus()
    return () => {
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus()
    }
  }, [])

  return <div className="dialog-backdrop" role="presentation" onPointerDown={event => {
    if (event.target === event.currentTarget) onDismiss()
  }}>
    <section className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId} onKeyDown={event => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onDismiss()
      } else if (event.key === 'Tab') {
        if (event.shiftKey && document.activeElement === dismissButton.current) {
          event.preventDefault()
          updateButton.current?.focus()
        } else if (!event.shiftKey && document.activeElement === updateButton.current) {
          event.preventDefault()
          dismissButton.current?.focus()
        }
      }
    }}>
      <h2 id={titleId}>{t('发现应用新版本')}</h2>
      <p id={descriptionId}>{t('应用 {0} 已可更新。前往版本管理查看公告和更新选项。', version)}</p>
      <p className="settings-note">{t('可在版本管理中关闭每日更新提醒。')}</p>
      <div className="dialog-actions">
        <button className="button button-secondary" type="button" ref={dismissButton} onClick={onDismiss}>{t('暂不更新')}</button>
        <button className="button button-primary" type="button" ref={updateButton} onClick={onUpdate}>{t('查看更新')}</button>
      </div>
    </section>
  </div>
}
