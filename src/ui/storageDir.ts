/**
 * 目录白名单（登记册 §5.1）的受控错误码文案与忙碌标识。
 *
 * 从 `App.tsx` 提出来：错误码表、失败文案、按路径区分的 busy id 同时被外壳（发起动作）
 * 与文件管理页（渲染条目状态）使用，留在任一侧都会让另一侧反向依赖。正文零改动。
 */
import { t } from '../i18n'
import { MAX_STORAGE_DIRECTORIES } from '../platform/types'
import { errorMessage } from './messages'

/** 添加目录这条链路的 busy 标识（`busy` 是单个字符串，同一时刻只允许一个操作在飞）。 */
export const STORAGE_DIR_ADD_BUSY_ID = 'storage-dir-add'


/**
 * 目录白名单（登记册 §5.1）的受控错误码 → 中文说明。
 *
 * 一张表服务两处，因为两边用的是**原生侧同一套码**：
 *  1. **添加/移除失败**时桥 reject 的码（`RuntimeStorageDirs.StorageDirCodes`）；
 *  2. **已选条目不可用**时的 `StorageDirEntry.reasonCode`。
 * 分开写两张表迟早会漂移：同一个码在两处给出不同说法，用户以为是两回事。
 *
 * 文案要求是「看完知道下一步做什么」，所以每条都带动作（去哪开权限、换哪种目录名），
 * 而不是复述码的字面意思。{0} 由 [storageDirMessage] 填成目录数量上限。
 */
export const STORAGE_DIR_ERROR_MESSAGES: Record<string, string> = {
  STORAGE_DIR_UNSUPPORTED: '本机系统没有「所有文件访问」这一档（需要 Android 11 及以上），目录白名单无法启用；这些设备上访客内不提供共享存储。',
  STORAGE_DIR_NEEDS_PERMISSION: '还没有「所有文件访问」权限：现在不能选新目录，已选的目录也不会挂进访客。请先在系统设置里为本应用开启该权限，再回到本页重试。',
  STORAGE_DIR_PICKER_UNAVAILABLE: '这台设备上没有可用的目录选择器（部分精简系统移除了文件管理器），无法选择目录。',
  STORAGE_DIR_DOCUMENT_ID_INVALID: '系统返回的选择结果不是可识别的目录，请重新选择一次目录。',
  STORAGE_DIR_VOLUME_UNSUPPORTED: '只能选择「内部共享存储」里的目录：SD 卡、U 盘以及下载页面里的位置都不支持。请从「内部共享存储」进入后再选目录。',
  STORAGE_DIR_ROOT_REJECTED: '不能选择共享存储的根目录，请进入它下面的某个具体目录后再确认。',
  STORAGE_DIR_OUTSIDE_PUBLIC: '这个目录不在本机的内部共享存储范围内（例如来自副用户或工作资料），当前不支持。',
  STORAGE_DIR_ANDROID_REJECTED: '不能选择 Android/ 目录及其子目录，那里是应用私有数据。',
  STORAGE_DIR_PRIVATE_REJECTED: '不能选择应用私有目录，请换一个共享存储里的普通目录。',
  STORAGE_DIR_NOT_A_DIRECTORY: '这个位置已经不是真实目录（可能被删除、改名，或被替换成链接），请重新选择。',
  STORAGE_DIR_UNRESOLVED: '无法把这个选择解析成手机上的真实路径，请换一个目录再试。',
  STORAGE_DIR_UNREADABLE: '这个目录当前不可读（可能已被删除、改名，或系统没有真正授权），请检查后重新选择。',
  STORAGE_DIR_UNBINDABLE: '这个目录名里有运行时不支持的字符（中文、空格或其它特殊符号），无法绑定进访客；请改用只含字母、数字、点、下划线和短横线的目录名。',
  STORAGE_DIR_DUPLICATE: '这个目录已经在列表里了，不用重复添加。',
  STORAGE_DIR_LIMIT_REACHED: '最多只能添加 {0} 个目录，请先移除一个再添加。',
  STORAGE_DIR_SAVE_FAILED: '白名单保存失败（应用私有存储不可写），请稍后重试。',
  STORAGE_DIR_PATH_REQUIRED: '没有拿到要移除的目录路径，无法移除；请重新进入本页后再试。',
  STORAGE_DIR_NOT_FOUND: '这条目录已经不在白名单里了（可能在别处被移除）；请点上面的「重新检查」刷新列表。',
  STORAGE_DIR_PATH_INVALID: '目录路径格式不符合要求，这一条没有被接受。',
  STORAGE_DIR_PREFERENCES_INVALID: '白名单的本地记录已损坏，无法读取；请移除后重新添加目录。',
}

/**
 * 未知错误码的兜底文案：**不把码直接甩给用户当答案**，但把码留在文案里 ——
 * 用户截图反馈时维护者能一眼定位，比「未知错误」有用得多。
 */
const UNKNOWN_STORAGE_DIR_ERROR_MESSAGE = '目录操作失败（错误码 {0}）。请重试；若持续失败，可先移除该条目再重新添加。'

/** 用户在 SAF 选择器里点了返回/取消时的码；它不是故障，界面据此不报错。 */
export const STORAGE_DIR_CANCELLED = 'STORAGE_DIR_CANCELLED'

/**
 * 目录白名单操作的 busy 标识（`busy` 是单个字符串，同一时刻只允许一个操作在飞）。
 *
 * 移除**按路径区分**：同一屏可以有多条目录，共用一个标识会让每条都转圈，
 * 用户以为自己点了好几条。路径只做内存里的键，不进任何提示文案。
 */
export function storageDirRemoveBusyId(path: string): string {
  return `storage-dir-remove:${path}`
}

/**
 * 「文件管理」页里共享目录那一路的 busy 标识。
 *
 * 投递区那一路复用既有的 `mailbox-directory` / `mailbox-folder-create`：同一个浏览器、
 * 同一时刻只可能有一条链路在飞，用两组标识只是为了让两条桥调用在日志里分得清。
 */
export const FILES_STORAGE_DIRECTORY_BUSY_ID = 'files-storage-directory'
export const FILES_STORAGE_FOLDER_CREATE_BUSY_ID = 'files-storage-folder-create'

/**
 * 从桥调用抛出的错误里取出受控错误码。
 *
 * 正常路径上码在 `error.code`：原生侧 `call.reject(message, code)` 过桥后，
 * Capacitor 会把 `{message, code}` 拷进一个 `Error` 实例（`native-bridge.js` 的
 * `returnResult`），所以 `.code` 是字符串。但错误也可能来自浏览器预览桥或更外层的包装，
 * 因此这里**同时接受「message 本身就是一个码」**这一种形态；两处都拿不到时返回 undefined，
 * 由文案兜底如实说明，不猜一个码出来。
 */
export function storageDirErrorCode(error: unknown): string | undefined {
  if (typeof error === 'object' && error !== null) {
    const code = (error as { code?: unknown }).code
    if (typeof code === 'string' && code.startsWith('STORAGE_DIR_')) return code
  }
  const message = error instanceof Error ? error.message.trim() : ''
  return message.startsWith('STORAGE_DIR_') && /^[A-Z_]+$/.test(message) ? message : undefined
}

/**
 * 目录白名单操作的失败文案。
 *
 * 已知码一律换成可操作的中文；未知的 `STORAGE_DIR_*` 码进兜底文案（带码）；
 * 完全取不到码时退回通用 [errorMessage] —— 原生侧那句 message 可能是技术描述，
 * 但它至少是真的，比编一句话好。
 */
export function storageDirMessage(code: string | undefined, error: unknown): string {
  if (code === undefined) return errorMessage(error)
  const known = STORAGE_DIR_ERROR_MESSAGES[code]
  return known === undefined
    ? t(UNKNOWN_STORAGE_DIR_ERROR_MESSAGE, code)
    : t(known, MAX_STORAGE_DIRECTORIES)
}