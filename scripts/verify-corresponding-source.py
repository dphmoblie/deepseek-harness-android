#!/usr/bin/env python3
"""Validate the release's machine-readable corresponding-source archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import sys
import tarfile
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"CORRESPONDING_SOURCE_FAILED: {message}")


def safe_name(name: str) -> None:
    normalized = posixpath.normpath(name)
    if name.startswith("/") or normalized in {"", ".", ".."} or normalized.startswith("../"):
        fail(f"archive member path escapes its root: {name!r}")


def sha256_member(source) -> str:
    digest = hashlib.sha256()
    while chunk := source.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args()
    archive = args.archive
    if not archive.is_file() or archive.stat().st_size == 0:
        fail(f"archive is missing or empty: {archive}")

    try:
        with tarfile.open(archive, "r:*") as bundle:
            members = bundle.getmembers()
            if not members:
                fail("archive has no members")
            names: set[str] = set()
            for member in members:
                safe_name(member.name)
                if member.name in names:
                    fail(f"duplicate archive member: {member.name!r}")
                names.add(member.name)
                if member.islnk() or member.issym():
                    fail(f"archive must not contain links: {member.name!r}")
            required = {
                "SOURCE_MANIFEST.json",
                "OFFER.md",
                "proot-corresponding-source.tar.gz",
                "ubuntu-git-corresponding-source.tar.gz",
                "build-and-install-materials.tar.gz",
            }
            missing = sorted(required - names)
            if missing:
                fail("archive is missing required members: " + ", ".join(missing))
            manifest_member = bundle.getmember("SOURCE_MANIFEST.json")
            if not manifest_member.isfile() or manifest_member.size > 1024 * 1024:
                fail("SOURCE_MANIFEST.json is missing or too large")
            raw_manifest = bundle.extractfile(manifest_member)
            if raw_manifest is None:
                fail("cannot read SOURCE_MANIFEST.json")
            try:
                manifest = json.load(raw_manifest)
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                fail(f"SOURCE_MANIFEST.json is not valid JSON: {error}")
            if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
                fail("SOURCE_MANIFEST.json has an unsupported schema")
            assets = manifest.get("assets")
            if not isinstance(assets, list) or not assets:
                fail("SOURCE_MANIFEST.json has no assets")
            declared = set()
            for entry in assets:
                if not isinstance(entry, dict):
                    fail("SOURCE_MANIFEST.json asset entry is not an object")
                name = entry.get("name")
                digest = entry.get("sha256")
                size = entry.get("bytes")
                if not isinstance(name, str) or name not in names or name in {"SOURCE_MANIFEST.json", "OFFER.md"}:
                    fail(f"invalid or undeclared source asset: {name!r}")
                if name in declared or not isinstance(digest, str) or len(digest) != 64 or not isinstance(size, int) or size <= 0:
                    fail(f"invalid source asset metadata: {entry!r}")
                declared.add(name)
                member = bundle.getmember(name)
                if not member.isfile() or member.size != size:
                    fail(f"source asset size mismatch: {name}")
                source = bundle.extractfile(member)
                if source is None:
                    fail(f"cannot read source asset: {name}")
                actual = sha256_member(source)
                if actual != digest.lower():
                    fail(f"source asset digest mismatch: {name}")
            expected_assets = names - {"SOURCE_MANIFEST.json", "OFFER.md"}
            if declared != expected_assets:
                fail("SOURCE_MANIFEST.json does not cover every source asset")
    except (tarfile.TarError, OSError) as error:
        fail(f"cannot inspect archive: {error}")
    print(f"CORRESPONDING_SOURCE_OK: archive={archive} assets={len(declared)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except OSError as error:
        fail(str(error))
