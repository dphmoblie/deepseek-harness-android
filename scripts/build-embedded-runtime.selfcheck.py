#!/usr/bin/env python3
"""Self-check for build-embedded-runtime.py (no rootfs inputs required).

覆盖三块**本机可测**的逻辑，完整 rootfs 仍需 CI 的 arm64 runner：

1. mobile profile 校验与 rootfs 路径/权限规范化（原有内容）；
2. 网络工具组件组：内建子命令的 0755 口径、git-core 硬链接去重、
   强制常规文件的入口、CA 条目下限与预置树体积兜底；
3. sourcemap 裁剪：只删 `*.map`，构建与重建两侧的计数与「应写入清单」一致；
4. verify-bundle.py 的新校验：git/curl/git-remote-https 的存在 + 0755 + ARM64 ELF、
   git-core 全目录 0755、/etc/ssl/certs 的 CA 数量下限（含正例与反例）。
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from types import SimpleNamespace

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
# 与 build-embedded-runtime.NETWORK_TOOLS_MIN_CA_FILES / verify-bundle.MIN_CA_CERTIFICATE_FILES 对齐
CA_FLOOR = 64


def assert_node_syntax(path: Path) -> None:
    node = shutil.which("node")
    if node is None:
        raise RuntimeError("node is required for compiled client bundle self-checks")
    result = subprocess.run(
        [node, "--check", str(path)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"patched client bundle failed node --check: {path}\n{result.stderr}"
        )


def load_module() -> object:
    spec = importlib.util.spec_from_file_location(
        "build_embedded_runtime", SCRIPTS / "build-embedded-runtime.py"
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("unable to load build-embedded-runtime.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_sibling(name: str, filename: str) -> object:
    """按文件名加载同级脚本（文件名带连字符，不能用 import 语句）。"""
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def arm64_elf(machine: int = 183) -> bytes:
    """最小 ELF 头：校验只看 class/type/machine，不看程序行为。"""
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    header[16:20] = bytes([3, 0, machine, 0])
    return bytes(header)


def write_network_tools_fixture(
    tree: Path,
    *,
    ca_files: int = CA_FLOOR,
    with_ssh: bool = True,
) -> None:
    """造一棵与 stage-network-tools.sh 输出同构的预置树。"""
    payloads = {
        "usr/bin/curl": arm64_elf(),
        "usr/lib/git-core/git": arm64_elf(),
        "usr/lib/git-core/git-remote-http": arm64_elf(),
        "usr/lib/aarch64-linux-gnu/libcurl.so.4.8.0": b"libcurl",
        "usr/share/ca-certificates/mozilla/Example.crt": b"cert",
        "usr/share/git-core/templates/description": b"template",
        "etc/ssl/certs/ca-certificates.crt": b"bundle",
    }
    if with_ssh:
        payloads["usr/bin/ssh"] = arm64_elf()
    for index in range(ca_files):
        payloads[f"etc/ssl/certs/ca{index:04d}.pem"] = b"cert"
    for name, payload in payloads.items():
        path = tree / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
    # 上游把 /usr/bin/git 与 git-core 的 git 硬链接在一起，git-add 也共享同一个
    # inode：预置树必须保留这个关系，同时 /usr/bin/git 仍要在归档里落成真实常规文件。
    os.link(tree / "usr/lib/git-core/git", tree / "usr/bin/git")
    os.link(tree / "usr/lib/git-core/git", tree / "usr/lib/git-core/git-add")
    # 上游把 git-remote-https 做成指向 git-remote-http 的相对软链：软链也必须落成
    # 真实常规文件（verify-bundle.py 的口径），否则真机 CI 会在校验阶段才暴露。
    os.symlink("git-remote-http", tree / "usr/lib/git-core/git-remote-https")


def check_network_tools(module: object) -> None:
    with tempfile.TemporaryDirectory(prefix="dsh-network-tools-") as directory:
        root = Path(directory)
        tree = root / "network-tools"
        write_network_tools_fixture(tree)
        # 体积口径必须是 du 语义：usr/bin/git、git-core/git、git-core/git-add 三个名字
        # 共享一个 inode，按名字累加会多算两份，git-core 的真实规模会被算成数百 MB。
        naive_bytes = sum(
            path.stat().st_size
            for path in tree.rglob("*")
            if path.is_file() and not path.is_symlink()
        )
        unique_bytes = module.shared_tree_bytes(tree)
        assert naive_bytes - unique_bytes == 128, (unique_bytes, naive_bytes)
        archive = root / "network-tools.tar"
        with module.tarfile.open(archive, "w") as target:
            writer = module.RootfsWriter(target, 0)
            module.add_network_tools(writer, tree)
        with module.tarfile.open(archive, "r") as source:
            members = {name: source.getmember(name) for name in source.getnames()}

        # 执行位口径：usr/bin 与 usr/lib/git-core 下的常规文件一律 0755。
        for name in (
            "usr/bin/git",
            "usr/bin/curl",
            "usr/bin/ssh",
            "usr/lib/git-core/git",
            "usr/lib/git-core/git-remote-https",
        ):
            member = members[name]
            assert member.isreg(), f"{name} 必须是真实常规文件"
            assert member.mode == 0o755, f"{name} 的执行位不对: {member.mode:o}"
            assert member.size == 64, f"{name} 载荷为空: {member.size}"
        # 入口即使与 git-core 共 inode，也不能被去重成硬链接条目。
        assert members["usr/bin/git"].size == 64
        # git-core 内同一个 inode 的重复条目必须去重成硬链接，且指向已存在的目标。
        git_member = members["usr/lib/git-core/git"]
        git_add_member = members["usr/lib/git-core/git-add"]
        assert git_member.isreg() != git_add_member.isreg(), "git-core 内应恰好保留一份真实载荷"
        linked = git_add_member if git_add_member.islnk() else git_member
        target = git_member if git_add_member.islnk() else git_add_member
        assert linked.linkname == target.name
        assert linked.size == 0
        # 共享库与 CA/模板数据保持 0644。
        for name in (
            "usr/lib/aarch64-linux-gnu/libcurl.so.4.8.0",
            "usr/share/ca-certificates/mozilla/Example.crt",
            "usr/share/git-core/templates/description",
            "etc/ssl/certs/ca0000.pem",
            "etc/ssl/certs/ca-certificates.crt",
        ):
            assert members[name].mode == 0o644, f"{name} 不应带执行位"
        # 组件元数据落在 guest 里，便于真机核对装了什么。
        metadata = members["etc/deepseek-harness-network-tools.json"]
        assert metadata.isreg() and metadata.mode == 0o644
        with module.tarfile.open(archive, "r") as source:
            content = json.loads(source.extractfile(metadata).read().decode("ascii"))
        assert "ca-certificates" in content["components"] and content["caEntries"] >= CA_FLOOR

        # 反例 1：缺必需文件
        broken = root / "missing-curl"
        write_network_tools_fixture(broken)
        (broken / "usr/bin/curl").unlink()
        expect_build_error(module, lambda: add_network_tools_to_archive(module, root, "missing.tar", broken), "缺少 curl")

        # 反例 2：CA 条目低于下限
        thin_ca = root / "thin-ca"
        write_network_tools_fixture(thin_ca, ca_files=3)
        expect_build_error(module, lambda: add_network_tools_to_archive(module, root, "thin-ca.tar", thin_ca), "CA 条目不足")

        # 反例 3：预置树异常膨胀（硬链接丢失的场景），体积上限必须当场拦住
        original_limit = module.NETWORK_TOOLS_MAX_TREE_BYTES
        module.NETWORK_TOOLS_MAX_TREE_BYTES = 16
        try:
            expect_build_error(
                module,
                lambda: add_network_tools_to_archive(module, root, "oversized.tar", tree),
                "预置树体积超限",
            )
        finally:
            module.NETWORK_TOOLS_MAX_TREE_BYTES = original_limit


def add_network_tools_to_archive(module: object, root: Path, name: str, tree: Path) -> None:
    archive = root / name
    archive.unlink(missing_ok=True)
    with module.tarfile.open(archive, "w") as target:
        module.add_network_tools(module.RootfsWriter(target, 0), tree)


def expect_build_error(module: object, action, label: str) -> None:
    try:
        action()
    except module.BuildError:
        return
    raise AssertionError(f"该用例应当失败但没有: {label}")


def check_sourcemap_trimming(module: object, rebuild: object) -> None:
    # 判定函数本身：只按 .map 后缀，不动 .js/.json
    for trimmed in ("a.map", "a.js.map", "dir/a.css.map"):
        assert module.is_trimmed_sourcemap(trimmed), trimmed
    for kept in ("a.js", "a.json", "map", "a.map.js"):
        assert not module.is_trimmed_sourcemap(kept), kept

    with tempfile.TemporaryDirectory(prefix="dsh-sourcemaps-") as directory:
        root = Path(directory)
        tree = root / "runtime"
        payloads = {
            "lib/index.js": b"module.exports = 1\n",
            "lib/index.js.map": b"m" * 2048,
            "lib/data.json": b"{}\n",
            "lib/nested/style.css": b"a{}\n",
            "lib/nested/style.css.map": b"n" * 1024,
        }
        for name, payload in payloads.items():
            path = tree / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
        archive = root / "runtime.tar"
        with module.tarfile.open(archive, "w") as target:
            writer = module.RootfsWriter(target, 0)
            module.add_windows_tree(writer, tree, "opt/dsh")
            assert writer.trimmed_sourcemaps == 2, writer.trimmed_sourcemaps
            assert writer.trimmed_sourcemap_bytes == 2048 + 1024
        with module.tarfile.open(archive, "r") as source:
            names = set(source.getnames())
        assert "opt/dsh/lib/index.js" in names and "opt/dsh/lib/data.json" in names
        assert "opt/dsh/lib/nested/style.css" in names
        assert not [name for name in names if name.endswith(".map")]

        # 重建脚本的「应写入清单」必须同样跳过 .map，否则会误报条目缺失
        dist = root / "dist"
        (dist / "assets").mkdir(parents=True)
        (dist / "index.html").write_bytes(b'<div id="root"></div>')
        (dist / "assets/app.js").write_bytes(b"console.log(1)\n")
        (dist / "assets/app.js.map").write_bytes(b"z" * 4096)
        expected = rebuild.collect_expected_files(dist, "opt/dsh/dist", "测试 dist")
        assert "opt/dsh/dist/assets/app.js" in expected
        assert "opt/dsh/dist/assets/app.js.map" not in expected

        # 原地重建旧 bundle：输入流里的 sourcemap 也要被剔除并计入统计
        dist_path = (
            "opt/dsh/node_modules/.pnpm/frontend/node_modules/"
            "@deepseek-ai/dsh-web-frontend/dist/index.html"
        )
        source_bundle = root / "old.bundle"
        with module.tarfile.open(source_bundle, "w:gz") as bundle:
            for name, payload, mode in (
                ("opt/dsh/lib/a.js", b"a", 0o644),
                ("opt/dsh/lib/a.js.map", b"m" * 512, 0o644),
                (dist_path, b"old frontend", 0o644),
                (rebuild.RUNTIME_METADATA_PATH, json.dumps({"runtimeVersion": "old"}).encode(), 0o644),
            ):
                info = module.tarfile.TarInfo(name)
                info.size = len(payload)
                info.mode = mode
                bundle.addfile(info, module.io.BytesIO(payload))
        rebuilt = root / "rebuilt.bundle"
        rebuild.ARGS = SimpleNamespace(compression_level=1)
        stats = rebuild.stream_rebuild(source_bundle, rebuilt, dist, [], "new")
        assert stats["trimmedSourcemapFiles"] == 1
        assert stats["trimmedSourcemapBytes"] == 512
        with module.tarfile.open(rebuilt, "r:gz") as bundle:
            rebuilt_names = set(bundle.getnames())
        assert "opt/dsh/lib/a.js" in rebuilt_names
        assert "opt/dsh/lib/a.js.map" not in rebuilt_names
        # 重建校验必须接受已裁剪的结果（残留 .map 会被 verify_rebuilt 直接拒绝）
        rebuild.verify_rebuilt(
            rebuilt,
            rebuild.collect_expected(dist, stats["distRoot"], []),
            {},
            "new",
        )


def build_verify_fixture(
    root: Path,
    *,
    ca_files: int,
    network: bool,
    include_git: bool = True,
    git_mode: int = 0o755,
    git_machine: int = 183,
    bad_git_subcommand_mode: bool = False,
    with_ssh: bool = False,
) -> tuple[Path, Path]:
    """造一个刚好能走到 verify-bundle 新校验的合成 bundle + manifest。"""
    elf = arm64_elf()
    files: dict[str, tuple[bytes, int]] = {
        "usr/bin/bash": (elf, 0o755),
        "opt/dsh/node_modules/@vscode/ripgrep-linux-arm64/bin/rg": (elf, 0o755),
        "opt/dsh/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run": (elf, 0o755),
        "etc/ssl/certs/ca-certificates.crt": (b"# bundle\n" * 8, 0o644),
    }
    if network:
        if include_git:
            files["usr/bin/git"] = (arm64_elf(git_machine), git_mode)
        files["usr/bin/curl"] = (elf, 0o755)
        files["usr/lib/git-core/git"] = (elf, 0o755)
        files["usr/lib/git-core/git-remote-https"] = (elf, 0o755)
        if bad_git_subcommand_mode:
            files["usr/lib/git-core/git-sh-setup"] = (b"#!/bin/sh\n", 0o644)
    if with_ssh:
        files["usr/bin/ssh"] = (elf, 0o755)
    for index in range(ca_files):
        files[f"etc/ssl/certs/ca{index:04d}.pem"] = (b"-----BEGIN CERTIFICATE-----\n", 0o644)

    root.mkdir(parents=True, exist_ok=True)
    bundle = root / "fixture.bundle"
    extracted = 0
    with tarfile.open(bundle, "w:gz") as archive:
        for name, (payload, mode) in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = mode
            archive.addfile(info, io.BytesIO(payload))
            extracted += len(payload)
        if network and include_git:
            link = tarfile.TarInfo("usr/lib/git-core/git-add")
            link.type = tarfile.LNKTYPE
            link.linkname = "usr/lib/git-core/git"
            link.mode = 0o755
            archive.addfile(link)
    manifest = root / "fixture-manifest.json"
    manifest.write_text(
        json.dumps({"rootfs": {"extractedBytes": extracted}}),
        encoding="utf-8",
    )
    return bundle, manifest


def check_verify_bundle_network_rules(module: object) -> None:
    verify = load_sibling("verify_bundle", "verify-bundle.py")
    assert verify.exact_executable_name("usr/bin/git", verify.REQUIRED_NETWORK_EXECUTABLES) == "git"
    assert verify.exact_executable_name("usr/bin/curl", verify.REQUIRED_NETWORK_EXECUTABLES) == "curl"
    assert verify.exact_executable_name("usr/bin/git-extras", verify.REQUIRED_NETWORK_EXECUTABLES) is None
    assert verify.exact_executable_name("opt/dsh/usr/bin/git", verify.REQUIRED_NETWORK_EXECUTABLES) is None
    assert verify.exact_executable_name("usr/bin/ssh", verify.OPTIONAL_NETWORK_EXECUTABLES) == "ssh"
    assert verify.is_ca_certificate_entry("etc/ssl/certs/ca-certificates.crt")
    assert verify.is_ca_certificate_entry("etc/ssl/certs/002c0b4f.0")
    assert verify.is_ca_certificate_entry("etc/ssl/certs/Example.pem")
    assert not verify.is_ca_certificate_entry("etc/ssl/certs/README")
    assert verify.MIN_CA_CERTIFICATE_FILES == CA_FLOOR

    # 新校验的报错文案：正例必须走到后续校验，反例必须命中对应文案。
    new_check_markers = (
        "required network tool executables missing",
        "CA certificate files under",
        "git subcommand is not mode 0755",
        "git subcommand directory is missing",
    )

    with tempfile.TemporaryDirectory(prefix="dsh-verify-network-") as directory:
        root = Path(directory)

        def run(label: str, *, extra_args=(), **kwargs) -> tuple[int, str]:
            bundle, manifest = build_verify_fixture(root / label, **kwargs)
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS / "verify-bundle.py"),
                    "--bundle",
                    str(bundle),
                    "--manifest",
                    str(manifest),
                    *extra_args,
                ],
                cwd=REPO,
                capture_output=True,
                text=True,
                timeout=60,
            )
            return result.returncode, result.stderr + result.stdout

        # 正例：新校验全部通过，会在后面的既有校验（运行时元数据）上失败。
        code, output = run("positive", ca_files=CA_FLOOR, network=True, with_ssh=True)
        assert code != 0
        assert "BUNDLE_VERIFY_FAILED" in output
        for marker in new_check_markers:
            assert marker not in output, output
        # 可选组件（openssh-client）存在且合格时不得报错；不存在时也不算失败。
        code, output = run("without-ssh", ca_files=CA_FLOOR, network=True)
        assert code != 0 and all(marker not in output for marker in new_check_markers), output

        # 反例：缺少 git
        code, output = run("missing-git", ca_files=CA_FLOOR, network=True, include_git=False)
        assert "required network tool executables missing: git" in output, output
        # 反例：git 不是 0755
        code, output = run("git-mode", ca_files=CA_FLOOR, network=True, git_mode=0o644)
        assert "not mode 0755" in output and "usr/bin/git" in output, output
        # 反例：git 不是 ARM64（模拟 x86_64 runner 混入）
        code, output = run("git-arch", ca_files=CA_FLOOR, network=True, git_machine=62)
        assert "not an AArch64 ELF" in output and "usr/bin/git" in output, output
        # 反例：CA 文件不足
        code, output = run("thin-ca", ca_files=10, network=True)
        assert "CA certificate files under" in output, output
        # 反例：git 子命令缺执行位
        code, output = run("git-core-mode", ca_files=CA_FLOOR, network=True, bad_git_subcommand_mode=True)
        assert "git subcommand is not mode 0755" in output, output
        # 显式关闭组件组时必须被接受（仅构建侧显式关闭才允许）
        code, output = run(
            "without-network-tools",
            ca_files=0,
            network=False,
            extra_args=("--without-network-tools",),
        )
        assert code != 0 and all(marker not in output for marker in new_check_markers), output


def check_legal_notices() -> None:
    """法律材料收集/校验：用夹具跑一遍正例与两个反例（真实 /usr/share/doc 只在 CI 上）。"""
    legal = load_sibling("legal_notices", "legal-notices.py")
    quiet = contextlib.redirect_stderr(io.StringIO())
    with tempfile.TemporaryDirectory(prefix="dsh-legal-notices-") as directory:
        root = Path(directory)
        doc_root = root / "doc"
        dest = root / "ubuntu-packages"
        copyrights = {
            "git": ("GPL-2", 700),
            "curl": ("curl", 700),
            "libcurl4t64": ("curl", 700),
            "libexpat1": ("MIT", 700),
            "ca-certificates": ("MPL-2.0", 700),
            "openssh-client": ("permissive", 700),
            "libnghttp2-14": ("MIT", 700),
        }
        for name, (license_id, size) in copyrights.items():
            path = doc_root / name / "copyright"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/\n"
                f"License: {license_id}\n" + "x" * size,
                encoding="utf-8",
            )
        packages = root / "packages.txt"
        packages.write_text("\n".join(copyrights) + "\n", encoding="utf-8")

        original_doc_root = legal.DOC_ROOT
        legal.DOC_ROOT = doc_root
        try:
            assert legal.collect(dest, packages) == 0
            for name in copyrights:
                assert (dest / f"{name}-copyright.txt").is_file(), name
            inventory = json.loads((dest / "inventory.json").read_text(encoding="utf-8"))
            assert "libnghttp2-14" in inventory["collected"], "传递依赖库必须进清单"
            assert legal.verify(dest) == 0

            # 反例 1：必需组件的版权文件缺失
            (dest / "git-copyright.txt").unlink()
            with quiet:
                expect_system_exit(lambda: legal.verify(dest), "缺少 git 版权文件")

            # 反例 2：文件在但许可证标记不对（拿错文件时必须失败）
            (dest / "ca-certificates-copyright.txt").write_text(
                "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/\n"
                "License: BSD\n" + "x" * 700,
                encoding="utf-8",
            )
            with quiet:
                expect_system_exit(lambda: legal.verify(dest), "ca-certificates 标记不符")

            # 反例 3：runner 上连版权文件都没有时，collect 必须失败而不是静默跳过
            (doc_root / "curl" / "copyright").unlink()
            with quiet:
                expect_system_exit(
                    lambda: legal.collect(dest, packages),
                    "runner 缺少必需组件的版权文件",
                )
        finally:
            legal.DOC_ROOT = original_doc_root


def expect_system_exit(action, label: str) -> None:
    try:
        action()
    except SystemExit:
        return
    raise AssertionError(f"该用例应当失败但没有: {label}")


def main() -> int:
    module = load_module()
    validate = module.validate_mobile_profile
    BuildError = module.BuildError

    example = REPO / "scripts" / "mobile-profile.example.json"
    spec = validate(example)
    assert spec["dsh"]["profile"]["bundles"], "example must declare bundles"
    assert spec["mobile"]["embedRootfs"] is False, "example must opt out of embedded rootfs"

    temp = SCRIPTS / "mobile-profile.selfcheck-tmp.json"
    cases = [
        ("missing bundles", '{"dsh": {"profile": {}}}'),
        ("bad bundle id", '{"dsh": {"profile": {"bundles": ["bad id!"]}}}'),
        ("traversal bundle id", '{"dsh": {"profile": {"bundles": ["../outside"]}}}'),
        ("bad idle", '{"dsh": {"profile": {"bundles": ["a"]}}, "mobile": {"idleStopMinutes": 0}}'),
        ("bad disabled", '{"dsh": {"profile": {"bundles": ["a"]}}, "mobile": {"disabledOnMobile": [1]}}'),
        ("bad embed", '{"dsh": {"profile": {"bundles": ["a"]}}, "mobile": {"embedRootfs": "no"}}'),
        ("not object", '[1, 2]'),
    ]
    for label, payload in cases:
        temp.write_text(payload, encoding="utf-8")
        try:
            validate(temp)
        except BuildError:
            continue
        raise AssertionError(f"case should have failed: {label}")
    temp.unlink(missing_ok=True)

    assert module.normalized_path("root/.dsh/profiles/web/package.json") == "root/.dsh/profiles/web/package.json"
    assert module.PNPM_VERSION == "11.19.0"
    assert module.PNPM_ENTRYPOINT.as_posix() == "node_modules/pnpm/bin/pnpm.cjs"
    assert module.PNPM_WRAPPER == (
        b"#!/bin/sh\n"
        b'exec /opt/node/bin/node /opt/dsh/node_modules/pnpm/bin/pnpm.cjs "$@"\n'
    )
    assert module.WEB_PROFILE_PNPM_WORKSPACE == (
        b"packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n"
    )
    assert module.MOBILE_AUTH_PRELOAD.read_bytes()
    assert module.MOBILE_SESSION_PUBLISHER.read_bytes()

    with tempfile.TemporaryDirectory(prefix="dsh-python-mode-") as directory:
        root = Path(directory)
        python_root = root / "python"
        (python_root / "bin").mkdir(parents=True)
        (python_root / "lib").mkdir()
        (python_root / "bin" / "python3.13").write_bytes(b"python")
        (python_root / "lib" / "stdlib.py").write_bytes(b"stdlib")
        archive = root / "python.tar"
        with module.tarfile.open(archive, "w") as target:
            writer = module.RootfsWriter(target, 0)
            module.add_windows_tree(
                writer,
                python_root,
                "opt/python",
                executable_prefixes=(module.PurePosixPath("bin"),),
            )
        with module.tarfile.open(archive, "r") as source:
            assert source.getmember("opt/python/bin/python3.13").mode == 0o755
            assert source.getmember("opt/python/lib/stdlib.py").mode == 0o644

    with tempfile.TemporaryDirectory(prefix="dsh-npm-executable-mode-") as directory:
        root = Path(directory)
        runtime = root / "runtime"
        expected_modes = {
            "node_modules/.pnpm/@vscode+ripgrep-linux-arm64@1.18.0/node_modules/@vscode/ripgrep-linux-arm64/bin/rg": 0o755,
            "node_modules/.pnpm/@deepseek-ai+node-addon-system-linux-arm64@0.1.2/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run": 0o755,
            "node_modules/@vscode/ripgrep-linux-arm64/bin/rg": 0o755,
            "node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run": 0o755,
            "node_modules/@vscode/ripgrep-linux-arm64/bin/rg.data": 0o644,
            "node_modules/@vscode/ripgrep-linux-arm64/lib/index.js": 0o644,
            "node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run.json": 0o644,
            "node_modules/@deepseek-ai/node-addon-system-linux-arm64/lib/index.js": 0o644,
            "node_modules/@vscode/ripgrep-linux-arm64-extra/bin/rg": 0o644,
            "node_modules/@vscode/ripgrep-linux-x64/bin/rg": 0o644,
            "node_modules/@other/node-addon-system-linux-arm64/bin/landlock-run": 0o644,
            "node_modules/other/bin/rg": 0o644,
            "not_node_modules/@vscode/ripgrep-linux-arm64/bin/rg": 0o644,
            "@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run": 0o644,
        }
        for relative, mode in expected_modes.items():
            path = runtime / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
            # Target executables arrive without execute bits; unrelated source files may have them.
            path.chmod(0o644 if mode == 0o755 else 0o755)
        archive = root / "runtime.tar"
        with module.tarfile.open(archive, "w") as target:
            writer = module.RootfsWriter(target, 0)
            module.add_windows_tree(writer, runtime, "opt/dsh")
        with module.tarfile.open(archive, "r") as source:
            for relative, mode in expected_modes.items():
                member = source.getmember(f"opt/dsh/{relative}")
                assert member.mode == mode, f"unexpected runtime mode: {relative}: {member.mode:o}"

    if sys.platform != "win32":
        publisher_spec = importlib.util.spec_from_file_location(
            "mobile_session_publish",
            module.MOBILE_SESSION_PUBLISHER,
        )
        if publisher_spec is None or publisher_spec.loader is None:
            raise RuntimeError("unable to load mobile session publisher")
        publisher = importlib.util.module_from_spec(publisher_spec)
        publisher_spec.loader.exec_module(publisher)
        with tempfile.TemporaryDirectory(prefix="dsh-mobile-sessions-") as directory:
            session_root = Path(directory)
            session_dir = session_root / "project" / "session-id"
            session_dir.mkdir(parents=True, mode=0o700)
            publisher.SESSION_ROOT = publisher.PurePosixPath(session_root)
            target = session_dir / "session.v3.jsonl.zstd"
            first = session_dir / "session.v3.jsonl.zstd.0123456789ab.tmp"
            first.write_bytes(b"first")
            first.chmod(0o600)
            publisher.publish(str(first), str(target))
            assert target.read_bytes() == b"first"
            assert not first.exists()

            second = session_dir / "session.v3.jsonl.zstd.abcdef012345.tmp"
            second.write_bytes(b"second")
            second.chmod(0o600)
            try:
                publisher.publish(str(second), str(target))
            except SystemExit as error:
                assert error.code == 17
            else:
                raise AssertionError("session publisher must not replace an existing target")
            assert target.read_bytes() == b"first"
            assert second.read_bytes() == b"second"

    with tempfile.TemporaryDirectory(prefix="dsh-node-pty-") as directory:
        root = Path(directory)
        module_path = (
            root
            / "node_modules"
            / ".pnpm"
            / "node-pty@1.2.0-beta.15"
            / "node_modules"
            / "node-pty"
            / "prebuilds"
            / "linux-arm64"
            / "pty.node"
        )
        module_path.parent.mkdir(parents=True)
        module_path.write_bytes(b"synthetic-arm64-module")
        assert module.find_linux_arm64_node_pty(root) == module_path

        second = (
            root
            / "node_modules"
            / ".pnpm"
            / "node-pty@1.1.0"
            / "node_modules"
            / "node-pty"
            / "prebuilds"
            / "linux-arm64"
            / "pty.node"
        )
        second.parent.mkdir(parents=True)
        second.write_bytes(b"second-synthetic-arm64-module")
        try:
            module.find_linux_arm64_node_pty(root)
        except BuildError:
            pass
        else:
            raise AssertionError("multiple node-pty packages should fail the build")

        shutil.rmtree(second.parents[4])
        module_path.write_bytes(b"")
        try:
            module.find_linux_arm64_node_pty(root)
        except BuildError:
            pass
        else:
            raise AssertionError("empty node-pty module should fail the build")

    disabled = frozenset({"obsolete-bundle"})
    assert module.skip_runtime_path(module.PurePosixPath("pnpm-lock.yaml"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("node_modules/.package-map.json"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("obsolete-bundle/lib/client.js"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("node_modules/obsolete-bundle"), disabled)
    assert module.skip_runtime_path(
        module.PurePosixPath(
            "node_modules/.pnpm/obsolete-bundle@file+fixture/node_modules/obsolete-bundle/package.json"
        ),
        disabled,
    )
    assert not module.skip_runtime_path(
        module.PurePosixPath("node_modules/@deepseek-ai/dsh-web-app"),
        disabled,
    )

    with tempfile.TemporaryDirectory(prefix="dsh-profile-links-") as directory:
        root = Path(directory)
        (root / "node_modules" / "obsolete-bundle").mkdir(parents=True)
        kept = root / "node_modules" / "kept-profile"
        kept.mkdir(parents=True)
        platform_only = root / "node_modules" / "@deepseek-ai" / "dsh-win32-process"
        platform_only.mkdir(parents=True)
        (root / "package.json").write_text(
            json.dumps({"dependencies": {
                "obsolete-bundle": "*",
                "kept-profile": "*",
                "@deepseek-ai/dsh-win32-process": "*",
            }}),
            encoding="utf-8",
        )
        (root / "node_modules" / "obsolete-bundle" / "package.json").write_text(
            json.dumps({"name": "obsolete-bundle"}),
            encoding="utf-8",
        )
        (kept / "package.json").write_text(
            json.dumps({"name": "kept-profile"}),
            encoding="utf-8",
        )
        (platform_only / "package.json").write_text(
            json.dumps({"name": "@deepseek-ai/dsh-win32-process"}),
            encoding="utf-8",
        )

        class LinkRecorder:
            def __init__(self) -> None:
                self.links: list[tuple[str, str]] = []

            def add_symlink(self, name: str, target: str) -> None:
                self.links.append((name, target))

        recorder = LinkRecorder()
        count = module.add_profiles_module_fallback(
            recorder,
            root,
            "opt/dsh",
            disabled,
        )
        assert count == 2
        assert [name for name, _ in recorder.links] == [
            "root/.dsh/profiles/node_modules/@deepseek-ai/dsh-win32-process",
            "root/.dsh/profiles/node_modules/kept-profile",
        ]

    assert not module.skip_runtime_path(
        module.PurePosixPath(
            "node_modules/.pnpm/@deepseek-ai+dsh-win32-process@x/node_modules/"
            "@deepseek-ai/dsh-win32-process/lib/index.js"
        ),
        disabled,
    )
    assert module.skip_runtime_path(
        module.PurePosixPath(
            "node_modules/.pnpm/@deepseek-ai+dsh-win32-process@x/node_modules/"
            "@deepseek-ai/dsh-win32-process/node_modules/koffi/prebuilds/win32-x64/koffi.node"
        ),
        disabled,
    )

    # --- 网络工具组件组（git / curl / CA 证书）----------------------------------
    check_network_tools(module)

    # --- sourcemap 裁剪（构建 + 历史 bundle 重建）-------------------------------
    check_sourcemap_trimming(module, load_sibling("rebuild_rootfs_frontend", "rebuild-rootfs-frontend.py"))

    # --- verify-bundle.py 的新校验（正例 + 反例）--------------------------------
    check_verify_bundle_network_rules(module)

    # --- 法律材料收集/校验（夹具；真实 /usr/share/doc 只在 CI 上）----------------
    check_legal_notices()

    print(
        "selfcheck OK: mobile profile + rootfs path normalization + "
        "network tools component + sourcemap trimming + bundle verification rules + "
        "legal notices collection"
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
