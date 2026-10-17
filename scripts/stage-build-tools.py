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
from pathlib import Path


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
            for wanted_package in PACKAGES
        )
    ]
    # 头文件和 GCC 后端可能来自带版本后缀的依赖包（例如 gcc-13、g++-13），
    # 不能只查询 build-essential 这个元包。
    for package in selected:
        listing = run("dpkg-query", "-L", package, capture=True)
        result.update(Path(line.strip()) for line in listing.splitlines() if line.startswith("/"))
    for root in (Path("/usr/bin"), Path("/usr/include"), Path("/usr/lib/gcc"), Path("/usr/libexec/gcc")):
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


def map_path(path: Path) -> Path:
    # Ubuntu 24.04 使用 usr-merge，/lib 下的库必须以 /usr/lib 形式落盘。
    if path == Path("/lib") or path.as_posix().startswith("/lib/"):
        return Path("/usr/lib") / path.relative_to("/lib")
    if path == Path("/bin") or path.as_posix().startswith("/bin/"):
        return Path("/usr/bin") / path.relative_to("/bin")
    if path == Path("/sbin") or path.as_posix().startswith("/sbin/"):
        return Path("/usr/sbin") / path.relative_to("/sbin")
    return path


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


def copy_entry(source: Path, destination: Path) -> None:
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
    staged: list[Path] = []
    for source in sorted(candidates):
        mapped = map_path(source)
        if mapped.as_posix() in base:
            continue
        target = args.destination / mapped.relative_to("/")
        copy_entry(source, target)
        staged.append(mapped)

    missing = [path for path in ENTRYPOINTS if not (args.destination / map_path(Path(path)).relative_to("/")).exists()]
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
