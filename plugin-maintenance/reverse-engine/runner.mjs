import { chromium } from 'playwright-core'
import CDP from 'chrome-remote-interface'
import { spawn } from 'node:child_process'
import { STATUS_CODES } from 'node:http'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { sha, location, requestMatches, stackFrames, primitive, responseSignals, networkContract, retainNetworkContract, scriptCandidates, scriptExcerpt, limitScriptExcerpts, recoverScript } from './analysis.mjs'

const scriptKey = (sessionId, scriptId) => sha(sessionId).slice(0, 16) + ':' + scriptId
const sessionFrames = (stack, sessionId) => stackFrames(stack, new Map()).map(frame =>
  ({ ...frame, scriptId: scriptKey(sessionId, frame.scriptId) }))

export const httpResponsePhrase = status => STATUS_CODES[status] || 'Unassigned Status'

export function browserEnvironment(environment = process.env) {
  return Object.fromEntries(Object.entries(environment).filter(([name]) =>
    ['SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH'].includes(name.toUpperCase())))
}

export function browserNetworkFailure(event) {
  const code = String(event.errorText || '').match(/\bnet::ERR_[A-Z0-9_]{1,100}\b/)?.[0]
  const result = { code: code || 'BROWSER_NETWORK_ERROR_UNCLASSIFIED', canceled: event.canceled === true }
  for (const [field, value] of [['blockedReason', event.blockedReason], ['corsError', event.corsErrorStatus?.corsError]])
    if (typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value)) result[field] = value
  return result
}

export function navigationEvidence(requestedUrl, currentUrl, error) {
  const result = { requested: location(requestedUrl), current: location(currentUrl),
    waitUntil: 'domcontentloaded', timeoutMillis: 45000, completed: !error,
    status: !error ? 'completed' : error.name === 'TimeoutError' ? 'timeout' : 'failed' }
  if (error) result.errorCode = error.name === 'TimeoutError' ? 'NAVIGATION_TIMEOUT'
    : browserNetworkFailure({ errorText: error.message }).code
  return result
}

export function pageSourceBinding(pageUrl, origins, observedPageUrlSha256 = '') {
  const url = new URL(pageUrl)
  if (origins.includes(url.origin)) return 'source_literal_origin'
  if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
      && observedPageUrlSha256 === sha(pageUrl)) return 'same_run_observed_page'
  throw new Error('REVERSE_PAGE_NOT_BOUND_TO_SOURCE')
}

export function setupFailureEvidence(stage, error) {
  const phases = ['cdp_session_create', 'cdp_command_line', 'cdp_endpoint_read', 'cdp_endpoint_connect']
  return { stage: phases.includes(error?.reverseStage) ? error.reverseStage : stage,
    errorType: /^[A-Za-z]{1,40}$/.test(error?.name || '') ? error.name : 'Error',
    code: /^[A-Z][A-Z0-9_]{0,79}$/.test(error?.code || '') ? error.code
      : /^[A-Z][A-Z0-9_]{0,79}$/.test(error?.message || '') ? error.message : 'BROWSER_SETUP_UNCLASSIFIED' }
}

export function transportFailureEvidence(stage, error) {
  const message = String(error?.message || '')
  const known = [
    ['HTTP_STATUS_PHRASE_INVALID', /invalid http status code or phrase/i],
    ['PROTOCOL_METHOD_UNSUPPORTED', /wasn't found|method not found|not supported/i],
    ['INTERCEPTION_NO_LONGER_ACTIVE', /invalid interception|interception.*(?:not found|already handled)|no resource with given identifier/i],
    ['TARGET_SESSION_CLOSED', /(?:target|session|connection).*(?:closed|detached)/i],
    ['RESPONSE_HEADERS_INVALID', /invalid.*header|header.*invalid/i],
    ['PROTOCOL_PARAMETERS_INVALID', /invalid parameters/i]
  ].find(([, pattern]) => pattern.test(message))
  return { stage, errorType: /^[A-Za-z]{1,40}$/.test(error?.name || '') ? error.name : 'Error',
    code: known?.[0] || (/^[A-Z][A-Z0-9_]{0,79}$/.test(error?.code || '')
      ? error.code : 'REVERSE_TRANSPORT_UNCLASSIFIED') }
}

export function pageErrorEvidence(error) {
  const message = String(error?.message || '')
  const missing = message.match(/^([A-Za-z_$][\w$]{0,79}) is not defined$/)
  return { errorType: /^[A-Za-z]{1,40}$/.test(error?.name || '') ? error.name : 'Error',
    messageSha256: sha(message), ...(missing ? { missingSymbol: missing[1] } : {}) }
}

export function runtimeExceptionEvidence(details, session) {
  const frames = sessionFrames(details.stackTrace, session.id)
  const position = { scriptId: details.scriptId, url: details.url,
    lineNumber: details.lineNumber, columnNumber: details.columnNumber }
  return { authority: 'task_browser_runtime', targetType: session.type,
    ...pageErrorEvidence({ name: details.exception?.className,
      message: details.exception?.description || details.text }),
    location: sessionFrames({ callFrames: [position] }, session.id)[0], frames }
}

export async function pageDomEvidence(page) {
  const frames = page.frames(), result = { authority: 'task_browser_dom',
    frameCount: frames.length, framesTruncated: frames.length > 8, frames: [],
    formValuesStored: false, storageRead: false, interactionsPerformed: false }
  for (const frame of frames.slice(0, 8)) {
    const row = { ...location(frame.url()), frameIndex: frames.indexOf(frame),
      parentFrameIndex: frames.indexOf(frame.parentFrame()) }
    try {
      const dom = await frame.locator('html').evaluate(root => {
        const document = root.ownerDocument
        const name = value => /^[A-Za-z_][A-Za-z0-9_-]{0,79}$/.test(value || '') ? value : ''
        const text = value => String(value || '').replace(/\s+/g, ' ').trim()
          .replace(/(?:bearer\s+|sk-)[A-Za-z0-9._-]{12,}/gi, '[redacted]').slice(0, 120)
        const playback = /play|watch|login|sign.?in|\u64ad\u653e|\u89c2\u770b|\u767b\u5f55|\u4f1a\u5458|\u4ed8\u8d39/i
        const candidates = [...document.querySelectorAll(
          'button,[role="button"],video,audio,iframe,input[type="password"],a[href],[id*="player" i],[class*="player" i]')]
          .slice(0, 256).filter(node => node.tagName !== 'A' || playback.test(node.textContent || ''))
        const controls = candidates.slice(0, 32).map(node => {
          const rect = node.getBoundingClientRect()
          const value = { tag: node.tagName.toLowerCase(), id: name(node.id),
            classes: [...node.classList].map(name).filter(Boolean).slice(0, 6),
            text: ['INPUT', 'VIDEO', 'AUDIO', 'IFRAME'].includes(node.tagName)
              ? '' : text(node.getAttribute('aria-label') || node.textContent),
            visible: rect.width > 0 && rect.height > 0, disabled: node.matches(':disabled') }
          if (node instanceof HTMLMediaElement) value.media = {
            readyState: node.readyState, networkState: node.networkState,
            paused: node.paused, ended: node.ended, errorCode: node.error?.code || 0,
            source: node.currentSrc || node.src || '' }
          return value
        })
        const scripts = [...document.scripts]
        const scriptDeclarations = scripts.slice(0, 32).map((node, index) => ({
          index, source: node.src, type: ['module', 'application/ld+json', 'application/json'].includes(node.type)
            ? node.type : 'classic', async: node.async, defer: node.defer,
          entrypoint: node.type === 'module' || node.hasAttribute('data-main'),
          requireModule: /^[A-Za-z0-9_./-]{1,160}$/.test(node.dataset.requiremodule || '')
            ? node.dataset.requiremodule : ''
        }))
        return { readyState: document.readyState, controls, controlsTruncated: candidates.length > 32,
          stylesheets: { declared: document.querySelectorAll('link[rel~="stylesheet"]').length,
            loaded: [...document.styleSheets].filter(sheet => sheet.href).length },
          scriptCount: scripts.length, scriptsTruncated: scripts.length > 32, scriptDeclarations,
          preloadedScripts: [...document.querySelectorAll('link[rel~="preload"][as="script"],link[rel~="modulepreload"]')]
            .slice(0, 16).map(node => node.href) }
      }, undefined, { timeout: 1500 })
      dom.preloadedScripts = dom.preloadedScripts.map(location)
      dom.scriptDeclarations = dom.scriptDeclarations.map(({ source, ...value }) => ({ ...value,
        sourceKind: source ? 'resource' : 'inline', ...(source ? location(source) : {}) }))
      for (const control of dom.controls) if (control.media) {
        const { source, ...state } = control.media
        control.media = { ...state, sourcePresent: !!source, ...(source ? location(source) : {}) }
      }
      Object.assign(row, { status: 'observed' }, dom)
    } catch { row.status = 'unavailable'; row.errorCode = 'FRAME_DOM_UNAVAILABLE' }
    result.frames.push(row)
  }
  return result
}

export async function settleBrowserCapture(state, idleMillis, deadline, now = Date.now,
  delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))) {
  const started = now()
  while (now() < deadline) {
    if (!state.inFlight.size && !state.pending.size && now() - state.lastActivity >= idleMillis
        && !state.waitingForAccess?.())
      return { status: 'idle', elapsedMilliseconds: now() - started, pendingAtClose: 0, idleMillis }
    await delay(Math.min(100, deadline - now()))
  }
  return { status: 'deadline', elapsedMilliseconds: now() - started,
    pendingAtClose: state.inFlight.size + state.pending.size, idleMillis }
}

export function pageAccessEvidence(rows, mainFrameIdSha256, requestedUrl, currentUrl) {
  const requested = typeof requestedUrl === 'string' ? location(requestedUrl) : requestedUrl
  const currentLocation = typeof currentUrl === 'string' ? location(currentUrl) : currentUrl
  const documents = rows.filter(row => row.resourceType === 'document' && row.frameIdSha256 === mainFrameIdSha256)
  const current = documents.findLast(row => row.urlSha256 === currentLocation.urlSha256) || documents.at(-1)
  const documentUrlMatchesCurrent = current?.urlSha256 === currentLocation.urlSha256
  const delivered = current?.browserDeliveryVerified === true
  const challengeObserved = documents.some(row => row.browserDeliveryVerified && row.responseEvidence?.challengePage)
  const status = !delivered ? 'unavailable' : current.networkFailed ? 'network_failed'
    : current.responseEvidence?.challengePage ? documentUrlMatchesCurrent ? 'challenge_pending' : 'challenge_unresolved'
      : !documentUrlMatchesCurrent ? 'unavailable'
    : !current.http?.responseComplete ? 'incomplete' : current.status >= 400 ? 'http_error'
      : current.status >= 300 ? 'redirect' : current.status >= 200 && current.http.responseBytes > 0
        ? 'content_observed' : 'empty_document'
  return { authority: 'task_browser_main_document', status, urlSha256: currentLocation.urlSha256, origin: currentLocation.origin,
    requestedPageObserved: documents.some(row => row.urlSha256 === requested.urlSha256 && row.http),
    challengeObserved, recoveredAfterChallenge: challengeObserved && status === 'content_observed',
    automaticChallengeSolving: false, businessSuccessVerified: false,
    ...(current ? { responseStatus: current.status, responseComplete: !!current.http?.responseComplete,
      browserDeliveryVerified: delivered, documentUrlMatchesCurrent, documentUrlSha256: current.urlSha256,
      challengeBasis: current.responseEvidence?.challengeBasis || 'not_established' } : {}),
    nextAction: status === 'content_observed' ? 'continue_diagnostics'
      : ['challenge_pending', 'challenge_unresolved'].includes(status) ? 'authorized_access_required' : 'inspect_document_failure' }
}

export function requestEvidence(routed, observed) {
  const rows = [], handled = new Set()
  const key = row => row.method + ':' + row.urlSha256
  for (const row of routed) {
    const related = observed.filter(item => key(item) === key(row)
      && (!row.cdpRequestIdSha256 || item.requestIdSha256 === row.cdpRequestIdSha256))
    if (related.length === 1 && (row.status == null || related[0].status == null || row.status === related[0].status)) {
      row.cdpRequestIdSha256 = related[0].requestIdSha256
      row.initiatorType = related[0].initiatorType
      row.initiator = related[0].initiator
      if (related[0].networkTargetType) row.networkTargetType = related[0].networkTargetType
      row.frameIdSha256 = related[0].frameIdSha256
      row.initiatorAssociation = row.httpAssociation === 'cdp_fetch_request' ? 'cdp_network_id' : 'unique_method_url'
      if (related[0].networkFailed) {
        row.networkFailed = true
        row.networkFailure = related[0].networkFailure
      }
    }
    handled.add(key(row))
    rows.push(row)
  }
  rows.push(...observed.filter(row => !handled.has(key(row))))
  return rows.sort((a, b) => Number(b.targetMatched) - Number(a.targetMatched)).slice(0, 64)
}

export async function readBrowserEndpoint(profile, read = readFile,
  delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)), onAttempt = () => {}) {
  for (let attempt = 0; ; attempt++) {
    try {
      const endpoint = await read(join(profile, 'DevToolsActivePort'), 'utf8')
      onAttempt({ attempt: attempt + 1, status: 'ready' })
      return endpoint
    }
    catch (error) {
      error.reverseStage = 'cdp_endpoint_read'
      onAttempt({ attempt: attempt + 1, status: 'failed',
        code: ['EBUSY', 'ENOENT', 'EACCES'].includes(error.code) ? error.code : 'ENDPOINT_READ_FAILED' })
      if (!['EBUSY', 'ENOENT'].includes(error.code) || attempt === 3) throw error
      await delay(100)
    }
  }
}

export async function browserProtocol(browser, onEndpointRead = () => {}) {
  let stage = 'cdp_session_create'
  try {
    const session = await browser.newBrowserCDPSession()
    let arguments_
    stage = 'cdp_command_line'
    try { ({ arguments: arguments_ } = await session.send('Browser.getBrowserCommandLine')) }
    finally { await session.detach() }
    const profile = arguments_.find(value => value.startsWith('--user-data-dir='))?.slice('--user-data-dir='.length)
    if (!profile) throw new Error('REVERSE_BROWSER_SETUP_FAILED')
    // This is the fresh task browser's profile, never a user's existing browser profile.
    stage = 'cdp_endpoint_read'
    const [port, endpoint] = (await readBrowserEndpoint(profile, readFile, undefined, onEndpointRead)).trim().split(/\r?\n/)
    if (!/^[0-9]{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535
        || !/^\/devtools\/browser\/[A-Za-z0-9-]+$/.test(endpoint)) throw new Error('REVERSE_BROWSER_SETUP_FAILED')
    stage = 'cdp_endpoint_connect'
    return await CDP({ target: `ws://127.0.0.1:${port}${endpoint}`, local: true })
  } catch (error) {
    error.reverseStage ??= stage
    throw error
  }
}

export async function managedBrowserTargets(protocol, contextId, { fetch, excludedResourceTypes, onRequest,
  onNetwork, onScript, onPause, onException = () => {}, onContract = () => {}, onTargetSetup = () => {},
  fail, tracked, requestPattern = '', isClosing = () => false }) {
  const sessions = new Map(), networkOwners = new Map()
  const transportId = 'task-browser:' + contextId
  const filter = [{ type: 'page' }, { type: 'iframe' }, { type: 'worker' }, { type: 'shared_worker' }, { exclude: true }]
  const attach = { autoAttach: true, waitForDebuggerOnStart: true, flatten: true, filter }
  const identity = (sessionId, requestId) => sha(sessionId + ':' + requestId)
  const send = (sessionId, method, params = {}) => protocol.send(method, params, sessionId)
  protocol.on('Target.attachedToTarget', (event, parentId) => tracked((async () => {
    const { sessionId, targetInfo } = event
    if (targetInfo.browserContextId !== contextId && !sessions.has(parentId)) {
      await send(sessionId, 'Runtime.runIfWaitingForDebugger')
      return
    }
    const session = { id: sessionId, targetId: targetInfo.targetId, type: targetInfo.type,
      send: (method, params) => send(sessionId, method, params) }
    sessions.set(sessionId, session)
    let command
    const setup = async (method, params) => { command = method; return session.send(method, params) }
    const target = { targetType: ['page', 'iframe', 'worker', 'shared_worker'].includes(session.type) ? session.type : 'other',
      targetIdSha256: sha(targetInfo.targetId), sessionIdSha256: sha(sessionId) }
    try {
      await setup('Network.enable', { maxPostDataSize: 1048576 })
      await setup('Network.setCacheDisabled', { cacheDisabled: true })
      await setup('Target.setAutoAttach', attach)
      await setup('Debugger.enable', { maxScriptsCacheSize: 1048576 })
      await setup('Debugger.setAsyncCallStackDepth', { maxDepth: 12 })
      await setup('Runtime.enable')
      if (requestPattern && !requestPattern.startsWith('sha256:'))
        await setup('DOMDebugger.setXHRBreakpoint', { url: requestPattern })
      await setup('Runtime.runIfWaitingForDebugger')
      onTargetSetup({ ...target, status: 'ready', transportBinding: 'task_browser',
        transportSessionIdSha256: sha(transportId) })
    } catch (error) {
      onTargetSetup({ ...target, status: 'failed', ...transportFailureEvidence(command, error),
        ...(Number.isInteger(error.response?.code) ? { protocolCode: error.response.code } : {}) })
      fail('REVERSE_TARGET_SETUP_FAILED')
      await protocol.send('Target.closeTarget', { targetId: targetInfo.targetId }).catch(() => {})
    }
  })()))
  protocol.on('Target.detachedFromTarget', event => sessions.delete(event.sessionId))
  for (const name of ['requestWillBeSent', 'responseReceived', 'loadingFailed'])
    protocol.on('Network.' + name, (event, sessionId) => {
      const session = sessions.get(sessionId)
      if (!session) return
      if (name === 'requestWillBeSent' && networkOwners.size < 256)
        networkOwners.set(event.requestId, { session, url: event.request.url, method: event.request.method })
      onNetwork(name, event, identity(transportId, event.requestId), sessionId)
    })
  protocol.on('Debugger.scriptParsed', (event, sessionId) => {
    if (sessions.has(sessionId)) onScript(event, sessions.get(sessionId))
  })
  protocol.on('Runtime.exceptionThrown', (event, sessionId) => {
    if (sessions.has(sessionId)) onException(event.exceptionDetails, sessions.get(sessionId))
  })
  protocol.on('Debugger.paused', (event, sessionId) => {
    if (sessions.has(sessionId)) tracked(onPause(event, sessions.get(sessionId)))
  })
  protocol.on('Fetch.requestPaused', (event, sessionId) => tracked((async () => {
    if (sessionId) return
    const command = (method, params) => protocol.send(method, params)
    const abort = () => command('Fetch.failRequest', { requestId: event.requestId, errorReason: 'Aborted' }).catch(() => {})
    if (isClosing()) return abort()
    let row, stage = 'request_prepare'
    try {
      const request = event.request, resourceType = event.resourceType.toLowerCase()
      const networkOwner = networkOwners.get(event.networkId)
      const owner = networkOwner?.url === request.url && networkOwner.method === request.method
        && sessions.has(networkOwner.session.id) ? networkOwner.session : undefined
      const frameOwner = [...sessions.values()].find(session =>
        ['page', 'iframe'].includes(session.type) && session.targetId === event.frameId)
      const session = owner || frameOwner
      if (!session) {
        fail('REVERSE_REQUEST_OWNER_UNAVAILABLE')
        return await abort()
      }
      if (!/^https?:\/\//.test(request.url) || !['GET', 'HEAD', 'POST'].includes(request.method)) {
        fail('REVERSE_REQUEST_NOT_SAMPLED')
        return await abort()
      }
      if (excludedResourceTypes.includes(resourceType)) return await abort()
      row = { requestIdSha256: identity(transportId, 'fetch:' + event.requestId), ...location(request.url),
        method: request.method, targetMatched: !!requestPattern && requestMatches(request.url, requestPattern),
        resourceType, targetType: session.type, frameIdSha256: sha(event.frameId || ''),
        initiatorType: 'cdp_fetch', initiator: [], httpAssociation: 'cdp_fetch_request' }
      if (event.networkId) row.cdpRequestIdSha256 = identity(transportId, event.networkId)
      if (event.redirectedRequestId) row.redirectedRequestIdSha256 = identity(transportId, 'fetch:' + event.redirectedRequestId)
      onRequest(row)
      const headers = Object.fromEntries(Object.entries(request.headers)
        .filter(([key]) => !['host', 'content-length', 'accept-encoding', 'connection'].includes(key.toLowerCase())))
      let body = ''
      if (request.postDataEntries?.length && request.postDataEntries.every(entry => typeof entry.bytes === 'string'))
        body = Buffer.concat(request.postDataEntries.map(entry => Buffer.from(entry.bytes, 'base64'))).toString('base64')
      else if (typeof request.postData === 'string') body = Buffer.from(request.postData, 'utf8').toString('base64')
      else if (request.hasPostData) {
        row.httpError = 'REVERSE_REQUEST_BODY_UNAVAILABLE'
        fail(row.httpError)
        return await abort()
      }
      stage = 'broker_http'
      const brokerStarted = performance.now()
      const result = await fetch({ method: request.method, url: request.url, headers, body })
      row.brokerRoundTripMilliseconds = Math.round(performance.now() - brokerStarted)
      if (result.transportTiming) row.transportTiming = result.transportTiming
      if (result.networkObservations?.length) row.networkObservations = result.networkObservations
      if (result.transportError) row.transportError = result.transportError
      if (result.connectionEvidence) row.connectionEvidence = result.connectionEvidence
      if (result.requestDiagnostic) row.requestDiagnostic = result.requestDiagnostic
      if (result.error) { row.httpError = result.error; fail(result.error); return await abort() }
      stage = 'broker_response'
      const { response, observation } = result
      row.http = observation
      row.status = response.status
      onContract(networkContract(row, { headers, body }, response, observation))
      if (row.targetMatched || row.status >= 400 || resourceType === 'document')
        row.responseEvidence = responseSignals(Buffer.from(response.body, 'base64'), observation.responseContentType,
          observation.responseComplete, response.headers)
      const responseHeaders = Object.entries(response.headers).filter(([key]) => key.toLowerCase() !== 'set-cookie')
        .map(([name, value]) => ({ name, value }))
      for (const value of response.setCookies || []) responseHeaders.push({ name: 'set-cookie', value })
      stage = 'browser_response'
      await command('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: response.status,
        responsePhrase: httpResponsePhrase(response.status), responseHeaders, body: response.body })
      row.browserDeliveryVerified = true
    } catch (error) {
      if (row) {
        row.httpError = 'REVERSE_TRANSPORT_FAILED'
        row.transportFailure = transportFailureEvidence(stage, error)
      }
      fail('REVERSE_TRANSPORT_FAILED')
      await abort()
    }
  })()))
  // Browser Fetch also owns nested-worker script loads that have no frame Fetch agent.
  await protocol.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] })
  await protocol.send('Target.setAutoAttach', attach)
  return sessions
}

export function brokerRequests(broker, taskId) {
  const reader = createInterface({ input: broker.stdout }), pending = new Map()
  let sequence = 0, closed = false, remoteCleanupConfirmed = false
  const rejectAll = () => {
    closed = true
    for (const item of pending.values()) item.reject(new Error('REVERSE_TRANSPORT_FAILED'))
    pending.clear()
  }
  reader.on('line', line => {
    try {
      const { requestId, result, transportState } = JSON.parse(line)
      if (transportState) {
        if (!taskId || transportState.taskId !== taskId) { rejectAll(); return }
        if (transportState.phase === 'cleaned') {
          if (pending.size) { rejectAll(); return }
          remoteCleanupConfirmed = true
        }
        return
      }
      const item = pending.get(requestId)
      if (!item) return
      pending.delete(requestId)
      item.resolve(result)
    } catch { rejectAll() }
  })
  broker.once('exit', rejectAll)
  broker.once('error', rejectAll)
  return {
    get remoteCleanupConfirmed() { return remoteCleanupConfirmed },
    fetch(value) {
      if (closed) return Promise.reject(new Error('REVERSE_TRANSPORT_EXITED'))
      if (sequence >= 65) return Promise.resolve({ error: 'EGRESS_REQUEST_BUDGET' })
      const requestId = ++sequence
      const promise = new Promise((resolve, reject) => {
        pending.set(requestId, { resolve, reject })
        broker.stdin.write(JSON.stringify({ requestId, value }) + '\n', error => { if (error) rejectAll() })
      })
      return promise
    },
    close() { reader.close(); rejectAll() }
  }
}

// One process, temporary browser profile and HTTP session per platform repair task.
export async function analyze(input, options = {}) {
  const report = { schemaVersion: 1, authority: input.originInspectionOnly
    ? 'host_source_origin_observed' : 'host_reverse_browser_observed',
    mode: 'reverse_analysis_v1', taskId: input.taskId, repairId: input.repairId,
    runId: input.runId, artifactSha256: input.artifactSha256,
    requests: [], pauses: [], scripts: [], pageErrors: [], runtimeExceptions: [], targetSetups: [], networkContracts: [], failures: [],
    networkContractCapture: { maxRecords: 4, maxBodyBytes: 65536, maxStoredBytes: 32768, rawBodiesStored: false },
    transport: 'maintenance_pinned_public_http', credentialsImported: false,
    cookieAuthority: input.originInspectionOnly ? 'none' : 'task_browser_store',
    networkLocation: input.tunnelsFile ? 'source_runner_host' : 'controller_host',
    excludedResourceTypes: ['image', 'media', 'font', ...(input.requestPattern || input.pageUrl ? [] : ['stylesheet'])],
    businessChainVerified: false, publishEligible: false, cleanupConfirmed: false }
  const scripts = new Map(), requests = new Map(), networkCurrent = new Map(), routed = [], pending = new Set()
  let networkSequence = 0
  let browser, context, broker, transport, sandboxContext, protocol, brokerDone, pageUrl
  let setupStage = 'not_started'
  let failureCode = 'REVERSE_TRANSPORT_START_FAILED'
  let brokerExited = false, closing = false, samplingClosed = false, captureFailure
  let browserCleanupConfirmed = false
  const inFlight = new Set()
  const activity = { inFlight, pending, lastActivity: Date.now() }
  const captureDeadline = Date.now() + 58000
  const target = input.requestPattern || ''
  const match = url => requestMatches(url, target)
  const fail = code => { if (!report.failures.includes(code) && report.failures.length < 16) report.failures.push(code) }
  function tracked(task) {
    pending.add(task)
    task.catch(() => fail('REVERSE_OBSERVATION_PARTIAL')).finally(() => pending.delete(task))
  }
  try {
    const arguments_ = input.tunnelsFile
      ? ['-I', '-X', 'utf8', fileURLToPath(new URL('remote_broker.py', import.meta.url)), input.egressScript,
        input.tunnelsFile, input.tunnelScript, input.taskId]
      : ['-I', '-X', 'utf8', fileURLToPath(new URL('broker.py', import.meta.url)), input.egressScript]
    broker = spawn(input.python, arguments_,
      { stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true, env: { ...process.env, PYTHONIOENCODING: 'utf-8' } })
    transport = brokerRequests(broker, input.taskId)
    brokerDone = new Promise(resolve => broker.once('close', () => {
      brokerExited = true
      resolve()
    }))
    function fetch(value) {
      if (brokerExited || closing) return Promise.reject(new Error('REVERSE_TRANSPORT_EXITED'))
      const task = transport.fetch(value)
      inFlight.add(task)
      task.then(() => inFlight.delete(task), () => inFlight.delete(task))
      return task
    }
    failureCode = 'REVERSE_ORIGIN_DISCOVERY_FAILED'
    const discovery = await fetch({ discoverOrigins: input.sourceContent,
      replayTargets: input.replayTargets || [], replayHints: input.replayHints || [] })
    if (!Array.isArray(discovery.origins) || !discovery.origins.length) throw new Error('REVERSE_ORIGINS_REQUIRED')
    const recoveredPage = !input.pageUrl && discovery.replayPageUrl || ''
    if (recoveredPage && !(input.replayTargets || []).includes(sha(recoveredPage))) throw new Error('REVERSE_REPLAY_TARGET_INVALID')
    pageUrl = input.pageUrl || recoveredPage || discovery.origins[0] + '/'
    report.pageTargetSelection = input.pageUrl ? 'explicit_observed_target'
      : recoveredPage ? 'host_observed_static_resolution' : 'source_origin_fallback'
    report.pageSourceBinding = pageSourceBinding(pageUrl, discovery.origins,
      recoveredPage ? sha(recoveredPage) : input.observedPageUrlSha256)
    report.pageUrlSha256 = sha(pageUrl)
    report.operation = input.originInspectionOnly ? 'source_origin_inspection' : 'browser_observation'
    if (input.originInspectionOnly) {
      failureCode = 'REVERSE_ORIGIN_INSPECTION_FAILED'
      const inspected = input.searchForOrigins
        ? await fetch({ searchOriginCandidates: { sourceContent: input.sourceContent, pageUrl } })
        : await fetch({ inspectOriginPool: { sourceContent: input.sourceContent, pageUrl,
          publishedRedirects: input.publishedRedirects || [], modelOrigins: input.modelOrigins || [] } })
      if (input.searchForOrigins) {
        if (!inspected.originSearch) throw new Error('REVERSE_ORIGIN_INSPECTION_FAILED')
        report.originSearch = inspected.originSearch
        inspected.originPool = { selectionApplied: false, businessChainVerified: false }
      }
      if (!inspected.originPool) throw new Error('REVERSE_ORIGIN_INSPECTION_FAILED')
      report.originPool = inspected.originPool
      report.browserStarted = false
      browserCleanupConfirmed = true
    } else {
    failureCode = 'REVERSE_BROWSER_START_FAILED'
    browser = await chromium.launch({ executablePath: input.browserExecutable, headless: true,
      chromiumSandbox: true, timeout: 15000, env: browserEnvironment(), args: [
        '--disable-background-networking', '--disable-quic', '--disable-breakpad',
        '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
        '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1',
        '--proxy-server=http://127.0.0.1:9', '--proxy-bypass-list=<-loopback>'
      ] })
    failureCode = 'REVERSE_BROWSER_SETUP_FAILED'
    report.browserVersion = browser.version()
    setupStage = 'context_create'
    context = await browser.newContext({ ignoreHTTPSErrors: false, serviceWorkers: 'block', acceptDownloads: false })
    setupStage = 'websocket_guard'
    await context.routeWebSocket('**/*', socket => { socket.close(); fail('REVERSE_WEBSOCKET_NOT_SAMPLED') })
    setupStage = 'cdp_connect'
    report.endpointReads = []
    protocol = await (options.connectProtocol || browserProtocol)(browser, row => report.endpointReads.push(row))
    setupStage = 'context_identity'
    const { browserContextIds } = await protocol.send('Target.getBrowserContexts')
    if (browserContextIds.length !== 1) throw new Error('REVERSE_BROWSER_CONTEXT_IDENTITY_INVALID')
    const frames = sessionFrames
    setupStage = 'target_interception'
    const sessions = await managedBrowserTargets(protocol, browserContextIds[0], {
      fetch, excludedResourceTypes: report.excludedResourceTypes, fail, tracked, requestPattern: target,
      isClosing: () => closing || samplingClosed,
      onTargetSetup(row) {
        if (report.targetSetups.length < 32) report.targetSetups.push(row)
        else report.targetSetupsTruncated = true
      },
      onContract: contract => retainNetworkContract(report, contract),
      onRequest(row) {
        activity.lastActivity = Date.now()
        if (routed.length < 64) routed.push(row)
      },
      onScript(script, session) {
        activity.lastActivity = Date.now()
        if (scripts.size < 128 && script.scriptId)
          scripts.set(scriptKey(session.id, script.scriptId), { ...script, session })
      },
      onException(details, session) {
        if (!samplingClosed && report.runtimeExceptions.length < 8)
          report.runtimeExceptions.push(runtimeExceptionEvidence(details, session))
      },
      onNetwork(name, event, requestIdSha256, sessionId) {
        if (name !== 'requestWillBeSent') {
          const row = networkCurrent.get(requestIdSha256)
          if (row && name === 'responseReceived') row.status = event.response.status
          if (row && name === 'loadingFailed') { row.networkFailed = true; row.networkFailure = browserNetworkFailure(event) }
          return
        }
        if (samplingClosed) return
        const previous = networkCurrent.get(requestIdSha256)
        if (previous && event.redirectResponse) previous.status = event.redirectResponse.status
        if (requests.size >= 64) {
          if (!target || !match(event.request.url)) return
          const expendable = [...requests].find(([, row]) => !row.targetMatched)
          if (!expendable) return
          requests.delete(expendable[0])
        }
        const row = { requestIdSha256, ...location(event.request.url),
          method: event.request.method, targetMatched: !!target && match(event.request.url),
          networkTargetType: sessions.get(sessionId)?.type,
          frameIdSha256: sha(event.frameId || ''), initiatorType: event.initiator.type,
          initiator: frames(event.initiator.stack, sessionId) }
        requests.set(++networkSequence, row)
        networkCurrent.set(requestIdSha256, row)
      },
      async onPause(event, session) {
      try {
        if (report.pauses.length >= 3) return
        const pause = { reason: event.reason, frames: frames({ callFrames: event.callFrames }, session.id), locals: [] }
        for (const scope of (event.callFrames?.[0]?.scopeChain || []).filter(value => value.type === 'local').slice(0, 1)) {
          const values = await session.send('Runtime.getProperties', { objectId: scope.object.objectId, ownProperties: true })
          for (const item of (values.result || []).slice(0, 16)) {
            if (item.value) pause.locals.push({ name: item.name.slice(0, 80), ...primitive(item.value) })
          }
        }
        if (event.callFrames?.[0]?.returnValue) pause.returnValue = primitive(event.callFrames[0].returnValue)
        report.pauses.push(pause)
      } finally { await session.send('Debugger.resume').catch(() => {}) }
      }
    })
    setupStage = 'page_create'
    const page = await context.newPage()
    const pageSession = await context.newCDPSession(page)
    let mainFrameIdSha256
    try { mainFrameIdSha256 = sha((await pageSession.send('Page.getFrameTree')).frameTree.frame.id) }
    finally { await pageSession.detach() }
    page.on('pageerror', error => {
      if (!samplingClosed && report.pageErrors.length < 8) report.pageErrors.push(pageErrorEvidence(error))
    })
    failureCode = 'REVERSE_CAPTURE_FAILED'
    let navigationError
    try { await page.goto(pageUrl, { waitUntil: 'domcontentloaded', timeout: 45000 }) }
    catch (error) { navigationError = error; fail('REVERSE_NAVIGATION_INCOMPLETE') }
    report.navigation = navigationEvidence(pageUrl, page.url(), navigationError)
    activity.waitingForAccess = () => {
      const access = pageAccessEvidence(routed, mainFrameIdSha256, pageUrl, page.url())
      return access.challengeObserved && access.status !== 'content_observed'
    }
    activity.lastActivity = Date.now()
    report.samplingWindow = await settleBrowserCapture(activity, options.settleMillis ?? 4000, captureDeadline)
    samplingClosed = true
    while (inFlight.size) await Promise.allSettled([...inFlight])
    await Promise.allSettled([...pending])
    report.requests = requestEvidence(routed, [...requests.values()])
    report.targetPage = pageAccessEvidence(report.requests, mainFrameIdSha256, pageUrl, page.url())
    if (target || input.pageUrl) {
      report.dom = await pageDomEvidence(page)
      report.dom.stylesheetsSampled = !report.excludedResourceTypes.includes('stylesheet')
    }
    if (target && !target.startsWith('sha256:')) for (const session of sessions.values())
      await session.send('DOMDebugger.removeXHRBreakpoint', { url: target }).catch(() => {})
    report.requests = requestEvidence(routed, [...requests.values()])
    failureCode = 'REVERSE_SCRIPT_COLLECTION_FAILED'
    const preloads = report.dom?.frames.flatMap(frame => frame.preloadedScripts || []) || []
    const declarations = report.dom?.frames.flatMap(frame => frame.scriptDeclarations || []) || []
    report.scriptInventory = [...scripts].filter(([, script]) => script.url?.startsWith('http')).map(([id, script]) => ({
      scriptId: id, ...location(script.url), sourceLength: script.length,
      scriptKind: script.url === pageUrl ? 'page_inline' : 'resource',
      sourceStartLine: script.startLine || 0, sourceStartColumn: script.startColumn || 0,
      declared: declarations.some(row => row.urlSha256 === sha(script.url))
    }))
    const wanted = scriptCandidates(report.pauses, report.requests, scripts, pageUrl, preloads, declarations,
      report.runtimeExceptions)
    let sourceBytes = 0
    for (const [id, frame] of wanted) {
      if (report.scripts.length >= 12) break
      const info = scripts.get(id)
      if (info?.length > 1048576) { fail('REVERSE_SCRIPT_BYTE_LIMIT'); continue }
      let code
      try { code = (await info.session.send('Debugger.getScriptSource', { scriptId: info.scriptId })).scriptSource }
      catch { fail('REVERSE_SCRIPT_UNAVAILABLE'); continue }
      const size = Buffer.byteLength(code)
      if (size > 1048576 || sourceBytes + size > 1048576) { fail('REVERSE_SCRIPT_BYTE_LIMIT'); continue }
      sourceBytes += size
      const preloadedDependency = preloads.some(row => row.urlSha256 === sha(info.url))
      const declaredModule = declarations.some(row => row.urlSha256 === sha(info.url) && row.requireModule)
      const targetedResource = report.requests.some(row => row.targetMatched && row.urlSha256 === sha(info.url))
      const runtimeExceptionRelated = report.runtimeExceptions.some(error =>
        error.location.scriptId === id || error.frames.some(frame => frame.scriptId === id))
      const item = { ...location(info?.url || ''), scriptId: id, sourceSha256: sha(code),
        sourceBytes: size, scriptKind: info.url === pageUrl ? 'page_inline' : 'resource',
        preloadedDependency, declaredModule, targetedResource, runtimeExceptionRelated, executionVerified: false,
        sourceStartLine: info.startLine || 0, sourceStartColumn: info.startColumn || 0,
        excerpt: scriptExcerpt(code, frame, info,
          preloadedDependency || targetedResource || info.url === pageUrl ? 131072 : declaredModule ? 32768 : 6000) }
      if (input.recoverScripts && (!report.targetPage.challengeObserved || report.targetPage.status === 'content_observed')) {
        sandboxContext ??= await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false })
        await sandboxContext.route('**/*', route => route.abort())
        await sandboxContext.routeWebSocket('**/*', socket => socket.close())
        const sandboxPage = await sandboxContext.newPage()
        const sandboxCdp = await sandboxContext.newCDPSession(sandboxPage)
        try {
          item.recovery = await recoverScript(code, async expression => {
            const value = await sandboxCdp.send('Runtime.evaluate', { expression, returnByValue: true, timeout: 1500 })
            if (value.exceptionDetails || !Array.isArray(value.result?.value)) throw new Error('REVERSE_SANDBOX_FAILED')
            return value.result.value
          })
        } catch { item.recovery = { status: 'unavailable', originalSha256: sha(code), appliedToSource: false }; fail('REVERSE_RECOVERY_UNAVAILABLE') }
        finally { await sandboxPage.close() }
      }
      report.scripts.push(item)
    }
    report.scriptRecoveryDeferred = !!input.recoverScripts && report.targetPage.challengeObserved
      && report.targetPage.status !== 'content_observed'
    for (const dependency of preloads) {
      dependency.httpComplete = report.requests.some(row => row.urlSha256 === dependency.urlSha256
        && row.http?.responseComplete && row.status === 200)
      dependency.scriptParsed = [...scripts.values()].some(script => sha(script.url || '') === dependency.urlSha256)
      dependency.sourceCollected = report.scripts.some(script => script.urlSha256 === dependency.urlSha256)
      dependency.executionVerified = false
    }
    report.collectionStatus = report.requests.some(row => row.http) ? 'observed' : 'unavailable'
    report.targetRequestObserved = !!target && report.requests.some(row => row.targetMatched && row.http)
    failureCode = 'REVERSE_CLEANUP_FAILED'
    closing = true
    await context.close()
    context = undefined
    await sandboxContext?.close()
    sandboxContext = undefined
    await browser.close()
    browserCleanupConfirmed = true
    browser = undefined
    await protocol.close()
    protocol = undefined
    }
    broker.stdin.end()
    await Promise.race([brokerDone, new Promise((_, reject) => setTimeout(() => reject(new Error('REVERSE_CLEANUP_FAILED')), 3000))])
    report.cleanupConfirmed = brokerExited && (!input.tunnelsFile || transport.remoteCleanupConfirmed)
  } catch (error) { captureFailure = error }
  finally {
    closing = true
    if (context || sandboxContext || browser || broker && !brokerExited) {
      await context?.close().catch(() => {})
      await sandboxContext?.close().catch(() => {})
      if (browser) await browser.close().then(() => { browserCleanupConfirmed = true }, () => {})
      if (broker && !brokerExited) {
        broker.stdin.end()
        await Promise.race([brokerDone, new Promise(resolve => setTimeout(resolve, 3000))])
        if (!brokerExited) broker.kill()
      }
    }
    await protocol?.close().catch(() => {})
    if (captureFailure && failureCode === 'REVERSE_BROWSER_SETUP_FAILED') {
      report.cleanupConfirmed = browserCleanupConfirmed && brokerExited
        && (!input.tunnelsFile || transport?.remoteCleanupConfirmed)
      if (report.cleanupConfirmed) {
        fail(failureCode)
        report.setupFailure = setupFailureEvidence(setupStage, captureFailure)
        report.navigation = { requested: location(pageUrl), current: location('about:blank'),
          completed: false, status: 'not_started' }
        report.collectionStatus = 'unavailable'
        report.targetRequestObserved = false
      }
    }
    transport?.close()
  }
  if (captureFailure && !report.setupFailure || !report.cleanupConfirmed) throw new Error(failureCode, { cause: captureFailure })
  return limitScriptExcerpts(report)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    let text = ''
    for await (const part of process.stdin) { text += part; if (text.length > 1200000) throw new Error('REVERSE_INPUT_LIMIT') }
    process.stdout.write(JSON.stringify(await analyze(JSON.parse(text))))
  } catch (error) {
    const codes = ['REVERSE_TRANSPORT_START_FAILED', 'REVERSE_ORIGIN_DISCOVERY_FAILED',
      'REVERSE_BROWSER_START_FAILED', 'REVERSE_BROWSER_SETUP_FAILED', 'REVERSE_CAPTURE_FAILED',
      'REVERSE_SCRIPT_COLLECTION_FAILED', 'REVERSE_ORIGIN_INSPECTION_FAILED', 'REVERSE_CLEANUP_FAILED']
    process.stdout.write(JSON.stringify({ errorCode: codes.includes(error.message) ? error.message : 'REVERSE_INPUT_INVALID' }))
  }
}
