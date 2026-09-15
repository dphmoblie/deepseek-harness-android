package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 投递区归档通道的**夹具自测**：全部在临时目录里跑真实文件系统，跑完由 [TemporaryFolder] 删除。
 *
 * 这里不依赖 `/sdcard`（真机行为无法在本机验证），但**跑的是生产代码**：
 * `MailboxWorkspaceScanner` / `MailboxArchiveWriter`（导出侧）与
 * `MailboxArchiveReader` / `MailboxArchiveExtractor` / `MailboxManifestVerifier`（导入侧）。
 * 恶意 tar 由 [RawTarWriter] 直接写 tar 头构造，不经过 commons-compress 的规范化，
 * 因此能真实构造出 `../` 条目、绝对路径条目、绝对符号链接、FIFO 与重复条目。
 *
 * 两类断言只在类 POSIX 主机成立，用 `assume*` 显式跳过并在报告里列明：
 *  - 执行位落盘（Windows 的默认文件系统没有 POSIX 权限视图）；
 *  - 符号链接的落盘（Windows 需要管理员/开发者模式才能建链接）。
 */
class RuntimeMailboxArchiveTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    // ---------------------------------------------------------------- 正向：tar 往返

    @Test
    fun `export then import keeps the tree structure and every file content`() {
        val workspace = temporaryFolder.newFolder("workspace").toPath()
        Files.createDirectories(workspace.resolve("src/deep"))
        writeText(workspace.resolve("README.md"), "hello mailbox\n")
        writeText(workspace.resolve("src/deep/tool.sh"), "#!/bin/sh\necho hi\n")
        writeText(workspace.resolve("src/deep/data.bin"), "\u0000\u0001\u0002binary")

        val tar = File(temporaryFolder.newFolder("outbox"), RuntimeMailboxLayout.EXPORT_TAR_NAME)
        val plan = MailboxWorkspaceScanner().scan(workspace, null)
        MailboxArchiveWriter().write(workspace, plan, tar)

        assertEquals(
            listOf("README.md", "src", "src/deep", "src/deep/data.bin", "src/deep/tool.sh"),
            plan.entries.map { it.name },
        )
        assertEquals(5, plan.entries.size)
        assertEquals(2, plan.directoryCount)
        assertEquals(3, plan.fileCount)

        val destination = temporaryFolder.newFolder("import").toPath()
        val importPlan = MailboxArchiveReader().plan(tar, destination)
        MailboxArchiveExtractor().extract(tar, importPlan, destination)

        assertEquals(treeOf(workspace), treeOf(destination))
        assertEquals(importPlan.entries.map { it.name }, plan.entries.map { it.name })
        assertEquals(workspace.resolve("README.md").toFile().length(), importPlan.entries.first { it.name == "README.md" }.bytes)
    }

    @Test
    fun `export of a subdirectory prefixes every entry with the subdirectory name`() {
        val workspace = temporaryFolder.newFolder("workspace-sub").toPath()
        Files.createDirectories(workspace.resolve("proj/src"))
        writeText(workspace.resolve("proj/src/main.txt"), "main\n")
        writeText(workspace.resolve("outside.txt"), "outside\n")

        val tar = File(temporaryFolder.newFolder("outbox-sub"), "proj.tar")
        val plan = MailboxWorkspaceScanner().scan(workspace, "proj")
        assertEquals(listOf("proj", "proj/src", "proj/src/main.txt"), plan.entries.map { it.name })
        MailboxArchiveWriter().write(workspace.resolve("proj"), plan, tar)

        val destination = temporaryFolder.newFolder("import-sub").toPath()
        MailboxArchiveExtractor().extract(tar, MailboxArchiveReader().plan(tar, destination), destination)
        assertEquals(
            listOf("dir proj", "dir proj/src", "file proj/src/main.txt ${sha256Of("main\n")}"),
            treeOf(destination),
        )
    }

    @Test
    fun `imported archive carries permission bits into the destination on posix hosts`() {
        val tar = newTar("modes.tar")
        RawTarWriter(tar)
            .entry("script.sh", mode = 0x1ED, content = "#!/bin/sh\n".toByteArray())
            .entry("data.txt", mode = 0x1A4, content = "x".toByteArray())
            .finish()
        val destination = temporaryFolder.newFolder("modes").toPath()

        val plan = MailboxArchiveReader().plan(tar, destination)
        assertEquals(0x1ED, plan.entries.first { it.name == "script.sh" }.mode)
        assertEquals(0x1A4, plan.entries.first { it.name == "data.txt" }.mode)

        MailboxArchiveExtractor().extract(tar, plan, destination)
        assumeTrue("宿主文件系统没有 POSIX 权限视图，只能在类 POSIX 主机验证执行位", hasPosix(destination))
        assertEquals(0x1ED, MailboxTree.readMode(destination.resolve("script.sh")))
        assertEquals(0x1A4, MailboxTree.readMode(destination.resolve("data.txt")))
    }

    @Test
    fun `plan normalizes relative symlink targets and rejects everything else`() {
        val tar = newTar("links-plan.tar")
        RawTarWriter(tar)
            .entry("dir", typeFlag = TYPE_DIRECTORY, mode = 0x1ED)
            .entry("dir/target.txt", content = "payload\n".toByteArray())
            .entry("dir/link.txt", typeFlag = TYPE_SYMLINK, linkName = "target.txt")
            .entry("dir/up.txt", typeFlag = TYPE_SYMLINK, linkName = "../dir/target.txt")
            .finish()
        val destination = temporaryFolder.newFolder("links-plan").toPath()

        val plan = MailboxArchiveReader().plan(tar, destination)
        assertEquals(
            "target.txt",
            plan.entries.first { it.name == "dir/link.txt" }.linkTarget,
        )
        // `../dir/target.txt` 规范化后仍在落点内，因此收敛成 `target.txt`。
        assertEquals("target.txt", plan.entries.first { it.name == "dir/up.txt" }.linkTarget)
        assertEquals(2, plan.symlinkCount)
    }

    @Test
    fun `imported symlink stays a link instead of being followed`() {
        val tar = newTar("links.tar")
        RawTarWriter(tar)
            .entry("dir", typeFlag = TYPE_DIRECTORY, mode = 0x1ED)
            .entry("dir/target.txt", content = "payload\n".toByteArray())
            .entry("dir/link.txt", typeFlag = TYPE_SYMLINK, linkName = "target.txt")
            .finish()
        val destination = temporaryFolder.newFolder("links").toPath()

        val plan = MailboxArchiveReader().plan(tar, destination)
        assertEquals("target.txt", plan.entries.first { it.name == "dir/link.txt" }.linkTarget)
        assumeSymlinks(destination)

        MailboxArchiveExtractor().extract(tar, plan, destination)
        val link = destination.resolve("dir/link.txt")
        assertTrue("符号链接必须是链接而不是被跟随后的实体文件", Files.isSymbolicLink(link))
        assertEquals("target.txt", Files.readSymbolicLink(link).toString().replace('\\', '/'))
        // 内容仍可通过链接读到，证明链接没有指向落点之外。
        assertEquals("payload\n", Files.readAllBytes(link).toString(Charsets.UTF_8))
    }

    @Test
    fun `tar round trip preserves recorded modes and symlink entries without host support`() {
        val source = temporaryFolder.newFolder("header-source").toPath()
        writeText(source.resolve("script.sh"), "#!/bin/sh\n")
        writeText(source.resolve("target.txt"), "data\n")
        val plan = MailboxExportPlan(
            subdirectory = null,
            prefix = "",
            entries = listOf(
                MailboxEntry(
                    name = "script.sh",
                    kind = MailboxEntryKind.FILE,
                    bytes = 10L,
                    mode = 0x1ED,
                    sha256 = sha256Of("#!/bin/sh\n"),
                ),
                MailboxEntry(
                    name = "target.txt",
                    kind = MailboxEntryKind.FILE,
                    bytes = 5L,
                    mode = 0x1A4,
                    sha256 = sha256Of("data\n"),
                ),
                MailboxEntry(
                    name = "link.txt",
                    kind = MailboxEntryKind.SYMLINK,
                    bytes = 0L,
                    mode = 0x1FF,
                    sha256 = null,
                    linkTarget = "target.txt",
                ),
            ),
            totalBytes = 15L,
            skippedLinks = 0,
            skippedSpecial = 0,
        )
        val tar = newTar("header.tar")
        MailboxArchiveWriter().write(source, plan, tar)

        // tar 头的权限字段（偏移 100..107，八进制）必须带着 0755：这一步不依赖宿主的 POSIX 支持。
        val headerMode = tar.readBytes().copyOfRange(100, 108)
            .toString(Charsets.US_ASCII)
            .trim { it == '\u0000' || it == ' ' }
            .toInt(8)
        assertEquals(0x1ED, headerMode and 0x1FF)
        // 与 GNU tar 同口径：权限位之外还写文件类型位（常规文件 0o100000），外部 tar 会自行掩掉。
        assertEquals(0x8000, headerMode and 0xF000)

        val destination = temporaryFolder.newFolder("header-dest").toPath()
        val imported = MailboxArchiveReader().plan(tar, destination)
        assertEquals(0x1ED, imported.entries.first { it.name == "script.sh" }.mode)
        assertEquals(0x1A4, imported.entries.first { it.name == "target.txt" }.mode)
        val link = imported.entries.first { it.name == "link.txt" }
        assertEquals(MailboxEntryKind.SYMLINK, link.kind)
        assertEquals("target.txt", link.linkTarget)
        assertEquals(0x1FF, link.mode)
    }

    // ---------------------------------------------------------------- 负向：越界与恶意条目

    @Test
    fun `rejects traversal absolute fifo duplicate and oversized archives without touching the destination`() {
        val cases = listOf(
            "traversal" to { writer: RawTarWriter -> writer.entry("../evil.txt", content = "x".toByteArray()) },
            "nested traversal" to { writer: RawTarWriter -> writer.entry("a/../../evil.txt", content = "x".toByteArray()) },
            "absolute name" to { writer: RawTarWriter -> writer.entry("/etc/passwd", content = "x".toByteArray()) },
            "absolute symlink" to { writer: RawTarWriter ->
                writer.entry("link", typeFlag = TYPE_SYMLINK, linkName = "/root/.dsh")
            },
            "escaping symlink" to { writer: RawTarWriter ->
                writer.entry("link", typeFlag = TYPE_SYMLINK, linkName = "../../../root/.dsh")
            },
            "fifo" to { writer: RawTarWriter -> writer.entry("pipe", typeFlag = TYPE_FIFO) },
            "character device" to { writer: RawTarWriter -> writer.entry("null", typeFlag = TYPE_CHAR_DEVICE) },
            "duplicate entry" to { writer: RawTarWriter ->
                writer.entry("dup.txt", content = "a".toByteArray()).entry("dup.txt", content = "b".toByteArray())
            },
            "truncated content" to { writer: RawTarWriter ->
                writer.entry("big.bin", size = 4096L, content = ByteArray(512))
            },
        )
        val expected = mapOf(
            "traversal" to MailboxCodes.PATH_INVALID,
            "nested traversal" to MailboxCodes.PATH_INVALID,
            "absolute name" to MailboxCodes.PATH_INVALID,
            "absolute symlink" to MailboxCodes.LINK_ABSOLUTE,
            "escaping symlink" to MailboxCodes.LINK_ESCAPE,
            "fifo" to MailboxCodes.ENTRY_TYPE_REJECTED,
            "character device" to MailboxCodes.ENTRY_TYPE_REJECTED,
            "duplicate entry" to MailboxCodes.DUPLICATE_ENTRY,
            "truncated content" to MailboxCodes.ARCHIVE_TRUNCATED,
        )
        cases.forEach { (label, build) ->
            val destination = temporaryFolder.newFolder("reject-${label.replace(' ', '-')}").toPath()
            val tar = newTar("${label.replace(' ', '-')}.tar")
            build(RawTarWriter(tar)).finish()

            val failure = assertThrows(label, RuntimeFailure::class.java) {
                MailboxArchiveReader().plan(tar, destination)
            }
            assertEquals(label, expected.getValue(label), failure.code)
            assertTrue("$label：被拒绝的导入不得在落点留下任何条目", isEmptyDirectory(destination))
        }
    }

    @Test
    fun `rejects archives that exceed the entry and byte budgets`() {
        val destination = temporaryFolder.newFolder("budget").toPath()

        val entries = newTar("entries.tar")
        RawTarWriter(entries)
            .entry("a.txt", content = "1".toByteArray())
            .entry("b.txt", content = "2".toByteArray())
            .entry("c.txt", content = "3".toByteArray())
            .finish()
        assertEquals(
            MailboxCodes.ENTRY_LIMIT,
            assertThrows(RuntimeFailure::class.java) {
                MailboxArchiveReader(MailboxLimits(maxEntries = 2)).plan(entries, destination)
            }.code,
        )

        val total = newTar("total.tar")
        RawTarWriter(total).entry("big.bin", content = ByteArray(64) { 'a'.code.toByte() }).finish()
        assertEquals(
            MailboxCodes.SIZE_LIMIT,
            assertThrows(RuntimeFailure::class.java) {
                MailboxArchiveReader(MailboxLimits(maxTotalBytes = 32L)).plan(total, destination)
            }.code,
        )

        val single = newTar("single.tar")
        RawTarWriter(single).entry("big.bin", content = ByteArray(64) { 'a'.code.toByte() }).finish()
        assertEquals(
            MailboxCodes.SIZE_LIMIT,
            assertThrows(RuntimeFailure::class.java) {
                MailboxArchiveReader(MailboxLimits(maxFileBytes = 32L)).plan(single, destination)
            }.code,
        )
        assertTrue(isEmptyDirectory(destination))
    }

    @Test
    fun `rejects loose files and non tar content with an explicit code`() {
        val destination = temporaryFolder.newFolder("notatar").toPath()

        val loose = newTar("loose.bin")
        loose.writeBytes("this is definitely not a tar archive".toByteArray())
        assertEquals(
            MailboxCodes.INPUT_NOT_TAR,
            assertThrows(RuntimeFailure::class.java) { MailboxArchiveReader().plan(loose, destination) }.code,
        )

        val empty = newTar("empty.tar")
        empty.writeBytes(ByteArray(0))
        assertEquals(
            MailboxCodes.INPUT_NOT_TAR,
            assertThrows(RuntimeFailure::class.java) { MailboxArchiveReader().plan(empty, destination) }.code,
        )

        val zeroPadding = newTar("zero.tar")
        zeroPadding.writeBytes(ByteArray(1024))
        // 全零填充是合法的空 tar：接受，但不产出任何条目。
        assertEquals(0, MailboxArchiveReader().plan(zeroPadding, destination).entries.size)
        assertTrue(isEmptyDirectory(destination))
    }

    @Test
    fun `extractor refuses an absolute link target even when the plan was forged`() {
        val tar = newTar("forged.tar")
        RawTarWriter(tar)
            .entry("target.txt", content = "payload".toByteArray())
            .entry("link", typeFlag = TYPE_SYMLINK, linkName = "target.txt")
            .finish()
        val destination = temporaryFolder.newFolder("forged").toPath()
        val plan = MailboxArchiveReader().plan(tar, destination)
        val forged = plan.copy(
            entries = plan.entries.map {
                if (it.kind == MailboxEntryKind.SYMLINK) it.copy(linkTarget = "/root/.dsh") else it
            },
        )

        val failure = assertThrows(RuntimeFailure::class.java) {
            MailboxArchiveExtractor().extract(tar, forged, destination)
        }
        assertEquals(MailboxCodes.LINK_ABSOLUTE, failure.code)
        // 解包失败后暂存落点被整体删除：不留半截产物。
        assertFalse("失败必须清掉暂存落点", Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
    }

    // ---------------------------------------------------------------- manifest

    @Test
    fun `exported manifest describes every entry with relative paths only`() {
        val workspace = temporaryFolder.newFolder("manifest-workspace").toPath()
        Files.createDirectories(workspace.resolve("src"))
        writeText(workspace.resolve("src/main.txt"), "main\n")
        val tar = File(temporaryFolder.newFolder("manifest-outbox"), RuntimeMailboxLayout.EXPORT_TAR_NAME)
        val plan = MailboxWorkspaceScanner().scan(workspace, null)
        MailboxArchiveWriter().write(workspace, plan, tar)
        val (tarBytes, tarSha256) = MailboxDigest.bytesAndSha256(tar)

        val text = MailboxManifestCodec.render(plan, tarBytes, tarSha256, RuntimeMailboxLayout.EXPORT_TAR_NAME)
        val parsed = MailboxManifestCodec.parse(text)

        assertEquals(RuntimeMailboxLayout.MANIFEST_FORMAT, parsed.format)
        assertEquals(2, parsed.entryCount)
        assertEquals(plan.totalBytes, parsed.totalBytes)
        assertEquals(tarBytes, parsed.tarBytes)
        assertEquals(tarSha256, parsed.tarSha256)
        assertEquals(listOf("src", "src/main.txt"), parsed.entries.map { it.path })
        assertEquals("file", parsed.entries.first { it.path == "src/main.txt" }.type)
        assertEquals(sha256Of("main\n"), parsed.entries.first { it.path == "src/main.txt" }.sha256)
        assertEquals("directory", parsed.entries.first { it.path == "src" }.type)

        // 产物里不允许出现绝对路径或宿主路径。
        assertFalse("manifest 不得包含宿主绝对路径", text.contains(workspace.toString()))
        assertFalse("manifest 不得包含绝对路径取值", text.contains("\"/"))
        assertFalse("manifest 不得包含反斜杠路径", text.contains('\\'))
        assertTrue(MailboxDigest.isValid(parsed.tarSha256))
    }

    @Test
    fun `import rejects a manifest that does not match the archive`() {
        val workspace = temporaryFolder.newFolder("tamper-workspace").toPath()
        writeText(workspace.resolve("payload.txt"), "original content\n")
        val tar = File(temporaryFolder.newFolder("tamper-outbox"), "tamper.tar")
        val plan = MailboxWorkspaceScanner().scan(workspace, null)
        MailboxArchiveWriter().write(workspace, plan, tar)
        val (bytes, sha) = MailboxDigest.bytesAndSha256(tar)
        val manifest = MailboxManifestCodec.parse(
            MailboxManifestCodec.render(plan, bytes, sha, "tamper.tar"),
        )
        val destination = temporaryFolder.newFolder("tamper-import").toPath()
        MailboxManifestVerifier.verify(manifest, MailboxArchiveReader().plan(tar, destination))

        // 人为改一个字节：归档摘要与逐条摘要都必须能发现。
        val raw = tar.readBytes()
        raw[512 + 3] = (raw[512 + 3].toInt() xor 0x01).toByte()
        tar.writeBytes(raw)
        val tamperedPlan = MailboxArchiveReader().plan(tar, destination)
        assertNotEquals(sha, tamperedPlan.tarSha256)
        assertEquals(
            MailboxCodes.MANIFEST_MISMATCH,
            assertThrows(RuntimeFailure::class.java) {
                MailboxManifestVerifier.verify(manifest, tamperedPlan)
            }.code,
        )

        // 归档摘要一致但逐条摘要不符（例如 manifest 自身被改）：同样必须拒绝。
        val lyingEntries = manifest.entries.map {
            if (it.type == MailboxManifestCodec.WIRE_FILE) it.copy(sha256 = "0".repeat(64)) else it
        }
        assertEquals(
            MailboxCodes.MANIFEST_MISMATCH,
            assertThrows(RuntimeFailure::class.java) {
                MailboxManifestVerifier.verify(
                    manifest.copy(entries = lyingEntries, tarSha256 = tamperedPlan.tarSha256, tarBytes = tamperedPlan.tarBytes),
                    tamperedPlan,
                )
            }.code,
        )
        assertTrue(isEmptyDirectory(destination))
    }

    @Test
    fun `manifest codec rejects absolute paths missing fields and wrong counts`() {
        val valid = """
            {
              "format": "${RuntimeMailboxLayout.MANIFEST_FORMAT}",
              "subdirectory": null,
              "entryCount": 1,
              "totalBytes": 4,
              "tarName": "dsh-workspace.tar",
              "tarBytes": 10240,
              "tarSha256": "${"a".repeat(64)}",
              "entries": [
                { "path": "a.txt", "type": "file", "bytes": 4, "sha256": "${"b".repeat(64)}" }
              ]
            }
        """.trimIndent()
        assertEquals(1, MailboxManifestCodec.parse(valid).entryCount)

        listOf(
            valid.replace("\"a.txt\"", "\"/etc/passwd\""),
            valid.replace("\"a.txt\"", "\"../a.txt\""),
            valid.replace("\"entryCount\": 1", "\"entryCount\": 2"),
            valid.replace("\"format\": \"${RuntimeMailboxLayout.MANIFEST_FORMAT}\"", "\"format\": \"other/9\""),
            valid.replace("\"tarSha256\": \"${"a".repeat(64)}\"", "\"tarSha256\": \"short\""),
            valid.replace(
                "    { \"path\": \"a.txt\", \"type\": \"file\", \"bytes\": 4, \"sha256\": \"${"b".repeat(64)}\" }\n",
                "",
            ),
            valid.replace("\"sha256\": \"${"b".repeat(64)}\"", "\"sha256\": \"nope\""),
        ).forEach { broken ->
            assertNotEquals("替换必须真的改坏了 manifest", valid, broken)
            assertEquals(
                broken.take(48),
                MailboxCodes.MANIFEST_INVALID,
                assertThrows(RuntimeFailure::class.java) { MailboxManifestCodec.parse(broken) }.code,
            )
        }
    }

    // ---------------------------------------------------------------- 幂等与原子性

    @Test
    fun `export is reproducible and repeated export leaves a single artifact set`() {
        val workspace = temporaryFolder.newFolder("repeat-workspace").toPath()
        Files.createDirectories(workspace.resolve("a/b"))
        writeText(workspace.resolve("a/b/one.txt"), "one\n")
        writeText(workspace.resolve("top.txt"), "top\n")

        val first = File(temporaryFolder.newFolder("repeat-1"), RuntimeMailboxLayout.EXPORT_TAR_NAME)
        val second = File(temporaryFolder.newFolder("repeat-2"), RuntimeMailboxLayout.EXPORT_TAR_NAME)
        val firstPlan = MailboxWorkspaceScanner().scan(workspace, null)
        val secondPlan = MailboxWorkspaceScanner().scan(workspace, null)
        MailboxArchiveWriter().write(workspace, firstPlan, first)
        MailboxArchiveWriter().write(workspace, secondPlan, second)

        assertEquals(MailboxDigest.bytesAndSha256(first), MailboxDigest.bytesAndSha256(second))
        assertEquals(firstPlan.entries, secondPlan.entries)

        // 重复导出只覆盖同一组固定文件名，不产生第二份、第三份产物。
        val outbox = temporaryFolder.newFolder("repeat-outbox").toPath()
        val target = outbox.resolve(RuntimeMailboxLayout.EXPORT_TAR_NAME)
        MailboxTree.move(first.toPath(), target)
        MailboxTree.move(second.toPath(), target)
        assertEquals(listOf(RuntimeMailboxLayout.EXPORT_TAR_NAME), Files.list(outbox).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        })
    }

    @Test
    fun `repeated import replaces the destination instead of accumulating entries`() {
        val workspace = temporaryFolder.newFolder("idem-workspace").toPath()
        writeText(workspace.resolve("only.txt"), "only\n")
        val tar = File(temporaryFolder.newFolder("idem-outbox"), "idem.tar")
        val plan = MailboxWorkspaceScanner().scan(workspace, null)
        MailboxArchiveWriter().write(workspace, plan, tar)

        val parent = temporaryFolder.newFolder("idem-parent").toPath()
        val destination = parent.resolve(RuntimeMailboxLayout.IMPORT_DIRECTORY)
        val stagingParent = temporaryFolder.newFolder("idem-staging").toPath()
        val importPlan = MailboxArchiveReader().plan(tar, destination)

        repeat(2) { round ->
            val staging = stagingParent.resolve("staging-$round")
            MailboxArchiveExtractor().extract(tar, importPlan, staging)
            MailboxTree.replaceDirectory(staging, destination, stagingParent)
        }

        assertEquals(listOf("file only.txt ${sha256Of("only\n")}"), treeOf(destination))
        assertEquals(
            listOf(RuntimeMailboxLayout.IMPORT_DIRECTORY),
            Files.list(parent).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() },
        )
        assertTrue("提交后不得留下暂存目录", isEmptyDirectory(stagingParent))
    }

    @Test
    fun `failed commit keeps the previous destination intact`() {
        val parent = temporaryFolder.newFolder("rollback-parent").toPath()
        val destination = parent.resolve(RuntimeMailboxLayout.IMPORT_DIRECTORY)
        Files.createDirectories(destination)
        writeText(destination.resolve("keep.txt"), "keep\n")
        val stagingParent = temporaryFolder.newFolder("rollback-staging").toPath()

        val failure = assertThrows(RuntimeFailure::class.java) {
            MailboxTree.replaceDirectory(stagingParent.resolve("missing"), destination, stagingParent)
        }
        assertEquals(MailboxCodes.FILESYSTEM_ERROR, failure.code)
        assertEquals(listOf("file keep.txt ${sha256Of("keep\n")}"), treeOf(destination))
    }

    @Test
    fun `failed extraction leaves no half written staging tree`() {
        val tar = newTar("half.tar")
        RawTarWriter(tar)
            .entry("first.txt", content = "first\n".toByteArray())
            .entry("second.txt", content = "second\n".toByteArray())
            .finish()
        val destination = temporaryFolder.newFolder("half").toPath()
        val plan = MailboxArchiveReader().plan(tar, destination)
        val forged = plan.copy(
            entries = plan.entries.map {
                if (it.name == "second.txt") it.copy(sha256 = "0".repeat(64)) else it
            },
        )

        val failure = assertThrows(RuntimeFailure::class.java) {
            MailboxArchiveExtractor().extract(tar, forged, destination)
        }
        assertEquals(MailboxCodes.CONTENT_MISMATCH, failure.code)
        assertFalse("失败必须整树删除，不留半截产物", Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
    }

    // ---------------------------------------------------------------- 输入选择

    @Test
    fun `input selection is deterministic and never imports loose files`() {
        val inbox = temporaryFolder.newFolder("inbox").toPath()
        assertEquals(
            MailboxCodes.INPUT_INVALID,
            assertThrows(RuntimeFailure::class.java) { MailboxInputSelection.select(inbox.toFile()) }.code,
        )

        writeText(inbox.resolve("loose.txt"), "loose\n")
        assertEquals(
            MailboxCodes.INPUT_INVALID,
            assertThrows(RuntimeFailure::class.java) { MailboxInputSelection.select(inbox.toFile()) }.code,
        )

        writeText(inbox.resolve("a.tar"), "a")
        writeText(inbox.resolve("b.tar"), "b")
        assertEquals(
            MailboxCodes.INPUT_INVALID,
            assertThrows(RuntimeFailure::class.java) { MailboxInputSelection.select(inbox.toFile()) }.code,
        )

        writeText(inbox.resolve(RuntimeMailboxLayout.EXPORT_TAR_NAME), "export")
        val selected = MailboxInputSelection.select(inbox.toFile())
        assertEquals(RuntimeMailboxLayout.EXPORT_TAR_NAME, selected.tar.name)
        // 散文件与另外两个 tar 都不参与导入，只计入被忽略的数量。
        assertEquals(3, selected.ignoredFiles)
        assertEquals(null, selected.manifest)

        Files.delete(inbox.resolve(RuntimeMailboxLayout.EXPORT_TAR_NAME))
        Files.delete(inbox.resolve("b.tar"))
        writeText(inbox.resolve("a.manifest.json"), "{}")
        writeText(inbox.resolve("a.tar.sha256"), "${"a".repeat(64)}  a.tar\n")
        val single = MailboxInputSelection.select(inbox.toFile())
        assertEquals("a.tar", single.tar.name)
        assertEquals("a.manifest.json", single.manifest?.name)
        assertEquals("a.tar.sha256", single.sha256File?.name)
        assertEquals(1, single.ignoredFiles)
    }

    // ---------------------------------------------------------------- 树操作

    @Test
    fun `directory creation refuses to descend through a symbolic link`() {
        val root = temporaryFolder.newFolder("no-follow-root").toPath()
        val outside = temporaryFolder.newFolder("no-follow-outside").toPath()
        assumeSymlinks(root)
        Files.createSymbolicLink(root.resolve("escape"), outside)

        val failure = assertThrows(RuntimeFailure::class.java) {
            MailboxTree.createDirectoriesNoFollow(root, root.resolve("escape/nested"))
        }
        assertEquals(MailboxCodes.PATH_INVALID, failure.code)
        assertFalse(Files.exists(outside.resolve("nested"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `tree deletion refuses a path outside the allowed parent`() {
        val allowed = temporaryFolder.newFolder("delete-allowed").toPath()
        val outside = temporaryFolder.newFolder("delete-outside").toPath()
        writeText(outside.resolve("keep.txt"), "keep\n")

        val failure = assertThrows(RuntimeFailure::class.java) {
            MailboxTree.deleteTreeNoFollow(outside, allowed)
        }
        assertEquals(MailboxCodes.FILESYSTEM_ERROR, failure.code)
        assertTrue(Files.exists(outside.resolve("keep.txt")))
    }

    @Test
    fun `deletion is no-follow so a linked directory survives`() {
        val parent = temporaryFolder.newFolder("delete-parent").toPath()
        val target = Files.createDirectory(parent.resolve("target"))
        val outside = temporaryFolder.newFolder("delete-linked-outside").toPath()
        writeText(outside.resolve("keep.txt"), "keep\n")
        assumeSymlinks(parent)
        Files.createSymbolicLink(target.resolve("link"), outside)

        MailboxTree.deleteTreeNoFollow(target, parent)

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertTrue("符号链接的目标必须原样保留", Files.exists(outside.resolve("keep.txt")))
    }

    // ---------------------------------------------------------------- 夹具辅助

    private fun newTar(name: String): File = File(temporaryFolder.newFolder("tar-${name}-${counter++}"), name)

    private var counter = 0

    private fun writeText(path: Path, text: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, text.toByteArray())
    }

    private fun sha256Of(text: String): String = MailboxDigest.bytesAndSha256(fileWith(text)).second

    private fun fileWith(text: String): File {
        val file = File(temporaryFolder.newFolder("sha-${counter++}"), "payload.txt")
        file.writeText(text)
        return file
    }

    /** 目录树的形状快照：类型 + 相对路径 +（常规文件）内容 sha256。 */
    private fun treeOf(root: Path): List<String> {
        val entries = ArrayList<String>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) entries += "dir ${relative(root, dir)}"
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = relative(root, file)
                    entries += when {
                        attrs.isSymbolicLink ->
                            "link $name -> ${Files.readSymbolicLink(file).toString().replace('\\', '/')}"
                        attrs.isRegularFile -> "file $name ${MailboxDigest.bytesAndSha256(file.toFile()).second}"
                        else -> "other $name"
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return entries.sorted()
    }

    private fun relative(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    private fun isEmptyDirectory(directory: Path): Boolean {
        if (!Files.isDirectory(directory)) return true
        return Files.list(directory).use { !it.findAny().isPresent }
    }

    private fun hasPosix(directory: Path): Boolean = MailboxTree.readMode(directory) != null

    /** Windows 需要管理员或开发者模式才能建符号链接：不支持时显式跳过，而不是伪造成通过。 */
    private fun assumeSymlinks(directory: Path) {
        val probe = directory.resolve("dsh-symlink-probe")
        try {
            Files.createSymbolicLink(probe, directory.fileName)
            Files.deleteIfExists(probe)
        } catch (error: Throwable) {
            if (error !is IOException && error !is UnsupportedOperationException && error !is SecurityException) {
                throw error
            }
            assumeNoException("当前主机不允许创建符号链接", error)
        }
    }
}

/** 直接写 tar 头构造归档：不经过 commons-compress 的规范化，因此能构造出恶意条目。 */
internal class RawTarWriter(private val file: File) {
    private val out = ByteArrayOutputStream()

    fun entry(
        name: String,
        typeFlag: Char = TYPE_REGULAR,
        mode: Int = 0x1A4,
        content: ByteArray = ByteArray(0),
        linkName: String = "",
        size: Long = content.size.toLong(),
    ): RawTarWriter {
        val header = ByteArray(RECORD_SIZE)
        putText(header, 0, 100, name)
        putOctal(header, 100, 8, mode.toLong())
        putOctal(header, 108, 8, 0L)
        putOctal(header, 116, 8, 0L)
        putOctal(header, 124, 12, size)
        putOctal(header, 136, 12, 0L)
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        header[156] = typeFlag.code.toByte()
        putText(header, 157, 100, linkName)
        putText(header, 257, 6, "ustar")
        header[262] = 0
        putText(header, 263, 2, "00")
        putText(header, 265, 32, "root")
        putText(header, 297, 32, "root")
        val checksum = header.sumOf { it.toInt() and 0xFF }
        val checksumText = checksum.toString(8).padStart(6, '0')
        putText(header, 148, 8, "$checksumText\u0000 ")
        out.write(header)
        if (content.isNotEmpty()) out.write(content)
        repeat(((RECORD_SIZE - (size % RECORD_SIZE)) % RECORD_SIZE).toInt()) { out.write(0) }
        return this
    }

    fun finish() {
        repeat(RECORD_SIZE * 2) { out.write(0) }
        file.writeBytes(out.toByteArray())
    }

    private fun putText(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, length - 1)
        bytes.copyInto(target, offset, 0, count)
        for (index in offset + count until offset + length) target[index] = 0
    }

    private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        putText(target, offset, length, value.toString(8).padStart(length - 1, '0'))
    }

    companion object {
        const val TYPE_REGULAR = '0'
        const val TYPE_SYMLINK = '2'
        const val TYPE_CHAR_DEVICE = '3'
        const val TYPE_DIRECTORY = '5'
        const val TYPE_FIFO = '6'
        private const val RECORD_SIZE = 512
    }
}

private const val TYPE_REGULAR = RawTarWriter.TYPE_REGULAR
private const val TYPE_SYMLINK = RawTarWriter.TYPE_SYMLINK
private const val TYPE_CHAR_DEVICE = RawTarWriter.TYPE_CHAR_DEVICE
private const val TYPE_DIRECTORY = RawTarWriter.TYPE_DIRECTORY
private const val TYPE_FIFO = RawTarWriter.TYPE_FIFO
