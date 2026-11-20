import test from 'node:test'
import assert from 'node:assert/strict'

const { request, sourceContent, downloadSource, downloadFile } = await import('../../src/maintenance/api.ts')
let pathname = '/'
let assigned
let calls = []
let clicked
let revoked
let removed
globalThis.window = {
  location: { get pathname() { return pathname }, assign(value) { assigned = value } },
  localStorage: {
    getItem(key) { assert.equal(key, 'token'); return 'at-admin-session' },
    removeItem(key) { removed = key }
  }
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

test('AT hash route uses the session header at the host root', async () => {
  pathname = '/'
  calls = []
  await request('/sources')
  assert.equal(calls[0].options.headers.Authorization, 'at-admin-session')
  assert.equal(calls[0].options.credentials, 'same-origin')
})

test('embedded reads and writes use AT session header and existing CSRF contract', async () => {
  pathname = '/'
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
  pathname = '/'
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

test('all archive endpoints download with AT headers and preserve the server Unicode versioned name', async t => {
  const name = '测试来源_维护_v2.zip'
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return new Response('PK-test', { headers: {
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`
    } })
  })
  for (const path of ['/source-imports/import/archive', '/repair-candidates/candidate/export',
    '/release-plans/plan/export', '/media-probes/run/export']) {
    calls = []
    await downloadFile(path, 'fallback.zip')
    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/api/plugin-maintenance' + path)
    assert.equal(calls[0].options.headers.Authorization, 'at-admin-session')
    assert.equal(calls[0].options.credentials, 'same-origin')
    assert.equal(clicked.download, name)
  }
})

test('failed and expired downloads never create a link or retry', async t => {
  for (const status of [403, 401]) {
    let requests = 0
    clicked = undefined
    t.mock.method(globalThis, 'fetch', async () => { requests++; return new Response('', { status }) })
    await assert.rejects(downloadFile('/repair-candidates/candidate/export', 'fallback.zip'))
    assert.equal(requests, 1)
    assert.equal(clicked, undefined)
    t.mock.restoreAll()
  }
  assert.equal(assigned, '/#/login')
})

test('unauthorized embedded request returns to AT login', async () => {
  await assert.rejects(request('/unauthorized'))
  assert.equal(assigned, '/#/login')
  assert.equal(removed, 'token')
})

test('expired session returns to AT login under its deployed base path', async () => {
  pathname = '/at/'
  await assert.rejects(request('/unauthorized'))
  assert.equal(assigned, '/at/#/login')
})
