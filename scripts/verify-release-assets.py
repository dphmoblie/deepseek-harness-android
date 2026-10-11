#!/usr/bin/env python3
"""核对已发布 Release 的资产与 checksums.txt 是否一致。

发布作业把四个资产上传到 GitHub Release：APK、rootfs bundle、runtime manifest
和上传当场生成的 checksums.txt。上传之后这些字节就不再受本仓库控制，因此唯一
能证明「用户拿到的就是本次构建产物」的办法是把它们重新下载回来重算摘要：

- 上传被截断（半截文件）→ 摘要不符；
- 目录里多出第二份 bundle 或 .bak → 出现未登记文件；
- 漏传 manifest → 缺少被登记的文件；
- checksums.txt 自身写坏 → 行格式或重复登记。

任何一条命中都以非零退出码失败，并把中文原因打到标准错误；成功时打印一行
`RELEASE_ASSETS_OK:` 供 CI 日志检索。

用法：
  python3 scripts/verify-release-assets.py --dir /tmp/release-verify \
    --expect app-release.apk,rootfs.bundle,runtime-manifest.json
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

BUFFER_SIZE = 1024 * 1024
CHECKSUMS_NAME = "checksums.txt"
# GNU coreutils 的两种写法：`<摘要>  <文件名>` 与 `<摘要> *<文件名>`（二进制模式）。
# 发布作业自己写的是前者，这里两种都接受，避免别的工具生成的清单被误判。
LINE_PATTERN = re.compile(r"^([0-9a-fA-F]{64}) ([ *])(.+)$")


def fail(message: str) -> None:
    raise SystemExit(f"RELEASE_VERIFY_FAILED: {message}")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(BUFFER_SIZE)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def parse_checksums(path: Path) -> dict[str, str]:
    if not path.is_file():
        fail(f"缺少 {CHECKSUMS_NAME}：{path}")
    raw = path.read_text(encoding="utf-8", errors="replace")
    entries: dict[str, str] = {}
    for number, line in enumerate(raw.splitlines(), start=1):
        if not line.strip():
            continue
        match = LINE_PATTERN.match(line)
        if match is None:
            fail(f"{CHECKSUMS_NAME} 第 {number} 行格式不正确：{line!r}")
        digest, _, name = match.groups()
        if name != Path(name).name or name in {".", ".."}:
            fail(f"{CHECKSUMS_NAME} 第 {number} 行的文件名非法：{name!r}")
        if name == CHECKSUMS_NAME:
            fail(f"{CHECKSUMS_NAME} 不能登记自己（第 {number} 行）")
        if name in entries:
            fail(f"{CHECKSUMS_NAME} 重复登记了 {name!r}（第 {number} 行）")
        entries[name] = digest.lower()
    if not entries:
        fail(f"{CHECKSUMS_NAME} 里没有任何资产登记项")
    return entries


def verify_file(directory: Path, name: str, expected: str) -> int:
    path = directory / name
    if path.is_symlink() or not path.is_file():
        fail(f"缺少被登记的文件：{name}")
    size = path.stat().st_size
    if size == 0:
        fail(f"被登记的文件是空文件：{name}")
    actual = sha256_of(path)
    if actual != expected:
        fail(f"{name} 摘要不一致：清单 {expected}，实际 {actual}（{size} 字节）")
    return size


def main() -> int:
    parser = argparse.ArgumentParser(description="核对发布资产与 checksums.txt")
    parser.add_argument("--dir", type=Path, required=True, help="已下载的发布资产目录")
    parser.add_argument("--checksums", type=Path, help=f"清单路径，默认 <dir>/{CHECKSUMS_NAME}")
    parser.add_argument("--expect", default="", help="必须登记的资产名，逗号分隔且集合必须完全一致")
    args = parser.parse_args()

    directory: Path = args.dir
    if not directory.is_dir():
        fail(f"资产目录不存在或不是目录：{directory}")
    checksums = args.checksums if args.checksums is not None else directory / CHECKSUMS_NAME
    entries = parse_checksums(checksums)

    # 逐件核对摘要；顺带拿到字节数，作为「上传完整」的第二重证据。
    total = 0
    for name in sorted(entries):
        total += verify_file(directory, name, entries[name])

    # 目录里不允许出现未登记的文件：多一份 bundle、一个 .bak、一次半截下载，
    # 都说明发布的资产集与清单描述的不是同一批东西。
    checksums_path = checksums.resolve()
    present = {entry.name for entry in directory.iterdir() if entry.resolve() != checksums_path}
    extras = sorted(present - set(entries))
    if extras:
        fail("出现了未登记的文件：" + "、".join(extras))

    if args.expect:
        wanted = {item.strip() for item in args.expect.split(",") if item.strip()}
        missing = sorted(wanted - set(entries))
        unexpected = sorted(set(entries) - wanted)
        if missing:
            fail("清单缺少必需资产：" + "、".join(missing))
        if unexpected:
            fail("清单多出非必需资产：" + "、".join(unexpected))

    print(f"RELEASE_ASSETS_OK: files={len(entries)} bytes={total} checksums={checksums.name}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except OSError as error:
        fail(str(error))
