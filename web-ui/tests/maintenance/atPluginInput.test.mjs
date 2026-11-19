import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, ref, watch } from 'vue'

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/AtPluginInputPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('at-input.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const code = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isExpressionStatement(node) && node.expression.getText().startsWith('defineProps'))
    && !(ts.isVariableStatement(node) && node.declarationList.declarations.some(item => item.name.getText() === 'emit')))
    .map(node => node.getText(source)).join('\n')
  const calls = [], events = [], hooks = []
  const context = { ref, computed, watch, Error, onUnmounted: fn => hooks.push(fn),
    crypto: { randomUUID: () => 'same-request-key' }, ElMessage: { success() {} },
    emit: (name, id) => events.push({ name, id }),
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const output = ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  return { ...vm.runInNewContext(output + '\n;({observe, inspect, save, resetSaved, observation, pluginId, authorized, name, filename, preview, pending, saved, error, canSave})', context),
    calls, events, hooks }
}
const observed = { sha256: 'a'.repeat(64), plugins: [
  { id: 7, name: 'owned', enabled: true, t4Visible: true, kind: 'python' }] }
const preview = { sourceId: null, artifactSha256: 'b'.repeat(64), evidenceSha256: 'c'.repeat(64),
  evidence: { atSource: { pluginId: 7, observationSha256: observed.sha256 } } }

async function ready() {
  const view = panel()
  const read = view.observe()
  view.calls[0].resolve(observed)
  await read
  view.pluginId.value = 7
  view.authorized.value = true
  const inspect = view.inspect()
  assert.equal(view.calls[1].path, '/at/plugin-inputs/preview')
  view.calls[1].resolve(preview)
  await inspect
  return view
}

test('AT preview and confirm use only the observed plugin identity and verify the receipt', async () => {
  const view = await ready()
  assert.equal(view.canSave.value, true)
  const saving = view.save()
  assert.equal(view.calls[2].path, '/at/plugin-inputs')
  assert.equal(view.calls[2].body.observationSha256, observed.sha256)
  assert.equal(view.calls[2].body.expectedEvidenceSha256, preview.evidenceSha256)
  assert.equal('inputBase64' in view.calls[2].body, false)
  view.calls[2].resolve({ ...preview, sourceId: 'created' })
  await saving
  assert.equal(view.saved.value.sourceId, 'created')
  assert.equal(view.events[0].id, 'created')
  view.resetSaved()
  assert.equal(view.pending.value, undefined)
})

test('ambiguous confirmation keeps the same request while a definite drift allows a fresh preview', async () => {
  const view = await ready()
  const saving = view.save()
  const original = view.calls[2].body
  view.calls[2].reject(new Error('network error'))
  await saving
  assert.equal(view.pending.value, original)
  await view.observe()
  assert.equal(view.calls.length, 3)
  const retry = view.save()
  assert.equal(view.calls[3].body, original)
  view.calls[3].reject(new Error('PLUGIN_INPUT_PREVIEW_CHANGED'))
  await retry
  assert.equal(view.pending.value, undefined)
  assert.equal(view.preview.value, undefined)
})

test('mismatched receipts never select a source and unmounted responses are ignored', async () => {
  const view = await ready()
  const saving = view.save()
  view.calls[2].resolve({ ...preview, sourceId: 'wrong', evidenceSha256: 'wrong' })
  await saving
  assert.equal(view.saved.value, undefined)
  assert.equal(view.events.length, 0)
  assert.ok(view.pending.value)
  const retry = view.save()
  view.hooks[0]()
  view.calls[3].resolve({ ...preview, sourceId: 'late' })
  await retry
  assert.equal(view.events.length, 0)
})

test('changing plugin identity invalidates a late preview, including an ABA change', async () => {
  const view = panel()
  view.observation.value = observed
  view.pluginId.value = 7
  view.authorized.value = true
  const reading = view.inspect()
  view.pluginId.value = 8
  view.pluginId.value = 7
  view.calls[0].resolve(preview)
  await reading
  assert.equal(view.preview.value, undefined)
  assert.equal(view.authorized.value, false)
})

test('explicitly excluded plugins cannot preview or save while similar names stay eligible', async () => {
  const view = panel()
  view.observation.value = { ...observed, excludedPluginIds: [7] }
  view.pluginId.value = 7
  view.authorized.value = true
  view.preview.value = preview
  assert.equal(view.canSave.value, false)
  await view.inspect()
  await view.save()
  assert.equal(view.calls.length, 0)
  view.observation.value = { ...observed, excludedPluginIds: [416], plugins: [
    { ...observed.plugins[0], name: '豆瓣TMDB助手' }] }
  const reading = view.inspect()
  assert.equal(view.calls.length, 1)
  view.calls[0].resolve(preview)
  await reading
  assert.equal(view.canSave.value, true)
})

test('exclusion during confirmation is terminal and never asks to retry the same import', async () => {
  const view = await ready()
  const saving = view.save()
  view.calls[2].reject(new Error('AT_PLUGIN_MAINTENANCE_EXCLUDED'))
  await saving
  assert.equal(view.pending.value, undefined)
  assert.equal(view.preview.value, undefined)
  assert.equal(view.saved.value, undefined)
  assert.equal(view.error.value, '此插件不参与维护')
  assert.equal(view.events.length, 0)
})
