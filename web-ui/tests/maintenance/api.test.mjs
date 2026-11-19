import assert from 'node:assert/strict'
import { test } from 'node:test'

globalThis.window = { location: { pathname: '/' }, localStorage: { getItem() { return 'fixture-session' } } }

test('GET stays read-only; POST and PUT share the existing CSRF contract', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, ...options })
    return new Response(JSON.stringify(url.endsWith('/csrf')
      ? { token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' } : { ok: true }),
    { headers: { 'Content-Type': 'application/json' } })
  })
  const { request } = await import('../../src/maintenance/api.ts?csrf-methods')
  await request('/model/connection')
  assert.equal(calls[0].method, 'GET')
  assert.equal(calls[0].body, undefined)
  await request('/sources/fixture/model-consent', { artifactSha256: 'a'.repeat(64) }, 'PUT')
  assert.equal(calls[1].url, '/api/plugin-maintenance/csrf')
  assert.equal(calls[2].method, 'PUT')
  assert.equal(calls[2].credentials, 'same-origin')
  assert.equal(calls[2].headers['X-CSRF-TOKEN'], 'fixture-csrf')
  await request('/repairs/fixture/model-calls', { autoSubmit: false })
  assert.equal(calls[4].method, 'POST')
  assert.equal(calls[4].headers['X-CSRF-TOKEN'], 'fixture-csrf')
  assert.equal(calls.filter(call => call.url.endsWith('/csrf')).length, 2)
})

test('a rejected write clears CSRF and is never retried automatically', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, ...options })
    return new Response(JSON.stringify(url.endsWith('/csrf')
      ? { token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' } : { code: 'FIXTURE_FORBIDDEN' }),
    { status: url.endsWith('/csrf') ? 200 : 403, headers: { 'Content-Type': 'application/json' } })
  })
  const { request } = await import('../../src/maintenance/api.ts?csrf-denied')
  await assert.rejects(request('/sources/fixture/model-consent', {}, 'PUT'), /FIXTURE_FORBIDDEN/)
  assert.equal(calls.length, 2)
  await assert.rejects(request('/sources/fixture/model-consent', {}, 'PUT'), /FIXTURE_FORBIDDEN/)
  assert.equal(calls.filter(call => call.url.endsWith('/csrf')).length, 2)
})

test('a lost model-request response is surfaced without an automatic second POST', async t => {
  let posts = 0
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.endsWith('/csrf')) return new Response(JSON.stringify({ token: 'fixture', headerName: 'X-CSRF' }))
    if (options.method === 'POST') posts++
    throw new TypeError('FIXTURE_RESPONSE_LOST')
  })
  const { request } = await import('../../src/maintenance/api.ts?response-lost')
  await assert.rejects(request('/repairs/fixture/model-calls', { requestKey: 'same-key' }), /FIXTURE_RESPONSE_LOST/)
  assert.equal(posts, 1)
})

test('DNS preparation failures explain that no crawler or repair started without retrying', async t => {
  const { request } = await import('../../src/maintenance/api.ts?dns-preparation')
  for (const [code, message] of [
    ['SOURCE_SETUP_DNS_NO_ADDRESS', '暂无可用的 IPv4 解析'],
    ['SOURCE_SETUP_DNS_NONPUBLIC', '非公网地址'],
    ['SOURCE_SETUP_DNS_UNAVAILABLE', '未取得可用的公网解析']
  ]) {
    let posts = 0
    t.mock.method(globalThis, 'fetch', async (url, options) => {
      if (url.endsWith('/csrf')) return new Response(JSON.stringify({ token: 'fixture', headerName: 'X-CSRF' }))
      if (options.method === 'POST') posts++
      return new Response(JSON.stringify({ code }), { status: 503 })
    })
    await assert.rejects(request('/sources/fixture/probe-runs', { mode: 'managed_source' }), error =>
      error.message.includes(message) && error.message.includes(code))
    assert.equal(posts, 1)
    t.mock.restoreAll()
  }
})
