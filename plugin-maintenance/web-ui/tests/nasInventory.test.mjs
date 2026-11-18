import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readInventory, validateInventory, inventoryLimits } from '../src/nasInventory.ts'

const sha = 'a'.repeat(64)
const entry = { path: '中文/source.py', kind: 'python', bytes: 5, sha256: sha, status: 'hashed' }
function fixture(files = [entry]) {
  return {
    schemaVersion: 1, kind: 'nas_plugin_directory_inventory', authority: 'ssh_host_read_only',
    observedAt: '2026-11-09T04:00:00+00:00',
    owner: { target: 't4_custom', containerName: 'alist-tvbox-t4-sidecar-t4-sidecar-1',
      containerId: sha, imageId: 'sha256:' + sha, containerDirectory: '/data/plugins',
      mountDestination: '/data', mountSource: '/vol1/volume', hostDirectory: '/vol1/volume/plugins' },
    scan: { complete: true, visitedEntries: files.length + 1, ignoredEntries: 1,
      sourceBytesRead: 5, limits: { ...inventoryLimits }, blockedDirectories: 0 },
    files: structuredClone(files), scannerSha256: sha, productionIdentityUnchanged: true,
    sourceExecuted: false, contentsStored: false, modelCalled: false, published: false
  }
}
function file(value) {
  const bytes = value instanceof Uint8Array ? value : new TextEncoder().encode(JSON.stringify(value))
  return { size: bytes.byteLength, arrayBuffer: async () => bytes.buffer.slice(0) }
}
test('bounded inventory keeps raw-byte identity and Chinese paths without execution', async () => {
  const result = await readInventory(file(fixture()))
  assert.equal(result.inventory.files[0].path, entry.path)
  assert.match(result.fileSha256, /^[a-f0-9]{64}$/)
  assert.equal(result.inventory.sourceExecuted, false)
})
test('empty T4 directory and independent AT static owner remain distinct', () => {
  const t4 = fixture([])
  t4.scan.sourceBytesRead = 0
  assert.equal(validateInventory(t4).files.length, 0)
  const at = fixture()
  Object.assign(at.owner, { target: 'at_static', containerName: 'xiaoya-tvbox',
    containerDirectory: '/www/static', mountDestination: '/www/static', hostDirectory: '/vol1/volume' })
  assert.equal(validateInventory(at).owner.target, 'at_static')
})
test('unknown fields cannot carry configuration, bodies or credentials', () => {
  for (const scope of ['root', 'owner', 'scan', 'file']) {
    const value = fixture()
    const target = scope === 'root' ? value : scope === 'file' ? value.files[0] : value[scope]
    target.secret = 'PRIVATE_SENTINEL'
    assert.throws(() => validateInventory(value), error => !error.message.includes('PRIVATE_SENTINEL'))
  }
})
test('execution, model, contents and publication claims are refused', () => {
  for (const key of ['sourceExecuted', 'contentsStored', 'modelCalled', 'published']) {
    const value = fixture()
    value[key] = true
    assert.throws(() => validateInventory(value))
  }
})
test('wrong owner, image and host/container mapping cannot validate', () => {
  for (const change of [{ containerId: 'short' }, { imageId: 'latest' },
    { hostDirectory: '/other/plugins' }, { containerDirectory: '/data/../secret' },
    { containerName: 'other' }, { mountDestination: '/www' }, { target: 'unknown' }]) {
    const value = fixture()
    Object.assign(value.owner, change)
    assert.throws(() => validateInventory(value))
  }
})
test('traversal, absolute paths, controls, backslash and duplicate files refused', () => {
  for (const path of ['../a.py', '/a.py', 'dir//a.py', 'dir/./a.py', 'a\\b.py', 'a\0.py', 'a\u202e.py']) {
    const value = fixture()
    value.files[0].path = path
    assert.throws(() => validateInventory(value))
  }
  const value = fixture([entry, entry])
  value.scan.sourceBytesRead = 10
  assert.throws(() => validateInventory(value))
})
test('blocked entries require null hashes; no status is promoted to a runtime pass', () => {
  const value = fixture([{ ...entry, status: 'symlink', sha256: null }])
  assert.equal(validateInventory(value).files[0].status, 'symlink')
  value.files[0].sha256 = sha
  assert.throws(() => validateInventory(value))
  value.files[0].status = 'pass'
  assert.throws(() => validateInventory(value))
})
test('limits, invalid counters and sum mismatches fail before rendering', () => {
  for (const changes of [{ visitedEntries: 2001 }, { sourceBytesRead: 4 }, { ignoredEntries: 2 },
    { visitedEntries: -1 }, { sourceBytesRead: NaN }, { complete: 'true' }]) {
    const value = fixture()
    Object.assign(value.scan, changes)
    assert.throws(() => validateInventory(value))
  }
  const value = fixture()
  value.scan.limits.totalBytes *= 2
  assert.throws(() => validateInventory(value))
})
test('per-file byte limits, wrong type and malformed hashes are rejected', () => {
  for (const changes of [{ bytes: 1048577 }, { bytes: 0 }, { sha256: null },
    { kind: 'javascript' }, { sha256: 'not-a-hash' }, { bytes: '5' }]) {
    const value = fixture()
    Object.assign(value.files[0], changes)
    assert.throws(() => validateInventory(value))
  }
})
test('oversize, size mismatch, invalid UTF8 and JSON never become previews', async () => {
  await assert.rejects(readInventory({ size: 1048577, arrayBuffer: () => { throw Error('must not read') } }), /1 MiB/)
  await assert.rejects(readInventory({ ...file(fixture()), size: 3 }))
  await assert.rejects(readInventory(file(new Uint8Array([0xff, 0xfe]))), /UTF-8 JSON/)
  await assert.rejects(readInventory(file(new TextEncoder().encode('{bad'))), /UTF-8 JSON/)
})
test('incomplete snapshots stay incomplete and are never silently completed', () => {
  const value = fixture()
  value.scan.complete = false
  assert.equal(validateInventory(value).scan.complete, false)
})
