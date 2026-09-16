import { spawnSync } from 'node:child_process'
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { resolve } from 'node:path'

test('runtime rebuild repairs native tools and verification rejects unusable executables', () => {
  const probe = String.raw`
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path('scripts') / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

rebuild = load('rebuild', 'rebuild-rootfs-frontend.py')
verify = load('verify', 'verify-bundle.py')
rebuild.ARGS = SimpleNamespace(compression_level=1)
rg = 'opt/dsh/node_modules/.pnpm/@vscode+ripgrep-linux-arm64@1.18.0/node_modules/@vscode/ripgrep-linux-arm64/bin/rg'
landlock = 'opt/dsh/node_modules/.pnpm/@deepseek-ai+node-addon-system-linux-arm64@0.1.2/node_modules/@deepseek-ai/node-addon-system-linux-arm64/bin/landlock-run'
dist_path = 'opt/dsh/node_modules/.pnpm/frontend/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html'
# Minimal ELF header: the verifier checks type/class/machine, not program behavior.
elf = bytearray(64)
elf[:7] = b'\x7fELF\x02\x01\x01'
elf[16:20] = bytes([3, 0, 183, 0])
elf = bytes(elf)
ordinary = [rg + '.js', landlock + '.txt', 'opt/dsh/assets/rg', 'tmp/' + rg]

def add(archive, name, content, mode):
    info = tarfile.TarInfo(name)
    info.size = len(content)
    info.mode = mode
    archive.addfile(info, io.BytesIO(content))

with tempfile.TemporaryDirectory(prefix='dsh-tool-modes-') as directory:
    root = Path(directory)
    source = root / 'old.bundle'
    repaired = root / 'repaired.bundle'
    dist = root / 'dist'
    dist.mkdir()
    (dist / 'index.html').write_bytes(b'<div id="root">new frontend</div>')
    with tarfile.open(source, 'w:gz') as archive:
        add(archive, 'usr/bin/bash', elf, 0o755)
        for name in (rg, landlock):
            add(archive, name, elf, 0o644)
        for name in ordinary:
            add(archive, name, b'data', 0o644)
        add(archive, dist_path, b'old frontend', 0o644)
        add(archive, rebuild.RUNTIME_METADATA_PATH, json.dumps({'runtimeVersion': 'old'}).encode(), 0o644)
    stats = rebuild.stream_rebuild(source, repaired, dist, [], 'new')
    rebuild.verify_rebuilt(repaired, rebuild.collect_expected(dist, stats['distRoot'], []), {}, 'new')
    with tarfile.open(repaired, 'r:gz') as archive:
        for name in ('usr/bin/bash', rg, landlock):
            member = archive.getmember(name)
            assert member.mode == 0o755, name
            assert archive.extractfile(member).read() == elf, 'binary bytes changed'
            verify.validate_runtime_executable(member, elf)
            for bad_mode, header in ((0o644, elf), (0o777, elf), (0o755, b'not ELF'), (0o755, elf[:18] + b'\x3e\x00' + elf[20:])):
                member.mode = bad_mode
                try:
                    verify.validate_runtime_executable(member, header)
                except ValueError:
                    pass
                else:
                    raise AssertionError('bad executable accepted')
        for name in ordinary:
            assert archive.getmember(name).mode == 0o644, name
            assert verify.runtime_executable_name(name) is None, name
    assert verify.runtime_executable_name(rg) == 'ripgrep'
    assert verify.runtime_executable_name(landlock) == 'landlock-run'
    assert verify.runtime_executable_name('usr/bin/bash') == 'bash'
    # N-4: usr/local/bin is three levels deep, so reaching opt/python/bin needs three '..'.
    # The table used to carry the two-level form and therefore *agreed* with the wrong
    # build script: the verifier compared a wrong expectation against a wrong artifact and
    # passed, while PATH python3 was a dangling link on the device. Pin the exact expected
    # targets, and pin the build call site too, so the two cannot drift back together.
    table = dict(verify.REQUIRED_SYMLINKS)
    assert table['usr/local/bin/python3'] == '../../../opt/python/bin/python3'
    assert table['usr/local/bin/python'] == '../../../opt/python/bin/python3'
    # node entries were always correct; keep them pinned so a "fix" cannot touch them.
    assert table['usr/local/bin/node'] == '../../../opt/node/bin/node'
    assert table['usr/local/bin/npm'] == '../../../opt/node/bin/npm'
    assert '../../opt/python/bin/python3' not in table.values(), 'two-level python target is back in the table'
    build_source = Path('scripts/build-embedded-runtime.py').read_text(encoding='utf-8')
    assert 'add_symlink("usr/local/bin/python3", "../../../opt/python/bin/python3")' in build_source
    assert 'add_symlink("usr/local/bin/python", "../../../opt/python/bin/python3")' in build_source
    assert '"../../opt/python/bin/python3"' not in build_source, 'two-level relative target is back'
    symlink = tarfile.TarInfo(rg)
    symlink.type = tarfile.SYMTYPE
    symlink.mode = 0o755
    symlink.size = 64
    try:
        verify.validate_runtime_executable(symlink, elf)
    except ValueError:
        pass
    else:
        raise AssertionError('symlink accepted as executable payload')
    # The full CLI must reject missing tools even if the remaining ELF is valid.
    manifest = root / 'manifest.json'
    manifest.write_text(json.dumps({'rootfs': {'extractedBytes': 64}}))
    for present, missing in (('usr/bin/bash', 'landlock-run, ripgrep'), (rg, 'bash, landlock-run'), (landlock, 'bash, ripgrep')):
        incomplete = root / 'incomplete.bundle'
        with tarfile.open(incomplete, 'w:gz') as archive:
            add(archive, present, elf, 0o755)
        result = subprocess.run([sys.executable, 'scripts/verify-bundle.py', '--bundle', str(incomplete), '--manifest', str(manifest)], capture_output=True, text=True, timeout=5)
        assert result.returncode != 0
        assert 'required runtime executables missing: ' + missing in result.stderr, result.stderr
`
  const result = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', probe], {
    cwd: resolve(import.meta.dirname, '..'),
    encoding: 'utf8',
    timeout: 20_000,
    windowsHide: true,
    env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  })
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr || result.stdout)
})
