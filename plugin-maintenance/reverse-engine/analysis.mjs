import { createHash } from 'node:crypto'
import { webcrack } from 'webcrack'

export const sha = value => createHash('sha256').update(value).digest('hex')
export function requestMatches(url, pattern) {
  return pattern.startsWith('sha256:') ? sha(url) === pattern.slice(7) : url.includes(pattern)
}
export function location(value) {
  try {
    const url = new URL(value)
    return { urlSha256: sha(value), origin: ['http:', 'https:'].includes(url.protocol) ? url.origin : 'inline' }
  } catch { return { urlSha256: sha(value || ''), origin: 'inline' } }
}
export function stackFrames(stack, scripts, result = [], depth = 0) {
  if (!stack || depth > 4) return result
  for (const frame of (stack.callFrames || []).slice(0, 12 - result.length)) {
    result.push({ functionName: String(frame.functionName || '').slice(0, 100),
      scriptId: frame.scriptId, ...location(frame.url || scripts.get(frame.scriptId)?.url || ''),
      line: frame.lineNumber, column: frame.columnNumber })
  }
  if (result.length < 12) stackFrames(stack.parent, scripts, result, depth + 1)
  return result
}
export function primitive(value) {
  const result = { type: value?.type || 'unknown' }
  if (value && ['string', 'number', 'boolean'].includes(value.type) && Object.hasOwn(value, 'value')) {
    const encoded = JSON.stringify(value.value)
    result.valueSha256 = sha(encoded)
    result.valueBytes = Buffer.byteLength(encoded)
  }
  return result
}
export function responseSignals(body, contentType, responseComplete, headers = {}) {
  const mitigated = Object.entries(headers).some(([key, value]) =>
    key.toLowerCase() === 'cf-mitigated' && String(value).toLowerCase() === 'challenge')
  if (!mitigated && !/(?:text\/|json|javascript|xml)/i.test(contentType || '')) return undefined
  const sampled = body.subarray(0, 8192), text = sampled.toString('utf8').toLowerCase()
  const challengePage = mitigated || /\b_cf_chl_opt\s*=/.test(text)
    && text.includes('/cdn-cgi/challenge-platform/')
    && (/<title[^>]*>\s*just a moment[\s.!\u2026]*<\/title>/.test(text)
      || text.includes('enable javascript and cookies to continue'))
  const patterns = {
    login_required: /login required|log in to|please (?:log|sign) in|authentication required|\u8bf7(?:\u5148)?\u767b\u5f55|\u767b\u5f55\u540e/,
    permission_denied: /access denied|permission denied|forbidden|not authori[sz]ed|\u672a\u6388\u6743|\u65e0\u6743|\u6743\u9650\u4e0d\u8db3|\u7981\u6b62\u8bbf\u95ee/,
    session_invalid: /(?:invalid|expired) (?:session|token|authorization)|(?:session|token|authorization) (?:invalid|expired)|\u6388\u6743\u8fc7\u671f|\u4f1a\u8bdd\u8fc7\u671f|\u6388\u6743\u65e0\u6548/,
    referer_required: /(?:invalid|missing) referer|referer (?:required|invalid)|\u6765\u6e90\u6821\u9a8c\u5931\u8d25/,
    challenge: /cf-chl-|_cf_chl_opt|turnstile|captcha/
  }
  return { inspectedBytes: sampled.length, inspectionComplete: !!responseComplete && body.length <= 8192,
    sampleSha256: sha(sampled), rawBodyStored: false, challengePage,
    challengeBasis: mitigated ? 'cf_mitigated_header' : challengePage ? 'cloudflare_page_template' : 'not_established',
    signals: Object.entries(patterns).filter(([name, pattern]) => pattern.test(text)
      || name === 'challenge' && mitigated).map(([name]) => name) }
}

export function bodyContract(body, contentType = '', complete = true) {
  const result = { bodyBytes: body.length, bodySha256: sha(body), rawBodyStored: false }
  if (!complete) return { ...result, status: 'incomplete' }
  if (body.length > 65536) return { ...result, status: 'byte_limit' }
  if (!body.length) return { ...result, status: 'empty' }
  let text
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(body) }
  catch { return { ...result, status: 'opaque' } }
  let nodes = 0
  const sensitive = name => /password|passwd|token|secret|authorization|cookie|session|credential|signature|nonce|api.?key/i.test(name)
  const fieldName = name => /^[\p{L}\p{N}_$.-]{1,80}$/u.test(name) && !/^sk-/i.test(name)
    ? { name } : { nameSha256: sha(name) }
  function shape(value, name = '', depth = 0) {
    const type = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value
    const row = { type }
    if (nodes++ >= 64 || depth > 6) { result.truncated = true; return { type, truncated: true } }
    if (sensitive(name)) return { type, redacted: true }
    if (type === 'object') {
      row.fields = []
      for (const [key, child] of Object.entries(value)) {
        if (nodes >= 64) { result.truncated = true; break }
        row.fields.push({ ...fieldName(key), shape: shape(child, key, depth + 1) })
      }
    } else if (type === 'array') {
      row.length = value.length
      row.items = []
      for (const child of value.slice(0, 4)) {
        if (nodes >= 64) { result.truncated = true; break }
        row.items.push(shape(child, name, depth + 1))
      }
      if (row.items.length < value.length) result.truncated = true
    } else if (type !== 'null') {
      if (type === 'boolean' || type === 'number' && Number.isSafeInteger(value)
          && /^(code|status|errno|ret|success)$/i.test(name)) row.value = value
      else if (type === 'string' && /(?:method|format|type)$/i.test(name)
          && /^(GET|POST|HEAD|PUT|PATCH|DELETE|hls|dash|mp4|m3u8|json|video\/mp4)$/i.test(value)) row.value = value
      else {
        row.valueSha256 = sha(JSON.stringify(value))
        if (type === 'string') {
          row.valueBytes = Buffer.byteLength(value)
          if (/^https?:\/\//i.test(value)) row.url = location(value)
          else if (/^magnet:\?/i.test(value)) row.uriScheme = 'magnet'
          else if (value.startsWith('/')) row.relativeUrl = true
        }
      }
    }
    return row
  }
  if (/^application\/x-www-form-urlencoded(?:;|$)/i.test(contentType)) {
    result.schema = shape({})
    for (const [name, value] of new URLSearchParams(text)) {
      if (nodes >= 64) { result.truncated = true; break }
      result.schema.fields.push({ ...fieldName(name), shape: shape(value, name, 1) })
    }
    return { ...result, status: 'form' }
  }
  try { result.schema = shape(JSON.parse(text)); return { ...result, status: 'json' } }
  catch { return { ...result, status: 'opaque' } }
}

export function networkContract(row, request, response, observation) {
  if (!['xhr', 'fetch'].includes(row.resourceType)) return undefined
  const headers = Object.entries(request.headers)
  const contentType = headers.find(([name]) => name.toLowerCase() === 'content-type')?.[1] || ''
  const body = Buffer.from(response.body, 'base64')
  if (!row.targetMatched && !body.length) return undefined
  return { requestIdSha256: row.requestIdSha256, urlSha256: row.urlSha256, method: row.method,
    targetMatched: row.targetMatched, authority: 'browser_broker_http_observed',
    request: { contentType: contentType.split(';')[0].trim().slice(0, 100),
      headerNames: headers.map(([name]) => name.toLowerCase()).sort().slice(0, 64),
      headersTruncated: headers.length > 64,
      body: bodyContract(Buffer.from(request.body, 'base64'), contentType) },
    response: { status: response.status, contentType: observation.responseContentType,
      body: bodyContract(body, observation.responseContentType, observation.responseComplete),
      signals: responseSignals(body, observation.responseContentType, observation.responseComplete, response.headers) },
    browserDeliveryVerified: false, businessSuccessVerified: false }
}

export function retainNetworkContract(report, contract) {
  if (!contract) return
  const previous = report.networkContracts, next = [...previous, contract]
  const exceeds = () => next.length > 4 || Buffer.byteLength(JSON.stringify(next)) > 32768
  while (exceeds()) {
    report.networkContractsTruncated = true
    if (!contract.targetMatched) return
    const index = next.findIndex(row => row !== contract && !row.targetMatched)
    if (index < 0) return
    next.splice(index, 1)
  }
  report.networkContracts = next
}

export function scriptCandidates(pauses, requests, scripts, pageUrl = '', preloads = [], declarations = [], errors = []) {
  const wanted = new Map()
  const dependencies = new Set(preloads.map(value => value.urlSha256))
  const entries = new Set(declarations.filter(value => value.entrypoint).map(value => value.urlSha256))
  const modules = new Set(declarations.filter(value => value.requireModule).map(value => value.urlSha256))
  const declared = new Set(declarations.map(value => value.urlSha256))
  const add = (frame, priority) => {
    if (frame.scriptId && (!wanted.has(frame.scriptId) || wanted.get(frame.scriptId).priority < priority))
      wanted.set(frame.scriptId, { frame, priority })
  }
  for (const pause of pauses) for (const frame of pause.frames) add(frame, 100)
  for (const error of errors) {
    add(error.location, 98)
    for (const frame of error.frames) add(frame, 98)
  }
  for (const row of requests) {
    const priority = row.targetMatched ? 90 : row.resourceType === 'document' ? (row.status >= 400 ? 80 : 70)
      : ['xhr', 'fetch'].includes(row.resourceType) ? 60 : row.resourceType === 'script' ? 30 : 10
    for (const frame of row.initiator || []) add(frame, priority)
  }
  for (const [scriptId, script] of scripts) {
    if (!script.url?.startsWith('http')) continue
    const urlSha256 = sha(script.url)
    const priority = requests.some(row => row.targetMatched && row.urlSha256 === urlSha256) ? 95
      : entries.has(urlSha256) ? 90 : modules.has(urlSha256) ? 88 : script.url === pageUrl ? 85
      : declared.has(urlSha256) ? 80 : dependencies.has(urlSha256) ? 75 : 0
    add({ scriptId, line: script.startLine || 0, column: script.startColumn || 0 }, priority)
  }
  return [...wanted].sort((a, b) => b[1].priority - a[1].priority)
    .map(([id, value]) => [id, value.frame])
}
export function excerpt(code, line = 0, column = 0, limit = 6000) {
  const lines = code.split('\n')
  const offset = lines.slice(0, line).reduce((sum, item) => sum + item.length + 1, 0) + column
  const start = Math.max(0, offset - 500)
  return { offset: start, text: Buffer.from(code.slice(start)).subarray(0, limit).toString('utf8'),
    complete: Buffer.byteLength(code) <= limit && start === 0 }
}
export function scriptExcerpt(code, frame, script, limit = 6000) {
  const line = Math.max(0, (frame.line || 0) - (script.startLine || 0))
  const column = Math.max(0, (frame.column || 0) - (line === 0 ? script.startColumn || 0 : 0))
  return excerpt(code, line, column, limit)
}
export function limitScriptExcerpts(report, maxBytes = 245760) {
  const bytes = () => Buffer.byteLength(JSON.stringify(report))
  const excerpts = report.scripts.flatMap(script => [script.excerpt, script.recovery?.excerpt]).filter(Boolean)
  while (bytes() > maxBytes) {
    const candidates = excerpts.filter(item => item.text.length).sort((a, b) =>
      Number(a.complete) - Number(b.complete) || Buffer.byteLength(b.text) - Buffer.byteLength(a.text))
    if (!candidates.length) throw new Error('REVERSE_SCRIPT_COLLECTION_FAILED')
    const item = candidates[0]
    item.text = item.text.slice(0, Math.floor(item.text.length / 2))
    item.complete = false
    report.scriptExcerptsTruncated = true
  }
  return report
}
export async function recoverScript(code, sandbox) {
  const result = await webcrack(code, { sandbox, unpack: true, jsx: false, deobfuscate: true })
  if (typeof result.code !== 'string' || !result.code.trim()) throw new Error('REVERSE_RECOVERY_EMPTY')
  return { engine: 'webcrack', version: '2.15.1', originalSha256: sha(code),
    outputSha256: sha(result.code), outputBytes: Buffer.byteLength(result.code),
    excerpt: excerpt(result.code), moduleCount: result.bundle?.modules?.size || 0,
    equivalenceVerified: false, appliedToSource: false, sandbox: 'task_chromium_offline' }
}
