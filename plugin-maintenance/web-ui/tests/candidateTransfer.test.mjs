import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, ref } from 'vue'

function panel() {
  const calls = []
  const props = { sourceId: 'new-source', artifactSha256: 'baseline', baselineRunId: 'new-run' }
  const { descriptor } = parse(readFileSync(new URL('../src/RepairPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations
      .some(item => ['props', 'emit'].includes(item.name.getText()))))
    .map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.None } }).outputText
  const view = vm.runInNewContext(code + '\n;({reuseImported,selected,selectedId,importedCandidates,selectedImportId,busy,'
    + 'invalidate(){revision++}})', {
    props, ref, computed, watch() {}, onUnmounted() {}, emit() {}, evidenceFields: () => [],
    ElMessage: { error(error) { throw new Error(error) } },
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject }))
  })
  view.selected.value = { id: 'new-repair', status: 'AWAITING_PROPOSAL', freshness: 'current',
    baselineArtifactSha256: 'baseline' }
  view.selectedId.value = 'new-repair'
  view.importedCandidates.value = [{ importId: 'import', archiveSha256: 'archive', baselineArtifactSha256: 'baseline',
    candidateSha256: 'candidate', originalModelCallId: 'historical-model-call' }]
  view.selectedImportId.value = 'import'
  return { ...view, calls, props }
}

test('reuse submits only the archive identity and never repeats a model call', async () => {
  const view = panel()
  const operation = view.reuseImported()
  assert.equal(view.calls[0].path, '/repairs/new-repair/imported-candidates')
  assert.deepEqual(JSON.parse(JSON.stringify(view.calls[0].body)),
    { importId: 'import', expectedArchiveSha256: 'archive' })
  assert.equal(view.busy.value, true)
  view.invalidate()
  view.calls[0].resolve({ id: 'new-candidate' })
  await operation
  assert.equal(view.calls.length, 1)
  assert.equal(view.busy.value, false)
})

test('stale baseline, repair state and mismatched imports cannot submit', async () => {
  for (const change of [
    view => { view.props.artifactSha256 = 'changed' },
    view => { view.selected.value.freshness = 'inputs_changed' },
    view => { view.selected.value.status = 'VALIDATING' },
    view => { view.importedCandidates.value[0].baselineArtifactSha256 = 'other' }
  ]) {
    const view = panel()
    change(view)
    await view.reuseImported()
    assert.equal(view.calls.length, 0)
  }
})

test('a second click during submission cannot create another candidate', async () => {
  const view = panel()
  const first = view.reuseImported()
  await view.reuseImported()
  assert.equal(view.calls.length, 1)
  view.invalidate()
  view.calls[0].resolve({})
  await first
})
