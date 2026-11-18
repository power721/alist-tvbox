import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
const call = { id: 'model-call', proposalSha256: 'provider-proposal-sha', status: 'PROPOSED' }
function panel() {
  const { descriptor } = parse(readFileSync(new URL('../src/ModelProposalPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('model.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node) && !(ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => ['props', 'emit'].includes(item.name.getText()))))
    .map(node => node.getText(source)).join('\n')
  const props = reactive({ sourceId: 'source-A', artifactSha256: 'baseline-A', disabled: false, runtimeAvailable: true,
    repair: { id: 'repair-A', baselineArtifactSha256: 'baseline-A', freshness: 'current',
      status: 'AWAITING_PROPOSAL' } })
  const requests = [], events = []
  const context = { props, computed, ref, watch, Error, onMounted() {}, onUnmounted() {},
    setTimeout() {}, clearTimeout() {}, crypto: { randomUUID: () => 'automatic-test-request' },
    emit: (name, value) => events.push({ name, value }),
    request: (path, body) => new Promise((resolve, reject) => requests.push({ path, body, resolve, reject })) }
  const code = ts.transpileModule(body, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  const view = vm.runInNewContext(code + '\n;({submit,startAutomatic,generate,refresh,pendingRequest,enhancedDiagnostics,automatic,consent,connection,calls,busy,error,canAnalyze,canRequest,responseStatus})', context)
  view.consent.value = { artifactSha256: 'baseline-A' }
  view.connection.value = { enabled: true }
  return { ...view, props, requests, events }
}

test('model response status distinguishes waiting, permanent pause, exhaustion and success history', () => {
  const view = panel()
  const detail = { failureCategory: 'rate_limit', httpStatus: 429, retryScheduled: true,
    nextRetryAt: '2026-11-16T01:00:00Z', transportAttempts: [{ stage: 'MODEL_RATE_LIMITED' }] }
  assert.match(view.responseStatus({ status: 'QUEUED', responseDiagnostics: detail }), /服务方限流.*HTTP 429.*已传输 1 次.*等待重试/)
  assert.match(view.responseStatus({ status: 'FAILED', responseDiagnostics: {
    ...detail, retryScheduled: false, failureCategory: 'quota' } }), /余额或额度不足.*已暂停自动重试/)
  assert.match(view.responseStatus({ status: 'FAILED', responseDiagnostics: {
    ...detail, retryScheduled: false, pauseReason: 'MODEL_TRANSPORT_RETRIES_EXHAUSTED' } }), /已达重试上限/)
  assert.doesNotMatch(view.responseStatus({ status: 'CANCELLED', responseDiagnostics: detail }), /等待重试/)
  assert.doesNotMatch(view.responseStatus({ status: 'PROPOSED', responseDiagnostics: { transportAttempts: [{}] } }), /暂停/)
  assert.equal(view.responseStatus({ status: 'FAILED' }), '')
})

test('stopped repeated failures may collect evidence but cannot start another model call', () => {
  const view = panel()
  view.props.repair.status = 'STOPPED'
  view.props.repair.stopCode = 'REPEATED_FAILURE'
  assert.equal(view.canAnalyze.value, true)
  assert.equal(view.canRequest.value, false)
  view.props.repair.stopCode = 'USER_CANCELLED'
  assert.equal(view.canAnalyze.value, false)
  view.props.repair.stopCode = 'ROUND_LIMIT'
  view.props.repair.freshness = 'changed'
  assert.equal(view.canAnalyze.value, false)
})
async function complete(view) {
  view.requests[0].resolve({ id: 'candidate-A', repairId: 'repair-A' })
  await settle()
  view.requests[1].resolve({ enabled: true })
  view.requests[2].resolve({ artifactSha256: 'baseline-A' })
  view.requests[3].resolve([{ ...call, candidateId: 'candidate-A' }])
  view.requests[4].resolve({ status: 'DISABLED', stopCode: null })
}

test('explicit combined submission pins the unchanged proposal and passes the bound candidate to the parent', async () => {
  const view = panel()
  const operation = view.submit(call, true)
  assert.equal(view.requests[0].path, '/model-calls/model-call/submit')
  assert.equal(view.requests[0].body.expectedProposalSha256, 'provider-proposal-sha')
  await complete(view)
  await operation
  assert.deepEqual(JSON.parse(JSON.stringify(view.events)), [{ name: 'submitted-for-validation', value: {
    sourceId: 'source-A', artifactSha256: 'baseline-A', repairId: 'repair-A', candidateId: 'candidate-A'
  } }])
  assert.ok(!view.requests.some(row => (row.path.endsWith('/model-calls') && row.body)
    || row.path.endsWith('/runtime-validation')))
})

test('the static-only option never approves runtime execution', async () => {
  const view = panel()
  const operation = view.submit(call)
  await complete(view)
  await operation
  assert.deepEqual(view.events.map(event => event.name), ['changed'])
})

test('double clicks and a late submission after changing the source cannot enqueue follow-up execution', async () => {
  const view = panel()
  const operation = view.submit(call, true)
  await view.submit(call, true)
  assert.equal(view.requests.length, 1)
  view.props.artifactSha256 = 'baseline-B'
  view.requests[0].resolve({ id: 'candidate-A', repairId: 'repair-A' })
  await operation
  assert.equal(view.requests.length, 1)
  assert.equal(view.events.length, 0)
})

test('combined submission requires explicit source consent and an available runtime', async () => {
  for (const change of [
    view => { view.consent.value = { artifactSha256: null } },
    view => { view.props.runtimeAvailable = false },
    view => { view.props.disabled = true },
    view => { view.props.repair.freshness = 'inputs_changed' }
  ]) {
    const view = panel()
    change(view)
    await view.submit(call, true)
    assert.equal(view.requests.length, 0)
  }
})

test('automatic repair starts one server task and never drives model or runtime steps from the page', async () => {
  const view = panel()
  view.connection.value = { enabled: true, autoSubmitEnabled: true, configurationSha256: 'config-A' }
  view.props.repair.faultSha256 = 'fault-A'
  const operation = view.startAutomatic()
  await view.startAutomatic()
  assert.equal(view.requests.length, 1)
  assert.equal(view.requests[0].path, '/repairs/repair-A/automatic')
  assert.equal(view.requests[0].body.expectedConfigurationSha256, 'config-A')
  assert.equal(view.requests[0].body.enhancedDiagnostics, false)
  view.requests[0].resolve({ status: 'ACTIVE' })
  await settle()
  view.requests[1].resolve(view.connection.value)
  view.requests[2].resolve({ artifactSha256: 'baseline-A' })
  view.requests[3].resolve([])
  view.requests[4].resolve({ status: 'ACTIVE', stopCode: null })
  await operation
  assert.equal(view.automatic.value.status, 'ACTIVE')
  assert.equal(view.requests.filter(item => item.body).length, 1)
  assert.ok(!view.requests.some(item => item.path.includes('runtime-validation') || item.path.endsWith('/submit')))
})

test('enhanced automatic mode is explicit and restored from the persistent server state', async () => {
  const view = panel()
  view.connection.value = { enabled: true, autoSubmitEnabled: true, configurationSha256: 'config-A' }
  view.props.repair.faultSha256 = 'fault-A'
  view.enhancedDiagnostics.value = true
  const work = view.startAutomatic()
  assert.equal(view.requests[0].body.enhancedDiagnostics, true)
  view.requests[0].resolve({ status: 'ACTIVE', enhancedDiagnostics: true })
  await settle()
  view.requests[1].resolve(view.connection.value)
  view.requests[2].resolve({ artifactSha256: 'baseline-A' })
  view.requests[3].resolve([])
  view.requests[4].resolve({ status: 'ACTIVE', enhancedDiagnostics: true })
  await work
  assert.equal(view.enhancedDiagnostics.value, true)
})

test('manual enhanced requests retain their mode and key when a response is lost', async () => {
  const view = panel()
  view.connection.value = { enabled: true, configurationSha256: 'config-A' }
  view.props.repair.faultSha256 = 'fault-A'
  view.enhancedDiagnostics.value = true
  const first = view.generate()
  view.requests[0].reject(new Error('lost response'))
  await first
  view.enhancedDiagnostics.value = false
  const second = view.generate()
  assert.equal(view.requests[1].body.requestKey, view.requests[0].body.requestKey)
  assert.equal(view.requests[1].body.enhancedDiagnostics, true)
  view.requests[1].reject(new Error('still unknown'))
  await second
  assert.equal(view.requests.length, 2)
})
