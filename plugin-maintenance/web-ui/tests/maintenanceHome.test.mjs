import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { computed, ref } from 'vue'
import { parse } from '@vue/compiler-sfc'

function component(name) {
  const result = parse(readFileSync(new URL(`../src/${name}.vue`, import.meta.url), 'utf8'))
  assert.deepEqual(result.errors, [])
  return result.descriptor
}
function elements(node, parents = [], result = []) {
  if (node.type === 1) result.push({ node, parents })
  for (const child of node.children ?? []) elements(child, node.type === 1 ? [...parents, node] : parents, result)
  return result
}
const attribute = (node, name) => node.props.find(prop => prop.type === 6 && prop.name === name)?.value?.content
const withinDetails = parents => parents.some(node => node.tag === 'details')

test('the default workspace contains only model repair and adoption, with diagnostics folded', () => {
  const nodes = elements(component('App').template.ast)
  const tabs = nodes.filter(({ node, parents }) => node.tag === 'el-tab-pane' && !withinDetails(parents))
  assert.deepEqual(tabs.map(({ node }) => attribute(node, 'name')), ['repair', 'release'])
  for (const tag of ['ProbeEvidencePanel', 'PluginInputEvidence', 'ScheduleObservationPanel',
    'MediaProbePanel', 'UpstreamPanel', 'DirectoryImportPanel', 'NasDirectoryPanel', 'BusinessEvidencePanel']) {
    const items = nodes.filter(({ node }) => node.tag === tag)
    assert.ok(items.length > 0, tag)
    assert.ok(items.every(({ parents }) => withinDetails(parents)), tag)
  }
  const advanced = nodes.find(({ node }) => attribute(node, 'class') === 'advanced-workspace').node
  assert.ok(!advanced.props.some(prop => prop.type === 6 && prop.name === 'open'))
  const script = component('App').scriptSetup.content
  assert.ok(script.includes("const advancedOpen = ref(false)"))
})

test('manual JSON, replay and budget stay folded while the model path remains visible', () => {
  const nodes = elements(component('RepairPanel').template.ast)
  const model = nodes.find(({ node }) => node.tag === 'ModelProposalPanel')
  assert.ok(!withinDetails(model.parents))
  for (const tag of ['EvidenceToolsPanel', 'ModelBudgetPanel', 'el-input']) {
    assert.ok(nodes.filter(({ node }) => node.tag === tag).every(({ parents }) => withinDetails(parents)), tag)
  }
  assert.ok(component('ConnectionSettingsPanel').template.content.includes("@click=\"show('model')\""))
  assert.ok(!component('RepairPanel').scriptSetup.content.includes('ModelBudgetPanel'))
  assert.ok(component('ConnectionSettingsPanel').scriptSetup.content.includes('ModelBudgetPanel'))
})

test('imports share a drawer, the crawler catalog stays central and scheduled inspection is live-only', () => {
  const app = component('App')
  const nodes = elements(app.template.ast)
  for (const tag of ['AtPluginInputPanel', 'PluginInputPanel', 'DirectoryImportPanel', 'SourceImportPanel'])
    assert.ok(nodes.filter(({ node }) => node.tag === tag).every(({ parents }) => parents.some(node => node.tag === 'el-drawer')))
  assert.ok(app.template.content.includes('class="crawler-catalog"'))
  assert.ok(app.template.content.includes('<ProcessLogPanel'))
  assert.ok(!app.template.content.includes('label="静态 AST 检查"'))
  assert.ok(app.scriptSetup.content.includes("scheduleMode.value = 'managed_source'"))
})

test('smart inspection is above the list and its time, cycle and repair controls share one drawer', () => {
  const app = component('App')
  const nodes = elements(app.template.ast)
  const button = nodes.find(({ node }) => attribute(node, 'class') === 'smart-inspection')
  assert.ok(button.parents.some(node => attribute(node, 'class') === 'crawler-catalog'))
  assert.ok(app.template.content.indexOf('class="smart-inspection"') < app.template.content.indexOf('class="source-list"'))
  const drawer = nodes.find(({ node }) => node.tag === 'el-drawer' && attribute(node, 'title') === '智能巡检')
  const fields = elements(drawer.node).filter(({ node }) => node.tag === 'el-form-item').map(({ node }) => attribute(node, 'label'))
  for (const name of ['首次执行时间（本地时间）', '巡检周期（小时）', '巡检后自动修复']) assert.ok(fields.includes(name))
})

test('current exhausted and degraded crawlers are highlighted, network blocks and old versions are not', () => {
  const source = ts.createSourceFile('home.ts', component('App').scriptSetup.content,
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const body = source.statements.filter(node => ts.isFunctionDeclaration(node)
    && ['sourceState', 'sourceAttention'].includes(node.name?.text)).map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
  const row = { sourceId: 'A', artifactSha256: 'sha', state: 'blocked' }
  const view = vm.runInNewContext(code + '\n;({sourceState,sourceAttention})', { overview: { value: [row] } })
  const crawler = { id: 'A', artifactSha256: 'sha' }
  assert.equal(view.sourceAttention(crawler), false)
  row.repairExhausted = true
  assert.equal(view.sourceState(crawler), 'repair_exhausted')
  assert.equal(view.sourceAttention(crawler), true)
  row.repairExhausted = false
  row.degraded = true
  assert.equal(view.sourceState(crawler), 'degraded')
  crawler.artifactSha256 = 'new-sha'
  assert.equal(view.sourceState(crawler), 'stale')
  assert.equal(view.sourceAttention(crawler), false)
})

function overview() {
  const source = ts.createSourceFile('home.ts', component('App').scriptSetup.content,
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const names = ['sources', 'selectedId', 'selected', 'sourceQuery', 'visibleSources', 'runs', 'activeRunId',
    'activeRun', 'checking', 'baselineReady', 'failedNodes', 'checkSummary', 'statuses',
    'managedConfiguration', 'liveRuntimeAvailable', 'sourceFilter']
  const body = source.statements.filter(node => ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => names.includes(item.name.getText())))
    .map(node => node.getText(source)).join('\n')
  const code = ts.transpileModule(body, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  return vm.runInNewContext(code + `\n;({${names.join(',')}})`, { computed, ref })
}
const run = overrides => ({ id: 'run-A', status: 'COMPLETED', freshness: 'current', executionMode: 'static',
  artifactSha256: 'sha-A', errorCode: null, result: { nodes: [], requiredCoverage: { total: 3, pass: 3 } }, ...overrides })
const liveEvidence = overrides => ({
  cleanup: { confirmed: true, containerAbsent: true }, deniedCodes: [],
  httpObservations: [{ responseStatus: 200, responseBytes: 512, responseComplete: true }], ...overrides
})
function selectRun(view, value) {
  view.runs.value = [value]
  view.activeRunId.value = value.id
}

test('code and fixture passes never claim a live crawler is healthy', () => {
  const view = overview()
  assert.equal(view.checkSummary.value, '尚未检查')
  selectRun(view, run())
  assert.equal(view.checkSummary.value, '仅语法预检通过，未进行联网巡检')
  selectRun(view, run({ executionMode: 'container_fixture' }))
  assert.match(view.checkSummary.value, /真实爬虫尚未验证/)
  selectRun(view, run({ executionMode: 'managed_source' }))
  assert.equal(view.checkSummary.value, '联网执行或清理未确认，结果暂不可采信')
  selectRun(view, run({ executionMode: 'managed_source', result: { nodes: [{ id: 'C03', status: 'pass' }],
    requiredCoverage: { total: 9, pass: 9 }, managedSourceEvidence: liveEvidence() } }))
  assert.equal(view.checkSummary.value, '所选联网环节通过，未覆盖项仍未验证')
  selectRun(view, run({ executionMode: 'managed_source', result: { nodes: [{ id: 'C03', status: 'pass' }],
    managedSourceEvidence: liveEvidence() } }))
  assert.equal(view.checkSummary.value, '联网检查仍有未完成项')
})

test('stale, incomplete and failed reports keep their distinct states', () => {
  const view = overview()
  selectRun(view, run({ freshness: 'source_changed' }))
  assert.match(view.checkSummary.value, /过期/)
  selectRun(view, run({ errorCode: 'RUNNER_FAILED' }))
  assert.equal(view.checkSummary.value, '检查未完成')
  selectRun(view, run({ result: null }))
  assert.equal(view.checkSummary.value, '检查未完成')
  selectRun(view, run({ result: { nodes: [{ status: 'fail' }, { status: 'blocked' }] } }))
  assert.equal(view.checkSummary.value, '语法预检发现 1 项问题，未进行联网巡检')
  selectRun(view, run({ executionMode: 'managed_source', result: {
    nodes: [{ status: 'fail' }, { status: 'blocked' }], managedSourceEvidence: liveEvidence() } }))
  assert.equal(view.checkSummary.value, '联网发现 1 项问题')
  selectRun(view, run({ status: 'CANCELLING' }))
  assert.equal(view.checking.value, true)
  assert.equal(view.checkSummary.value, '正在停止并清理')
})

test('search and baseline readiness stay tied to the selected source version', () => {
  const view = overview()
  view.sources.value = [{ id: 'A', name: 'Example', filename: 'example.py', artifactSha256: 'sha-A' }]
  view.selectedId.value = 'A'
  view.sourceQuery.value = '  EXAMPLE.PY  '
  assert.equal(view.visibleSources.value.length, 1)
  selectRun(view, run())
  assert.equal(view.baselineReady.value, false)
  selectRun(view, run({ executionMode: 'managed_source' }))
  assert.equal(view.baselineReady.value, true)
  view.sources.value[0].artifactSha256 = 'sha-B'
  assert.equal(view.baselineReady.value, false)
  view.sourceQuery.value = 'missing'
  assert.equal(view.visibleSources.value.length, 0)
})

test('the primary inspection action never falls back to static analysis', () => {
  const nodes = elements(component('App').template.ast)
  const overview = nodes.find(({ node }) => attribute(node, 'class') === 'check-overview').node
  const buttons = elements(overview).filter(({ node }) => node.tag === 'el-button')
  const primary = buttons.find(({ node }) => attribute(node, 'type') === 'primary').node
  const click = primary.props.find(prop => prop.type === 7 && prop.arg?.content === 'click')
  const disabled = primary.props.find(prop => prop.type === 7 && prop.arg?.content === 'disabled')
  assert.equal(click.exp.content, "probe('managed_source')")
  assert.match(disabled.exp.content, /liveRuntimeAvailable/)
  assert.doesNotMatch(click.exp.content, /static/)
})

test('automatic channel setup enables the live action without treating setup as health evidence', () => {
  const view = overview()
  assert.equal(view.liveRuntimeAvailable.value, false)
  view.managedConfiguration.value = { blockedReason: 'MANAGED_LOCAL_SOCKET_REQUIRED', setupAvailable: false }
  assert.equal(view.liveRuntimeAvailable.value, false)
  view.managedConfiguration.value.setupAvailable = true
  assert.equal(view.liveRuntimeAvailable.value, true)
  assert.equal(view.checkSummary.value, '尚未检查')
  view.managedConfiguration.value = { blockedReason: '', setupAvailable: false }
  assert.equal(view.liveRuntimeAvailable.value, true)
})

test('source output and HTTP 200 alone cannot certify inspection, and policy denial is not a source defect', () => {
  const view = overview()
  for (const observations of [[], [{ responseStatus: 200, responseBytes: 100 }],
    [{ responseStatus: 200, responseBytes: 100, responseComplete: false }]]) {
    selectRun(view, run({ executionMode: 'managed_source', result: { nodes: [],
      requiredCoverage: { total: 9, pass: 9 },
      managedSourceEvidence: liveEvidence({ httpObservations: observations,
        untrustedRuntimeSummary: { status: 'returned', categoryItems: 100 } }) } }))
    assert.equal(view.checkSummary.value, '未取得源站响应，业务健康尚未验证')
  }
  selectRun(view, run({ executionMode: 'managed_source', result: { nodes: [{ status: 'fail' }],
    managedSourceEvidence: liveEvidence({ deniedCodes: ['EGRESS_URL_DENIED'] }) } }))
  assert.equal(view.checkSummary.value, '联网检查受执行策略限制，不能据此判定爬虫故障')
  selectRun(view, run({ executionMode: 'managed_source', result: { nodes: [{ id: 'C01', status: 'pass' }],
    requiredCoverage: { total: 1, pass: 1 }, managedSourceEvidence: liveEvidence() } }))
  assert.equal(view.checkSummary.value, '已取得联网响应，业务环节尚未验证')
})
