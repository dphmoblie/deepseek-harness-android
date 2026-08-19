#!/usr/bin/env python3
"""Self-check for build-embedded-runtime.py mobile-profile integration (no rootfs inputs required)."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent


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

    print("selfcheck OK: mobile-profile validation + path normalization")
    return 0


if __name__ == "__main__":
    sys.exit(main())
