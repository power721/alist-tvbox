import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { computed, ref, nextTick } from 'vue'
import { parse } from '@vue/compiler-sfc'

function panel() {
  const { descriptor, errors } = parse(readFileSync(new URL('../../src/maintenance/ProcessLogPanel.vue', import.meta.url), 'utf8'))
  assert.deepEqual(errors, [])
  const source = ts.createSourceFile('log.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const code = source.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(source)).join('\n')
  const props = { sourceId: 'source-A', runId: 'run-A' }, calls = [], timers = [], watchers = []
  const context = { ref, computed, nextTick, Error, JSON, setTimeout: (fn, delay) => { timers.push({ fn, delay }); return timers.length },
    clearTimeout() {}, onUnmounted() {}, defineExpose() {}, defineProps: () => props, defineEmits: () => () => {},
    watch: (_getter, callback) => watchers.push(callback), request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const output = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  return { ...vm.runInNewContext(output + '\n;({refresh,show,importLog,log,imported,error,visible})', context), props, calls, timers, watchers }
}

test('log refresh is read-only and follows stored active tasks without creating work', async () => {
  const view = panel()
  view.watchers[0]()
  assert.equal(view.calls[0].path, '/sources/source-A/maintenance-log?runId=run-A')
  assert.equal(view.calls[0].body, undefined)
  view.calls[0].resolve({ active: true, events: [{ id: 'real-event' }] })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.log.value.events[0].id, 'real-event')
  assert.equal(view.timers.at(-1).delay, 1500)
})

test('a response for the old selection cannot populate the new source log', async () => {
  const view = panel()
  view.watchers[0]()
  view.props.sourceId = 'source-B'; view.props.runId = 'run-B'
  view.watchers[0]()
  view.calls[0].resolve({ active: false, events: [{ id: 'old' }] })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.log.value, undefined)
  assert.equal(view.timers.at(-1).delay, 0)
  const refreshing = view.refresh()
  view.calls[1].resolve({ active: false, events: [{ id: 'new' }] })
  await refreshing
  assert.equal(view.log.value.events[0].id, 'new')
})

test('import sends data only to the analysis store and rejects oversized files before upload', async () => {
  const view = panel()
  const input = { value: 'test', files: [{ size: 10, text: async () => '{"format":"at-maintenance-log"}' }] }
  const importing = view.importLog({ target: input })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(view.calls[0].path, '/maintenance-log-imports')
  view.calls[0].resolve({ authority: 'imported_untrusted_log', active: false, events: [] })
  await importing
  assert.equal(view.imported.value.authority, 'imported_untrusted_log')
  assert.equal(view.visible.value, true)
  await view.importLog({ target: { value: 'test', files: [{ size: 3_000_000 }] } })
  assert.match(view.error.value, /2 MiB/)
  assert.equal(view.calls.length, 1)
})
