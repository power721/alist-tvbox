import test from 'node:test'
import assert from 'node:assert/strict'

const { request, sourceContent, downloadSource } = await import('../src/api.ts')
let pathname = '/'
let assigned
let calls = []
let clicked
let revoked
globalThis.window = {
  location: { get pathname() { return pathname }, assign(value) { assigned = value } },
  localStorage: { getItem(key) { assert.equal(key, 'token'); return 'at-admin-session' } }
}
globalThis.document = { createElement() {
  const link = { click() { clicked = { href: link.href, download: link.download } } }
  return link
} }
URL.createObjectURL = () => 'blob:owned-download'
URL.revokeObjectURL = value => { revoked = value }
globalThis.fetch = async (url, options) => {
  calls.push({ url, options })
  if (url.endsWith('/csrf')) return Response.json({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' })
  if (url.includes('/artifacts/')) return new Response('class Spider: pass')
  if (url.includes('/export?')) return new Response('PK-test')
  if (url.endsWith('/unauthorized')) return new Response('', { status: 401 })
  return Response.json([])
}

test('standalone API keeps cookie authentication without reading AT credentials', async () => {
  pathname = '/'
  calls = []
  await request('/sources')
  assert.equal(calls[0].options.headers.Authorization, undefined)
  assert.equal(calls[0].options.credentials, 'same-origin')
})

test('embedded reads and writes use AT session header and existing CSRF contract', async () => {
  pathname = '/plugin-maintenance/'
  calls = []
  await request('/sources', { name: 'test' })
  assert.equal(calls.length, 2)
  assert.equal(calls[0].options.headers.Authorization, 'at-admin-session')
  assert.equal(calls[1].options.headers.Authorization, 'at-admin-session')
  assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'csrf-value')
  assert.equal(await sourceContent('source', 'sha'), 'class Spider: pass')
  assert.equal(calls[2].options.headers.Authorization, 'at-admin-session')
})

test('embedded consecutive writes refresh rotating CSRF without replaying a mutation', async t => {
  pathname = '/plugin-maintenance/'
  const observed = []
  let next = 0
  let current
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    observed.push({ url, options })
    if (url.endsWith('/csrf')) {
      current = `rotating-${++next}`
      return Response.json({ token: current, headerName: 'X-CSRF-TOKEN' })
    }
    if (options.method !== 'GET') {
      assert.equal(options.headers['X-CSRF-TOKEN'], current)
    }
    current = undefined
    return Response.json({ ok: true })
  })
  await request('/sources/fixture', { content: 'first' })
  await request('/sources/fixture')
  await request('/sources/fixture', { content: 'second' }, 'PUT')
  const writes = observed.filter(call => call.options.method !== 'GET')
  assert.equal(next, 2)
  assert.equal(writes.length, 2)
  assert.equal(writes[0].options.headers['X-CSRF-TOKEN'], 'rotating-1')
  assert.equal(writes[1].options.headers['X-CSRF-TOKEN'], 'rotating-2')
})

test('embedded ZIP download sends header instead of leaking token in URL', async () => {
  calls = []
  await downloadSource('source', '0123456789abcdef')
  assert.equal(calls[0].options.headers.Authorization, 'at-admin-session')
  assert.equal(calls[0].url, '/api/plugin-maintenance/sources/source/export?sha=0123456789abcdef')
  assert.deepEqual(clicked, { href: 'blob:owned-download', download: 'source-source-0123456789ab.zip' })
  await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(revoked, 'blob:owned-download')
})

test('unauthorized embedded request returns to AT login', async () => {
  await assert.rejects(request('/unauthorized'))
  assert.equal(assigned, '/#/login')
})

test('unauthorized standalone request returns to its own login', async () => {
  pathname = '/'
  await assert.rejects(request('/unauthorized'))
  assert.equal(assigned, '/login')
})
