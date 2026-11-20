import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { webcrypto } from 'node:crypto'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { computed, reactive, ref, watch } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
const target = (values = {}) => ({ enabled: true, pluginId: 7, pluginSha256: 'baseline',
  runtime: { loadState: 'LOADED', workerGeneration: 'generation-A' }, deployments: [], ...values })
const deployment = (values = {}) => ({ id: 'deployment-A', planId: 'plan-A', state: 'DEPLOYED',
  loadedGeneration: 'generation-B', errorCode: null, ...values })

function panel() {
  const props = reactive({ planId: 'plan-A', frozenSha256: 'frozen', baselineSha256: 'baseline', eligible: true })
  const calls = [], confirms = [], errors = [], stops = []
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/NativeDeploymentPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('panel.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isVariableStatement(node) && node.declarationList.declarations
      .some(item => item.name.getText() === 'props'))).map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.None } }).outputText
  const view = vm.runInNewContext(code
    + '\n;({refresh,transition,target,result,error,busy,canDeploy,interruptPlayback})', {
    props, ref, computed, watch: (...args) => { const stop = watch(...args); stops.push(stop); return stop },
    crypto: webcrypto, Error, onUnmounted() {}, ElMessage: { error: value => errors.push(value) },
    ElMessageBox: { confirm: () => new Promise((resolve, reject) => confirms.push({ resolve, reject })) },
    request: (path, body) => new Promise((resolve, reject) => calls.push({ path, body, resolve, reject }))
  })
  return { ...view, props, calls, confirms, errors, stop: () => stops.forEach(fn => fn()) }
}
async function ready(view, value = target()) {
  const reading = view.refresh()
  assert.equal(view.calls[0].path, '/at-native/release-plans/plan-A/target')
  view.calls[0].resolve(value)
  await reading
}

test('native deployment sends the frozen plan and observed generation and retains its confirmed result', async () => {
  const view = panel()
  try {
    await ready(view)
    const operation = view.transition(false)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[1].path, '/at-native/plugins/7/deployments')
    const input = view.calls[1].body
    assert.equal(input.planId, 'plan-A')
    assert.equal(input.expectedFrozenSha256, 'frozen')
    assert.equal(input.expectedPluginSha256, 'baseline')
    assert.equal(input.expectedWorkerGeneration, 'generation-A')
    assert.equal(input.interruptIssuedPlayback, false)
    assert.match(input.requestKey, /^[a-f0-9-]{36}$/)
    view.calls[1].resolve(deployment())
    await settle()
    view.calls[2].reject(new Error('read unavailable'))
    await operation
    assert.equal(view.result.value.state, 'DEPLOYED')
    assert.equal(view.canDeploy.value, false)
    assert.equal(view.errors[0], 'read unavailable')
  } finally { view.stop() }
})

test('disabled, ineligible and mismatched targets cannot deploy', async () => {
  const view = panel()
  try {
    await ready(view, target({ enabled: false }))
    await view.transition(false)
    view.target.value = target({ pluginSha256: 'other' })
    await view.transition(false)
    view.target.value = target()
    view.props.eligible = false
    await view.transition(false)
    assert.equal(view.confirms.length, 0)
    assert.equal(view.calls.length, 1)
  } finally { view.stop() }
})

test('a lost response reads the durable intent without replaying the write', async () => {
  const view = panel()
  try {
    await ready(view)
    const operation = view.transition(false)
    view.confirms[0].resolve()
    await settle()
    view.calls[1].reject(new Error('response lost'))
    await settle()
    assert.equal(view.calls[2].body, undefined)
    view.calls[2].resolve(target({ deployments: [deployment()] }))
    await operation
    assert.equal(view.calls.filter(call => call.body).length, 1)
    assert.equal(view.canDeploy.value, false)
    assert.equal(view.errors[0], 'response lost')
  } finally { view.stop() }
})

test('unresolved writes retain their request key and original generation on an explicit retry', async () => {
  const view = panel()
  try {
    await ready(view)
    let operation = view.transition(false)
    view.confirms[0].resolve()
    await settle()
    const input = view.calls[1].body
    view.calls[1].reject(new Error('write unavailable'))
    await settle()
    view.calls[2].reject(new Error('read unavailable'))
    await operation
    operation = view.transition(false)
    view.confirms[1].resolve()
    await settle()
    assert.deepEqual(view.calls[3].body, input)
    view.calls[3].resolve(deployment({ state: 'REJECTED', loadedGeneration: null,
      errorCode: 'AT_WORKER_GENERATION_CHANGED' }))
    await settle()
    view.calls[4].resolve(target())
    await operation
    assert.equal(view.result.value.state, 'REJECTED')
  } finally { view.stop() }
})

test('rollback uses the active intent and blocked worker generation with explicit interruption consent', async () => {
  const view = panel()
  try {
    await ready(view, target({ runtime: { loadState: 'BLOCKED', workerGeneration: 'generation-B' },
      deployments: [deployment({ state: 'RECOVERY_REQUIRED' })] }))
    view.interruptPlayback.value = true
    const operation = view.transition(true)
    view.confirms[0].resolve()
    await settle()
    assert.equal(view.calls[1].path, '/at-native/deployments/deployment-A/rollback')
    assert.equal(view.calls[1].body.expectedWorkerGeneration, 'generation-B')
    assert.equal(view.calls[1].body.interruptIssuedPlayback, true)
    view.calls[1].resolve(deployment({ state: 'ROLLED_BACK', loadedGeneration: 'generation-C' }))
    await settle()
    view.calls[2].resolve(target({ deployments: [deployment({ state: 'ROLLED_BACK' })] }))
    await operation
    assert.equal(view.result.value.state, 'ROLLED_BACK')
  } finally { view.stop() }
})

test('changed plans discard old confirmations, late reads and late write results', async () => {
  const view = panel()
  try {
    await ready(view)
    let operation = view.transition(false)
    view.props.planId = 'plan-B'
    view.confirms[0].resolve()
    await operation
    assert.equal(view.calls.length, 1)
    view.target.value = target()
    operation = view.transition(false)
    view.confirms[1].resolve()
    await settle()
    view.props.planId = 'plan-C'
    view.calls[1].resolve(deployment())
    await operation
    assert.equal(view.result.value, undefined)
    assert.equal(view.calls.length, 2)
    const read = view.refresh()
    view.props.planId = 'plan-D'
    view.calls[2].resolve(target())
    await read
    assert.equal(view.target.value, undefined)
  } finally { view.stop() }
})
