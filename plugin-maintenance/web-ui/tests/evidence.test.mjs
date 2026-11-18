import assert from 'node:assert/strict'
import { test } from 'node:test'
import { evidenceFields, mediaObservationLabel, phaseSummary } from '../src/evidence.ts'

test('media label uses only bounded HTTP observations, never the source verdict or media address', () => {
  assert.equal(mediaObservationLabel(), '未观察')
  assert.equal(mediaObservationLabel([{ mediaStatus: 'prefix_returned', url: 'PRIVATE' }]), '未观察')
  const event = { rangeRequested: true, responseStatus: 200, responseBytes: 151, prefixFormat: 'hls',
    url: 'https://PRIVATE/?token=PRIVATE' }
  assert.equal(mediaObservationLabel([event]), 'HLS 清单 · HTTP 200 · 151 字节')
  assert.equal(mediaObservationLabel([event, { ...event, responseStatus: 206, responseBytes: 65536,
    prefixFormat: 'mpeg_ts' }]), 'MPEG-TS · HTTP 206 · 65536 字节')
  for (const change of [{ responseBytes: 65537 }, { responseBytes: -1 }, { responseBytes: 1.5 },
    { responseStatus: 502 }, { prefixFormat: 'PRIVATE' }, { prefixFormat: 'constructor' },
    { rangeRequested: false }]) {
    assert.equal(mediaObservationLabel([{ ...event, ...change }]), '未观察')
  }
})

test('missing and unknown statuses stay untested; no invented pass', () => {
  const definitions = ['S01', 'S02', 'S03', 'C01'].map(id => ({ id, title: id }))
  const nodes = [{ id: 'S01', status: 'pass' }, { id: 'S02', status: 'untrusted' },
    { id: 'C01', status: 'pass' }, { id: 'S99', status: 'pass' }]
  assert.deepEqual(phaseSummary(definitions, nodes, 'S'),
    { total: 3, pass: 1, fail: 0, blocked: 0, not_tested: 2 })
})
test('duplicate node IDs cannot inflate the pass count', () => {
  assert.deepEqual(phaseSummary([{ id: 'S01' }], [{ id: 'S01', status: 'pass' }, { id: 'S01', status: 'pass' }], 'S'),
    { total: 1, pass: 0, fail: 0, blocked: 0, not_tested: 1 })
})
test('fail, blocked and not_tested stay distinct', () => {
  const statuses = ['fail', 'blocked', 'not_tested']
  const definitions = statuses.map((status, i) => ({ id: `C0${i}` }))
  assert.deepEqual(phaseSummary(definitions, definitions.map((node, i) => ({ ...node, status: statuses[i] })), 'C'),
    { total: 3, pass: 0, fail: 1, blocked: 1, not_tested: 1 })
})
test('evidence data stays text, preserves primitive values and hides named credentials', () => {
  const fields = evidenceFields({ success: false, count: 0, value: null, source: '<script>alert(1)</script>',
    headers: { Authorization: 'PRIVATE_AUTH', 'X-API-KEY': 'PRIVATE_KEY' }, password: 'PRIVATE_PASSWORD' })
  const serialized = JSON.stringify(fields)
  assert.match(serialized, /success: false/)
  assert.match(serialized, /count: 0/)
  assert.match(serialized, /value: null/)
  assert.match(serialized, /<script>/)
  assert.doesNotMatch(serialized, /PRIVATE_AUTH|PRIVATE_KEY|PRIVATE_PASSWORD/)
})
test('large, deep and long evidence is display bounded; absent evidence remains absent', () => {
  assert.deepEqual(evidenceFields(undefined), [])
  assert.ok(JSON.stringify(evidenceFields({ value: 'x'.repeat(10000) })).length < 1600)
  let deep = {}
  for (let i = 0; i < 20; i++) deep = { child: deep }
  assert.match(JSON.stringify(evidenceFields(deep)), /展示深度/)
  const large = Object.fromEntries(Array.from({ length: 1000 }, (_, i) => [i, i]))
  assert.ok(evidenceFields(large)[0].children.length <= 201)
})
