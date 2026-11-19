import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { ref } from 'vue'

function panel() {
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/ReverseAnalysisPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const code = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations.some(item => item.name.getText() === 'emit')))
    .map(node => node.getText(source)).join('\n')
  const calls = [], mounted = []
  const context = { ref, Error, defineProps: () => ({ repairId: 'repair', faultSha256: 'fault' }), emit() {},
    watch: (_, callback) => callback(), onMounted: callback => mounted.push(callback), onUnmounted() {},
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject })) }
  const output = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
  return { ...vm.runInNewContext(output + '\n;({capture,ready,browserReady,report,busy,error})', context), calls,
    mount: () => mounted[0](), template: descriptor.template.content }
}

test('script-ready executor uses the existing analysis control without requiring a browser', async () => {
  const view = panel()
  const mounting = view.mount()
  view.calls[0].resolve({ ready: false, scriptInspectionReady: true })
  await mounting
  assert.equal(view.ready.value, true)
  const capturing = view.capture()
  assert.equal(view.calls[1].path, '/repairs/repair/script-inspection')
  view.calls[1].resolve({ collectionStatus: 'unavailable', requests: [], scripts: [], failures: [{ code: 'EVIDENCE_GATEWAY_BUSY' }] })
  await capturing
  assert.equal(view.report.value.pauses, undefined)
  assert.equal(view.busy.value, false)
  assert.ok(view.template.includes('report.pauses?.length ?? 0'))
  assert.ok(view.template.includes("typeof item === 'string' ? item : item.code"))
})

test('configured browser keeps the same analysis command', async () => {
  const view = panel()
  const mounting = view.mount()
  view.calls[0].resolve({ ready: true, scriptInspectionReady: true })
  await mounting
  const capturing = view.capture()
  assert.equal(view.calls[1].path, '/repairs/repair/reverse-analysis')
  view.calls[1].reject(new Error('REVERSE_CAPTURE_FAILED'))
  await capturing
  assert.equal(view.error.value, 'REVERSE_CAPTURE_FAILED')
})
