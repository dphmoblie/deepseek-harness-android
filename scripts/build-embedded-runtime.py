#!/usr/bin/env python3
"""Build a deterministic ARM64 Ubuntu/Harness rootfs without unpacking it on Windows."""

from __future__ import annotations

import argparse
import sys
import copy
import re
import gzip
import hashlib
import io
import json
import os
import posixpath
import stat
import tarfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Callable, Iterator


MAX_ARCHIVE_ENTRIES = 250_000
MAX_EXTRACTED_BYTES = 6_442_450_944
MAX_PATH_CHARS = 4_096
MAX_COMPONENT_CHARS = 255
BUFFER_SIZE = 1024 * 1024
MAX_SUPPORT_FILE_BYTES = 16 * 1024
MOBILE_AUTH_PRELOAD = Path(__file__).with_name("mobile-auth-preload.cjs")
COMPRESSION_OUTPUT_SUFFIXES = {
    # AAPT treats .gz assets specially and strips the suffix. The payload remains gzip.
    "gzip": ".bundle",
}
UBUNTU_EXCLUDED_REGULAR_PATHS = frozenset(
    {
        r"usr/lib/systemd/system/system-systemd\x2dcryptsetup.slice",
        r"usr/lib/systemd/system/system-systemd\x2dveritysetup.slice",
    },
)


class BuildError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


MAX_MOBILE_PROFILE_BYTES = 64 * 1024
MOBILE_BUNDLE_PATTERN = re.compile(r"^[A-Za-z0-9@._/-]{1,128}$")


def validate_mobile_profile(path: Path) -> dict:
    """Validate the optional mobile profile spec (scripts/mobile-profile.example.json)."""
    if path.is_symlink() or not path.is_file():
        raise BuildError("mobile profile does not exist or is not a regular file")
    content = path.read_bytes()
    if not content or len(content) > MAX_MOBILE_PROFILE_BYTES or b"\x00" in content:
        raise BuildError("mobile profile size or content is invalid")
    try:
        spec = json.loads(content)
    except json.JSONDecodeError as error:
        raise BuildError(f"mobile profile JSON is invalid: {error}") from error
    if not isinstance(spec, dict):
        raise BuildError("mobile profile must be a JSON object")
    dsh = spec.get("dsh")
    if not isinstance(dsh, dict):
        raise BuildError("mobile profile requires a dsh object")
    profile = dsh.get("profile")
    if not isinstance(profile, dict):
        raise BuildError("mobile profile requires dsh.profile")
    bundles = profile.get("bundles")
    if not isinstance(bundles, list) or not bundles or len(bundles) > 64:
        raise BuildError("mobile profile bundles must be a non-empty list (max 64)")
    for bundle in bundles:
        if not isinstance(bundle, str) or not MOBILE_BUNDLE_PATTERN.match(bundle):
            raise BuildError(f"mobile profile bundle identifier is invalid: {bundle!r}")
    mobile = spec.get("mobile")
    result: dict = {"dsh": {"profile": {"bundles": list(bundles)}}}
    if mobile is not None:
        if not isinstance(mobile, dict):
            raise BuildError("mobile profile 'mobile' section must be an object")
        layout = mobile.get("layout")
        if layout is not None and (not isinstance(layout, str) or len(layout) > 128):
            raise BuildError("mobile profile layout identifier is invalid")
        disabled = mobile.get("disabledOnMobile")
        if disabled is not None and (
            not isinstance(disabled, list)
            or len(disabled) > 64
            or any(not isinstance(name, str) or not MOBILE_BUNDLE_PATTERN.match(name) for name in disabled)
        ):
            raise BuildError("mobile profile disabledOnMobile list is invalid")
        idle = mobile.get("idleStopMinutes")
        if idle is not None and (not isinstance(idle, int) or isinstance(idle, bool) or not 1 <= idle <= 1440):
            raise BuildError("mobile profile idleStopMinutes must be an integer in 1..1440")
        embed = mobile.get("embedRootfs")
        if embed is not None and not isinstance(embed, bool):
            raise BuildError("mobile profile embedRootfs must be a boolean")
        result["mobile"] = {
            key: value
            for key, value in mobile.items()
            if key in ("layout", "disabledOnMobile", "idleStopMinutes", "embedRootfs")
        }
    return result


def verify_input(path: Path, expected_sha256: str, label: str) -> None:
    if not path.is_file():
        raise BuildError(f"{label} does not exist or is not a regular file")
    if len(expected_sha256) != 64 or any(char not in "0123456789abcdef" for char in expected_sha256):
        raise BuildError(f"{label} SHA-256 must be 64 lowercase hexadecimal characters")
    actual_sha256 = sha256_file(path)
    if actual_sha256 != expected_sha256:
        raise BuildError(f"{label} SHA-256 mismatch: {actual_sha256}")


def read_support_file(path: Path, label: str) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise BuildError(f"{label} does not exist or is not a regular file")
    content = path.read_bytes()
    if not content or len(content) > MAX_SUPPORT_FILE_BYTES or b"\x00" in content:
        raise BuildError(f"{label} size or content is invalid")
    return content


def validate_output_extension(path: Path, compression: str) -> None:
    expected_suffix = COMPRESSION_OUTPUT_SUFFIXES[compression]
    if not path.name.endswith(expected_suffix):
        raise BuildError(f"{compression} output path must end with {expected_suffix}")


@contextmanager
def open_output_tar(path: Path, compression: str, source_date_epoch: int) -> Iterator[tarfile.TarFile]:
    if compression != "gzip":
        raise BuildError(f"unsupported output compression: {compression}")
    with path.open("xb") as raw_output:
        with gzip.GzipFile(
            filename="",
            mode="wb",
            compresslevel=9,
            fileobj=raw_output,
            mtime=source_date_epoch,
        ) as compressed_output:
            with tarfile.open(fileobj=compressed_output, mode="w|", format=tarfile.PAX_FORMAT) as output:
                yield output


def has_windows_drive_prefix(value: str, *, allow_posix_root: bool = False) -> bool:
    candidate = value.removeprefix("/") if allow_posix_root else value
    return len(candidate) >= 2 and candidate[0].isascii() and candidate[0].isalpha() and candidate[1] == ":"


def normalized_path(raw_name: str) -> str:
    name = raw_name.removesuffix("/")
    while name.startswith("./"):
        name = name[2:]
    if (
        not name
        or len(name) > MAX_PATH_CHARS
        or name.startswith(("/", "\\"))
        or "\\" in name
        or has_windows_drive_prefix(name)
    ):
        raise BuildError(f"invalid archive path: {raw_name!r}")
    if any(char in name for char in ("\x00", "\r", "\n")):
        raise BuildError(f"invalid control character in archive path: {raw_name!r}")
    parts = name.split("/")
    if any(not part or part in {".", ".."} or len(part) > MAX_COMPONENT_CHARS for part in parts):
        raise BuildError(f"invalid archive path component: {raw_name!r}")
    return name


def validate_link(name: str, target: str) -> None:
    if (
        not target
        or len(target) > MAX_PATH_CHARS
        or target.startswith("\\")
        or "\\" in target
        or has_windows_drive_prefix(target, allow_posix_root=True)
    ):
        raise BuildError(f"invalid link target for {name!r}")
    if any(char in target for char in ("\x00", "\r", "\n")):
        raise BuildError(f"invalid link target for {name!r}")
    target_parts = target.removeprefix("/").split("/")
    if any(not part or len(part) > MAX_COMPONENT_CHARS for part in target_parts):
        raise BuildError(f"invalid link target component for {name!r}")
    resolved = posixpath.normpath(
        target.removeprefix("/") if target.startswith("/") else posixpath.join(posixpath.dirname(name), target),
    )
    if resolved == ".." or resolved.startswith("../"):
        raise BuildError(f"link target escapes rootfs for {name!r}")


class RootfsWriter:
    def __init__(self, output: tarfile.TarFile, source_date_epoch: int) -> None:
        self.output = output
        self.source_date_epoch = source_date_epoch
        self.seen: set[str] = set()
        self.entry_count = 0
        self.extracted_bytes = 0

    def add(self, member: tarfile.TarInfo, source: BinaryIO | None = None, *, allow_existing_dir: bool = False) -> None:
        name = normalized_path(member.name)
        member.name = name
        if name in self.seen:
            if allow_existing_dir and member.isdir():
                return
            raise BuildError(f"duplicate rootfs entry: {name}")
        self.entry_count += 1
        if self.entry_count > MAX_ARCHIVE_ENTRIES:
            raise BuildError("rootfs entry count exceeds the Android extraction limit")
        if member.isreg():
            if member.size < 0 or member.size > MAX_EXTRACTED_BYTES - self.extracted_bytes:
                raise BuildError("rootfs expanded byte count exceeds the Android extraction limit")
            self.extracted_bytes += member.size
        elif member.issym() or member.islnk():
            validate_link(name, member.linkname)
        elif not member.isdir():
            raise BuildError(f"unsupported rootfs entry type: {name}")
        self.seen.add(name)
        self.output.addfile(member, source)

    def add_directory(self, name: str) -> None:
        normalized = normalized_path(name)
        if normalized in self.seen:
            return
        parent = posixpath.dirname(normalized)
        if parent:
            self.add_directory(parent)
        info = tarfile.TarInfo(normalized)
        info.type = tarfile.DIRTYPE
        info.mode = 0o755
        info.uid = 0
        info.gid = 0
        info.uname = "root"
        info.gname = "root"
        info.mtime = self.source_date_epoch
        self.add(info)

    def add_bytes(self, name: str, content: bytes, mode: int) -> None:
        normalized = normalized_path(name)
        parent = posixpath.dirname(normalized)
        if parent:
            self.add_directory(parent)
        info = tarfile.TarInfo(normalized)
        info.size = len(content)
        info.mode = mode
        info.uid = 0
        info.gid = 0
        info.uname = "root"
        info.gname = "root"
        info.mtime = self.source_date_epoch
        self.add(info, io.BytesIO(content))

    def add_symlink(self, name: str, target: str) -> None:
        normalized = normalized_path(name)
        parent = posixpath.dirname(normalized)
        if parent:
            self.add_directory(parent)
        info = tarfile.TarInfo(normalized)
        info.type = tarfile.SYMTYPE
        info.linkname = target
        info.mode = 0o777
        info.uid = 0
        info.gid = 0
        info.uname = "root"
        info.gname = "root"
        info.mtime = self.source_date_epoch
        self.add(info)


def strip_single_root(raw_name: str, expected_root: str) -> str | None:
    name = normalized_path(raw_name)
    if name == expected_root:
        return None
    prefix = f"{expected_root}/"
    if not name.startswith(prefix):
        raise BuildError(f"archive entry is outside the expected {expected_root!r} root")
    return name.removeprefix(prefix)


def strip_flat_entry(raw_name: str) -> str | None:
    """Strip the leading './' segments of a flat archive entry (CI 使用的
    ubuntu-base 24.04 官方包为扁平结构，无顶层目录)。"""
    name = normalized_path(raw_name)
    if name == "." or name == "./":
        return None
    return name.removeprefix("./")


def copy_tar_archive(
    writer: RootfsWriter,
    archive_path: Path,
    expected_root: str,
    map_name: Callable[[str], str],
    *,
    skip_devices_under_dev: bool = False,
    excluded_regular_paths: frozenset[str] = frozenset(),
) -> None:
    flat = expected_root == ""
    excluded_source_paths = {f"{expected_root}/{name}" for name in excluded_regular_paths}
    remaining_excluded_paths = excluded_source_paths.copy()
    with tarfile.open(archive_path, "r:*") as source_tar:
        for original in source_tar:
            if original.name in excluded_source_paths:
                if not original.isreg():
                    raise BuildError(f"excluded archive path is not a regular file: {original.name}")
                if original.name not in remaining_excluded_paths:
                    raise BuildError(f"duplicate excluded archive path: {original.name}")
                remaining_excluded_paths.remove(original.name)
                continue
            stripped = strip_flat_entry(original.name) if flat else strip_single_root(original.name, expected_root)
            if stripped is None:
                continue
            mapped_name = normalized_path(map_name(stripped))
            member = copy.copy(original)
            member.name = mapped_name
            if member.islnk():
                target = strip_flat_entry(member.linkname) if flat else strip_single_root(member.linkname, expected_root)
                if target is None:
                    raise BuildError(f"hard link points at archive root: {mapped_name}")
                member.linkname = normalized_path(map_name(target))
            if not (member.isdir() or member.isreg() or member.issym() or member.islnk()):
                if skip_devices_under_dev and (mapped_name == "dev" or mapped_name.startswith("dev/")):
                    continue
                raise BuildError(f"unsupported source archive entry type: {mapped_name}")
            file_object = source_tar.extractfile(original) if member.isreg() else None
            try:
                writer.add(member, file_object, allow_existing_dir=True)
            finally:
                if file_object is not None:
                    file_object.close()
    if remaining_excluded_paths:
        if flat:
            # 官方 ubuntu-base 扁平包不保证包含钉死排除清单里的路径：
            # 缺失即视为无需排除（严格校验仅对自有 rooted 归档保留）。
            print(f"note: excluded paths not present in flat archive, skipped: {sorted(remaining_excluded_paths)}", file=sys.stderr)
        else:
            missing = ", ".join(sorted(remaining_excluded_paths))
            raise BuildError(f"expected excluded archive paths are missing: {missing}")


def skip_non_linux_runtime_path(relative: PurePosixPath) -> bool:
    lowered_parts = tuple(part.lower() for part in relative.parts)
    if any("win32" in part or part.startswith("darwin-") for part in lowered_parts):
        return True
    if relative.suffix.lower() in {".cmd", ".ps1", ".pdb", ".exe", ".dll"}:
        return True
    if "prebuilds" in lowered_parts:
        platform_index = lowered_parts.index("prebuilds") + 1
        if platform_index < len(lowered_parts) and lowered_parts[platform_index] != "linux-arm64":
            return True
    return False


def add_windows_tree(writer: RootfsWriter, source_root: Path, destination_root: str) -> None:
    writer.add_directory(destination_root)
    for current_raw, directory_names, file_names in os.walk(source_root, topdown=True, followlinks=False):
        current = Path(current_raw)
        relative = current.relative_to(source_root)
        archive_parent = destination_root if relative == Path(".") else f"{destination_root}/{relative.as_posix()}"

        for name in list(directory_names):
            path = current / name
            local_relative = path.relative_to(source_root)
            if skip_non_linux_runtime_path(PurePosixPath(local_relative.as_posix())):
                directory_names.remove(name)
                continue
            archive_name = normalized_path(f"{archive_parent}/{name}")
            if path.is_symlink():
                target = os.readlink(path).replace("\\", "/")
                info = tarfile.TarInfo(archive_name)
                info.type = tarfile.SYMTYPE
                info.linkname = target
                info.mode = 0o777
                info.uid = 0
                info.gid = 0
                info.uname = "root"
                info.gname = "root"
                info.mtime = writer.source_date_epoch
                writer.add(info)
                directory_names.remove(name)
            else:
                writer.add_directory(archive_name)

        for name in file_names:
            path = current / name
            local_relative = path.relative_to(source_root)
            if skip_non_linux_runtime_path(PurePosixPath(local_relative.as_posix())):
                continue
            archive_name = normalized_path(f"{archive_parent}/{name}")
            if path.is_symlink():
                target = os.readlink(path).replace("\\", "/")
                info = tarfile.TarInfo(archive_name)
                info.type = tarfile.SYMTYPE
                info.linkname = target
                info.mode = 0o777
                info.uid = 0
                info.gid = 0
                info.uname = "root"
                info.gname = "root"
                info.mtime = writer.source_date_epoch
                writer.add(info)
                continue
            file_stat = path.stat()
            if not stat.S_ISREG(file_stat.st_mode):
                raise BuildError(f"unsupported local runtime file type: {path}")
            info = tarfile.TarInfo(archive_name)
            info.size = file_stat.st_size
            info.mode = 0o644
            info.uid = 0
            info.gid = 0
            info.uname = "root"
            info.gname = "root"
            info.mtime = writer.source_date_epoch
            with path.open("rb") as source:
                writer.add(info, source)


def add_profiles_module_fallback(writer: RootfsWriter, dsh_root: Path, rootfs_dsh: str) -> None:
    """预生成 $DSH_HOME/profiles/node_modules 的扁平包链接。

    dsh 启动时（profile-boot）会调用 healProfilesModuleFallback 维护这个目录，
    但它只从 @deepseek-ai/dsh 包的依赖闭包收集——profile bundles
    （dsh-mobile-compat、dshmarket 等）不是 dsh 的依赖，永远不会被它链接，
    cordis 加载器从 profile 目录解析 loader entry 时就会 "Cannot find package"。
    这里在构建期按 dsh_root 的依赖闭包（含全部 bundles）预生成链接打进 rootfs，
    运行时无需（也避免在受限 ROM 上）再创建符号链接。
    """
    node_modules = dsh_root / "node_modules"
    if not node_modules.is_dir():
        return

    links: dict[str, Path] = {}
    queue: list[Path] = []

    def resolve_package(from_dir: Path, name: str) -> Path | None:
        # Node 语义：从 from_dir 逐级向父目录查找 node_modules/<name>
        cursor = from_dir
        while True:
            candidate = cursor / name if cursor.name == "node_modules" else cursor / "node_modules" / name
            if candidate.exists():
                try:
                    real = candidate.resolve()
                except OSError:
                    return None
                # pnpm 顶层包条目是符号链接（Windows 上为 junction）：解析后应指向真实目录
                if real.is_dir():
                    return real
            if cursor.parent == cursor:
                return None
            cursor = cursor.parent

    def enqueue(name: str, from_dir: Path) -> None:
        if name in links:
            return
        real = resolve_package(from_dir, name)
        if real is None:
            return
        links[name] = real
        queue.append(real)

    root_manifest = json.loads((dsh_root / "package.json").read_text(encoding="utf-8"))
    for dep in {**(root_manifest.get("dependencies") or {}), **(root_manifest.get("peerDependencies") or {})}:
        enqueue(dep, dsh_root)

    while queue:
        pkg_dir = queue.pop()
        manifest_path = pkg_dir / "package.json"
        if not manifest_path.is_file():
            continue
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for dep in {**(manifest.get("dependencies") or {}), **(manifest.get("peerDependencies") or {})}:
            if dep not in links:
                enqueue(dep, pkg_dir)

    resolved_root = dsh_root.resolve()
    for name in sorted(links):
        real = links[name]
        rootfs_real = PurePosixPath("/") / PurePosixPath(rootfs_dsh) / real.relative_to(resolved_root).as_posix()
        rel = posixpath.relpath(rootfs_real.as_posix(), start="/root/.dsh/profiles/node_modules")
        writer.add_symlink(f"root/.dsh/profiles/node_modules/{name}", rel)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ubuntu", required=True, type=Path)
    parser.add_argument("--ubuntu-sha256", required=True)
    parser.add_argument(
        "--ubuntu-root",
        default="",
        help="top-level directory inside the Ubuntu archive; empty = flat archive (official ubuntu-base layout)",
    )
    parser.add_argument("--node", required=True, type=Path)
    parser.add_argument("--node-sha256", required=True)
    parser.add_argument("--node-root", default="node-v24.19.0-linux-arm64")
    parser.add_argument("--node-version", default="24.19.0")
    parser.add_argument("--dsh-root", required=True, type=Path)
    parser.add_argument("--dsh-version", default="0.1.0-rc.6")
    parser.add_argument("--runtime-version", required=True)
    parser.add_argument("--compression", choices=tuple(COMPRESSION_OUTPUT_SUFFIXES), default="gzip")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--source-date-epoch", type=int, default=0)
    parser.add_argument(
        "--mobile-profile",
        type=Path,
        default=None,
        help="optional mobile profile spec (bundles subset + mobile flags); "
        "written to root/.dsh/profiles/web/package.json and reflected in the manifest 'mobile' field",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_arguments()
    if args.source_date_epoch < 0:
        raise BuildError("source date epoch must be non-negative")
    validate_output_extension(args.output, args.compression)
    verify_input(args.ubuntu, args.ubuntu_sha256, "Ubuntu archive")
    verify_input(args.node, args.node_sha256, "Node.js archive")
    dsh_entrypoint = args.dsh_root / "node_modules" / "@deepseek-ai" / "dsh" / "lib" / "bin.js"
    node_pty = (
        args.dsh_root
        / "node_modules"
        / ".pnpm"
        / "node-pty@1.1.0"
        / "node_modules"
        / "node-pty"
        / "prebuilds"
        / "linux-arm64"
        / "pty.node"
    )
    if not dsh_entrypoint.is_file() or not node_pty.is_file():
        raise BuildError("Harness runtime is missing its CLI or Linux ARM64 node-pty module")
    mobile_auth_preload = read_support_file(MOBILE_AUTH_PRELOAD, "mobile authentication preload")
    mobile_spec = validate_mobile_profile(args.mobile_profile) if args.mobile_profile is not None else None
    if args.output.exists() or args.manifest.exists():
        raise BuildError("output archive and manifest must not already exist")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = args.output.with_name(f"{args.output.name}.{os.getpid()}.part")
    temporary_manifest = args.manifest.with_name(f"{args.manifest.name}.{os.getpid()}.part")

    try:
        with open_output_tar(temporary_output, args.compression, args.source_date_epoch) as output_tar:
            writer = RootfsWriter(output_tar, args.source_date_epoch)
            copy_tar_archive(
                writer,
                args.ubuntu,
                args.ubuntu_root,
                lambda name: name,
                skip_devices_under_dev=True,
                excluded_regular_paths=UBUNTU_EXCLUDED_REGULAR_PATHS,
            )
            copy_tar_archive(writer, args.node, args.node_root, lambda name: f"opt/node/{name}")
            add_windows_tree(writer, args.dsh_root, "opt/dsh")
            add_profiles_module_fallback(writer, args.dsh_root, "opt/dsh")
            writer.add_symlink("usr/local/bin/node", "../../../opt/node/bin/node")
            # Ubuntu base 精简包不含这两个链接，但 App 完整性校验将其列为必需：
            # 运行时（mount 视图/时区）与校验都需要，缺了安装会报 ROOTFS_LINKS_CORRUPTED。
            writer.add_symlink("etc/mtab", "../proc/self/mounts")
            writer.add_symlink("etc/localtime", "../usr/share/zoneinfo/Etc/UTC")
            writer.add_bytes(
                "usr/local/bin/dsh",
                b'#!/bin/sh\nexec /opt/node/bin/node /opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js "$@"\n',
                0o755,
            )
            writer.add_bytes(
                "usr/local/lib/dsh-mobile-auth.cjs",
                mobile_auth_preload,
                0o644,
            )
            metadata = {
                "dshVersion": args.dsh_version,
                "nodeVersion": args.node_version,
                "runtimeVersion": args.runtime_version,
                "ubuntuSha256": args.ubuntu_sha256,
                "nodeSha256": args.node_sha256,
            }
            writer.add_bytes(
                "etc/deepseek-harness-runtime.json",
                (json.dumps(metadata, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
                0o644,
            )
            if mobile_spec is not None:
                writer.add_bytes(
                    "root/.dsh/profiles/web/package.json",
                    (json.dumps(mobile_spec, ensure_ascii=True, indent=2) + "\n").encode("ascii"),
                    0o644,
                )

        compressed_bytes = temporary_output.stat().st_size
        archive_sha256 = sha256_file(temporary_output)
        manifest = {
            "schemaVersion": 1,
            "runtimeId": "ubuntu-24.04-arm64-deepseek-harness",
            "version": args.runtime_version,
            "architecture": "arm64-v8a",
            "rootfs": {
                "url": f"https://bundled.invalid/runtime/rootfs{COMPRESSION_OUTPUT_SUFFIXES[args.compression]}",
                "sha256": archive_sha256,
                "compressedBytes": compressed_bytes,
                "extractedBytes": writer.extracted_bytes,
                "compression": args.compression,
            },
            "entrypoints": {
                "shell": ["/bin/bash", "--login"],
                "harness": ["/usr/local/bin/dsh", "web", "--host", "127.0.0.1", "--port", "3080"],
            },
            "harnessUrl": "http://127.0.0.1:3080/",
            **({"mobile": mobile_spec} if mobile_spec is not None else {}),
        }
        manifest_bytes = (json.dumps(manifest, ensure_ascii=True, indent=2) + "\n").encode("ascii")
        with temporary_manifest.open("xb") as manifest_output:
            manifest_output.write(manifest_bytes)
            manifest_output.flush()
            os.fsync(manifest_output.fileno())
        os.replace(temporary_output, args.output)
        os.replace(temporary_manifest, args.manifest)
        print(
            json.dumps(
                {
                    "archive": str(args.output),
                    "compression": args.compression,
                    "compressedBytes": compressed_bytes,
                    "entries": writer.entry_count,
                    "extractedBytes": writer.extracted_bytes,
                    "sha256": archive_sha256,
                },
                sort_keys=True,
            ),
        )
    finally:
        temporary_output.unlink(missing_ok=True)
        temporary_manifest.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except (BuildError, OSError, tarfile.TarError) as error:
        raise SystemExit(str(error)) from error
