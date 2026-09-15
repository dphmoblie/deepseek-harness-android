package io.deepseekharness.mobile.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ≤8 目录白名单的固定路径与落点（`docs/存储权限与导入落点.md` §三）。
 *
 * ```
 * 手机侧（用户通过 SAF 显式点选）                     访客内（固定挂载点）
 * /storage/emulated/0/Download          →  /mnt/user/1
 * /storage/emulated/0/Documents/Project →  /mnt/user/2
 * 序号 = 白名单里的持久化顺序（稳定，不因某条失效而重排）
 * ```
 *
 * **序号取自持久化顺序而不是「当前可用条目的序号」**：某一条目录被用户删掉/改名时只跳过该条
 * （诊断日志里留痕），其余条目的访客路径不变——否则一次失效就会让所有会话里写死的
 * `/mnt/user/2` 指向另一个目录，那比留一个空洞危险得多。
 *
 * 宿主路径刻意写成 `/storage/emulated/0`（[RuntimeMailboxLayout.PUBLIC_STORAGE]）而不是 `/sdcard`：
 * `/sdcard` 是符号链接，而 PRoot 的绑定源必须是解析后的真实路径——投递区已经踩过这个坑。
 */
object RuntimeStorageDirsLayout {
    /** 主共享存储的真实路径前缀；与投递区共用同一个常量，避免两处口径漂移。 */
    const val PUBLIC_STORAGE = RuntimeMailboxLayout.PUBLIC_STORAGE

    /** SAF document id 的内置存储卷前缀；其余卷（SD 卡、OTG、下载提供方）一律拒绝。 */
    const val PRIMARY_VOLUME = "primary"

    /** 共享存储里对第三方应用没有正当用途的目录。 */
    const val ANDROID_DIRECTORY = "Android"

    /** `Android/` 之下装的是各应用的私有数据，单列出来是为了给出更准确的拒绝理由。 */
    val PRIVATE_SUBDIRECTORIES = setOf("data", "obb")

    /** 访客侧固定挂载点前缀。序号从 1 开始，与白名单顺序一致。 */
    const val GUEST_PREFIX = "/mnt/user"

    /** 宿主侧挂载点相对 rootfs 的路径（逐级 NoFollow 创建）。 */
    const val HOST_PREFIX = "mnt/user"

    fun guestPath(index: Int): String = "$GUEST_PREFIX/$index"

    /** 访客路径是否属于白名单挂载点（供启动档的可选绑定回退判定使用）。 */
    fun isGuestPath(path: String): Boolean = path.startsWith("$GUEST_PREFIX/")
}

/** 白名单限额。上限 8 来自规格；路径数值与投递区同源（同一批文件系统与 PRoot 约束）。 */
object RuntimeStorageDirsLimits {
    const val MAX_DIRECTORIES = 8
    const val MAX_RELATIVE_PATH_CHARS = RuntimeMailboxLimits.MAX_PATH_CHARS
    const val MAX_PATH_DEPTH = RuntimeMailboxLimits.MAX_PATH_DEPTH
    const val MAX_COMPONENT_CHARS = RuntimeMailboxLimits.MAX_COMPONENT_CHARS

    /** 目录标识（document id）的字符上限：SAF 给的 id 可能很长，但没有任何正当理由超过这个量级。 */
    const val MAX_DOCUMENT_ID_CHARS = 1024

    /** 展示名上限：只用于界面标签，不参与任何判定。 */
    const val MAX_DISPLAY_NAME_CHARS = 64
}

/**
 * 白名单受控错误码。
 *
 * 与投递区同一条原则：码只用于分类，**不携带路径**（路径不进诊断日志，也不进审计日志）。
 * 「用户选了 SD 卡」与「用户选了 Android/data」是两件完全不同的事，界面要给不同的提示，
 * 因此不能合并成一个笼统的「目录不可用」。
 */
internal object StorageDirCodes {
    /** 系统不存在「所有文件访问」这一档（Android 11 以下），白名单整体不可用。 */
    const val UNSUPPORTED = "STORAGE_DIR_UNSUPPORTED"

    /** 系统支持但尚未授予「所有文件访问」：给授权入口，不代替用户决定。 */
    const val NEEDS_PERMISSION = "STORAGE_DIR_NEEDS_PERMISSION"

    /** document id 形态非法：空、超长、含控制字符、缺卷分隔符、路径分段非法。 */
    const val DOCUMENT_ID_INVALID = "STORAGE_DIR_DOCUMENT_ID_INVALID"

    /** 非 `primary:` 卷。SD 卡 / OTG 的路径映射不可靠，**拒绝比猜错好**。 */
    const val VOLUME_UNSUPPORTED = "STORAGE_DIR_VOLUME_UNSUPPORTED"

    /** 用户选的是共享存储根（`primary:`）；根被整体绑进访客等于把旧的整体绑定换个名字。 */
    const val ROOT_REJECTED = "STORAGE_DIR_ROOT_REJECTED"

    /** `Android/` 及其子目录。 */
    const val ANDROID_REJECTED = "STORAGE_DIR_ANDROID_REJECTED"

    /** 应用私有目录（`Android/data`、`Android/obb` 之下）。 */
    const val PRIVATE_REJECTED = "STORAGE_DIR_PRIVATE_REJECTED"

    /** 解析符号链接之后落在共享存储之外（含 `/sdcard` 之外的挂载点与应用私有区）。 */
    const val OUTSIDE_PUBLIC = "STORAGE_DIR_OUTSIDE_PUBLIC"

    /** 路径结构非法：非绝对路径、`.` / `..` 分段、空分段、反斜杠、控制字符、超长超深。 */
    const val PATH_INVALID = "STORAGE_DIR_PATH_INVALID"

    /** 无法解析真实路径（`canonicalFile` 抛 IO）。 */
    const val UNRESOLVED = "STORAGE_DIR_UNRESOLVED"

    /** 规范路径不是真实目录（NoFollow）：不存在、被删除、被改名、是符号链接或普通文件。 */
    const val NOT_A_DIRECTORY = "STORAGE_DIR_NOT_A_DIRECTORY"

    /** 目录当前不可读不可进入。 */
    const val UNREADABLE = "STORAGE_DIR_UNREADABLE"

    /**
     * 路径含 PRoot 绑定源不接受的字符（只有 ASCII 字母数字与 `._-`）。
     *
     * 这条不是洁癖：`RuntimeCommand` 的绑定参数校验是白名单正则，非 ASCII 或带空格的目录名
     * 会让 `prootArgv` 抛错，从而**让 PRoot 起不来**。宁可如实拒绝并说清原因，也不能接受一个
     * 进来就会炸掉运行时的目录。
     */
    const val UNBINDABLE = "STORAGE_DIR_UNBINDABLE"

    /** 该目录已在白名单里（重复选择不叠加）。 */
    const val DUPLICATE = "STORAGE_DIR_DUPLICATE"

    /** 已达到 8 个上限。 */
    const val LIMIT_REACHED = "STORAGE_DIR_LIMIT_REACHED"

    /** 要移除的目录不在白名单里。 */
    const val NOT_FOUND = "STORAGE_DIR_NOT_FOUND"

    /** 移除请求缺少 `path`，或类型不是字符串。 */
    const val PATH_REQUIRED = "STORAGE_DIR_PATH_REQUIRED"

    /** 用户在 SAF 选择器里取消：不是故障，界面不该报错。 */
    const val CANCELLED = "STORAGE_DIR_CANCELLED"

    /** 设备上没有能处理 `ACTION_OPEN_DOCUMENT_TREE` 的界面。 */
    const val PICKER_UNAVAILABLE = "STORAGE_DIR_PICKER_UNAVAILABLE"

    /** 落盘失败（偏好写入异常）。 */
    const val SAVE_FAILED = "STORAGE_DIR_SAVE_FAILED"

    /** 偏好内容整份无法解析：没有可保留的条目（与「部分条目失效」区分开）。 */
    const val PREFERENCES_INVALID = "STORAGE_DIR_PREFERENCES_INVALID"
}

/**
 * 单条白名单条目的可用性。
 *
 * 与投递区的 `MailboxAvailability` 同一风格（受控 wire 值 + 权限档），但语义是**逐条**的：
 * 白名单允许「一部分可用、一部分已失效」，整体状态由条目列表表达。
 */
internal enum class StorageDirAvailability(val wireValue: String, val level: String) {
    /** T2：目录存在、是真实目录、可读，会出现在 `/mnt/user/<序号>`。 */
    AVAILABLE("available", "T2"),

    /** 已有「所有文件访问」但这条目录当前用不了（被删/改名/不再满足规则），其余条目不受影响。 */
    UNAVAILABLE("unavailable", "T0"),

    /** 尚未授予「所有文件访问」：此时不判断目录是否存在（结果会误导），只如实说需要授权。 */
    NEEDS_PERMISSION("needsPermission", "T0"),

    /** 系统不存在这一档（Android 11 以下）：白名单整体不可用，条目只保留不生效。 */
    UNSUPPORTED("unsupported", "T0"),
}

/** 一条白名单条目：**持久化的真实路径 + 展示名**；顺序即数组顺序（序号 = 下标 + 1）。 */
internal data class StorageDirEntry(
    val path: String,
    val displayName: String,
)

/** 一次读取的结果：[dropped] 是被丢弃的条目数（形态非法、重复或超限）。 */
internal data class StoredStorageDirs(
    val entries: List<StorageDirEntry>,
    val dropped: Int = 0,
    /** 整份偏好内容无法解析：与「部分条目失效」区分开，前者没有可保留的东西。 */
    val corrupt: Boolean = false,
)

/** 单条条目的实时状态。 */
internal data class StorageDirStatus(
    val index: Int,
    val entry: StorageDirEntry,
    val availability: StorageDirAvailability,
    /** 不可用时的受控错误码；可用时为 null。 */
    val reasonCode: String?,
) {
    val guestPath: String get() = RuntimeStorageDirsLayout.guestPath(index)
    val available: Boolean get() = availability == StorageDirAvailability.AVAILABLE
}

/** 白名单整体状态（桥方法载荷）。 */
internal data class StorageDirsState(
    val supported: Boolean,
    val granted: Boolean,
    val maxDirectories: Int,
    val entries: List<StorageDirStatus>,
) {
    val level: String get() = if (supported && granted) "T2" else "T0"

    /** 至少有一条目录会真的出现在访客里；界面用它决定是否承诺「访客可见」。 */
    val active: Boolean get() = entries.any { it.available }
}

/**
 * 判定白名单所需的最小文件系统视图。
 *
 * 抽出来是**为了能在 JVM 单测里穷举判定链**：策略层只依赖本接口，生产实现才碰 Android API
 * （`Environment`、`java.io.File`）。这样「符号链接逃逸」「目录被删」「非 primary 卷」这些分支
 * 全部可以在单测里构造，而不是只能靠真机试。
 */
internal interface StorageDirFileSystem {
    /** `primary:` 卷映射到的宿主根（生产：`Environment.getExternalStorageDirectory()`）。 */
    val publicStorageRoot: String

    /** 系统是否存在「所有文件访问」这一档（Android 11+）。 */
    fun isFeatureSupported(): Boolean

    /** 是否已实际持有共享存储的路径访问权（T2 = `Environment.isExternalStorageManager()`）。 */
    fun isSharedStorageAccessible(): Boolean

    /** 解析符号链接后的真实路径；解析失败（IO）返回 null。 */
    fun canonicalPath(path: String): String?

    /** 是否为真实目录（NoFollow：符号链接一律不算）。 */
    fun isRealDirectoryNoFollow(path: String): Boolean

    /** 是否可读可进入（PRoot 绑定源至少要能被读取与遍历）。 */
    fun isReadableDirectory(path: String): Boolean
}

/**
 * 白名单的**纯策略**：document id → 相对路径、canonical 路径的准入规则。
 *
 * 本对象不接触文件系统、不读偏好、不依赖 Android API，因此可以在 JVM 单测里穷举
 * （见 `RuntimeStorageDirsPolicyTest`）。它只回答两件事：
 *  1. 这段 SAF document id 能不能映射成一个共享存储内的相对路径；
 *  2. 这条规范路径能不能进白名单（不能的话，是哪一个受控错误码）。
 */
internal object RuntimeStorageDirsPolicy {
    private const val VOLUME_SEPARATOR = ':'
    private const val PATH_SEPARATOR = '/'

    /**
     * SAF 目录树 document id → 共享存储根下的相对路径。
     *
     * 形如 `primary:Documents/Foo` → `Documents/Foo`（**纯字符串**判定，不拼系统路径）。
     * 拒绝：
     *  - 空、超长、含控制字符或没有卷分隔符 → [StorageDirCodes.DOCUMENT_ID_INVALID]；
     *  - 非 `primary:` 卷（SD 卡 / OTG / 文档提供方）→ [StorageDirCodes.VOLUME_UNSUPPORTED]；
     *  - `primary:` 本身（共享存储根）→ [StorageDirCodes.ROOT_REJECTED]；
     *  - `..` / `.` / 空分段 / 反斜杠 / 超长超深 → [StorageDirCodes.DOCUMENT_ID_INVALID]。
     *
     * 前导 `/` 会被去掉：少数 ROM 的提供方给出 `primary:/Documents/Foo`，这与
     * `primary:Documents/Foo` 表达的是同一个目录，属于无歧义归一化，不属于猜测。
     */
    fun relativePathOfDocumentId(documentId: String): String {
        if (
            documentId.isEmpty() || documentId.length > RuntimeStorageDirsLimits.MAX_DOCUMENT_ID_CHARS ||
            documentId.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录标识格式无效")
        }
        val separator = documentId.indexOf(VOLUME_SEPARATOR)
        if (separator <= 0) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录标识缺少存储卷")
        }
        val volume = documentId.substring(0, separator)
        if (volume != RuntimeStorageDirsLayout.PRIMARY_VOLUME) {
            throw RuntimeFailure(
                StorageDirCodes.VOLUME_UNSUPPORTED,
                "只能选择内置共享存储中的目录；SD 卡与外部存储卷的路径不可靠，已拒绝",
            )
        }
        return normalizeRelativePath(documentId.substring(separator + 1))
    }

    /** 从相对路径取展示名：最后一级目录名，截断到上限（不参与任何判定）。 */
    fun displayNameOf(relativePath: String): String {
        val name = relativePath.trimEnd(PATH_SEPARATOR).substringAfterLast(PATH_SEPARATOR)
        return if (name.length <= RuntimeStorageDirsLimits.MAX_DISPLAY_NAME_CHARS) {
            name
        } else {
            name.take(RuntimeStorageDirsLimits.MAX_DISPLAY_NAME_CHARS)
        }
    }

    /**
     * 把 SAF 选择结果解析成可以落盘的宿主绝对路径。
     *
     * 判定顺序即错误码优先级：先映射（document id 规则）→ `canonicalFile` 解析符号链接
     * → 必须是真实目录（NoFollow）→ 必须在共享存储之内（不能是根、`Android/`、应用私有目录）
     * → 路径能作为 PRoot 绑定源。任何一条不过都抛 [RuntimeFailure]，绝不返回一个「先存下再说」的路径。
     */
    fun resolveSelectedDirectory(documentId: String, fileSystem: StorageDirFileSystem): String {
        val relative = relativePathOfDocumentId(documentId)
        val base = fileSystem.publicStorageRoot.trimEnd(PATH_SEPARATOR)
        val canonical = fileSystem.canonicalPath("$base/$relative")
            ?: throw RuntimeFailure(StorageDirCodes.UNRESOLVED, "无法解析所选目录的真实路径")
        if (!fileSystem.isRealDirectoryNoFollow(canonical)) {
            throw RuntimeFailure(StorageDirCodes.NOT_A_DIRECTORY, "所选对象不是真实目录（可能是符号链接或已被删除）")
        }
        requireCanonicalPath(canonical)
        if (!fileSystem.isReadableDirectory(canonical)) {
            throw RuntimeFailure(StorageDirCodes.UNREADABLE, "所选目录当前不可读")
        }
        return canonical
    }

    /**
     * 规范绝对路径的准入规则。**纯字符串判定**：同样的规则既用在「刚选中的目录」上，
     * 也用在「上次存下、这次要绑定的目录」上，因此失效条目不会绕过校验。
     */
    fun requireCanonicalPath(
        canonicalPath: String,
        publicRoot: String = RuntimeStorageDirsLayout.PUBLIC_STORAGE,
    ) {
        val root = publicRoot.trimEnd(PATH_SEPARATOR)
        requireStructuralPath(canonicalPath)
        if (canonicalPath == root) {
            throw RuntimeFailure(StorageDirCodes.ROOT_REJECTED, "不能选择共享存储的根目录")
        }
        if (!canonicalPath.startsWith("$root$PATH_SEPARATOR")) {
            throw RuntimeFailure(StorageDirCodes.OUTSIDE_PUBLIC, "目录不在共享存储之内")
        }
        val segments = canonicalPath.removePrefix("$root$PATH_SEPARATOR").split(PATH_SEPARATOR)
        // `Android/data` 与 `Android/obb` 装的是各应用的私有数据：先于「Android 整体」判定，
        // 是为了给出「应用私有目录」这个更准确的理由（两者都被拒绝，但提示不同）。
        if (segments.size >= 2 && segments[0] == RuntimeStorageDirsLayout.ANDROID_DIRECTORY &&
            segments[1] in RuntimeStorageDirsLayout.PRIVATE_SUBDIRECTORIES
        ) {
            throw RuntimeFailure(StorageDirCodes.PRIVATE_REJECTED, "不能选择应用私有目录")
        }
        if (segments[0] == RuntimeStorageDirsLayout.ANDROID_DIRECTORY) {
            throw RuntimeFailure(StorageDirCodes.ANDROID_REJECTED, "不能选择 Android 目录及其子目录")
        }
        // 最后一道是「能不能真的挂进访客」：与 RuntimeCommand 用同一个判定，
        // 避免这里放行、那里抛错，最后以「PRoot 起不来」的形式暴露给用户。
        if (!RuntimeCommand.isSafeAbsolutePath(canonicalPath)) {
            throw RuntimeFailure(
                StorageDirCodes.UNBINDABLE,
                "目录路径包含运行时不支持的字符（仅支持 ASCII 字母、数字与 . _ -）",
            )
        }
    }

    /** 路径结构：绝对路径、无空分段、无 `.` / `..`、无反斜杠与控制字符、长度与层级在限内。 */
    private fun requireStructuralPath(path: String) {
        val invalid = path.length < 2 ||
            !path.startsWith(PATH_SEPARATOR) ||
            path.contains('\\') ||
            path.any { it.code < 0x20 || it.code == 0x7f }
        if (invalid) throw RuntimeFailure(StorageDirCodes.PATH_INVALID, "目录路径格式无效")
        val segments = path.drop(1).split(PATH_SEPARATOR)
        if (segments.size > RuntimeStorageDirsLimits.MAX_PATH_DEPTH) {
            throw RuntimeFailure(StorageDirCodes.PATH_INVALID, "目录路径层级超过上限")
        }
        if (segments.any {
                it.isEmpty() || it == "." || it == ".." ||
                    it.length > RuntimeStorageDirsLimits.MAX_COMPONENT_CHARS
            }
        ) {
            throw RuntimeFailure(StorageDirCodes.PATH_INVALID, "目录路径分段无效")
        }
    }

    private fun normalizeRelativePath(raw: String): String {
        var value = raw
        // SAF 偶尔给出尾部分隔符（`primary:Documents/Foo/`）与前导 `/`：属于无歧义归一化。
        while (value.startsWith(PATH_SEPARATOR)) value = value.drop(1)
        while (value.endsWith(PATH_SEPARATOR)) value = value.dropLast(1)
        if (value.isEmpty()) {
            throw RuntimeFailure(StorageDirCodes.ROOT_REJECTED, "不能选择共享存储的根目录")
        }
        if (value.contains('\\') || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录标识包含非法字符")
        }
        val segments = value.split(PATH_SEPARATOR)
        if (segments.size > RuntimeStorageDirsLimits.MAX_PATH_DEPTH) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录层级超过上限")
        }
        if (segments.any {
                it.isEmpty() || it == "." || it == ".." ||
                    it.length > RuntimeStorageDirsLimits.MAX_COMPONENT_CHARS
            }
        ) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录标识路径分段无效")
        }
        if (value.length > RuntimeStorageDirsLimits.MAX_RELATIVE_PATH_CHARS) {
            throw RuntimeFailure(StorageDirCodes.DOCUMENT_ID_INVALID, "目录路径超过长度上限")
        }
        return value
    }
}

/**
 * 生产实现：`Environment` + `java.io.File` + `java.nio.file`。
 *
 * `allFilesGranted()` 的版本守卫必须与调用写在**同一个表达式**里（[Build.VERSION_CODES.R] 起才有
 * `isExternalStorageManager()`，而 minSdk 是 26）：拆开写会让 Android 8–10 走到 `NoSuchMethodError`，
 * Android Lint 的 `NewApi` 也会把 release 构建拦下来——单测全绿、`lintRelease` 失败的那次事故就是这个形状。
 */
internal class AndroidStorageDirFileSystem : StorageDirFileSystem {
    @Suppress("DEPRECATION")
    override val publicStorageRoot: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    override fun isFeatureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun isSharedStorageAccessible(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    } catch (_: Throwable) {
        false
    }

    override fun canonicalPath(path: String): String? = try {
        File(path).canonicalPath
    } catch (_: IOException) {
        null
    }

    /** 复用投递区的 NoFollow 目录判定；任何异常都按「不是真实目录」处理（fail-closed）。 */
    override fun isRealDirectoryNoFollow(path: String): Boolean = try {
        MailboxTree.isRealDirectory(File(path).toPath())
    } catch (_: Throwable) {
        false
    }

    override fun isReadableDirectory(path: String): Boolean = try {
        val directory = File(path)
        directory.isDirectory && directory.canRead() && directory.canExecute()
    } catch (_: Throwable) {
        false
    }
}

/**
 * ≤8 目录白名单门面：状态查询、增删、以及追加到 PRoot 启动档的绑定。
 *
 * 三条边界与投递区一致，理由也一致：
 * 1. **无权限不绑定**：没有「所有文件访问」时一个绑定都不追加——绑定一个读不到的宿主路径
 *    会让 PRoot 直接起不来，那比「白名单不可用」严重得多；
 * 2. **可选绑定**：单条目录失效（被删/改名/不可读/挂载点建不出来）只跳过该条并记诊断，
 *    其余条目照常，绝不影响会话启动；
 * 3. **序号稳定**：序号来自持久化顺序，跳过条目会留下空洞，但不重排。
 *
 * 生产构造见 [from]；单测用内存偏好替身 + 假文件系统直接构造（见 `RuntimeStorageDirsTest`）。
 */
internal class RuntimeStorageDirs(
    private val preferences: RuntimeStorageDirPreferences,
    private val fileSystem: StorageDirFileSystem,
    /** 访客 rootfs 根目录（宿主侧）；挂载点在它下面逐级 NoFollow 创建。 */
    private val guestRoot: () -> File,
    /** 诊断出口：只记受控字段（reason/result/count/code），绝不记路径。 */
    private val record: (DiagnosticLevel, Map<String, String>) -> Unit = { _, _ -> },
) {
    /** 上一次已上报的读取丢弃数：避免界面每次轮询状态都刷一条同样的诊断。 */
    private var reportedDrops = -1

    /** 上一次已上报的「条目不可用」签名：同一批失效条目只记一次。 */
    private var reportedStatusSkips: String? = null

    /** 上一次已上报的「绑定期跳过」签名：与条目状态分开，避免两者互相覆盖。 */
    private var reportedBindSkips: String? = null

    fun state(): StorageDirsState {
        val stored = read()
        val statuses = statuses(stored.entries)
        reportStatusSkips(statuses)
        return StorageDirsState(
            supported = fileSystem.isFeatureSupported(),
            granted = fileSystem.isFeatureSupported() && fileSystem.isSharedStorageAccessible(),
            maxDirectories = RuntimeStorageDirsLimits.MAX_DIRECTORIES,
            entries = statuses,
        )
    }

    /**
     * 加入一个 SAF 选中的目录。
     *
     * 校验全部通过才落盘：映射失败、卷不受支持、符号链接逃逸、规则拒绝、重复、超限
     * 都以受控错误码抛出，**不写入任何东西**（不会留下「选了但没生效」的条目）。
     */
    fun add(documentId: String): StorageDirsState {
        requireReady()
        val canonical = RuntimeStorageDirsPolicy.resolveSelectedDirectory(documentId, fileSystem)
        val stored = read().entries
        if (stored.any { it.path == canonical }) {
            throw RuntimeFailure(StorageDirCodes.DUPLICATE, "该目录已在白名单中")
        }
        if (stored.size >= RuntimeStorageDirsLimits.MAX_DIRECTORIES) {
            throw RuntimeFailure(
                StorageDirCodes.LIMIT_REACHED,
                "最多只能添加 ${RuntimeStorageDirsLimits.MAX_DIRECTORIES} 个目录，请先移除一个",
            )
        }
        val updated = stored + StorageDirEntry(
            path = canonical,
            displayName = RuntimeStorageDirsPolicy.displayNameOf(canonical),
        )
        write(updated)
        record(
            DiagnosticLevel.INFO,
            mapOf("reason" to "add", "result" to "ok", "count" to updated.size.toString()),
        )
        return state()
    }

    /**
     * 按路径移除。路径不在白名单里时如实报错，不做「看起来成功」的空操作。
     *
     * **刻意不要求档位就绪**（与 [add] 不同）：移除是纯账本操作，权限被撤销、运行时坏了、
     * 目录早就不存在时都必须能清理条目。否则用户会卡在一份删不掉的列表上，而那份列表
     * 正是他下次启动时唯一能自救的地方。
     */
    fun remove(path: String): StorageDirsState {
        val stored = read().entries
        val updated = stored.filterNot { it.path == path }
        if (updated.size == stored.size) {
            throw RuntimeFailure(StorageDirCodes.NOT_FOUND, "该目录不在白名单中")
        }
        write(updated)
        record(
            DiagnosticLevel.INFO,
            mapOf("reason" to "remove", "result" to "ok", "count" to updated.size.toString()),
        )
        return state()
    }

    /**
     * 追加到 PRoot 启动档的绑定：逐条检查、逐条跳过。
     *
     * **不可用就返回空列表**（未授权 / Android 11 以下 / 一条都不可用）：绑定不存在的宿主路径
     * 会让 PRoot 直接起不来。
     */
    fun bindMounts(): List<ProotBindMount> {
        if (!fileSystem.isFeatureSupported() || !fileSystem.isSharedStorageAccessible()) return emptyList()
        val entries = read().entries
        if (entries.isEmpty()) return emptyList()
        val root = guestRoot()
        if (!MailboxTree.isRealDirectory(root.toPath())) return emptyList()

        val mounts = mutableListOf<ProotBindMount>()
        val skipped = mutableListOf<String>()
        val statuses = statuses(entries)
        // 失效条目在建绑定这一步就记一次诊断：不能指望界面一定先调过状态查询。
        reportStatusSkips(statuses)
        statuses.forEach { status ->
            // 未授权/系统不支持时整份列表都不绑定（不是「这一条失效」），因此不计入跳过。
            if (!status.available) return@forEach
            // 状态查询与绑定之间目录可能刚好变得不可读：绑定期再探一次，失败只跳过这一条。
            if (!fileSystem.isReadableDirectory(status.entry.path)) {
                skipped += "${status.index}:${StorageDirCodes.UNREADABLE}"
                return@forEach
            }
            if (!ensureGuestMountPoint(status.guestPath)) {
                skipped += "${status.index}:${StorageDirCodes.PATH_INVALID}"
                return@forEach
            }
            mounts += ProotBindMount(status.entry.path, status.guestPath)
        }
        reportBindSkips(skipped)
        return mounts
    }

    /**
     * 启动档缓存键里的白名单摘要。
     *
     * 只取计数与路径摘要：`profile_key` 存在应用私有偏好里，**不把用户目录路径写进去**，
     * 同时保证「内容一变，缓存即失效」（与投递区把可用性放进 `profileKey` 是同一机制）。
     */
    fun cacheToken(): String {
        val entries = read().entries
        if (entries.isEmpty()) return "dirs:0"
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.update(entry.path.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(16)
        return "dirs:${entries.size}:$hex"
    }

    /**
     * 档位前置判定：系统是否支持、是否已授予「所有文件访问」。
     *
     * 提到 `internal` 是为了让桥方法在**弹选择器之前**先判一次：让用户白选一次目录、
     * 回来才被告知「需要授权」是纯粹的浪费，而判定规则仍然只有这一份。
     */
    internal fun requireReady() {
        if (!fileSystem.isFeatureSupported()) {
            throw RuntimeFailure(
                StorageDirCodes.UNSUPPORTED,
                "当前系统不支持「所有文件访问」，无法把目录绑定进访客（需要 Android 11 及以上）",
            )
        }
        if (!fileSystem.isSharedStorageAccessible()) {
            throw RuntimeFailure(
                StorageDirCodes.NEEDS_PERMISSION,
                "请先在系统设置中授予「所有文件访问」，再选择要绑定进访客的目录",
            )
        }
    }

    private fun statuses(entries: List<StorageDirEntry>): List<StorageDirStatus> =
        entries.mapIndexed { position, entry -> entryStatus(position + 1, entry) }

    private fun entryStatus(index: Int, entry: StorageDirEntry): StorageDirStatus {
        if (!fileSystem.isFeatureSupported()) {
            return StorageDirStatus(index, entry, StorageDirAvailability.UNSUPPORTED, StorageDirCodes.UNSUPPORTED)
        }
        if (!fileSystem.isSharedStorageAccessible()) {
            return StorageDirStatus(
                index,
                entry,
                StorageDirAvailability.NEEDS_PERMISSION,
                StorageDirCodes.NEEDS_PERMISSION,
            )
        }
        return try {
            RuntimeStorageDirsPolicy.requireCanonicalPath(entry.path)
            if (!fileSystem.isRealDirectoryNoFollow(entry.path)) {
                throw RuntimeFailure(StorageDirCodes.NOT_A_DIRECTORY, "目录已不存在或不再是真实目录")
            }
            if (!fileSystem.isReadableDirectory(entry.path)) {
                throw RuntimeFailure(StorageDirCodes.UNREADABLE, "目录当前不可读")
            }
            StorageDirStatus(index, entry, StorageDirAvailability.AVAILABLE, null)
        } catch (failure: RuntimeFailure) {
            StorageDirStatus(index, entry, StorageDirAvailability.UNAVAILABLE, failure.code)
        }
    }

    private fun ensureGuestMountPoint(guestPath: String): Boolean {
        val root = guestRoot()
        if (!MailboxTree.isRealDirectory(root.toPath())) return false
        val directory = File(root, guestPath.removePrefix("/"))
        return try {
            MailboxTree.createDirectoriesNoFollow(root.toPath(), directory.toPath())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 读取偏好，并把「有东西被丢弃」这件事记一次诊断。
     *
     * **只跳过坏条目，不清空整份白名单**：目录被删/改名不该让用户其余的选择一起消失，
     * 更不该让启动失败（绑定不存在的宿主路径会让 PRoot 起不来）。
     */
    private fun read(): StoredStorageDirs {
        val stored = preferences.read()
        if (stored.corrupt) {
            record(
                DiagnosticLevel.WARN,
                mapOf(
                    "reason" to "read",
                    "result" to "failed",
                    "count" to "1",
                    "code" to StorageDirCodes.PREFERENCES_INVALID,
                ),
            )
        } else if (stored.dropped > 0 && stored.dropped != reportedDrops) {
            reportedDrops = stored.dropped
            record(
                DiagnosticLevel.WARN,
                mapOf("reason" to "read", "result" to "skipped", "count" to stored.dropped.toString()),
            )
        }
        return stored
    }

    private fun write(entries: List<StorageDirEntry>) {
        try {
            preferences.write(entries)
        } catch (error: RuntimeFailure) {
            throw error
        } catch (error: Throwable) {
            throw RuntimeFailure(StorageDirCodes.SAVE_FAILED, "无法保存目录白名单", error)
        }
    }

    /** 条目级不可用（目录被删/改名/规则不再通过）只记一次：界面轮询状态不该刷爆日志。 */
    private fun reportStatusSkips(statuses: List<StorageDirStatus>) {
        recordSkips(
            skipped = statuses
                .filter { it.availability == StorageDirAvailability.UNAVAILABLE }
                .map { "${it.index}:${it.reasonCode}" },
            previous = reportedStatusSkips,
            update = { reportedStatusSkips = it },
        )
    }

    /** 绑定期跳过：状态是「可用」但读取或挂载点创建失败，属于 PRoot 启动前的最后一道。 */
    private fun reportBindSkips(skipped: List<String>) {
        recordSkips(skipped = skipped, previous = reportedBindSkips, update = { reportedBindSkips = it })
    }

    private fun recordSkips(skipped: List<String>, previous: String?, update: (String?) -> Unit) {
        if (skipped.isEmpty()) {
            update(null)
            return
        }
        val signature = skipped.joinToString(",")
        if (signature == previous) return
        update(signature)
        record(
            DiagnosticLevel.WARN,
            mapOf(
                "reason" to "bind",
                "result" to "skipped",
                "count" to skipped.size.toString(),
                "code" to skipped.first().substringAfter(':'),
            ),
        )
    }

    companion object {
        /**
         * 生产构造。
         *
         * [record] 由调用方注入（启动解析器写运行时诊断日志）：白名单模块自己不持有
         * `DiagnosticLog`，这样单测里可以直接断言「记了什么」，不需要 Android 环境。
         */
        fun from(
            context: Context,
            store: RuntimeStore,
            record: (DiagnosticLevel, Map<String, String>) -> Unit,
        ): RuntimeStorageDirs = RuntimeStorageDirs(
            preferences = RuntimeStorageDirPreferences.from(context),
            fileSystem = AndroidStorageDirFileSystem(),
            guestRoot = { store.currentRoot },
            record = record,
        )
    }
}
