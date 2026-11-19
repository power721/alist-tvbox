import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, ref, watch } from 'vue'

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/UpstreamPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('upstream.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const code = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations.some(item => ['props', 'emit'].includes(item.name.getText()))))
    .map(node => node.getText(source)).join('\n')
  const calls = [], messages = []
  const context = { ref, computed, watch, Error,
    props: { sourceId: 'owned', artifactSha256: 'a'.repeat(64) },
    onMounted() {}, onUnmounted() {}, emit() {},
    crypto: { randomUUID: () => 'persistent-request-key' },
    ElMessage: { success: value => messages.push(value), error: value => messages.push(value) },
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const output = ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  return { ...vm.runInNewContext(output + '\n;({load, fetchAt, canFetchAt, state, binding, busy})', context), calls, messages }
}

const binding = { sha256: 'b'.repeat(64), connectionMatches: true, snapshot: { plugin: { kind: 'python' } } }
async function loaded(view, state, bound = binding) {
  const offset = view.calls.length
  const loading = view.load()
  const results = [state, [], [{ sha256: 'a'.repeat(64) }], bound]
  results.forEach((value, index) => view.calls[offset + index].resolve(value))
  await loading
}

test('AT fetch works before manual initialization and sends only bound identities', async () => {
  const view = panel()
  await loaded(view, undefined)
  assert.equal(view.canFetchAt.value, true)
  const work = view.fetchAt()
  assert.equal(view.canFetchAt.value, false)
  const call = view.calls.at(-1)
  assert.equal(call.path, '/sources/owned/upstream/at-observations')
  assert.deepEqual(JSON.parse(JSON.stringify(call.body)), {
    expectedRevision: null, expectedArtifactSha256: 'a'.repeat(64),
    expectedBindingSha256: binding.sha256, authorized: true, requestKey: 'persistent-request-key'
  })
  call.reject(new Error('temporary failure'))
  await work
  const retry = view.fetchAt()
  assert.equal(view.calls.at(-1).body, call.body)
  view.calls.at(-1).reject(new Error('still unavailable'))
  await retry
})

test('unbound, mismatched and non-Python bindings cannot trigger a fetch', async () => {
  for (const bound of [undefined, { ...binding, connectionMatches: false },
    { ...binding, snapshot: { plugin: { kind: 'secspider' } } }]) {
    const view = panel()
    await loaded(view, undefined, bound)
    if (bound === undefined) view.binding.value = undefined
    const count = view.calls.length
    assert.equal(Boolean(view.canFetchAt.value), false)
    await view.fetchAt()
    assert.equal(view.calls.length, count)
  }
})

test('an initialized upstream fetch preserves its expected revision', async () => {
  const view = panel()
  await loaded(view, { revision: 7 })
  const work = view.fetchAt()
  const call = view.calls.at(-1)
  assert.equal(call.body.expectedRevision, 7)
  call.reject(new Error('UPSTREAM_CHANGED'))
  await work
  assert.equal(view.state.value.revision, 7)
  assert.equal(view.busy.value, false)
})
