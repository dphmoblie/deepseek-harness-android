import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const appRoot = join(import.meta.dirname, '..')
const verifier = join(appRoot, 'scripts', 'verify-corresponding-source.py')
const python = process.platform === 'win32' ? 'python' : 'python3'

function createArchive(path, traversal = false) {
  const script = String.raw`
import io, json, tarfile, hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1])
payload = {
  'proot-corresponding-source.tar.gz': b'proot source',
  'ubuntu-git-corresponding-source.tar.gz': b'git source',
  'build-and-install-materials.tar.gz': b'build instructions',
}
manifest = {'schemaVersion': 1, 'assets': [
  {'name': name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
  for name, data in payload.items()
]}
with tarfile.open(path, 'w:gz') as out:
  for name, data in {**payload, 'SOURCE_MANIFEST.json': json.dumps(manifest).encode(), 'OFFER.md': b'offer'}.items():
    info = tarfile.TarInfo('../escape' if ${traversal ? 'True' : 'False'} and name == 'OFFER.md' else name)
    info.size = len(data)
    out.addfile(info, io.BytesIO(data))
`
  return spawnSync(python, ['-c', script, path], { encoding: 'utf8', windowsHide: true })
}

test('accepts a complete corresponding-source archive', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-source-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const archive = join(dir, 'source.tar.gz')
  assert.equal(createArchive(archive).status, 0)
  const result = spawnSync(python, [verifier, '--archive', archive], { encoding: 'utf8', windowsHide: true })
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /CORRESPONDING_SOURCE_OK/u)
})

test('rejects path traversal in a corresponding-source archive', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsh-source-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const archive = join(dir, 'source.tar.gz')
  assert.equal(createArchive(archive, true).status, 0)
  const result = spawnSync(python, [verifier, '--archive', archive], { encoding: 'utf8', windowsHide: true })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /path escapes/u)
})
