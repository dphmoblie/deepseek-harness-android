/**
 * 文件管理页：把投递区（inbox / outbox）与共享目录合并到统一的两张卡片中。
 *
 * - 卡片 1（#files-browser）：统一文件浏览器 + 工作区导入/导出操作栏 + 单一权限状态与授权入口；
 *   内置投递目录与用户选择的共享目录共用同一套标签切换、路径摘要、面包屑、新建文件夹、
 *   分享/打开以及工作区 tar 导入/导出流程。
 * - 卡片 2（#files-directories）：统一挂载目录清单（内置投递目录 + 共享目录白名单共用同款目录行元素），
 *   支持一键跳转浏览、添加与移除。
 */
import {
  AlertTriangle,
  ArrowLeft,
  ChevronRight,
  ExternalLink,
  FileText,
  Folder,
  FolderInput,
  FolderOpen,
  FolderOutput,
  FolderPlus,
  HardDrive,
  Loader2,
  RefreshCw,
  Share2,
  ShieldCheck,
  Trash2,
} from 'lucide-react'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
import { WorkspaceFilesCard } from '../components/WorkspaceFilesCard'
import { STORAGE_DIR_ADD_BUSY_ID, storageDirMessage, storageDirRemoveBusyId } from '../ui/storageDir'
import {
  MAX_STORAGE_DIRECTORIES,
  type MailboxDirectoryState,
  type MailboxExportResult,
  type MailboxImportResult,
  type MailboxRoot,
  type MailboxState,
  type StorageAccessState,
  type StorageDirEntry,
  type StorageDirectoryState,
  type StorageDirsState,
} from '../platform/types'

/**
 * 「文件管理」页当前浏览的根：内置投递目录（inbox / outbox）或用户选择的共享目录（`/mnt/user/<序号>`）。
 *
 * 两类根共用同一套文件浏览、目录创建、系统分享/打开与工作区 tar 导入/导出入口。
 */
export type FilesRoot = { kind: 'mailbox'; root: MailboxRoot } | { kind: 'storage'; guestPath: string; path: string }

interface FilesScreenProps {
  busy: string | null
  /** 投递区状态：null 表示尚未读到快照。 */
  mailbox: MailboxState | null
  mailboxReadFailed: boolean
  /** 投递区当前目录快照；null 表示还没读到（尚未浏览过，或读取失败）。 */
  mailboxDirectory: MailboxDirectoryState | null
  mailboxDirectoryReadFailed: boolean
  /** 存储访问状态（T1 媒体只读 / T2 所有文件访问）；null 表示尚未读到。 */
  storageAccess: StorageAccessState | null
  /** 目录白名单（共享目录的唯一来源）；null 表示尚未读到快照。 */
  storageDirs: StorageDirsState | null
  storageDirsReadFailed: boolean
  /** 当前浏览的共享目录快照；null 表示还没读到。 */
  storageDirectory: StorageDirectoryState | null
  storageDirectoryReadFailed: boolean
  /** 当前浏览的根：投递区（inbox/outbox）或一条共享目录。 */
  filesRoot: FilesRoot
  /** 本次会话内最近一次导入/导出结果；null 表示本次会话还没有搬运。 */
  lastMailboxImport: MailboxImportResult | null
  lastMailboxExport: MailboxExportResult | null
  /** 新增一条共享目录：原生侧弹 SAF 目录选择器，用户取消不算故障。 */
  onAddStorageDirectory: () => void
  /** 按宿主路径移除一条共享目录。 */
  onRemoveStorageDirectory: (path: string) => void
  onImportMailbox: () => void
  onExportMailbox: () => void
  /** 跳转到系统「所有文件访问」设置页（投递区与共享目录的唯一解锁入口）。 */
  onOpenAllFilesAccess: () => void
  onRefreshMailbox: () => void
  /** 切换当前浏览的根：只换光标，不动任何文件。 */
  onSelectFilesRoot: (root: FilesRoot) => void
  /** 进入当前根下的子目录（不传表示回到该根目录）。 */
  onOpenFilesDirectory: (path?: string) => void
  /** 在当前目录新建文件夹；名称由用户输入，创建前一律校验。 */
  onCreateFilesFolder: () => void
  /** 对当前目录中的普通文件打开系统分享面板或交给其它应用打开。 */
  onExternalFileAction: (action: 'share' | 'open', path: string) => void
  onBack: () => void
}

export function FilesScreen({
  busy,
  mailbox,
  mailboxReadFailed,
  mailboxDirectory,
  mailboxDirectoryReadFailed,
  storageAccess,
  storageDirs,
  storageDirsReadFailed,
  storageDirectory,
  storageDirectoryReadFailed,
  filesRoot,
  lastMailboxImport,
  lastMailboxExport,
  onAddStorageDirectory,
  onRemoveStorageDirectory,
  onImportMailbox,
  onExportMailbox,
  onOpenAllFilesAccess,
  onRefreshMailbox,
  onSelectFilesRoot,
  onOpenFilesDirectory,
  onCreateFilesFolder,
  onExternalFileAction,
  onBack,
}: FilesScreenProps) {
  const anyReadFailed = mailboxReadFailed || storageDirsReadFailed
  const allFilesSupported = mailbox?.supported ?? storageDirs?.supported ?? storageAccess?.allFilesSupported ?? false
  const allFilesGranted = mailbox?.granted ?? storageDirs?.granted ?? storageAccess?.allFilesGranted ?? false
  const mailboxAvailable = mailbox?.available === true

  // 统一外部存储状态徽章（卡片 1 头部唯一一份权限/可用状态）。
  const overallStatus: { label: string; tone: 'ready' | 'warning' | 'danger' | 'neutral' } = anyReadFailed
    ? { label: t("未读取"), tone: 'warning' }
    : mailbox === null && storageDirs === null
      ? { label: t("未读取"), tone: 'warning' }
      : mailbox?.availability === 'unwritable'
        ? { label: t("不可写"), tone: 'danger' }
        : !allFilesSupported
          ? { label: t("不支持"), tone: 'neutral' }
          : !allFilesGranted
            ? { label: t("需要授权"), tone: 'warning' }
            : { label: t("可用"), tone: 'ready' }

  const needsAllFilesPermission = !anyReadFailed && allFilesSupported && !allFilesGranted
  const unsupportedAllFiles = !anyReadFailed && (mailbox !== null || storageDirs !== null) && !allFilesSupported
  const unwritableMailbox = !anyReadFailed && mailbox?.availability === 'unwritable'

  // 目录浏览层的统一抽象：内置投递区与共享目录共用同一份面包屑、条目列表与导入/导出操作。
  const browsingMailbox = filesRoot.kind === 'mailbox'
  const selectedStorageEntry: StorageDirEntry | undefined = filesRoot.kind === 'storage'
    ? storageDirs?.entries.find(entry => entry.guestPath === filesRoot.guestPath && entry.path === filesRoot.path)
    : undefined
  const activeDirectory: { path?: string; entries: MailboxDirectoryState['entries']; truncated: boolean } | null = browsingMailbox
    ? (mailboxDirectory?.root === filesRoot.root ? mailboxDirectory : null)
    : (storageDirectory?.guestPath === filesRoot.guestPath && selectedStorageEntry?.available ? storageDirectory : null)
  const activeDirectoryReadFailed = browsingMailbox ? mailboxDirectoryReadFailed : storageDirectoryReadFailed
  const activeRootAvailable = browsingMailbox ? mailboxAvailable : selectedStorageEntry?.available === true
  const activePathSegments = activeDirectory?.path ? activeDirectory.path.split('/') : []
  const activeRootLabel = browsingMailbox
    ? filesRoot.root
    : (selectedStorageEntry?.displayName ?? filesRoot.guestPath)
  const activeGuestPath = browsingMailbox
    ? (filesRoot.root === 'inbox'
      ? (mailbox?.guestInboxPath ?? '/mnt/inbox')
      : (mailbox?.guestOutboxPath ?? '/mnt/outbox'))
    : (selectedStorageEntry?.guestPath ?? filesRoot.guestPath)
  const activeHostPath = browsingMailbox
    ? (filesRoot.root === 'inbox'
      ? (mailbox?.inboxPath ?? '/storage/emulated/0/Documents/DSH/inbox')
      : (mailbox?.outboxPath ?? '/storage/emulated/0/Documents/DSH/outbox'))
    : (selectedStorageEntry?.path ?? filesRoot.path)

  // 当前目录可导入的 .tar 归档提示：inbox 根目录直接用 mailbox.inboxTars，其余目录（outbox/子目录/共享目录）从条目列表实时汇总。
  const visibleTars = browsingMailbox && filesRoot.root === 'inbox' && !activeDirectory?.path
    ? (mailbox?.inboxTars ?? [])
    : (activeDirectory?.entries ?? [])
      .filter(entry => entry.kind === 'file' && entry.name.toLowerCase().endsWith('.tar'))
      .slice(0, 5)
      .map(entry => ({ name: entry.name, bytes: entry.bytes }))

  const storageDirLimit = storageDirs?.maxDirectories ?? MAX_STORAGE_DIRECTORIES
  const storageDirCount = storageDirs?.count ?? 0
  const storageDirsCountLabel = storageDirs === null ? t("未读取") : `${storageDirCount}/${storageDirLimit}`
  const storageDirsTierLabel = storageDirs === null
    ? t("未读取")
    : !storageDirs.supported
      ? t("T0 · 本机没有这一档")
      : storageDirs.granted
        ? t("T2 · 所有文件访问（可读可写）")
        : t("T0 · 未授予「所有文件访问」")
  const storageDirList = storageDirs?.entries ?? []
  const canAddStorageDirectory = storageDirs !== null
    && storageDirs.supported
    && storageDirs.granted
    && storageDirs.count < storageDirs.maxDirectories
    && busy === null
  const addingStorageDirectory = busy === STORAGE_DIR_ADD_BUSY_ID
  const mailboxBusy = busy === 'mailbox-import'
    || busy === 'mailbox-export'
    || busy === 'mailbox-refresh'
    || busy === 'mailbox-permission'
    || busy === 'mailbox-directory'
    || busy === 'mailbox-folder-create'
    || busy === 'files-root'
    || busy === 'files-storage-directory'
    || busy === 'files-storage-folder-create'
    || busy === 'external-file-share'
    || busy === 'external-file-open'
  const canCreateActiveFolder = activeRootAvailable && activeDirectory !== null && !activeDirectoryReadFailed && busy === null
  const canTransferWorkspace = activeRootAvailable && busy === null
  const exportToCurrentLabel = (!browsingMailbox || (filesRoot.root === 'outbox' && Boolean(activeDirectory?.path)))
    ? t("导出到当前目录")
    : t("导出工作区")

  const builtInDirectories: Array<{
    root: MailboxRoot
    name: string
    guestPath: string
    hostPath: string
  }> = [
    {
      root: 'inbox',
      name: t("inbox · 用户放入"),
      guestPath: mailbox?.guestInboxPath ?? '/mnt/inbox',
      hostPath: mailbox?.inboxPath ?? '/storage/emulated/0/Documents/DSH/inbox',
    },
    {
      root: 'outbox',
      name: t("outbox · 产物取出"),
      guestPath: mailbox?.guestOutboxPath ?? '/mnt/outbox',
      hostPath: mailbox?.outboxPath ?? '/storage/emulated/0/Documents/DSH/outbox',
    },
  ]

  const storageEntryStatus = (entry: StorageDirEntry): { label: string; tone: 'ready' | 'warning' | 'neutral' } => {
    switch (entry.availability) {
      case 'available':
        return { label: t("可用"), tone: 'ready' }
      case 'needsPermission':
        return { label: t("需要授权"), tone: 'warning' }
      case 'unsupported':
        return { label: t("不支持"), tone: 'neutral' }
      case 'unavailable':
        return { label: t("不可用"), tone: 'warning' }
    }
  }

  return (
    <main className="settings-screen files-screen">
      <header className="settings-header">
        <button type="button" className="icon-button" aria-label={t("返回设置")} onClick={onBack}>
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1>{t("文件管理")}</h1>
        </div>
      </header>

      {/* 卡片 1：统一的外部文件浏览器 + 工作区导入导出 + 单一权限入口 */}
      <section className="settings-section files-card" id="files-browser" aria-labelledby="files-browser-heading">
        <div className="settings-section-head">
          <h2 id="files-browser-heading">{t("外部文件与工作区")}</h2>
          <span className={`status-chip status-chip--${overallStatus.tone}`}>
            {overallStatus.label}
          </span>
        </div>

        <p className="settings-note">
          {t("这里的目录都挂载在访客中，支持浏览与工作区搬运")}
        </p>

        {/* 统一的权限 / 读取状态提示（全页唯一一份，不再在两块重复出现） */}
        {anyReadFailed && (
          <div className="settings-alert settings-alert--warning" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>
              <strong>{t("文件与目录状态读取失败，请重试")}</strong>
              <p>{t("运行时可能暂不可用，请点击「重新检查」")}</p>
            </div>
          </div>
        )}

        {needsAllFilesPermission && (
          <div className="settings-alert settings-alert--warning" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <div className="files-alert-body">
              <strong>{t("需要授予「所有文件访问」")}</strong>
              <p>{t("授权后即可浏览、搬运工作区并挂载共享目录")}</p>
              <button
                type="button"
                className="button primary small"
                disabled={busy !== null}
                onClick={onOpenAllFilesAccess}
              >
                {busy === 'mailbox-permission'
                  ? <Loader2 size={14} className="spin" aria-hidden="true" />
                  : <ShieldCheck size={14} aria-hidden="true" />}
                {busy === 'mailbox-permission' ? t("正在打开系统设置…") : t("去开启「所有文件访问」")}
              </button>
            </div>
          </div>
        )}

        {unsupportedAllFiles && (
          <div className="settings-alert settings-alert--info" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>
              <strong>{t("当前系统不支持「所有文件访问」")}</strong>
              <p>{t("Android 11 以下不支持共享存储，请改用终端上传")}</p>
            </div>
          </div>
        )}

        {unwritableMailbox && (
          <div className="settings-alert settings-alert--danger" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>
              <strong>{t("目录当前不可写")}</strong>
              <p>{t("系统阻止写入内置目录，请检查空间与权限后重试")}</p>
            </div>
          </div>
        )}

        {/* 统一根目录选择器：内置 inbox / outbox + 所有已添加的共享目录 */}
        <div className="mailbox-browser">
          <div className="mailbox-root-tabs" role="tablist" aria-label={t("选择要浏览的目录")}>
            {(['inbox', 'outbox'] as const).map(root => {
              const selected = filesRoot.kind === 'mailbox' && filesRoot.root === root
              return (
                <button
                  key={root}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  className={`mailbox-root-tab${selected ? ' active' : ''}`}
                  disabled={busy !== null}
                  onClick={() => onSelectFilesRoot({ kind: 'mailbox', root })}
                >
                  <Folder size={13} aria-hidden="true" />
                  <span>{root === 'inbox' ? t("inbox · 用户放入") : t("outbox · 产物取出")}</span>
                </button>
              )
            })}
            {storageDirList.map(entry => {
              const selected = filesRoot.kind === 'storage' && filesRoot.guestPath === entry.guestPath && filesRoot.path === entry.path
              return (
                <button
                  key={entry.guestPath}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  className={`mailbox-root-tab${selected ? ' active' : ''}`}
                  disabled={busy !== null}
                  onClick={() => onSelectFilesRoot({ kind: 'storage', guestPath: entry.guestPath, path: entry.path })}
                >
                  <HardDrive size={13} aria-hidden="true" />
                  <span>{entry.displayName}</span>
                </button>
              )
            })}
          </div>

          {/* 当前根路径摘要条：统一显示来源类别、访客挂载点与本机路径 */}
          <div className="files-active-root">
            <div className="files-active-root-head">
              <span className={`files-kind-badge${browsingMailbox ? '' : ' files-kind-badge--shared'}`}>
                {browsingMailbox ? t("内置投递") : t("共享目录")}
              </span>
              <code className="mailbox-path storage-dir-path">{activeGuestPath}</code>
            </div>
            <code className="mailbox-path storage-dir-source">{activeHostPath}</code>
          </div>

          {/* 面包屑 + 新建文件夹工具条 */}
          <div className="mailbox-browser-toolbar">
            <nav className="mailbox-breadcrumb" aria-label={t("当前目录")}>
              <button
                type="button"
                disabled={!activeRootAvailable || busy !== null || activePathSegments.length === 0}
                onClick={() => onOpenFilesDirectory(undefined)}
              >
                {activeRootLabel}
              </button>
              {activePathSegments.map((segment, index) => {
                const target = activePathSegments.slice(0, index + 1).join('/')
                const current = index === activePathSegments.length - 1
                return (
                  <span key={target} className="mailbox-breadcrumb-segment">
                    <ChevronRight size={12} aria-hidden="true" />
                    <button
                      type="button"
                      disabled={!activeRootAvailable || busy !== null || current}
                      onClick={() => onOpenFilesDirectory(target)}
                    >
                      {segment}
                    </button>
                  </span>
                )
              })}
            </nav>
            <button
              type="button"
              className="button secondary small mailbox-create-button"
              disabled={!canCreateActiveFolder}
              onClick={onCreateFilesFolder}
            >
              {(busy === 'mailbox-folder-create' || busy === 'files-storage-folder-create')
                ? <Loader2 size={14} className="spin" aria-hidden="true" />
                : <FolderPlus size={14} aria-hidden="true" />}
              {t("新建文件夹")}
            </button>
          </div>

          {/* 统一条目列表 */}
          {!activeRootAvailable ? (
            <p className="mailbox-browser-note">
              {browsingMailbox
                ? t("内置目录当前不可用，授权后即可查看其中的子目录与文件。")
                : selectedStorageEntry === undefined
                  ? t("所选共享目录已不在挂载列表中，请切换到其他目录。")
                  : (selectedStorageEntry.reasonCode
                    ? storageDirMessage(selectedStorageEntry.reasonCode, t("该共享目录当前不可用"))
                    : t("该共享目录当前不可用"))}
            </p>
          ) : activeDirectoryReadFailed ? (
            <div className="mailbox-browser-error" role="alert">
              <span>{t("无法读取当前目录，请重试")}</span>
              <button
                type="button"
                className="button secondary small"
                disabled={busy !== null}
                onClick={() => onOpenFilesDirectory(activeDirectory?.path)}
              >
                {t("重试")}
              </button>
            </div>
          ) : activeDirectory === null ? (
            <p className="mailbox-browser-note">{t("正在读取当前目录…")}</p>
          ) : activeDirectory.entries.length === 0 ? (
            <p className="mailbox-browser-note">{t("当前目录为空")}</p>
          ) : (
            <div className="mailbox-entry-list" role="list">
              {activeDirectory.entries.map(entry => {
                const target = activeDirectory.path ? `${activeDirectory.path}/${entry.name}` : entry.name
                return entry.kind === 'directory' ? (
                  <button
                    key={`dir:${target}`}
                    type="button"
                    role="listitem"
                    className="mailbox-entry mailbox-entry--dir"
                    disabled={busy !== null}
                    onClick={() => onOpenFilesDirectory(target)}
                  >
                    <Folder size={15} aria-hidden="true" />
                    <span className="mailbox-entry-name">{entry.name}</span>
                    <ChevronRight size={14} aria-hidden="true" />
                  </button>
                ) : (
                  <div key={`file:${target}`} role="listitem" className="mailbox-entry mailbox-entry--file">
                    <FileText size={15} aria-hidden="true" />
                    <span className="mailbox-entry-name">{entry.name}</span>
                    <span className="mailbox-entry-size">{formatBytes(entry.bytes)}</span>
                    <span className="mailbox-entry-actions">
                      <button
                        type="button"
                        className="button secondary small mailbox-entry-action"
                        disabled={busy !== null}
                        aria-label={t("其他应用打开 {0}", entry.name)}
                        onClick={() => onExternalFileAction('open', target)}
                      >
                        <ExternalLink size={12} aria-hidden="true" />
                        {t("打开")}
                      </button>
                      <button
                        type="button"
                        className="button secondary small mailbox-entry-action"
                        disabled={busy !== null}
                        aria-label={t("分享 {0}", entry.name)}
                        onClick={() => onExternalFileAction('share', target)}
                      >
                        <Share2 size={12} aria-hidden="true" />
                        {t("分享")}
                      </button>
                    </span>
                  </div>
                )
              })}
            </div>
          )}

          {activeDirectory?.truncated && (
            <p className="mailbox-browser-note">{t("当前目录条目较多，仅显示前 {0} 项；请进入子目录继续浏览。", 256)}</p>
          )}
        </div>

        {/* 统一工作区导入/导出操作栏：对内置投递目录和已授权共享目录同等生效 */}
        <div className="files-transfer-bar" role="region" aria-label={t("工作区导入与导出")}>
          {activeRootAvailable && (
            <p className="mailbox-meta">
              {visibleTars.length > 0
                ? `${t("可导入的 tar：")}${visibleTars.map(item => `${item.name} (${formatBytes(item.bytes)})`).join('、')}`
                : t("当前目录暂无 .tar 归档")}
            </p>
          )}

          <div className="files-action-row">
            <button
              type="button"
              className="button primary small"
              disabled={!canTransferWorkspace}
              onClick={onImportMailbox}
            >
              {busy === 'mailbox-import'
                ? <Loader2 size={14} className="spin" aria-hidden="true" />
                : <FolderInput size={14} aria-hidden="true" />}
              {busy === 'mailbox-import' ? t("导入中…") : t("导入到工作区")}
            </button>
            <button
              type="button"
              className="button secondary small"
              disabled={!canTransferWorkspace}
              onClick={onExportMailbox}
            >
              {busy === 'mailbox-export'
                ? <Loader2 size={14} className="spin" aria-hidden="true" />
                : <FolderOutput size={14} aria-hidden="true" />}
              {busy === 'mailbox-export' ? t("正在导出…") : exportToCurrentLabel}
            </button>
            <button
              type="button"
              className="button secondary small"
              disabled={busy !== null}
              onClick={onRefreshMailbox}
            >
              {busy === 'mailbox-refresh'
                ? <Loader2 size={14} className="spin" aria-hidden="true" />
                : <RefreshCw size={14} aria-hidden="true" />}
              {t("重新检查")}
            </button>
          </div>

          {lastMailboxImport && (
            <p className="mailbox-meta">
              {t(
                "最近一次导入：{0} 个条目 · {1} · manifest {2}",
                lastMailboxImport.entryCount,
                formatBytes(lastMailboxImport.bytes),
                lastMailboxImport.manifestName ?? t("未附带"),
              )}
              {lastMailboxImport.ignoredFiles > 0 ? t(" · 忽略 {0} 个散文件", lastMailboxImport.ignoredFiles) : ''}
            </p>
          )}
          {lastMailboxExport && (
            <p className="mailbox-meta">
              {t(
                "最近一次导出：{0} 个条目 · {1} · manifest {2}",
                lastMailboxExport.entryCount,
                formatBytes(lastMailboxExport.bytes),
                lastMailboxExport.manifestName,
              )}
              {lastMailboxExport.destinationDirectory ? ` · ${lastMailboxExport.destinationDirectory}` : ''}
            </p>
          )}
          {mailboxBusy && !activeRootAvailable && (
            <p className="mailbox-meta">{t("正在处理文件操作…")}</p>
          )}
        </div>
      </section>

      {/* 卡片 2：统一挂载目录清单（内置投递目录 + 用户选择的共享目录共用同款目录行元素） */}
      <section className="settings-section files-card" id="files-directories" aria-labelledby="files-directories-heading">
        <div className="settings-section-head">
          <h2 id="files-directories-heading">{t("挂载目录")}</h2>
          <span className={`status-chip status-chip--${storageDirs === null ? 'warning' : (storageDirs.supported && storageDirs.granted ? 'ready' : 'neutral')}`}>
            {storageDirsCountLabel}
          </span>
        </div>

        <div className="settings-status-list">
          <div className="settings-status-row">
            <span>{t("存储访问档位")}</span>
            <strong>{storageDirsTierLabel}</strong>
          </div>
          <div className="settings-status-row">
            <span>{t("挂载目录数量")}</span>
            <strong>
              {storageDirs === null
                ? t("未读取")
                : t("内置 {0} 个 · 共享 {1}/{2} 个", builtInDirectories.length, storageDirCount, storageDirLimit)}
            </strong>
          </div>
        </div>

        <div className="storage-dir-list" role="list" aria-label={t("已挂载目录列表")}>
          {builtInDirectories.map(item => {
            const active = filesRoot.kind === 'mailbox' && filesRoot.root === item.root
            const statusTone = mailbox === null
              ? 'warning'
              : mailbox.availability === 'available'
                ? 'ready'
                : mailbox.availability === 'unwritable'
                  ? 'danger'
                  : mailbox.availability === 'needsPermission'
                    ? 'warning'
                    : 'neutral'
            const statusText = mailbox === null
              ? t("未读取")
              : mailbox.availability === 'available'
                ? t("可用")
                : mailbox.availability === 'needsPermission'
                  ? t("需要授权")
                  : mailbox.availability === 'unsupported'
                    ? t("不支持")
                    : t("不可写")
            return (
              <div key={item.root} className={`storage-dir-row${active ? ' storage-dir-row--active' : ''}`} role="listitem">
                <div className="storage-dir-head">
                  <div className="storage-dir-title">
                    <strong className="storage-dir-name">{item.name}</strong>
                    <span className="files-kind-badge">{t("内置")}</span>
                  </div>
                  <span className={`status-chip status-chip--${statusTone}`}>
                    {statusText}
                  </span>
                </div>
                <code className="mailbox-path storage-dir-path">{item.guestPath}</code>
                <code className="mailbox-path storage-dir-source">{item.hostPath}</code>
                <div className="storage-dir-actions">
                  <button
                    type="button"
                    className="button secondary small"
                    disabled={busy !== null || active}
                    onClick={() => onSelectFilesRoot({ kind: 'mailbox', root: item.root })}
                  >
                    <FolderOpen size={13} aria-hidden="true" />
                    {active ? t("正在浏览") : t("浏览")}
                  </button>
                </div>
              </div>
            )
          })}

          {storageDirList.map(entry => {
            const removing = busy === storageDirRemoveBusyId(entry.path)
            const active = filesRoot.kind === 'storage' && filesRoot.guestPath === entry.guestPath && filesRoot.path === entry.path
            const status = storageEntryStatus(entry)
            const reasonText = entry.availability === 'unavailable'
              ? (entry.reasonCode
                ? storageDirMessage(entry.reasonCode, t("该目录当前不可用（错误码 {0}）", entry.reasonCode))
                : t("这一条当前不可用，但原生侧没有返回原因码；请移除后重新添加。"))
              : null
            return (
              <div key={entry.path} className={`storage-dir-row${active ? ' storage-dir-row--active' : ''}`} role="listitem">
                <div className="storage-dir-head">
                  <div className="storage-dir-title">
                    <strong className="storage-dir-name">{entry.displayName}</strong>
                    <span className="files-kind-badge files-kind-badge--shared">{t("共享")}</span>
                  </div>
                  <span className={`status-chip status-chip--${status.tone}`}>
                    {status.label}
                  </span>
                </div>
                <code className="mailbox-path storage-dir-path">{entry.guestPath}</code>
                <code className="mailbox-path storage-dir-source">{entry.path}</code>
                {reasonText && <p className="storage-dir-reason">{reasonText}</p>}
                <div className="storage-dir-actions">
                  <button
                    type="button"
                    className="button secondary small"
                    disabled={busy !== null || active}
                    onClick={() => onSelectFilesRoot({ kind: 'storage', guestPath: entry.guestPath, path: entry.path })}
                  >
                    <FolderOpen size={13} aria-hidden="true" />
                    {active ? t("正在浏览") : t("浏览")}
                  </button>
                  <button
                    type="button"
                    className="button secondary small"
                    disabled={busy !== null}
                    onClick={() => onRemoveStorageDirectory(entry.path)}
                  >
                    {removing
                      ? <Loader2 size={13} className="spin" aria-hidden="true" />
                      : <Trash2 size={13} aria-hidden="true" />}
                    {removing ? t("正在移除…") : t("移除")}
                  </button>
                </div>
              </div>
            )
          })}
        </div>

        <div className="files-action-row storage-dirs-actions">
          <button
            type="button"
            className="button primary small"
            disabled={!canAddStorageDirectory}
            onClick={onAddStorageDirectory}
          >
            {addingStorageDirectory
              ? <Loader2 size={14} className="spin" aria-hidden="true" />
              : <FolderPlus size={14} aria-hidden="true" />}
            {addingStorageDirectory ? t("正在选择目录…") : t("添加目录")}
          </button>
        </div>

        <p className="settings-note">
          {t("增删共享目录将在下次启动运行时生效")}
        </p>
      </section>

      {/* 工作区文件：不必先导出到 outbox 也能把 Agent 写出来的文件取走（见组件注释）。 */}
      <WorkspaceFilesCard />
    </main>
  )
}
