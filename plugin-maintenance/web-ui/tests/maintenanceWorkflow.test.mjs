import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { test } from 'node:test'
import { parse } from '@vue/compiler-sfc'
import { computed, ref } from 'vue'
import ts from 'typescript'

const content = parse(readFileSync(new URL('../src/MaintenanceWorkflowPanel.vue', import.meta.url), 'utf8'))
assert.deepEqual(content.errors, [])
const source = ts.createSourceFile('workflow.ts', content.descriptor.scriptSetup.content,
  ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source)).join('\n')
const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022,
  module: ts.ModuleKind.None } }).outputText
const config = { enabled: true, autoSubmitEnabled: true, configurationSha256: 'config', model: 'test' }

test('explicit denial explains platform pause without presenting a model or source repair', () => {
  const { view } = setup(() => {})
  for (const stopCode of ['MODEL_SOURCE_ACCESS_DENIED', 'LIVE_INSPECTION_SOURCE_ACCESS_DENIED']) {
    view.task.value = { runId: 'run', repairId: 'repair', status: 'STOPPED', stopCode }
    assert.match(view.stopLabels[stopCode], /Cloudflare 明确拒绝页/)
    assert.equal(view.canResume.value, false)
  }
})

test('only explicit recoverable transport failures expose continue, not quota auth or unknown outcomes', () => {
  const { view } = setup(() => {})
  for (const stopCode of ['MODEL_HTTP_ERROR', 'MODEL_RATE_LIMITED', 'MODEL_SERVICE_UNAVAILABLE', 'MODEL_CONNECTION_FAILED']) {
    view.task.value = { runId: 'run', repairId: 'repair', status: 'STOPPED', stopCode }
    assert.equal(view.canResume.value, true)
    assert.ok(view.stopLabels[stopCode])
  }
  for (const stopCode of ['MODEL_OUTCOME_UNKNOWN', 'MODEL_RESTARTED_OUTCOME_UNKNOWN',
    'MODEL_QUOTA_EXHAUSTED', 'MODEL_AUTHENTICATION_REJECTED', 'MODEL_REQUEST_REJECTED']) {
    view.task.value = { runId: 'run', repairId: 'repair', status: 'STOPPED', stopCode }
    assert.equal(view.canResume.value, false)
  }
  assert.match(view.stopLabels.MODEL_HTTP_ERROR, /新模型请求/)
})
function setup(handler, permission = 'sha') {
  const calls = [], events = []
  const props = { sourceId: 'source', artifactSha256: 'sha', disabled: false, runtimeEnabled: true }
  const view = vm.runInNewContext(code + '\n;({start, refresh, submit, cancel, resume, canResume, task, taskLabel, stopLabels, active, pending, pendingResume, connection, consent, reviewing, props, evidenceOffer, reviewContinuation, continueWithEvidence, confirmingContinuation, pendingContinuation})', {
    ref, computed, defineProps: () => props, defineEmits: () => (...args) => events.push(args),
    onMounted() {}, onUnmounted() {}, setTimeout: () => 1, clearTimeout() {},
    crypto: { randomUUID: () => 'stable_request_key' }, sourceContent: async () => 'source bytes',
    request: async (path, input) => { calls.push({ path, input }); return handler(path, input) }
  })
  view.connection.value = config
  view.consent.value = { sourceId: 'source', artifactSha256: permission }
  return { view, calls, events }
}

test('unauthorized source opens review without starting a maintenance task or sending source to a model', async () => {
  const { view, calls } = setup(() => {}, null)
  await view.start()
  assert.equal(view.reviewing.value, true)
  assert.equal(calls.length, 0)
})

test('unavailable runtime never downgrades maintenance to static mode', async () => {
  const { view, calls } = setup(() => {})
  view.props.runtimeEnabled = false
  await view.start()
  assert.equal(calls.length, 0)
})

test('preparation failure survives reload and is never labeled as an inspection or a repair', async () => {
  const task = { runId: 'setup-run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: null, stopCode: 'SOURCE_SETUP_DNS_NO_ADDRESS' }
  const { view, calls } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' } : [task])
  await view.refresh()
  assert.equal(view.task.value.runId, 'setup-run')
  assert.equal(view.taskLabel.value, '准备失败，源未执行')
  assert.match(view.stopLabels[task.stopCode], /备用域/)
  assert.equal(view.active.value, false)
  assert.equal(view.canResume.value, true)
  assert.ok(calls.every(call => call.input === undefined))
})

test('DNS recovery resumes the same stopped inspection, never a cancellation or nonpublic target', async () => {
  const task = { runId: 'dns-run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: null, stopCode: 'SOURCE_SETUP_DNS_NO_ADDRESS' }
  const { view, calls } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' } : [task])
  view.task.value = task
  await view.resume()
  const writes = calls.filter(call => call.input !== undefined)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].path, '/probe-runs/dns-run/maintenance/resume')
  view.task.value.status = 'CANCELLED'
  assert.equal(view.canResume.value, false)
  view.task.value.status = 'STOPPED'
  view.task.value.stopCode = 'SOURCE_SETUP_DNS_NONPUBLIC'
  assert.equal(view.canResume.value, false)
})

test('lost start response preserves the request identity across retry', async () => {
  const { view, calls } = setup(() => { throw new Error('connection lost') })
  await view.start()
  await view.start()
  assert.equal(calls.length, 2)
  assert.equal(calls[0].path, '/sources/source/maintenance')
  assert.equal(calls[0].input.requestKey, calls[1].input.requestKey)
  assert.equal(view.pending.value.requestKey, 'stable_request_key')
})

test('confirmed access challenges explain both historical and early stops without resending repair', async () => {
  const { view, calls } = setup(() => {})
  for (const stopCode of ['LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE', 'MODEL_SOURCE_ACCESS_CHALLENGE']) {
    view.task.value = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
      repairId: stopCode.startsWith('MODEL_') ? 'repair' : null, stopCode }
    assert.match(view.stopLabels[stopCode], /访问挑战页/)
    assert.equal(view.canResume.value, false)
    await view.resume()
  }
  assert.equal(calls.length, 0)
})

test('reload observes a persistent workflow without creating another task', async () => {
  const task = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'REPAIRING', repairId: 'repair' }
  const { view, calls, events } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' } : [task])
  await view.refresh()
  assert.equal(view.active.value, true)
  assert.equal(view.task.value.repairId, 'repair')
  assert.ok(calls.every(call => call.input === undefined))
  assert.equal(events[0][0], 'changed')
})

test('budget resume targets the existing workflow instead of starting another inspection', async () => {
  const task = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: 'repair', stopCode: 'MODEL_NAME_DAILY_BUDGET' }
  const { view, calls } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' } : [task])
  view.task.value = task
  await view.resume()
  const writes = calls.filter(call => call.input !== undefined)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].path, '/probe-runs/run/maintenance/resume')
  assert.equal(writes[0].input.expectedArtifactSha256, 'sha')
})

test('network pause retries the existing workflow and unrelated failure cannot resume', async () => {
  const task = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: 'repair', stopCode: 'RUNTIME_NETWORK_UNAVAILABLE' }
  const { view, calls } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' } : [task])
  view.task.value = task
  assert.equal(view.canResume.value, true)
  await view.resume()
  assert.deepEqual(calls.filter(call => call.input !== undefined).map(call => call.path),
    ['/probe-runs/run/maintenance/resume'])
  view.task.value = { ...task, stopCode: 'REPEATED_FAILURE' }
  assert.equal(view.canResume.value, false)
})

test('response recovery retains the resume identity on lost response and never resumes unknown outcomes', async () => {
  const { view, calls } = setup(() => { throw new Error('connection lost') })
  const task = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: 'repair', stopCode: 'MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID' }
  view.task.value = task
  assert.equal(view.canResume.value, true)
  await view.resume()
  await view.resume()
  assert.equal(calls.length, 2)
  assert.equal(calls[0].path, '/probe-runs/run/maintenance/resume')
  assert.equal(calls[0].input, calls[1].input)
  assert.equal(view.pendingResume.value.requestKey, 'stable_request_key')
  for (const stopCode of ['MODEL_OUTCOME_UNKNOWN', 'MODEL_RESPONSE_REFUSED', 'MODEL_RESPONSE_OUTPUT_FILTERED']) {
    view.task.value = { ...task, stopCode }
    assert.equal(view.canResume.value, false)
  }
})

test('new evidence is read-only until an explicit continuation confirmation', async () => {
  const task = { runId: 'run', sourceId: 'source', artifactSha256: 'sha', status: 'STOPPED',
    repairId: 'repair', stopCode: 'REPEATED_FAILURE' }
  const offer = { available: true, candidateSha256: 'candidate', evidenceSha256: 'material',
    expectedFaultSha256: 'fault', usedRounds: 3, maxRounds: 3 }
  const { view, calls } = setup(path => path === '/model/connection' ? config
    : path.endsWith('/model-consent') ? { artifactSha256: 'sha' }
    : path.endsWith('/evidence-continuation') ? offer : [task])
  await view.refresh()
  assert.equal(view.evidenceOffer.value.available, true)
  assert.ok(calls.every(call => call.input === undefined))
  view.reviewContinuation()
  assert.equal(view.confirmingContinuation.value, true)
  assert.ok(calls.every(call => call.input === undefined))
  await view.continueWithEvidence()
  const writes = calls.filter(call => call.input !== undefined)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].path, '/probe-runs/run/maintenance/evidence-continuation')
  assert.equal(writes[0].input.expectedCandidateSha256, 'candidate')
  assert.equal(writes[0].input.expectedEvidenceSha256, 'material')
  assert.equal(writes[0].input.expectedFaultSha256, 'fault')
  assert.equal(view.confirmingContinuation.value, false)
})

test('lost continuation response reuses the grant identity and switching sources prevents a stale write', async () => {
  const { view, calls } = setup(() => { throw new Error('connection lost') })
  view.task.value = { runId: 'run', repairId: 'repair', status: 'STOPPED', stopCode: 'ROUND_LIMIT' }
  view.evidenceOffer.value = { available: true, candidateSha256: 'candidate', evidenceSha256: 'material',
    expectedFaultSha256: 'fault', usedRounds: 3, maxRounds: 3 }
  view.reviewContinuation()
  await view.continueWithEvidence()
  await view.continueWithEvidence()
  assert.equal(calls.length, 2)
  assert.equal(calls[0].input.requestKey, calls[1].input.requestKey)
  assert.equal(view.pendingContinuation.value.requestKey, 'stable_request_key')
  view.props.sourceId = 'other-source'
  await view.continueWithEvidence()
  assert.equal(calls.length, 2)
})
