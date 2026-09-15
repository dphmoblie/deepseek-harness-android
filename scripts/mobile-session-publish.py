#!/opt/python/bin/python3
"""Publish DSH sessions, attachments and new files without hard links.

The installed filename is retained for runtime compatibility. Attachment/file
publication preserves the source; only the legacy session path consumes it.
"""

from __future__ import annotations

import ctypes
import errno
import os
from pathlib import PurePosixPath
import re
import secrets
import stat
import sys


RENAME_NOREPLACE = 1
TARGET_PATTERN = re.compile(r"session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?\Z")
STAGE_PATTERN = re.compile(r"session[.][A-Za-z0-9._-]{1,192}[.]tmp\Z")
SESSION_ROOT = PurePosixPath("/root/.dsh/sessions")
ATTACHMENT_ROOT = PurePosixPath("/root/.dsh/attachments/v1")
MAX_PATH_BYTES = 4096
COPY_CHUNK_BYTES = 1024 * 1024
UUID_PATTERN = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
OBJECT_PATTERN = re.compile(r"(objects|file-objects|request-images)/([a-f0-9]{2})/([a-f0-9]{64})\Z")
ALIAS_PATTERN = re.compile(r"files/([a-f0-9]{2})/([a-f0-9]{64})/[^/]+\Z")


def fail(code: int) -> "None":
    raise SystemExit(code if 0 < code < 256 else errno.EIO)


def encoded(path: str) -> bytes:
    value = os.fsencode(path)
    if not value or len(value) > MAX_PATH_BYTES or b"\0" in value:
        fail(errno.EINVAL)
    return value


def canonical_path(value: str) -> PurePosixPath:
    """安全校验点：拒绝非绝对路径、路径折叠、控制字符和过长组件。"""
    encoded(value)
    if not value.startswith("/") or any(ord(char) < 32 or ord(char) == 127 for char in value):
        fail(errno.EINVAL)
    if any(part in ("", ".", "..") or len(os.fsencode(part)) > 255 for part in value[1:].split("/")):
        fail(errno.EINVAL)
    return PurePosixPath(value)


def copy_publication_kind(source: PurePosixPath, target: PurePosixPath) -> str | None:
    if source.is_relative_to(ATTACHMENT_ROOT) and target.is_relative_to(ATTACHMENT_ROOT):
        original = str(source.relative_to(ATTACHMENT_ROOT))
        final = str(target.relative_to(ATTACHMENT_ROOT))
        obj = OBJECT_PATTERN.fullmatch(final)
        if re.fullmatch(r"tmp/" + UUID_PATTERN, original) and obj and obj[2] == obj[3][:2]:
            return "attachment-stage"
        obj = OBJECT_PATTERN.fullmatch(original)
        alias = ALIAS_PATTERN.fullmatch(final)
        if obj and obj[1] == "file-objects" and obj[2] == obj[3][:2] and alias and (alias[1], alias[2]) == (obj[2], obj[3]):
            return "attachment-alias"
        fail(errno.EACCES)
    prefix = f".{target.name}."
    staging = source.parent
    if (
        staging.parent == target.parent
        and source.name == f"{target.name}.tmp"
        and staging.name.startswith(prefix)
        and re.fullmatch(r"[0-9]{1,10}\." + UUID_PATTERN + r"\.tmpdir", staging.name[len(prefix):])
    ):
        return "file"
    return None


def open_directory(path: PurePosixPath) -> int:
    """Walk canonical absolute components using pinned, no-follow directory FDs."""
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW
    directory_fd = os.open("/", flags)
    try:
        for component in path.parts[1:]:
            next_fd = os.open(component, flags, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        return directory_fd
    except BaseException:
        os.close(directory_fd)
        raise


def rename_no_replace(source_fd: int, source_name: str, target_fd: int, target_name: str) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        fail(errno.ENOSYS)
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    if renameat2(source_fd, encoded(source_name), target_fd, encoded(target_name), RENAME_NOREPLACE) != 0:
        fail(ctypes.get_errno())


def copy_snapshot(source_fd: int, output_fd: int, before: os.stat_result) -> None:
    # Fixed memory bound and a fixed byte count prevent a growing source from
    # keeping the helper alive forever. A changed source must not be published.
    remaining = before.st_size
    while remaining:
        chunk = os.read(source_fd, min(COPY_CHUNK_BYTES, remaining))
        if not chunk:
            fail(errno.EIO)
        remaining -= len(chunk)
        pending = memoryview(chunk)
        while pending:
            written = os.write(output_fd, pending)
            if written <= 0:
                fail(errno.EIO)
            pending = pending[written:]
    after = os.fstat(source_fd)
    if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        fail(errno.EIO)


def publish_copy(source: PurePosixPath, target: PurePosixPath, kind: str) -> None:
    source_parent_fd = open_directory(source.parent)
    source_fd = target_fd = staging_fd = output_fd = None
    staging_name = None
    try:
        # Safety: only regular, private DSH files are copied; symlinks/FIFOs are
        # rejected without blocking. The new destination never gains permissions.
        if os.fstat(source_parent_fd).st_mode & 0o077:
            fail(errno.EACCES)
        source_fd = os.open(source.name, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=source_parent_fd)
        info = os.fstat(source_fd)
        mode = stat.S_IMODE(info.st_mode)
        if not stat.S_ISREG(info.st_mode) or mode not in (0o400, 0o600):
            fail(errno.EINVAL)
        if kind != "attachment-alias" and info.st_nlink != 1:
            fail(errno.EINVAL)
        target_fd = open_directory(target.parent)
        # A private directory also protects the completed copy from name swaps
        # before renameat2 when the workspace parent is shared with other users.
        candidate = f".dsh-publish-{secrets.token_hex(16)}"
        os.mkdir(candidate, 0o700, dir_fd=target_fd)
        staging_name = candidate
        staging_fd = os.open(candidate, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW, dir_fd=target_fd)
        output_fd = os.open("payload", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW, 0o600, dir_fd=staging_fd)
        copy_snapshot(source_fd, output_fd, info)
        os.fchmod(output_fd, mode)
        os.fsync(output_fd)
        os.close(output_fd)
        output_fd = None
        rename_no_replace(staging_fd, "payload", target_fd, target.name)
        os.fsync(target_fd)
    finally:
        if output_fd is not None:
            os.close(output_fd)
        if staging_fd is not None:
            try:
                os.unlink("payload", dir_fd=staging_fd)
            except FileNotFoundError:
                pass
            finally:
                os.close(staging_fd)
        try:
            if staging_name is not None:
                os.rmdir(staging_name, dir_fd=target_fd)
        finally:
            for descriptor in (source_fd, source_parent_fd, target_fd):
                if descriptor is not None:
                    os.close(descriptor)


def validate_paths(source: str, target: str) -> tuple[str, str, str]:
    source_path = PurePosixPath(source)
    target_path = PurePosixPath(target)
    if not source_path.is_absolute() or not target_path.is_absolute():
        fail(errno.EINVAL)
    if source_path.parent != target_path.parent or source_path == target_path:
        fail(errno.EINVAL)
    try:
        relative_parent = target_path.parent.relative_to(SESSION_ROOT)
    except ValueError:
        fail(errno.EACCES)
    parts = relative_parent.parts
    if len(parts) != 2 or any(part in ("", ".", "..") or len(part) > 255 for part in parts):
        fail(errno.EACCES)
    if not TARGET_PATTERN.fullmatch(target_path.name) or not STAGE_PATTERN.fullmatch(source_path.name):
        fail(errno.EINVAL)
    encoded(str(source_path))
    encoded(str(target_path))
    return str(source_path.parent), source_path.name, target_path.name


def open_session_directory(parent: str) -> int:
    parent_path = PurePosixPath(parent)
    relative = parent_path.relative_to(SESSION_ROOT)
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW
    try:
        directory_fd = os.open(str(SESSION_ROOT), flags)
    except OSError as error:
        fail(error.errno or errno.EIO)
    try:
        for component in relative.parts:
            next_fd = os.open(component, flags, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        return directory_fd
    except OSError as error:
        os.close(directory_fd)
        fail(error.errno or errno.EIO)


def publish_session(source: str, target: str) -> None:
    parent, source_name, target_name = validate_paths(source, target)
    directory_fd = open_session_directory(parent)
    try:
        try:
            source_stat = os.stat(source_name, dir_fd=directory_fd, follow_symlinks=False)
        except OSError as error:
            fail(error.errno or errno.EIO)
        if (
            not stat.S_ISREG(source_stat.st_mode)
            or source_stat.st_nlink != 1
            or source_stat.st_mode & 0o077
        ):
            fail(errno.EINVAL)

        rename_no_replace(directory_fd, source_name, directory_fd, target_name)
    finally:
        os.close(directory_fd)


def publish(source: str, target: str) -> None:
    source_path, target_path = canonical_path(source), canonical_path(target)
    if source_path == target_path:
        fail(errno.EINVAL)
    kind = copy_publication_kind(source_path, target_path)
    if kind is None:
        publish_session(source, target)
    else:
        publish_copy(source_path, target_path, kind)


def main() -> None:
    if len(sys.argv) != 3:
        fail(errno.EINVAL)
    try:
        publish(sys.argv[1], sys.argv[2])
    except OSError as error:
        # The Node bridge maps only errno; never send source paths or tracebacks.
        fail(error.errno or errno.EIO)


if __name__ == "__main__":
    main()
