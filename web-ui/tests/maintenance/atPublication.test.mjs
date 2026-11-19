import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { webcrypto } from 'node:crypto'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
const target = () => ({ available: true, defaultMode: 'incremental', bindingSha256: 'binding',
  originalPluginId: 7, originalPluginName: '原件', delivery: { pluginName: '测试源', version: '1.0.1' },
  publications: [] })
function panel() {
  const props = reactive({ candidateId: 'candidate', candidateSha256: 'sha', disabled: false })
  const calls = [], confirms = [], stops = []
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/AtPublicationPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations
      .some(item => item.name.getText() === 'props'))).map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.None } }).outputText
  const view = vm.runInNewContext(code + '\n;({open,publish,saveName,deliveryName,inspectRuntime,activate,runtime,runtimeBusy,pendingActivation,interruptPlayback,target,mode,authorized,pending,result,error,busy,canPublish})', {
    props, ref, computed, crypto: webcrypto, Error, onUnmounted() {},
    watch: (...args) => { const stop = watch(...args); stops.push(stop); return stop },
    ElMessageBox: { confirm: () => new Promise((resolve, reject) => confirms.push({ resolve, reject })) },
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject }))
  })
  return { ...view, props, calls, confirms, stop: () => stops.forEach(stop => stop()) }
}
async function ready(view, value = target()) {
  const opening = view.open()
  assert.equal(view.calls[0].body, undefined)
  view.calls[0].resolve(value)
  await opening
  view.authorized.value = true
}

test('publication defaults to incremental and requires explicit write consent', async () => {
  const view = panel()
  try {
    await ready(view)
    view.authorized.value = false
    await view.publish()
    assert.equal(view.calls.length, 1)
    view.authorized.value = true
    const writing = view.publish()
    assert.equal(view.confirms.length, 0)
    assert.equal(view.calls[1].body.mode, 'incremental')
    assert.equal(view.calls[1].body.authorized, true)
    assert.equal(view.calls[1].body.expectedBindingSha256, 'binding')
    assert.equal(view.calls[1].body.deliveryName, '测试源')
    view.calls[1].resolve({ state: 'PUBLISHED', publishedPluginId: 8 })
    await writing
    assert.equal(view.canPublish.value, false)
  } finally { view.stop() }
})

test('new sources can only publish incrementally to the frozen target', async () => {
  const view = panel()
  try {
    await ready(view, { ...target(), originalPluginId: null, originalPluginName: null,
      bindingSha256: 'new-source-target' })
    view.mode.value = 'replace'
    await view.publish()
    assert.equal(view.canPublish.value, false)
    assert.equal(view.calls.length, 1)
    assert.equal(view.confirms.length, 0)
    view.mode.value = 'incremental'
    const writing = view.publish()
    assert.equal(view.calls[1].body.expectedBindingSha256, 'new-source-target')
    assert.equal(view.calls[1].body.mode, 'incremental')
    view.calls[1].resolve({ state: 'PUBLISHED', originalPluginId: null, publishedPluginId: 8 })
    await writing
    assert.equal(view.result.value.publishedPluginId, 8)
  } finally { view.stop() }
})

test('a Chinese prefix alone is insufficient and a real localized name is sent with publication', async () => {
  const view = panel()
  try {
    await ready(view)
    view.deliveryName.value = '影视源_Xvideos_TuT'
    await view.publish()
    assert.equal(view.calls.length, 1)
    view.deliveryName.value = '艾克斯视频'
    const writing = view.publish()
    assert.equal(view.calls[1].body.deliveryName, '艾克斯视频')
    view.calls[1].resolve({ state: 'PUBLISHED', publishedPluginId: 8 })
    await writing
  } finally { view.stop() }
})

test('localizing an existing publication changes only its name through the platform', async () => {
  const view = panel()
  try {
    await ready(view, { ...target(), publications: [{
      id: 'existing', mode: 'incremental', state: 'PUBLISHED', publishedPluginId: 569,
      pluginName: '影视源_Xvideos_TuT_v1.0.1'
    }] })
    view.calls[1].resolve({ state: 'UNOBSERVED', activationAvailable: false })
    await settle()
    view.deliveryName.value = '艾克斯视频'
    const saving = view.saveName()
    assert.equal(view.calls[2].path, '/repair-candidates/candidate/at-publications/existing/name')
    assert.equal(view.calls[2].body.expectedPluginName, '影视源_Xvideos_TuT_v1.0.1')
    assert.equal(view.calls[2].body.deliveryName, '艾克斯视频')
    view.calls[2].resolve({ id: 'existing', state: 'PUBLISHED', publishedPluginId: 569,
      pluginName: '艾克斯视频_v1.0.1' })
    await settle()
    view.calls[3].resolve({ ...target(), delivery: { pluginName: '艾克斯视频', version: '1.0.1' } })
    await saving
    assert.equal(view.result.value.publishedPluginId, 569)
    assert.equal(view.target.value.delivery.pluginName, '艾克斯视频')
  } finally { view.stop() }
})

test('replace mode confirms original plugin before writing', async () => {
  const view = panel()
  try {
    await ready(view)
    view.mode.value = 'replace'
    const writing = view.publish()
    assert.equal(view.calls.length, 1)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[1].body.mode, 'replace')
    view.calls[1].resolve({ state: 'PUBLISHED', publishedPluginId: 7 })
    await writing
    assert.equal(view.result.value.publishedPluginId, 7)
  } finally { view.stop() }
})

test('a lost reply preserves the exact request key and mode for explicit continuation', async () => {
  const view = panel()
  try {
    await ready(view)
    let writing = view.publish()
    const input = view.calls[1].body
    view.calls[1].reject(new Error('connection lost'))
    await writing
    assert.equal(view.pending.value.requestKey, input.requestKey)
    writing = view.publish()
    assert.equal(view.calls[2].body, input)
    view.calls[2].resolve({ state: 'PUBLISHED', publishedPluginId: 8 })
    await writing
  } finally { view.stop() }
})

test('changed candidate invalidates a replacement confirmation and old responses', async () => {
  const view = panel()
  try {
    await ready(view)
    view.mode.value = 'replace'
    const writing = view.publish()
    view.props.candidateId = 'other'
    view.confirms[0].resolve()
    await writing
    assert.equal(view.calls.length, 1)
    assert.equal(view.pending.value, undefined)
  } finally { view.stop() }
})

test('ineligible candidates never submit a publication', async () => {
  const view = panel()
  try {
    await ready(view, { ...target(), available: false })
    await view.publish()
    assert.equal(view.calls.length, 1)
    assert.equal(view.canPublish.value, false)
  } finally { view.stop() }
})

const saved = () => ({ id: 'publication', mode: 'replace', state: 'PUBLISHED', publishedPluginId: 7 })
const waiting = () => ({ state: 'RELOAD_REQUIRED', activationAvailable: true,
  target: { configurationSha256: 'configuration', runtime: { workerGeneration: 'generation' }, activations: [] } })
async function published(view) {
  await ready(view, { ...target(), defaultMode: 'replace', publications: [saved()] })
  await settle()
  view.calls[1].resolve(waiting())
  await settle()
}
test('published files do not imply activation or automatically interrupt a worker', async () => {
  const view = panel()
  try {
    await published(view)
    assert.equal(view.runtime.value.state, 'RELOAD_REQUIRED')
    assert.equal(view.calls.length, 2)
    const applying = view.activate()
    assert.equal(view.calls[2].body.interruptIssuedPlayback, false)
    assert.equal(view.calls[2].body.expectedWorkerGeneration, 'generation')
    view.calls[2].resolve({ state: 'ACTIVE', activationAvailable: false })
    await applying
    assert.equal(view.runtime.value.state, 'ACTIVE')
  } finally { view.stop() }
})
test('activation lost reply keeps exact request until explicitly continued', async () => {
  const view = panel()
  try {
    await published(view)
    let applying = view.activate()
    const input = view.calls[2].body
    view.calls[2].reject(new Error('lost activation reply'))
    await applying
    applying = view.activate()
    assert.equal(view.calls[3].body, input)
    view.calls[3].resolve({ state: 'ACTIVE', activationAvailable: false })
    await applying
    assert.equal(view.pendingActivation.value, undefined)
  } finally { view.stop() }
})
test('interrupting existing playback requires another confirmation and recovery uses recorded activation', async () => {
  const view = panel()
  try {
    await published(view)
    view.runtime.value = { ...waiting(), state: 'RECOVERY_REQUIRED',
      target: { ...waiting().target, activations: [{ id: 'failed-activation', state: 'RECOVERY_REQUIRED' }] } }
    view.interruptPlayback.value = true
    const applying = view.activate()
    assert.equal(view.calls.length, 2)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[2].body.activationId, 'failed-activation')
    assert.equal(view.calls[2].body.interruptIssuedPlayback, true)
    view.calls[2].resolve({ state: 'ACTIVE', activationAvailable: false })
    await applying
  } finally { view.stop() }
})
