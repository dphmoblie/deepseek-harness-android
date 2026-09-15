#!/usr/bin/env python3
"""网络工具组件组的法律材料收集与校验。

项目既有的 APK 法律材料机制是**手工登记**：`android/app/build.gradle` 的
`legalAssetSources` 把 `LICENSE`、`THIRD_PARTY_NOTICES.md`、`legal/licenses/*.txt`
以及 node_modules 里的许可证登记成 `assets/legal/…`，任一来源缺失或为空即终止构建。
该机制对「从 Ubuntu 包里取二进制」的组件不适用 —— runner 上不会存在这些源码文件，
所以这里补上**收集步骤**：在与镜像同发行版的 arm64 runner 上，从每个包的
`/usr/share/doc/<pkg>/copyright` 取发行版自带的版权与许可证原文，落到
`android/app/src/main/assets/legal/ubuntu-packages/`（默认目标，即 APK 的
`assets/legal/ubuntu-packages/`）。

用法::

    # CI（build-rootfs 作业，arm64 runner）：收集（并当场自校验）
    python3 scripts/legal-notices.py collect \
        --dest android/app/src/main/assets/legal/ubuntu-packages \
        --packages /tmp/network-tools-packages.txt
    # CI（build 作业）与本地发布前：校验资产齐备（缺一件即失败）
    python3 scripts/legal-notices.py verify \
        --dest android/app/src/main/assets/legal/ubuntu-packages

注意：GPL 组件的「提供对应源码」义务不靠这个脚本完成，口径见
`docs/RELEASE_CHECKLIST.md` 的既有表述（不得只给上游链接）。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path
from typing import NamedTuple


DEFAULT_DEST = Path("android/app/src/main/assets/legal/ubuntu-packages")
DOC_ROOT = Path("/usr/share/doc")
# 版权文件最小长度：Debian 机器可读版权文件不会这么短，过短说明取错了文件。
MIN_COPYRIGHT_BYTES = 512
COPYRIGHT_SUFFIX = "-copyright.txt"
# git 是 GPL-2.0，APK 里必须同时有未删节的 GPL-2.0 全文（既有资产，不允许另起一份）。
GPL2_TEXT_ASSET = Path("legal/licenses/proot-GPL-2.0.txt")


class Component(NamedTuple):
    """一个需要法律材料的组件：候选包名 + 许可证标识 + 版权文件里必须出现的标记。"""

    key: str
    candidates: tuple[str, ...]
    pattern: re.Pattern[str]
    license: str
    markers: tuple[str, ...]
    required: bool


# markers 为「任一命中」：Ubuntu 的版权文件是机器可读格式，短名（如 GPL-2 / MPL-2.0）
# 一定出现在 License 字段里；openssh 这类逐文件授权的包只用宽松标记。
COMPONENTS: tuple[Component, ...] = (
    Component(
        key="git",
        candidates=("git",),
        pattern=re.compile(r"^git$"),
        license="GPL-2.0",
        markers=("GPL-2", "GNU General Public License"),
        required=True,
    ),
    Component(
        key="curl",
        candidates=("curl",),
        pattern=re.compile(r"^curl$"),
        license="curl（MIT 式）",
        markers=("curl",),
        required=True,
    ),
    Component(
        key="libcurl",
        candidates=("libcurl4t64", "libcurl4", "libcurl3t64-gnutls", "libcurl3-gnutls"),
        pattern=re.compile(r"^libcurl[0-9a-z.-]*$"),
        license="curl（MIT 式）",
        markers=("curl",),
        required=True,
    ),
    Component(
        key="libexpat1",
        candidates=("libexpat1",),
        pattern=re.compile(r"^libexpat1$"),
        license="MIT",
        markers=("MIT", "Expat"),
        required=True,
    ),
    Component(
        key="ca-certificates",
        candidates=("ca-certificates",),
        pattern=re.compile(r"^ca-certificates$"),
        license="MPL-2.0",
        markers=("MPL-2.0", "MPL"),
        required=True,
    ),
    Component(
        key="openssh-client",
        candidates=("openssh-client",),
        pattern=re.compile(r"^openssh-client$"),
        license="BSD 类（逐文件）",
        markers=("BSD", "permissive", "OpenSSH"),
        required=False,
    ),
)


def fail(message: str) -> None:
    raise SystemExit(f"LEGAL_NOTICES_FAILED: {message}")


def read_package_list(path: Path | None) -> list[str]:
    """读取预置脚本写出的包清单（每行一个包名）。"""
    if path is None:
        return []
    if not path.is_file():
        fail(f"包清单不存在: {path}")
    packages: list[str] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        name = line.strip()
        if name and not name.startswith("#") and name not in packages:
            packages.append(name)
    return packages


def collected_asset(dest: Path, component: Component) -> Path | None:
    """在目标目录里找出该组件已收集的版权文件（按包名匹配，不做模糊前缀）。"""
    if not dest.is_dir():
        return None
    for path in sorted(dest.glob(f"*{COPYRIGHT_SUFFIX}")):
        package = path.name[: -len(COPYRIGHT_SUFFIX)]
        if component.pattern.fullmatch(package):
            return path
    return None


def check_asset(component: Component, path: Path) -> list[str]:
    """校验单个已收集文件：非空、够长、含预期许可证标记。"""
    problems: list[str] = []
    if not path.is_file() or path.stat().st_size < MIN_COPYRIGHT_BYTES:
        problems.append(
            f"{component.key}: {path.name} 缺失或过短（< {MIN_COPYRIGHT_BYTES} 字节）"
        )
        return problems
    content = path.read_text(encoding="utf-8", errors="replace")
    if not any(marker in content for marker in component.markers):
        problems.append(
            f"{component.key}: {path.name} 未出现预期许可证标记 {component.markers!r}"
        )
    return problems


def verify(dest: Path, verbose: bool = True) -> int:
    """校验必需组件的版权文件齐备；可选组件「存在即必须合格」。"""
    problems: list[str] = []
    for component in COMPONENTS:
        path = collected_asset(dest, component)
        if path is None:
            if component.required:
                problems.append(
                    f"{component.key}: 缺少 {component.key} 的版权文件"
                    f"（collect 应从 {DOC_ROOT}/<pkg>/copyright 收集）"
                )
            elif verbose:
                print(f"note: 可选组件 {component.key} 未收集，跳过")
            continue
        problems.extend(check_asset(component, path))
    # git 是 GPL-2.0：APK 里必须有未删节的 GPL-2.0 全文（复用既有资产，不另起一份）。
    gpl_text = Path(__file__).resolve().parent.parent / GPL2_TEXT_ASSET
    if not gpl_text.is_file() or gpl_text.stat().st_size < 10_000:
        problems.append(f"缺少未删节的 GPL-2.0 全文资产: {GPL2_TEXT_ASSET}")
    else:
        text = gpl_text.read_text(encoding="utf-8", errors="replace")
        if "GNU GENERAL PUBLIC LICENSE" not in text or "Version 2" not in text:
            problems.append(f"{GPL2_TEXT_ASSET} 不是 GPL-2.0 全文")
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        fail(f"{len(problems)} 项法律材料不齐备（dest={dest}）")
    if verbose:
        print(f"LEGAL_NOTICES_OK: dest={dest} components={len(COMPONENTS)}")
    return 0


def collect(dest: Path, packages_file: Path | None) -> int:
    """从 runner 的 /usr/share/doc/<pkg>/copyright 收集到目标目录，并当场自校验。"""
    dest.mkdir(parents=True, exist_ok=True)
    listed = read_package_list(packages_file)
    collected: list[str] = []
    missing_copyright: list[str] = []
    missing_components: list[str] = []
    missing_optional_components: list[str] = []

    def take(package: str) -> bool:
        """收集单个包；返回是否收集到（包名重复时幂等）。"""
        source = DOC_ROOT / package / "copyright"
        if not source.is_file():
            if package not in missing_copyright:
                missing_copyright.append(package)
            return False
        shutil.copyfile(source, dest / f"{package}{COPYRIGHT_SUFFIX}")
        if package not in collected:
            collected.append(package)
        return True

    # 1) 组件：候选包名是「任一命中」——例如 libcurl 只会有一种 flavour 在场。
    for component in COMPONENTS:
        satisfied = any(take(name) for name in component.candidates)
        if satisfied:
            continue
        if component.required:
            missing_components.append(component.key)
        else:
            missing_optional_components.append(component.key)

    # 2) 预置脚本列出的其余包（含 ldd 带进来的传递依赖库）。
    for name in listed:
        if name in collected or name in missing_copyright:
            continue
        if any(component.pattern.fullmatch(name) for component in COMPONENTS):
            continue  # 组件候选已在第 1 步处理
        if not take(name):
            # 传递依赖库缺版权文件不拦构建，但必须留在日志里可见（不静默）。
            print(f"warn: {name} 没有 {DOC_ROOT}/{name}/copyright，未收集")

    if missing_components:
        fail("以下必需组件没有发行版版权文件: " + ", ".join(sorted(missing_components)))
    if missing_optional_components:
        print("note: 可选组件未收集: " + ", ".join(sorted(missing_optional_components)))

    inventory = {
        "schemaVersion": 1,
        "source": f"{DOC_ROOT}/<pkg>/copyright（与镜像同发行版的构建 runner）",
        "collected": sorted(collected),
        "missingCopyright": sorted(missing_copyright),
        "requiredComponents": {
            component.key: component.license
            for component in COMPONENTS
            if component.required
        },
        "optionalComponents": {
            component.key: component.license
            for component in COMPONENTS
            if not component.required
        },
        "correspondingSource": (
            "GPL 组件的对应源码口径见 docs/RELEASE_CHECKLIST.md："
            "必须按许可证允许的方式提供确切二进制的完整对应源码（含构建与安装脚本），"
            "只给上游仓库链接不构成对应源码要约。"
        ),
        "gplTextAsset": GPL2_TEXT_ASSET.as_posix(),
    }
    (dest / "inventory.json").write_text(
        json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"collected {len(collected)} copyright files -> {dest}")
    return verify(dest)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("collect", "verify"))
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    parser.add_argument(
        "--packages",
        type=Path,
        default=None,
        help="collect: 预置脚本写出的包清单（每行一个包名），用于覆盖传递依赖库",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    if args.action == "collect":
        return collect(args.dest, args.packages)
    if args.packages is not None:
        fail("verify 不接受 --packages")
    return verify(args.dest)


if __name__ == "__main__":
    sys.exit(main())
