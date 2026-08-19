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
        (
            "disabled Android layout bundle",
            '{"dsh": {"profile": {"bundles": ["dsh-mobile-compat"]}}}',
        ),
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

    disabled = frozenset({"dsh-mobile-compat"})
    assert module.skip_runtime_path(module.PurePosixPath("pnpm-lock.yaml"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("node_modules/.package-map.json"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("dsh-mobile-compat/lib/client.js"), disabled)
    assert module.skip_runtime_path(module.PurePosixPath("node_modules/dsh-mobile-compat"), disabled)
    assert module.skip_runtime_path(
        module.PurePosixPath(
            "node_modules/.pnpm/dsh-mobile-compat@file+fixture/node_modules/dsh-mobile-compat/package.json"
        ),
        disabled,
    )
    assert not module.skip_runtime_path(
        module.PurePosixPath("node_modules/@deepseek-ai/dsh-web-app"),
        disabled,
    )

    with tempfile.TemporaryDirectory(prefix="dsh-profile-links-") as directory:
        root = Path(directory)
        (root / "node_modules" / "dsh-mobile-compat").mkdir(parents=True)
        kept = root / "node_modules" / "kept-profile"
        kept.mkdir(parents=True)
        (root / "package.json").write_text(
            json.dumps({"dependencies": {"dsh-mobile-compat": "*", "kept-profile": "*"}}),
            encoding="utf-8",
        )
        (root / "node_modules" / "dsh-mobile-compat" / "package.json").write_text(
            json.dumps({"name": "dsh-mobile-compat"}),
            encoding="utf-8",
        )
        (kept / "package.json").write_text(
            json.dumps({"name": "kept-profile"}),
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
        assert count == 1
        assert [name for name, _ in recorder.links] == [
            "root/.dsh/profiles/node_modules/kept-profile"
        ]

    with tempfile.TemporaryDirectory(prefix="dsh-app-boot-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-app-boot@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-app-boot"
            / "lib"
            / "index.js"
        )
        fixture.parent.mkdir(parents=True)
        trust_existing = (
            "if (!stat.isSymbolicLink()) throw new Error("
            "`dsh: ${link} exists and is not a symlink; remove it so dsh can manage the installation fallback`);"
        )
        tolerate_denied = (
            'if (error.code !== "EEXIST" || !lstatSync(link).isSymbolicLink() || readlinkSync(link) !== target) throw error;'
        )
        tolerate_denied_prefix = (
            'if (error.code === "EACCES" || error.code === "EPERM" || error.code === "ENOTSUP") return; '
        )
        tolerate_denied_replacement = tolerate_denied_prefix + tolerate_denied
        fixture.write_text(f"{trust_existing}\n{tolerate_denied}\n", encoding="utf-8")

        module.patch_dsh_app_boot(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert patched.count(tolerate_denied_prefix) == 1
        assert patched.count(tolerate_denied_replacement) == 1

        module.patch_dsh_app_boot(Path(directory))
        assert fixture.read_text(encoding="utf-8") == patched

        fixture.write_text(
            patched.replace(
                tolerate_denied_replacement,
                tolerate_denied_prefix * 4 + tolerate_denied,
            ),
            encoding="utf-8",
        )
        module.patch_dsh_app_boot(Path(directory))
        assert fixture.read_text(encoding="utf-8") == patched

        fixture.write_text(f"{patched}\n{trust_existing}\n", encoding="utf-8")
        try:
            module.patch_dsh_app_boot(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("duplicate trust-existing source should fail the build")

    with tempfile.TemporaryDirectory(prefix="dsh-client-failure-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-client-runtime@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-runtime"
            / "lib"
            / "client.js"
        )
        fixture.parent.mkdir(parents=True)
        fixture_source = (
            "function displayFailureMessage(failure) {\n"
            "\tif (failure === null || typeof failure !== \"object\") return String(failure);\n"
            "\tconst record = failure;\n"
            "\tif (record.code === \"AUTH\") return \"API key is invalid\";\n"
            "\treturn typeof record.message === \"string\" ? record.message : JSON.stringify(failure);\n"
            "}\n\t\t//#endregion\n"
        )
        fixture.write_text(fixture_source, encoding="utf-8")
        fixture_copy = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "truncated-client-store-copy"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-runtime"
            / "lib"
            / "client.js"
        )
        fixture_copy.parent.mkdir(parents=True)
        fixture_copy.write_text(fixture_source, encoding="utf-8")
        module.patch_client_failure_display(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert "Failure details unavailable" in patched
        assert "const placeholders" in patched
        assert module.CLIENT_FAILURE_DISPLAY_MARKER in fixture_copy.read_text(encoding="utf-8")
        fixture.write_text(
            patched
            + "\nconsole.log(JSON.stringify([\n"
            + "  displayFailureMessage({message: 'Provider quota exhausted'}),\n"
            + "  displayFailureMessage({error: {detail: 'Upstream connection reset'}}),\n"
            + "  displayFailureMessage({code: 'RATE_LIMIT'}),\n"
            + "  displayFailureMessage({status: 503}),\n"
            + "  displayFailureMessage({message: '本轮因错误终止'}),\n"
            + "  displayFailureMessage({message: 'Authorization: Bearer synthetic-secret'}),\n"
            + "  displayFailureMessage({message: 'connect ' + ['192', '168', '10', '8'].join('.') + ' failed'}),\n"
            + "  displayFailureMessage({message: '{\"secret\":\"must-not-render\"}'}),\n"
            + "  displayFailureMessage({message: 'contact person@example.invalid'}),\n"
            + "  displayFailureMessage({message: 'path C:\\\\Users\\\\Synthetic\\\\project and /home/synthetic/work'}),\n"
            + "  displayFailureMessage({message: 'call ' + '1' + '3' + '0'.repeat(9)}),\n"
            + "  displayFailureMessage({message: 'Request failed', error: {message: 'Provider quota exhausted'}}),\n"
            + "  displayFailureMessage({message: 'Request failed', code: 'RATE_LIMIT', status: 429}),\n"
            + "  displayFailureMessage({message: 'Request failed', status: 429}),\n"
            + "  displayFailureMessage([{message: '本轮因错误终止'}, {detail: 'Array failure detail'}]),\n"
            + "  displayFailureMessage({code: 'UNAUTHORIZED', message: 'Proxy session expired'}),\n"
            + "  displayFailureMessage({code: 'INVALID_API_KEY', message: 'must not render'}),\n"
            + "  displayFailureMessage({message: 'Traceback (most recent call last):\\n  File \"/root/private/app.py\", line 8, in request\\n    raise TimeoutError()\\nTimeoutError: Upstream timed out'}),\n"
            + "  displayFailureMessage({message: 'paths /opt/dsh/private.js /data/user/0/io.deepseekharness.mobile/files/config /data/user_de/0/io.deepseekharness.mobile/files/direct-boot /tmp/session/cache /storage/emulated/0/Download/key'}),\n"
            + "  displayFailureMessage({message: 'tokens github_' + 'pat_' + 'A'.repeat(24) + ' ' + ['ghp', 'B'.repeat(24)].join('_') + ' AI' + 'za' + 'C'.repeat(24) + ' xoxb-' + '1'.repeat(12) + '-' + 'D'.repeat(20)}),\n"
            + "  displayFailureMessage({message: 'url https://example.invalid/request?key=query-placeholder&client_secret=secret-placeholder'}),\n"
            + "]));\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["node", str(fixture)],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        assert json.loads(result.stdout) == [
            "Provider quota exhausted",
            "Upstream connection reset",
            "Error code RATE_LIMIT",
            "HTTP 503",
            "Failure details unavailable",
            "Authorization: [redacted]",
            "connect [private address] failed",
            "Failure details unavailable",
            "contact [email redacted]",
            "path %USERPROFILE%\\project and $HOME/work",
            "call [phone redacted]",
            "Provider quota exhausted",
            "Error code RATE_LIMIT",
            "HTTP 429",
            "Array failure detail",
            "Proxy session expired",
            "API key is invalid",
            "TimeoutError: Upstream timed out",
            "paths $DSH_HOME $APP_DATA $APP_DATA $TMP $SHARED_STORAGE",
            "tokens [redacted] [redacted] [redacted] [redacted]",
            "url https://example.invalid/request?key=[redacted]&client_secret=[redacted]",
        ]

        # Running the replacement twice must keep every discovered copy valid.
        module.patch_client_failure_display(Path(directory))
        assert fixture.read_text(encoding="utf-8").count("function displayFailureMessage(failure) {") == 1
        assert fixture_copy.read_text(encoding="utf-8").count("function displayFailureMessage(failure) {") == 1

        fixture.write_text(patched.replace(".replace(/\\bAKIA", ".replace(/\\bAKIB", 1), encoding="utf-8")
        try:
            module.patch_client_failure_display(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("modified client failure replacement should fail the build")
        fixture.write_text(patched, encoding="utf-8")

        partial = Path(str(fixture).replace("@fixture", "@fixture-partial"))
        partial.parent.mkdir(parents=True)
        partial.write_text(
            f"/* {module.CLIENT_FAILURE_DISPLAY_MARKER} */\n"
            "function displayFailureMessage(failure) { return String(failure); }\n",
            encoding="utf-8",
        )
        try:
            module.patch_client_failure_display(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("partial client failure marker should fail the build")

    with tempfile.TemporaryDirectory(prefix="dsh-client-settings-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-client-ui-settings-general@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-ui-settings-general"
            / "lib"
            / "client.js"
        )
        fixture.parent.mkdir(parents=True)
        fixture_source = (
            'const css$3 = ".VOzbGW_panel{display:flex}";\n'
            'const tagId$3 = "settings";\n'
        )
        fixture.write_text(fixture_source, encoding="utf-8")
        fixture_copy = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "truncated-settings-store-copy"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-ui-settings-general"
            / "lib"
            / "client.js"
        )
        fixture_copy.parent.mkdir(parents=True)
        fixture_copy.write_text(fixture_source, encoding="utf-8")
        module.patch_client_mobile_settings_layout(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert module.MOBILE_SETTINGS_LAYOUT_MARKER in patched
        assert "@media (max-width:600px)" in patched
        assert module.MOBILE_SETTINGS_LAYOUT_MARKER in fixture_copy.read_text(encoding="utf-8")

        fixture.write_text(patched.replace(".VOzbGW_navTitle{padding:0 4px}", "", 1), encoding="utf-8")
        try:
            module.patch_client_mobile_settings_layout(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("modified mobile settings CSS should fail the build")
        fixture.write_text(patched, encoding="utf-8")

        partial = Path(str(fixture).replace("@fixture", "@fixture-partial"))
        partial.parent.mkdir(parents=True)
        partial.write_text(f"/* {module.MOBILE_SETTINGS_LAYOUT_MARKER} */", encoding="utf-8")
        try:
            module.patch_client_mobile_settings_layout(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("partial mobile settings marker should fail the build")

    with tempfile.TemporaryDirectory(prefix="dsh-client-tool-details-action-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-client-ui-tool@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-ui-tool"
            / "lib"
            / "client.js"
        )
        fixture.parent.mkdir(parents=True)
        fixture_source = (
            "window.__dshFixture = () => {\n"
            '\t\tconst css$2 = ".fixture:before{content:\\"\\"}.ztWv_q_callRow{border-radius:6px}";\n'
            '\t\tconst tagId$2 = "@deepseek-ai/dsh-client-ui-tool/ToolCallTree.module.css";\n'
            "\t\tvar ToolCallTree_module_css_default = {\n"
            '\t\t\t"callRow": "ztWv_q_callRow",\n'
            '\t\t\t"subCalls": "ztWv_q_subCalls"\n'
            "\t\t};\n"
            "\t\tconst fixtureInspectIcon = "
            "_deepseek_ai_dsh_client_ui_primitives.IconInspectOutline12;\n"
            "\t\tconst fixtureSampleInspectIcon = "
            "_deepseek_ai_dsh_client_ui_primitives.IconInspectOutline12;\n"
            "\t\tconst ToolCall = (0, react.memo)(function ToolCall() {\n"
            "\t\t\treturn (0, react_jsx_runtime.jsxs)(\"div\", {\n"
            "\t\t\t\tclassName: ToolCallTree_module_css_default.callRow,\n"
            '\t\t\t\t"data-chat-call-id": callId,\n'
            "\t\t\t\tchildren: [renderSlot(\"tool.call.toolview\", owner, {\n"
            "\t\t\t\t\tentryKey: toolName,\n"
            "\t\t\t\t\tfallback: (0, react_jsx_runtime.jsx)(GenericToolCard, {\n"
            "\t\t\t\t\t\t...owner,\n"
            "\t\t\t\t\t\tt\n"
            "\t\t\t\t\t})\n"
            "\t\t\t\t}), children]\n"
            "\t\t\t});\n"
            "\t\t});\n"
            "};\n"
        )
        fixture.write_text(fixture_source, encoding="utf-8")
        module.patch_client_tool_details_action(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert patched.count(module.CLIENT_TOOL_DETAILS_ACTION_MARKER) == 1
        assert patched.count('"data-dsh-open-tool-details": ""') == 1
        assert patched.count('"detailsButton": "ztWv_q_detailsButton"') == 1
        assert patched.count("_deepseek_ai_dsh_client_ui_primitives.IconInspectOutline12") == 3
        assert "content:\\\"\\\"" in patched
        assert_node_syntax(fixture)

        module.patch_client_tool_details_action(Path(directory))
        assert fixture.read_text(encoding="utf-8") == patched

        fixture.write_text(
            patched.replace('\t\t\t\t\t"data-dsh-open-tool-details": "",\n', "", 1),
            encoding="utf-8",
        )
        try:
            module.patch_client_tool_details_action(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("modified Tool details action should fail the build")
        fixture.write_text(patched, encoding="utf-8")

        partial = Path(str(fixture).replace("@fixture", "@fixture-partial"))
        partial.parent.mkdir(parents=True)
        partial.write_text(
            f"/* {module.CLIENT_TOOL_DETAILS_ACTION_MARKER} */\n",
            encoding="utf-8",
        )
        try:
            module.patch_client_tool_details_action(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("partial Tool details action marker should fail the build")

    with tempfile.TemporaryDirectory(prefix="dsh-client-tool-details-entry-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-client-ui-conversation@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-ui-conversation"
            / "lib"
            / "client.js"
        )
        fixture.parent.mkdir(parents=True)
        fixture_source = (
            "function ChatView({ useSession, useSessions, useStore, renderSlot, "
            "sessionId, openFile, loadOlder, loadImage, inspectCall, chatScroll, "
            "forkAt, fileMentions, t }) {\n"
            "\t\t\tconst listRef = (0, react.useRef)(null);\n"
            "\t\t\treturn (0, react_jsx_runtime.jsx)(\"div\", {\n"
            "\t\t\t\tchildren: (0, react_jsx_runtime.jsxs)(\"div\", {\n"
            "\t\t\t\t\tref: listRef,\n"
            "\t\t\t\t\tclassName: ChatView_module_css_default.scroll,\n"
            "\t\t\t\t\tchildren: []\n"
            "\t\t\t\t})\n"
            "\t\t\t});\n"
            "}\n"
            "const injected = {\n"
            "\t\t\t\t\t\topenDetails: (target) => {\n"
            "\t\t\t\t\t\t\tactions.select(target);\n"
            "\t\t\t\t\t\t\tlayout.openDetails();\n"
            "\t\t\t\t\t\t},\n"
            "};\n"
        )
        fixture.write_text(fixture_source, encoding="utf-8")
        module.patch_client_tool_details_entry(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert patched.count(module.CLIENT_TOOL_DETAILS_ENTRY_MARKER) == 1
        assert "sessionId, openFile, openDetails, loadOlder" in patched
        assert 'target.closest("[data-dsh-open-tool-details]")' in patched
        assert "callId.length > 256" in patched
        assert patched.count("onClick: openToolDetails") == 1
        assert "onKeyDown: openToolDetails" not in patched
        assert "openDetails({" in patched
        assert_node_syntax(fixture)

        module.patch_client_tool_details_entry(Path(directory))
        assert fixture.read_text(encoding="utf-8") == patched

        fixture.write_text(
            patched.replace("\t\t\t\t\tonClick: openToolDetails,\n", "", 1),
            encoding="utf-8",
        )
        try:
            module.patch_client_tool_details_entry(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("modified Tool details entry patch should fail the build")
        fixture.write_text(patched, encoding="utf-8")

        partial = Path(str(fixture).replace("@fixture", "@fixture-partial"))
        partial.parent.mkdir(parents=True)
        partial.write_text(
            f"/* {module.CLIENT_TOOL_DETAILS_ENTRY_MARKER} */\n",
            encoding="utf-8",
        )
        try:
            module.patch_client_tool_details_entry(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("partial Tool details entry marker should fail the build")

    with tempfile.TemporaryDirectory(prefix="dsh-mobile-tool-details-layout-") as directory:
        fixture = (
            Path(directory)
            / "node_modules"
            / ".pnpm"
            / "@deepseek-ai+dsh-client-ui-layout@fixture"
            / "node_modules"
            / "@deepseek-ai"
            / "dsh-client-ui-layout"
            / "lib"
            / "client.js"
        )
        fixture.parent.mkdir(parents=True)
        fixture_source = (
            'const css = ".fixture:before{content:\\"\\"}.pI_x6G_frame{display:grid}";\n'
            '\t\tconst tagId = "@deepseek-ai/dsh-client-ui-layout/AppFrame.module.css";\n'
            "function DetailsColumn(props) {\n"
            "\t\t\treturn (0, react_jsx_runtime.jsx)(\"div\", {\n"
            "\t\t\t\tclassName: AppFrame_module_css_default.detailsCol,\n"
            "\t\t\t\tchildren: props.children\n"
            "\t\t\t});\n"
            "}\n"
            "function AppFrame() {\n"
            "\t\t\tconst cols = computeColumns(viewport, sidebarCollapsed ? 0 : "
            "panels.sidebar === 0 ? 280 : panels.sidebar, detailsSession === void 0 "
            "? 0 : panels.details);\n"
            "\t\t\treturn (0, react_jsx_runtime.jsxs)(\"div\", {\n"
            "\t\t\t\tstyle: { gridTemplateColumns: "
            "`${cols.sidebar}px minmax(0, 1fr) ${cols.details}px` },\n"
            "\t\t\t\t\"data-sidebar-collapsed\": sidebarCollapsed || void 0,\n"
            "\t\t\t\t\"data-details-collapsed\": cols.details === 0 || void 0,\n"
            "\t\t\t\tchildren: []\n"
            "\t\t\t});\n"
            "}\n"
        )
        fixture.write_text(fixture_source, encoding="utf-8")
        module.patch_client_mobile_tool_details_layout(Path(directory))
        patched = fixture.read_text(encoding="utf-8")
        assert patched.count(module.MOBILE_TOOL_DETAILS_LAYOUT_MARKER) == 1
        assert patched.count('"data-dsh-layout-frame": ""') == 1
        assert patched.count('"data-dsh-details-column": ""') == 1
        assert "const detailsOverlay = detailsSession !== void 0" in patched
        assert "[data-dsh-layout-frame][data-details-overlay]" in patched
        assert "@media (max-width:600px)" in patched
        assert "content:\\\"\\\"" in patched
        assert_node_syntax(fixture)

        module.patch_client_mobile_tool_details_layout(Path(directory))
        assert fixture.read_text(encoding="utf-8") == patched

        fixture.write_text(
            patched.replace("box-shadow:-8px 0 28px rgba(0,0,0,.16);", "", 1),
            encoding="utf-8",
        )
        try:
            module.patch_client_mobile_tool_details_layout(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("modified mobile Tool details CSS should fail the build")
        fixture.write_text(patched, encoding="utf-8")

        partial = Path(str(fixture).replace("@fixture", "@fixture-partial"))
        partial.parent.mkdir(parents=True)
        partial.write_text(
            f"/* {module.MOBILE_TOOL_DETAILS_LAYOUT_MARKER} */\n",
            encoding="utf-8",
        )
        try:
            module.patch_client_mobile_tool_details_layout(Path(directory))
        except BuildError:
            pass
        else:
            raise AssertionError("partial mobile Tool details marker should fail the build")

    print("selfcheck OK: mobile profile + runtime patches + path normalization")
    return 0


if __name__ == "__main__":
    sys.exit(main())
