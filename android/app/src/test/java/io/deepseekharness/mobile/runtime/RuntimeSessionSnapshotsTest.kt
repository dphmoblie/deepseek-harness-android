package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 运行时会话快照的单元测试。
 *
 * 全部走真实文件系统（[TemporaryFolder]）而不是替身：这一块的正确性就在
 * 「复制到的字节是否一模一样」「淘汰是否真的删掉最旧的那份」「同名文件是否真的没被覆盖」，
 * 用假文件系统测等于什么都没测。快照根注入自临时目录，因此不依赖真机。
 *
 * 有意不覆盖的部分（需要在真机/仪器化测试里做）：应用私有目录的实际权限与可用空间、
 * Harness 正在写会话时拍快照的一致性（本实现只承诺「尽力而为的一帧」）。
 */
class RuntimeSessionSnapshotsTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    /** 一套临时目录 + 可控时钟与随机后缀的快照实例。 */
    private class Harness(
        val sessionRoot: File,
        val snapshotsRoot: File,
        var clockMs: Long = 1_700_000_000_000L,
        maxCount: Int = RuntimeSessionSnapshotLimits.MAX_COUNT,
        maxBytes: Long = RuntimeSessionSnapshotLimits.MAX_TOTAL_BYTES,
    ) {
        private var sequence = 0

        val snapshots = RuntimeSessionSnapshots(
            sessionRoot = sessionRoot,
            snapshotsRoot = snapshotsRoot,
            identity = { RuntimeIdentitySnapshot("0.2.0", "2026.01.01", "rt-abc123") },
            maxCount = maxCount,
            maxBytes = maxBytes,
            now = { clockMs },
            nonce = { "%08x".format(++sequence) },
        )

        fun write(relative: String, content: String) {
            val file = File(sessionRoot, relative)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }

        fun writeBytes(relative: String, content: ByteArray) {
            val file = File(sessionRoot, relative)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
        }

        fun read(relative: String): String? = File(sessionRoot, relative).takeIf { it.isFile }?.readText(Charsets.UTF_8)

        /** 一份快照的目录（元数据与内容子目录都在它下面）。 */
        fun snapshotDirectory(id: String): File = File(snapshotsRoot, id)

        /** 快照里的**会话内容**文件：`<id>/files/<relative>`。 */
        fun snapshotFile(id: String, relative: String): File =
            File(File(snapshotDirectory(id), RuntimeSessionSnapshotLimits.FILES_DIRECTORY), relative)

        /** 快照元数据：`<id>/snapshot.json`，刻意放在内容目录之外。 */
        fun metadataFile(id: String): File =
            File(snapshotDirectory(id), RuntimeSessionSnapshotLimits.METADATA_FILE)

        /** 取出一次调用抛出的受控失败码；没抛就判失败。 */
        fun codeOf(block: () -> Unit): String = try {
            block()
            fail("预期抛出 RuntimeFailure，但调用成功了")
            "NO_FAILURE"
        } catch (failure: RuntimeFailure) {
            failure.code
        }
    }

    /**
     * 每次调用都换一套独立目录：同一个用例里建两个 harness（例如同时看「有会话」与「空会话」两种
     * 载荷）时，TemporaryFolder.newFolder 会因目录重名直接抛 IOException。
     */
    private var harnessSequence = 0

    private fun harness(
        maxCount: Int = RuntimeSessionSnapshotLimits.MAX_COUNT,
        maxBytes: Long = RuntimeSessionSnapshotLimits.MAX_TOTAL_BYTES,
    ): Harness {
        val index = harnessSequence++
        return Harness(
            sessionRoot = temporaryFolder.newFolder("sessions-$index"),
            snapshotsRoot = File(
                temporaryFolder.newFolder("snapshots-$index"),
                RuntimeSessionSnapshotLimits.DIRECTORY_NAME,
            ),
            maxCount = maxCount,
            maxBytes = maxBytes,
        )
    }

    @Test
    fun createCopiesTheSessionTreeByteForByteAndWritesMetadata() {
        val harness = harness()
        harness.write("sessions/aa/one.jsonl", "会话内容一")
        val binary = ByteArray(256) { (it and 0xFF).toByte() }
        harness.writeBytes("two.bin", binary)

        val state = harness.snapshots.create()

        assertEquals(1, state.snapshots.size)
        assertEquals(harness.snapshotsRoot.absolutePath, state.directory)
        assertEquals(RuntimeSessionSnapshotLimits.MAX_COUNT, state.maxCount)
        assertEquals(RuntimeSessionSnapshotLimits.MAX_TOTAL_BYTES, state.maxBytes)

        val meta = state.snapshots.first()
        assertEquals(2, meta.fileCount)
        assertEquals(binary.size.toLong() + "会话内容一".toByteArray(Charsets.UTF_8).size, meta.totalBytes)
        assertEquals(harness.clockMs, meta.createdAtMs)
        // 身份读得到就写进去；读不到写 null（不猜）——这里是读得到的路径。
        assertEquals("0.2.0", meta.dshVersion)
        assertEquals("2026.01.01", meta.runtimeVersion)
        assertEquals("rt-abc123", meta.runtimeId)
        assertEquals(meta.totalBytes, state.totalBytes)

        // 字节级一致：zstd 会话文件被改写一个字节就废了。
        assertEquals("会话内容一", harness.snapshotFile(meta.id, "sessions/aa/one.jsonl").readText(Charsets.UTF_8))
        assertTrue(binary.contentEquals(harness.snapshotFile(meta.id, "two.bin").readBytes()))

        // 元数据落盘且能原样读回（快照元数据是「这份快照是否完整」的唯一凭据）。
        val metadataText = harness.metadataFile(meta.id).readText(Charsets.UTF_8)
        val parsed = RuntimeSessionSnapshotPolicy.parseMetadata(meta.id, metadataText)
        assertEquals(meta, parsed)
        assertEquals(1, JSONObject(metadataText).getInt("schemaVersion"))
    }

    @Test
    fun createRejectsMissingOrEmptySessionDirectory() {
        val harness = harness()
        // 会话目录根本不存在：首次安装就是这种形状，不能产出空快照。
        assertEquals(RuntimeSessionSnapshotCodes.EMPTY, harness.codeOf { harness.snapshots.create() })
        assertTrue(harness.snapshots.state().snapshots.isEmpty())

        // 目录存在但里面没有文件（例如只有空子目录）：同样什么都不做。
        harness.write("sessions/aa/placeholder/keep", "")
        File(harness.sessionRoot, "sessions/aa/placeholder/keep").delete()
        assertEquals(RuntimeSessionSnapshotCodes.EMPTY, harness.codeOf { harness.snapshots.create() })
        assertTrue(harness.snapshots.state().snapshots.isEmpty())
    }

    @Test
    fun createEvictsTheOldestSnapshotsBeyondTheCountLimit() {
        val harness = harness(maxCount = 2)
        harness.write("sessions/a.jsonl", "aaaa")
        val first = harness.snapshots.create().snapshots.first().id
        harness.clockMs += 1_000
        val second = harness.snapshots.create().snapshots.first().id
        harness.clockMs += 1_000
        val third = harness.snapshots.create().snapshots.first().id

        val state = harness.snapshots.state()
        assertEquals(2, state.snapshots.size)
        // 从新到旧：刚创建的排最前。
        assertEquals(listOf(third, second), state.snapshots.map { it.id })
        assertFalse(File(harness.snapshotsRoot, first).exists())
        assertTrue(File(harness.snapshotsRoot, second).exists())
    }

    @Test
    fun createEvictsTheOldestSnapshotsBeyondTheByteLimit() {
        // 每次快照恰好 10 字节，上限 25 字节：第三份必须先把最旧的挤掉才放得下。
        val harness = harness(maxBytes = 25)
        harness.write("sessions/a.jsonl", "0123456789")
        harness.clockMs += 1_000
        val first = harness.snapshots.create().snapshots.first().id
        harness.clockMs += 1_000
        harness.snapshots.create()
        harness.clockMs += 1_000
        harness.snapshots.create()

        val state = harness.snapshots.state()
        assertEquals(2, state.snapshots.size)
        assertEquals(20L, state.totalBytes)
        assertFalse(File(harness.snapshotsRoot, first).exists())
    }

    @Test
    fun createFailsWhenASingleSnapshotCannotFitAndLeavesNothingBehind() {
        val harness = harness(maxBytes = 5)
        harness.write("sessions/a.jsonl", "0123456789")

        assertEquals(RuntimeSessionSnapshotCodes.TOO_LARGE, harness.codeOf { harness.snapshots.create() })
        assertTrue(harness.snapshots.state().snapshots.isEmpty())
        // 半成品必须清干净：界面上不能出现一份缺文件的「备份」。
        assertTrue(harness.snapshotsRoot.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun restoreMergesWithoutOverwritingExistingFiles() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "旧-a")
        harness.write("sessions/b.jsonl", "旧-b")
        val id = harness.snapshots.create().snapshots.first().id

        // 更新之后：a 被新 dsh 重写过，b 消失了（用户要恢复的正是这种）。
        harness.write("sessions/a.jsonl", "新-a")
        File(harness.sessionRoot, "sessions/b.jsonl").delete()

        val result = harness.snapshots.restore(id)

        assertEquals(1, result.restoredFileCount)
        assertEquals(1, result.skippedFileCount)
        assertEquals("新-a", harness.read("sessions/a.jsonl"))
        assertEquals("旧-b", harness.read("sessions/b.jsonl"))
        assertEquals(1, result.state.snapshots.size)
    }

    @Test
    fun restoreAndDeleteRejectMalformedAndUnknownIds() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "x")
        val id = harness.snapshots.create().snapshots.first().id

        // 形态不合法：不可能表达路径穿越，因此与「不存在」区分开。
        assertEquals(RuntimeSessionSnapshotCodes.ID_INVALID, harness.codeOf { harness.snapshots.restore("../escape") })
        assertEquals(RuntimeSessionSnapshotCodes.ID_INVALID, harness.codeOf { harness.snapshots.restore("") })
        assertEquals(RuntimeSessionSnapshotCodes.ID_INVALID, harness.codeOf { harness.snapshots.delete("a/b") })
        assertEquals(RuntimeSessionSnapshotCodes.ID_INVALID, harness.codeOf {
            RuntimeSessionSnapshotPolicy.requireId(null)
        })
        // 形态合法但不存在。
        assertEquals(RuntimeSessionSnapshotCodes.NOT_FOUND, harness.codeOf { harness.snapshots.restore("snap-1-deadbeef") })
        assertEquals(RuntimeSessionSnapshotCodes.NOT_FOUND, harness.codeOf { harness.snapshots.delete("snap-1-deadbeef") })

        // 失败不能有任何副作用。
        assertTrue(File(harness.snapshotsRoot, id).exists())
    }

    @Test
    fun deleteRemovesOnlyTheRequestedSnapshot() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "aaaa")
        val older = harness.snapshots.create().snapshots.first().id
        harness.clockMs += 1_000
        val newer = harness.snapshots.create().snapshots.first().id

        val state = harness.snapshots.delete(older)

        assertEquals(listOf(newer), state.snapshots.map { it.id })
        assertFalse(File(harness.snapshotsRoot, older).exists())
        assertTrue(File(harness.snapshotsRoot, newer).exists())
        // 会话目录与快照无关，删快照不许碰它。
        assertEquals("aaaa", harness.read("sessions/a.jsonl"))
    }

    @Test
    fun stateIgnoresUnusableEntriesAndCreatePrunesThem() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "aaaa")
        // 残留一：名字本身就不是合法标识（人工翻动或写入中断的产物）。
        val badName = File(harness.snapshotsRoot, "快照 残留").apply {
            mkdirs()
            File(this, "junk").writeText("x")
        }
        // 残留二：名字看着像标识，但它是个文件而不是目录。
        val strayFile = File(harness.snapshotsRoot, "snap-1-ffffffff").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        // 残留三：标识合法、是目录，但没有元数据，且已经超过一小时的保留窗口。
        // 注意：刚建出来还没到窗口的同类目录会被**保留**（可能是另一个进程正在写的快照），
        // 所以这里必须真的把它改成旧时间，否则测的是另一条分支。
        val stale = File(harness.snapshotsRoot, "snap-1-deadbeef").apply { mkdirs() }
        assertTrue(stale.setLastModified(harness.clockMs - RuntimeSessionSnapshotLimits.STALE_PARTIAL_MS - 60_000))

        // 三种残留都不算有效备份：总览里一份都不显示。
        assertTrue(harness.snapshots.state().snapshots.isEmpty())

        harness.snapshots.create()

        assertFalse(badName.exists())
        assertFalse(strayFile.exists())
        assertFalse(stale.exists())
        assertEquals(1, harness.snapshots.state().snapshots.size)
    }

    @Test
    fun snapshotContentNeverCollidesWithMetadataAndRestoreVerifiesIntegrity() {
        val harness = harness()
        // 会话目录里真的可能有叫 snapshot.json 的文件：它必须原样进内容目录，不能被元数据挤掉。
        harness.write("snapshot.json", "这是会话数据，不是元数据")
        val id = harness.snapshots.create().snapshots.first().id

        assertEquals(
            "这是会话数据，不是元数据",
            harness.snapshotFile(id, "snapshot.json").readText(Charsets.UTF_8),
        )
        assertTrue(harness.metadataFile(id).isFile)

        // 内容被改动（少了一个文件）时拒绝恢复：半份备份不许悄悄回填。
        File(harness.sessionRoot, "snapshot.json").delete()
        harness.snapshotFile(id, "snapshot.json").delete()
        assertEquals(
            RuntimeSessionSnapshotCodes.FAILED,
            harness.codeOf { harness.snapshots.restore(id) },
        )
        assertFalse(File(harness.sessionRoot, "snapshot.json").exists())
    }

    @Test
    fun autoSnapshotReportsEmptyAsSkippedAndSucceedsWhenThereIsData() {
        val harness = harness()
        val skipped = harness.snapshots.createBeforeUpdate()
        assertEquals(RuntimeAutoSnapshotStatus.SKIPPED, skipped.status)
        assertEquals(RuntimeSessionSnapshotCodes.EMPTY, skipped.code)
        // skipped 不是失败：它表示「本来就没有可备份的会话数据」。
        assertNull(skipped.snapshotId)

        harness.write("sessions/a.jsonl", "aaaa")
        val created = harness.snapshots.createBeforeUpdate()
        assertEquals(RuntimeAutoSnapshotStatus.CREATED, created.status)
        assertEquals(harness.snapshots.state().snapshots.first().id, created.snapshotId)
    }

    @Test
    fun autoSnapshotReportsFailureInsteadOfThrowing() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "aaaa")
        // 快照根被一个同名文件占住：创建必然失败，但自动快照**不能抛**——它不许挡住安装。
        harness.snapshotsRoot.parentFile?.mkdirs()
        harness.snapshotsRoot.writeText("not a directory", Charsets.UTF_8)

        val outcome = harness.snapshots.createBeforeUpdate()

        assertEquals(RuntimeAutoSnapshotStatus.FAILED, outcome.status)
        assertEquals(RuntimeSessionSnapshotCodes.FAILED, outcome.code)
    }

    /**
     * 桥层载荷的字段名由平台层契约冻结（`maxSnapshots`/`maxBytes`/`totalBytes`/`snapshots`、
     * `createdAt` 为 ISO8601、`bytes`、自动快照的 `snapshotId` 等）。这里逐字钉住：
     * 平台层照契约写完校验、原生侧却少一个字段，是这类接口最典型的返工。
     *
     * 同时把**真实跑出来的**载荷写到 `build/reports/runtime-snapshot-payload-samples.json`，
     * 报告与评审引用它，而不是设计稿。
     */
    @Test
    fun bridgePayloadsMatchTheFrozenPlatformContract() {
        val harness = harness()
        harness.write("sessions/a.jsonl", "aaaa")

        // 安装/更新路径：created 只带 status + snapshotId（没有 code/message）。
        val createdJson = harness.snapshots.createBeforeUpdate().toJs()
        assertEquals(setOf("status", "snapshotId"), createdJson.keys().asSequence().toSet())
        assertEquals(RuntimeAutoSnapshotStatus.CREATED, createdJson.getString("status"))

        // 再拍一份：用来验证「新的在前」不是只有一份时的巧合。
        harness.clockMs += 1_000
        val newer = harness.snapshots.createBeforeUpdate().toJs()

        val stateJson = harness.snapshots.state().toJs()
        assertEquals(
            setOf("maxSnapshots", "maxBytes", "totalBytes", "snapshots"),
            stateJson.keys().asSequence().toSet(),
        )
        val snapshots = stateJson.getJSONArray("snapshots")
        val idsNewestFirst = (0 until snapshots.length()).map { snapshots.getJSONObject(it).getString("id") }
        assertEquals(listOf(newer.getString("snapshotId"), createdJson.getString("snapshotId")), idsNewestFirst)

        val first = snapshots.getJSONObject(0)
        assertEquals(
            setOf("id", "createdAt", "bytes", "fileCount", "dshVersion", "runtimeVersion"),
            first.keys().asSequence().toSet(),
        )
        // createdAt 必须能往返解析（前端会 new Date() 校验并原样展示）。
        assertEquals(harness.clockMs, Instant.parse(first.getString("createdAt")).toEpochMilli())

        // 恢复结果多包一层 state；删除直接返回 state。
        val restoredJson = harness.snapshots.restore(first.getString("id")).toJs()
        assertEquals(
            setOf("restoredFileCount", "skippedFileCount", "state"),
            restoredJson.keys().asSequence().toSet(),
        )
        val afterDelete = harness.snapshots.delete(createdJson.getString("snapshotId")).toJs()
        assertEquals(1, afterDelete.getJSONArray("snapshots").length())

        // skipped 也要带 code + message：界面要能说明「为什么这次没有备份」。
        val emptyHarness = harness()
        val skippedJson = emptyHarness.snapshots.createBeforeUpdate().toJs()
        assertEquals(setOf("status", "code", "message"), skippedJson.keys().asSequence().toSet())
        assertEquals(RuntimeAutoSnapshotStatus.SKIPPED, skippedJson.getString("status"))

        writePayloadSamples(
            linkedMapOf(
                "state（getRuntimeSessionSnapshotState / createRuntimeSessionSnapshot / deleteRuntimeSessionSnapshot）" to stateJson,
                "restoreRuntimeSessionSnapshot" to restoredJson,
                "install.autoSnapshot（created）" to createdJson,
                "install.autoSnapshot（skipped，首次安装）" to skippedJson,
            ),
        )
    }

    /** 把真实载荷落到 `build/reports/` 下（构建产物，不入库），供报告直接引用。 */
    private fun writePayloadSamples(samples: Map<String, JSONObject>) {
        val file = File("build/reports/runtime-snapshot-payload-samples.json")
        file.parentFile?.mkdirs()
        val json = JSONObject()
        samples.forEach { (title, payload) -> json.put(title, payload) }
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    @Test
    fun metadataPolicyRejectsMismatchedIdsAndGarbage() {
        val meta = RuntimeSessionSnapshotMeta(
            id = "snap-1700000000000-ab12cd34",
            createdAtMs = 1_700_000_000_000L,
            // 读不到版本时是 null：落盘写真实 null，读回是 null，而不是空串。
            dshVersion = null,
            runtimeVersion = "2026.01.01",
            runtimeId = null,
            fileCount = 3,
            totalBytes = 42L,
        )
        val text = RuntimeSessionSnapshotPolicy.metadataJson(meta)
        assertEquals(meta, RuntimeSessionSnapshotPolicy.parseMetadata(meta.id, text))
        assertNull(RuntimeSessionSnapshotPolicy.parseMetadata("snap-1-other", text))
        assertNull(RuntimeSessionSnapshotPolicy.parseMetadata(meta.id, "not json"))
        assertNull(RuntimeSessionSnapshotPolicy.parseMetadata(meta.id, ""))
        assertNull(RuntimeSessionSnapshotPolicy.parseMetadata(meta.id, """{"id":"snap-1-x"}"""))
        // 载荷里读不到的字段直接省略 key，而不是写 null 或空串。
        assertFalse(meta.toJs().has("dshVersion"))
        assertTrue(meta.toJs().has("runtimeVersion"))
    }

    @Test
    fun evictionPlanKeepsTheNewestWithinBothLimits() {
        fun meta(id: String, age: Long, bytes: Long) = RuntimeSessionSnapshotMeta(
            id = id,
            createdAtMs = 1_000L + age,
            dshVersion = null,
            runtimeVersion = null,
            runtimeId = null,
            fileCount = 1,
            totalBytes = bytes,
        )

        // 份数上限：4 份里只留 2 份，淘汰最旧的两份。
        val byCount = RuntimeSessionSnapshotPolicy.evictionPlan(
            existing = listOf(meta("snap-1-aaaa", 1, 10), meta("snap-2-bbbb", 2, 10), meta("snap-3-cccc", 3, 10)),
            incomingBytes = 10,
            maxCount = 2,
            maxBytes = 1_000,
        )
        assertEquals(listOf("snap-1-aaaa", "snap-2-bbbb"), byCount)

        // 字节上限：份数还有余量，但再加一份就超了。
        val byBytes = RuntimeSessionSnapshotPolicy.evictionPlan(
            existing = listOf(meta("snap-1-aaaa", 1, 10), meta("snap-2-bbbb", 2, 10)),
            incomingBytes = 10,
            maxCount = 5,
            maxBytes = 25,
        )
        assertEquals(listOf("snap-1-aaaa"), byBytes)

        // 一份都放不下时把现有全淘汰（调用方随后会因为放不下而报 TOO_LARGE）。
        val all = RuntimeSessionSnapshotPolicy.evictionPlan(
            existing = listOf(meta("snap-1-aaaa", 1, 10)),
            incomingBytes = 99,
            maxCount = 5,
            maxBytes = 25,
        )
        assertEquals(listOf("snap-1-aaaa"), all)

        // 上限都够：一份都不淘汰。
        assertTrue(
            RuntimeSessionSnapshotPolicy.evictionPlan(
                existing = listOf(meta("snap-1-aaaa", 1, 10)),
                incomingBytes = 10,
                maxCount = 5,
                maxBytes = 1_000,
            ).isEmpty(),
        )
    }

    @Test
    fun snapshotIdsAreSafeDirectoryNames() {
        val id = RuntimeSessionSnapshotPolicy.snapshotId(1_700_000_000_000L, "AB12cd34")
        assertEquals("snap-1700000000000-ab12cd34", id)
        assertTrue(RuntimeSessionSnapshotLimits.validId(id))
        // 非十六进制字符被丢掉、空后缀有兜底：标识永远是可当目录名的形态。
        assertEquals("snap-1-00000000", RuntimeSessionSnapshotPolicy.snapshotId(1L, "!!!"))
        assertFalse(RuntimeSessionSnapshotLimits.validId("../escape"))
        assertFalse(RuntimeSessionSnapshotLimits.validId("a".repeat(RuntimeSessionSnapshotLimits.MAX_ID_CHARS + 1)))
        assertFalse(RuntimeSessionSnapshotLimits.validId(null))
    }

    @Test
    fun sessionRelativePathMatchesThePreservePolicyExpectation() {
        // 两个常量必须指向同一个目录：不一致会让「自动快照」悄悄备份一个不存在的目录，
        // 而它永远不会报错 —— 只会在用户需要恢复时才发现什么都没有。
        assertEquals(
            RuntimePreservePolicy.guestRelativePath("sessions"),
            RuntimeSessionSnapshotLimits.SESSIONS_RELATIVE_PATH,
        )
    }

    @Test
    fun symbolicLinksAreSkippedInsteadOfCopied() {
        val harness = harness()
        harness.write("sessions/real.jsonl", "真实内容")
        val link = File(harness.sessionRoot, "sessions/link.jsonl").toPath()
        try {
            Files.createSymbolicLink(link, Path.of("real.jsonl"))
        } catch (error: Throwable) {
            assumeNoException("当前平台/权限不支持创建符号链接，跳过", error)
        }

        val state = harness.snapshots.create()

        val meta = state.snapshots.first()
        // 只数常规文件：链接既不算条目，也不会被复制成一份「内容一样的普通文件」。
        assertEquals(1, meta.fileCount)
        assertTrue(harness.snapshotFile(meta.id, "sessions/real.jsonl").isFile)
        assertFalse(harness.snapshotFile(meta.id, "sessions/link.jsonl").exists())

        // 恢复同样只回填真实文件。
        File(harness.sessionRoot, "sessions/real.jsonl").delete()
        val result = harness.snapshots.restore(meta.id)
        assertEquals(1, result.restoredFileCount)
        assertEquals("真实内容", harness.read("sessions/real.jsonl"))
    }
}
