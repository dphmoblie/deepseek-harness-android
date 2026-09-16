#!/usr/bin/env python3
"""CI-side faithful simulation of SafeRootfsExtractor's rejection rules.

Runs against a freshly built bundle + manifest and fails with the exact
offending entry, so packaging never ships an archive the app would reject.
"""
from __future__ import annotations

import argparse
import json
import posixpath
import re
import sys
from pathlib import Path
from pathlib import PurePosixPath

MAX_ENTRIES = 250_000
MAX_PATH_CHARS = 4_096
MAX_COMPONENT_CHARS = 255
PROFILE_BUNDLE_NAMES = (
    "@deepseek-ai/dsh-base",
    "@deepseek-ai/dsh-web-app",
    "@deepseek-harness/dsh-mobile-shizuku",
)
RUNTIME_BUILD_METADATA_PATHS = frozenset(
    {
        "opt/dsh/pnpm-lock.yaml",
        "opt/dsh/pnpm-workspace.yaml",
        "opt/dsh/node_modules/.modules.yaml",
        "opt/dsh/node_modules/.package-map.json",
        "opt/dsh/node_modules/.pnpm-workspace-state-v1.json",
        "opt/dsh/node_modules/.pnpm/lock.yaml",
    }
)
MOBILE_BUNDLE_PATTERN = re.compile(
    r"^(?:@[A-Za-z0-9][A-Za-z0-9._-]{0,61}/)?[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"
)
OFFICIAL_FRONTEND_MARKER = b'name="dsh-official-frontend" content="android-adapted-v1"'
LEGACY_MOBILE_FRONTEND_MARKER = b'dsh-mobile-frontend'
FRONTEND_DIST_SUFFIX = "/node_modules/@deepseek-ai/dsh-web-frontend/dist/"
DSH_PACKAGE_PATTERN = re.compile(
    r"^opt/dsh/node_modules/\.pnpm/@deepseek-ai\+dsh@[^/]+/node_modules/@deepseek-ai/dsh/package\.json$"
)
LEGACY_FRONTEND_FILES = frozenset({"plugin-workbench-loader.js"})
LEGACY_FRONTEND_PREFIXES = ("plugin-workbench/",)
SUPPORT_FILES = {
    "usr/local/lib/dsh-mobile-auth.cjs": Path(__file__).with_name("mobile-auth-preload.cjs"),
    "usr/local/lib/dsh-mobile-session-publish.py": Path(__file__).with_name("mobile-session-publish.py"),
}

# Match package paths, not basenames: a random file called rg is not a runtime tool.
REQUIRED_RUNTIME_EXECUTABLES = {
    "bash": "usr/bin/bash",
    "ripgrep": "node_modules/@vscode/ripgrep-linux-arm64/bin/rg",
    "landlock-run": "node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run",
}
# 网络工具组件组的必需入口：路径固定在 /usr 下，必须精确匹配归档路径，
# 不接受用相近名字的文件（如 usr/bin/git-extras 或包内同名副本）冒充。
REQUIRED_NETWORK_EXECUTABLES = {
    "git": "usr/bin/git",
    "curl": "usr/bin/curl",
    "git-remote-https": "usr/lib/git-core/git-remote-https",
}
# openssh-client 是可选项：存在就必须通过同样的检查，缺失不算失败。
OPTIONAL_NETWORK_EXECUTABLES = {
    "ssh": "usr/bin/ssh",
    "scp": "usr/bin/scp",
    "sftp": "usr/bin/sftp",
    "ssh-keygen": "usr/bin/ssh-keygen",
    "ssh-keyscan": "usr/bin/ssh-keyscan",
    "ssh-add": "usr/bin/ssh-add",
    "ssh-agent": "usr/bin/ssh-agent",
}
# git 的子命令目录：其中每个常规文件都必须 0755。只给 /usr/bin/git 授权是不够的
# （git-remote-https、git-credential-*、git-upload-pack 等都在这里），
# 与 D3 的 rg / landlock-run 权限口径一致。
GIT_SUBCOMMAND_DIRECTORY = "usr/lib/git-core/"
# /etc/ssl/certs 的 CA 文件数量下限：空目录、只剩一个 ca-certificates.crt 都算失败，
# 否则 git clone https:// 会先在证书校验上失败。该值与
# scripts/build-embedded-runtime.py 的 NETWORK_TOOLS_MIN_CA_FILES 保持一致。
MIN_CA_CERTIFICATE_FILES = 64
CA_CERTIFICATE_DIRECTORY = "etc/ssl/certs/"
CA_HASHED_LINK_PATTERN = re.compile(r"[0-9a-f]{8}\.\d+")


def resolved_symlink_target(name: str, target: str) -> PurePosixPath | None:
    """按 POSIX 语义折叠链接目标，返回解析后的绝对路径；跑出 root 时返回 None。

    **返回路径而不只是布尔值**是 N-4 的教训：原来只判断「有没有跑出 root」，
    于是**断链**（指向一个不存在的位置）完全看不出来 ——
    `usr/local/bin/python3` 若写成 `../../opt/python/bin/python3`，解析到
    `/usr/opt/python/bin/python3`：既不越界、也不存在，校验一路放行，
    直到真机上敲 `python3` 才暴露。`main()` 用它做「落点必须存在」的检查，
    单测直接断言这条解析结果（见 `scripts/runtime-executables.test.mjs`）。
    """
    parent = PurePosixPath(name).parent
    base = PurePosixPath("/") if target.startswith("/") else parent
    rel = target.lstrip("/")
    stack: list[str] = []
    for part in (str(base) + "/" + rel).split("/"):
        if part in ("", ".", "/"):
            continue
        if part == "..":
            if not stack:
                return None
            stack.pop()
        else:
            stack.append(part)
    return PurePosixPath("/" + "/".join(stack)) if stack else None


# 明确要求的链接：解析结果必须**正好落在期望的位置，且该位置存在**。
# 这一条专门防「相对层数写错」——它同时抓住「指向错误位置」与「指向不存在的位置」，
# 而两者在真机上的表现都是「命令找不到」，排查成本很高。
REQUIRED_SYMLINKS = {
    "usr/local/bin/python3": "opt/python/bin/python3",
    "usr/local/bin/python": "opt/python/bin/python3",
}


# 基线镜像（ubuntu-base）一定会提供的 SONAME，属于预期不出现在 rootfs bundle 里的：
# 动态加载器与 libc 家族。**刻意保持很短**——每多写一个名字就等于放过一类缺库，
# 而 O-7 的教训正是「缺库没人管」。网络工具自己的依赖（libcurl / libgssapi 等）绝不在此列。
BASE_IMAGE_SONAMES = frozenset({
    "ld-linux-aarch64.so.1",
    "libc.so.6",
    "libm.so.6",
    "libdl.so.2",
    "libpthread.so.0",
    "librt.so.1",
    "libgcc_s.so.1",
})

# 必须做「依赖可解析性」检查的二进制：用户会直接敲的，以及 git 走 HTTPS 的必经之路。
# 少列一个就等于放过一条「命令在真机上起不来」的路径。
DEPENDENCY_CHECKED_BINARIES = (
    "usr/bin/git",
    "usr/bin/curl",
    "usr/bin/ssh",
    "usr/lib/git-core/git-remote-https",
)


def elf_needed_sonames(data: bytes) -> list[str]:
    """读 ELF64 小端文件的 DT_NEEDED（它依赖的 SONAME 列表）。

    **为什么必须解析 ELF，而不是「在 runner 上跑一下 --version」**：runner 上装着这些库，
    动态加载器会从**宿主**解析，缺库的镜像照样能跑起来 —— O-7 就是这么漏出去的：
    已发布的 rootfs.bundle 里 `usr/bin/curl`、`usr/bin/ssh`、`git-remote-https` 都在，
    而 libcurl / libgssapi / libkrb5 **一个都没有**（实测：45,439 个条目里零命中）。
    只有把依赖名抠出来、再对着**产物内容**逐个查，才验得出「这个镜像能不能自己跑起来」。

    非 ELF 或结构异常时返回空列表（调用方只在明确的 ELF 上调用）。
    """
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return []
    phoff = int.from_bytes(data[0x20:0x28], "little")
    phentsize = int.from_bytes(data[0x36:0x38], "little")
    phnum = int.from_bytes(data[0x38:0x3A], "little")
    if phentsize < 56 or phnum == 0 or phoff + phentsize * phnum > len(data):
        return []
    loads: list[tuple[int, int, int]] = []
    dynamic_offset: int | None = None
    dynamic_size = 0
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type = int.from_bytes(data[base:base + 4], "little")
        p_offset = int.from_bytes(data[base + 8:base + 16], "little")
        p_vaddr = int.from_bytes(data[base + 16:base + 24], "little")
        p_filesz = int.from_bytes(data[base + 32:base + 40], "little")
        if p_type == 1:  # PT_LOAD
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:  # PT_DYNAMIC
            dynamic_offset, dynamic_size = p_offset, p_filesz
    if dynamic_offset is None or dynamic_offset + dynamic_size > len(data):
        return []

    def vaddr_to_offset(vaddr: int) -> int | None:
        for start, offset, size in loads:
            if start <= vaddr < start + size:
                return offset + (vaddr - start)
        return None

    strtab: int | None = None
    strsz = 0
    needed_offsets: list[int] = []
    for index in range(dynamic_size // 16):
        base = dynamic_offset + index * 16
        d_tag = int.from_bytes(data[base:base + 8], "little", signed=True)
        d_val = int.from_bytes(data[base + 8:base + 16], "little")
        if d_tag == 0:  # DT_NULL：动态段到此为止
            break
        if d_tag == 1:  # DT_NEEDED
            needed_offsets.append(d_val)
        elif d_tag == 5:  # DT_STRTAB
            strtab = vaddr_to_offset(d_val)
        elif d_tag == 10:  # DT_STRSZ
            strsz = d_val
    if strtab is None or strsz <= 0 or strtab + strsz > len(data):
        return []
    names: list[str] = []
    for offset in needed_offsets:
        if offset >= strsz:
            continue
        end = data.find(b"\x00", strtab + offset, strtab + strsz)
        if end < 0:
            continue
        name = data[strtab + offset:end].decode("utf-8", "replace")
        if name:
            names.append(name)
    return names


def runtime_executable_name(name: str) -> str | None:
    for label, suffix in REQUIRED_RUNTIME_EXECUTABLES.items():
        if (label == "bash" and name == suffix) or (
            label != "bash" and name.startswith("opt/dsh/") and name.endswith("/" + suffix)
        ):
            return label
    return None


def exact_executable_name(name: str, table: dict[str, str]) -> str | None:
    """在「目标路径 -> 标签」表里精确匹配归档路径。"""
    for label, path in table.items():
        if name == path:
            return label
    return None


def is_ca_certificate_entry(name: str) -> bool:
    """CA 目录条目：PEM/CRT 证书，或 update-ca-certificates 生成的 <hash>.N 链接。"""
    basename = posixpath.basename(name)
    return basename.endswith((".pem", ".crt")) or bool(CA_HASHED_LINK_PATTERN.fullmatch(basename))


def validate_runtime_executable(member, header: bytes) -> None:
    """Require a regular, executable AArch64 ELF; never accept a host-platform binary."""
    if not member.isreg() or member.size < 64 or member.mode != 0o755:
        raise ValueError(f"runtime executable missing, empty or not mode 0755: {member.name!r}")
    if (
        len(header) < 64 or header[:7] != b"\x7fELF\x02\x01\x01"
        or int.from_bytes(header[16:18], "little") not in (2, 3)
        or int.from_bytes(header[18:20], "little") != 183
    ):
        raise ValueError(f"runtime executable is not an AArch64 ELF: {member.name!r}")


def normalized(raw: str) -> str:
    return raw.removesuffix("/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument(
        "--without-network-tools",
        action="store_true",
        help="accept an image built without the network tools component group; "
        "only for builds that explicitly passed --without-network-tools to "
        "scripts/build-embedded-runtime.py",
    )
    args = parser.parse_args()

    manifest = json.loads(open(args.manifest, encoding="utf-8").read())
    expected_extracted = manifest["rootfs"]["extractedBytes"]

    seen: set[str] = set()
    types: dict[str, str] = {}
    file_modes: dict[str, int] = {}
    symlinks: list[tuple[str, str]] = []
    hardlinks: list[tuple[str, str]] = []
    entry_count = 0
    extracted = 0
    frontend_indexes: list[str] = []
    pnpm_wrapper: bytes | None = None
    web_profile_workspace: bytes | None = None
    runtime_metadata: bytes | None = None
    dsh_package_metadata: list[bytes] = []
    support_files: dict[str, tuple[bytes, int]] = {}
    runtime_executables: set[str] = set()
    ca_certificate_files = 0
    sourcemap_entries = 0
    sourcemap_bytes = 0
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
            # sourcemap 只做统计（非致命）：裁剪口径见 build-embedded-runtime.py。
            if m.isreg() and name.lower().endswith(".map"):
                sourcemap_entries += 1
                sourcemap_bytes += max(m.size, 0)
            executable = runtime_executable_name(name)
            if executable is None and not args.without_network_tools:
                executable = exact_executable_name(name, REQUIRED_NETWORK_EXECUTABLES)
                if executable is None:
                    executable = exact_executable_name(name, OPTIONAL_NETWORK_EXECUTABLES)
                if (
                    name.startswith(CA_CERTIFICATE_DIRECTORY)
                    and (m.isreg() or m.issym())
                    and is_ca_certificate_entry(name)
                ):
                    ca_certificate_files += 1
            if executable is not None:
                source = t.extractfile(m) if m.isreg() else None
                try:
                    validate_runtime_executable(m, b"" if source is None else source.read(64))
                except ValueError as error:
                    fail(str(error))
                finally:
                    if source is not None:
                        source.close()
                runtime_executables.add(executable)
            if m.isdir():
                types[name] = "dir"
                continue
            if m.isreg():
                types[name] = "file"
                file_modes[name] = m.mode
                if m.size < 0:
                    fail(f"negative-size file: {name!r}")
                extracted += m.size
                if name in {
                    "usr/local/bin/pnpm",
                    "root/.dsh/profiles/web/pnpm-workspace.yaml",
                    "etc/deepseek-harness-runtime.json",
                }:
                    source = t.extractfile(m)
                    content = b"" if source is None else source.read()
                    if name == "usr/local/bin/pnpm":
                        pnpm_wrapper = content
                    elif name == "root/.dsh/profiles/web/pnpm-workspace.yaml":
                        web_profile_workspace = content
                    else:
                        runtime_metadata = content
                elif DSH_PACKAGE_PATTERN.fullmatch(name):
                    source = t.extractfile(m)
                    dsh_package_metadata.append(b"" if source is None else source.read())
                elif name in SUPPORT_FILES:
                    source = t.extractfile(m)
                    support_files[name] = (b"" if source is None else source.read(), m.mode)
                if FRONTEND_DIST_SUFFIX in name:
                    dist_path = name.split(FRONTEND_DIST_SUFFIX, 1)[1]
                    if dist_path == "index.html":
                        frontend_indexes.append(name)
                        source = t.extractfile(m)
                        content = b"" if source is None else source.read()
                        if OFFICIAL_FRONTEND_MARKER not in content:
                            fail(f"official frontend marker missing: {name!r}")
                        if b'<div id="root">' not in content:
                            fail(f"official frontend index missing #root: {name!r}")
                        if LEGACY_MOBILE_FRONTEND_MARKER in content:
                            fail(f"legacy mobile frontend marker remains: {name!r}")
                    elif dist_path.endswith("/index.html"):
                        fail(f"duplicate frontend entry: {name!r}")
                    elif dist_path in LEGACY_FRONTEND_FILES or dist_path.startswith(LEGACY_FRONTEND_PREFIXES):
                        fail(f"legacy custom frontend artifact remains: {name!r}")
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

    missing_executables = set(REQUIRED_RUNTIME_EXECUTABLES) - runtime_executables
    if missing_executables:
        fail(f"required runtime executables missing: {', '.join(sorted(missing_executables))}")
    if not args.without_network_tools:
        # 网络工具组件组：git/curl/git-remote-https 必须存在且是 0755 的非空 ARM64 ELF。
        # 这与既有三个入口用同一套口径，不放宽任何一条。
        missing_network_executables = set(REQUIRED_NETWORK_EXECUTABLES) - runtime_executables
        if missing_network_executables:
            fail(
                "required network tool executables missing: "
                f"{', '.join(sorted(missing_network_executables))}"
            )
        if ca_certificate_files < MIN_CA_CERTIFICATE_FILES:
            fail(
                f"CA certificate files under {CA_CERTIFICATE_DIRECTORY!r} are insufficient: "
                f"{ca_certificate_files} < {MIN_CA_CERTIFICATE_FILES}"
            )
        # git 的子命令都在 usr/lib/git-core 下，缺执行位时 git clone/push 会静默不可用。
        git_subcommand_files = [n for n in file_modes if n.startswith(GIT_SUBCOMMAND_DIRECTORY)]
        if not git_subcommand_files:
            fail(f"git subcommand directory is missing: {GIT_SUBCOMMAND_DIRECTORY!r}")
        invalid_git_subcommands = sorted(
            n for n in git_subcommand_files if file_modes[n] != 0o755
        )
        if invalid_git_subcommands:
            fail(f"git subcommand is not mode 0755: {invalid_git_subcommands[0]!r}")
    if extracted != expected_extracted:
        fail(f"extracted size mismatch: {extracted} != {expected_extracted}")
    expected_dsh_version = manifest.get("dshVersion")
    if not isinstance(expected_dsh_version, str) or not re.fullmatch(
        r"[A-Za-z0-9._-]{1,96}", expected_dsh_version
    ):
        fail("manifest dshVersion is missing or invalid")
    try:
        metadata = json.loads(runtime_metadata) if runtime_metadata is not None else None
    except (json.JSONDecodeError, UnicodeDecodeError):
        metadata = None
    if not isinstance(metadata, dict):
        fail("runtime build metadata is missing or invalid")
    if metadata.get("dshVersion") != expected_dsh_version:
        fail(
            "runtime dshVersion mismatch: "
            f"{metadata.get('dshVersion')!r} != {expected_dsh_version!r}"
        )
    expected_runtime_version = manifest.get("version")
    if not isinstance(expected_runtime_version, str) or not re.fullmatch(
        r"[A-Za-z0-9._-]{1,96}", expected_runtime_version
    ):
        fail("manifest runtime version is missing or invalid")
    if metadata.get("runtimeVersion") != expected_runtime_version:
        fail(
            "runtime version mismatch: "
            f"{metadata.get('runtimeVersion')!r} != {expected_runtime_version!r}"
        )
    if len(dsh_package_metadata) != 1:
        fail(f"expected exactly one Harness runtime package, found {len(dsh_package_metadata)}")
    try:
        packaged_dsh_version = json.loads(dsh_package_metadata[0]).get("version")
    except (json.JSONDecodeError, UnicodeDecodeError):
        packaged_dsh_version = None
    if packaged_dsh_version != expected_dsh_version:
        fail(
            "packaged Harness version mismatch: "
            f"{packaged_dsh_version!r} != {expected_dsh_version!r}"
        )
    if len(frontend_indexes) != 1:
        fail(f"expected exactly one official frontend index, found {len(frontend_indexes)}")
    for archive_path, local_path in SUPPORT_FILES.items():
        packaged = support_files.get(archive_path)
        if packaged is None or packaged[0] != local_path.read_bytes():
            fail(f"mobile support file is missing or stale: {archive_path!r}")
        expected_mode = 0o600 if archive_path.endswith(".py") else 0o644
        if packaged[1] != expected_mode:
            fail(f"mobile support file mode is invalid: {archive_path!r}")

    # 文件-目录冲突（提取器 ensureDirectory 规则：父路径被非目录条目占用）
    for name, kind in list(types.items()):
        p = str(PurePosixPath(name).parent)
        while p and p != ".":
            if p in types and types[p] != "dir":
                fail(f"path conflict: parent of {name!r} is occupied by {types[p]} entry {p!r}")
            p = str(PurePosixPath(p).parent)

    # 符号链接：目标解析不越界（绝对目标落在 root 内；相对目标折叠后不越界）
    for name, target in symlinks:
        if resolved_symlink_target(name, target) is None:
            fail(f"symlink escapes root: {name!r} -> {target!r}")
        if name in seen and any(n == name for n, _ in symlinks[:symlinks.index((name, target))]):
            fail(f"duplicate symlink path: {name!r}")

    # 要求的链接：解析结果必须正好落在期望位置且该位置存在（防相对层数写错，见 N-4）。
    for link, expected in REQUIRED_SYMLINKS.items():
        actual = next((t for n, t in symlinks if n == link), None)
        # 镜像里没装 python 时不强制：与构建脚本「python 目录存在才建链」的条件一致。
        if actual is None:
            continue
        resolved = resolved_symlink_target(link, actual)
        if resolved is None:
            fail(f"{link} escapes root: -> {actual!r}")
        resolved_name = str(resolved).lstrip("/")
        if resolved_name != expected:
            fail(
                f"{link} resolves to /{resolved_name}, expected /{expected}: "
                f"relative target {actual!r} has the wrong number of '..' levels"
            )
        if expected not in seen:
            fail(f"{link} points at /{expected}, which is missing from the bundle")

    # 依赖可解析性（O-7）：预置的网络工具必须能在**这个镜像内**解析到自己的共享库。
    #
    # 为什么这一条非有不可：已发布的产物里 `usr/bin/curl`、`usr/bin/ssh`、`git-remote-https` 都在，
    # 而 libcurl / libgssapi / libkrb5 **一个都没有**（45,439 个条目里零命中），
    # 真机上这三个命令全部启动失败。原有校验只查「存在 + 0755 + ELF」，
    # 查不出缺库；在 runner 上跑 `--version` 也查不出，因为加载器会用宿主的库。
    # 只有把 DT_NEEDED 抠出来对着产物内容查，才拦得住。
    bundle_basenames = {PurePosixPath(entry).name for entry in seen}
    unresolved: list[str] = []
    with tarfile.open(args.bundle, "r:gz") as t:
        for name in DEPENDENCY_CHECKED_BINARIES:
            # `--without-network-tools` 的镜像里这些文件本就不存在，跳过而不是报错。
            if name not in seen:
                continue
            member = t.getmember(name)
            if not member.isreg():
                continue
            executable = t.extractfile(member)
            if executable is None:
                continue
            for soname in elf_needed_sonames(executable.read()):
                if soname in bundle_basenames or soname in BASE_IMAGE_SONAMES:
                    continue
                unresolved.append(f"{name} -> {soname}")
    if unresolved:
        fail(
            "unresolved shared libraries (these commands will not start in the guest): "
            + ", ".join(sorted(set(unresolved)))
        )
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
        ("usr/local/bin/npm", "../../../opt/node/bin/npm"),
        ("usr/local/bin/npx", "../../../opt/node/bin/npx"),
        ("usr/local/bin/corepack", "../../../opt/node/bin/corepack"),
        ("usr/local/bin/python3", "../../opt/python/bin/python3"),
        ("usr/local/bin/python", "../../opt/python/bin/python3"),
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

    python_link = "opt/python/bin/python3"
    if types.get(python_link) != "sym":
        fail("embedded Python interpreter link is missing")
    python_target = next(target for name, target in symlinks if name == python_link)
    python_executable = canonical_target(python_link, python_target).lstrip("/")
    if types.get(python_executable) != "file" or file_modes.get(python_executable) != 0o755:
        fail("embedded Python interpreter target is missing or not executable")
    for name, mode in file_modes.items():
        if name.startswith("opt/python/bin/") and mode != 0o755:
            fail(f"embedded Python command is not executable: {name!r}")

    expected_pnpm_wrapper = (
        b"#!/bin/sh\n"
        b'exec /opt/node/bin/node /opt/dsh/node_modules/pnpm/bin/pnpm.cjs "$@"\n'
    )
    if pnpm_wrapper != expected_pnpm_wrapper:
        fail("pinned pnpm wrapper is missing or invalid")
    pnpm_package_link = "opt/dsh/node_modules/pnpm"
    if types.get(pnpm_package_link) != "sym":
        fail("pinned pnpm package link is missing")
    pnpm_package_target = next(target for name, target in symlinks if name == pnpm_package_link)
    pnpm_package_dir = canonical_target(pnpm_package_link, pnpm_package_target).lstrip("/")
    if types.get(f"{pnpm_package_dir}/bin/pnpm.cjs") != "file":
        fail("pinned pnpm package entrypoint is missing")

    # profiles 扁平模块回退：dsh 启动时 cordis 从 profile 目录解析 loader entry，
    # 必须能在 $DSH_HOME/profiles/node_modules 找到全部 profile bundles。
    mobile_profile = manifest.get("mobile")
    profile_bundle_names = list(PROFILE_BUNDLE_NAMES)
    if mobile_profile is not None and not isinstance(mobile_profile, dict):
        fail("manifest mobile profile must be an object")
    if isinstance(mobile_profile, dict):
        profile_bundle_names = []
        dsh_profile = mobile_profile.get("dsh")
        if isinstance(dsh_profile, dict):
            profile = dsh_profile.get("profile")
            if isinstance(profile, dict):
                bundles = profile.get("bundles")
                if isinstance(bundles, list) and 0 < len(bundles) <= 64:
                    if any(not isinstance(name, str) or not MOBILE_BUNDLE_PATTERN.fullmatch(name) for name in bundles):
                        fail("manifest mobile profile contains an invalid bundle name")
                    profile_bundle_names = list(bundles)
        if not profile_bundle_names:
            fail("manifest mobile profile does not declare any bundle names")
        expected_workspace = b"packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n"
        if web_profile_workspace != expected_workspace:
            fail("mobile web profile pnpm workspace is missing or invalid")

    leaked_metadata = sorted(RUNTIME_BUILD_METADATA_PATHS.intersection(seen))
    if leaked_metadata:
        fail(f"runtime contains build-only package-manager metadata: {leaked_metadata[0]!r}")
    for package_name in profile_bundle_names:
        link_name = f"root/.dsh/profiles/node_modules/{package_name}"
        if types.get(link_name) != "sym":
            fail(f"profiles module fallback missing for bundle: {package_name!r}")
        actual = next(target for n, target in symlinks if n == link_name)
        resolved = canonical_target(link_name, actual).lstrip("/")
        if resolved not in types:
            fail(f"profiles link target missing in bundle: {link_name!r} -> {actual!r}")

    # 全量扁平链接校验：所有 root/.dsh/profiles/node_modules 条目必须是符号链接，
    # 且目标在 bundle 内真实存在。dsh 启动时 cordis 从 profile 目录解析 loader
    # entry 依赖这条扁平目录；链接缺一个，对应插件就报 Cannot find package。
    # 数量与构建期写入 manifest 的 profileLinks 对照，防止回归成部分链接。
    profile_prefix = "root/.dsh/profiles/node_modules/"
    profile_links = {n: t for n, t in symlinks if n.startswith(profile_prefix)}
    for name, target in profile_links.items():
        resolved = canonical_target(name, target).lstrip("/")
        if resolved not in types:
            fail(f"profiles link target missing in bundle: {name!r} -> {target!r} (resolved {resolved!r})")
    expected_links = manifest.get("profileLinks")
    if expected_links is not None and len(profile_links) != expected_links:
        fail(f"profiles link count mismatch: bundle has {len(profile_links)}, manifest declares {expected_links}")
    print(f"PROFILES_LINKS={len(profile_links)}")
    # 网络工具与裁剪统计写进 CI 日志：CA 数量是硬门槛，sourcemap 残余量是预算观测值。
    if not args.without_network_tools:
        print(f"CA_CERTIFICATES={ca_certificate_files}")
    print(f"SOURCEMAPS={sourcemap_entries} SOURCEMAP_BYTES={sourcemap_bytes}")

    print(f"BUNDLE_VERIFY_OK: entries={entry_count} extracted={extracted} symlinks={len(symlinks)} hardlinks={len(hardlinks)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
