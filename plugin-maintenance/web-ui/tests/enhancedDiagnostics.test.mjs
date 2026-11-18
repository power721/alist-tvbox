import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { reactive, ref, watch } from 'vue'

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../src/EnhancedDiagnosticsPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('diagnostics.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node) && !(ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => item.name.getText() === 'props')))
    .map(node => node.getText(source)).join('\n')
  const props = reactive({ runId: 'run-A', artifactSha256: 'sha-A', repairId: undefined, disabled: false })
  const requests = []
  let unmount
  const context = { props, ref, watch, Error, onUnmounted: fn => { unmount = fn },
    request: path => new Promise((resolve, reject) => requests.push({ path, resolve, reject })) }
  const code = ts.transpileModule(body, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  const view = vm.runInNewContext(code + '\n;({diagnose,report,busy,error,findingLabel,contractLabel,accessPageLabel,httpLabel,transportLabel,cooldownLabel,dnsLabel,networkDenialLabel,phaseLabel})', context)
  return { ...view, props, requests, unmount: () => unmount() }
}

test('enhanced diagnostics is read-only and only requests stored evidence after a click', async () => {
  const view = panel()
  assert.equal(view.requests.length, 0)
  const work = view.diagnose()
  await view.diagnose()
  assert.equal(view.requests.length, 1)
  assert.equal(view.requests[0].path, '/runs/run-A/diagnostics')
  view.requests[0].resolve({ findings: [], routes: [] })
  await work
  assert.ok(view.report.value)
  assert.equal(view.busy.value, false)
})

test('contract details distinguish cover from identity and preserve pagination and magnet semantics', () => {
  const view = panel()
  assert.equal(view.contractLabel({ contractObservations: { identityEvidence: { exactMatch: false, coverMatch: true } } }),
    '详情 ID不匹配；封面匹配')
  assert.equal(view.contractLabel({ contractObservations: { firstItems: 0, secondItems: 20, pagesObserved: 2 } }),
    '首页 0 条；第二页 20 条；已观察 2 页')
  assert.equal(view.contractLabel({ contractObservations: { playerRouteKind: 'magnet', offlineResolverRequired: true } }),
    '磁力交接；需要 AT 离线解析')
  assert.equal(view.contractLabel({}), '')
  assert.equal(view.requests.length, 0)
})

test('host header rejection identifies the unsupported field without exposing its value', () => {
  const view = panel()
  assert.equal(view.findingLabel({ reason: 'EGRESS_HEADERS_DENIED',
    requestDiagnostic: { reason: 'header_not_allowed', headerName: 'x-requested-with' } }), '请求头未适配：x-requested-with')
})

test('player output diagnostics name the observed shape without turning hints into success', () => {
  const view = panel()
  const finding = { contractObservations: { playerContract: { playCallId: 7, detailCallId: 5,
    returnedUrlShape: 'base64_encoded_url', returnedUrlMatchesIdentifier: true, parseMode: 'parse_required',
    hasWhitespace: true, hasFragment: true, url: 'PRIVATE' } } }
  const label = view.contractLabel(finding)
  assert.equal(label, '取链调用 #7；返回值：Base64 编码地址；原样返回选集标识；要求外部解析；含空白或控制字符；含地址片段')
  assert.ok(!label.includes('PRIVATE'))
  assert.equal(view.requests.length, 0)
})

test('Cloudflare explicit denial is not presented as a waiting challenge', () => {
  const view = panel()
  assert.match(view.findingLabel({ reason: 'CONTENT_LIST_EMPTY_OR_INVALID', accessClassification: 'cloudflare_access_denied' }),
    /Cloudflare 明确拒绝页.*不是等待验证/)
  assert.match(view.accessPageLabel({ classification: 'cloudflare_access_denied', targetNode: 'C03',
    responseStatus: 403, observedNode: 'C03', observedDuringCall: { id: 3, method: 'category' } }),
    /明确拒绝页.*HTTP 403.*分类调用 #3/)
  assert.equal(view.requests.length, 0)
})

test('transport details preserve request and address attempts without inventing action attribution', () => {
  const view = panel()
  assert.equal(view.transportLabel({ requestNumber: 2, addressIndex: 1, stage: 'tls_handshake',
    kind: 'timeout', elapsedMilliseconds: 10111 }), '请求 #2 · 地址尝试 2 · TLS 握手 · 超时 · 10111 毫秒')
  assert.equal(view.transportLabel({ requestNumber: 1, addressIndex: 0, stage: 'tcp_connect',
    kind: 'connection_refused', elapsedMilliseconds: 8 }), '请求 #1 · 地址尝试 1 · TCP 连接 · 连接被拒绝 · 8 毫秒')
  assert.equal(view.requests.length, 0)
})

test('Retry-After preserves decimal precision and distinguishes missing timing from zero', () => {
  const view = panel()
  const seconds = '9'.repeat(128)
  assert.equal(view.cooldownLabel({ retryAfter: { kind: 'delay', seconds } }),
    `HTTP 429 · 秒数型 Retry-After · 响应当时 ${seconds} 秒`)
  assert.equal(view.cooldownLabel({ retryAfter: { kind: 'date', seconds: '0' } }),
    'HTTP 429 · 日期型 Retry-After · 响应当时 0 秒')
  assert.match(view.cooldownLabel({ retryAfter: { kind: 'absent', seconds: null } }), /未提供.*未知/)
  assert.match(view.cooldownLabel({ retryAfter: { kind: 'invalid', seconds: null } }), /无效.*未知/)
  assert.equal(view.requests.length, 0)
})

test('runtime snapshots show matched call intervals, DNS stages and budget without starting waits', () => {
  const view = panel()
  const observedDuringCall = { id: 3, method: 'category', inputSha256: 'a'.repeat(64) }
  assert.match(view.transportLabel({ requestNumber: 2, addressIndex: 0, stage: 'tls_handshake', kind: 'timeout',
    elapsedMilliseconds: 8, observedDuringCall }), /分类 #3 调用期间/)
  assert.match(view.dnsLabel({ kind: 'success', dnsStatus: 0, answerCount: 1, publicIpv4Count: 0,
    recordType: 'AAAA', publicIpv6Count: 1, resolver: 'cloudflare', fallbackFrom: 'alidns',
    elapsedMilliseconds: 37, observedDuringCall }), /AAAA.*备用解析.*本阶段 37 毫秒.*分类 #3/)
  const cached = view.dnsLabel({ kind: 'no_address', dnsStatus: 3, answerCount: 0, publicIpv4Count: 0, cacheHit: true })
  assert.match(cached, /复用失败缓存，未重新解析/)
  const rate = { retryAfter: { kind: 'delay', seconds: '120' }, observedDuringCall,
    cooldownContext: { recordedAtEpochMilliseconds: 1795000000000, remainingWaitMilliseconds: '118000',
      taskBudgetRemainingMilliseconds: 5000, fitsTaskBudget: false } }
  const before = JSON.stringify(rate)
  const label = view.cooldownLabel(rate)
  assert.match(label, /采样时间.*当时剩余等待 118000 毫秒.*当时任务剩余 5000 毫秒.*预算不足/)
  assert.equal(view.cooldownLabel(rate), label)
  assert.equal(JSON.stringify(rate), before)
  assert.equal(view.requests.length, 0)
})

test('DNS details separate missing status, empty answers, resolver and transport failures', () => {
  const view = panel()
  assert.equal(view.dnsLabel({ kind: 'no_address', dnsStatus: 3, answerCount: 0, publicIpv4Count: 0 }),
    '未获得地址 · DNS 状态 3 · 地址记录 0 条 · 公网 IPv4 0 个')
  assert.match(view.dnsLabel({ kind: 'transport_error', dnsStatus: null, answerCount: 0, publicIpv4Count: 0 }),
    /解析服务连接失败 · DNS 状态未记录/)
  assert.match(view.dnsLabel({ kind: 'resolver_error', dnsStatus: 2, answerCount: 0, publicIpv4Count: 0,
    recordType: 'AAAA', publicIpv6Count: 0 }), /解析服务错误.*公网 IPv6 0 个/)
  assert.equal(view.requests.length, 0)
})

test('network denials remain method observations and execution phase does not claim a cause', () => {
  const view = panel()
  assert.equal(view.networkDenialLabel({ networkDenials: [{ method: 'category', denialIndex: 0, code: 'EGRESS_HTTP_FAILED' }] }),
    '分类 · 拒绝记录 #1 · EGRESS_HTTP_FAILED（方法级观察，尚未关联到某次连接尝试）')
  assert.equal(view.networkDenialLabel({}), '')
  assert.equal(view.phaseLabel({ executionPhase: 'fetch' }), '执行停止阶段：出站请求')
  assert.equal(view.phaseLabel({}), '')
  assert.equal(view.requests.length, 0)
})

test('action HTTP samples retain call identity and do not invent a challenge or source verdict', () => {
  const view = panel()
  const finding = { reason: 'DETAIL_OUTPUT_INVALID', httpEvidence: { calls: [
    { id: 3, method: 'detail', statusCounts: { 403: 1 } },
    { id: 4, method: 'detail', statusCounts: { 200: 1, 503: 1 } }
  ] } }
  const before = JSON.stringify(finding)
  assert.equal(view.httpLabel(finding), '详情 #3 · HTTP 403 × 1；详情 #4 · HTTP 200 × 1、HTTP 503 × 1')
  assert.equal(view.findingLabel(finding), 'DETAIL_OUTPUT_INVALID')
  assert.equal(view.httpLabel({}), '')
  assert.equal(JSON.stringify(finding), before)
  assert.equal(view.requests.length, 0)
})

test('candidate regression names the observed loss without inventing a failed node or root cause', () => {
  const view = panel()
  const finding = { node: '', reason: 'RUNTIME_REGRESSION',
    regression: { field: 'homeItems', before: 22, after: 0 } }
  const before = JSON.stringify(finding)
  assert.equal(view.findingLabel(finding), '首页条目退化：22 → 0（采样输出，原因待确认）')
  assert.equal(view.findingLabel({ reason: 'RUNTIME_REGRESSION' }), 'RUNTIME_REGRESSION')
  assert.equal(view.findingLabel({ ...finding, reason: 'RUNTIME_NETWORK_UNAVAILABLE' }),
    '首页条目待复验：22 → 0（同动作出现网络失败，先复测现有候选，因果待确认）')
  assert.equal(JSON.stringify(finding), before)
  assert.equal(view.requests.length, 0)
})

test('confirmed challenge finding shows access cause while preserving the original verdict', () => {
  const view = panel()
  const finding = { node: 'C03', status: 'fail', reason: 'CONTENT_LIST_EMPTY_OR_INVALID',
    route: 'access_evidence', accessClassification: 'javascript_cookie_challenge' }
  const before = JSON.stringify(finding)
  assert.equal(view.findingLabel(finding), '观察到访问挑战页，故障因果未确认（原始结果：CONTENT_LIST_EMPTY_OR_INVALID）')
  assert.equal(view.findingLabel({ ...finding, accessClassification: 'cloudflare_challenge' }), view.findingLabel(finding))
  assert.equal(JSON.stringify(finding), before)
  assert.equal(view.findingLabel({ ...finding, accessClassification: undefined }), finding.reason)
  assert.equal(view.requests.length, 0)
})

test('challenge observation displays action and target separately without claiming failure causality', () => {
  const view = panel()
  const page = { classification: 'cloudflare_challenge', targetNode: 'C07', observedNode: 'C03',
    observedDuringCall: { id: 3, method: 'category' }, responseStatus: 403 }
  const before = JSON.stringify(page)
  assert.equal(view.accessPageLabel(page), '已观察到 Cloudflare 挑战页（HTTP 403）；分类调用 #3 期间（C03）；诊断目标 C07。尚不能确认是故障原因。')
  assert.match(view.accessPageLabel({ classification: 'javascript_cookie_challenge', targetNode: 'C03' }), /动作未记录/)
  assert.equal(JSON.stringify(page), before)
  assert.equal(view.requests.length, 0)
})

test('repair diagnostics uses the selected repair and ignores a late response after changing inputs', async () => {
  const view = panel()
  view.props.repairId = 'repair-A'
  const old = view.diagnose()
  assert.equal(view.requests[0].path, '/repairs/repair-A/diagnostics')
  view.props.artifactSha256 = 'sha-B'
  const current = view.diagnose()
  view.requests[1].resolve({ artifactSha256: 'sha-B' })
  await current
  view.requests[0].resolve({ artifactSha256: 'sha-A' })
  await old
  assert.equal(view.report.value.artifactSha256, 'sha-B')
})

test('disabled or absent runs never request diagnostics and errors stay with their input identity', async () => {
  const view = panel()
  view.props.disabled = true
  await view.diagnose()
  view.props.disabled = false
  view.props.runId = ''
  await view.diagnose()
  assert.equal(view.requests.length, 0)
  view.props.runId = 'run-B'
  const work = view.diagnose()
  view.unmount()
  view.requests[0].reject(new Error('old failure'))
  await work
  assert.equal(view.error.value, '')
})
