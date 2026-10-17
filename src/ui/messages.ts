/**
 * 面向用户的错误文案。
 *
 * 从 `App.tsx` 整体搬出：这些内容被**所有**屏幕用到（对话门屏、设置、文件管理、版本管理），
 * 留在外壳里会让每个拆出去的子组件反向 import `App.tsx`，形成循环依赖。
 * 搬运是纯机械的：错误码表、`runtimeErrorMessage`、`errorMessage` 一行未改。
 */
import { t } from '../i18n'

export type NoticeTone = 'success' | 'error' | 'info'

const MAX_NOTICE_CHARACTERS = 240

const UNKNOWN_RUNTIME_ERROR_MESSAGE = '运行环境操作失败，请重试；若仍然失败，请查看「诊断与日志」。'
const RUNTIME_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  SOURCE_INCOMPLETE: '请同时配置运行时清单地址和 SHA-256，或同时留空。',
  URL_INVALID: '运行时下载地址格式无效。',
  URL_HOST_NOT_ALLOWED: '运行时下载地址必须使用允许的公网 HTTPS 主机。',
  DIGEST_INVALID: '配置的 SHA-256 格式无效。',
  DOWNLOAD_FAILED: '运行时下载失败，请稍后重试。',
  DOWNLOAD_HOST_NOT_ALLOWED: '运行时归档与清单必须使用同一下载主机。',
  DOWNLOAD_NETWORK_UNAVAILABLE: '网络不可用或下载连接已中断，可稍后继续。',
  DOWNLOAD_TIMEOUT: '下载连接或读取超时，可稍后继续。',
  DOWNLOAD_TLS_FAILED: '下载服务的 TLS 校验失败。',
  DOWNLOAD_HOST_UNRESOLVED: '无法解析下载主机。',
  DOWNLOAD_HTTP_ERROR: '下载服务返回了错误响应。',
  DOWNLOAD_INCOMPLETE: '下载尚未完成，再次安装时会继续。',
  DOWNLOAD_RANGE_INVALID: '下载服务返回了无效的断点响应。',
  DOWNLOAD_REDIRECT_LIMIT: '下载重定向次数过多。',
  DOWNLOAD_TOO_LARGE: '下载内容超过清单声明或应用大小限制。',
  DOWNLOAD_PART_CHANGED: '断点文件在下载期间发生变化，请重试。',
  DOWNLOAD_PART_INVALID: '断点文件无效，请重置环境后重试。',
  MANIFEST_DIGEST_MISMATCH: '运行时清单完整性校验失败。',
  MANIFEST_INVALID: '运行时清单格式无效。',
  MANIFEST_SCHEMA_UNSUPPORTED: '当前应用不支持此运行时清单版本。',
  MANIFEST_SIZE_INVALID: '运行时清单中的大小信息无效。',
  ARCHITECTURE_UNSUPPORTED: '运行时架构与当前设备不兼容。',
  ENTRYPOINT_NOT_ALLOWED: '运行时清单包含不允许的启动入口。',
  HARNESS_URL_INVALID: '运行时清单中的 Harness 地址无效。',
  ARCHIVE_COMPRESSION_UNSUPPORTED: '当前应用不支持此运行时归档格式。',
  ROOTFS_DIGEST_MISMATCH: '运行时归档完整性校验失败。',
  ARCHIVE_DIGEST_MISMATCH: '运行环境安装包未通过完整性校验，文件可能不完整或与清单不匹配。',
  ARCHIVE_SOURCE_DIGEST_MISMATCH: '解压时读取的运行时归档未通过完整性复核。',
  ARCHIVE_SOURCE_SIZE_MISMATCH: '解压时读取的运行时归档大小与清单不一致。',
  ARCHIVE_SIZE_MISMATCH: '运行环境安装包的大小与清单不一致。',
  ARCHIVE_EXPANSION_LIMIT: '运行时归档解压后超过允许大小。',
  ARCHIVE_ENTRY_LIMIT: '运行时归档包含过多文件。',
  ARCHIVE_FEATURE_UNSUPPORTED: '运行时归档包含不支持的文件特性。',
  ARCHIVE_ENTRY_TYPE_REJECTED: '运行时归档包含不允许的文件类型。',
  ARCHIVE_PATH_INVALID: '运行时归档包含无效路径。',
  ARCHIVE_PATH_CONFLICT: '运行时归档中的文件路径发生冲突。',
  ARCHIVE_DUPLICATE_ENTRY: '运行时归档包含重复文件。',
  ARCHIVE_LINK_INVALID: '运行时归档包含无效链接。',
  ARCHIVE_TRUNCATED: '运行时归档内容不完整。',
  ARCHIVE_EXTRACTION_FAILED: '无法解压运行时归档。',
  BUNDLED_RUNTIME_MISSING: 'APK 未包含完整的内置运行时。',
  BUNDLED_RUNTIME_READ_FAILED: '无法读取 APK 内置运行时。',
  FILESYSTEM_ERROR: '无法安全读写应用私有运行时文件，请检查可用存储空间。',
  FILESYSTEM_SECURE_DELETE_UNAVAILABLE: '当前设备无法安全清理运行时文件。',
  CLEANUP_FAILED: '无法完整清理旧运行时文件，请重试。',
  RESET_SCOPE_INVALID: '为保护应用数据，已拒绝范围异常的文件清理操作。',
  STAGING_NOT_EMPTY: '运行时暂存目录状态异常，请重试。',
  RUNTIME_RECOVERY_FAILED: '无法恢复上次中断的运行时安装。',
  RUNTIME_PROMOTION_FAILED: '无法启用已完成校验的运行时。',
  RUNTIME_PRESERVE_FAILED: '旧运行时里的用户数据未能放回新运行时；数据与旧运行时备份均已保留，请勿重置环境。',
  INSTALL_IN_PROGRESS: '运行时安装正在进行。',
  INSTALL_CANCELLED: '运行时安装已取消，再次安装时可继续下载。',
  INSTALL_FAILED: '运行时安装失败，请稍后重试。',
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端。',
  /*
   * 桥接实例（WebView 插件实例）被销毁、进程级运行时控制器已释放后仍来启动的失败。
   *
   * 文案必须与实际情况一致：调用方**已经**自动重连过一次（见 `openHarness` 的至多一次恢复重试），
   * 所以这里不能写「正在连接」或「请等待」——那会让用户对着一个不会再发生的事等下去。
   * 写清「旧连接已失效 + 已尝试重连 + 请再试一次」，用户才知道下一次点击是有意义的。
   */
  RUNTIME_CLOSED: '运行时已关闭，旧连接已失效；已尝试重新连接，请再试一次。',
  /*
   * 桥调用在超时窗口内没有任何回应。
   *
   * 这不是「操作失败」而是「不知道成没成」：Capacitor 没有取消语义，底层请求可能仍在
   * 原生侧执行。文案因此只说「没有响应、已停止等待」，不承诺操作没有生效——
   * 后续状态刷新会给出真实结论。
   */
  BRIDGE_TIMEOUT: '设备没有在预期时间内响应，已停止等待；请刷新后确认结果，必要时重试。',
  HARNESS_OPEN_CANCELLED: '打开请求已取消，请重新打开 Harness。',
  RUNTIME_CORRUPTED: '运行时文件已损坏，请重置运行时后重新安装。',
  ROOTFS_LINKS_CORRUPTED: '运行时归档的关键符号链接缺失或损坏，请更换运行时来源后重新安装。',
  RUNNER_UNAVAILABLE: '本机不是 ARM64（arm64-v8a）架构，内置 PRoot 无法运行。请换用 ARM64 真机或确认安装的是新版应用。',
  PROOT_RUNNER_START_FAILED: 'Android 无法执行内置 PRoot，请确认安装的是新版 ARM64 应用。',
  PROOT_RUNNER_TIMEOUT: 'PRoot 自检超时，请停止其他会话后重试。',
  PROOT_RUNNER_REJECTED: '内置 PRoot 未通过启动自检。',
  PROOT_PROBE_TIMEOUT: 'PRoot 启动 Ubuntu 超时。',
  PROOT_PTRACE_DENIED: '系统内核拒绝 PRoot 所需的 ptrace 操作，当前设备可能不兼容。',
  PROOT_SECCOMP_UNAVAILABLE: '系统内核的 seccomp 策略与 PRoot 不兼容。',
  PROOT_GUEST_EXEC_FAILED: 'PRoot 无法加载 Ubuntu 程序。',
  PROOT_GUEST_START_FAILED: 'PRoot 无法启动 Ubuntu 用户空间。',
  RUNNER_PREPARE_FAILED: '无法准备内置 PRoot 运行器。',
  PROOT_REQUIRED_BIND_FAILED: 'PRoot 无法挂载 Ubuntu 必需的 DNS、设备或进程路径。',
  NODE_RUNTIME_FAILED: '内置 Node.js 无法在当前设备运行。',
  NODE_CPU_UNSUPPORTED: '设备 CPU 无法执行内置 Node.js。',
  HARNESS_PREFLIGHT_FAILED: 'Harness 命令未通过启动自检。',
  CREDENTIALS_DECRYPT_FAILED: '无法读取已保存的模型密钥；原有数据已保留，请稍后重试。',
  CREDENTIALS_ENCRYPT_FAILED: '无法安全保存模型密钥；请稍后重试。',
  RUNTIME_CONFIG_FAILED: '无法生成模型供应商启动配置，请检查运行时文件后重试。',
  HARNESS_PORT_IN_USE: 'Harness 本机端口已被占用，请停止占用端口的程序后重试。',
  HARNESS_MODULE_MISSING: 'Harness 运行模块不完整。',
  HARNESS_NATIVE_MODULE_FAILED: 'Harness 原生模块无法在当前设备运行。',
  HARNESS_START_TIMEOUT: 'Harness 首次启动超时，请重试或先打开 Ubuntu 终端检查环境。',
  HARNESS_AUTH_UNAVAILABLE: 'Harness 未提供有效的网页认证入口，请更新运行环境后重试。',
  HARNESS_EXITED: 'Harness 在完成启动前已退出。',
  HARNESS_STOP_FAILED: '无法停止 Harness 进程，请重试。',
  HARNESS_STOP_TIMEOUT: 'Harness 未在限定时间内停止，请重试。',
  HARNESS_STOP_INTERRUPTED: 'Harness 停止操作被中断，请重试。',
  SHIZUKU_UNBIND_TIMEOUT: 'Shizuku 设备服务未在限定时间内退出，请在 Shizuku 中重启服务后重试；仍无响应时请完全退出并重开本应用。',
  SHIZUKU_UNBIND_INTERRUPTED: 'Shizuku 设备服务停止操作被中断，请重试。',
  SHIZUKU_UNBIND_FAILED: '无法停止 Shizuku 设备服务，请重试。',
  SHIZUKU_DISCONNECTING: 'Shizuku 设备服务正在停止，请稍后重试。',
  /*
   * Shizuku 连接失败的文案必须给出「下一步做什么」。
   *
   * 真机反馈的场景是**刚更新过 Shizuku**：手机上还在跑更新前启动的那个服务端进程，
   * 于是绑定用户服务超时或失败（状态读取也可能一起挂在它上面）。本应用没有重启
   * Shizuku 服务的接口——那是 Shizuku 自己的事——所以这里直说两条可行操作：
   * 在 Shizuku 里停止服务后重新启动，或重启手机。
   */
  SHIZUKU_SERVICE_TIMEOUT: '连接 Shizuku 设备服务超时。若刚更新过 Shizuku，请在 Shizuku 中停止服务后重新启动（或重启手机），再回到本页重试。',
  SHIZUKU_SERVICE_FAILED: '无法连接 Shizuku 设备服务。若刚更新过 Shizuku，请在 Shizuku 中停止服务后重新启动（或重启手机），再回到本页重试。',
  SHIZUKU_SERVICE_INTERRUPTED: '连接 Shizuku 设备服务被中断，请重试。',
  SHIZUKU_BINDER_TIMEOUT: '未能连接 Shizuku：请先打开 Shizuku 确认服务已启动；若刚更新过 Shizuku，请停止服务后重新启动。',
  SHIZUKU_BINDER_UNAVAILABLE: 'Shizuku 连接已断开：请打开 Shizuku 重新启动服务后再连接。',
}

export function runtimeErrorMessage(errorCode?: string): string {
  if (errorCode === undefined) return t("请重试；若问题持续，可在「Ubuntu 运行时」页重置后重新安装，重置会清除运行时内的数据。")
  return t(RUNTIME_ERROR_MESSAGES[errorCode] ?? UNKNOWN_RUNTIME_ERROR_MESSAGE)
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error && 'code' in error && typeof error.code === 'string' && Object.hasOwn(RUNTIME_ERROR_MESSAGES, error.code)) {
    return runtimeErrorMessage(error.code)
  }
  if (!(error instanceof Error)) return t("操作失败，请稍后重试")
  const message = Array.from(error.message.trim())
    .map(character => {
      const code = character.charCodeAt(0)
      return code <= 31 || code === 127 ? ' ' : character
    })
    .slice(0, MAX_NOTICE_CHARACTERS)
    .join('')
    .trim()
  return message === '' ? t("操作失败，请稍后重试") : t(message)
}
