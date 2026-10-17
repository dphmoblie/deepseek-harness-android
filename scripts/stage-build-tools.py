#!/usr/bin/env python3
"""在 ARM64 Ubuntu runner 上预置 C 编译工具与 make。

该脚本只在 CI 构建机执行，不在 Android 访客内运行 apt。输出目录是
build-embedded-runtime.py 的增量目录，后续会与 ubuntu-base 合并。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tarfile
from pathlib import Path, PurePosixPath


PACKAGES = (
    "build-essential",
    "gcc",
    "g++",
    "make",
    "binutils",
    "libc6-dev",
    "libstdc++-13-dev",
)
ENTRYPOINTS = (
    "/usr/bin/cc",
    "/usr/bin/c++",
    "/usr/bin/gcc",
    "/usr/bin/g++",
    "/usr/bin/make",
    "/usr/bin/as",
    "/usr/bin/ld",
    "/usr/bin/ar",
    "/usr/bin/nm",
    "/usr/bin/objcopy",
    "/usr/bin/ranlib",
    "/usr/bin/strip",
)
SKIP_PARTS = {"doc", "man", "info", "locale"}
# 只查清单、不进 apt 安装列表：内核头文件（<linux/...>）由 linux-libc-dev 提供，
# 名字和 libc6-dev 不沾亲，靠前缀匹配抓不到，编译原生扩展时却常用。
EXTRA_PACKAGES = ("linux-libc-dev",)


def run(*args: str, capture: bool = False) -> str:
    result = subprocess.run(args, check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def baseline_paths(archive: Path) -> set[str]:
    with tarfile.open(archive, "r:gz") as source:
        return {
            "/" + name.removeprefix("./").removesuffix("/")
            for name in source.getnames()
            if name not in {".", "./"}
        }


def package_paths() -> set[Path]:
    result: set[Path] = set()
    installed = run("dpkg-query", "-W", "-f=${binary:Package}\n", capture=True).splitlines()
    selected = [
        package for package in installed
        if any(
            package == wanted_package
            or package.startswith(wanted_package + ":")
            or package.startswith(wanted_package + "-")
            for wanted_package in PACKAGES + EXTRA_PACKAGES
        )
    ]
    # 头文件和 GCC 后端可能来自带版本后缀的依赖包（例如 gcc-13、g++-13），
    # 不能只查询 build-essential 这个元包。
    for package in selected:
        listing = run("dpkg-query", "-L", package, capture=True)
        result.update(Path(line.strip()) for line in listing.splitlines() if line.startswith("/"))
    # 只扫编译器自己的后端目录。/usr/include 与 /usr/bin 曾经整体 rglob，构建机镜像上
    # 装了成片的 -dev 包，一进来就是几百兆无关头文件（run 39043573082 预置树 714MB）。
    for root in (Path("/usr/lib/gcc"), Path("/usr/libexec/gcc")):
        if root.exists():
            result.update(path for path in root.rglob("*") if path.is_file() or path.is_symlink())
    return result


def wanted(path: Path) -> bool:
    text = path.as_posix()
    if not (path.is_file() or path.is_symlink()):
        return False
    if any(part in SKIP_PARTS for part in path.parts):
        return False
    return (
        text in ENTRYPOINTS
        or text.startswith(("/usr/include/", "/usr/aarch64-linux-gnu/include/"))
        or text.startswith(("/usr/lib/gcc/", "/usr/libexec/gcc/"))
        or text.startswith("/usr/lib/aarch64-linux-gnu/")
        or text.startswith("/usr/bin/") and (
            Path(text).name in {Path(p).name for p in ENTRYPOINTS}
            or Path(text).name.startswith(("gcc-", "g++-", "cpp-"))
        )
    )


def map_posix(text: str) -> str:
    """usr-merge 归并：/lib、/bin、/sbin 一律落到 /usr 下。"""
    for prefix, replacement in (("/lib", "/usr/lib"), ("/bin", "/usr/bin"), ("/sbin", "/usr/sbin")):
        if text == prefix or text.startswith(prefix + "/"):
            return replacement + text[len(prefix):]
    return text


def map_path(path: Path) -> Path:
    return Path(map_posix(path.as_posix()))


def normalize_link_posix(parent: str, raw: str) -> str:
    """把链接文本（可相对）折算成预置树里的绝对路径（已做 usr-merge 归并）。"""
    joined = PurePosixPath(raw) if raw.startswith("/") else PurePosixPath(parent) / raw
    parts: list[str] = []
    for part in joined.parts:
        if part in ("", ".", "/"):
            continue
        if part == "..":
            if parts:
                parts.pop()
            continue
        parts.append(part)
    return map_posix("/" + "/".join(parts))


def link_is_self_contained(source: Path, planned: set[str]) -> bool:
    """链接的**字面终点**是否也在预置树里。

    /usr/bin/cc -> gcc-13 算（同一目录的兄弟文件会一起落盘）；
    /usr/bin/ld -> /etc/alternatives/ld 不算——访客里没有 /etc/alternatives，
    这种链接必须按内容落盘，否则 /usr/bin/ld 在访客里是断链，链接期直接找不到 ld。
    指向自己的链接（usr-merge 之后 /lib/... 与 /usr/lib/... 折叠成同一路径）同样不算。
    """
    target = normalize_link_posix(source.parent.as_posix(), os.readlink(source))
    return target in planned and target != map_path(source).as_posix()


def ldd_paths(binary: Path) -> set[Path]:
    try:
        output = subprocess.run(("ldd", str(binary)), check=False, text=True, capture_output=True).stdout
    except OSError:
        return set()
    paths: set[Path] = set()
    for line in output.splitlines():
        match = re.search(r"(?:=>\s*)?(\/[^\s(]+)", line)
        if match:
            candidate = Path(match.group(1))
            if candidate.exists() or candidate.is_symlink():
                paths.add(candidate)
    return paths


def resolve_link(source: Path) -> Path | None:
    """把符号链接解析成最终目标；断链返回 None。"""
    target = Path(os.readlink(source))
    if not target.is_absolute():
        target = source.parent / target
    if not (target.exists() or target.is_symlink()):
        return None
    try:
        return target.resolve()
    except OSError:
        return None


def active_gcc_major() -> str | None:
    """入口真正指向的 GCC 主版本（13、14 …）。镜像上通常同时装着好几套。"""
    for entry in ("/usr/bin/cc", "/usr/bin/gcc", "/usr/bin/c++", "/usr/bin/g++", "/usr/bin/cpp"):
        path = Path(entry)
        if not (path.exists() or path.is_symlink()):
            continue
        resolved = resolve_link(path) if path.is_symlink() else path
        if resolved is None:
            continue
        match = re.search(r"^(?:gcc|g\+\+|cpp|c\+\+|cc)-(\d+)(?:\.\d+)*$", resolved.name)
        if match:
            return match.group(1)
    # 入口是硬链接或真实二进制时，退回问编译器自己版本。
    try:
        output = subprocess.run(("cc", "--version"), check=False, text=True, capture_output=True).stdout
    except OSError:
        return None
    match = re.search(r"\b(\d+)\.\d+\.\d+\b", output)
    return match.group(1) if match else None


# 同一套工具链在不同主版本下各有一份：/usr/lib/gcc/<triple>/13、/usr/include/c++/13、
# /usr/bin/gcc-13 …。只保留入口真正指向的那一版，其余整棵跳过。
VERSION_SCOPED = (
    re.compile(r"^/usr/lib/gcc/[^/]+/(\d+)(?:/|$)"),
    re.compile(r"^/usr/libexec/gcc/[^/]+/(\d+)(?:/|$)"),
    re.compile(r"^/usr/include/c\+\+/(\d+)(?:\.\d+)*(?:/|$)"),
    re.compile(r"^/usr/bin/[a-z0-9_-]*?(?:gcc|g\+\+|cpp|c\+\+|cc)-(\d+)(?:\.\d+)*$"),
)


def is_foreign_version(text: str, keep: str | None) -> bool:
    if keep is None:
        return False
    for pattern in VERSION_SCOPED:
        match = pattern.match(text)
        if match is not None and match.group(1) != keep:
            return True
    return False


def directory_sizes(root: Path, depth: int = 3, limit: int = 8) -> list[tuple[str, int]]:
    """按前几级目录汇总体积，超限时能一眼看出是谁胖。"""
    totals: dict[str, int] = {}
    for current, _directories, files in os.walk(root):
        relative = Path(current).relative_to(root)
        key = "/".join(relative.parts[:depth]) or "."
        for name in files:
            try:
                size = os.lstat(Path(current) / name).st_size
            except OSError:
                continue
            totals[key] = totals.get(key, 0) + size
    return sorted(totals.items(), key=lambda item: item[1], reverse=True)[:limit]


def copy_entry(source: Path, destination: Path, planned: set[str] | None = None) -> None:
    if source.is_dir() and not source.is_symlink():
        # 目录由子项自己落盘。目录目标曾被当成文件交给 shutil.copy2，
        # /usr/lib/llvm-16/lib/clang/16/include 这种链接目标一进来就 IsADirectoryError。
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_symlink() or destination.is_file():
        destination.unlink()
    elif destination.is_dir():
        shutil.rmtree(destination)
    if source.is_symlink():
        if planned is not None and not link_is_self_contained(source, planned):
            target = resolve_link(source)
            if target is not None and target.is_file():
                # 终点不会进访客（/etc/alternatives 之类）：按内容落盘，别留断链。
                shutil.copy2(target, destination, follow_symlinks=False)
                return
        destination.symlink_to(os.readlink(source))
    else:
        shutil.copy2(source, destination, follow_symlinks=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    parser.add_argument("base_archive", type=Path)
    parser.add_argument("package_list", type=Path)
    args = parser.parse_args()

    run("sudo", "apt-get", "update", "-qq")
    run("sudo", "apt-get", "install", "-y", "-qq", "--no-install-recommends", *PACKAGES)
    base = baseline_paths(args.base_archive)
    candidates = {path for path in package_paths() if wanted(path)}
    pending = list(candidates)
    seen: set[Path] = set()
    while pending:
        source = pending.pop()
        if source in seen or not (source.exists() or source.is_symlink()):
            continue
        seen.add(source)
        if source.is_symlink():
            target = resolve_link(source)
            # 只跟文件目标。目录目标（例如 /usr/lib/llvm-16/lib/clang/16/include）
            # 编译用不到，把整棵目录拉进来只会撑大预置树；链接本身仍然按链接落盘。
            if target is not None and target.is_file():
                pending.append(target)
        mapped = map_path(source)
        if mapped.as_posix() not in base:
            candidates.add(source)
        if source.is_file() and (
            os.access(source, os.X_OK)
            or ".so" in source.name
            or source.parent.as_posix().startswith(("/usr/lib", "/usr/libexec"))
        ):
            for dependency in ldd_paths(source):
                if dependency not in seen:
                    candidates.add(dependency)
                    pending.append(dependency)

    args.destination.mkdir(parents=True, exist_ok=True)
    # 只留入口真正指向的那一套 GCC：构建机镜像里常同时装着 12/13/14，
    # 每套后端加 C++ 头都是上百兆，装进来访客也用不上。
    keep_version = active_gcc_major()
    selected = [
        path
        for path in candidates
        if map_path(path).as_posix() not in base
        and not is_foreign_version(map_path(path).as_posix(), keep_version)
    ]
    # 预置树里会出现的路径集合：链接只在这些终点上保留链接形态，其余按内容落盘。
    planned = {map_path(path).as_posix() for path in selected}
    staged: list[Path] = []
    for source in sorted(selected):
        mapped = map_path(source)
        target = args.destination / mapped.relative_to("/")
        copy_entry(source, target, planned)
        staged.append(mapped)

    totals = directory_sizes(args.destination)
    if totals:
        print(f"预置树体积: {sum(size for _name, size in totals) / 1048576:.1f} MB（打包上限 320 MB）")
        for name, size in totals:
            print(f"  {size / 1048576:8.1f} MB  {name}")

    missing = []
    for path in ENTRYPOINTS:
        candidate = args.destination / map_path(Path(path)).relative_to("/")
        if candidate.exists():
            continue
        if candidate.is_symlink():
            missing.append(f"{path}（链接终点 {os.readlink(candidate)} 不在预置树里）")
        else:
            missing.append(path)
    if missing:
        raise SystemExit(f"构建工具入口缺失: {', '.join(missing)}")

    # 入口在树里不等于能编译：头文件、C++ 标准库头与 crt 启动对象也必须有着落
    # （要么本次预置进来，要么基线镜像里本来就有）。这一步挡在生成 300MB 资产之前。
    required = (
        "/usr/include/stdio.h",
        "/usr/include/c++",
        "/usr/lib/aarch64-linux-gnu/crt1.o",
        "/usr/lib/aarch64-linux-gnu/libstdc++.so.6",
    )
    absent = [
        path
        for path in required
        if map_path(Path(path)).as_posix() not in base
        and not (args.destination / map_path(Path(path)).relative_to("/")).exists()
    ]
    if absent:
        raise SystemExit(f"构建工具链不完整，缺少: {', '.join(absent)}")
    args.package_list.parent.mkdir(parents=True, exist_ok=True)
    providers: set[str] = set()
    for path in staged:
        result = subprocess.run(("dpkg", "-S", path.as_posix()), check=False, text=True, capture_output=True)
        for line in result.stdout.splitlines():
            providers.add(line.split(":", 1)[0].strip())
    args.package_list.write_text("\n".join(sorted(providers)) + "\n", encoding="utf-8")
    print(f"构建工具预置完成: 文件={len(staged)} 包={len(providers)} 目录={args.destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
