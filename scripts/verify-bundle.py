#!/usr/bin/env python3
"""CI-side faithful simulation of SafeRootfsExtractor's rejection rules.

Runs against a freshly built bundle + manifest and fails with the exact
offending entry, so packaging never ships an archive the app would reject.
"""
from __future__ import annotations

import argparse
import json
import posixpath
import sys
from pathlib import PurePosixPath

MAX_ENTRIES = 250_000
MAX_PATH_CHARS = 4_096
MAX_COMPONENT_CHARS = 255


def normalized(raw: str) -> str:
    return raw.removesuffix("/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()

    manifest = json.loads(open(args.manifest, encoding="utf-8").read())
    expected_extracted = manifest["rootfs"]["extractedBytes"]

    seen: set[str] = set()
    types: dict[str, str] = {}
    symlinks: list[tuple[str, str]] = []
    hardlinks: list[tuple[str, str]] = []
    entry_count = 0
    extracted = 0
    fail = lambda msg: (_ for _ in ()).throw(SystemExit(f"BUNDLE_VERIFY_FAILED: {msg}"))

    import tarfile
    with tarfile.open(args.bundle, "r:gz") as t:
        for m in t:
            entry_count += 1
            if entry_count > MAX_ENTRIES:
                fail("entry count exceeds the app limit (250000)")
            name = normalized(m.name)
            if not name or name.startswith(("/", "\\")) or "\\" in name:
                fail(f"absolute/backslash path: {m.name!r}")
            parts = name.split("/")
            if any(p in (".", "..") or not p or len(p) > MAX_COMPONENT_CHARS for p in parts) or len(name) > MAX_PATH_CHARS:
                fail(f"invalid path component: {m.name!r}")
            if name in seen:
                fail(f"duplicate entry: {name!r}")
            seen.add(name)
            if m.isdir():
                types[name] = "dir"
                continue
            if m.isreg():
                types[name] = "file"
                if m.size < 0:
                    fail(f"negative-size file: {name!r}")
                extracted += m.size
            elif m.issym():
                if m.size != 0:
                    fail(f"symlink with unexpected data: {name!r}")
                types[name] = "sym"
                symlinks.append((name, m.linkname))
            elif m.islnk():
                types[name] = "hard"
                hardlinks.append((name, m.linkname))
            elif m.isdev() or m.ischr() or m.isblk() or m.isfifo():
                fail(f"device node rejected: {name!r}")
            else:
                fail(f"unsupported entry type {m.type!r}: {name!r}")

    if extracted != expected_extracted:
        fail(f"extracted size mismatch: {extracted} != {expected_extracted}")

    # 文件-目录冲突（提取器 ensureDirectory 规则：父路径被非目录条目占用）
    for name, kind in list(types.items()):
        p = str(PurePosixPath(name).parent)
        while p and p != ".":
            if p in types and types[p] != "dir":
                fail(f"path conflict: parent of {name!r} is occupied by {types[p]} entry {p!r}")
            p = str(PurePosixPath(p).parent)

    # 符号链接：目标解析不越界（绝对目标落在 root 内；相对目标折叠后不越界）
    def resolve_link(name: str, target: str) -> bool:
        parent = PurePosixPath(name).parent
        base = PurePosixPath("/") if target.startswith("/") else parent
        rel = target.lstrip("/")
        stack: list[str] = []
        for part in (str(base) + "/" + rel).split("/"):
            if part in ("", ".", "/"):
                continue
            if part == "..":
                if not stack:
                    return False
                stack.pop()
            else:
                stack.append(part)
        return bool(stack)

    for name, target in symlinks:
        if not resolve_link(name, target):
            fail(f"symlink escapes root: {name!r} -> {target!r}")
        if name in seen and any(n == name for n, _ in symlinks[:symlinks.index((name, target))]):
            fail(f"duplicate symlink path: {name!r}")
    # 符号链接延后创建冲突（提取器：目录/文件先写，symlink 创建时路径已存在则 ARCHIVE_DUPLICATE_ENTRY）
    for name, _ in symlinks:
        others = [n for n, t in types.items() if t != "sym" and n == name]
        if others:
            fail(f"symlink path occupied by non-symlink entry: {name!r}")
    for name, _ in hardlinks:
        others = [n for n, t in types.items() if t != "hard" and n == name]
        if others:
            fail(f"hardlink path occupied by non-hardlink entry: {name!r}")

    # 硬链接：目标存在、不是目录/链接、无环
    for name, target in hardlinks:
        t = normalized(target)
        if t not in seen:
            fail(f"hardlink target missing: {name!r} -> {target!r}")

    # 与 App 侧 RootfsIntegrity.REQUIRED_LINKS 保持一致：
    # 关键符号链接必须存在且目标解析一致（缺失/损坏会在安装后报 ROOTFS_LINKS_CORRUPTED）。
    required_links = [
        ("bin", "usr/bin"),
        ("lib", "usr/lib"),
        ("sbin", "usr/sbin"),
        ("usr/bin/sh", "dash"),
        ("etc/mtab", "../proc/self/mounts"),
        ("etc/os-release", "../usr/lib/os-release"),
        ("etc/localtime", "/usr/share/zoneinfo/Etc/UTC"),
        ("usr/local/bin/node", "../../../opt/node/bin/node"),
    ]

    def canonical_target(name: str, target: str) -> str:
        base = "/" if target.startswith("/") else "/" + posixpath.dirname(name)
        return posixpath.normpath(posixpath.join(base, target))

    for name, expected in required_links:
        if types.get(name) != "sym":
            fail(f"required symlink missing or not a symlink: {name!r}")
        actual = next(target for n, target in symlinks if n == name)
        if canonical_target(name, actual) != canonical_target(name, expected):
            fail(f"required symlink target mismatch: {name!r} -> {actual!r} (expected {expected!r})")

    # profiles 扁平模块回退：dsh 启动时 cordis 从 profile 目录解析 loader entry，
    # 必须能在 $DSH_HOME/profiles/node_modules 找到全部 profile bundles。
    # （构建期由 add_profiles_module_fallback 生成，缺失会导致 Cannot find package）
    profile_bundle_names = [
        "dsh-mobile-compat",
        "dshmarket",
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "@linxin666/dsh-web-ui-all",
        "@liustack/modlens",
    ]
    for package_name in profile_bundle_names:
        link_name = f"root/.dsh/profiles/node_modules/{package_name}"
        if types.get(link_name) != "sym":
            fail(f"profiles module fallback missing for bundle: {package_name!r}")
        actual = next(target for n, target in symlinks if n == link_name)
        resolved = canonical_target(link_name, actual).lstrip("/")
        if resolved not in types:
            fail(f"profiles link target missing in bundle: {link_name!r} -> {actual!r}")

    print(f"BUNDLE_VERIFY_OK: entries={entry_count} extracted={extracted} symlinks={len(symlinks)} hardlinks={len(hardlinks)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
