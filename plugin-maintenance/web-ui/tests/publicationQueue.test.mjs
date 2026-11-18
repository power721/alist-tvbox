import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { ref } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
function panel() {
  const calls = [], confirms = []
  const { descriptor } = parse(readFileSync(new URL('../src/PublicationQueuePanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  const view = vm.runInNewContext(code + '\n;({open,close,refresh,saveMode,tasks,modes,busy,error})', {
    ref, Promise, Error, onUnmounted() {}, setTimeout: () => 1, clearTimeout() {},
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })),
    ElMessageBox: { confirm: () => new Promise((resolve, reject) => confirms.push({ resolve, reject })) }
  })
  return { ...view, calls, confirms }
}
const task = () => ({ id: 'publication:candidate', kind: 'publication', ownerId: 'candidate', state: 'QUEUED', attempts: 0,
  context: { candidateSha256: 'sha', bindingSha256: 'binding', mode: 'incremental' } })
async function ready(view) {
  view.open()
  view.calls[0].resolve([task()]); view.calls[1].resolve({ enabled: true })
  await settle()
}
test('queue reads durable tasks and keeps incremental as the initial mode', async () => {
  const view = panel(); await ready(view)
  assert.equal(view.tasks.value.length, 1)
  assert.equal(view.modes.value['publication:candidate'], 'incremental')
  view.close()
})
test('replacement mode requires explicit confirmation before updating the queue', async () => {
  const view = panel(); await ready(view)
  view.modes.value['publication:candidate'] = 'replace'
  const saving = view.saveMode(view.tasks.value[0])
  assert.equal(view.calls.length, 2); assert.equal(view.confirms.length, 1)
  view.confirms[0].resolve(); await settle()
  assert.equal(view.calls[2].body.mode, 'replace')
  assert.equal(view.calls[2].body.expectedBindingSha256, 'binding')
  assert.equal(view.calls[2].body.authorized, true)
  view.calls[2].resolve({ state: 'QUEUED' }); await saving
  view.calls[3].resolve([task()]); view.calls[4].resolve({ enabled: true }); await settle()
  view.close()
})
test('an issued publication cannot change mode', async () => {
  const view = panel(); await ready(view)
  view.tasks.value[0].attempts = 1
  await view.saveMode(view.tasks.value[0])
  assert.equal(view.calls.length, 2); assert.equal(view.confirms.length, 0)
  view.close()
})

test('a queued new source cannot switch to replacement', async () => {
  const view = panel(); await ready(view)
  view.tasks.value[0].context.newSource = true
  view.modes.value['publication:candidate'] = 'replace'
  await view.saveMode(view.tasks.value[0])
  assert.equal(view.calls.length, 2)
  assert.equal(view.confirms.length, 0)
  assert.equal(view.error.value, '新来源仅支持增量新增')
  view.close()
})
