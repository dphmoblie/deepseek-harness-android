/**
 * 文件管理页：投递区与共享目录的统一浏览器。
 *
 * 从 `App.tsx` 整体搬出：它只吃 props，导航、导入导出、目录白名单的动作都由外壳注入。
 * `FilesRoot` 类型随它一起搬（只有这一页在用）。正文零改动。
 */
import {
  AlertTriangle,
  ArrowLeft,
  ChevronRight,
  FileText,
  Folder,
  FolderInput,
  FolderOutput,
  FolderPlus,
  HardDrive,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Trash2,
} from 'lucide-react'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
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
 * 「文件管理」页当前浏览的根。
 *
 * 两种根的**语义不同**，这里只是把它们放进同一个浏览器里：
 *  - 投递区（inbox / outbox）是「校验式批量搬运」通道：目录由原生侧管理，导入/导出走 tar + manifest；
 *  - 共享目录是「实时挂载」：用户在系统里点选的手机目录，挂进访客的 `/mnt/user/<序号>`，直接可读可写。
 *
 * 正因为语义不同，导入到工作区/导出工作区**只在投递区根上渲染**；把两套动作混在一起会让
 * 用户以为共享目录也要「搬运」一次才能用。
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
  onBack: () => void
}

/**
 * 文件管理（一级页）：把投递区与共享目录收敛成**同一个文件浏览器**。
 *
 * 为什么合并浏览层：对用户来说这两边都是「我在里面能看到、能建文件夹的目录」，
 * 分成两套界面时，用户要在两个地方各学一遍「怎么看子目录、怎么建文件夹」；
 * 合并之后只有一套面包屑 + 条目列表 + 新建文件夹，根用一个选择器切换。
 *
 * 为什么不把协议也合并：投递区是校验式搬运（tar + manifest + sha256，落点是工作区），
 * 共享目录是实时挂载（直接读写）。协议动作只在投递区根上出现，避免把「实时挂载」
 * 误当成「还要再搬运一次」。底层那部分重复（绑定/缓存、路径校验、列举与建目录）
 * 已经在原生侧合并成一份实现（`RuntimeDirectoryBrowser` + `OptionalBindProvider`）。
 */
export function FilesScreen({ busy, mailbox, mailboxReadFailed, mailboxDirectory, mailboxDirectoryReadFailed, storageAccess, storageDirs, storageDirsReadFailed, storageDirectory, storageDirectoryReadFailed, filesRoot, lastMailboxImport, lastMailboxExport, onAddStorageDirectory, onRemoveStorageDirectory, onImportMailbox, onExportMailbox, onOpenAllFilesAccess, onRefreshMailbox, onSelectFilesRoot, onOpenFilesDirectory, onCreateFilesFolder, onBack }: FilesScreenProps) {
  /**
   * 投递区状态文案。
   *
   * 四个档位各自一句，刻意不合并成「可用 / 不可用」两句：用户需要知道
   * 「去开权限就有用」还是「这台设备根本没有这一档，只能用控制台上传」。
   * 文案只说事实，不承诺授权一定成功。
   */
  const mailboxStatusLabel = mailbox === null
    ? t("未读取")
    : mailbox.availability === 'available'
      ? t("可用")
      : mailbox.availability === 'needsPermission'
        ? t("需要授权")
        : mailbox.availability === 'unsupported'
          ? t("不支持")
          : t("不可写")
  const mailboxUnavailableReason = mailbox === null
    ? ''
    : mailbox.availability === 'needsPermission'
      ? t("尚未授予「所有文件访问」。请到系统设置里为 DSH 手动开启；开启后回到本页会重新检查。没有该权限时只能用终端或控制台上传文件。")
      : mailbox.availability === 'unsupported'
        ? t("当前系统不存在「所有文件访问」这一档，投递区无法启用；请改用终端或控制台上传文件。Harness 本身不受影响。")
        : mailbox.availability === 'unwritable'
          ? t("已授予「所有文件访问」，但投递区目录仍不可读写；可能是系统限制或目录被占用。工作区与 Harness 不受影响。")
          : ''
  /**
   * 共享目录（≤8 条白名单）的状态文案与禁用条件。
   *
   * 判定顺序是有意的：先看「系统有没有这一档」，再看「有没有授权」。系统根本没有这一档时
   * 说「需要授权」会把用户引到一个不存在的开关上（与投递区同一口径）。
   */
  const storageDirsStatusLabel = storageDirs === null
    ? ''
    : !storageDirs.supported
      ? t("系统不支持")
      : !storageDirs.granted
        ? t("需要授权")
        : t("已授权")
  const storageDirsLevelLabel = storageDirs === null
    ? ''
    : storageDirs.level === 'T2'
      ? t("T2 · 所有文件访问（可读可写）")
      : storageDirs.supported ? t("T0 · 未授予「所有文件访问」") : t("T0 · 本机没有这一档")
  /**
   * 未启用时的整段说明；空串表示当前可用。
   *
   * 不支持那一档必须说清**代价**（这些设备上访客内没有共享存储），否则用户会以为功能坏了
   * ——`docs/存储权限与导入落点.md` §4.5 明确要求如此。
   */
  const storageDirsBlockedReason = storageDirs === null
    ? ''
    : !storageDirs.supported
      ? t("当前系统没有「所有文件访问」这一档（Android 11 以下），目录白名单无法启用：这些设备上访客内不提供共享存储。这是用「用户点选的目录」取代 /sdcard 整体绑定的必然代价，不是故障；控制台上传与投递区照常可用。")
      : !storageDirs.granted
        ? t("还没有「所有文件访问」权限：现在不能选新目录，已经选好的目录也不会挂进访客。请点下面的按钮去系统设置开启；回到本页会自动重新检查。")
        : ''
  /**
   * 三种情况都禁用「添加目录」：状态未读到、未授权、已达上限。
   * 上限用 `MAX_STORAGE_DIRECTORIES`（平台层与原生侧钉住是同一个数）：让按钮还能点、
   * 再由原生回一个 `STORAGE_DIR_LIMIT_REACHED`，是纯粹的浪费。
   */
  const storageDirAddDisabled = busy !== null || storageDirs === null || !storageDirs.granted || storageDirs.count >= MAX_STORAGE_DIRECTORIES
  const storageDirLimitReached = storageDirs !== null && storageDirs.granted && storageDirs.count >= MAX_STORAGE_DIRECTORIES
  /** 逐条的可用性标签；四档各一句，与投递区一样不合并成「能用/不能用」。 */
  const storageDirAvailabilityLabel = (entry: StorageDirEntry): string =>
    entry.availability === 'available'
      ? t("可用")
      : entry.availability === 'needsPermission'
        ? t("需要授权")
        : entry.availability === 'unsupported' ? t("系统不支持") : t("不可用")
  /**
   * 逐条不可用的原因。
   *
   * `reasonCode` 在契约里是可选的（校验只要求它与可用性不矛盾），所以缺码时必须如实说
   * 「没给原因」，而不是编一句「可能已被删除」——那是猜测，用户会照着猜错的方向排查。
   */
  const storageDirEntryReason = (entry: StorageDirEntry): string =>
    entry.reasonCode === undefined
      ? t("这一条当前不可用，但原生侧没有返回原因码；请移除后重新添加。")
      : storageDirMessage(entry.reasonCode, undefined)

  const browsingMailbox = filesRoot.kind === 'mailbox'
  const storageEntries = storageDirs?.entries ?? []
  const currentStorageEntry = filesRoot.kind === 'storage'
    ? storageEntries.find(entry => entry.guestPath === filesRoot.guestPath && entry.path === filesRoot.path)
    : undefined
  const directory = browsingMailbox
    ? mailboxDirectory?.root === filesRoot.root ? mailboxDirectory : null
    : storageDirectory?.guestPath === filesRoot.guestPath && currentStorageEntry?.path === filesRoot.path ? storageDirectory : null
  /** 当前根下正在浏览的相对路径；undefined 表示就在根目录上。 */
  const currentPath = directory?.path
  /**
   * 两种根的条目形状本来就一样（`{ name, kind, bytes }`，共用同一个原生 `toJs()`），
   * 所以列表只写一份；这不是「凑巧」，是原生两侧刻意共用同一份实现。
   */
  const entries = directory?.entries ?? []
  const readFailed = browsingMailbox ? mailboxDirectoryReadFailed : storageDirectoryReadFailed
  const snapshotReady = directory !== null
  const truncated = directory?.truncated === true
  const rootUsable = browsingMailbox ? mailbox?.available === true : currentStorageEntry?.available === true
  /** 面包屑最左边的根名字：投递区是 inbox/outbox，共享目录用它的显示名（没有就退回挂载点）。 */
  const rootLabel = browsingMailbox
    ? (filesRoot.root)
    : (currentStorageEntry?.displayName ?? filesRoot.guestPath)
  const exportToCurrentDirectory = browsingMailbox && filesRoot.root === 'outbox' && directory?.path !== undefined
  const createFolderDisabled = busy !== null || !rootUsable || !snapshotReady || readFailed

  return (
    <div className="screen files-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t("文件管理")}</h1>
        </div>
        <div className="heading-actions">
          <button className="icon-button" type="button" aria-label={t("返回设置")} title={t("返回设置")} onClick={onBack}><ArrowLeft size={19} /></button>
        </div>
      </div>

      {/*
        这一段是这一页的存在理由，必须写在最前面：用户最容易搞混的正是
        「哪些目录是搬进来的、哪些是本来就挂着的」，以及为什么只有 inbox / outbox 有搬运按钮。
      */}
      <p className="settings-note">
        {t("这里的目录都是访客能看到的「外部工作区」：手机上的文件夹挂进访客的 /mnt/user/<序号>，可以直接读写；inbox / outbox 是批量搬运通道，只能靠按钮搬运。")}
      </p>

      <section className="settings-section" aria-labelledby="files-browser">
        <div className="section-title section-title-action">
          <span className="section-icon"><FolderInput size={19} /></span>
          <div>
            <h2 id="files-browser">{t("外部工作区")}</h2>
            <p>{t("选一个目录浏览它的子目录；导入到工作区与导出工作区只对 inbox / outbox 有效。")}</p>
          </div>
          <button className="button button-secondary mailbox-create-button" type="button" onClick={onCreateFilesFolder} disabled={createFolderDisabled}>
            {busy === 'mailbox-folder-create' || busy === 'files-storage-folder-create' ? <Loader2 className="spin" size={17} /> : <FolderPlus size={17} />}
            {t("新建文件夹")}
          </button>
        </div>

        <div className="mailbox-root-tabs" role="tablist" aria-label={t("选择要浏览的目录")}>
          <button className={`mailbox-root-tab ${browsingMailbox && filesRoot.root === 'inbox' ? 'active' : ''}`} type="button" role="tab"
            aria-selected={browsingMailbox && filesRoot.root === 'inbox'} onClick={() => onSelectFilesRoot({ kind: 'mailbox', root: 'inbox' })}
            disabled={busy !== null || mailbox?.available !== true}>
            <Folder size={16} />{t("inbox · 用户放入")}
          </button>
          <button className={`mailbox-root-tab ${browsingMailbox && filesRoot.root === 'outbox' ? 'active' : ''}`} type="button" role="tab"
            aria-selected={browsingMailbox && filesRoot.root === 'outbox'} onClick={() => onSelectFilesRoot({ kind: 'mailbox', root: 'outbox' })}
            disabled={busy !== null || mailbox?.available !== true}>
            <Folder size={16} />{t("outbox · 产物取出")}
          </button>
          {storageEntries.map(entry => {
            const selected = filesRoot.kind === 'storage' && filesRoot.guestPath === entry.guestPath && filesRoot.path === entry.path
            return (
              <button className={`mailbox-root-tab ${selected ? 'active' : ''}`} key={entry.guestPath} type="button" role="tab"
                aria-selected={selected} onClick={() => onSelectFilesRoot({ kind: 'storage', guestPath: entry.guestPath, path: entry.path })}
                /*
                  不可用的条目禁用但要留下原因：禁用按钮不参与焦点，读屏用户看不到 title，
                  所以下面白名单列表里那条「不可用原因」才是真正的解释入口，这里只补一个悬停提示。
                */
                title={entry.available ? entry.guestPath : `${entry.guestPath} · ${storageDirEntryReason(entry)}`}
                disabled={busy !== null || !entry.available}>
                <HardDrive size={16} />{entry.displayName}
              </button>
            )
          })}
        </div>

        {storageEntries.length === 0 && (
          <p className="settings-note">{t("还没有挂进来的目录：在下面「目录」里添加一个手机上的目录，它会出现在这里。")}</p>
        )}

        {readFailed && (
          <p className="settings-note" role="alert">
            {browsingMailbox ? t("无法读取当前搬运目录，请重试") : t("无法读取当前目录，请重试")}
            <button className="button button-secondary compact-button" type="button" onClick={() => onOpenFilesDirectory(currentPath)} disabled={busy !== null}>{t("重试")}</button>
          </p>
        )}

        {snapshotReady && !readFailed && rootUsable && (
          <>
            <nav className="mailbox-breadcrumb" aria-label={t("当前目录")}>
              <button type="button" onClick={() => onOpenFilesDirectory()} disabled={busy !== null}>
                {rootLabel}
              </button>
              {(currentPath?.split('/') ?? []).map((segment, index, segments) => {
                const path = segments.slice(0, index + 1).join('/')
                return <span key={path} className="mailbox-breadcrumb-segment">
                  <ChevronRight size={14} aria-hidden="true" />
                  <button type="button" onClick={() => onOpenFilesDirectory(path)} disabled={busy !== null}>{segment}</button>
                </span>
              })}
            </nav>
            {entries.length === 0 && (
              <p className="settings-note">{t("当前目录为空")}</p>
            )}
            {entries.length > 0 && (
              <div className="mailbox-entry-list" role="list">
                {entries.map(entry => {
                  const nextPath = currentPath ? `${currentPath}/${entry.name}` : entry.name
                  return entry.kind === 'directory'
                    ? <button className="mailbox-entry mailbox-entry-directory" key={entry.name} type="button" role="listitem"
                      onClick={() => onOpenFilesDirectory(nextPath)} disabled={busy !== null}>
                      <Folder size={17} /><span>{entry.name}</span><ChevronRight size={15} />
                    </button>
                    : <div className="mailbox-entry mailbox-entry-file" key={entry.name} role="listitem">
                      <FileText size={17} /><span>{entry.name}</span><small>{formatBytes(entry.bytes)}</small>
                    </div>
                })}
              </div>
            )}
            {truncated && (
              <p className="settings-note">{t("当前目录条目较多，仅显示前 {0} 项；请进入子目录继续浏览。", entries.length)}</p>
            )}
          </>
        )}
      </section>

      {/*
        搬运块：状态、搬运按钮与说明。
        只在浏览 inbox / outbox 时渲染 —— 协议动作只属于这两个根，摆在别的目录下面会让人以为它也能用。
      */}
      {browsingMailbox && (
        <div className="settings-subsection" role="region" aria-labelledby="files-mailbox">
          <div className="section-title section-title-action">
            <span className="section-icon"><FolderInput size={19} /></span>
            <div>
              <h3 id="files-mailbox">{t("搬运")}</h3>
              <p>{t("inbox / outbox 与访客之间的批量搬运；不会自动搬运，必须点下面的按钮。")}</p>
            </div>
            <span className={`status-chip ${mailbox?.available === true ? 'success' : 'warn'}`}>{mailboxStatusLabel}</span>
          </div>

          {mailbox === null && (
            <p className="settings-note" role={mailboxReadFailed ? 'alert' : undefined}>
              {mailboxReadFailed ? t("无法读取搬运状态，请重试") : t("正在读取搬运状态")}
            </p>
          )}

          {mailbox !== null && (
            <>
              <div className="settings-status-list">
                <div className="settings-status-row">
                  <span>{t("用户放入（inbox）")}</span>
                  <code className="mailbox-path">{mailbox.inboxPath}</code>
                </div>
                <div className="settings-status-row">
                  <span>{t("产物取出（outbox）")}</span>
                  <code className="mailbox-path">{mailbox.outboxPath}</code>
                </div>
                <div className="settings-status-row">
                  <span>{t("访客内挂载点")}</span>
                  <code className="mailbox-path">{`${mailbox.guestInboxPath} · ${mailbox.guestOutboxPath}`}</code>
                </div>
                <div className="settings-status-row">
                  <span>{t("inbox 内文件")}</span>
                  <strong>{t("{0} 个", mailbox.inboxFileCount)}</strong>
                </div>
              </div>

              {mailbox.inboxTars.length > 0 && (
                <p className="settings-note">
                  {t("可导入的 tar：")}
                  {mailbox.inboxTars.map(candidate => `${candidate.name}（${formatBytes(candidate.bytes)}）`).join('、')}
                </p>
              )}

              {/*
                不可用说明刻意**不带 role="alert"**：它是这一页的常驻内容，不是用户操作后
                才出现的时效性提示；真正该被播报的是那些随操作出现的警告。
              */}
              {!mailbox.available && (
                <div className="inline-alert warning">
                  <AlertTriangle size={19} />
                  <div>
                    <strong>{t("搬运不可用")}</strong>
                    <span>{mailboxUnavailableReason}</span>
                  </div>
                </div>
              )}
            </>
          )}

          {/*
            按钮区始终渲染（即使状态还没读到）：布局稳定，「不可用即禁用」这条规则
            在三种状态下是同一句话，用户不会看到按钮忽隐忽现。
          */}
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={onImportMailbox} disabled={busy !== null || mailbox?.available !== true}>
              {busy === 'mailbox-import' ? <Loader2 className="spin" size={18} /> : <FolderInput size={18} />}{t("导入到工作区")}</button>
            <button className="button button-secondary" type="button" onClick={onExportMailbox} disabled={busy !== null || mailbox?.available !== true}>
              {busy === 'mailbox-export' ? <Loader2 className="spin" size={18} /> : <FolderOutput size={18} />}
              {exportToCurrentDirectory ? t("导出到当前目录") : t("导出工作区")}</button>
            {mailbox !== null && mailbox.supported && !mailbox.granted && (
              <button className="button button-secondary" type="button" onClick={onOpenAllFilesAccess} disabled={busy !== null}>
                <ShieldCheck size={18} />{t("去开启「所有文件访问」")}</button>
            )}
            <button className="button button-secondary" type="button" onClick={onRefreshMailbox} disabled={busy !== null}>
              {busy === 'mailbox-refresh' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("重新检查")}</button>
          </div>

          {mailbox !== null && (
            <>
              <p className="settings-note">
                {t("导入落点是工作区内的 {0}/ 子目录；导出固定产出 {1} + {2} + {3}（含逐条 sha256）。", mailbox.importDirectory, mailbox.exportTarName, mailbox.exportManifestName, `${mailbox.exportTarName}.sha256`)}
              </p>
              {exportToCurrentDirectory && (
                <p className="settings-note">{t("当前导出目标：outbox/{0}", mailboxDirectory?.path ?? '')}</p>
              )}
              {storageAccess !== null && (
                <p className="settings-note">
                  {t("存储权限：媒体读取（T1）{0} · 所有文件访问（T2）{1}。外部工作区需要 T2。", storageAccess.mediaGranted ? t("已授予") : t("未授予"), storageAccess.allFilesSupported ? (storageAccess.allFilesGranted ? t("已授予") : t("未授予")) : t("系统不支持"))}
                </p>
              )}
              <p className="settings-note">
                {t("inbox / outbox 不是工作区：dsh 的 write 工具写 /mnt/inbox、/mnt/outbox 会失败（实测 EACCES），这是预期行为；搬运只能走这里的按钮。")}
              </p>
            </>
          )}

          {lastMailboxImport !== null && (
            <p className="settings-note">
              {t("最近一次导入：{0} 个条目 · {1} · manifest {2}", lastMailboxImport.entryCount, formatBytes(lastMailboxImport.bytes), lastMailboxImport.manifestName ?? t("未附带"))}
            </p>
          )}
          {lastMailboxExport !== null && (
            <p className="settings-note">
              {t("最近一次导出：{0} 个条目 · {1} · manifest {2}", lastMailboxExport.entryCount, formatBytes(lastMailboxExport.bytes), lastMailboxExport.manifestName)}
            </p>
          )}
        </div>
      )}

      {/*
        目录管理：这一页同时是「看有哪些目录」和「决定有哪些目录」的地方 ——
        用户发现某个目录不在列表里时，下一步就是在这里添加它，不该再跳回另一页。
      */}
      <div className="settings-subsection" role="region" aria-labelledby="files-storage-dirs">
        <div className="section-title section-title-action">
          <span className="section-icon"><HardDrive size={19} /></span>
          <div>
            <h3 id="files-storage-dirs">{t("目录")}</h3>
            {/*
              说明里必须写明「访客内不再有 /sdcard」：旧的 /sdcard 整体绑定已被这份白名单取代
              （登记册 §5.1 / §3.1），用户看不到目录时最容易怀疑是权限坏了。
            */}
            <p>{t("这里点选过的目录会挂进访客：手机上的目录 → 访客内 /mnt/user/<序号>。访客里不再有 /sdcard，能看到哪些用户目录完全由这份列表决定。")}</p>
          </div>
          {storageDirs !== null && (
            <span className={`status-chip ${storageDirs.level === 'T2' ? 'success' : 'warn'}`}>{storageDirsStatusLabel}</span>
          )}
        </div>

        {storageDirs === null && (
          <p className="settings-note" role={storageDirsReadFailed ? 'alert' : undefined}>
            {storageDirsReadFailed ? t("无法读取目录状态，请重试") : t("正在读取目录状态")}
          </p>
        )}

        {storageDirs !== null && (
          <>
            <div className="settings-status-list">
              <div className="settings-status-row">
                <span>{t("已选目录")}</span>
                {/* 上限与原生侧同一个数；载荷校验已把它钉成 8，这里不再自己算一遍。 */}
                <strong>{`${storageDirs.count}/${MAX_STORAGE_DIRECTORIES}`}</strong>
              </div>
              <div className="settings-status-row">
                <span>{t("权限档位")}</span>
                <strong>{storageDirsLevelLabel}</strong>
              </div>
            </div>

            {/*
              与投递区同一口径：不可用说明刻意**不带 role="alert"**——它是这一页的常驻内容，
              不是用户操作后才出现的时效性提示；真正该被播报的是随操作出现的警告（toast）。
            */}
            {storageDirsBlockedReason !== '' && (
              <div className="inline-alert warning">
                <AlertTriangle size={19} />
                <div>
                  <strong>{t("目录当前不可用")}</strong>
                  <span>{storageDirsBlockedReason}</span>
                </div>
              </div>
            )}

            {storageDirs.entries.length > 0 && (
              <div className="storage-dir-list">
                {storageDirs.entries.map(entry => (
                  <div className="storage-dir-row" key={entry.path}>
                    <div className="storage-dir-head">
                      <strong className="storage-dir-name">{entry.displayName}</strong>
                      <span className={`status-chip ${entry.available ? 'success' : 'warn'}`}>{storageDirAvailabilityLabel(entry)}</span>
                    </div>
                    <code className="mailbox-path storage-dir-path">{entry.guestPath}</code>
                    <code className="mailbox-path storage-dir-source">{entry.path}</code>
                    {!entry.available && <p className="storage-dir-reason">{storageDirEntryReason(entry)}</p>}
                    {/*
                      移除按钮对**每一条**都渲染并且始终可点（只受 busy 影响）：
                      条目不可用时用户更需要能清掉它，禁用等于把人锁在失效状态里。
                    */}
                    <div className="storage-dir-actions">
                      <button
                        className="button button-danger-quiet compact-button"
                        type="button"
                        disabled={busy !== null}
                        onClick={() => onRemoveStorageDirectory(entry.path)}
                      >
                        {busy === storageDirRemoveBusyId(entry.path) ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}{t("移除")}</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        {/*
          按钮区始终渲染（与投递区一致）：布局稳定，「不可用即禁用」这条规则在所有状态下
          是同一句话，用户不会看到按钮忽隐忽现。
        */}
        <div className="settings-inline-actions storage-dirs-actions">
          <button className="button button-secondary" type="button" onClick={onAddStorageDirectory} disabled={storageDirAddDisabled}>
            {busy === STORAGE_DIR_ADD_BUSY_ID ? <Loader2 className="spin" size={18} /> : <FolderInput size={18} />}{t("添加目录")}</button>
          {storageDirs !== null && storageDirs.supported && !storageDirs.granted && (
            // 文案与投递区的入口**刻意不同名**：同一个动作在两处出现时，
            // 同名按钮会让「按名字取元素」的测试与读屏用户都分不清点的是哪一个。
            <button className="button button-secondary" type="button" onClick={onOpenAllFilesAccess} disabled={busy !== null}>
              <ShieldCheck size={18} />{t("去系统设置开启所有文件访问")}</button>
          )}
        </div>

        {storageDirs !== null && (
          <>
            <p className="settings-note">
              {t("每行下面两个路径：第一个是访客内的挂载点（/mnt/user/<序号>），第二个是这个目录在手机上的真实位置。")}
            </p>
            {/*
              挂载点跳号是**刻意的稳定语义**（序号来自持久化顺序），必须写成事实而不是 bug：
              用户看到 1、3 没有 2 时最容易以为界面出错。
            */}
            <p className="settings-note">
              {t("不可用的条目仍然占着它的序号：访客里的挂载点会跳号（例如有 1、3 而没有 2）。这是刻意的稳定语义——移除别的条目不会让一个目录换到另一个挂载点上。")}
            </p>
            <p className="settings-note">
              {t("目录白名单是 T2「所有文件访问」这一档的能力；未授予时只能查看，不能添加。")}
            </p>
            <p className="settings-note">
              {t("目录改动在下次启动运行环境时生效：正在运行的访客不会热更新挂载点。")}
            </p>
            <p className="settings-note">
              {t("挂进来的目录是实时挂载：进去之后可以直接读写，inbox / outbox 才需要先搬运一次。")}
            </p>
            {storageDirLimitReached && (
              <p className="settings-note">{t("已达上限：最多只能添加 {0} 个目录，请先移除一个再添加。", MAX_STORAGE_DIRECTORIES)}</p>
            )}
          </>
        )}
      </div>
    </div>
  )
}