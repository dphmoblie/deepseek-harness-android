#!/usr/bin/env python3
"""把 harness-web 构建产物替换进现有 rootfs.bundle 的 dsh 前端 dist，并重算 manifest。

原理：dsh 的 distIndex 由 require.resolve('@deepseek-ai/dsh-web-frontend/dist/index.html')
解析到 .pnpm 下的真实目录，流式重写 tar 时跳过旧 dist 条目、追加新 dist 树即可，
无需改动 dsh 代码。全程不解压到磁盘；替换期间使用 .bak 事务备份，
校验成功后立即删除，避免 Android AAPT 把备份也打进 APK。
"""

from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import os
import stat
import tarfile
from pathlib import Path

# 复用构建脚本的 writer/校验逻辑（文件名带连字符，按路径加载）
_ber_spec = importlib.util.spec_from_file_location(
    "build_embedded_runtime", Path(__file__).with_name("build-embedded-runtime.py")
)
if _ber_spec is None or _ber_spec.loader is None:
    raise SystemExit("无法加载 build-embedded-runtime.py")
_ber = importlib.util.module_from_spec(_ber_spec)
_ber_spec.loader.exec_module(_ber)
BuildError = _ber.BuildError
RootfsWriter = _ber.RootfsWriter
add_windows_tree = _ber.add_windows_tree
sha256_file = _ber.sha256_file

DIST_MARKER = "/dsh-web-frontend/dist"
DIST_INDEX_SUFFIX = f"{DIST_MARKER}/index.html"


def is_frontend_dist_path(name: str) -> bool:
    """路径是否落在 dsh 前端 dist 树内（含 dist 目录条目本身）。"""
    return name.endswith(DIST_MARKER) or f"{DIST_MARKER}/" in name


def is_replaced_path(name: str, replacements: list[tuple[Path, str]]) -> bool:
    """路径是否命中任一 --replace-file 的归档目标（文件本身或其子树）。"""
    return any(name == target or name.startswith(f"{target}/") for _, target in replacements)


def verify_bundle(bundle: Path, expected_sha256: str) -> None:
    """重建前校验 bundle 与 manifest 记录一致，防止对已替换产物二次操作。"""
    print(f"[verify] 计算 {bundle.name} 的 SHA-256…")
    actual = sha256_file(bundle)
    if actual != expected_sha256:
        raise BuildError(f"bundle SHA-256 与 manifest 不一致：{actual}")


def stream_rebuild(bundle: Path, output: Path, dist_root: Path, replacements: list[tuple[Path, str]]) -> dict:
    """流式复制 bundle，跳过旧前端 dist 与替换目标条目，末尾追加新 dist 树与替换文件。"""
    skipped_count = 0
    skipped_bytes = 0
    copied_count = 0
    dist_index_name: str | None = None
    added_bytes = 0
    existing_directories: set[str] = set()
    copied_paths: set[str] = set()
    deduplicated_count = 0

    with bundle.open("rb") as raw_input:
        with gzip.open(raw_input, "rb") as compressed_input:
            with tarfile.open(fileobj=compressed_input, mode="r|") as source:
                with output.open("xb") as raw_output:
                    with gzip.GzipFile(
                        filename="",
                        mode="wb",
                        compresslevel=ARGS.compression_level,
                        fileobj=raw_output,
                        mtime=0,
                    ) as compressed_output:
                        with tarfile.open(
                            fileobj=compressed_output, mode="w|", format=tarfile.PAX_FORMAT
                        ) as target:
                            for member in source:
                                if (
                                    member.isreg()
                                    and member.name.endswith(DIST_INDEX_SUFFIX)
                                    and dist_index_name is None
                                ):
                                    dist_index_name = member.name
                                if is_frontend_dist_path(member.name) or is_replaced_path(member.name, replacements):
                                    if member.isreg():
                                        skipped_bytes += member.size
                                    skipped_count += 1
                                    continue
                                if member.islnk() or member.issym():
                                    if is_frontend_dist_path(member.linkname) or is_replaced_path(
                                        member.linkname, replacements
                                    ):
                                        raise BuildError(
                                            f"链接指向被替换的条目：{member.name} -> {member.linkname}"
                                        )
                                # 输入 bundle 可能已含重复条目（历史脚本 bug 产物）：
                                # 复制时按规范化路径去重，保证输出归档干净。
                                normalized_name = _ber.normalized_path(member.name)
                                if normalized_name in copied_paths:
                                    deduplicated_count += 1
                                    if member.isreg():
                                        skipped_bytes += member.size
                                    continue
                                copied_paths.add(normalized_name)
                                if member.isreg():
                                    file_object = source.extractfile(member)
                                    target.addfile(member, file_object)
                                else:
                                    target.addfile(member)
                                if member.isdir():
                                    existing_directories.add(normalized_name)
                                copied_count += 1

                            if dist_index_name is None:
                                raise BuildError("bundle 中未找到 dsh-web-frontend/dist/index.html")
                            new_dist_root = dist_index_name[: -len("index.html")].rstrip("/")

                            writer = RootfsWriter(target, 0)
                            # 旧条目中已保留父目录链（如 opt/dsh/...、usr/local/lib）：
                            # 预注入避免追加新树时重复写目录条目（重复条目会被
                            # SafeRootfsExtractor 以 ARCHIVE_DUPLICATE_ENTRY 拒绝）。
                            writer.preseed_directories(existing_directories)
                            before_bytes = writer.extracted_bytes
                            add_windows_tree(writer, dist_root, new_dist_root)
                            for local_path, archive_target in replacements:
                                if local_path.is_dir():
                                    add_windows_tree(writer, local_path, archive_target)
                                else:
                                    writer.add_bytes(
                                        archive_target,
                                        _ber.read_support_file(local_path, str(local_path)),
                                        0o644,
                                    )
                            added_bytes = writer.extracted_bytes - before_bytes

    return {
        "skippedCount": skipped_count,
        "skippedBytes": skipped_bytes,
        "copiedCount": copied_count,
        "deduplicatedCount": deduplicated_count,
        "addedEntries": 0,  # 由 collect_expected 阶段补充
        "addedBytes": added_bytes,
        "distRoot": new_dist_root,
    }


def collect_expected_files(source_root: Path, archive_root: str, label: str) -> dict[str, bytes]:
    """收集待写入树（dist 或目录替换）的相对路径与内容（用于重建后校验）。"""
    expected: dict[str, bytes] = {}
    for current_raw, directory_names, file_names in os.walk(source_root, topdown=True, followlinks=False):
        current = Path(current_raw)
        relative = current.relative_to(source_root)
        archive_parent = (
            archive_root if relative == Path(".") else f"{archive_root}/{relative.as_posix()}"
        )
        for name in file_names:
            path = current / name
            if path.is_symlink():
                continue
            if not stat.S_ISREG(path.stat().st_mode):
                continue
            archive_name = f"{archive_parent}/{name}"
            expected[archive_name] = path.read_bytes()
    if not expected:
        raise BuildError(f"{label} 目录为空")
    return expected


def collect_expected(
    dist_root: Path,
    archive_dist_root: str,
    replacements: list[tuple[Path, str]],
) -> dict[str, bytes]:
    """新 dist 与全部替换文件的归档路径 → 内容映射。"""
    expected = collect_expected_files(dist_root, archive_dist_root, "新 dist")
    if not any(name.endswith("index.html") for name in expected):
        raise BuildError("新 dist 目录缺少 index.html")
    for local_path, archive_target in replacements:
        if local_path.is_dir():
            expected.update(collect_expected_files(local_path, archive_target, str(local_path)))
        else:
            expected[archive_target] = local_path.read_bytes()
    return expected


def verify_rebuilt(bundle: Path, expected: dict[str, bytes]) -> None:
    """流式遍历新 bundle，校验新写入条目完整、旧 dist 条目已清除、全局无重复路径。"""
    print(f"[verify] 校验重建后的 {bundle.name}…")
    found = 0
    seen_paths: set[str] = set()
    with tarfile.open(bundle, "r|gz") as source:
        for member in source:
            normalized = _ber.normalized_path(member.name)
            if normalized in seen_paths:
                raise BuildError(f"重建后归档包含重复条目：{normalized}")
            seen_paths.add(normalized)
            wanted = expected.pop(member.name, None)
            if wanted is None:
                # 新写入树的目录条目不在 expected 中，允许；内容条目残留才算失败
                if is_frontend_dist_path(member.name) and not member.isdir():
                    raise BuildError(f"重建后仍残留旧 dist 条目：{member.name}")
                continue
            if not member.isreg():
                raise BuildError(f"重建后条目类型异常：{member.name}")
            if source.extractfile(member).read() != wanted:
                raise BuildError(f"重建后内容不一致：{member.name}")
            found += 1
    if expected:
        missing = ", ".join(sorted(expected))
        raise BuildError(f"新写入条目缺失：{missing}")
    print(f"[verify] 新写入共 {found} 个条目校验通过")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path, help="现有 rootfs.bundle 路径（原地替换）")
    parser.add_argument("--manifest", required=True, type=Path, help="现有 runtime-manifest.json 路径（原地替换）")
    parser.add_argument("--dist", required=True, type=Path, help="harness-web/dist 构建产物目录")
    parser.add_argument("--runtime-version", required=True, help="替换后的运行时版本号（如 2026.08.4）")
    parser.add_argument(
        "--replace-file",
        action="append",
        default=[],
        metavar="LOCAL=ARCHIVE",
        help="额外替换条目（可重复）：本地文件/目录写入指定归档路径，如 "
        "scripts/mobile-auth-preload.cjs=usr/local/lib/dsh-mobile-auth.cjs",
    )
    parser.add_argument("--compression-level", type=int, choices=range(1, 10), default=6)
    return parser.parse_args()


def parse_replacements(raw_entries: list[str]) -> list[tuple[Path, str]]:
    """解析 LOCAL=ARCHIVE 替换项并校验归档路径合法性。"""
    replacements: list[tuple[Path, str]] = []
    for entry in raw_entries:
        local_raw, separator, archive_raw = entry.partition("=")
        if not separator or not local_raw or not archive_raw:
            raise BuildError(f"--replace-file 格式应为 LOCAL=ARCHIVE：{entry!r}")
        local_path = Path(local_raw)
        if not local_path.exists():
            raise BuildError(f"--replace-file 本地路径不存在：{local_path}")
        archive_target = _ber.normalized_path(archive_raw)
        replacements.append((local_path, archive_target))
    return replacements


def main() -> None:
    global ARGS
    ARGS = parse_arguments()
    bundle = ARGS.bundle
    manifest_path = ARGS.manifest
    dist_root = ARGS.dist

    if not bundle.is_file():
        raise BuildError("bundle 不存在")
    if not manifest_path.is_file():
        raise BuildError("manifest 不存在")
    if not (dist_root / "index.html").is_file():
        raise BuildError("dist 目录缺少 index.html（先运行 pnpm build）")
    replacements = parse_replacements(ARGS.replace_file)

    manifest = json.loads(manifest_path.read_bytes())
    if manifest.get("schemaVersion") != 1:
        raise BuildError("manifest schemaVersion 不是 1")
    if manifest["rootfs"]["compression"] != "gzip":
        raise BuildError("仅支持 gzip 压缩的 bundle")
    old_extracted_bytes = manifest["rootfs"]["extractedBytes"]
    verify_bundle(bundle, manifest["rootfs"]["sha256"])

    temporary_output = bundle.with_name(f"{bundle.name}.{os.getpid()}.part")
    temporary_manifest = manifest_path.with_name(f"{manifest_path.name}.{os.getpid()}.part")
    backup_bundle = bundle.with_name(f"{bundle.name}.bak")
    backup_manifest = manifest_path.with_name(f"{manifest_path.name}.bak")
    bundle_backed_up = False
    manifest_backed_up = False
    committed = False

    try:
        print(f"[rebuild] 流式重建（跳过旧 dist，追加新 dist，gzip 级别 {ARGS.compression_level}）…")
        stats = stream_rebuild(bundle, temporary_output, dist_root, replacements)
        expected = collect_expected(dist_root, stats["distRoot"], replacements)
        stats["addedEntries"] = len(expected)

        compressed_bytes = temporary_output.stat().st_size
        archive_sha256 = sha256_file(temporary_output)
        manifest["version"] = ARGS.runtime_version
        manifest["rootfs"]["sha256"] = archive_sha256
        manifest["rootfs"]["compressedBytes"] = compressed_bytes
        manifest["rootfs"]["extractedBytes"] = old_extracted_bytes - stats["skippedBytes"] + stats["addedBytes"]

        manifest_bytes = (json.dumps(manifest, ensure_ascii=True, indent=2) + "\n").encode("ascii")
        with temporary_manifest.open("xb") as manifest_output:
            manifest_output.write(manifest_bytes)
            manifest_output.flush()
            os.fsync(manifest_output.fileno())

        # 原子替换：校验新文件期间用 .bak 保留旧文件，异常时立即回滚。
        os.replace(bundle, backup_bundle)
        bundle_backed_up = True
        os.replace(temporary_output, bundle)
        os.replace(manifest_path, backup_manifest)
        manifest_backed_up = True
        os.replace(temporary_manifest, manifest_path)

        verify_rebuilt(bundle, expected)
        committed = True

        # 备份只能在校验通过后删除。assets 下残留的任意 .bak 都会被 AAPT 打包，
        # 使 APK 同时包含新旧两份 rootfs。
        backup_bundle.unlink(missing_ok=True)
        backup_manifest.unlink(missing_ok=True)

        print(
            json.dumps(
                {
                    "bundle": str(bundle),
                    "manifest": str(manifest_path),
                    "transactionBackupsRemoved": True,
                    "runtimeVersion": ARGS.runtime_version,
                    "compressedBytes": compressed_bytes,
                    "extractedBytes": manifest["rootfs"]["extractedBytes"],
                    "sha256": archive_sha256,
                    "skippedOldDistEntries": stats["skippedCount"],
                    "skippedOldDistBytes": stats["skippedBytes"],
                    "deduplicatedOldEntries": stats["deduplicatedCount"],
                    "addedNewDistEntries": stats["addedEntries"],
                    "addedNewDistBytes": stats["addedBytes"],
                },
                sort_keys=True,
                indent=2,
            )
        )
    except Exception:
        if not committed:
            if manifest_backed_up and backup_manifest.exists():
                manifest_path.unlink(missing_ok=True)
                os.replace(backup_manifest, manifest_path)
            if bundle_backed_up and backup_bundle.exists():
                bundle.unlink(missing_ok=True)
                os.replace(backup_bundle, bundle)
        raise
    finally:
        temporary_output.unlink(missing_ok=True)
        temporary_manifest.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except (BuildError, OSError, tarfile.TarError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
