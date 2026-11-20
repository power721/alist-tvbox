import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'

const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/MaintenanceWorkbench.vue', import.meta.url), 'utf8'))
const source = ts.createSourceFile('App.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const nodes = source.statements.filter(node =>
  ts.isFunctionDeclaration(node) && ['saveSchedule', 'resumeSchedule'].includes(node.name?.text)
  || ts.isVariableStatement(node) && node.declarationList.declarations.some(item => item.name.getText(source) === 'scheduleApprovalOptions'))
const code = ts.transpileModule(nodes.map(node => node.getText(source)).join('\n'),
  { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText

function view() {
  const pending = [], messages = [], requests = []
  const run = { id: 'run-A', sourceRevision: 3, triggerKind: 'manual', executionMode: 'managed_source',
    status: 'COMPLETED', errorCode: null, freshness: 'current', artifactSha256: 'sha',
    result: { nodes: [{ id: 'S03', status: 'pass' }, { id: 'C01', status: 'fail' }],
      managedSourceEvidence: { cleanup: { confirmed: true, containerAbsent: true } } } }
  const context = { selectedId: { value: 'A' }, selected: { value: { revision: 3, artifactSha256: 'sha' } },
    disposed: false, selectionRevision: 1, runs: { value: [run] },
    schedule: { value: { sha256: 'policy', policy: {} } }, scheduleEnabled: { value: true },
    scheduleRefreshKey: { value: 0 },
    scheduleMode: { value: 'managed_source' }, scheduleHours: { value: 6 }, scheduleDailyLimit: { value: 2 },
    scheduleApprovalConfirmed: { value: false }, scheduleApprovalRunId: { value: 'run-A' },
    scheduleAutomaticRepair: { value: false },
    scheduleMaxRounds: { value: 3 },
    scheduleRepairConfigurationSha: { value: '' }, scheduleFirstRunAt: { value: null },
    computed: fn => ({ get value() { return fn() } }), action: async fn => fn(),
    ElMessage: { success: message => messages.push(message) },
    request: async (path, body) => {
      requests.push({ path, body })
      return new Promise(resolve => pending.push(resolve))
    } }
  const functions = vm.runInNewContext(code + '\n;({saveSchedule, resumeSchedule, scheduleApprovalOptions})', context)
  return { ...functions, context, pending, messages, requests, run }
}

test('managed schedules require explicit consent and send the manual receipt revision', async () => {
  const app = view()
  await assert.rejects(app.saveSchedule(), /明确批准/)
  assert.equal(app.requests.length, 0)
  app.context.scheduleApprovalConfirmed.value = true
  const saving = app.saveSchedule()
  assert.deepEqual(JSON.parse(JSON.stringify(app.requests[0].body.approval)),
    { approvedRunId: 'run-A', expectedSourceRevision: 3 })
  app.pending[0]({ sha256: 'saved', policy: { approval: { approvedRunId: 'run-A' } } })
  await saving
  assert.equal(app.context.schedule.value.sha256, 'saved')
  assert.equal(app.context.scheduleApprovalConfirmed.value, false)
})

test('receipt choices exclude stale, automatic, unclean and legacy runs but allow business failures', () => {
  const app = view()
  assert.equal(app.scheduleApprovalOptions.value.length, 1)
  for (const [key, value] of [['sourceRevision', null], ['sourceRevision', 4], ['triggerKind', 'scheduled'],
    ['freshness', 'runtime_changed'], ['status', 'BLOCKED'], ['errorCode', 'MANAGED_CLEANUP_UNCONFIRMED']]) {
    const before = app.run[key]
    app.run[key] = value
    assert.equal(app.scheduleApprovalOptions.value.length, 0, key)
    app.run[key] = before
  }
  app.run.result.managedSourceEvidence.cleanup.confirmed = false
  assert.equal(app.scheduleApprovalOptions.value.length, 0)
})

test('saving or resuming cannot steal selection after a source change or same-source ABA', async () => {
  for (const operation of ['saveSchedule', 'resumeSchedule']) for (const switchSource of [false, true]) {
    const app = view()
    app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
    const saving = app[operation]()
    app.context.selectionRevision++
    if (switchSource) app.context.selectedId.value = 'B'
    const current = { sha256: 'current', policy: {} }
    app.context.schedule.value = current
    app.pending[0]({ sha256: 'late', policy: {} })
    await saving
    assert.equal(app.context.schedule.value, current)
    assert.equal(app.messages.length, 0)
    assert.equal(app.requests.length, 1)
  }
})

test('an existing approval can be disabled without silently replacing or reapproving it', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleEnabled.value = false
  const saving = app.saveSchedule()
  assert.equal(app.requests[0].body.approval, null)
  assert.equal(app.requests[0].body.enabled, false)
  app.pending[0]({ sha256: 'disabled', policy: { approval: { approvedRunId: 'run-A' } } })
  await saving
  assert.equal(app.context.schedule.value.sha256, 'disabled')
})

test('repair opt-in binds the current model only after checking the existing source consent', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleAutomaticRepair.value = true
  const saving = app.saveSchedule()
  assert.equal(app.requests[0].path, '/model/connection')
  app.pending[0]({ enabled: true, autoSubmitEnabled: true, configurationSha256: 'model-config' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(app.requests[1].path, '/sources/A/model-consent')
  app.pending[1]({ artifactSha256: 'sha' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(app.requests[2].path, '/sources/A/schedule')
  assert.deepEqual(JSON.parse(JSON.stringify(app.requests[2].body.automaticRepair)),
    { expectedConfigurationSha256: 'model-config', maxRounds: 3 })
  app.pending[2]({ sha256: 'saved', policy: { automaticRepair: { configurationSha256: 'model-config' } } })
  await saving
  assert.equal(app.context.scheduleRepairConfigurationSha.value, 'model-config')
})

test('editing and disabling preserve the frozen model rather than silently authorizing a new one', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleAutomaticRepair.value = true
  app.context.scheduleRepairConfigurationSha.value = 'frozen-model'
  app.context.scheduleEnabled.value = false
  app.context.scheduleMaxRounds.value = 8
  const saving = app.saveSchedule()
  assert.equal(app.requests.length, 1)
  assert.equal(app.requests[0].path, '/sources/A/schedule')
  assert.equal(app.requests[0].body.automaticRepair.expectedConfigurationSha256, 'frozen-model')
  assert.equal(app.requests[0].body.automaticRepair.maxRounds, 8)
  app.pending[0]({ sha256: 'saved', policy: {} })
  await saving
})

test('invalid scheduled repair rounds do not submit a schedule or contact the model', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleAutomaticRepair.value = true
  for (const limit of [0, -1, 1.5, undefined]) {
    app.context.scheduleMaxRounds.value = limit
    await assert.rejects(app.saveSchedule(), /正整数/)
  }
  assert.equal(app.requests.length, 0)
})

test('source change during model approval aborts before consent lookup or policy update', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleAutomaticRepair.value = true
  const saving = app.saveSchedule()
  app.context.selectionRevision++
  app.pending[0]({ enabled: true, autoSubmitEnabled: true, configurationSha256: 'model-config' })
  await saving
  assert.equal(app.requests.length, 1)
})

test('smart inspection saves the chosen local instant as UTC with a live interval and optional repair', async () => {
  const app = view()
  app.context.schedule.value.policy.approval = { approvedRunId: 'run-A' }
  app.context.scheduleFirstRunAt.value = new Date('2030-01-02T08:30:00+08:00')
  app.context.scheduleHours.value = 24
  const saving = app.saveSchedule()
  assert.equal(app.requests[0].body.firstRunAt, '2030-01-02T00:30:00.000Z')
  assert.equal(app.requests[0].body.intervalSeconds, 86400)
  assert.equal(app.requests[0].body.mode, 'managed_source')
  assert.equal(app.requests[0].body.automaticRepair, null)
  app.pending[0]({ sha256: 'saved', policy: {} })
  await saving
})
