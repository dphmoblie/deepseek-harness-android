#!/usr/bin/env python3
"""Create the deterministic manifest for release source component archives."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    assets = []
    for path in sorted(args.dir.iterdir()):
        if path.name in {"OFFER.md", "SOURCE_MANIFEST.json"}:
            continue
        if not path.is_file() or path.stat().st_size == 0:
            raise SystemExit(f"source asset is missing or empty: {path.name}")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        assets.append({"name": path.name, "sha256": digest.hexdigest(), "bytes": path.stat().st_size})
    if len(assets) != 3:
        raise SystemExit(f"expected exactly three source component archives, found {len(assets)}")
    args.out.write_text(json.dumps({"schemaVersion": 1, "assets": assets}, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    main()
