import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { webcrypto } from 'node:crypto'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))

function script(file, choose) {
  const { descriptor } = parse(readFileSync(new URL(file, import.meta.url), 'utf8'))
  const source = ts.createSourceFile(file, descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(choose).map(node => node.getText(source)).join('\n')
  return ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.None } }).outputText
}

function panel() {
  const props = reactive({ sourceId: 'source-A', artifactSha256: 'baseline' })
  const calls = [], events = [], confirms = [], errors = [], stops = []
  const context = { props, ref, computed, watch: (...args) => { const stop = watch(...args); stops.push(stop); return stop },
    crypto: webcrypto, onUnmounted() {}, ElMessage: { error: value => errors.push(value) },
    ElMessageBox: { confirm: () => new Promise((resolve, reject) => confirms.push({ resolve, reject })) },
    emit: (name, value) => events.push({ name, value }),
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const code = script('../src/ReleasePlanPanel.vue', node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations
      .some(item => ['props', 'emit'].includes(item.name.getText()))))
  const view = vm.runInNewContext(code + '\n;({transitionLocal, refresh, loadCandidates, plans, repairs, repairId, candidates, busy})', context)
  return { ...view, props, calls, events, confirms, errors, stop: () => stops.forEach(fn => fn()) }
}

const plan = (values = {}) => ({ id: 'plan-A', candidateId: 'candidate-A', status: 'AWAITING_RUNTIME_VALIDATION',
  frozenSha256: 'frozen', baselineArtifactSha256: 'baseline', candidateSha256: 'candidate',
  localApplyEligible: true, localRollbackEligible: false, sourceRevision: 8, ...values })

async function ready(view, item = plan()) {
  view.calls[0].resolve([])
  view.calls[1].resolve([item])
  await settle()
}

test('local apply keeps the frozen request and reports a successful switch even when refresh fails', async () => {
  const view = panel()
  try {
    await ready(view)
    const operation = view.transitionLocal(view.plans.value[0], false)
    assert.equal(view.calls.length, 2)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[2].path, '/release-plans/plan-A/local-apply')
    assert.deepEqual(JSON.parse(JSON.stringify(view.calls[2].body)), {
      expectedFrozenSha256: 'frozen', expectedArtifactSha256: 'baseline', expectedSourceRevision: 8
    })
    view.calls[2].resolve(plan({ status: 'LOCAL_APPLIED', localApplyEligible: false,
      localRollbackEligible: true, sourceRevision: 9 }))
    await settle()
    assert.equal(view.events[0].name, 'source-changed')
    assert.equal(view.events[0].value, 'source-A')
    view.calls[3].reject(new Error('refresh unavailable'))
    view.calls[4].resolve([])
    await operation
    assert.equal(view.plans.value[0].status, 'LOCAL_APPLIED')
    assert.equal(view.plans.value[0].localApplyEligible, false)
    assert.equal(view.errors.length, 1)
  } finally { view.stop() }
})

test('cancelled confirmations and ineligible plans do not send local writes', async () => {
  const view = panel()
  try {
    await ready(view)
    await view.transitionLocal(plan({ localApplyEligible: false }), false)
    assert.equal(view.confirms.length, 0)
    const operation = view.transitionLocal(plan(), false)
    view.confirms[0].reject('cancel')
    await operation
    assert.equal(view.calls.length, 2)
  } finally { view.stop() }
})

test('a source ABA change during confirmation discards the old local operation', async () => {
  const view = panel()
  try {
    await ready(view)
    const operation = view.transitionLocal(plan(), false)
    view.props.sourceId = 'source-B'
    view.props.sourceId = 'source-A'
    view.confirms[0].resolve()
    await operation
    assert.equal(view.calls.filter(item => item.body).length, 0)
    assert.equal(view.events.length, 0)
    for (const call of view.calls.slice(2)) call.resolve([])
    await settle()
  } finally { view.stop() }
})

test('late local results never update or reselect another source', async () => {
  const view = panel()
  try {
    await ready(view)
    const operation = view.transitionLocal(plan(), false)
    view.confirms[0].resolve()
    await settle()
    view.props.artifactSha256 = 'changed'
    view.calls[2].resolve(plan({ status: 'LOCAL_APPLIED' }))
    await operation
    assert.equal(view.events.length, 0)
    assert.equal(view.plans.value.length, 0)
    view.calls[3].resolve([])
    view.calls[4].resolve([])
    await settle()
  } finally { view.stop() }
})

test('rollback uses the candidate hash and the latest source revision', async () => {
  const view = panel()
  try {
    const applied = plan({ status: 'LOCAL_APPLIED', localApplyEligible: false,
      localRollbackEligible: true, sourceRevision: 9 })
    await ready(view, applied)
    const operation = view.transitionLocal(applied, true)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[2].path, '/release-plans/plan-A/local-rollback')
    assert.equal(view.calls[2].body.expectedArtifactSha256, 'candidate')
    assert.equal(view.calls[2].body.expectedSourceRevision, 9)
    view.calls[2].resolve(plan({ status: 'LOCAL_ROLLED_BACK', localApplyEligible: false, sourceRevision: 10 }))
    await settle()
    view.calls[3].resolve([])
    view.calls[4].resolve([])
    await operation
    assert.equal(view.events.length, 1)
  } finally { view.stop() }
})

test('an older candidate response cannot overwrite a newer ABA selection', async () => {
  const view = panel()
  try {
    await ready(view)
    view.repairId.value = 'repair-A'
    const older = view.loadCandidates()
    view.repairId.value = 'repair-B'
    view.repairId.value = 'repair-A'
    const newer = view.loadCandidates()
    view.calls[3].resolve([{ id: 'new', status: 'STATIC_VALIDATED' }])
    await newer
    view.calls[2].resolve([{ id: 'old', status: 'STATIC_VALIDATED' }])
    await older
    assert.equal(view.candidates.value[0].id, 'new')
  } finally { view.stop() }
})

test('local source refresh does not steal focus after the user selects another source', async () => {
  const pending = [], selected = []
  const context = { selectionRevision: 1, disposed: false, selectedId: { value: 'source-A' },
    sources: { value: [] }, action: async fn => fn(), selectSource: async id => selected.push(id),
    request: () => new Promise(resolve => pending.push(resolve)) }
  const code = script('../src/App.vue', node => ts.isFunctionDeclaration(node) && node.name?.text === 'localSourceChanged')
  const changed = vm.runInNewContext(code + '\n;localSourceChanged', context)
  const operation = changed('source-A')
  context.selectionRevision++
  context.selectedId.value = 'source-B'
  pending[0]([{ id: 'source-A', artifactSha256: 'candidate' }])
  await operation
  assert.equal(selected.length, 0)
  assert.equal(context.sources.value.length, 0)
  const current = changed('source-B')
  pending[1]([{ id: 'source-B', artifactSha256: 'current' }])
  await current
  assert.deepEqual(selected, ['source-B'])
})
