import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/RepairPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('repair.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node) && !(ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => ['props', 'emit'].includes(item.name.getText()))))
    .map(node => node.getText(source)).join('\n')
  const props = reactive({ sourceId: 'source-A', artifactSha256: 'sha-A', baselineRunId: 'run-A',
    baselineReady: true, runtimeEnabled: true })
  const calls = [], events = [], timers = [], errors = []
  const context = { props, ref, computed, watch, Error, onUnmounted() {}, evidenceFields: () => [],
    setTimeout: fn => { timers.push(fn); return timers.length }, clearTimeout() {},
    ElMessage: { error: value => errors.push(value) }, emit: (name, value) => events.push({ name, value }),
    request: (path, body) => path.endsWith('/imported-candidates') ? Promise.resolve([])
      : new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const code = ts.transpileModule(body, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  const value = vm.runInNewContext(code + '\n;({create, refresh, loadSelection, evidenceChanged, selectedId, selected, candidates, busy, evidenceBusy, canProposeSelector,submittedForValidation,pendingValidation,canRetest,canRevalidate,revalidateRuntime})', context)
  return { ...value, props, calls, events, timers, errors }
}
const repair = (id, run = 'run-A') => ({ id, baselineRunId: run, baselineArtifactSha256: 'sha-A',
  status: 'AWAITING_PROPOSAL', freshness: 'current', staleInputs: [], faultSha256: 'fault-sha', frozen: { policy: { scope: 'managed_loader_candidate' }, baseline: { context: {
    auxiliaryEvidence: { extractionReplay: { passed: false } } } } } })

test('repair creation is available before any session exists and retains the current replay identity', async () => {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  const pending = view.create()
  assert.equal(view.calls[1].path, '/sources/source-A/repairs')
  assert.equal(view.calls[1].body.baselineRunId, 'run-A')
  const created = repair('new-repair')
  view.calls[1].resolve(created)
  await settle()
  view.calls[2].resolve([created])
  await settle()
  view.calls[3].resolve(created)
  view.calls[4].resolve([])
  await pending
  assert.equal(view.selected.value.id, 'new-repair')
  assert.equal(view.canProposeSelector.value, true)
  assert.equal(view.props.baselineRunId, 'run-A')
})

test('switching the baseline while create is in flight cannot select the late repair', async () => {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  const pending = view.create()
  view.props.baselineRunId = 'run-B'
  view.props.baselineRunId = 'run-A'
  view.calls[1].resolve(repair('late'))
  await pending
  assert.equal(view.selectedId.value, '')
  assert.equal(view.selected.value, undefined)
  view.calls[3].resolve([])
  view.calls[2].resolve([repair('stale-list')])
  await settle()
  assert.equal(view.selectedId.value, '')
})

test('an ABA selection rejects the older detail and candidate response', async () => {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  view.selectedId.value = 'repair-A'
  const old = view.loadSelection()
  view.selectedId.value = 'repair-B'
  view.selectedId.value = 'repair-A'
  const fresh = view.loadSelection()
  view.calls[3].resolve(repair('repair-A'))
  view.calls[4].resolve([{ id: 'fresh' }])
  await fresh
  view.calls[1].resolve({ ...repair('repair-A'), status: 'VALIDATING' })
  view.calls[2].resolve([{ id: 'old' }])
  await old
  assert.equal(view.candidates.value[0].id, 'fresh')
  assert.equal(view.timers.length, 0)
})

test('candidate proposal events refresh candidates only for the current baseline', async () => {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  const event = { kind: 'selectorProposal', sourceId: 'source-A', artifactSha256: 'sha-A', runId: 'run-A' }
  view.evidenceChanged({ ...event, runId: 'run-B' })
  assert.equal(view.events.length, 0)
  view.evidenceChanged({ ...event, kind: 'extractionReplay' })
  assert.equal(view.calls.length, 1)
  view.evidenceChanged(event)
  assert.equal(view.calls[1].path, '/sources/source-A/repairs')
  view.calls[1].resolve([])
  await settle()
})

test('creation waits for a usable baseline and an active replay to finish', async () => {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  view.props.baselineReady = false
  await view.create()
  view.props.baselineReady = true
  view.evidenceBusy.value = true
  await view.create()
  assert.equal(view.calls.length, 1)
})

const approved = { sourceId: 'source-A', artifactSha256: 'sha-A', repairId: 'repair-A', candidateId: 'candidate-A' }
const candidate = status => ({ id: 'candidate-A', sha256: 'candidate-sha', status })

async function selectedPanel() {
  const view = panel()
  view.calls[0].resolve([])
  await settle()
  view.selectedId.value = 'repair-A'
  view.selected.value = repair('repair-A')
  return view
}

test('current-environment revalidation keeps candidate and fault identities without model or publication requests', async () => {
  const view = await selectedPanel()
  view.selected.value = { ...repair('repair-A'), status: 'BLOCKED', freshness: 'runtime_changed',
    staleInputs: ['runtime', 'execution_policy', 'inspector'] }
  const item = candidate('FAILED')
  view.candidates.value = [item]
  assert.equal(view.canRetest.value, false)
  assert.equal(view.canRevalidate(item), true)
  const operation = view.revalidateRuntime(item)
  const sent = view.calls.at(-1)
  assert.equal(sent.path, '/repair-candidates/candidate-A/runtime-revalidation')
  assert.equal(sent.body.expectedCandidateSha256, 'candidate-sha')
  assert.equal(sent.body.expectedFaultSha256, 'fault-sha')
  sent.resolve({})
  await settle()
  await settleRefresh(view, 'VALIDATING_RUNTIME', [candidate('RUNTIME_QUEUED')])
  await operation
  assert.ok(!view.calls.some(call => call.path.includes('/model-calls') || call.path.includes('/release-plans')))
})

test('revalidation is unavailable for stopped repairs, non-runtime drift, unavailable runners or older candidates', async () => {
  for (const change of [
    view => { view.selected.value.status = 'STOPPED' },
    view => { view.selected.value.status = 'STOPPED'; view.selected.value.staleInputs = ['execution_policy'] },
    view => { view.selected.value.staleInputs = ['profile'] },
    view => { view.selected.value.staleInputs = ['credentials'] },
    view => { view.props.artifactSha256 = 'different' },
    view => { view.props.runtimeEnabled = false },
    view => { view.candidates.value.push({ ...candidate('FAILED'), id: 'newer' }) },
    view => { view.candidates.value[0].sha256 = 'different' }
  ]) {
    const view = await selectedPanel()
    view.selected.value.status = 'BLOCKED'
    const item = candidate('FAILED')
    view.candidates.value = [{ ...item }]
    change(view)
    await view.revalidateRuntime(item)
    assert.equal(view.calls.filter(call => call.path.endsWith('/runtime-revalidation')).length, 0)
  }
})

test('stopped latest candidates can revalidate only after runtime or inspector changes', async () => {
  const view = await selectedPanel()
  view.selected.value.status = 'STOPPED'
  const item = candidate('REJECTED')
  view.candidates.value = [item]
  for (const input of ['runtime', 'inspector']) {
    view.selected.value.staleInputs = [input, 'execution_policy']
    assert.equal(view.canRevalidate(item), true)
  }
  view.selected.value.staleInputs = ['runtime', 'profile']
  assert.equal(view.canRevalidate(item), false)
})

async function settleRefresh(view, status, items) {
  view.calls.at(-1).resolve([{ ...repair('repair-A'), status }])
  await settle()
  view.calls.at(-2).resolve({ ...repair('repair-A'), status })
  view.calls.at(-1).resolve(items)
  await settle()
}

test('an explicit model approval waits for static success then starts exactly one isolated validation', async () => {
  const view = await selectedPanel()
  const operation = view.submittedForValidation(approved)
  await settleRefresh(view, 'VALIDATING', [candidate('QUEUED')])
  await operation
  assert.equal(view.pendingValidation.value.candidateId, 'candidate-A')
  assert.ok(!view.calls.some(call => call.path.endsWith('/runtime-validation')))
  const polling = view.loadSelection()
  view.calls.at(-2).resolve({ ...repair('repair-A'), status: 'AWAITING_RUNTIME_VALIDATION' })
  view.calls.at(-1).resolve([candidate('STATIC_VALIDATED')])
  await settle()
  const sent = view.calls.at(-1)
  assert.equal(sent.path, '/repair-candidates/candidate-A/runtime-validation')
  assert.equal(sent.body.expectedCandidateSha256, 'candidate-sha')
  assert.equal(view.pendingValidation.value, undefined)
  sent.resolve({})
  await settle()
  await settleRefresh(view, 'VALIDATING_RUNTIME', [candidate('RUNTIME_RUNNING')])
  await polling
  assert.equal(view.calls.filter(call => call.path.endsWith('/runtime-validation')).length, 1)
  assert.ok(!view.calls.some(call => call.path.includes('/model-calls') || call.path.includes('/release-plans')))
})

test('static rejection clears the approved follow-up without executing the candidate', async () => {
  const view = await selectedPanel()
  const operation = view.submittedForValidation(approved)
  await settleRefresh(view, 'AWAITING_PROPOSAL', [candidate('REJECTED')])
  await operation
  assert.equal(view.pendingValidation.value, undefined)
  assert.ok(!view.calls.some(call => call.path.endsWith('/runtime-validation')))
})

test('switching a source invalidates a pending follow-up and ignores late static success', async () => {
  const view = await selectedPanel()
  const operation = view.submittedForValidation(approved)
  view.calls.at(-1).resolve([repair('repair-A')])
  await settle()
  const details = view.calls.slice(-2)
  view.props.sourceId = 'source-B'
  details[0].resolve({ ...repair('repair-A'), status: 'AWAITING_RUNTIME_VALIDATION' })
  details[1].resolve([candidate('STATIC_VALIDATED')])
  await operation
  assert.equal(view.pendingValidation.value, undefined)
  assert.ok(!view.calls.some(call => call.path.endsWith('/runtime-validation')))
})

test('a lost runtime reply is not replayed by later status refreshes', async () => {
  const view = await selectedPanel()
  const operation = view.submittedForValidation(approved)
  view.calls.at(-1).resolve([repair('repair-A')])
  await settle()
  view.calls.at(-2).resolve({ ...repair('repair-A'), status: 'AWAITING_RUNTIME_VALIDATION' })
  view.calls.at(-1).resolve([candidate('STATIC_VALIDATED')])
  await settle()
  view.calls.at(-1).reject(new Error('RUNTIME_REPLY_UNKNOWN'))
  await operation
  assert.equal(view.pendingValidation.value, undefined)
  const polling = view.loadSelection()
  view.calls.at(-2).resolve({ ...repair('repair-A'), status: 'AWAITING_RUNTIME_VALIDATION' })
  view.calls.at(-1).resolve([candidate('STATIC_VALIDATED')])
  await polling
  assert.equal(view.calls.filter(call => call.path.endsWith('/runtime-validation')).length, 1)
  assert.deepEqual(view.errors, ['RUNTIME_REPLY_UNKNOWN'])
})

test('unavailable runtime, stale inputs and cross-source approvals cannot queue execution', async () => {
  for (const change of [
    view => { view.props.runtimeEnabled = false },
    view => { view.selected.value.freshness = 'inputs_changed' },
    view => { view.props.artifactSha256 = 'different' },
    view => { view.selected.value.frozen.policy.scope = 'static_candidate' }
  ]) {
    const view = await selectedPanel()
    change(view)
    const count = view.calls.length
    await view.submittedForValidation(approved)
    assert.equal(view.calls.length, count)
    assert.equal(view.pendingValidation.value, undefined)
  }
})
