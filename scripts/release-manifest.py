#!/usr/bin/env python3
"""生成版本清单（release-manifest.json）。

把「这次发布的到底是什么」写成一份机器可读文件：应用版本、发布标签与类型、提交号，
以及每个资产的 SHA-256 与字节数。用户和后续排查不必解压 300MB 的 APK 就能核对版本
与摘要，也便于把线上包与源码提交对应起来。

清单本身也要被 checksums.txt 登记（发布前复核要求下载目录与清单完全一致），因此必须
先于 checksums.txt 生成。

用法：
  python3 scripts/release-manifest.py \
    --version 0.2.13 --tag v0.2.13-mobile-42 --release-type preview \
    --commit <提交号> --apk /tmp/apk/app-release.apk \
    --bundle /tmp/runtime/rootfs.bundle \
    --runtime-manifest /tmp/runtime/runtime-manifest.json \
    --out /tmp/release-manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = 1
BUFFER_SIZE = 1024 * 1024
COMMIT_PATTERN = re.compile(r"^[0-9a-fA-F]{7,40}$")


def fail(message: str) -> None:
    raise SystemExit(f"RELEASE_MANIFEST_FAILED: {message}")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(path: Path) -> Path:
    if not path.is_file():
        fail(f"缺少要登记的资产：{path}")
    return path


def asset_entry(path: Path) -> dict[str, object]:
    """登记一个资产的名称、摘要与字节数（名称取文件名，与发布资产同名）。"""
    require_file(path)
    return {
        "name": path.name,
        "sha256": sha256_of(path),
        "bytes": path.stat().st_size,
    }


def build_manifest(
    version: str,
    tag: str,
    release_type: str,
    commit: str,
    apk: Path,
    bundle: Path,
    runtime_manifest: Path,
) -> dict[str, object]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "appVersion": version,
        "releaseTag": tag,
        "releaseType": release_type,
        "commit": commit,
        "assets": [
            asset_entry(apk),
            asset_entry(bundle),
            asset_entry(runtime_manifest),
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="生成版本清单 release-manifest.json")
    parser.add_argument("--version", required=True, help="应用版本号（与 versionName 一致）")
    parser.add_argument("--tag", required=True, help="发布标签，如 v0.2.13-mobile-42")
    parser.add_argument("--release-type", required=True, help="发布类型：preview 或 stable")
    parser.add_argument("--commit", required=True, help="发布对应的提交号")
    parser.add_argument("--apk", required=True, help="APK 路径")
    parser.add_argument("--bundle", required=True, help="rootfs.bundle 路径")
    parser.add_argument("--runtime-manifest", required=True, help="runtime-manifest.json 路径")
    parser.add_argument("--out", required=True, help="清单输出路径")
    args = parser.parse_args()

    if not COMMIT_PATTERN.match(args.commit):
        fail(f"提交号不合法（应为 7 到 40 位十六进制）：{args.commit!r}")
    for label, value in (
        ("应用版本", args.version),
        ("发布标签", args.tag),
        ("发布类型", args.release_type),
    ):
        if not value.strip():
            fail(f"{label}不能为空")

    manifest = build_manifest(
        version=args.version.strip(),
        tag=args.tag.strip(),
        release_type=args.release_type.strip(),
        commit=args.commit.lower(),
        apk=Path(args.apk),
        bundle=Path(args.bundle),
        runtime_manifest=Path(args.runtime_manifest),
    )

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # 固定缩进与换行，保证同一批输入产出的文件逐字节可复现。
    out.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    assets = manifest["assets"]
    assert isinstance(assets, list)
    total_bytes = sum(int(entry["bytes"]) for entry in assets)  # type: ignore[index]
    print(f"RELEASE_MANIFEST_OK: assets={len(assets)} bytes={total_bytes} manifest={out}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except OSError as error:
        fail(str(error))
