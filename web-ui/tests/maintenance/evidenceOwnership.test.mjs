import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { nextTick, reactive, ref, watch } from 'vue'

function statements(file, choose) {
  const { descriptor } = parse(readFileSync(new URL(file, import.meta.url), 'utf8'))
  const source = ts.createSourceFile(file, descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  return ts.transpileModule(source.statements.filter(choose).map(node => node.getText(source)).join('\n'),
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
}

function app() {
  const deferred = []
  const timers = []
  const context = { disposed: false, selectionRevision: 0, poll: undefined, definitions: { value: [] },
    setTimeout: fn => { timers.push(fn); return timers.length }, clearTimeout() {},
    workTab: { value: 'repair' },
    advancedOpen: { value: false },
    loadSources: async () => {},
    ElMessage: { error() { throw new Error('unexpected stale error') } },
    request: async path => {
      if (path.endsWith('/probe-runs')) return new Promise(resolve => deferred.push(resolve))
      if (path.endsWith('/profile')) return { snapshot: { requiredNodes: [], optionalNodes: [], runtime: 't4' } }
      if (path.endsWith('/schedule')) return { policy: { intervalSeconds: 3600 } }
      if (path.startsWith('/managed-source-runtime?')) return { routeLabel: path.split('sourceId=')[1] }
      return []
    } }
  for (const key of ['selectedId', 'activeRunId', 'runs', 'artifacts', 'imports', 'pluginInputs', 'profile',
    'profileHistory', 'extInput', 'publicDataInput', 'sourceConfigInput', 'atBinding', 'schedule', 'scheduling', 'scheduleEnabled', 'scheduleMode',
    'scheduleHours', 'scheduleFirstRunAt', 'scheduleDailyLimit', 'scheduleApprovalRunId', 'scheduleApprovalConfirmed',
    'scheduleAutomaticRepair', 'scheduleRepairConfigurationSha', 'inspectionDrawer', 'searchKeyword',
    'runtime', 'runtimeRevision', 'paginationMode', 'nodeModes', 'managedConfiguration']) context[key] = { value: null }
  context.scheduleRefreshKey = { value: 0 }
  const code = statements('../../src/maintenance/MaintenanceWorkbench.vue', node =>
    ts.isFunctionDeclaration(node) && ['selectSource', 'schedulePoll'].includes(node.name?.text))
  const functions = vm.runInNewContext(code + '\n;({selectSource, schedulePoll})', context)
  return { ...functions, context, deferred, timers }
}

function profileEditor(input = '', config = '') {
  const calls = []
  const selections = []
  const context = {
    disposed: false, selectionRevision: 1, sources: { value: [] },
    selectedId: { value: 'source-A' },
    profile: { value: { sha256: 'frozen-profile', snapshot: { sourcePurpose: 'video' } } },
    searchKeyword: { value: '' },
    runtime: { value: 't4' }, runtimeRevision: { value: 'runtime' },
    paginationMode: { value: 'exact-total' },
    nodeModes: { value: { S01: 'required', C01: 'optional' } },
    extInput: { value: '' }, publicDataInput: { value: input }, sourceConfigInput: { value: config },
    action: async work => work(), selectSource: async id => { selections.push(id) },
    ElMessage: { success() {} },
    request: async (path, body) => {
      calls.push({ path, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) })
      return path === '/sources' ? [{ id: 'source-A' }] : {}
    }
  }
  const code = statements('../../src/maintenance/MaintenanceWorkbench.vue', node =>
    ts.isFunctionDeclaration(node) && node.name?.text === 'saveProfile')
  return { save: vm.runInNewContext(code + '\n;saveProfile', context), context, calls, selections }
}

test('profile saves send public JSON explicitly, omit blank input and preserve an explicit clear', async () => {
  for (const input of ['', '{}', '{"label":"public"}']) {
    const view = profileEditor(input)
    await view.save()
    assert.equal(view.calls[0].path, '/sources/source-A/profiles')
    assert.equal(view.calls[0].body.expectedSha256, 'frozen-profile')
    if (!input) assert.equal('publicSourceData' in view.calls[0].body, false)
    else assert.deepEqual(view.calls[0].body.publicSourceData, JSON.parse(input))
    assert.equal(view.context.publicDataInput.value, '')
  }
})

test('invalid public JSON sends no request and retains the draft for correction', async () => {
  const view = profileEditor('{broken')
  await assert.rejects(view.save(), { name: 'SyntaxError' })
  assert.equal(view.calls.length, 0)
  assert.equal(view.context.publicDataInput.value, '{broken')
})

test('profile saves freeze the selected pagination contract', async () => {
  for (const mode of ['exact-total', 'page-window']) {
    const view = profileEditor()
    view.context.paginationMode.value = mode
    await view.save()
    assert.equal(view.calls[0].body.paginationMode, mode)
  }
})

test('runtime config saves preserve numeric and boolean types, omit blanks and explicitly clear', async () => {
  for (const input of ['', '  ', '{}', '{"gateway_timeout":30,"subtitle_enabled":false}']) {
    const view = profileEditor('', input)
    await view.save()
    const body = view.calls[0].body
    if (!input.trim()) assert.equal('sourceConfig' in body, false)
    else assert.deepEqual(body.sourceConfig, JSON.parse(input))
    assert.equal('publicSourceData' in body, false)
    assert.equal(view.context.sourceConfigInput.value, '')
    assert.deepEqual(view.selections, ['source-A'])
  }
  const invalid = profileEditor('', '{broken')
  await assert.rejects(invalid.save(), { name: 'SyntaxError' })
  assert.equal(invalid.calls.length, 0)
  assert.equal(invalid.context.sourceConfigInput.value, '{broken')
})

test('late profile saves and source refreshes preserve the current selection and configuration drafts', async () => {
  for (const phase of ['save', 'refresh']) {
    for (const selection of ['other-source', 'ABA', 'disposed']) {
      const view = profileEditor('{"label":"submitted"}', '{"gateway_timeout":30}')
      let release
      let entered
      const pending = new Promise(resolve => { release = resolve })
      const started = new Promise(resolve => { entered = resolve })
      const original = view.context.request
      view.context.request = async (path, body) => {
        if (path === (phase === 'save' ? '/sources/source-A/profiles' : '/sources')) {
          entered()
          await pending
        }
        return original(path, body)
      }
      const saving = view.save()
      await started
      if (selection === 'disposed') view.context.disposed = true
      else {
        view.context.selectionRevision += selection === 'ABA' ? 2 : 1
        view.context.selectedId.value = selection === 'ABA' ? 'source-A' : 'source-B'
      }
      const profile = { sha256: 'current-profile' }
      const sources = [{ id: 'current-source' }]
      view.context.profile.value = profile
      view.context.sources.value = sources
      view.context.publicDataInput.value = '{"label":"current draft"}'
      view.context.sourceConfigInput.value = '{"gateway_timeout":60}'
      release()
      await saving
      assert.equal(view.context.profile.value, profile, `${phase}/${selection}`)
      assert.equal(view.context.sources.value, sources, `${phase}/${selection}`)
      assert.equal(view.context.publicDataInput.value, '{"label":"current draft"}')
      assert.equal(view.context.sourceConfigInput.value, '{"gateway_timeout":60}')
      assert.deepEqual(view.selections, [])
    }
  }
})

test('frozen auxiliary evidence updates only the currently selected source, artifact and run', () => {
  const run = { id: 'run-A', artifactSha256: 'sha-A', context: { auxiliaryEvidence: { browserEvidence: { preserved: true } } } }
  const polls = []
  const context = { selectedId: { value: 'source-A' }, activeRun: { value: run }, poll: undefined,
    selectionRevision: 1, schedulePoll: (id, revision) => polls.push({ id, revision }) }
  const code = statements('../../src/maintenance/MaintenanceWorkbench.vue', node => ts.isFunctionDeclaration(node) && node.name?.text === 'evidenceChanged')
  const changed = vm.runInNewContext(code + '\n;evidenceChanged', context)
  const event = { kind: 'extractionReplay', sourceId: 'source-A', artifactSha256: 'sha-A',
    runId: 'run-A', evidence: { passed: false } }
  for (const field of ['sourceId', 'artifactSha256', 'runId', 'kind']) {
    changed({ ...event, [field]: field === 'kind' ? 'selectorProposal' : 'other' })
    assert.equal(run.context.auxiliaryEvidence.extractionReplay, undefined, field)
  }
  changed(event)
  assert.equal(run.context.auxiliaryEvidence.extractionReplay, event.evidence)
  assert.equal(run.context.auxiliaryEvidence.browserEvidence.preserved, true)
  assert.equal(context.selectionRevision, 2)
  assert.deepEqual(polls, [{ id: 'source-A', revision: 2 }])
})

test('same-source reload does not accept an older selection response', async () => {
  const view = app()
  const old = view.selectSource('same')
  const current = view.selectSource('same')
  view.deferred[1]([{ id: 'current', status: 'COMPLETED' }])
  await current
  view.deferred[0]([{ id: 'old', status: 'COMPLETED' }])
  await old
  assert.equal(view.context.runs.value[0].id, 'current')
  assert.equal(view.context.activeRunId.value, 'current')
})

test('source changes discard public configuration drafts before any response arrives', async () => {
  const view = app()
  view.context.publicDataInput.value = '{"label":"source A"}'
  view.context.sourceConfigInput.value = '{"gateway_timeout":30}'
  const pending = view.selectSource('source B')
  assert.equal(view.context.publicDataInput.value, '')
  assert.equal(view.context.sourceConfigInput.value, '')
  view.deferred[0]([])
  await pending
  assert.equal(view.context.publicDataInput.value, '')
  assert.equal(view.context.sourceConfigInput.value, '')
})

test('source changes clear the runner and late responses cannot replace the current source channel', async () => {
  const view = app()
  view.context.managedConfiguration.value = { routeLabel: 'previous' }
  const old = view.selectSource('source A')
  assert.equal(view.context.managedConfiguration.value, undefined)
  const current = view.selectSource('source B')
  view.deferred[1]([])
  await current
  assert.equal(view.context.managedConfiguration.value.routeLabel, 'source%20B')
  view.deferred[0]([])
  await old
  assert.equal(view.context.managedConfiguration.value.routeLabel, 'source%20B')
})

test('late polling from an earlier same-source view cannot overwrite or reschedule', async () => {
  const view = app()
  const initial = view.selectSource('same')
  view.deferred[0]([{ id: 'running', status: 'RUNNING' }])
  await initial
  const oldPoll = view.timers[0]()
  const current = view.selectSource('same')
  view.deferred[2]([{ id: 'new', status: 'COMPLETED' }])
  await current
  view.deferred[1]([{ id: 'old', status: 'RUNNING' }])
  await oldPoll
  assert.equal(view.context.runs.value[0].id, 'new')
  assert.equal(view.timers.length, 1)
})

test('node drawer closes on source, profile or runtime identity changes even with the same job', async () => {
  const props = reactive({ run: { id: 'job', artifactSha256: 'source', profileSha256: 'profile', runtimeSha256: 'runtime' } })
  const drawer = ref(true)
  const nodeId = ref('C03')
  const code = statements('../../src/maintenance/ProbeEvidencePanel.vue', node =>
    ts.isExpressionStatement(node) && ts.isCallExpression(node.expression)
      && node.expression.expression.getText() === 'watch')
  const stop = vm.runInNewContext(code, { props, drawer, nodeId, watch })
  try {
    for (const key of Object.keys(props.run)) {
      drawer.value = true
      nodeId.value = 'C03'
      props.run[key] += '-changed'
      await nextTick()
      assert.equal(drawer.value, false, key)
      assert.equal(nodeId.value, '', key)
    }
  } finally { stop() }
})
