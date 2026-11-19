import assert from 'node:assert/strict'
import { test } from 'node:test'
import { countKeys, readBusinessComparison, validateBusinessComparison } from '../../src/maintenance/businessSnapshot.ts'

function fixture() {
  const sample = {
    sourceSha256: 'a'.repeat(64), imageId: 'sha256:' + 'b'.repeat(64), runtimeSha256: 'c'.repeat(64),
    receiptSha256: 'd'.repeat(64), jobId: '11111111-1111-1111-1111-111111111111', containerId: 'e'.repeat(64),
    observedAt: '2026-11-09T08:00:00Z', status: 'home_empty',
    counts: Object.fromEntries(countKeys.map(key => [key, 0])), fixturePassed: false, deniedCodes: [],
    http: [{ page: 'home', responseStatus: 200, responseBytes: 100, responseSha256: 'f'.repeat(64), elapsedMilliseconds: 20 }],
    limits: { maxResponseBytes: 2097152, maxTotalResponseBytes: 4194304, maxRequests: 6 },
    controls: { network: 'none', uid: 65532, cleanupConfirmed: true, containerAbsent: true }
  }
  return { schemaVersion: 1, kind: 'nas-t4-business-comparison', evidenceClass: 'untrusted_source_output',
    baseline: structuredClone(sample), candidate: structuredClone(sample) }
}
test('read comparison preserves zero, null and untrusted classification', async () => {
  const value = fixture()
  value.candidate.counts.homeItems = 20
  value.candidate.counts.detailItems = null
  const result = await readBusinessComparison(new Blob([JSON.stringify(value)]))
  assert.deepEqual(result.comparison, value)
  assert.match(result.fileSha256, /^[a-f0-9]{64}$/)
  assert.equal(result.comparison.evidenceClass, 'untrusted_source_output')
  assert.equal(result.comparison.baseline.counts.homeItems, 0)
  assert.equal(result.comparison.candidate.counts.detailItems, null)
})
test('credentials, arbitrary text and trust promotion fields are refused', () => {
  for (const key of ['Authorization', 'url', 'token', 'businessVerified', '__proto__']) {
    const value = fixture()
    Object.defineProperty(value.candidate, key, { value: 'PRIVATE', enumerable: true })
    assert.throws(() => validateBusinessComparison(value))
  }
  const value = fixture()
  value.evidenceClass = 'trusted'
  assert.throws(() => validateBusinessComparison(value))
})
test('counts, HTTP details and identities have fixed types and bounds', () => {
  for (const count of [-1, true, 1.2, 10001, '<script>']) {
    const value = fixture()
    value.candidate.counts.homeItems = count
    assert.throws(() => validateBusinessComparison(value))
  }
  const value = fixture()
  value.candidate.http[0].page = 'https://private.test'
  assert.throws(() => validateBusinessComparison(value))
  value.candidate.http[0].page = 'home'
  value.candidate.jobId = 'not-a-job'
  assert.throws(() => validateBusinessComparison(value))
})
test('incomplete cleanup, network access and invented denial codes cannot be imported', () => {
  for (const change of [{ network: 'bridge' }, { uid: 0 }, { cleanupConfirmed: false }, { containerAbsent: false }]) {
    const value = fixture()
    Object.assign(value.candidate.controls, change)
    assert.throws(() => validateBusinessComparison(value))
  }
  const value = fixture()
  value.candidate.deniedCodes = ['PRIVATE']
  assert.throws(() => validateBusinessComparison(value))
})
test('declared response budgets also bound the observed response array', () => {
  const value = fixture()
  value.candidate.limits.maxResponseBytes = 99
  assert.throws(() => validateBusinessComparison(value))
  value.candidate.limits.maxResponseBytes = 100
  value.candidate.limits.maxTotalResponseBytes = 99
  assert.throws(() => validateBusinessComparison(value))
  value.candidate.limits.maxTotalResponseBytes = 100
  assert.doesNotThrow(() => validateBusinessComparison(value))
})
test('file read is bounded and rejects broken encoding or unsupported JSON shape', async () => {
  for (const blob of [new Blob([]), new Blob(['x'.repeat(131073)]), new Blob([new Uint8Array([255])]),
    new Blob(['[]']), new Blob(['{"schemaVersion":1}'])]) {
    await assert.rejects(() => readBusinessComparison(blob))
  }
})
