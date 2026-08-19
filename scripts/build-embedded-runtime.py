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
from typing import BinaryIO, Callable, Iterable, Iterator
from urllib.parse import urlsplit


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
PROFILE_BUNDLE_NAMES = (
    "@deepseek-ai/dsh-base",
    "@deepseek-ai/dsh-web-app",
    "@linxin666/dsh-web-ui-all",
    "@liustack/modlens",
    "dshmarket",
)
MOBILE_SETTINGS_LAYOUT_MARKER = "dsh-mobile-settings-layout-v1"
CLIENT_FAILURE_DISPLAY_MARKER = "dsh-client-failure-display-v2"


class BuildError(RuntimeError):
    pass


def unique_file_candidates(paths: Iterable[Path]) -> list[Path]:
    """Return each installed package copy once, preserving discovery order.

    pnpm exposes the same package through a versioned store path and a
    top-level symlink.  Patching one spelling and silently skipping the other
    makes the generated runtime depend on filesystem traversal order, so every
    resolved file must be checked below.
    """
    result: list[Path] = []
    seen: set[str] = set()
    for path in paths:
        if not path.is_file():
            continue
        try:
            key = os.path.normcase(str(path.resolve(strict=False)))
        except OSError:
            key = os.path.normcase(str(path.absolute()))
        if key in seen:
            continue
        seen.add(key)
        result.append(path)
    return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


MAX_MOBILE_PROFILE_BYTES = 64 * 1024
MOBILE_BUNDLE_PATTERN = re.compile(
    r"^(?:@[A-Za-z0-9][A-Za-z0-9._-]{0,61}/)?[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"
)


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

    def preseed_directories(self, names: Iterable[str]) -> None:
        """流式重建场景：预注入 tar 流中已存在的目录，避免追加新树时重复写父目录链。

        仅登记目录路径，不写条目、不计入 entry_count/extracted_bytes；
        新树中与旧条目同路径的文件仍会被 add() 以 duplicate 拒绝。
        """
        for raw_name in names:
            self.seen.add(normalized_path(raw_name))

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


def inject_bundles_into_dsh_manifest(dsh_root: Path, bundle_names: Iterable[str] = PROFILE_BUNDLE_NAMES) -> None:
    """把 profile bundles 注入 @deepseek-ai/dsh 的 package.json dependencies。

    dsh 启动时 healProfilesModuleFallback 只从 dsh 包的依赖闭包维护
    profiles/node_modules；profile bundles 不是 dsh 的依赖时不会被它链接，
    cordis 加载器从 profile 目录解析 loader entry 就会 "Cannot find package"。
    这里把 bundles 注入 dsh 包依赖，让官方机制在运行时自动补齐链接，
    构建期预置链接仅作兜底。
    """
    bundle_names = tuple(bundle_names)
    for manifest_path in dsh_root.glob(
        "node_modules/.pnpm/@deepseek-ai+dsh@*/node_modules/@deepseek-ai/dsh/package.json",
    ):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        deps = manifest.setdefault("dependencies", {})
        changed = False
        # A reused build directory may still carry the experimental layout
        # package from an older profile. Remove that stale edge so Cordis does
        # not discover an unrequested root-slot owner at runtime.
        if "dsh-mobile-compat" not in bundle_names and deps.pop("dsh-mobile-compat", None) is not None:
            changed = True
        for name in bundle_names:
            if name not in deps:
                deps[name] = "*"
                changed = True
        if changed:
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )


def add_profiles_module_fallback(writer: RootfsWriter, dsh_root: Path, rootfs_dsh: str) -> int:
    """预生成 $DSH_HOME/profiles/node_modules 的扁平包链接，返回链接总数。

    dsh 启动时（profile-boot）会调用 healProfilesModuleFallback 维护这个目录，
    但它只从 @deepseek-ai/dsh 包的依赖闭包收集；profile bundles 不是 dsh 的依赖时，
    不会被它链接，
    cordis 加载器从 profile 目录解析 loader entry 时就会 "Cannot find package"。
    这里在构建期按 dsh_root 的依赖闭包（含全部 bundles）预生成链接打进 rootfs，
    运行时无需（也避免在受限 ROM 上）再创建符号链接。链接总数写入 manifest 的
    `profileLinks` 字段，供 verify-bundle.py 对照，防止回归成"只链接了部分包"。
    """
    node_modules = dsh_root / "node_modules"
    if not node_modules.is_dir():
        return 0

    links: dict[str, Path] = {}
    queue: list[Path] = []

    def resolve_package(from_dir: Path, name: str) -> Path | None:
        # Node 语义：从 from_dir 逐级向父目录查找 node_modules/<name>
        cursor = from_dir
        while True:
            candidate = cursor / name if cursor.name == "node_modules" else cursor / "node_modules" / name
            try:
                # `tar` extracted pnpm links can report exists() == false on
                # Windows while resolve() still reaches their real target.
                real = candidate.resolve()
            except OSError:
                real = candidate
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
        link_name = f"root/.dsh/profiles/node_modules/{name}"
        link_dir = "/" + posixpath.dirname(link_name)
        rel = posixpath.relpath(rootfs_real.as_posix(), start=link_dir)
        writer.add_symlink(link_name, rel)
    return len(links)


def patch_dsh_app_boot(dsh_root: Path) -> None:
    """放宽 dsh-app-boot 的 profiles/node_modules 链接维护（ROM 兼容）。

    荣耀等 ROM 的 SELinux 禁止应用创建符号链接时，SafeRootfsExtractor 会把
    profiles/node_modules 的链接降级复制成真实目录。dsh 启动时
    healProfilesModuleFallback -> ensureSymlink 对"存在且不是符号链接"的条目
    会直接抛错（"exists and is not a symlink"），导致 dsh 无法启动。这里在
    构建期对 dsh-app-boot 打两个补丁：

    1. 存在（任意类型）即信任跳过——条目要么是构建期预置链接，要么是
       解压降级复制出的目录，两者都可用，不需要强制重建成符号链接；
    2. 创建符号链接失败（EACCES/EPERM/ENOTSUP）时静默返回——受限 ROM 上
       补链必然失败，解析靠扁平 profiles/node_modules 目录兜底。

    补丁基于 dsh-app-boot 0.1.0-rc.6 的精确源码文本；任何一处不匹配都会
    让构建失败（fail loud），避免静默打偏。
    """
    candidates: list[Path] = []
    candidates.extend(
        dsh_root.glob("node_modules/.pnpm/*/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js")
    )
    top_level = dsh_root / "node_modules" / "@deepseek-ai" / "dsh-app-boot" / "lib" / "index.js"
    if top_level.is_file():
        candidates.append(top_level)
    candidates = unique_file_candidates(candidates)

    trust_existing = (
        "if (!stat.isSymbolicLink()) throw new Error("
        "`dsh: ${link} exists and is not a symlink; remove it so dsh can manage the installation fallback`);"
    )
    trust_existing_replacement = (
        "if (!stat.isSymbolicLink()) return; "
        "/* dsh-mobile: trust prebuilt or degraded (copied) profiles entries */"
    )
    tolerate_denied = (
        'if (error.code !== "EEXIST" || !lstatSync(link).isSymbolicLink() || readlinkSync(link) !== target) throw error;'
    )
    tolerate_denied_replacement = (
        'if (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP") return; '
        'if (error.code !== "EEXIST" || !lstatSync(link).isSymbolicLink() || readlinkSync(link) !== target) throw error;'
    )

    if not candidates:
        raise BuildError(
            "dsh-app-boot ensureSymlink patch found no installed copy; "
            "aborting to avoid shipping an unpatched runtime"
        )
    for path in candidates:
        text = path.read_text(encoding="utf-8")
        original = text
        if trust_existing in text:
            text = text.replace(trust_existing, trust_existing_replacement)
        if tolerate_denied in text:
            text = text.replace(tolerate_denied, tolerate_denied_replacement)
        if text != original:
            path.write_text(text, encoding="utf-8")
        if trust_existing_replacement not in text or tolerate_denied_replacement not in text:
            raise BuildError(
                f"dsh-app-boot ensureSymlink patch is incomplete in {path}; "
                "aborting to avoid shipping a partially patched runtime"
            )


def patch_session_persistence(dsh_root: Path) -> None:
    """荣耀等 ROM 禁 link()（EACCES）时会话持久化原子写失败补丁。

    dsh-session-persistence-jsonl 的 materializePosix() 用 mkdtemp + link(tmp, finalPath)
    实现"不覆盖"的原子发布；荣耀 SELinux 拒绝 link 系统调用（与解压期
    symlink/hardlink 降级同一家族），导致会话文件永远写不出、agent 必然报
    "本轮因错误终止"。这里把 link 失败（EACCES/EPERM/ENOTSUP/EXDEV）降级为
    copyFile（普通写，已验证可行），语义从"硬链接发布"变为"复制发布"。
    """
    candidates: list[Path] = []
    candidates.extend(
        dsh_root.glob(
            "node_modules/.pnpm/*/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js"
        )
    )
    top_level = (
        dsh_root / "node_modules" / "@deepseek-ai" / "dsh-session-persistence-jsonl" / "lib" / "index.js"
    )
    if top_level.is_file():
        candidates.append(top_level)
    candidates = unique_file_candidates(candidates)

    old_line = "\t\t\tawait link(tmp, finalPath);"
    new_lines = (
        "\t\t\tawait link(tmp, finalPath).catch(async (error) => {"
        "\n\t\t\t\t// dsh-mobile: 荣耀 ROM 禁 link()（EACCES/EPERM/ENOTSUP/EXDEV），"
        "\n\t\t\t\t// 降级为 copyFile 复制发布（普通写路径，finalPath 经 rejectExistingLog 保证不存在）"
        "\n\t\t\t\tif (error && (error.code === \"EACCES\" || error.code === \"EPERM\" || error.code === \"ENOTSUP\" || error.code === \"EXDEV\")) {"
        "\n\t\t\t\t\tconst { copyFile } = await import(\"node:fs/promises\");"
        "\n\t\t\t\t\tawait copyFile(tmp, finalPath);"
        "\n\t\t\t\t} else {"
        "\n\t\t\t\t\tthrow error;"
        "\n\t\t\t\t}"
        "\n\t\t\t});"
    )
    if not candidates:
        raise BuildError(
            "dsh-session-persistence-jsonl link patch found no installed copy; "
            "aborting to avoid shipping an unpatched runtime"
        )
    for path in candidates:
        text = path.read_text(encoding="utf-8")
        original = text
        if old_line in text:
            text = text.replace(old_line, new_lines)
            # 防回归：替换后必须包含 copyFile 降级（历史上出现过只加注释的伪补丁，CI 仍绿）
            if "await copyFile(tmp, finalPath);" not in text:
                raise BuildError(
                    "dsh-session-persistence-jsonl link patch produced no copyFile fallback; aborting"
                )
        if text != original:
            path.write_text(text, encoding="utf-8")
        if new_lines not in text:
            raise BuildError(
                f"dsh-session-persistence-jsonl link patch is incomplete in {path}; "
                "aborting to avoid shipping a partially patched runtime"
            )


def patch_attachment_link(dsh_root: Path) -> None:
    """dsh-attachment-local 的附件发布 link() 同样被荣耀 ROM 拒绝（EACCES）。

    附件写入是 temporary -> link(target) 的 no-clobber 发布；与会话持久化
    一样补 copyFile 降级。copyFile 对已存在目标抛 EEXIST，会继续走原
    外层 catch 的完整性校验逻辑，语义不变。
    """
    candidates: list[Path] = []
    candidates.extend(
        dsh_root.glob(
            "node_modules/.pnpm/*/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js"
        )
    )
    top_level = (
        dsh_root / "node_modules" / "@deepseek-ai" / "dsh-attachment-local" / "lib" / "index.js"
    )
    if top_level.is_file():
        candidates.append(top_level)
    candidates = unique_file_candidates(candidates)

    old_line = "\t\t\tawait link(temporary, target);"
    new_lines = (
        "\t\t\tawait link(temporary, target).catch(async (error) => {"
        "\n\t\t\t\t// dsh-mobile: 荣耀 ROM 禁 link()，降级为 copyFile 复制发布（EEXIST 语义保留给外层完整性校验）"
        "\n\t\t\t\tif (error && (error.code === \"EACCES\" || error.code === \"EPERM\" || error.code === \"ENOTSUP\" || error.code === \"EXDEV\")) {"
        "\n\t\t\t\t\tconst { copyFile } = await import(\"node:fs/promises\");"
        "\n\t\t\t\t\tawait copyFile(temporary, target);"
        "\n\t\t\t\t} else {"
        "\n\t\t\t\t\tthrow error;"
        "\n\t\t\t\t}"
        "\n\t\t\t});"
    )
    if not candidates:
        raise BuildError(
            "dsh-attachment-local link patch found no installed copy; "
            "aborting to avoid shipping an unpatched runtime"
        )
    for path in candidates:
        text = path.read_text(encoding="utf-8")
        original = text
        if old_line in text:
            text = text.replace(old_line, new_lines)
            # 防回归：必须包含 copyFile 降级
            if "await copyFile(temporary, target);" not in text:
                raise BuildError("dsh-attachment-local link patch produced no copyFile fallback; aborting")
        if text != original:
            path.write_text(text, encoding="utf-8")
        if new_lines not in text:
            raise BuildError(
                f"dsh-attachment-local link patch is incomplete in {path}; "
                "aborting to avoid shipping a partially patched runtime"
            )


def patch_client_failure_display(dsh_root: Path) -> None:
    """让官方对话 UI 展示已有失败详情，而不是只显示占位文案。

    这是客户端 runtime 的显示边界补丁：它只读取 session event 中已经存在的
    message/detail/cause/code/status 字段，过滤旧的“本轮因错误终止”占位文本，
    并在进入 WebView 前做长度限制和凭据脱敏。Agent、模型请求和会话持久化逻辑
    均不变。补丁基于 rc.6 的浏览器 bundle，匹配失败时构建直接失败，避免静默
    生成未修复的官方 UI。
    """
    candidates: list[Path] = []
    candidates.extend(
        dsh_root.glob(
            "node_modules/.pnpm/*/node_modules/@deepseek-ai/dsh-client-runtime/lib/client.js"
        )
    )
    top_level = (
        dsh_root
        / "node_modules"
        / "@deepseek-ai"
        / "dsh-client-runtime"
        / "lib"
        / "client.js"
    )
    if top_level.is_file():
        candidates.append(top_level)
    candidates = unique_file_candidates(candidates)

    replacement = r'''/* dsh-client-failure-display-v2 */
		function displayFailureMessage(failure) {
			const placeholders = new Set([
				"本轮因错误终止",
				"本轮运行失败",
				"本轮因错误结束",
				"本轮以错误结束",
				"This turn failed"
			]);
			const weakMessages = new Set([
				"request failed",
				"network error",
				"fetch failed",
				"failed to fetch",
				"failure",
				"error",
				"unknown error",
				"internal server error",
				"service unavailable",
				"请求失败",
				"网络错误",
				"未知错误",
				"内部服务器错误",
				"服务不可用"
			]);
			const hidden = "[redacted]";
			const normalizedForComparison = (text) => text.replace(/[\s。.!！:：]+$/g, "").trim().toLowerCase();
			const isWeakMessage = (text) => weakMessages.has(normalizedForComparison(text));
			const read = (record, field) => {
				try {
					return record[field];
				} catch {
					return void 0;
				}
			};
			const redactHeaders = (text) => text
				.replace(/\b((?:proxy-)?authorization\s*:\s*)[^\r\n]*/gi, `$1${hidden}`)
				.replace(/\b((?:set-)?cookie\s*:\s*)[^\r\n]*/gi, `$1${hidden}`);
			const redact = (text) => text
				.replace(/\b(Bearer|Basic|Token)\s+[A-Za-z0-9+/=._~-]{4,}/gi, `$1 ${hidden}`)
				.replace(/((?:["']?(?:api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|id[-_ ]?token|token|auth(?:orization)?|cookie|credential|pass(?:word|wd)?|secret|signature)["']?)\s*[:=]\s*)(?!\[redacted\])(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)/gi, `$1${hidden}`)
				.replace(/([?&](?:api[-_]?key|key|access[-_]?token|refresh[-_]?token|id[-_]?token|token|password|pass(?:wd)?|client[-_]?secret|secret|signature|credential)=)[^&#\s]+/gi, `$1${hidden}`)
				.replace(/\b([a-z][a-z0-9+.-]*:\/\/[^\s/:@]+:)[^\s/@]+@/gi, `$1${hidden}@`)
				.replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, hidden)
				.replace(/\b(?:sk|rk|pk)-[A-Za-z0-9_-]{12,}\b/gi, hidden)
				.replace(/\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})\b/g, hidden)
				.replace(/\bAKIA[0-9A-Z]{16}\b/g, hidden)
				.replace(/\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b/g, "[private address]")
				.replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, "[email redacted]")
				.replace(/(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)/g, "[phone redacted]")
				.replace(/(?<!\d)(?:\d{6}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]|\d{15})(?!\d)/g, "[identity redacted]")
				.replace(/\b[A-Za-z]:\\Users\\[^\\\s]+/gi, "%USERPROFILE%")
				.replace(/\/(?:home|Users|root)\/[^/\s]+/g, "$HOME")
				.replace(/\/data\/(?:user(?:_de)?\/\d+|data)\/[^/\s]+(?:\/[^\s]*)?/g, "$APP_DATA")
				.replace(/\/opt\/dsh(?:\/[^\s]*)?/gi, "$DSH_HOME")
				.replace(/\/(?:tmp|var\/tmp)(?:\/[^\s]*)?/g, "$TMP")
				.replace(/\/(?:storage\/emulated\/\d+|sdcard)\/[^/\s]+(?:\/[^\s]*)?/g, "$SHARED_STORAGE");
			const clean = (value, depth) => {
				if (typeof value !== "string" || depth > 4) return null;
				// Keep tab/newline/CR until stack frames have been removed below.
				let text = value.slice(0, 4096).replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, "").replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, " ").trim();
				if (text === "") return null;
				text = text.replace(/^(?:[A-Za-z_$][\w.$-]*(?:Error|Exception)|Error)\s*:\s*/i, "").trim();
				const normalized = text.replace(/[\s。.!！:：]+$/g, "");
				if (placeholders.has(normalized)) return null;
				const structured = text.startsWith("{") || /^\[\s*(?:[{"\d]|true\b|false\b|null\b)/.test(text);
				if (structured) {
					try {
						const nested = visit(JSON.parse(text), depth + 1);
						if (nested !== null) return nested;
					} catch {}
					return null;
				}
				const lines = redactHeaders(text).split(/\r?\n/).map(line => line.trim()).filter(Boolean);
				const pythonTraceback = lines.some(line => /^Traceback \(most recent call last\):$/i.test(line));
				const useful = lines.filter(line => !/^(?:Traceback \(most recent call last\):|at\s+|File\s+".*",\s+line\s+\d+|\.\.\. \d+ more$|Suppressed:\s*|goroutine\s+\d+|\d+:\s+0x[\da-f]+)/i.test(line));
				const meaningful = pythonTraceback ? useful.slice(-1) : useful.slice(0, 2);
				if (meaningful.length === 0) return null;
				text = meaningful.join(" ").replace(/\s+at\s+(?:new\s+)?[\w$.[\]<>]+\s*\([^)]*\.(?:[cm]?[jt]sx?|java|kt|py):\d+(?::\d+)?\).*$/i, "");
				text = redact(text).replace(/\s+/g, " ").trim();
				const cleaned = text.replace(/[\s。.!！:：]+$/g, "");
				if (text === "" || text === "[object Object]" || placeholders.has(cleaned)) return null;
				return [...text].length > 240 ? `${[...text].slice(0, 239).join("")}…` : text;
			};
			const seen = new Set();
			const visit = (value, depth) => {
				if (value === null || depth > 4) return null;
				if (typeof value === "string") return clean(value, depth);
				if (typeof value !== "object" || seen.has(value)) return null;
				seen.add(value);
				if (Array.isArray(value)) {
					for (const item of value) {
						const nested = visit(item, depth + 1);
						if (nested !== null) return nested;
					}
					return null;
				}
				const record = value;
				const code = read(record, "code");
				if (typeof code === "string" && /^INVALID_API_KEY$/i.test(code.trim())) return "API key is invalid";
				let weakMessage = null;
				for (const field of ["message", "detail", "description", "error_description"]) {
					const message = clean(read(record, field), depth + 1);
					if (message === null) continue;
					if (!isWeakMessage(message)) return message;
					weakMessage ??= message;
				}
				for (const field of ["error", "failure", "cause", "details"]) {
					const nested = visit(read(record, field), depth + 1);
					if (nested !== null) return nested;
				}
				if (typeof code === "string" && /^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$/.test(code) && code !== "UNKNOWN") return `Error code ${code}`;
				const status = read(record, "status");
				if (Number.isInteger(status) && status >= 100 && status <= 599) return `HTTP ${status}`;
				if (weakMessage !== null) return weakMessage;
				return null;
			};
			return visit(failure, 0) ?? "Failure details unavailable";
		}'''
    boundary = "\n\t\t//#endregion"

    def require_exact_replacement(text: str, path: Path) -> None:
        if text.count(CLIENT_FAILURE_DISPLAY_MARKER) != 1:
            raise BuildError(f"client failure display marker must occur exactly once in {path}")
        start = text.find(f"/* {CLIENT_FAILURE_DISPLAY_MARKER} */")
        end = text.find(boundary, start)
        if start < 0 or end < 0 or text[start:end] != replacement:
            raise BuildError(f"client failure display marker is incomplete in {path}")

    if not candidates:
        raise BuildError(
            "dsh-client-runtime failure display patch found no installed copy; "
            "aborting to avoid shipping a generic-error-only UI"
        )
    for path in candidates:
        text = path.read_text(encoding="utf-8")
        if CLIENT_FAILURE_DISPLAY_MARKER in text:
            require_exact_replacement(text, path)
            continue
        marker = "function displayFailureMessage(failure) {"
        start = text.find(marker)
        if start < 0:
            raise BuildError(f"client failure display function is missing in {path}")
        end = text.find(boundary, start)
        if end < 0:
            raise BuildError(f"client failure display function boundary is missing in {path}")
        text = text[:start] + replacement + text[end:]
        require_exact_replacement(text, path)
        path.write_text(text, encoding="utf-8")


def patch_client_mobile_settings_layout(dsh_root: Path) -> None:
    """Keep the official settings dialog usable in a narrow WebView.

    The official settings component uses a desktop 188px navigation rail. On a
    390px WebView that leaves too little room for plugin and Agent preset text.
    This CSS-only boundary patch changes the rail into a horizontally scrollable
    top tab row below 600px; it does not change plugin, Agent, model, or settings
    data logic.
    """
    candidates: list[Path] = []
    candidates.extend(
        dsh_root.glob(
            "node_modules/.pnpm/*/"
            "node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js",
        )
    )
    top_level = (
        dsh_root
        / "node_modules"
        / "@deepseek-ai"
        / "dsh-client-ui-settings-general"
        / "lib"
        / "client.js"
    )
    if top_level.is_file():
        candidates.append(top_level)
    candidates = unique_file_candidates(candidates)

    mobile_css = (
        f"/* {MOBILE_SETTINGS_LAYOUT_MARKER} */"
        "@media (max-width:600px){"
        ".VOzbGW_panel{width:calc(100vw - 24px);max-width:none;"
        "height:calc(100vh - 24px);border-radius:16px;flex-direction:column}"
        ".VOzbGW_nav{width:100%;height:auto;gap:8px;padding:14px 12px 0}"
        ".VOzbGW_navTitle{padding:0 4px}"
        ".VOzbGW_navList{width:100%;flex-direction:row;gap:4px;overflow-x:auto;"
        "padding-bottom:4px;scrollbar-width:none}"
        ".VOzbGW_navList::-webkit-scrollbar{display:none}"
        ".VOzbGW_navCell{flex:1 0 auto;height:36px;justify-content:center;"
        "gap:4px;padding:7px 6px;font-size:13px}"
        ".VOzbGW_navLabel{flex:0 1 auto}"
        ".VOzbGW_content{width:100%;min-height:0}"
        ".VOzbGW_header{height:44px;padding:8px 14px}"
        ".VOzbGW_options{padding:0 16px 16px}"
        "}"
    )
    encoded_css = json.dumps(mobile_css, ensure_ascii=True)[1:-1]

    def require_exact_mobile_css(text: str, path: Path) -> None:
        if text.count(MOBILE_SETTINGS_LAYOUT_MARKER) != 1 or text.count(encoded_css) != 1:
            raise BuildError(f"mobile settings layout marker is incomplete in {path}")

    if not candidates:
        raise BuildError(
            "dsh-client-ui-settings-general mobile layout patch found no installed copy; "
            "aborting to avoid shipping a desktop-only settings dialog"
        )
    for path in candidates:
        text = path.read_text(encoding="utf-8")
        if MOBILE_SETTINGS_LAYOUT_MARKER in text:
            require_exact_mobile_css(text, path)
            continue
        marker = 'const css$3 = "'
        start = text.find(marker)
        if start < 0:
            raise BuildError(f"settings CSS declaration is missing in {path}")
        end = text.find('";', start + len(marker))
        if end < 0:
            raise BuildError(f"settings CSS declaration boundary is missing in {path}")
        text = text[:end] + encoded_css + text[end:]
        require_exact_mobile_css(text, path)
        path.write_text(text, encoding="utf-8")


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
    parser.add_argument(
        "--rootfs-url",
        default=None,
        help="HTTPS URL written to the manifest for remote installs; omitted for embedded-only bundles",
    )
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
    rootfs_url = args.rootfs_url or f"https://bundled.invalid/runtime/rootfs{COMPRESSION_OUTPUT_SUFFIXES[args.compression]}"
    try:
        parsed_rootfs_url = urlsplit(rootfs_url)
        rootfs_port = parsed_rootfs_url.port
    except ValueError as error:
        raise BuildError("rootfs URL is invalid") from error
    if (
        len(rootfs_url) > 2048
        or parsed_rootfs_url.scheme.lower() != "https"
        or not parsed_rootfs_url.hostname
        or parsed_rootfs_url.username is not None
        or parsed_rootfs_url.password is not None
        or parsed_rootfs_url.fragment
        or (rootfs_port is not None and not 1 <= rootfs_port <= 65535)
        or any(ord(character) < 32 or ord(character) == 127 for character in rootfs_url)
    ):
        raise BuildError("rootfs URL must be a bounded HTTPS URL without credentials or fragments")
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
            profile_bundles = (
                tuple(mobile_spec["dsh"]["profile"]["bundles"])
                if mobile_spec is not None
                else PROFILE_BUNDLE_NAMES
            )
            inject_bundles_into_dsh_manifest(args.dsh_root, profile_bundles)
            patch_client_failure_display(args.dsh_root)
            patch_client_mobile_settings_layout(args.dsh_root)
            patch_dsh_app_boot(args.dsh_root)
            patch_session_persistence(args.dsh_root)
            patch_attachment_link(args.dsh_root)
            add_windows_tree(writer, args.dsh_root, "opt/dsh")
            profile_links = add_profiles_module_fallback(writer, args.dsh_root, "opt/dsh")
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
                "url": rootfs_url,
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
            "profileLinks": profile_links,
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
