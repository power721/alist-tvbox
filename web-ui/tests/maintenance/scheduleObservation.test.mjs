import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { nextTick, reactive, ref, watch } from 'vue'

function script(path, choose = node => !ts.isImportDeclaration(node)) {
  const { descriptor } = parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const source = ts.createSourceFile(path, descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return ts.transpileModule(source.statements.filter(choose).map(node => node.getText(source)).join('\n'),
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
}

function panel() {
  const props = reactive({ sourceId: 'A', policySha256: 'policy', refreshKey: 0 })
  const pending = []
  let stop, unmount
  const context = {
    ref, defineProps: () => props, defineEmits: () => () => {},
    watch: (...args) => { stop = watch(...args) },
    onUnmounted: fn => { unmount = fn },
    request: path => new Promise((resolve, reject) => pending.push({ path, resolve, reject }))
  }
  const view = vm.runInNewContext(script('../../src/maintenance/ScheduleObservationPanel.vue')
    + '\n;({load, observation, loading, error})', context)
  return { ...view, props, pending, dispose() { unmount(); stop() } }
}

test('observation requests remain read-only and stale ABA responses cannot replace the latest history', async () => {
  const view = panel()
  try {
    assert.equal(view.pending[0].path, '/sources/A/schedule/observations')
    view.props.sourceId = 'B'
    await nextTick()
    view.props.sourceId = 'A'
    await nextTick()
    view.pending[0].resolve({ sourceId: 'A', currentPolicySha256: 'policy', marker: 'old' })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(view.observation.value, undefined)
    assert.equal(view.loading.value, true)
    view.pending[2].resolve({ sourceId: 'A', currentPolicySha256: 'policy', marker: 'current' })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(view.observation.value.marker, 'current')
    view.pending[1].reject(new Error('late B error'))
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(view.error.value, '')
    assert.equal(view.loading.value, false)
  } finally { view.dispose() }
})

test('source or policy mismatches never render history as the current policy', async () => {
  for (const result of [
    { sourceId: 'B', currentPolicySha256: 'policy' },
    { sourceId: 'A', currentPolicySha256: 'other' }
  ]) {
    const view = panel()
    try {
      view.pending[0].resolve(result)
      await new Promise(resolve => setImmediate(resolve))
      assert.equal(view.observation.value, undefined)
      assert.match(view.error.value, /巡检策略已变化/)
    } finally { view.dispose() }
  }
})

test('refresh triggers a fresh history read while unmount rejects an in-flight response', async () => {
  const view = panel()
  view.props.refreshKey++
  await nextTick()
  assert.equal(view.pending.length, 2)
  view.dispose()
  view.pending[1].resolve({ sourceId: 'A', currentPolicySha256: 'policy' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.observation.value, undefined)
})

function app() {
  const pending = [], timers = []
  const context = {
    disposed: false, selectionRevision: 1, inspectionRevision: 0,
    selectedId: { value: 'A' }, activeRunId: { value: 'recent' }, activeTab: { value: 'schedule' },
    advancedOpen: { value: false },
    sourceReloads: 0,
    loadSources: async () => { context.sourceReloads++ },
    scheduleRefreshKey: { value: 0 }, runs: { value: [{ id: 'recent', status: 'RUNNING' }] },
    action: fn => fn(), setTimeout: fn => { timers.push(fn); return timers.length },
    ElMessage: { error: message => { throw new Error(message) } },
    request: path => new Promise(resolve => pending.push({ path, resolve }))
  }
  const code = script('../../src/maintenance/MaintenanceWorkbench.vue', node => ts.isFunctionDeclaration(node)
    && ['inspectScheduledRun', 'schedulePoll'].includes(node.name?.text))
  const functions = vm.runInNewContext(code + '\n;({inspectScheduledRun, schedulePoll})', context)
  return { ...functions, context, pending, timers }
}

test('opening older history loads its actual report and polling retains that selection', async () => {
  const view = app()
  const opening = view.inspectScheduledRun('older')
  assert.equal(view.pending[0].path, '/probe-runs/older')
  view.pending[0].resolve({ id: 'older', sourceId: 'A', status: 'COMPLETED' })
  await opening
  assert.equal(view.context.activeRunId.value, 'older')
  assert.equal(view.context.activeTab.value, 'probe')
  assert.equal(view.context.advancedOpen.value, true)
  view.schedulePoll('A', 1)
  const polling = view.timers[0]()
  view.pending[1].resolve([{ id: 'recent', status: 'COMPLETED' }])
  await polling
  assert.deepEqual(Array.from(view.context.runs.value, r => r.id), ['recent', 'older'])
  assert.equal(view.context.scheduleRefreshKey.value, 1)
  assert.equal(view.context.sourceReloads, 1)
})

test('overlapping report opens, changed dropdown selections and source ABA reject late reports', async () => {
  const view = app()
  const first = view.inspectScheduledRun('old-first')
  const second = view.inspectScheduledRun('old-second')
  view.pending[1].resolve({ id: 'old-second', sourceId: 'A', status: 'COMPLETED' })
  await second
  view.pending[0].resolve({ id: 'old-first', sourceId: 'A', status: 'COMPLETED' })
  await first
  assert.equal(view.context.activeRunId.value, 'old-second')
  for (const change of ['dropdown', 'source ABA']) {
    const opening = view.inspectScheduledRun('late')
    if (change === 'dropdown') view.context.activeRunId.value = 'chosen'
    else view.context.selectionRevision++
    view.pending.at(-1).resolve({ id: 'late', sourceId: 'A', status: 'COMPLETED' })
    await opening
    assert.notEqual(view.context.activeRunId.value, 'late')
  }
})

test('a report belonging to another source cannot enter the selected source list', async () => {
  const view = app()
  const opening = view.inspectScheduledRun('foreign')
  view.pending[0].resolve({ id: 'foreign', sourceId: 'B', status: 'COMPLETED' })
  await assert.rejects(opening, /巡检报告归属不匹配/)
  assert.equal(view.context.runs.value.length, 1)
  assert.equal(view.context.activeRunId.value, 'recent')
})
