#!/usr/bin/env python3
"""Exercise no-hardlink publication against a real Linux filesystem."""

import concurrent.futures
import errno
import importlib.util
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4


spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("mobile-session-publish.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
DIGEST = "ab" * 32
UUID = "01234567-89ab-4cde-8f01-23456789abcd"


class PathValidationTests(unittest.TestCase):
    def test_rejects_noncanonical_and_oversized_paths(self):
        for value in ("relative", "/a/../b", "/a/./b", "/a//b", "/a\n", "/a\0", "/" + "a" * 256, "/" + "界" * 90):
            with self.subTest(value=repr(value)), self.assertRaises(SystemExit) as failure:
                publisher.canonical_path(value)
            self.assertEqual(failure.exception.code, errno.EINVAL)

    def test_attachment_alias_requires_matching_digest_and_prefix(self):
        root = publisher.ATTACHMENT_ROOT
        source = root / "file-objects" / "ab" / DIGEST
        self.assertEqual(publisher.copy_publication_kind(source, root / "files" / "ab" / DIGEST / "报告.txt"), "attachment-alias")
        with self.assertRaises(SystemExit):
            publisher.copy_publication_kind(source, root / "files" / "cd" / ("cd" * 32) / "report.txt")

    def test_workspace_requires_the_matching_private_staging_layout(self):
        target = PurePosixPath("/root/project/报告 $() ' name.txt")
        source = target.parent / f".{target.name}.123.{UUID}.tmpdir" / f"{target.name}.tmp"
        self.assertEqual(publisher.copy_publication_kind(source, target), "file")
        self.assertIsNone(publisher.copy_publication_kind(source, target.parent / "another.txt"))
        self.assertIsNone(publisher.copy_publication_kind(source, PurePosixPath("/other") / target.name))


@unittest.skipUnless(sys.platform.startswith("linux"), "requires Linux renameat2 and POSIX permissions")
class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="dsh-publication-")
        self.root = Path(self.directory.name)
        self.original_roots = publisher.ATTACHMENT_ROOT, publisher.SESSION_ROOT
        publisher.ATTACHMENT_ROOT = PurePosixPath(self.root / "attachments" / "v1")
        publisher.SESSION_ROOT = PurePosixPath(self.root / "sessions")

    def tearDown(self):
        publisher.ATTACHMENT_ROOT, publisher.SESSION_ROOT = self.original_roots
        self.directory.cleanup()

    def file(self, path, data=b"complete content", mode=0o600):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.parent.chmod(0o700)
        path.write_bytes(data)
        path.chmod(mode)
        return path

    def workspace(self, name="报告 $() ' name.txt", data=b"complete content"):
        target = self.root / "workspace" / name
        source = target.parent / f".{name}.123.{uuid4()}.tmpdir" / f"{name}.tmp"
        return self.file(source, data), target

    def publish(self, source, target):
        target.parent.mkdir(parents=True, exist_ok=True)
        publisher.publish(str(source), str(target))

    def assert_clean(self):
        self.assertEqual(list(self.root.rglob(".dsh-publish-*")), [])

    def test_new_file_keeps_source_and_private_mode(self):
        source, target = self.workspace()
        self.publish(source, target)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        self.assertNotEqual(source.stat().st_ino, target.stat().st_ino)
        self.assert_clean()

    def test_all_attachment_object_buckets_keep_the_staged_source(self):
        for bucket in ("objects", "file-objects", "request-images"):
            with self.subTest(bucket=bucket):
                source = self.file(publisher.ATTACHMENT_ROOT / "tmp" / str(uuid4()), b"image or file bytes")
                target = Path(publisher.ATTACHMENT_ROOT / bucket / "ab" / DIGEST)
                self.publish(source, target)
                self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assert_clean()

    def test_alias_keeps_readonly_object_available(self):
        source = self.file(publisher.ATTACHMENT_ROOT / "file-objects" / "ab" / DIGEST, mode=0o400)
        target = Path(publisher.ATTACHMENT_ROOT / "files" / "ab" / DIGEST / "报告.txt")
        self.publish(source, target)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o400)
        self.assert_clean()

    def test_legacy_session_still_consumes_its_stage_and_preserves_collision(self):
        target = Path(publisher.SESSION_ROOT / "project" / "session" / "session.v3.jsonl.zstd")
        source = self.file(str(target) + ".0123456789ab.tmp", b"first log")
        self.publish(source, target)
        self.assertFalse(source.exists())
        source = self.file(str(target) + ".abcdef012345.tmp", b"second log")
        with self.assertRaises(SystemExit) as failure:
            self.publish(source, target)
        self.assertEqual(failure.exception.code, errno.EEXIST)
        self.assertEqual(target.read_bytes(), b"first log")
        self.assertEqual(source.read_bytes(), b"second log")

    def test_existing_file_directory_and_symlink_are_never_replaced(self):
        for kind in ("file", "directory", "symlink"):
            with self.subTest(kind=kind):
                source, target = self.workspace(kind)
                if kind == "file":
                    self.file(target, b"winner")
                elif kind == "directory":
                    target.mkdir()
                else:
                    target.symlink_to(self.file(self.root / "protected", b"winner"))
                with self.assertRaises(SystemExit) as failure:
                    self.publish(source, target)
                self.assertEqual(failure.exception.code, errno.EEXIST)
                self.assertTrue(source.exists())
                if kind == "directory":
                    self.assertTrue(target.is_dir())
                else:
                    self.assertEqual(target.read_bytes(), b"winner")
                    self.assertEqual(target.is_symlink(), kind == "symlink")
        self.assert_clean()

    def test_simultaneous_writers_publish_exactly_one_complete_file(self):
        fixtures = [self.workspace("race.txt", bytes([index]) * 100_000) for index in range(8)]
        gate = threading.Barrier(len(fixtures))

        def publish_one(fixture):
            gate.wait(timeout=5)
            try:
                self.publish(*fixture)
                return 0
            except SystemExit as failure:
                return failure.code

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(publish_one, fixtures))
        self.assertEqual(sorted(results), [0] + [errno.EEXIST] * 7)
        winner = results.index(0)
        self.assertEqual(fixtures[0][1].read_bytes(), fixtures[winner][0].read_bytes())
        self.assertTrue(all(source.exists() for source, _ in fixtures))
        self.assert_clean()

    def test_readers_cannot_see_a_partial_copy(self):
        source, target = self.workspace(data=b"x" * (publisher.COPY_CHUNK_BYTES + 7))
        copied = threading.Event()
        release = threading.Event()
        original_write = os.write

        def delayed_write(fd, chunk):
            size = original_write(fd, chunk)
            copied.set()
            if not release.wait(timeout=5):
                raise TimeoutError("reader check did not finish")
            return size

        with patch.object(publisher.os, "write", side_effect=delayed_write), concurrent.futures.ThreadPoolExecutor() as pool:
            future = pool.submit(self.publish, source, target)
            try:
                self.assertTrue(copied.wait(timeout=5))
                self.assertFalse(target.exists())
            finally:
                release.set()
            future.result(timeout=5)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assert_clean()

    def test_short_writes_are_completed(self):
        source, target = self.workspace(data=b"x" * 10_000)
        original_write = os.write
        with patch.object(publisher.os, "write", side_effect=lambda fd, data: original_write(fd, data[:997])):
            self.publish(source, target)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assert_clean()

    def test_copy_and_sync_failures_leave_no_target_or_temporary_copy(self):
        for operation in ("write", "fsync"):
            with self.subTest(operation=operation):
                source, target = self.workspace(operation)
                with patch.object(publisher.os, operation, side_effect=OSError(errno.ENOSPC, "full")):
                    with self.assertRaises(OSError) as failure:
                        self.publish(source, target)
                self.assertEqual(failure.exception.errno, errno.ENOSPC)
                self.assertFalse(target.exists())
                self.assertTrue(source.exists())
                self.assert_clean()

    def test_unavailable_atomic_rename_does_not_fall_back_to_overwrite(self):
        source, target = self.workspace()
        with patch.object(publisher, "rename_no_replace", side_effect=SystemExit(errno.ENOSYS)):
            with self.assertRaises(SystemExit) as failure:
                self.publish(source, target)
        self.assertEqual(failure.exception.code, errno.ENOSYS)
        self.assertFalse(target.exists())
        self.assertTrue(source.exists())
        self.assert_clean()

    def test_source_change_during_copy_is_rejected(self):
        source, target = self.workspace()
        original_read = os.read

        def changed_read(fd, size):
            chunk = original_read(fd, size)
            source.write_bytes(b"changed source with another size")
            return chunk

        with patch.object(publisher.os, "read", side_effect=changed_read):
            with self.assertRaises(SystemExit) as failure:
                self.publish(source, target)
        self.assertEqual(failure.exception.code, errno.EIO)
        self.assertFalse(target.exists())
        self.assert_clean()

    def test_untrusted_source_types_and_permissions_are_rejected(self):
        for kind in ("symlink", "fifo", "public", "executable", "hardlink"):
            with self.subTest(kind=kind):
                source, target = self.workspace(kind)
                if kind in ("symlink", "fifo"):
                    source.unlink()
                    if kind == "symlink":
                        source.symlink_to(self.file(self.root / "protected", b"private"))
                    else:
                        os.mkfifo(source, 0o600)
                elif kind == "hardlink":
                    os.link(source, source.parent / "other-name")
                else:
                    source.chmod(0o644 if kind == "public" else 0o700)
                with self.assertRaises((SystemExit, OSError)):
                    self.publish(source, target)
                self.assertFalse(target.exists())
        self.assert_clean()

    def test_symlink_ancestor_is_not_followed(self):
        source, target = self.workspace()
        link = self.root / "redirect"
        link.symlink_to(target.parent, target_is_directory=True)
        redirected_source = link / source.parent.name / source.name
        with self.assertRaises(OSError):
            self.publish(redirected_source, link / target.name)
        self.assertFalse(target.exists())
        self.assert_clean()


if __name__ == "__main__":
    unittest.main()
