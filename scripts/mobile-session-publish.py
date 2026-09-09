#!/opt/python/bin/python3
"""Atomically publish a staged DSH session file without replacing a target."""

from __future__ import annotations

import ctypes
import errno
import os
from pathlib import PurePosixPath
import re
import stat
import sys


RENAME_NOREPLACE = 1
TARGET_PATTERN = re.compile(r"session(?:\.v[0-9]+)?\.jsonl(?:\.zstd)?\Z")
STAGE_PATTERN = re.compile(r"session[.][A-Za-z0-9._-]{1,192}[.]tmp\Z")
SESSION_ROOT = PurePosixPath("/root/.dsh/sessions")
MAX_PATH_BYTES = 4096


def fail(code: int) -> "None":
    raise SystemExit(code if 0 < code < 256 else errno.EIO)


def encoded(path: str) -> bytes:
    value = os.fsencode(path)
    if not value or len(value) > MAX_PATH_BYTES or b"\0" in value:
        fail(errno.EINVAL)
    return value


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


def publish(source: str, target: str) -> None:
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

        libc = ctypes.CDLL(None, use_errno=True)
        renameat2 = getattr(libc, "renameat2", None)
        if renameat2 is None:
            fail(errno.ENOSYS)
        renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        renameat2.restype = ctypes.c_int
        result = renameat2(
            directory_fd,
            encoded(source_name),
            directory_fd,
            encoded(target_name),
            RENAME_NOREPLACE,
        )
        if result != 0:
            fail(ctypes.get_errno())
    finally:
        os.close(directory_fd)


def main() -> None:
    if len(sys.argv) != 3:
        fail(errno.EINVAL)
    publish(sys.argv[1], sys.argv[2])


if __name__ == "__main__":
    main()
