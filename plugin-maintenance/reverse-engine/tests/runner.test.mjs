import test from 'node:test'
import assert from 'node:assert/strict'
import { chromium } from 'playwright-core'
import { fileURLToPath } from 'node:url'
import { analyze, brokerRequests, browserEnvironment, requestEvidence, browserNetworkFailure, navigationEvidence, pageSourceBinding, managedBrowserTargets, transportFailureEvidence, readBrowserEndpoint, setupFailureEvidence, pageDomEvidence, pageErrorEvidence, runtimeExceptionEvidence, settleBrowserCapture, pageAccessEvidence } from '../runner.mjs'
import { sha, location, scriptExcerpt, responseSignals } from '../analysis.mjs'
import { EventEmitter } from 'node:events'
import { PassThrough, Writable } from 'node:stream'
import { readFile } from 'node:fs/promises'

test('origin-only inspection uses the broker and closes without launching a browser or probing unknown hosts', async t => {
  t.mock.method(chromium, 'launch', async () => { assert.fail('origin inspection must not launch Chromium') })
  const report = await analyze({ sourceContent: 'host="https://example.org"',
    pageUrl: 'https://example.org/page/', originInspectionOnly: true, python: process.env.REVERSE_TEST_PYTHON,
    egressScript: fileURLToPath(new URL('../../scripts/source_egress.py', import.meta.url)) })
  assert.equal(report.operation, 'source_origin_inspection')
  assert.equal(report.authority, 'host_source_origin_observed')
  assert.equal(report.cookieAuthority, 'none')
  assert.equal(report.originPool.nextAction, 'no_declared_alternate_origin')
  assert.equal(report.originPool.observations.length, 0)
  assert.equal(report.originPool.requestedPageUrlSha256, sha('https://example.org/page/'))
  assert.equal(report.browserStarted, false)
  assert.equal(report.cleanupConfirmed, true)
  assert.equal(report.businessChainVerified, false)
})

test('all task targets share browser Fetch while unrelated requests stay outside the broker', async () => {
  const protocol = new EventEmitter(), pending = [], commands = [], setups = [], networks = []
  protocol.send = async (method, params, sessionId) => { commands.push({ method, params, sessionId }); return {} }
  await managedBrowserTargets(protocol, 'task-context', {
    excludedResourceTypes: [], onNetwork: (name, event, identity) => networks.push(identity), onScript() {},
    onRequest() {}, async onPause() {}, onTargetSetup: row => setups.push(row),
    fail: code => assert.fail(code), tracked: task => pending.push(task)
  })
  const attach = async (id, type, parent) => {
    protocol.emit('Target.attachedToTarget', { sessionId: id,
      targetInfo: { targetId: id, type, browserContextId: 'task-context' } }, parent)
    await Promise.all(pending.splice(0))
  }
  await attach('page', 'page')
  await attach('worker', 'worker', 'page')
  await attach('nested', 'worker', 'worker')
  await attach('shared', 'shared_worker')
  assert.equal(commands.filter(row => row.method === 'Fetch.enable').length, 1)
  assert.equal(commands.find(row => row.method === 'Fetch.enable').sessionId, undefined)
  assert.ok(setups.every(row => row.transportBinding === 'task_browser'))
  assert.equal(setups.find(row => row.targetType === 'worker').transportSessionIdSha256, sha('task-browser:task-context'))
  protocol.emit('Network.requestWillBeSent', { requestId: 'worker-request',
    request: { url: 'https://example.org/api', method: 'GET' } }, 'nested')
  assert.deepEqual(networks, [sha('task-browser:task-context:worker-request')])
})

test('browser Fetch rejects requests without a live task owner before forwarding HTTP', async () => {
  const protocol = new EventEmitter(), pending = [], commands = [], failures = []
  protocol.send = async (method, params) => { commands.push({ method, params }); return {} }
  await managedBrowserTargets(protocol, 'task-context', {
    async fetch() { assert.fail('unowned request reached broker') }, excludedResourceTypes: [],
    onNetwork() {}, onScript() {}, onRequest() { assert.fail('unowned request stored') }, async onPause() {},
    fail: code => failures.push(code), tracked: task => pending.push(task)
  })
  protocol.emit('Target.attachedToTarget', { sessionId: 'other',
    targetInfo: { targetId: 'other-frame', type: 'page', browserContextId: 'other-context' } })
  await Promise.all(pending.splice(0))
  protocol.emit('Fetch.requestPaused', { requestId: 'unowned', networkId: 'unknown', frameId: 'other-frame',
    resourceType: 'Document', request: { method: 'GET', url: 'https://example.org/', headers: {} } })
  await Promise.all(pending.splice(0))
  assert.deepEqual(failures, ['REVERSE_REQUEST_OWNER_UNAVAILABLE'])
  assert.ok(commands.some(row => row.method === 'Fetch.failRequest' && row.params.requestId === 'unowned'))
})

test('target setup failures identify the exact command without raw protocol data and stay fatal', async () => {
  const protocol = new EventEmitter(), pending = [], commands = [], setups = [], failures = []
  protocol.send = async (method, params, sessionId) => {
    commands.push(method)
    if (method === 'Debugger.enable') throw Object.assign(new Error('PRIVATE protocol data'), { response: { code: -32601 } })
    return {}
  }
  await managedBrowserTargets(protocol, 'task-context', {
    excludedResourceTypes: [], onNetwork() {}, onScript() {}, onRequest() {}, async onPause() {},
    onTargetSetup: row => setups.push(row), fail: code => failures.push(code), tracked: task => pending.push(task)
  })
  protocol.emit('Target.attachedToTarget', { sessionId: 'PRIVATE-session',
    targetInfo: { targetId: 'PRIVATE-target', type: 'iframe', browserContextId: 'task-context', url: 'PRIVATE-url' } })
  await Promise.all(pending)
  assert.equal(setups[0].stage, 'Debugger.enable')
  assert.equal(setups[0].status, 'failed')
  assert.equal(setups[0].protocolCode, -32601)
  assert.deepEqual(failures, ['REVERSE_TARGET_SETUP_FAILED'])
  assert.ok(commands.includes('Target.closeTarget'))
  assert.ok(!commands.includes('Runtime.runIfWaitingForDebugger'))
  assert.ok(!JSON.stringify(setups).includes('PRIVATE'))
})

test('runtime exceptions bind redacted positions to their target script and bound async stacks', () => {
  const url = 'https://example.org/player.js?token=private-test-marker'
  const details = { text: 'Uncaught private-test-marker', scriptId: '42', url,
    lineNumber: 7, columnNumber: 11,
    exception: { className: 'TypeError', description: 'private-test-marker', objectId: 'private-test-marker' },
    stackTrace: { callFrames: Array.from({ length: 20 }, () => ({ scriptId: '42', url,
      functionName: 'startPlayer', lineNumber: 7, columnNumber: 11 })) } }
  const result = runtimeExceptionEvidence(details, { id: 'task-session', type: 'iframe' })
  assert.equal(result.authority, 'task_browser_runtime')
  assert.equal(result.errorType, 'TypeError')
  assert.equal(result.targetType, 'iframe')
  assert.equal(result.messageSha256, sha('private-test-marker'))
  assert.equal(result.location.scriptId, sha('task-session').slice(0, 16) + ':42')
  assert.equal(result.location.urlSha256, sha(url))
  assert.equal(result.location.line, 7)
  assert.equal(result.location.column, 11)
  assert.equal(result.frames.length, 12)
  assert.equal(result.frames[0].scriptId, result.location.scriptId)
  assert.notEqual(runtimeExceptionEvidence(details, { id: 'other-session', type: 'page' }).location.scriptId,
    result.location.scriptId)
  assert.ok(!JSON.stringify(result).includes('private-test-marker'))
})

test('runtime exception observation starts before page execution and ignores unrelated targets', async () => {
  const protocol = new EventEmitter(), pending = [], commands = [], exceptions = []
  protocol.send = async (method, params, sessionId) => { commands.push({ method, sessionId }); return {} }
  await managedBrowserTargets(protocol, 'task-context', {
    excludedResourceTypes: [], onNetwork() {}, onScript() {}, onRequest() {}, async onPause() {},
    onException: (details, session) => exceptions.push(runtimeExceptionEvidence(details, session)),
    fail: code => assert.fail(code), tracked: task => pending.push(task)
  })
  protocol.emit('Target.attachedToTarget', { sessionId: 'task-session',
    targetInfo: { targetId: 'target', type: 'page', browserContextId: 'task-context' } })
  await Promise.all(pending)
  assert.ok(commands.findIndex(row => row.method === 'Runtime.enable') <
    commands.findIndex(row => row.method === 'Runtime.runIfWaitingForDebugger'))
  const event = { exceptionDetails: { scriptId: '1', url: 'https://example.org/player.js',
    lineNumber: 2, columnNumber: 3, text: 'Uncaught', exception: { className: 'Error' } } }
  protocol.emit('Runtime.exceptionThrown', event, 'unrelated-session')
  protocol.emit('Runtime.exceptionThrown', event, 'task-session')
  assert.equal(exceptions.length, 1)
  assert.equal(exceptions[0].location.line, 2)
  protocol.emit('Target.detachedFromTarget', { sessionId: 'task-session' })
  protocol.emit('Runtime.exceptionThrown', event, 'task-session')
  assert.equal(exceptions.length, 1)
})

test('browser sampling admits late dependency chains before the idle window closes', async () => {
  let clock = 0, dependencyAdmitted = false
  const state = { inFlight: new Set(['parent']), pending: new Set(), lastActivity: 0 }
  const result = await settleBrowserCapture(state, 400, 3000, () => clock, async milliseconds => {
    clock += milliseconds
    if (clock === 1200) {
      state.inFlight.delete('parent')
      state.pending.add('browser-delivery')
    }
    if (clock === 1300) {
      state.pending.clear()
      state.inFlight.add('child')
      state.lastActivity = clock
      dependencyAdmitted = true
    }
    if (clock === 1600) {
      state.inFlight.clear()
      state.lastActivity = clock
    }
  })
  assert.equal(dependencyAdmitted, true)
  assert.deepEqual(result, { status: 'idle', elapsedMilliseconds: 2000, pendingAtClose: 0, idleMillis: 400 })
})

test('browser sampling preserves the existing total deadline for never-ending traffic', async () => {
  let clock = 0
  const state = { inFlight: new Set(['stalled']), pending: new Set(), lastActivity: 0 }
  const result = await settleBrowserCapture(state, 400, 1050, () => clock,
    async milliseconds => { clock += milliseconds })
  assert.deepEqual(result, { status: 'deadline', elapsedMilliseconds: 1050, pendingAtClose: 1, idleMillis: 400 })
})

test('challenge observation keeps the normal page alive past idle and stops at its existing deadline', async () => {
  let clock = 0, waiting = true
  const state = { inFlight: new Set(), pending: new Set(), lastActivity: 0, waitingForAccess: () => waiting }
  const result = await settleBrowserCapture(state, 400, 3000, () => clock, async milliseconds => {
    clock += milliseconds
    if (clock === 1200) { waiting = false; state.lastActivity = clock }
  })
  assert.equal(result.status, 'idle')
  assert.equal(result.elapsedMilliseconds, 1600)
  clock = 0; waiting = true
  const blocked = await settleBrowserCapture(state, 400, 1050, () => clock, async milliseconds => { clock += milliseconds })
  assert.equal(blocked.status, 'deadline')
  assert.equal(blocked.elapsedMilliseconds, 1050)
})

test('main document evidence distinguishes a challenge, its real recovery, and unrelated frame responses', () => {
  const url = 'https://example.org/videos/1/?private-test-marker', frame = sha('main-frame')
  const row = (status, text, headers = {}, frameIdSha256 = frame) => ({ ...location(url), resourceType: 'document',
    frameIdSha256, status, browserDeliveryVerified: true,
    http: { responseComplete: true, responseBytes: Buffer.byteLength(text) },
    responseEvidence: responseSignals(Buffer.from(text), 'text/html', true, headers) })
  const challenge = row(403, '<html>Checking browser</html>', { 'CF-Mitigated': 'challenge' })
  const rows = [challenge]
  let result = pageAccessEvidence(rows, frame, url, url)
  assert.equal(result.status, 'challenge_pending')
  assert.equal(result.requestedPageObserved, true)
  assert.equal(result.challengeObserved, true)
  assert.equal(result.challengeBasis, 'cf_mitigated_header')
  rows.push(row(200, '<article>One</article>', {}, sha('other-frame')))
  assert.equal(pageAccessEvidence(rows, frame, url, url).status, 'challenge_pending')
  const content = row(200, '<article>One</article>')
  rows.push(content)
  result = pageAccessEvidence(rows, frame, url, url)
  assert.equal(result.status, 'content_observed')
  assert.equal(result.recoveredAfterChallenge, true)
  assert.equal(result.businessSuccessVerified, false)
  assert.equal(result.automaticChallengeSolving, false)
  content.browserDeliveryVerified = false
  assert.equal(pageAccessEvidence(rows, frame, url, url).status, 'unavailable')
  assert.ok(!JSON.stringify(result).includes('private-test-marker'))
})

test('a normal embedded captcha is not a Cloudflare interstitial or a reason to extend observation', () => {
  const ordinary = responseSignals(Buffer.from('<article>Movie</article><div class="turnstile captcha"></div>'), 'text/html', true)
  assert.equal(ordinary.challengePage, false)
  const challenge = responseSignals(Buffer.from('<title>Just a moment...</title><script>window._cf_chl_opt = {};'
    + 'var src="/cdn-cgi/challenge-platform/start"</script>'), 'text/html', true)
  assert.equal(challenge.challengePage, true)
  assert.equal(challenge.challengeBasis, 'cloudflare_page_template')
  assert.equal(responseSignals(Buffer.from('opaque'), '', true, { 'cf-mitigated': 'challenge' }).challengePage, true)
  const detected = responseSignals(Buffer.from('<article>Movie</article><script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>'), 'text/html', true)
  assert.equal(detected.challengePage, false)
})

test('unobserved history changes keep a prior challenge unresolved and never promote old content', () => {
  const url = 'https://example.org/videos/one/', frame = sha('main')
  const challenge = { ...location(url), resourceType: 'document', frameIdSha256: frame,
    browserDeliveryVerified: true, status: 403, http: { responseComplete: true, responseBytes: 50 },
    responseEvidence: { challengePage: true, challengeBasis: 'cf_mitigated_header' } }
  const current = location(url + '?history=changed')
  const result = pageAccessEvidence([challenge], frame, location(url), current)
  assert.equal(result.status, 'challenge_unresolved')
  assert.equal(result.documentUrlMatchesCurrent, false)
  assert.equal(result.documentUrlSha256, sha(url))
  assert.equal(result.nextAction, 'authorized_access_required')
  assert.equal(result.recoveredAfterChallenge, false)
  const content = { ...challenge, status: 200, responseEvidence: { challengePage: false } }
  assert.equal(pageAccessEvidence([content], frame, url, current).status, 'unavailable')
})

test('saved platform capture retains an unresolved challenge after its address changes',
  { skip: !process.env.MAINTENANCE_REVERSE_CAPTURE_FIXTURE }, async () => {
    const receipt = JSON.parse(await readFile(process.env.MAINTENANCE_REVERSE_CAPTURE_FIXTURE, 'utf8'))
    const main = receipt.requests.find(row => row.resourceType === 'document' && row.urlSha256 === receipt.pageUrlSha256)
    assert.ok(main)
    const projected = pageAccessEvidence(receipt.requests, main.frameIdSha256,
      receipt.navigation.requested, receipt.targetPage)
    assert.equal(projected.status, 'challenge_unresolved')
    assert.equal(projected.requestedPageObserved, true)
    assert.equal(projected.challengeObserved, true)
    assert.equal(projected.recoveredAfterChallenge, false)
    assert.equal(projected.businessSuccessVerified, false)
    assert.equal(projected.documentUrlSha256, receipt.pageUrlSha256)
    assert.equal(receipt.cleanupConfirmed, true)
  })

test('task browser endpoint reads recover only bounded startup file races', async () => {
  let reads = 0
  const waits = [], endpoint = '12345\n/devtools/browser/task\n'
  const value = await readBrowserEndpoint('task-profile', async () => {
    if (++reads === 1) throw Object.assign(new Error('private-test-marker'), { code: 'EBUSY' })
    return endpoint
  }, async millis => waits.push(millis))
  assert.equal(value, endpoint)
  assert.equal(reads, 2)
  assert.deepEqual(waits, [100])
  reads = 0
  const busy = await readBrowserEndpoint('task-profile', async () => {
    reads++
    throw Object.assign(new Error('private-test-marker'), { code: 'EBUSY' })
  }, async () => {}).catch(error => error)
  assert.equal(reads, 4)
  assert.deepEqual(setupFailureEvidence('cdp_connect', busy),
    { stage: 'cdp_endpoint_read', errorType: 'Error', code: 'EBUSY' })
  reads = 0
  const attempts = []
  assert.equal(await readBrowserEndpoint('task-profile', async () => {
    if (++reads === 1) throw Object.assign(new Error('private-test-marker'), { code: 'ENOENT' })
    return endpoint
  }, async () => {}, row => attempts.push(row)), endpoint)
  assert.deepEqual(attempts, [{ attempt: 1, status: 'failed', code: 'ENOENT' }, { attempt: 2, status: 'ready' }])
  reads = 0
  await assert.rejects(readBrowserEndpoint('task-profile', async () => {
    reads++
    throw Object.assign(new Error('private-test-marker'), { code: 'ENOENT' })
  }, async () => {}), { code: 'ENOENT' })
  assert.equal(reads, 4)
  reads = 0
  await assert.rejects(readBrowserEndpoint('task-profile', async () => {
    reads++
    throw Object.assign(new Error('private-test-marker'), { code: 'EACCES' })
  }), { code: 'EACCES' })
  assert.equal(reads, 1)
})

test('late browser delivery retains successful HTTP and a redacted protocol failure stage', async () => {
  const protocol = new EventEmitter(), pending = [], rows = [], failures = [], contracts = []
  protocol.send = async method => {
    if (method === 'Fetch.fulfillRequest') throw new Error('Invalid InterceptionId private-test-marker')
    return {}
  }
  await managedBrowserTargets(protocol, 'task-context', {
    async fetch() { return { response: { status: 200, headers: {}, body: Buffer.from('{"code":200}').toString('base64') },
      observation: { responseStatus: 200, responseComplete: true, responseContentType: 'application/json' },
      connectionEvidence: { authority: 'maintenance_pinned_http_connection', attempts: [{ tcpConnected: true }] },
      transportTiming: { schedulerQueueMilliseconds: 1200, executionMilliseconds: 400, queuedAhead: 2 } } },
    requestPattern: 'sha256:' + sha('https://example.org/config'),
    excludedResourceTypes: [], onRequest: row => rows.push(row), onNetwork() {}, onScript() {},
    onContract: contract => contracts.push(contract),
    async onPause() {}, fail: code => failures.push(code), tracked: task => pending.push(task)
  })
  protocol.emit('Target.attachedToTarget', { sessionId: 'page-session',
    targetInfo: { targetId: 'target', type: 'page', browserContextId: 'task-context' } })
  await Promise.all(pending.splice(0))
  protocol.emit('Fetch.requestPaused', { requestId: 'request', frameId: 'target', resourceType: 'XHR',
    request: { method: 'GET', url: 'https://example.org/config', headers: {} } })
  await Promise.all(pending.splice(0))
  assert.equal(rows[0].http.responseStatus, 200)
  assert.equal(rows[0].targetMatched, true)
  assert.equal(rows[0].transportTiming.schedulerQueueMilliseconds, 1200)
  assert.equal(rows[0].connectionEvidence.attempts[0].tcpConnected, true)
  assert.ok(Number.isInteger(rows[0].brokerRoundTripMilliseconds))
  assert.deepEqual(rows[0].transportFailure,
    { stage: 'browser_response', errorType: 'Error', code: 'INTERCEPTION_NO_LONGER_ACTIVE' })
  assert.deepEqual(failures, ['REVERSE_TRANSPORT_FAILED'])
  assert.equal(contracts[0].requestIdSha256, rows[0].requestIdSha256)
  assert.equal(contracts[0].response.body.schema.fields[0].shape.value, 200)
  assert.equal(contracts[0].browserDeliveryVerified, false)
  assert.equal(transportFailureEvidence('browser_response', new Error('private-test-marker')).code,
    'REVERSE_TRANSPORT_UNCLASSIFIED')
  assert.ok(!JSON.stringify(rows).includes('private-test-marker'))
})

test('bounded DOM evidence observes player frames without form values or media URLs',
  { skip: !process.env.REVERSE_TEST_BROWSER, timeout: 30000 }, async t => {
    const browser = await chromium.launch({ executablePath: process.env.REVERSE_TEST_BROWSER,
      headless: true, env: browserEnvironment(), args: ['--proxy-server=http://127.0.0.1:9'] })
    try {
      t.diagnostic('task browser launched')
      const context = await browser.newContext({ serviceWorkers: 'block' })
      await context.route('**/*', route => route.request().resourceType() === 'stylesheet'
        ? route.fulfill({ contentType: 'text/css', body: '.auth { display: none }' })
        : route.request().resourceType() === 'document'
        ? route.fulfill({ contentType: 'text/html', body: route.request().url().includes('child.test')
          ? '<button id="login">Login required</button><input type="password" value="private-test-marker">'
          : '<link rel="stylesheet" href="/player.css"><button class="auth">Login</button>'
            + '<link rel="preload" as="script" href="/player.js?token=private-test-marker">'
            + '<link rel="modulepreload" href="https://cdn.test/init.js">'
            + '<video id="player" src="https://media.test/private-test-marker"></video>'
            + '<input value="private-test-marker"><iframe src="https://child.test/"></iframe>'
            + '<script>playerFixtureMissing()</script>'
            + '<script type="module" src="https://cdn.test/init.js?token=private-test-marker" data-requiremodule="pages/player"></script>'
            + '<script type="application/json">{"token":"private-test-marker"}</script>'
            + '<button>Play</button>'.repeat(35) }) : route.abort())
      const page = await context.newPage(), errors = []
      const cdp = await context.newCDPSession(page), parsed = []
      await cdp.send('Debugger.enable')
      cdp.on('Debugger.scriptParsed', row => parsed.push(row))
      page.on('pageerror', error => errors.push(pageErrorEvidence(error)))
      await page.goto('https://parent.test/', { waitUntil: 'domcontentloaded', timeout: 10000 })
      await page.frameLocator('iframe').locator('button').waitFor({ timeout: 5000 })
      t.diagnostic('main document and child frame observed')
      const dom = await pageDomEvidence(page)
      assert.equal(dom.frameCount, 2)
      assert.equal(dom.frames[0].controls.length, 32)
      assert.equal(dom.frames[0].controlsTruncated, true)
      assert.deepEqual(dom.frames[0].stylesheets, { declared: 1, loaded: 1 })
      assert.deepEqual(dom.frames[0].preloadedScripts, [
        { urlSha256: sha('https://parent.test/player.js?token=private-test-marker'), origin: 'https://parent.test' },
        { urlSha256: sha('https://cdn.test/init.js'), origin: 'https://cdn.test' }
      ])
      assert.equal(dom.frames[0].scriptCount, 3)
      assert.equal(dom.frames[0].scriptsTruncated, false)
      assert.deepEqual(dom.frames[0].scriptDeclarations[1], { index: 1, type: 'module',
        async: false, defer: false, entrypoint: true, requireModule: 'pages/player', sourceKind: 'resource',
        ...location('https://cdn.test/init.js?token=private-test-marker') })
      assert.equal(dom.frames[0].scriptDeclarations[2].type, 'application/json')
      assert.equal(dom.frames[0].controls.find(row => row.classes.includes('auth')).visible, false)
      const inline = parsed.find(row => row.url === 'https://parent.test/')
      const { scriptSource } = await cdp.send('Debugger.getScriptSource', { scriptId: inline.scriptId })
      assert.ok(inline.startColumn > 0)
      assert.ok(scriptExcerpt(scriptSource, { line: inline.startLine, column: inline.startColumn }, inline)
        .text.includes('playerFixtureMissing'))
      const media = dom.frames[0].controls.find(row => row.tag === 'video').media
      assert.equal(media.sourcePresent, true)
      assert.equal(media.origin, 'https://media.test')
      assert.equal(dom.frames[1].parentFrameIndex, 0)
      assert.ok(dom.frames[1].controls.some(row => row.tag === 'input'))
      assert.equal(errors[0].missingSymbol, 'playerFixtureMissing')
      assert.ok(!JSON.stringify({ dom, errors }).includes('private-test-marker'))
      assert.equal(dom.formValuesStored, false)
      assert.equal(dom.storageRead, false)
      assert.equal(dom.interactionsPerformed, false)
    } finally { await browser.close() }
  })

test('canonical playback pages require an exact host-observed page binding', () => {
  const original = 'https://original.example', canonical = 'https://canonical.example/player/1/'
  assert.equal(pageSourceBinding(original + '/player/', [original]), 'source_literal_origin')
  assert.equal(pageSourceBinding(canonical, [original], sha(canonical)), 'same_run_observed_page')
  assert.throws(() => pageSourceBinding(canonical, [original]), /REVERSE_PAGE_NOT_BOUND_TO_SOURCE/)
  assert.throws(() => pageSourceBinding(canonical + '?other=1', [original], sha(canonical)), /REVERSE_PAGE_NOT_BOUND_TO_SOURCE/)
  for (const page of ['file:///private', 'https://user:password@canonical.example/player/1/'])
    assert.throws(() => pageSourceBinding(page, [original], sha(page)), /REVERSE_PAGE_NOT_BOUND_TO_SOURCE/)
})

test('browser native processes never inherit SSH or model credentials', () => {
  assert.deepEqual(browserEnvironment({ SystemRoot: 'windows', TEMP: 'temporary', Path: 'commands',
    HOME: 'private-home', TEST_NAS_PASSWORD: 'private-test-marker', MODEL_API_KEY: 'model-marker' }),
  { SystemRoot: 'windows', TEMP: 'temporary', Path: 'commands' })
})

test('navigation and network failures retain fixed browser codes without URL or error secrets', () => {
  const secretUrl = 'https://example.org/player?token=private-test-marker'
  const timeout = new Error('page.goto timed out at ' + secretUrl)
  timeout.name = 'TimeoutError'
  const pending = navigationEvidence(secretUrl, 'about:blank', timeout)
  assert.equal(pending.completed, false)
  assert.equal(pending.status, 'timeout')
  assert.equal(pending.errorCode, 'NAVIGATION_TIMEOUT')
  const failed = navigationEvidence(secretUrl, secretUrl,
    new Error('page.goto: net::ERR_ABORTED at ' + secretUrl))
  assert.equal(failed.errorCode, 'net::ERR_ABORTED')
  assert.equal(failed.status, 'failed')
  const network = browserNetworkFailure({ errorText: 'net::ERR_FAILED ' + secretUrl, canceled: true,
    blockedReason: 'csp', corsErrorStatus: { corsError: 'InvalidResponse' } })
  assert.deepEqual(network, { code: 'net::ERR_FAILED', canceled: true, blockedReason: 'csp', corsError: 'InvalidResponse' })
  assert.ok(!JSON.stringify([pending, failed, network]).includes('private-test-marker'))
  assert.equal(navigationEvidence(secretUrl, secretUrl).completed, true)
  assert.equal(browserNetworkFailure({ errorText: 'private-test-marker' }).code, 'BROWSER_NETWORK_ERROR_UNCLASSIFIED')
})

test('HTTP stays on its route request across repeated URLs and browser-only preflights', () => {
  const urlSha256 = 'a'.repeat(64)
  const routed = [
    { requestIdSha256: 'first', method: 'GET', urlSha256, status: 200, initiator: [], http: { id: 1 } },
    { requestIdSha256: 'second', method: 'GET', urlSha256, status: 201, initiator: [], http: { id: 2 } },
    { requestIdSha256: 'post', method: 'POST', urlSha256, status: 403, initiator: [], http: { id: 3 } }
  ]
  const observed = [
    { method: 'GET', urlSha256, status: 200, initiator: ['first hint'] },
    { method: 'GET', urlSha256, status: 201, initiator: ['second hint'] },
    { method: 'POST', urlSha256, status: 403, initiator: ['post hint'], networkFailed: true,
      networkFailure: { code: 'net::ERR_ABORTED', canceled: true } },
    { method: 'OPTIONS', urlSha256, status: 204, initiator: [] }
  ]
  const result = requestEvidence(routed, observed)
  assert.equal(result.length, 4)
  assert.deepEqual(result.filter(row => row.http).map(row => row.http.id), [1, 2, 3])
  assert.equal(result.find(row => row.method === 'OPTIONS').http, undefined)
  assert.equal(result[0].initiatorAssociation, undefined)
  assert.equal(result[1].initiatorAssociation, undefined)
  assert.equal(result[2].initiatorAssociation, 'unique_method_url')
  assert.deepEqual(result[2].networkFailure, { code: 'net::ERR_ABORTED', canceled: true })
  assert.equal(result[2].http.id, 3)
})

test('incomplete navigation preserves observed request failures and still cleans up', async t => {
  const cdp = new EventEmitter()
  cdp.send = async (method, params, sessionId) => {
    if (method === 'Target.getBrowserContexts') return { browserContextIds: ['task-context'] }
    if (method === 'Target.setAutoAttach' && !sessionId) cdp.emit('Target.attachedToTarget', {
      sessionId: 'page-session', targetInfo: { targetId: 'page-target', type: 'page', browserContextId: 'task-context' }
    })
    return {}
  }
  cdp.close = async () => {}
  let contextsClosed = 0, browsersClosed = 0
  const page = { url: () => 'about:blank', frames: () => [], on() {}, async goto() {
    cdp.emit('Network.requestWillBeSent', { requestId: 'document', request: { url: 'https://example.org/', method: 'GET' },
      frameId: 'main', initiator: { type: 'other' } }, 'page-session')
    cdp.emit('Network.loadingFailed', { requestId: 'document', errorText: 'net::ERR_ABORTED', canceled: true }, 'page-session')
    throw new Error('page.goto: net::ERR_ABORTED at https://example.org/?token=private-test-marker')
  } }
  const context = { async routeWebSocket() {}, async route() {}, async newPage() { return page },
    async newCDPSession() { return { async send() { return { frameTree: { frame: { id: 'main' } } } }, async detach() {} } },
    async close() { contextsClosed++ } }
  t.mock.method(chromium, 'launch', async () => ({ version: () => 'fixture',
    async newContext() { return context }, async close() { browsersClosed++ } }))
  const report = await analyze({ sourceContent: 'host="https://example.org"', python: process.env.REVERSE_TEST_PYTHON,
    egressScript: fileURLToPath(new URL('../../scripts/source_egress.py', import.meta.url)) },
  { settleMillis: 0, connectProtocol: async () => cdp })
  assert.equal(report.navigation.errorCode, 'net::ERR_ABORTED')
  assert.equal(report.navigation.completed, false)
  assert.deepEqual(report.failures, ['REVERSE_NAVIGATION_INCOMPLETE'])
  assert.equal(report.requests[0].networkFailure.code, 'net::ERR_ABORTED')
  assert.equal(report.collectionStatus, 'unavailable')
  assert.equal(report.cleanupConfirmed, true)
  assert.equal(contextsClosed, 1)
  assert.equal(browsersClosed, 1)
  assert.ok(!JSON.stringify(report).includes('private-test-marker'))
})

test('CDP evidence distinguishes identical URLs in separate targets and keeps redirect hops', () => {
  const first = { method: 'GET', urlSha256: 'same-url', requestIdSha256: 'fetch-first',
    cdpRequestIdSha256: 'network-first', httpAssociation: 'cdp_fetch_request', status: 302, http: { id: 1 } }
  const second = { method: 'GET', urlSha256: 'same-url', requestIdSha256: 'fetch-second',
    cdpRequestIdSha256: 'network-second', httpAssociation: 'cdp_fetch_request', status: 200, http: { id: 2 } }
  const redirect = { method: 'GET', urlSha256: 'final-url', requestIdSha256: 'fetch-redirect',
    redirectedRequestIdSha256: 'fetch-first', cdpRequestIdSha256: 'network-first',
    httpAssociation: 'cdp_fetch_request', status: 200, http: { id: 3 } }
  const report = requestEvidence([first, second, redirect], [
    { method: 'GET', urlSha256: 'same-url', requestIdSha256: 'network-first', status: 302, initiator: ['first'] },
    { method: 'GET', urlSha256: 'same-url', requestIdSha256: 'network-second', status: 200, initiator: ['second'] },
    { method: 'GET', urlSha256: 'final-url', requestIdSha256: 'network-first', status: 200, initiator: ['redirect'] }
  ])
  assert.deepEqual(report.map(row => row.initiator), [['first'], ['second'], ['redirect']])
  assert.deepEqual(report.map(row => row.http.id), [1, 2, 3])
  assert.ok(report.every(row => row.initiatorAssociation === 'cdp_network_id'))
  assert.equal(report[2].redirectedRequestIdSha256, report[0].requestIdSha256)
})

test('browser setup failures preserve a phase-bound redacted report after confirmed cleanup', async t => {
  let closed = false
  const context = { async routeWebSocket() {}, async close() { closed = true } }
  t.mock.method(chromium, 'launch', async () => ({ version: () => 'fixture',
    async newContext() { return context }, async close() {} }))
  const report = await analyze({ sourceContent: 'host="https://example.org"',
    python: process.env.REVERSE_TEST_PYTHON,
    egressScript: fileURLToPath(new URL('../../scripts/source_egress.py', import.meta.url)) },
  { connectProtocol: async () => { throw Object.assign(new Error('ws://127.0.0.1/private-test-marker'),
    { code: 'ECONNREFUSED' }) } })
  assert.equal(closed, true)
  assert.equal(report.cleanupConfirmed, true)
  assert.equal(report.navigation.status, 'not_started')
  assert.equal(report.collectionStatus, 'unavailable')
  assert.deepEqual(report.failures, ['REVERSE_BROWSER_SETUP_FAILED'])
  assert.deepEqual(report.setupFailure, { stage: 'cdp_connect', errorType: 'Error', code: 'ECONNREFUSED' })
  assert.ok(!JSON.stringify(report).includes('private-test-marker'))
  assert.equal(report.businessChainVerified, false)
})

test('concurrent broker responses retain their request identity and exit rejects outstanding work', async () => {
  const broker = new EventEmitter(), sent = []
  broker.stdout = new PassThrough()
  broker.stdin = new Writable({ write(chunk, encoding, done) { sent.push(JSON.parse(chunk)); done() } })
  const transport = brokerRequests(broker)
  try {
    const first = transport.fetch({ url: 'https://slow.example/' })
    const second = transport.fetch({ url: 'https://fast.example/' })
    assert.equal(sent.length, 2)
    broker.stdout.write(JSON.stringify({ requestId: sent[1].requestId, result: { second: true } }) + '\n')
    assert.deepEqual(await second, { second: true })
    broker.stdout.write(JSON.stringify({ requestId: sent[0].requestId, result: { first: true } }) + '\n')
    assert.deepEqual(await first, { first: true })
    const outstanding = transport.fetch({ url: 'https://other.example/' })
    const rejected = assert.rejects(outstanding, { message: 'REVERSE_TRANSPORT_FAILED' })
    broker.emit('exit')
    await rejected
  } finally { transport.close(); broker.stdout.destroy(); broker.stdin.destroy() }
})

test('real broker accepts UTF-8 source and reports browser startup without leaking exceptions', async t => {
  let launches = 0
  t.mock.method(chromium, 'launch', async () => {
    launches++
    throw new Error('private exception text')
  })
  await assert.rejects(analyze({
    sourceContent: '\u540d\u79f0="\u7247\u5e93"\nhost="https://example.org"',
    python: process.env.REVERSE_TEST_PYTHON,
    egressScript: fileURLToPath(new URL('../../scripts/source_egress.py', import.meta.url))
  }), { message: 'REVERSE_BROWSER_START_FAILED' })
  assert.equal(launches, 1)
})

test('remote cleanup is bound to the current task and requires completed responses', async () => {
  const broker = new EventEmitter()
  broker.stdout = new PassThrough()
  broker.stdin = new Writable({ write(chunk, encoding, done) { done() } })
  const transport = brokerRequests(broker, 'current-task')
  try {
    assert.equal(transport.remoteCleanupConfirmed, false)
    broker.stdout.write(JSON.stringify({ transportState: { phase: 'started', taskId: 'current-task' } }) + '\n')
    const result = transport.fetch({ url: 'https://example.org/' })
    broker.stdout.write(JSON.stringify({ requestId: 1, result: { observed: true } }) + '\n')
    assert.deepEqual(await result, { observed: true })
    broker.stdout.write(JSON.stringify({ transportState: { phase: 'cleaned', taskId: 'current-task' } }) + '\n')
    assert.equal(transport.remoteCleanupConfirmed, true)
  } finally { transport.close(); broker.stdout.destroy(); broker.stdin.destroy() }

  const wrong = new EventEmitter()
  wrong.stdout = new PassThrough()
  wrong.stdin = new Writable({ write(chunk, encoding, done) { done() } })
  const other = brokerRequests(wrong, 'current-task')
  try {
    const pending = other.fetch({ url: 'https://example.org/' })
    const rejected = assert.rejects(pending, { message: 'REVERSE_TRANSPORT_FAILED' })
    wrong.stdout.write(JSON.stringify({ transportState: { phase: 'cleaned', taskId: 'another-task' } }) + '\n')
    await rejected
    assert.equal(other.remoteCleanupConfirmed, false)
  } finally { other.close(); wrong.stdout.destroy(); wrong.stdin.destroy() }
})
