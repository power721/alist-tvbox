import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'
import { ref } from 'vue'

function panel(props = {}) {
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/ConnectionSettingsPanel.vue', import.meta.url), 'utf8'))
  const source = ts.createSourceFile('settings.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const code = source.statements.filter(node => !ts.isImportDeclaration(node)
    && !(ts.isExpressionStatement(node) && /^(defineProps|defineExpose)/.test(node.expression.getText()))
    && !(ts.isVariableStatement(node) && node.declarationList.declarations.some(item => item.name.getText() === 'emit')))
    .map(node => node.getText(source)).join('\n')
  const calls = [], events = []
  const context = { ref, Error, defineProps: () => props, ElMessage: { success() {} }, emit: name => events.push(name),
    request: (path, body, method) => new Promise((resolve, reject) => calls.push({ path, body, method, resolve, reject })) }
  const output = ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  return { ...vm.runInNewContext(output
    + '\n;({show,save,saveLimits,saveGateway,checkGateway,gatewayHealth,forgetSecrets,settings,credential,apiKey,clearApiKey,visible,busy,error,tab,addressMode,loadRunner,checkRunner,runner,runnerConnection})', context),
    calls, events }
}
const config = () => ({ at: { configured: false, origin: '', instanceId: '', revision: 'unverified',
  authMode: 'session', allowHttp: false, credentialConfigured: false },
  model: { enabled: false, autoSubmitEnabled: false, endpoint: '', baseUrl: '', model: '', keyEnv: '', apiKeyConfigured: false,
    timeoutMillis: 8000, maxCompletionTokens: 2048, maxTokenParameter: 'max_completion_tokens',
    jsonMode: 'json_object', includeStoreParameter: true },
  evidenceGateway: { enabled: false, adapter: 'trawl', endpoint: '', timeoutMillis: 30000 } })

test('all gateway adapters share one settings endpoint and preserve unsaved relay fields', async () => {
  const view = await ready()
  view.settings.value.model.model = 'unsaved-model'
  view.tab.value = 'runner'
  for (const adapter of ['trawl', 'flaresolverr', 'flaresolverr-rs-alt']) {
    view.settings.value.evidenceGateway = { enabled: true, adapter, endpoint: 'http://gateway:8191', timeoutMillis: 30000 }
    const saving = view.saveGateway()
    const call = view.calls.at(-1)
    assert.equal(call.path, '/settings/connections/evidence-gateway')
    assert.equal(call.method, 'PUT')
    assert.equal(call.body.adapter, adapter)
    call.resolve({ ...config(), evidenceGateway: { ...call.body } })
    await saving
    assert.equal(view.settings.value.model.model, 'unsaved-model')
    assert.equal(view.settings.value.evidenceGateway.adapter, adapter)
  }
  const checking = view.checkGateway()
  const call = view.calls.at(-1)
  assert.equal(call.path, '/settings/connections/evidence-gateway/health')
  assert.equal(call.body, undefined)
  call.resolve({ ready: true, code: 'EVIDENCE_GATEWAY_HEALTHY' })
  await checking
  assert.equal(view.gatewayHealth.value.ready, true)
  const failed = view.checkGateway()
  assert.equal(view.gatewayHealth.value, undefined)
  view.calls.at(-1).reject(new Error('EVIDENCE_GATEWAY_UNREACHABLE'))
  await failed
  assert.equal(view.error.value, 'EVIDENCE_GATEWAY_UNREACHABLE')
})

async function ready(section = 'at') {
  const view = panel()
  const opening = view.show(section)
  view.calls[0].resolve(config())
  await opening
  return view
}

test('AT settings use CSRF-capable PUT and forget credentials after saving', async () => {
  const view = await ready()
  view.credential.value = 'private-at-key'
  const saving = view.save()
  assert.equal(view.calls[1].path, '/settings/connections/at')
  assert.equal(view.calls[1].method, 'PUT')
  assert.equal(view.calls[1].body.credential, 'private-at-key')
  view.calls[1].resolve(config())
  await saving
  assert.equal(view.credential.value, '')
  assert.deepEqual(view.events, ['changed'])
})

test('limits save stays on the relay page, preserves the ledger and normalizes empty costs', async () => {
  const view = await ready('model')
  view.settings.value.limits = { dailyCallLimit: 0, dailyCostUsd: '' }
  const saving = view.saveLimits()
  assert.equal(view.calls[1].path, '/settings/connections/model-limits')
  assert.equal(view.calls[1].method, 'PUT')
  assert.equal(view.calls[1].body.dailyCallLimit, 0)
  assert.equal(view.calls[1].body.dailyCostUsd, null)
  view.calls[1].resolve({ ...config(), limits: { dailyCallLimit: 0 } })
  await saving
  assert.equal(view.settings.value.limits.dailyCallLimit, 0)
  assert.deepEqual(view.events, ['changed'])
})

test('relay save sends only the selected address and a write-only API key', async () => {
  const view = await ready('model')
  view.settings.value.model.endpoint = 'https://stale.invalid/v1/chat/completions'
  view.settings.value.model.baseUrl = 'https://relay.invalid/prefix/v1'
  view.settings.value.model.keyEnv = 'OLD_REFERENCE'
  view.apiKey.value = 'private-model-key'
  const saving = view.save()
  assert.equal(view.calls[1].body.endpoint, '')
  assert.equal(view.calls[1].body.baseUrl, 'https://relay.invalid/prefix/v1')
  assert.equal(view.calls[1].body.keyEnv, '')
  view.calls[1].resolve(config())
  await saving
  assert.equal(view.apiKey.value, '')
})

test('a failed save leaves settings available and does not report applied configuration', async () => {
  const view = await ready('model')
  const saving = view.save()
  view.calls[1].reject(new Error('MODEL_BUSY'))
  await saving
  assert.equal(view.error.value, 'MODEL_BUSY')
  assert.equal(view.events.length, 0)
  assert.equal(view.busy.value, false)
})

test('automatic submission is an independent saved setting and never starts a repair or publication', async () => {
  const view = await ready('model')
  assert.equal(view.settings.value.model.autoSubmitEnabled, false)
  for (const enabled of [true, false]) {
    view.settings.value.model.autoSubmitEnabled = enabled
    const saving = view.save()
    const call = view.calls.at(-1)
    assert.equal(call.path, '/settings/connections/model')
    assert.equal(call.method, 'PUT')
    assert.equal(call.body.autoSubmitEnabled, enabled)
    assert.equal(call.body.enabled, false)
    assert.equal(call.body.apiKey, '')
    call.resolve({ ...config(), model: { ...config().model, autoSubmitEnabled: enabled } })
    await saving
    assert.equal(view.settings.value.model.autoSubmitEnabled, enabled)
  }
  assert.equal(view.calls.length, 3)
  const { descriptor } = parse(readFileSync(new URL('../../src/maintenance/ConnectionSettingsPanel.vue', import.meta.url), 'utf8'))
  assert.ok(descriptor.template.content.includes('v-model="settings.model.autoSubmitEnabled"'))
})

test('closing or reopening settings clears entered secrets', async () => {
  const view = await ready()
  view.credential.value = 'not-for-storage-in-browser'
  view.apiKey.value = 'not-for-storage-in-browser'
  view.forgetSecrets()
  assert.equal(view.credential.value, '')
  assert.equal(view.apiKey.value, '')
})

test('unconfigured import remains actionable and opens connection settings', () => {
  const source = readFileSync(new URL('../../src/maintenance/AtPluginInputPanel.vue', import.meta.url), 'utf8')
  assert.ok(source.includes("configured ? visible = true : emit('configure')"))
  assert.ok(!source.includes(':disabled="disabled || !configured'))
})

test('runner status and connection checks never save credentials or start a source', async () => {
  const view = await ready()
  view.tab.value = 'runner'
  const loading = view.loadRunner()
  assert.equal(view.calls[1].path, '/managed-source-runtime')
  view.calls[1].resolve({ blockedReason: '', snapshot: { enabled: true, transport: 'loopback_tunnel' } })
  await loading
  await view.save()
  assert.equal(view.calls.length, 2)
  const checking = view.checkRunner()
  assert.equal(view.calls[2].path, '/managed-source-runtime/connection')
  assert.equal(view.calls[2].method, undefined)
  assert.equal(view.calls[2].body, undefined)
  view.calls[2].resolve({ connected: true, ready: true, sourceExecuted: false, code: '' })
  await checking
  assert.equal(view.runnerConnection.value.ready, true)
  assert.equal(view.events.length, 0)
})

test('runner connection failures clear earlier success without retrying', async () => {
  const view = await ready()
  view.runner.value = { blockedReason: '', snapshot: {} }
  view.runnerConnection.value = { connected: true, ready: true }
  const checking = view.checkRunner()
  view.calls[1].reject(new Error('CONNECTION_FAILED'))
  await checking
  assert.equal(view.runnerConnection.value, undefined)
  assert.equal(view.error.value, 'CONNECTION_FAILED')
  assert.equal(view.busy.value, false)
  assert.equal(view.calls.length, 2)
})

test('runner configuration and identity checks are scoped to the selected source', async () => {
  const view = panel({ sourceId: 'source-A' })
  const opening = view.show()
  view.calls[0].resolve(config())
  await opening
  const loading = view.loadRunner()
  assert.equal(view.calls[1].path, '/managed-source-runtime?sourceId=source-A')
  view.calls[1].resolve({ blockedReason: '', routeLabel: 'Image API', snapshot: {} })
  await loading
  const checking = view.checkRunner()
  assert.equal(view.calls[2].path, '/managed-source-runtime/connection?sourceId=source-A')
  view.calls[2].resolve({ ready: true })
  await checking
  assert.equal(view.runner.value.routeLabel, 'Image API')
  assert.equal(view.events.length, 0)
})

test('switching sources remounts settings and loads source-scoped configuration for the main workflow', () => {
  const source = readFileSync(new URL('../../src/maintenance/MaintenanceWorkbench.vue', import.meta.url), 'utf8')
  assert.ok(source.includes('<ConnectionSettingsPanel :key="selectedId"'))
  assert.ok(source.includes(':source-id="selectedId"'))
  assert.ok(source.includes('`/managed-source-runtime?sourceId=${encodeURIComponent(id)}`'))
  assert.ok(source.includes('managedConfiguration.value = selectedRuntime'))
})
