package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject

/**
 * 删除一条**已归档**会话。
 *
 * 删的是三样东西（工作区整体不留残留）：
 * 1. 会话日志目录 `$DSH_HOME/sessions/<project>/<session-id>/`；
 * 2. 投影缓存 `$DSH_HOME/storages/session_projcache/sessions/<session-id>.json`；
 * 3. workspace 注册表里对它的记账（`archivedSessionIds` / `pinnedSessionIds` / 各 `workspace.sessionIds`）。
 *
 * 为什么只允许已归档：未归档的会话可能正是用户手上那个（或有在跑的工作），删掉没有任何补救余地；
 * 归档本身就是「这条不再在用」的显式表态，删除因此是它的自然延伸。前端也只在已归档分区给按钮。
 *
 * 运行期一律拒绝（[RUNTIME_RUNNING]）：dsh 正在往会话日志里追加，删目录只会留下半个会话，
 * 而注册表里那条 id 还指向一个正在被写的路径。调用方在生命周期锁内先看相位。
 *
 * 失败语义：磁盘删除失败 → [SESSION_DELETE_FAILED]（不重启、不回滚，如实报）；
 * 注册表更新失败**不**把结果改成失败——文件已经不在了，那条归档 id 只是指向一条不存在的会话，
 * dsh 侧的取消归档对它本来就是空操作。把「已删掉」报成失败会让用户重复删一次。
 */
internal object SessionDeletion {
    /** Harness 正在运行：会话日志可能正被追加，删除不安全。 */
    const val RUNTIME_RUNNING = "RUNTIME_RUNNING"

    /** 目标会话不在归档集合里（未归档，或归档状态读不到）。 */
    const val SESSION_NOT_ARCHIVED = "SESSION_NOT_ARCHIVED"

    /** 磁盘上已经没有这条会话。 */
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"

    /** 其余文件系统失败。 */
    const val SESSION_DELETE_FAILED = "SESSION_DELETE_FAILED"

    /** 会话标识形态非法（与 `SessionCatalogPayload` 同一套判据）。 */
    const val SESSION_ID_INVALID = "SESSION_ID_INVALID"

    /** 运行时根目录下的固定相对位置（`root/` 是 PRoot 根，`/root` 是访客家目录）。 */
    private const val SESSIONS_RELATIVE = "root/.dsh/sessions"
    private const val WORKSPACE_STATE_RELATIVE = "root/.dsh/storages/workspace.json"
    private const val PROJECTION_CACHE_RELATIVE = "root/.dsh/storages/session_projcache/sessions"

    private const val MAX_ID_CHARS = 200
    private const val MAX_WORKSPACE_STATE_BYTES = 4L * 1024 * 1024
    private val FORBIDDEN_IN_IDENTIFIER = Regex("[\\u0000-\\u001F\\u007F/\\\\\\s]")
    private val SEGMENT_ESCAPE = Regex("^[0-9A-Fa-f]{4}$")

    /**
     * 执行删除。
     *
     * [runtimeRoot] 是 `RuntimeStore.currentRoot`（宿主侧运行时根），[running] 由调用方在
     * 生命周期锁内按当前相位判定——本对象不自己去问相位，避免出现「锁外读相位」的竞态。
     *
     * 返回 `{deleted:true}` 或 `{deleted:false,reason:<受控码>}`；只有**请求本身不成立**
     * （标识形态非法）才抛 [RuntimeFailure]。
     */
    fun delete(runtimeRoot: File, sessionId: String, running: Boolean): JSONObject {
        if (!isValidIdentifier(sessionId)) throw RuntimeFailure(SESSION_ID_INVALID, "会话标识无效")
        if (running) return refusal(RUNTIME_RUNNING)

        val archived = readArchivedIds(runtimeRoot)
        // 注册表存在但读不懂：无法确认这条会话是否已归档，宁可不删。
        if (archived == null) return refusal(SESSION_DELETE_FAILED)
        if (sessionId !in archived) return refusal(SESSION_NOT_ARCHIVED)

        val sessionsRoot = File(runtimeRoot, SESSIONS_RELATIVE)
        val directories = findSessionDirectories(sessionsRoot, sessionId)
        if (directories.isEmpty()) return refusal(SESSION_NOT_FOUND)

        try {
            for (directory in directories) deleteTree(directory)
            forgetProjection(runtimeRoot, sessionId)
        } catch (error: IOException) {
            throw RuntimeFailure(SESSION_DELETE_FAILED, "删除会话失败", error)
        }
        // 注册表更新尽力而为：文件已经删掉，残留 id 对 dsh 是空操作（见类注释）。
        runCatching { forget(runtimeRoot, sessionId) }
        return JSONObject().put("deleted", true)
    }

    private fun refusal(reason: String): JSONObject = JSONObject().put("deleted", false).put("reason", reason)

    private fun isValidIdentifier(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_ID_CHARS) return false
        if (value.startsWith(".")) return false
        return !FORBIDDEN_IN_IDENTIFIER.containsMatchIn(value)
    }

    /**
     * 找出这条会话的日志目录：`sessions/` 下每个项目目录里的同名子目录。
     *
     * 目录名是**编码过**的（非 `[A-Za-z0-9._-]` 的码点写成 `~XXXX`，见
     * `dsh-session-persistence-jsonl`），所以先解码再比对；解码不出来的目录名一律跳过。
     * 只看真实目录（NoFollow），符号链接不会被当成会话。
     */
    private fun findSessionDirectories(sessionsRoot: File, sessionId: String): List<File> {
        if (!Files.isDirectory(sessionsRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val projects = sessionsRoot.listFiles() ?: return emptyList()
        val matches = ArrayList<File>()
        for (project in projects) {
            if (Files.isSymbolicLink(project.toPath())) continue
            if (!Files.isDirectory(project.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            val entries = project.listFiles() ?: continue
            for (entry in entries) {
                val path = entry.toPath()
                if (Files.isSymbolicLink(path)) continue
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                if (decodeSegment(entry.name) != sessionId) continue
                matches.add(entry)
            }
        }
        return matches
    }

    /** 目录名 → 会话标识；规则与访客脚本 `decodeSegment` 逐条一致，解不出来返回 `null`。 */
    private fun decodeSegment(name: String): String? {
        if (name.isEmpty() || name.length > MAX_ID_CHARS * 5) return null
        val builder = StringBuilder()
        var index = 0
        while (index < name.length) {
            val character = name[index]
            if (character == '~') {
                val hex = name.substring(index + 1, minOf(index + 5, name.length))
                if (!SEGMENT_ESCAPE.matches(hex)) return null
                val codePoint = hex.toInt(16)
                if (codePoint == 0 || codePoint > 0x10FFFF) return null
                builder.appendCodePoint(codePoint)
                index += 5
            } else {
                builder.append(character)
                index += 1
            }
        }
        val decoded = builder.toString()
        if (decoded.isEmpty() || decoded.length > MAX_ID_CHARS) return null
        return decoded
    }

    /** 递归删除一棵树：`walkFileTree` 默认不跟随符号链接，链接本身作为条目被删掉。 */
    private fun deleteTree(directory: File) {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun forgetProjection(runtimeRoot: File, sessionId: String) {
        val entry = File(File(runtimeRoot, PROJECTION_CACHE_RELATIVE), "$sessionId.json").toPath()
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(entry)
    }

    /**
     * 读归档集合。
     *
     * 三态：文件不存在 = 空集合（从没归档过，这不是错误）；读不懂或超限 = `null`
     * （无法确认，调用方据此拒绝删除）；正常 = 集合。
     */
    private fun readArchivedIds(runtimeRoot: File): Set<String>? {
        val state = File(runtimeRoot, WORKSPACE_STATE_RELATIVE)
        if (!state.isFile) return emptySet()
        if (state.length() > MAX_WORKSPACE_STATE_BYTES) return null
        val document = try {
            JSONObject(state.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val global = document.optJSONObject("global") ?: return emptySet()
        val list = global.optJSONArray("archivedSessionIds") ?: return emptySet()
        val ids = HashSet<String>()
        for (index in 0 until list.length()) {
            val value = list.opt(index) as? String ?: continue
            if (isValidIdentifier(value)) ids.add(value)
        }
        return ids
    }

    /**
     * 从注册表里摘掉这条会话的记账，原子替换写回。
     *
     * 只动三个全是 id 列表的键，其余字段原样保留（`unit` 版本、`workspaceIds` 顺序、`initialized` 等）。
     */
    private fun forget(runtimeRoot: File, sessionId: String) {
        val state = File(runtimeRoot, WORKSPACE_STATE_RELATIVE)
        if (!state.isFile || state.length() > MAX_WORKSPACE_STATE_BYTES) return
        val document = JSONObject(state.readText(StandardCharsets.UTF_8))
        val global = document.optJSONObject("global") ?: JSONObject()
        removeId(global, "archivedSessionIds", sessionId)
        removeId(global, "pinnedSessionIds", sessionId)
        document.put("global", global)
        val workspaces = document.optJSONObject("tables")?.optJSONObject("workspaces")
        if (workspaces != null) {
            val keys = workspaces.keys()
            while (keys.hasNext()) {
                workspaces.optJSONObject(keys.next())?.let { record -> removeId(record, "sessionIds", sessionId) }
            }
        }
        writeAtomically(state, document.toString())
    }

    private fun removeId(target: JSONObject, key: String, sessionId: String) {
        val list = target.optJSONArray(key) ?: return
        val kept = ArrayList<String>(list.length())
        for (index in 0 until list.length()) {
            val value = list.opt(index) as? String ?: continue
            if (value != sessionId) kept.add(value)
        }
        target.put(key, org.json.JSONArray(kept))
    }

    /** 同目录临时文件 + 原子改名：与 dsh 自己的 JSON 存储发布协议一致，写一半的文件永远不会被读到。 */
    private fun writeAtomically(target: File, text: String) {
        val directory = target.parentFile ?: throw IOException("workspace 注册表没有父目录")
        val pending = Files.createTempFile(directory.toPath(), ".session-delete-", ".tmp")
        try {
            Files.write(
                pending,
                text.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(pending, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
