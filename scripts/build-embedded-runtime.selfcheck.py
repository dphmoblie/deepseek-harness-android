#!/usr/bin/env python3
"""Self-check for build-embedded-runtime.py mobile-profile integration (no rootfs inputs required)."""
from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent


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

    print("selfcheck OK: mobile profile + rootfs path normalization")

    return 0


if __name__ == "__main__":
    sys.exit(main())
