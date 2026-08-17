#!/usr/bin/env python3
"""Self-check for build-embedded-runtime.py mobile-profile integration (no rootfs inputs required)."""
from __future__ import annotations

import importlib.util
import json
import sys
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
    print("selfcheck OK: mobile-profile validation + path normalization")
    return 0


if __name__ == "__main__":
    sys.exit(main())
