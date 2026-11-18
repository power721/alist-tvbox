import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../src/EvidenceToolsPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node) && !(ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => ['props', 'emit'].includes(item.name.getText()))))
    .map(node => node.getText(source)).join('\n')
  const props = reactive({ sourceId: 'source', artifactSha256: 'a'.repeat(64), runId: 'run',
    repairId: 'repair', canPropose: true })
  const deferred = [], writes = [], errors = [], events = []
  let unmount
  const context = { props, ref, computed, watch, evidenceFields: value => value ? [value] : [],
    onUnmounted: fn => { unmount = fn }, emit: (name, value) => events.push({ name, value }),
    ElMessage: { error: message => errors.push(message) },
    request: (path, body) => {
      writes.push({ path, body })
      return new Promise((resolve, reject) => deferred.push({ resolve, reject }))
    } }
  const code = ts.transpileModule(body, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  const value = vm.runInNewContext(code + '\n;({submit, template, mode, input, result, busy, allowResponseAbort})', context)
  return { ...value, props, writes, deferred, errors, events, unmount: () => unmount() }
}

test('replay, selector and browser actions use distinct APIs and require an explicit abort checkbox', async () => {
  const view = panel()
  for (const [mode, suffix] of [['replay', '/sources/source/extraction/replay?runId=run'],
    ['selector', '/repairs/repair/selector-proposals'], ['browser', '/sources/source/browser-evidence']]) {
    view.mode.value = mode
    view.template()
    const pending = view.submit()
    assert.equal(view.writes.at(-1).path, suffix)
    if (mode === 'browser') assert.equal(view.writes.at(-1).body.allowResponseAbort, false)
    view.deferred.at(-1).resolve({ frozen: mode })
    await pending
    assert.equal(view.result.value.frozen, mode)
  }
  view.allowResponseAbort.value = true
  const confirmed = view.submit()
  assert.equal(view.writes.at(-1).body.allowResponseAbort, true)
  view.deferred.at(-1).resolve({})
  await confirmed
})

test('source, artifact and run changes clear drafts and invalidate even an ABA late response', async () => {
  for (const key of ['sourceId', 'artifactSha256', 'runId']) {
    const view = panel()
    view.input.value = '{}'
    view.allowResponseAbort.value = true
    const pending = view.submit()
    const initial = view.props[key]
    view.props[key] += '-changed'
    view.props[key] = initial
    view.deferred[0].resolve({ stale: true })
    await pending
    assert.equal(view.result.value, undefined, key)
    assert.equal(view.input.value, '', key)
    assert.equal(view.allowResponseAbort.value, false, key)
    assert.equal(view.events.filter(event => event.name === 'changed').length, 0, key)
  }
})

test('a first replay freezes without a repair and its exact pages survive repair creation and mode switches', async () => {
  const view = panel()
  view.props.repairId = undefined
  view.props.canPropose = false
  view.template()
  const replay = JSON.parse(view.input.value)
  replay.pages[0].html = '<article>original</article>'
  replay.pages[1].html = '<article>independent</article>'
  replay.rule.fields.vod_name.selector = 'span.old'
  view.input.value = JSON.stringify(replay)
  const pending = view.submit()
  view.deferred[0].resolve({ passed: false, runId: 'run' })
  await pending
  const event = view.events.find(event => event.name === 'changed').value
  assert.equal(event.kind, 'extractionReplay')
  assert.equal(event.runId, 'run')
  assert.equal(event.sourceId, 'source')
  assert.equal(view.result.value.passed, false)
  view.props.repairId = 'created-repair'
  view.props.canPropose = true
  const draft = view.input.value
  view.mode.value = 'selector'
  assert.deepEqual(JSON.parse(view.input.value), { replay, field: 'vod_name', oldSelector: 'span.old' })
  const candidate = view.submit()
  assert.equal(view.writes[1].path, '/repairs/created-repair/selector-proposals')
  assert.deepEqual(JSON.parse(JSON.stringify(view.writes[1].body.replay)), replay)
  view.deferred[1].resolve({ id: 'candidate' })
  await candidate
  view.mode.value = 'replay'
  assert.equal(view.input.value, draft)
  assert.equal(view.result.value.passed, false)
  view.mode.value = 'selector'
  assert.equal(view.result.value.id, 'candidate')
})

test('selector templates use the submitted frozen input even after the replay draft is edited', async () => {
  const view = panel()
  view.template()
  const frozen = JSON.parse(view.input.value)
  const pending = view.submit()
  view.deferred[0].resolve({ passed: false })
  await pending
  view.input.value = '{"edited":true}'
  view.mode.value = 'selector'
  assert.deepEqual(JSON.parse(view.input.value).replay, frozen)
})

test('repair, mode and unmount changes reject late results while retaining same-run drafts', async () => {
  for (const key of ['repairId', 'mode', 'unmount']) {
    const view = panel()
    view.input.value = '{"draft":true}'
    const pending = view.submit()
    if (key === 'unmount') view.unmount()
    else if (key === 'mode') { view.mode.value = 'browser'; view.mode.value = 'replay' }
    else { view.props.repairId = 'other'; view.props.repairId = 'repair' }
    view.deferred[0].resolve({ stale: true })
    await pending
    assert.equal(view.result.value, undefined, key)
    assert.equal(view.input.value, '{"draft":true}', key)
    assert.equal(view.events.filter(event => event.name === 'changed').length, 0, key)
  }
})

test('missing baseline and disabled evidence controls never send an API request', async () => {
  const view = panel()
  view.input.value = '{}'
  view.props.runId = ''
  await view.submit()
  view.props.runId = 'run'
  view.input.value = '{}'
  view.props.disabled = true
  await view.submit()
  view.props.disabled = false
  view.mode.value = 'selector'
  view.props.repairId = undefined
  await view.submit()
  assert.equal(view.writes.length, 0)
})

test('late errors are ignored while current malformed input and proposal restrictions are enforced', async () => {
  const view = panel()
  view.input.value = '{}'
  const pending = view.submit()
  view.props.artifactSha256 = 'b'.repeat(64)
  view.deferred[0].reject(new Error('old failure'))
  await pending
  assert.deepEqual(view.errors, [])
  assert.equal(view.busy.value, false)
  view.input.value = '[]'
  await view.submit()
  assert.equal(view.errors.length, 1)
  view.mode.value = 'selector'
  view.props.canPropose = false
  view.input.value = '{}'
  await view.submit()
  assert.equal(view.writes.length, 1)
})
