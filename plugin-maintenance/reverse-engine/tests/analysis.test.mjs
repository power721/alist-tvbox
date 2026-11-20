import test from 'node:test'
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { sha, primitive, location, requestMatches, stackFrames, responseSignals, bodyContract, networkContract, retainNetworkContract, scriptCandidates, scriptExcerpt, limitScriptExcerpts, recoverScript } from '../analysis.mjs'

test('network body contracts preserve JSON fields and business codes without guest credentials', () => {
  const text = JSON.stringify({ code: 200, success: true, msg: 'private-test-marker', data: {
    manifest_url: 'https://media.example/master.m3u8?token=private-test-marker', manifest_method: 'GET',
    headers: { Authorization: 'private-test-marker', 'User-Agent': 'private-test-marker' },
    credentials: { type: 'GET', nested: 'private-test-marker' },
    items: [{ id: 1 }, { id: 2 }], '\u4e2d\u6587\u5b57\u6bb5': 'private-test-marker' } })
  const result = bodyContract(Buffer.from(text), 'application/json')
  const field = (schema, name) => schema.fields.find(item => item.name === name).shape
  assert.equal(result.status, 'json')
  assert.equal(result.bodyBytes, Buffer.byteLength(text))
  assert.equal(result.bodySha256, sha(text))
  assert.equal(field(result.schema, 'code').value, 200)
  assert.equal(field(result.schema, 'success').value, true)
  const data = field(result.schema, 'data')
  assert.equal(field(data, 'manifest_method').value, 'GET')
  assert.equal(field(data, 'manifest_url').url.origin, 'https://media.example')
  assert.equal(field(field(data, 'headers'), 'Authorization').redacted, true)
  assert.deepEqual(field(data, 'credentials'), { type: 'object', redacted: true })
  assert.equal(field(data, 'items').length, 2)
  assert.equal(field(data, '\u4e2d\u6587\u5b57\u6bb5').type, 'string')
  assert.ok(!JSON.stringify(result).includes('private-test-marker'))
})

test('network body contracts distinguish incomplete, oversized, binary and valid JSON under other MIME types', () => {
  const body = Buffer.from('{"code":403,"token":"private-test-marker"}')
  assert.equal(bodyContract(body, 'application/json', false).status, 'incomplete')
  assert.equal(bodyContract(body, 'application/json', false).schema, undefined)
  assert.equal(bodyContract(Buffer.from(' '.repeat(65537)), 'application/json').status, 'byte_limit')
  assert.equal(bodyContract(Buffer.from([0xff, 0x7b, 0x7d]), 'application/json').status, 'opaque')
  assert.equal(bodyContract(Buffer.from('<html>guest</html>'), 'text/html').status, 'opaque')
  assert.equal(bodyContract(Buffer.from('{}'), 'text/plain').status, 'json')
  assert.equal(bodyContract(Buffer.alloc(0), 'text/plain').status, 'empty')
})

test('network body contracts bound nesting and arrays while form fields preserve duplicates', () => {
  const form = bodyContract(Buffer.from('id=1&id=2&csrf_token=private-test-marker'),
    'application/x-www-form-urlencoded; charset=utf-8')
  assert.equal(form.status, 'form')
  assert.deepEqual(form.schema.fields.map(row => row.name), ['id', 'id', 'csrf_token'])
  assert.equal(form.schema.fields[2].shape.redacted, true)
  const array = bodyContract(Buffer.from(JSON.stringify(Array.from({ length: 100 }, () => ({ a: [1, 2, 3] })))), 'application/json')
  assert.equal(array.truncated, true)
  assert.equal(array.schema.length, 100)
  assert.equal(array.schema.items.length, 4)
  const deep = bodyContract(Buffer.from('{"a":'.repeat(10) + '1' + '}'.repeat(10)), 'application/json')
  assert.equal(deep.truncated, true)
  const wide = bodyContract(Buffer.from(JSON.stringify(Object.fromEntries(
    Array.from({ length: 100 }, (_, i) => ['field' + i, i])))), 'application/json')
  assert.equal(wide.truncated, true)
  assert.equal(wide.schema.fields.length, 63)
  const secretName = bodyContract(Buffer.from('{"sk-private-test-marker":1}'), 'application/json')
  assert.ok(!JSON.stringify([form, secretName]).includes('private-test-marker'))
})

test('network contracts bind request shape and response to the broker exchange without raw header values', () => {
  const row = { resourceType: 'xhr', method: 'POST', requestIdSha256: 'request', urlSha256: 'url', targetMatched: true }
  const contract = networkContract(row, { headers: { 'Content-Type': 'application/json', Cookie: 'private-test-marker' },
    body: Buffer.from('{}').toString('base64') },
    { status: 200, body: Buffer.from('{"code":403}').toString('base64') },
    { responseContentType: 'application/json', responseComplete: true })
  assert.equal(contract.requestIdSha256, 'request')
  assert.equal(contract.urlSha256, 'url')
  assert.equal(contract.method, 'POST')
  assert.deepEqual(contract.request.headerNames, ['content-type', 'cookie'])
  assert.equal(contract.request.body.status, 'json')
  assert.deepEqual(contract.request.body.schema.fields, [])
  assert.equal(contract.response.status, 200)
  assert.equal(contract.response.body.schema.fields[0].shape.value, 403)
  assert.equal(contract.businessSuccessVerified, false)
  assert.equal(networkContract({ ...row, resourceType: 'script' }, {}, {}, {}), undefined)
  assert.ok(!JSON.stringify(contract).includes('private-test-marker'))
})

test('network contract retention reserves exact targets under record and byte limits', () => {
  const report = { networkContracts: [] }
  for (let i = 0; i < 5; i++) retainNetworkContract(report, { id: i, targetMatched: false })
  assert.deepEqual(report.networkContracts.map(row => row.id), [0, 1, 2, 3])
  assert.equal(report.networkContractsTruncated, true)
  retainNetworkContract(report, { id: 'target', targetMatched: true })
  assert.deepEqual(report.networkContracts.map(row => row.id), [1, 2, 3, 'target'])
  const kept = structuredClone(report.networkContracts)
  retainNetworkContract(report, { id: 'oversized', targetMatched: true, data: 'x'.repeat(32769) })
  assert.deepEqual(report.networkContracts, kept)
  retainNetworkContract(report, undefined)
  assert.deepEqual(report.networkContracts, kept)
})

test('runtime exception sources precede unrelated inline analytics without replacing breakpoint positions', () => {
  const page = 'https://example.org/player/', scripts = new Map([
    ['analytics', { url: page }], ['player', { url: 'https://example.org/player.js' }],
    ['http', { url: 'https://example.org/http.js' }]
  ])
  const position = { scriptId: 'player', line: 15, column: 800 }
  const selected = scriptCandidates([], [], scripts, page, [], [], [{ location: position, frames: [position] }])
  assert.deepEqual(selected[0], ['player', position])
  assert.equal(selected[1][0], 'analytics')
  const paused = { scriptId: 'player', line: 18, column: 4 }
  assert.deepEqual(scriptCandidates([{ frames: [paused] }], [], scripts, page, [], [],
    [{ location: position, frames: [position] }])[0], ['player', paused])
  assert.equal(scriptExcerpt('x'.repeat(2000), { scriptId: 'player', line: 0, column: 800 }, {}).offset, 300)
})

test('response diagnostics retain bounded fixed signals but never response secrets', () => {
  const body = Buffer.from('<h1>Permission denied</h1>Please log in to continue; token expired; token=private-test-only')
  const result = responseSignals(body, 'text/html', true)
  assert.deepEqual(result.signals, ['login_required', 'permission_denied', 'session_invalid'])
  assert.equal(result.inspectionComplete, true)
  assert.equal(result.rawBodyStored, false)
  assert.ok(!JSON.stringify(result).includes('private-test-only'))
  const large = responseSignals(Buffer.from('x'.repeat(8192) + 'captcha'), 'application/json', true)
  assert.equal(large.inspectedBytes, 8192)
  assert.equal(large.inspectionComplete, false)
  assert.deepEqual(large.signals, [])
  assert.equal(responseSignals(Buffer.from('captcha'), 'image/png', true), undefined)
  assert.deepEqual(responseSignals(Buffer.from('Forbidden'), 'text/html', true).signals, ['permission_denied'])
  assert.equal(responseSignals(body, 'text/html', false).inspectionComplete, false)
})

test('script candidates prioritize the target and failed child document over analytics', () => {
  const frame = scriptId => ({ scriptId, line: 1, column: 2 })
  const requests = [
    { resourceType: 'ping', initiator: [frame('analytics')] },
    { resourceType: 'fetch', initiator: [frame('api')] },
    { resourceType: 'document', status: 403, initiator: [frame('player')] },
    { resourceType: 'script', targetMatched: true, initiator: [frame('target')] },
    { resourceType: 'xhr', initiator: [frame('analytics')] }
  ]
  const scripts = new Map([['fallback', { url: 'https://example.org/file.js' }]])
  const result = scriptCandidates([{ frames: [frame('paused')] }], requests, scripts)
  assert.deepEqual(result.map(([id]) => id), ['paused', 'target', 'player', 'analytics', 'api', 'fallback'])
  assert.equal(result[2][1].line, 1)
  assert.deepEqual(scriptCandidates([], [], scripts)[0], ['fallback', { scriptId: 'fallback', line: 0, column: 0 }])
})

test('bound page scripts precede analytics in observed order and use script-relative positions', () => {
  const page = 'https://example.org/player/', scripts = new Map([
    ['analytics', { url: 'https://metrics.test/a.js' }],
    ['short-page-tracking', { url: page, length: 200 }],
    ['initializer', { url: page, startLine: 40, startColumn: 8, length: 5000 }],
    ['other-page', { url: page + '?different=1' }]
  ])
  const selected = scriptCandidates([], [{ resourceType: 'xhr', initiator: [{ scriptId: 'analytics' }] }], scripts, page)
  assert.deepEqual(selected.slice(0, 2).map(([id]) => id), ['short-page-tracking', 'initializer'])
  assert.deepEqual(selected[1][1], { scriptId: 'initializer', line: 40, column: 8 })
  const code = 'a'.repeat(700) + '\n' + 'b'.repeat(800) + 'TARGET'
  assert.equal(scriptExcerpt(code, { line: 41, column: 800 }, scripts.get('initializer')).offset, 1001)
  assert.ok(scriptExcerpt(code, { line: 41, column: 800 }, scripts.get('initializer')).text.endsWith('TARGET'))
  assert.equal(scriptExcerpt(code, { line: 40, column: 708 }, scripts.get('initializer')).offset, 200)
  const observed = scriptCandidates([], [{ resourceType: 'xhr',
    initiator: [{ scriptId: 'initializer', line: 45, column: 20 }] }], scripts, page)
  assert.deepEqual(observed.find(([id]) => id === 'initializer')[1],
    { scriptId: 'initializer', line: 40, column: 8 })
})

test('page bootstrap precedes preloaded libraries while exact targets keep their source start', () => {
  const page = 'https://example.org/player/', player = 'https://cdn.example/player.js'
  const scripts = new Map([
    ['tracking', { url: page, length: 5000 }],
    ['player', { url: player, startLine: 0, startColumn: 0 }],
    ['target', { url: 'https://example.org/bootstrap.js' }],
    ['module', { url: 'https://example.org/main.js' }]
  ])
  const rows = [{ resourceType: 'script', targetMatched: true,
    urlSha256: sha('https://example.org/bootstrap.js'), initiator: [{ scriptId: 'target', line: 10, column: 900 }] }]
  const selected = scriptCandidates([], rows, scripts, page, [location(player)],
    [{ ...location('https://example.org/main.js'), entrypoint: true }])
  assert.deepEqual(selected.map(([id]) => id), ['target', 'module', 'tracking', 'player'])
  assert.deepEqual(selected[0][1], { scriptId: 'target', line: 0, column: 0 })
  const paused = scriptCandidates([{ frames: [{ scriptId: 'tracking', line: 7, column: 2 }] }], rows,
    scripts, page, [location(player)])
  assert.equal(paused[0][0], 'tracking')
  const code = 'x'.repeat(110000) + 'initializePlayer()'
  assert.equal(scriptExcerpt(code, selected[3][1], scripts.get('player'), 131072).complete, true)
  assert.equal(scriptExcerpt(code, selected[3][1], scripts.get('player')).complete, false)
  const breakpoint = scriptCandidates([{ frames: [{ scriptId: 'target', line: 10, column: 900 }] }],
    rows, scripts, page)
  assert.deepEqual(breakpoint[0][1], { scriptId: 'target', line: 10, column: 900 })
})

test('declared dependency modules survive repeated inline analytics within the script budget', () => {
  const page = 'https://example.org/watch/'
  const scripts = new Map(Array.from({ length: 12 }, (_, index) =>
    ['tracking-' + index, { url: page, length: 200 }]))
  const modules = ['plugins/player', 'plugins/codec', 'plugins/lazy', 'services/playback',
    'modules/utils', 'lib/player', 'lib/hls', '/cdn-config', 'plugins/image', 'services/http']
  const declarations = modules.map(requireModule => {
    const url = 'https://example.org/' + requireModule + '.js'
    scripts.set(requireModule, { url, length: 10000 })
    return { ...location(url), requireModule }
  })
  scripts.set('loader', { url: 'https://example.org/loader.js', length: 18000 })
  const position = { scriptId: 'loader', line: 10, column: 900 }
  const selected = scriptCandidates([], [], scripts, page, [], declarations,
    [{ location: position, frames: [position] }]).slice(0, 12)
  assert.equal(selected[0][0], 'loader')
  assert.deepEqual(selected.slice(1, 11).map(([id]) => id), modules)
  assert.equal(selected.filter(([id]) => id.startsWith('tracking-')).length, 1)
  const body = 'initializePlayback()'.repeat(500)
  assert.equal(scriptExcerpt(body, selected[4][1], scripts.get('services/playback'), 32768).complete, true)
})

test('script excerpts fit the existing process envelope while complete initialization code survives', () => {
  const initialization = 'initialize()'.repeat(9000)
  const report = { requests: [{ status: 200, metadata: 'x'.repeat(20000) }], scripts: [
    { sourceSha256: 'vendor-a', excerpt: { text: '"'.repeat(131072), complete: false } },
    { sourceSha256: 'vendor-b', excerpt: { text: '\\'.repeat(131072), complete: false } },
    { sourceSha256: 'initializer', excerpt: { text: initialization, complete: true } }
  ] }
  assert.equal(limitScriptExcerpts(report), report)
  assert.ok(Buffer.byteLength(JSON.stringify(report)) <= 245760)
  assert.equal(report.scriptExcerptsTruncated, true)
  assert.equal(report.scripts[2].excerpt.text, initialization)
  assert.equal(report.scripts[2].excerpt.complete, true)
  assert.deepEqual(report.scripts.map(script => script.sourceSha256), ['vendor-a', 'vendor-b', 'initializer'])
  assert.equal(report.requests[0].metadata.length, 20000)
})

test('observed URL hashes target exact resources without reconstructing credential-bearing URLs', () => {
  const url = 'https://example.org/init.js?token=private-test-marker'
  assert.equal(requestMatches(url, 'sha256:' + sha(url)), true)
  assert.equal(requestMatches(url + '&changed=1', 'sha256:' + sha(url)), false)
  assert.equal(requestMatches(url, 'sha256:invalid'), false)
  assert.equal(requestMatches(url, '/init.js'), true)
  assert.equal(requestMatches(url, ''), true)
})

test('request locations and primitive samples never retain query credentials or values', () => {
  const url = 'https://example.com/api?token=private-test-only'
  const frame = stackFrames({ callFrames: [{ scriptId: '7', url, functionName: 'load', lineNumber: 4, columnNumber: 2 }] }, new Map())
  assert.equal(frame[0].urlSha256, sha(url))
  assert.equal(frame[0].origin, 'https://example.com')
  const value = primitive({ type: 'string', value: 'private-test-only' })
  assert.equal(value.valueSha256, sha('"private-test-only"'))
  assert.ok(!JSON.stringify([frame, value]).includes('private-test-only'))
  assert.equal(location('data:text/javascript,1').origin, 'inline')
})

test('async request stacks remain bounded and keep source positions', () => {
  const stack = { callFrames: Array.from({ length: 30 }, (_, i) => ({ scriptId: String(i), lineNumber: i })) }
  assert.equal(stackFrames(stack, new Map()).length, 12)
  assert.equal(stackFrames(stack, new Map())[3].line, 3)
})

test('webcrack restores a script through a real offline browser without editing the original', async () => {
  const browser = await chromium.launch({ executablePath: process.env.REVERSE_TEST_BROWSER, headless: true,
    chromiumSandbox: true, args: ['--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>'] })
  try {
    const context = await browser.newContext({ serviceWorkers: 'block' })
    await context.route('**/*', route => route.abort())
    const page = await context.newPage()
    const cdp = await context.newCDPSession(page)
    const code = 'function load(){var a="/api/";return a+"items"}globalThis.result=load();'
    const restored = await recoverScript(code, async expression => {
      const value = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, timeout: 1000 })
      assert.ok(!value.exceptionDetails)
      return value.result.value
    })
    assert.equal(restored.originalSha256, sha(code))
    assert.equal(restored.equivalenceVerified, false)
    assert.equal(restored.appliedToSource, false)
    assert.ok(restored.excerpt.text.includes('/api/'))
    const before = await page.evaluate('(() => {' + code + ';return globalThis.result})()')
    const after = await page.evaluate('(() => {' + restored.excerpt.text + ';return globalThis.result})()')
    assert.equal(before, '/api/items')
    assert.equal(after, before)
    await context.close()
  } finally { await browser.close() }
})
