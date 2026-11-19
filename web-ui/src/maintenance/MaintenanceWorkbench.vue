<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, EditPen, Search, Tools, VideoPlay, Upload, Refresh, Timer } from '@element-plus/icons-vue'
import { downloadFile, downloadSource, request, sourceContent } from './api'
import RepairPanel from './RepairPanel.vue'
import ReleasePlanPanel from './ReleasePlanPanel.vue'
import MediaProbePanel from './MediaProbePanel.vue'
import SourceImportPanel from './SourceImportPanel.vue'
import UpstreamPanel from './UpstreamPanel.vue'
import DirectoryImportPanel from './DirectoryImportPanel.vue'
import NasDirectoryPanel from './NasDirectoryPanel.vue'
import ProbeEvidencePanel from './ProbeEvidencePanel.vue'
import BusinessEvidencePanel from './BusinessEvidencePanel.vue'
import PluginInputPanel from './PluginInputPanel.vue'
import AtPluginInputPanel from './AtPluginInputPanel.vue'
import PluginInputEvidence from './PluginInputEvidence.vue'
import ScheduleObservationPanel from './ScheduleObservationPanel.vue'
import ConnectionSettingsPanel from './ConnectionSettingsPanel.vue'
import PublicationQueuePanel from './PublicationQueuePanel.vue'
import MaintenanceWorkflowPanel from './MaintenanceWorkflowPanel.vue'
import ProcessLogPanel from './ProcessLogPanel.vue'
import SourceCredentialsPanel, { type CredentialSummary } from './SourceCredentialsPanel.vue'
import type { PluginInputSummary } from './pluginInput'
import type { EvidenceChange, ManagedEvidence, NodeDefinition } from './evidence'

interface Source {
  id: string; name: string; externalId: string | null; filename: string
  artifactSha256: string; revision: number; profileSha256: string
  credentials?: CredentialSummary
}
interface Artifact { id: string; sha256: string; createdAt: string }
interface SourceImport {
  id: string; archiveSha256: string; originalSourceId: string; createdAt: string; configRebindRequired: boolean
}
interface Rule {
  id: string; title: string; guidance: string; validationSteps: string[]; nodes?: string[]
  citation: { skill: string; path: string; startLine: number; endLine: number; sha256: string; excerpt: string }
}
interface Context { catalogSha256: string; rules: Rule[]; boundary: string
  auxiliaryEvidence?: { extractionReplay?: unknown; browserEvidence?: unknown } }
interface Node { id: string; title: string; status: string; reason: string; requirement: string; evidence?: unknown }
interface ProfileSnapshot {
  runtime: string; runtimeRevision: string; requiredNodes: string[]; optionalNodes: string[]
  extFingerprint: string | null; authority: string
  publicSourceData?: Record<string, string>
  sourceConfig?: Record<string, string | number | boolean>
  paginationMode?: 'exact-total' | 'page-window'
  sourcePurpose?: 'video' | 'metadata-navigation'
  searchKeyword?: string
}
interface Profile { id: string | null; sha256: string; snapshot: ProfileSnapshot; createdAt: string | null }
interface AtConnection { configured: boolean; instanceId: string | null; declaredRevision: string | null }
interface AtPlugin { id: number; name: string; kind: string; derivedSiteKey: string; enabled: boolean; t4Visible: boolean }
interface AtObservation { sha256: string; observedAt: string; plugins: AtPlugin[]; boundary: string }
interface AtBinding { sha256: string; snapshot: unknown; connectionMatches: boolean }
interface SchedulePolicy {
  enabled: boolean; mode: string; intervalSeconds: number; dailyLimit: number
  firstRunAt?: string
  approval?: { approvedRunId: string; sourceRevision: number; artifactSha256: string }
  automaticRepair?: { configurationSha256: string }
}
interface Schedule {
  sha256: string | null; policy: SchedulePolicy; nextDueAt: string | null
  budgetDay: string | null; usedAttempts: number; consecutiveFailures: number
  suspended: boolean; suspensionReason: string | null; activeRunId: string | null
  lastRunId: string | null; lastOutcome: string | null
}
interface Scheduling { enabled: boolean; dailyLimit: number; budgetDay: string; usedAttempts: number }
interface ContainerConfiguration { sha256: string; snapshot: unknown; blockedReason: string; setupAvailable?: boolean; backend?: string }
interface Run {
  id: string; sourceId: string; status: string; artifactSha256: string; createdAt: string; errorCode: string | null
  sourceRevision: number | null
  profileSha256: string | null; profile: ProfileSnapshot | null; freshness: string
  executionMode: string; runtimeSha256: string | null; staleInputs: string[]
  executionPolicySha256: string | null; executionPolicy: unknown
  triggerKind: string; scheduleSha256: string | null; scheduleBudgetDay: string | null; scheduleOrdinal: number | null
  result: { verdict: string; boundary: string; nodes: Node[]; runtimeEvidence?: unknown
    managedSourceEvidence?: ManagedEvidence & { cleanup?: { confirmed?: boolean; containerAbsent?: boolean } }
    requiredCoverage?: { total: number; pass: number; fail: number; blocked: number; notTested: number } } | null
  context: Context | null
}
const sources = ref<Source[]>([])
const selectedId = ref('')
const selected = computed(() => sources.value.find(s => s.id === selectedId.value))
const sourceQuery = ref('')
interface SourceOverview { sourceId: string; runId: string; state: string; checkedAt: string; artifactSha256: string
  maintenanceStatus: string | null; stopCode: string | null; degraded?: boolean; candidateRegressed?: boolean
  repairExhausted?: boolean; failedRepairRounds?: number; regressedNodes?: string[] }
const overview = ref<SourceOverview[]>([])
const sourceFilter = ref('all')
const importDrawer = ref(false)
const inspectionDrawer = ref(false)
const processLog = ref<InstanceType<typeof ProcessLogPanel>>()
const healthLabels: Record<string, string> = { unchecked: '待联网检查', verified: '联网已验证', needs_repair: '需维护',
  blocked: '执行受阻', stale: '待重新检查', running: '执行中', incomplete: '验证未完成', credentials: '待补凭据',
  degraded: '能力退化', repair_exhausted: '三轮未修复', candidate_regressed: '修复候选退化' }
function sourceState(source: Source) {
  const row = overview.value.find(item => item.sourceId === source.id)
  if (row && row.artifactSha256 !== source.artifactSha256) return 'stale'
  if (row?.repairExhausted) return 'repair_exhausted'
  if (row?.degraded) return 'degraded'
  if (row?.candidateRegressed) return 'candidate_regressed'
  if (['missing', 'rejected'].includes(source.credentials?.status ?? '')) return 'credentials'
  return row?.state ?? 'unchecked'
}
function sourceAttention(source: Source) {
  return ['degraded', 'repair_exhausted', 'candidate_regressed', 'needs_repair'].includes(sourceState(source))
}
const visibleSources = computed(() => sources.value.filter(source =>
  `${source.name} ${source.filename}`.toLowerCase().includes(sourceQuery.value.trim().toLowerCase())
  && (sourceFilter.value === 'all' || sourceState(source) === sourceFilter.value)))
const artifacts = ref<Artifact[]>([])
const imports = ref<SourceImport[]>([])
const pluginInputs = ref<PluginInputSummary[]>([])
const runs = ref<Run[]>([])
const activeRunId = ref('')
const activeTab = ref('probe')
const workTab = ref('repair')
const advancedOpen = ref(false)
const activeRun = computed(() => runs.value.find(r => r.id === activeRunId.value))
const checking = computed(() => runs.value.some(run => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status)))
const credentialsBlocked = computed(() => ['missing', 'rejected'].includes(selected.value?.credentials?.status ?? ''))
const baselineReady = computed(() => activeRun.value?.status === 'COMPLETED'
  && activeRun.value.executionMode === 'managed_source'
  && activeRun.value.freshness === 'current' && activeRun.value.artifactSha256 === selected.value?.artifactSha256)
const failedNodes = computed(() => activeRun.value?.result?.nodes.filter(node => node.status === 'fail') ?? [])
const checkSummary = computed(() => {
  const run = activeRun.value
  if (!run) return '尚未检查'
  if (run.freshness !== 'current') return '报告已过期，请重新检查'
  if (run.status !== 'COMPLETED') return statuses[run.status] || run.status
  if (run.errorCode || !run.result) return '检查未完成'
  if (run.executionMode === 'static') return failedNodes.value.length
    ? `语法预检发现 ${failedNodes.value.length} 项问题，未进行联网巡检` : '仅语法预检通过，未进行联网巡检'
  if (run.executionMode !== 'managed_source') return '示例检查完成，真实爬虫尚未验证'
  const evidence = run.result.managedSourceEvidence
  if (evidence?.deniedCodes?.length) return '联网检查受执行策略限制，不能据此判定爬虫故障'
  if (evidence?.cleanup?.confirmed !== true || evidence.cleanup.containerAbsent !== true)
    return '联网执行或清理未确认，结果暂不可采信'
  const responseObserved = evidence.httpObservations?.some(observation => {
    if (!observation || typeof observation !== 'object') return false
    const row = observation as Record<string, unknown>
    return row.responseComplete === true && Number.isInteger(row.responseStatus)
      && (row.responseStatus as number) >= 100 && (row.responseStatus as number) <= 599
      && Number.isInteger(row.responseBytes) && (row.responseBytes as number) >= 0
  })
  if (!responseObserved) return '未取得源站响应，业务健康尚未验证'
  if (failedNodes.value.length) return `联网发现 ${failedNodes.value.length} 项问题`
  if (!run.result.nodes.some(node => node.status === 'pass'
    && ['C03', 'C05', 'C07', 'C09', 'C10', 'R01', 'M01'].includes(node.id)))
    return '已取得联网响应，业务环节尚未验证'
  const coverage = run.result?.requiredCoverage
  return coverage && coverage.total > 0 && coverage.pass === coverage.total
    ? '所选联网环节通过，未覆盖项仍未验证' : '联网检查仍有未完成项'
})
const catalog = ref<Context>()
const busy = ref(false)
const editVisible = ref(false)
const creating = ref(true)
const name = ref('')
const filename = ref('source.py')
const externalId = ref('')
const content = ref('')
const expectedSha = ref('')
const editSourceId = ref('')
const profile = ref<Profile>()
const profileHistory = ref<Profile[]>([])
const atConnection = ref<AtConnection>()
const connectionSettings = ref<InstanceType<typeof ConnectionSettingsPanel>>()
const connectionRevision = ref(0)
const maintenanceRevision = ref(0)
const atObservation = ref<AtObservation>()
const atBinding = ref<AtBinding>()
const atPluginId = ref<number>()
const scheduling = ref<Scheduling>()
const containerConfiguration = ref<ContainerConfiguration>()
const managedConfiguration = ref<ContainerConfiguration>()
const liveRuntimeAvailable = computed(() => !!managedConfiguration.value
  && (!managedConfiguration.value.blockedReason || managedConfiguration.value.setupAvailable === true))
const schedule = ref<Schedule>()
const scheduleRefreshKey = ref(0)
const scheduleEnabled = ref(false)
const scheduleMode = ref('managed_source')
const scheduleHours = ref(6)
const scheduleFirstRunAt = ref<Date | null>(null)
const scheduleDailyLimit = ref(4)
const scheduleApprovalRunId = ref('')
const scheduleApprovalConfirmed = ref(false)
const scheduleAutomaticRepair = ref(false)
const scheduleRepairConfigurationSha = ref('')
const scheduleApprovalOptions = computed(() => runs.value.filter(run =>
  run.triggerKind === 'manual' && run.executionMode === 'managed_source' && run.status === 'COMPLETED'
  && !run.errorCode && run.freshness === 'current' && run.sourceRevision === selected.value?.revision
  && run.artifactSha256 === selected.value?.artifactSha256
  && run.result?.nodes.some(node => node.id === 'S03' && node.status === 'pass')
  && run.result?.managedSourceEvidence?.cleanup?.confirmed === true
  && run.result?.managedSourceEvidence?.cleanup?.containerAbsent === true))
const definitions = ref<NodeDefinition[]>([])
const runtime = ref('t4')
const runtimeRevision = ref('')
const paginationMode = ref<'exact-total' | 'page-window'>('exact-total')
const searchKeyword = ref('')
const nodeModes = ref<Record<string, string>>({})
const extInput = ref('')
const publicDataInput = ref('')
const sourceConfigInput = ref('')
const freshnessLabels: Record<string, string> = {
  source_changed: '这是历史版本的报告，不能用于证明当前源码状态。',
  profile_changed: '能力画像、配置指纹或运行器声明已变化，需要重新探测。',
  source_and_profile_changed: '源码和能力画像均已变化，需要重新探测。',
  legacy_unbound: '旧报告没有绑定能力画像与配置，不能用于证明当前配置状态。',
  runtime_changed: '内置运行时已变化，需要重新探测。',
  execution_policy_changed: '容器执行配置已变化，需要重新探测。',
  binding_changed: 'AT 绑定已变化，需要重新探测。',
  at_connection_changed: '当前 AT 连接与绑定的实例不同或未配置，请核对绑定。',
  credentials_changed: '站点凭据已更新，需要使用新凭据重新联网检查。'
}
const requirementLabels: Record<string, string> = { required: '必需', optional: '可选', undeclared: '未声明' }
const modeLabels: Record<string, string> = { static: '静态', offline_fixture: '离线夹具',
  container_fixture: '容器可信示例', managed_source: '受管源码隔离探测' }
let poll: ReturnType<typeof setTimeout> | undefined
let overviewPoll: ReturnType<typeof setTimeout> | undefined
let disposed = false
let selectionRevision = 0
let inspectionRevision = 0

const statuses: Record<string, string> = {
  pass: '通过', fail: '失败', blocked: '阻塞', not_tested: '未测试',
  QUEUED: '排队中', RUNNING: '分析中', COMPLETED: '分析完成',
  BLOCKED: '执行条件不足', FAILED: '内部失败', INTERRUPTED: '执行中断', REJECTED: '队列已满',
  CANCELLING: '正在停止并清理', CANCELLED: '已取消'
}
const runContext = computed(() => activeRun.value?.context ?? catalog.value)
function evidenceChanged(change: EvidenceChange) {
  const run = activeRun.value
  if (change.kind === 'selectorProposal' || change.sourceId !== selectedId.value
    || change.runId !== run?.id || change.artifactSha256 !== run.artifactSha256 || !run.context) return
  run.context = { ...run.context,
    auxiliaryEvidence: { ...run.context.auxiliaryEvidence, [change.kind]: change.evidence } }
  if (poll) clearTimeout(poll)
  schedulePoll(selectedId.value, ++selectionRevision)
}
function advancedToggled(event: Event) {
  advancedOpen.value = (event.currentTarget as HTMLDetailsElement).open
}

async function action(work: () => Promise<void>) {
  busy.value = true
  try { await work() } catch (e) { ElMessage.error(e instanceof Error ? e.message : '操作失败') }
  finally { busy.value = false }
}
async function loadSources() {
  sources.value = await request<Source[]>('/sources')
  await refreshOverview()
}
async function refreshOverview() {
  if (overviewPoll) clearTimeout(overviewPoll)
  try {
    const result = await request<SourceOverview[]>('/sources/overview')
    if (!disposed) overview.value = result
  } catch (error) {
    if (!overview.value.length) console.warn('爬虫状态暂未更新')
  } finally {
    if (!disposed) overviewPoll = setTimeout(refreshOverview, 8000)
  }
}
async function credentialsChanged() {
  const id = selectedId.value
  await loadSources()
  if (!disposed && selectedId.value === id) await selectSource(id)
}
async function connectionsChanged(section: string) {
  if (section === 'at') atConnection.value = await request<AtConnection>('/at/connection')
  connectionRevision.value++
}
async function importedSource(id: string) {
  importDrawer.value = false
  await action(async () => { await loadSources(); await selectSource(id) })
}
async function localSourceChanged(id: string) {
  const revision = selectionRevision
  await action(async () => {
    const items = await request<Source[]>('/sources')
    if (disposed || revision !== selectionRevision || selectedId.value !== id) return
    sources.value = items
    await selectSource(id)
  })
}
async function selectSource(id: string) {
  const revision = ++selectionRevision
  if (poll) clearTimeout(poll)
  selectedId.value = id
  activeRunId.value = ''
  workTab.value = 'repair'
  advancedOpen.value = false
  runs.value = []
  artifacts.value = []
  imports.value = []
  pluginInputs.value = []
  profile.value = undefined
  managedConfiguration.value = undefined
  extInput.value = ''
  publicDataInput.value = ''
  sourceConfigInput.value = ''
  atBinding.value = undefined
  schedule.value = undefined
  scheduleApprovalRunId.value = ''
  scheduleApprovalConfirmed.value = false
  const [versions, recent, currentProfile, history, binding, currentSchedule, globalSchedule, importedArchives, inputs, selectedRuntime] = await Promise.all([
    request<Artifact[]>(`/sources/${id}/artifacts`),
    request<Run[]>(`/sources/${id}/probe-runs`),
    request<Profile>(`/sources/${id}/profile`),
    request<Profile[]>(`/sources/${id}/profiles`),
    request<AtBinding | undefined>(`/sources/${id}/at-binding`),
    request<Schedule>(`/sources/${id}/schedule`),
    request<Scheduling>('/scheduling'),
    request<SourceImport[]>(`/sources/${id}/imports`),
    request<PluginInputSummary[]>(`/sources/${id}/plugin-inputs`),
    request<ContainerConfiguration>(`/managed-source-runtime?sourceId=${encodeURIComponent(id)}`)
  ])
  if (revision !== selectionRevision || id !== selectedId.value || disposed) return
  artifacts.value = versions
  imports.value = importedArchives
  pluginInputs.value = inputs
  managedConfiguration.value = selectedRuntime
  runs.value = recent
  profile.value = currentProfile
  profileHistory.value = history
  atBinding.value = binding
  schedule.value = currentSchedule
  scheduleRefreshKey.value++
  scheduling.value = globalSchedule
  scheduleEnabled.value = currentSchedule.policy.enabled
  scheduleMode.value = 'managed_source'
  scheduleHours.value = currentSchedule.policy.intervalSeconds / 3600
  scheduleFirstRunAt.value = currentSchedule.policy.firstRunAt ? new Date(currentSchedule.policy.firstRunAt) : null
  scheduleDailyLimit.value = currentSchedule.policy.dailyLimit
  scheduleApprovalRunId.value = currentSchedule.policy.approval?.approvedRunId ?? ''
  scheduleAutomaticRepair.value = !!currentSchedule.policy.automaticRepair
  scheduleRepairConfigurationSha.value = currentSchedule.policy.automaticRepair?.configurationSha256 ?? ''
  if (inspectionDrawer.value) openInspection()
  runtime.value = currentProfile.snapshot.runtime
  runtimeRevision.value = currentProfile.snapshot.runtimeRevision
  paginationMode.value = currentProfile.snapshot.paginationMode ?? 'exact-total'
  searchKeyword.value = currentProfile.snapshot.searchKeyword ?? ''
  nodeModes.value = Object.fromEntries(definitions.value.map(node => [node.id,
    currentProfile.snapshot.requiredNodes.includes(node.id) ? 'required'
      : currentProfile.snapshot.optionalNodes.includes(node.id) ? 'optional' : 'undeclared']))
  activeRunId.value = recent[0]?.id ?? ''
  schedulePoll(id, revision)
}
function schedulePoll(id: string, revision: number) {
  if (disposed || revision !== selectionRevision || id !== selectedId.value) return
  if (runs.value.some(r => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(r.status))) {
    poll = setTimeout(async () => {
      try {
        const recent = await request<Run[]>(`/sources/${id}/probe-runs`)
        if (revision === selectionRevision && id === selectedId.value && !disposed) {
          if (recent.some(run => runs.value.find(before => before.id === run.id)?.status !== run.status))
            scheduleRefreshKey.value++
          const opened = runs.value.find(run => run.id === activeRunId.value)
          if (!recent.some(run => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status))) await loadSources()
          runs.value = opened && !recent.some(run => run.id === opened.id) ? [...recent, opened] : recent
          schedulePoll(id, revision)
        }
      } catch (e) {
        if (revision === selectionRevision && id === selectedId.value && !disposed)
          ElMessage.error(e instanceof Error ? e.message : '状态读取失败')
      }
    }, 1000)
  }
}
function newSource() {
  creating.value = true
  name.value = ''; externalId.value = ''; filename.value = 'source.py'; content.value = ''
  editVisible.value = true
}
async function editSource() {
  const source = selected.value
  if (!source) return
  await action(async () => {
    creating.value = false
    editSourceId.value = source.id
    expectedSha.value = source.artifactSha256
    content.value = await sourceContent(source.id, expectedSha.value)
    filename.value = source.filename
    editVisible.value = true
  })
}
async function readFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  await action(async () => {
    if (file.size > 1_048_576) throw new Error('源码限制为 1 MiB')
    content.value = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer())
    if (creating.value) {
      filename.value = file.name
      if (!name.value) name.value = file.name.replace(/\.py$/, '')
    }
  })
}
async function saveSource() {
  await action(async () => {
    const source = creating.value
      ? await request<Source>('/sources', {
        name: name.value, filename: filename.value, externalId: externalId.value || null, content: content.value
      })
      : await request<Source>(`/sources/${editSourceId.value}/artifacts`, {
        expectedSha256: expectedSha.value, content: content.value
      })
    editVisible.value = false
    await loadSources()
    await selectSource(source.id)
    ElMessage.success('源码版本已保存')
  })
}
async function probe(mode = 'static') {
  const id = selectedId.value
  await action(async () => {
    const run = await request<Run>(`/sources/${id}/probe-runs`, { mode })
    await selectSource(id)
    activeRunId.value = run.id
    if (mode === 'managed_source') processLog.value?.show(run.id)
  })
}
async function maintenanceChanged(task: { sourceId: string; runId: string; status?: string }) {
  const revision = selectionRevision
  if (task.sourceId !== selectedId.value) return
  try {
    const [recent, selectedRuntime] = await Promise.all([
      request<Run[]>(`/sources/${task.sourceId}/probe-runs`),
      request<ContainerConfiguration>(`/managed-source-runtime?sourceId=${encodeURIComponent(task.sourceId)}`)
    ])
    if (disposed || revision !== selectionRevision || task.sourceId !== selectedId.value) return
    runs.value = recent
    managedConfiguration.value = selectedRuntime
    activeRunId.value = task.runId
    if (['CHECKING', 'REPAIRING'].includes(task.status ?? '')) processLog.value?.show(task.runId)
    void refreshOverview()
    maintenanceRevision.value++
    if (poll) clearTimeout(poll)
    schedulePoll(task.sourceId, revision)
  } catch (e) {
    if (!disposed && revision === selectionRevision)
      ElMessage.error(e instanceof Error ? e.message : '巡检报告读取失败')
  }
}
async function addFixture() {
  await action(async () => {
    const fixture = await request<{ name: string; filename: string; content: string }>('/fixtures/demo')
    const source = await request<Source>('/sources', fixture)
    await loadSources()
    await selectSource(source.id)
  })
}
async function addRepairFixture() {
  await action(async () => {
    const fixture = await request<{ name: string; filename: string; content: string }>('/fixtures/repair')
    const source = await request<Source>('/sources', {
      name: fixture.name, filename: fixture.filename, content: fixture.content
    })
    const run = await request<Run>(`/sources/${source.id}/probe-runs`, { mode: 'static' })
    await loadSources()
    await selectSource(source.id)
    activeRunId.value = run.id
  })
}
async function cancelProbe() {
  const run = activeRun.value
  const id = selectedId.value
  if (!run) return
  await action(async () => {
    await request<Run>(`/probe-runs/${run.id}/cancel`, {})
    await selectSource(id)
    activeRunId.value = run.id
  })
}
async function observeAt() {
  await action(async () => {
    atObservation.value = await request<AtObservation>('/at/observations', {})
    atPluginId.value = undefined
  })
}
async function bindAt() {
  const id = selectedId.value
  if (!atObservation.value || !atPluginId.value) return
  await action(async () => {
    await request(`/sources/${id}/at-binding`, { expectedSha256: atBinding.value?.sha256 ?? null,
      observationSha256: atObservation.value!.sha256, pluginId: atPluginId.value })
    await selectSource(id)
    ElMessage.success('绑定已保存；请重新探测以冻结新绑定')
  })
}
async function unbindAt() {
  const id = selectedId.value
  if (!atBinding.value) return
  await action(async () => {
    await request(`/sources/${id}/at-binding/unbind`, { expectedSha256: atBinding.value!.sha256 })
    await selectSource(id)
  })
}
async function observeRuntimeIdentity() {
  const id = selectedId.value
  if (!atBinding.value) return
  await action(async () => {
    await request(`/sources/${id}/at-binding/runtime-identity`,
      { expectedSha256: atBinding.value!.sha256 })
    await selectSource(id)
    ElMessage.success('已保存只读 runtime identity 回执；旧报告需要重新探测')
  })
}
async function saveSchedule() {
  const id = selectedId.value
  const revision = selectionRevision
  const baseline = schedule.value
  if (!baseline) return
  await action(async () => {
    let approval = null
    let automaticRepair = null
    if (scheduleMode.value === 'managed_source') {
      if (scheduleApprovalConfirmed.value) {
        const run = scheduleApprovalOptions.value.find(run => run.id === scheduleApprovalRunId.value)
        if (!run || run.sourceRevision == null) throw new Error('请选择当前源码的手动受管探测回执')
        approval = { approvedRunId: run.id, expectedSourceRevision: run.sourceRevision }
      } else if (!baseline.policy.approval || baseline.policy.approval.approvedRunId !== scheduleApprovalRunId.value) {
        throw new Error('请明确批准所选回执对应的自动隔离巡检')
      }
      if (scheduleAutomaticRepair.value) {
        let configuration = scheduleRepairConfigurationSha.value
        if (!configuration) {
          const connection = await request<{ enabled: boolean; autoSubmitEnabled: boolean; configurationSha256: string }>('/model/connection')
          if (disposed || revision !== selectionRevision || id !== selectedId.value) return
          if (!connection.enabled || !connection.autoSubmitEnabled) throw new Error('请先配置并启用模型自动修复')
          const consent = await request<{ artifactSha256: string | null }>(`/sources/${id}/model-consent`)
          if (disposed || revision !== selectionRevision || id !== selectedId.value) return
          if (consent.artifactSha256 !== selected.value?.artifactSha256) throw new Error('请先在自动维护中授权当前版本源码')
          configuration = connection.configurationSha256
        }
        automaticRepair = { expectedConfigurationSha256: configuration }
      }
    }
    const saved = await request<Schedule>(`/sources/${id}/schedule`, {
      expectedSha256: baseline.sha256, enabled: scheduleEnabled.value, mode: scheduleMode.value,
      intervalSeconds: Math.round(scheduleHours.value * 3600), dailyLimit: scheduleDailyLimit.value, approval,
      automaticRepair, firstRunAt: scheduleFirstRunAt.value?.toISOString() ?? null })
    if (disposed || revision !== selectionRevision || id !== selectedId.value) return
    schedule.value = saved
    scheduleRefreshKey.value++
    scheduleApprovalRunId.value = saved.policy.approval?.approvedRunId ?? ''
    scheduleApprovalConfirmed.value = false
    scheduleAutomaticRepair.value = !!saved.policy.automaticRepair
    scheduleRepairConfigurationSha.value = saved.policy.automaticRepair?.configurationSha256 ?? ''
    ElMessage.success(saved.policy.enabled ? '智能巡检计划已保存' : '智能巡检已关闭')
  })
}
function openInspection() {
  inspectionDrawer.value = true
  if (!schedule.value?.policy.approval && scheduleApprovalOptions.value[0]) {
    scheduleApprovalRunId.value = scheduleApprovalOptions.value[0].id
    scheduleApprovalConfirmed.value = true
  }
}
async function resumeSchedule() {
  const id = selectedId.value
  const revision = selectionRevision
  const baseline = schedule.value
  if (!baseline?.sha256) return
  await action(async () => {
    const saved = await request<Schedule>(`/sources/${id}/schedule/resume`, { expectedSha256: baseline.sha256 })
    if (disposed || revision !== selectionRevision || id !== selectedId.value) return
    schedule.value = saved
    scheduleRefreshKey.value++
  })
}
async function inspectScheduledRun(runId: string) {
  const sourceId = selectedId.value
  const revision = selectionRevision
  const inspection = ++inspectionRevision
  const previousRunId = activeRunId.value
  await action(async () => {
    const run = await request<Run>(`/probe-runs/${runId}`)
    if (disposed || revision !== selectionRevision || sourceId !== selectedId.value
      || inspection !== inspectionRevision || previousRunId !== activeRunId.value) return
    if (run.id !== runId || run.sourceId !== sourceId) throw new Error('巡检报告归属不匹配')
    runs.value = [run, ...runs.value.filter(item => item.id !== runId)]
    activeRunId.value = runId
    activeTab.value = 'probe'
    advancedOpen.value = true
  })
}
async function saveProfile() {
  const id = selectedId.value
  const revision = selectionRevision
  const baseline = profile.value
  if (!baseline) return
  await action(async () => {
    const ext = extInput.value.trim() ? JSON.parse(extInput.value) : null
    const publicSourceData = publicDataInput.value.trim() ? JSON.parse(publicDataInput.value) : undefined
    const sourceConfig = sourceConfigInput.value.trim() ? JSON.parse(sourceConfigInput.value) : undefined
    await request<Profile>(`/sources/${id}/profiles`, {
      expectedSha256: baseline.sha256, runtime: runtime.value, runtimeRevision: runtimeRevision.value,
      requiredNodes: Object.keys(nodeModes.value).filter(key => nodeModes.value[key] === 'required'),
      optionalNodes: Object.keys(nodeModes.value).filter(key => nodeModes.value[key] === 'optional'), ext,
      publicSourceData, sourceConfig, paginationMode: paginationMode.value,
      sourcePurpose: baseline.snapshot.sourcePurpose ?? 'video', searchKeyword: searchKeyword.value
    })
    if (disposed || revision !== selectionRevision || id !== selectedId.value) return
    extInput.value = ''
    publicDataInput.value = ''
    sourceConfigInput.value = ''
    const items = await request<Source[]>('/sources')
    if (disposed || revision !== selectionRevision || id !== selectedId.value) return
    sources.value = items
    await selectSource(id)
    ElMessage.success('画像版本已保存，请按新声明重新探测')
  })
}
function playbackNode(id: string) {
  return /^(C1[01]|R0[1-8]|M(?:0[1-9]|10)|P0[1-5])$/.test(id)
}
async function setSourcePurpose(purpose: 'video' | 'metadata-navigation') {
  const baseline = profile.value
  const id = selectedId.value
  const revision = selectionRevision
  if (!baseline || purpose === (baseline.snapshot.sourcePurpose ?? 'video')) return
  await action(async () => {
    const snapshot = baseline.snapshot
    const required = new Set(baseline.id ? snapshot.requiredNodes : ['S01', 'S02', 'S03', 'S06', 'C01', 'C03', 'C07'])
    const optional = new Set(baseline.id ? snapshot.optionalNodes : [])
    if (purpose === 'metadata-navigation') {
      for (const node of [...required, ...optional]) if (playbackNode(node)) { required.delete(node); optional.delete(node) }
      for (const node of ['S06', 'C07']) { required.add(node); optional.delete(node) }
      if (!required.has('C03') && !required.has('C05')) { required.add('C03'); optional.delete('C03') }
    } else {
      for (const node of ['S06', 'C10', 'R01']) { required.add(node); optional.delete(node) }
    }
    await request<Profile>(`/sources/${id}/profiles`, {
      expectedSha256: baseline.sha256, runtime: snapshot.runtime, runtimeRevision: snapshot.runtimeRevision,
      requiredNodes: [...required], optionalNodes: [...optional], sourcePurpose: purpose,
      paginationMode: snapshot.paginationMode ?? 'page-window'
    })
    if (disposed || revision !== selectionRevision || id !== selectedId.value) return
    await loadSources()
    await selectSource(id)
    ElMessage.success('来源用途已保存')
  })
}
onMounted(() => action(async () => {
  await loadSources()
  catalog.value = await request<Context>('/knowledge')
  atConnection.value = await request<AtConnection>('/at/connection')
  scheduling.value = await request<Scheduling>('/scheduling')
  containerConfiguration.value = await request<ContainerConfiguration>('/container-runtime')
  managedConfiguration.value = await request<ContainerConfiguration>('/managed-source-runtime')
  definitions.value = (await request<{ nodes: NodeDefinition[] }>('/capability-nodes')).nodes
  if (sources.value[0]) await selectSource(sources.value[0].id)
}))
onUnmounted(() => { disposed = true; if (poll) clearTimeout(poll); if (overviewPoll) clearTimeout(overviewPoll) })
</script>

<template>
  <div class="maintenance-workbench">
    <header>
      <div class="brand"><el-icon :size="26"><Tools /></el-icon>
        <div><p class="eyebrow">AList-TVBox</p><h1>爬虫维护</h1></div>
      </div>
      <div class="header-actions">
        <el-button :icon="Upload" @click="importDrawer = true">导入爬虫</el-button>
        <PublicationQueuePanel />
        <ConnectionSettingsPanel :key="selectedId" ref="connectionSettings" :source-id="selectedId"
                                 :disabled="busy" @changed="connectionsChanged" />
      </div>
    </header>
    <div class="workspace">
      <aside class="crawler-catalog">
        <div class="source-heading"><h2>爬虫列表</h2><span>{{ visibleSources.length }} / {{ sources.length }}</span>
          <el-tooltip content="刷新爬虫状态"><el-button :icon="Refresh" :disabled="busy" aria-label="刷新爬虫状态" @click="action(loadSources)" /></el-tooltip>
        </div>
        <el-button class="smart-inspection" :icon="Timer" :disabled="busy || !selected" @click="openInspection">智能巡检</el-button>
        <div class="catalog-filters">
        <el-input v-model="sourceQuery" :prefix-icon="Search" clearable
                  placeholder="搜索爬虫" aria-label="搜索爬虫" class="source-search" />
        <el-select v-model="sourceFilter" aria-label="按健康状态筛选">
          <el-option value="all" label="全部状态" />
          <el-option v-for="(label, state) in healthLabels" :key="state" :value="state" :label="label" />
        </el-select>
        </div>
        <div class="catalog-columns"><span>爬虫 / 文件</span><span>版本</span><span>联网状态</span></div>
        <div class="source-list">
          <el-empty v-if="!sources.length" description="暂无爬虫" :image-size="64" />
          <p v-else-if="!visibleSources.length" class="boundary">没有匹配的爬虫</p>
          <button v-for="source in visibleSources" :key="source.id" class="source"
                  :aria-pressed="source.id === selectedId" :disabled="busy"
                  :class="{ active: source.id === selectedId, 'source-attention': sourceAttention(source) }" @click="action(() => selectSource(source.id))">
            <span class="source-identity"><strong>{{ source.name }}</strong><small>{{ source.filename }}</small></span>
            <span class="source-version">{{ source.revision }}</span>
            <span class="source-health" :class="sourceState(source)">{{ healthLabels[sourceState(source)] }}</span>
          </button>
        </div>
      </aside>
      <section v-if="selected" class="maintenance">
        <div class="toolbar">
          <div class="source-title"><h2>{{ selected.name }}</h2><span>{{ selected.filename }} · 版本 {{ selected.revision }}</span></div>
          <div class="actions">
            <el-select :model-value="profile?.snapshot.sourcePurpose ?? 'video'" aria-label="来源用途"
                       style="width: 220px" :disabled="busy || checking || !profile" @change="setSourcePurpose">
              <el-option value="video" label="影片 / 磁力资源" />
              <el-option value="metadata-navigation" label="资料导航（列表 / 搜索 / 详情）" />
            </el-select>
            <el-tooltip content="编辑源码"><el-button :disabled="busy" :icon="EditPen" aria-label="编辑源码" @click="editSource" /></el-tooltip>
            <el-tooltip content="下载源码包">
              <el-button :disabled="busy" :icon="Download" aria-label="下载源码包"
                         @click="action(() => downloadSource(selected!.id, selected!.artifactSha256))" />
            </el-tooltip>
          </div>
        </div>
        <SourceCredentialsPanel :key="selected.id" :source-id="selected.id" :summary="selected.credentials"
                                :disabled="busy || checking" @changed="action(credentialsChanged)" />
        <div class="check-overview" aria-label="检查问题">
          <div class="check-heading">
            <h3><span class="step-number">1</span> 检查问题</h3>
            <div class="actions">
              <el-button :disabled="busy || checking || credentialsBlocked || !liveRuntimeAvailable"
                         :icon="Search" type="primary" @click="probe('managed_source')">
                联网巡检
              </el-button>
              <el-button v-if="checking && activeRun && ['QUEUED', 'RUNNING'].includes(activeRun.status)"
                         :disabled="busy" @click="cancelProbe">停止检查</el-button>
            </div>
          </div>
          <p class="check-result" role="status">{{ checkSummary }}</p>
          <p v-if="activeRun" class="check-meta">
            {{ modeLabels[activeRun.executionMode] || activeRun.executionMode }} · {{ new Date(activeRun.createdAt).toLocaleString() }}
          </p>
          <ul v-if="failedNodes.length" class="issue-list">
            <li v-for="node in failedNodes.slice(0, 3)" :key="node.id">{{ node.title }}：{{ node.reason }}</li>
          </ul>
          <p v-if="!liveRuntimeAvailable" class="boundary">{{ managedConfiguration?.backend === 'same_host_t4'
            ? 'AT/T4 内部运行环境暂不可用。' : '联网运行环境尚未连接。' }}</p>
          <MaintenanceWorkflowPanel :key="`${selected.id}:${selected.artifactSha256}:${connectionRevision}`"
                                    :source-id="selected.id" :artifact-sha256="selected.artifactSha256"
                                    :disabled="busy || checking || credentialsBlocked"
                                    :runtime-enabled="liveRuntimeAvailable"
                                    @changed="maintenanceChanged" @configure="connectionSettings?.show('model')" />
        </div>
        <ProcessLogPanel :key="selected.id" ref="processLog" :source-id="selected.id" :run-id="activeRunId"
                         @refreshed="refreshOverview" />
        <el-tabs v-model="workTab" class="work-tabs">
          <el-tab-pane label="2 大模型修复" name="repair">
            <RepairPanel :key="`${selected.id}:${connectionRevision}:${maintenanceRevision}`" :source-id="selected.id" :artifact-sha256="selected.artifactSha256"
                         :baseline-run-id="activeRun?.id || ''" :baseline-ready="baselineReady"
                         :runtime-enabled="!!managedConfiguration && !managedConfiguration.blockedReason"
                         @evidence-changed="evidenceChanged" @configure-model="connectionSettings?.show('model')"
                         @ready-to-adopt="workTab = 'release'" @activity-started="processLog?.show(activeRunId)" />
          </el-tab-pane>
          <el-tab-pane label="3 验证与采用" name="release" lazy>
            <ReleasePlanPanel v-if="workTab === 'release'" :key="selected.id" :source-id="selected.id"
                              :artifact-sha256="selected.artifactSha256" @source-changed="localSourceChanged" />
          </el-tab-pane>
        </el-tabs>
        <details class="advanced-workspace" :open="advancedOpen" @toggle="advancedToggled">
          <summary>高级设置与诊断</summary>
          <div v-if="advancedOpen" class="advanced-content">
            <div class="actions diagnostic-actions">
              <el-button :disabled="busy" @click="probe('static')">语法预检</el-button>
              <el-button :disabled="busy" @click="probe('offline_fixture')">离线夹具探测</el-button>
              <el-button :disabled="busy" @click="probe('container_fixture')">容器可信示例</el-button>
              <el-tooltip :content="managedConfiguration?.blockedReason || '执行受管源码隔离探测'">
                <el-button :disabled="busy || checking" :icon="VideoPlay" @click="probe('managed_source')">隔离联网探测</el-button>
              </el-tooltip>
            </div>
            <details v-if="pluginInputs.length"><summary>导入记录与源码身份</summary>
              <PluginInputEvidence v-for="input in pluginInputs" :key="input.id!"
                                   :input="input" :current-sha256="selected.artifactSha256" />
            </details>
            <el-tabs v-model="activeTab">
          <el-tab-pane label="探测节点" name="probe">
            <div class="run-controls">
              <el-select v-model="activeRunId" placeholder="选择最近 30 次探测" style="width: 340px">
                <el-option v-for="run in runs" :key="run.id" :value="run.id"
                           :label="`${modeLabels[run.executionMode] || run.executionMode} · ${statuses[run.status] || run.status} · ${new Date(run.createdAt).toLocaleString()}`" />
              </el-select>
              <el-button v-if="activeRun && ['QUEUED', 'RUNNING', 'CANCELLING'].includes(activeRun.status)"
                         :disabled="busy || activeRun.status === 'CANCELLING'" @click="cancelProbe">
                {{ activeRun.status === 'CANCELLING' ? '正在清理' : '取消探测' }}
              </el-button>
            </div>
            <details v-if="containerConfiguration"><summary>当前容器通道预期配置（未调用 Engine）</summary>
              <p>{{ containerConfiguration.blockedReason || '配置形式符合要求，实际运行尚需验收' }}</p>
              <pre>{{ JSON.stringify(containerConfiguration.snapshot, null, 2) }}</pre>
            </details>
            <details v-if="managedConfiguration"><summary>受管执行器预期配置</summary>
              <p>{{ managedConfiguration.blockedReason || '待任务核对执行器身份与控制值' }}</p>
              <pre>{{ JSON.stringify(managedConfiguration.snapshot, null, 2) }}</pre>
            </details>
            <el-empty v-if="!activeRun" description="尚未探测" />
            <template v-else>
              <el-alert v-if="activeRun.freshness !== 'current'"
                        :title="freshnessLabels[activeRun.freshness] || '报告绑定已变化，需要重新探测。'"
                        type="warning" :closable="false" />
              <p class="boundary">{{ activeRun.result?.boundary || statuses[activeRun.status] }}</p>
              <p v-if="activeRun.staleInputs?.length" class="boundary">已变化的输入：{{ activeRun.staleInputs.join('、') }}</p>
              <p v-if="activeRun.runtimeSha256" class="sha">冻结运行时 {{ activeRun.runtimeSha256 }}</p>
              <p v-if="activeRun.executionPolicySha256" class="sha">冻结执行配置 {{ activeRun.executionPolicySha256 }}</p>
              <p class="boundary">{{ activeRun.triggerKind === 'scheduled' ? '自动巡检' : '手动探测' }}
                <span v-if="activeRun.scheduleSha256"> · 预算日 {{ activeRun.scheduleBudgetDay }} 第 {{ activeRun.scheduleOrdinal }} 次</span>
              </p>
              <p v-if="activeRun.result?.requiredCoverage" class="boundary">
                本次声明的必需项 {{ activeRun.result.requiredCoverage.total }}：
                通过 {{ activeRun.result.requiredCoverage.pass }}，
                失败 {{ activeRun.result.requiredCoverage.fail }}，
                阻塞 {{ activeRun.result.requiredCoverage.blocked }}，
                未测 {{ activeRun.result.requiredCoverage.notTested }}。静态或离线夹具通过不代表真实源可用。
              </p>
              <details v-if="activeRun.profile"><summary>本次冻结的画像与配置指纹</summary>
                <pre>{{ JSON.stringify(activeRun.profile, null, 2) }}</pre>
              </details>
              <details v-if="activeRun.executionPolicy"><summary>本次冻结的容器预期配置</summary>
                <pre>{{ JSON.stringify(activeRun.executionPolicy, null, 2) }}</pre>
              </details>
              <details v-if="activeRun.result?.runtimeEvidence"><summary>可信示例装载回执与调用步骤</summary>
                <pre>{{ JSON.stringify(activeRun.result.runtimeEvidence, null, 2) }}</pre>
              </details>
              <el-alert v-if="activeRun.errorCode && activeRun.status !== 'CANCELLED'"
                        :title="`${activeRun.errorCode}：请检查运行配置与服务日志`"
                        type="error" :closable="false" />
              <ProbeEvidencePanel :run="activeRun" :definitions="definitions" :catalog="catalog" />
              <el-button @click="workTab = 'repair'; advancedOpen = false">返回大模型修复</el-button>
            </template>
          </el-tab-pane>
          <el-tab-pane label="AT 绑定">
            <p class="boundary">管理 API 绑定只读取插件元数据，不刷新 AT 插件，也不执行爬虫。配置专用 runtime identity 路径后，才能额外读取实际 siteKey、源码 SHA 和装载状态；未取得回执时不能证明运行时已生效。</p>
            <el-button v-if="!atConnection?.configured" @click="connectionSettings?.show('at')">配置外置 AT 连接</el-button>
            <p v-else>实例：{{ atConnection.instanceId }} · 版本声明：{{ atConnection.declaredRevision }}</p>
            <el-button :disabled="busy || !atConnection?.configured" @click="observeAt">读取 AT 插件</el-button>
            <template v-if="atObservation">
              <p class="boundary">观察时间：{{ new Date(atObservation.observedAt).toLocaleString() }}。30 秒内复用观察，绑定需使用 5 分钟内的结果。</p>
              <el-select v-model="atPluginId" placeholder="选择 AT 插件" style="width: 100%">
                <el-option v-for="plugin in atObservation.plugins" :key="plugin.id" :value="plugin.id"
                           :disabled="!['python', 'secspider'].includes(plugin.kind)"
                           :label="`${plugin.name} · ID ${plugin.id} · ${plugin.derivedSiteKey} · ${plugin.kind}${plugin.enabled ? '' : ' · 已禁用'}${plugin.t4Visible ? '' : ' · T4 隐藏'}`" />
              </el-select>
              <el-button :disabled="busy || !atPluginId" @click="bindAt">保存绑定</el-button>
            </template>
            <template v-if="atBinding">
              <el-alert v-if="!atBinding.connectionMatches" title="绑定实例与当前连接不匹配，请重新观察并核对。" type="warning" :closable="false" />
              <p class="sha">绑定 {{ atBinding.sha256 }}</p>
              <pre>{{ JSON.stringify(atBinding.snapshot, null, 2) }}</pre>
              <el-button :disabled="busy || !atConnection?.configured" @click="observeRuntimeIdentity">读取 runtime identity 回执</el-button>
              <el-button :disabled="busy" @click="unbindAt">解除本地绑定</el-button>
            </template>
          </el-tab-pane>
          <el-tab-pane label="能力与配置">
            <template v-if="profile">
              <p class="boundary">这里保存能力和运行器声明，尚未核验实际运行环境。S01–S03 是固定静态检查，不能取消。</p>
              <p class="sha">画像 {{ profile.sha256 }}</p>
              <el-form label-position="top">
                <el-form-item label="目标运行器">
                  <el-select v-model="runtime">
                    <el-option v-for="value in ['t4', 'atvp', 'fongmi']" :key="value" :value="value" :label="value" />
                  </el-select>
                </el-form-item>
                <el-form-item label="运行器版本声明（公开版本号或提交 SHA）">
                  <el-input v-model="runtimeRevision" maxlength="200" />
                </el-form-item>
                <el-form-item label="分类分页契约">
                  <el-select v-model="paginationMode">
                    <el-option value="exact-total" label="精确总数与末页" />
                    <el-option value="page-window" label="前两页翻页窗口" />
                  </el-select>
                </el-form-item>
                <el-form-item label="搜索样本词（留空使用列表首条片名）">
                  <el-input v-model="searchKeyword" maxlength="200" />
                </el-form-item>
                <el-form-item label="EXT JSON（留空保留指纹，输入 {} 表示空配置；只保存指纹）">
                  <el-input v-model="extInput" type="textarea" :rows="4" autocomplete="off" spellcheck="false" />
                </el-form-item>
                <p class="sha">EXT 指纹：{{ profile.snapshot.extFingerprint || '尚未提供' }}</p>
                <p class="boundary">EXT 正文只用于本次指纹计算，不保存或回显；运行测试仍需另行提供配置。</p>
                <el-form-item label="公开源码身份 JSON">
                  <el-input v-model="publicDataInput" type="textarea" :rows="3" autocomplete="off" spellcheck="false" />
                </el-form-item>
                <pre v-if="profile.snapshot.publicSourceData">{{ JSON.stringify(profile.snapshot.publicSourceData, null, 2) }}</pre>
                <el-form-item label="运行配置 JSON">
                  <el-input v-model="sourceConfigInput" type="textarea" :rows="3" autocomplete="off" spellcheck="false" />
                </el-form-item>
                <pre v-if="profile.snapshot.sourceConfig">{{ JSON.stringify(profile.snapshot.sourceConfig, null, 2) }}</pre>
                <el-button type="primary" :disabled="busy" @click="saveProfile">保存能力画像</el-button>
              </el-form>
              <el-table :data="definitions" stripe>
                <el-table-column prop="id" label="节点" width="80" />
                <el-table-column prop="title" label="能力" min-width="180" />
                <el-table-column label="要求" width="160">
                  <template #default="{ row }">
                    <span v-if="profile.snapshot.sourcePurpose === 'metadata-navigation' && playbackNode(row.id)">不适用</span>
                    <el-select v-else v-model="nodeModes[row.id]" :disabled="['S01', 'S02', 'S03'].includes(row.id)">
                      <el-option v-for="(label, value) in requirementLabels" :key="value" :value="value" :label="label" />
                    </el-select>
                  </template>
                </el-table-column>
              </el-table>
              <details><summary>画像版本记录（{{ profileHistory.length }}）</summary>
                <p v-for="item in profileHistory" :key="item.sha256" class="sha">
                  {{ item.createdAt || '默认声明' }} · {{ item.sha256 }}
                </p>
              </details>
            </template>
          </el-tab-pane>
          <el-tab-pane label="源码版本">
            <el-table :data="artifacts">
              <el-table-column label="SHA-256" min-width="220">
                <template #default="{ row }"><code>{{ row.sha256 }}</code></template>
              </el-table-column>
              <el-table-column prop="createdAt" label="创建时间" width="240" />
              <el-table-column label="离线包" width="100">
                <template #default="{ row }">
                  <el-link @click="action(() => downloadSource(selected!.id, row.sha256))">下载</el-link>
                </template>
              </el-table-column>
            </el-table>
            <h3>已恢复的离线包</h3>
            <el-table :data="imports">
              <el-table-column prop="id" label="恢复记录" min-width="220" />
              <el-table-column prop="originalSourceId" label="原源 ID" min-width="220" />
              <el-table-column label="ZIP SHA-256" min-width="240">
                <template #default="{ row }"><code>{{ row.archiveSha256 }}</code></template>
              </el-table-column>
              <el-table-column prop="createdAt" label="恢复时间" width="220" />
              <el-table-column label="配置" width="140">
                <template #default="{ row }">{{ row.configRebindRequired ? '需重新绑定' : '原包无配置指纹' }}</template>
              </el-table-column>
              <el-table-column label="原始 ZIP" width="110">
                <template #default="{ row }">
                  <el-link @click="action(() => downloadFile(`/source-imports/${encodeURIComponent(row.id)}/archive`, '导入原包.zip'))">
                    下载
                  </el-link>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!imports.length" description="该源没有离线包恢复记录" />
          </el-tab-pane>
          <el-tab-pane label="上游协调">
            <UpstreamPanel :key="selected.id" :source-id="selected.id" :artifact-sha256="selected.artifactSha256"
                           @changed="importedSource(selected.id)" />
          </el-tab-pane>
          <el-tab-pane label="媒体诊断">
            <MediaProbePanel :key="selected.id" :source-id="selected.id" :artifact-sha256="selected.artifactSha256" />
          </el-tab-pane>
          <el-tab-pane label="修复与验证资料">
            <p class="boundary">{{ runContext?.boundary }}</p>
            <p class="sha">资料版本 {{ runContext?.catalogSha256 }}</p>
            <article v-for="rule in runContext?.rules" :key="rule.id" class="rule">
              <h3>{{ rule.title }}</h3><p>{{ rule.guidance }}</p>
              <ul><li v-for="step in rule.validationSteps" :key="step">{{ step }}</li></ul>
              <details><summary>{{ rule.citation.skill }} · L{{ rule.citation.startLine }}–{{ rule.citation.endLine }}</summary>
                <p class="sha">{{ rule.citation.path }}</p>
                <p class="sha">{{ rule.citation.sha256 }}</p><pre>{{ rule.citation.excerpt }}</pre>
              </details>
            </article>
          </el-tab-pane>
            </el-tabs>
            <details class="demo-tools"><summary>示例与业务快照</summary>
              <div class="actions">
                <BusinessEvidencePanel :disabled="busy" :catalog="catalog" />
                <el-button :disabled="busy" @click="addFixture">添加离线示例</el-button>
                <el-button :disabled="busy" @click="addRepairFixture">添加静态修复示例</el-button>
              </div>
            </details>
          </div>
        </details>
      </section>
      <section v-else class="welcome">
        <el-icon :size="44"><Tools /></el-icon>
        <h2>还没有待维护的爬虫</h2>
        <el-button :icon="Upload" type="primary" @click="importDrawer = true">导入爬虫</el-button>
        <ProcessLogPanel />
      </section>
    </div>
    <el-drawer v-model="inspectionDrawer" title="智能巡检" size="min(520px, 96vw)" class="inspection-drawer">
      <el-form v-if="selected && schedule" label-position="top" :disabled="busy">
        <el-form-item label="巡检爬虫">
          <el-select :model-value="selectedId" filterable @change="(id: string) => action(() => selectSource(id))">
            <el-option v-for="source in sources" :key="source.id" :value="source.id" :label="source.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="定时联网巡检"><el-switch v-model="scheduleEnabled" /></el-form-item>
        <el-form-item label="首次执行时间（本地时间）">
          <el-date-picker v-model="scheduleFirstRunAt" type="datetime" placeholder="未指定时按巡检周期开始"
                          format="YYYY-MM-DD HH:mm" aria-label="首次执行时间" />
        </el-form-item>
        <el-form-item label="巡检周期（小时）">
          <el-input-number v-model="scheduleHours" :min="0.5" :max="168" :step="0.5" :precision="1" aria-label="巡检周期" />
        </el-form-item>
        <el-form-item label="巡检后自动修复">
          <el-switch v-model="scheduleAutomaticRepair" @change="scheduleRepairConfigurationSha = ''" />
        </el-form-item>
        <el-alert v-if="!scheduling?.enabled" title="定时调度尚未启用，计划保存后暂不执行。" type="warning" :closable="false" />
        <el-alert v-if="!scheduleApprovalOptions.length && !schedule.policy.approval"
                  title="尚无当前版本的联网检查记录，请先完成一次联网检查。" type="info" :closable="false" />
        <el-alert v-if="schedule.suspended" :title="`巡检已暂停：${schedule.suspensionReason}`" type="warning" :closable="false" />
        <p v-if="schedule.nextDueAt" class="schedule-next">下次执行不早于 {{ new Date(schedule.nextDueAt).toLocaleString() }}</p>
        <details class="schedule-advanced">
          <summary>高级设置与执行记录</summary>
          <el-form-item label="联网检查记录">
            <el-select v-model="scheduleApprovalRunId" @change="scheduleApprovalConfirmed = true">
              <el-option v-for="run in scheduleApprovalOptions" :key="run.id" :value="run.id"
                         :label="new Date(run.createdAt).toLocaleString()" />
            </el-select>
          </el-form-item>
          <el-checkbox v-model="scheduleApprovalConfirmed" class="schedule-approval"
                       :disabled="!scheduleApprovalOptions.some(run => run.id === scheduleApprovalRunId)">
            允许所选版本与配置定时联网验证
          </el-checkbox>
          <el-form-item label="每日最多投放次数（UTC）">
            <el-input-number v-model="scheduleDailyLimit" :min="1" :max="24" />
          </el-form-item>
          <p class="boundary">周期含 0–20% 错峰延迟；异常时保留冷却。每日已投放 {{ schedule.usedAttempts }} 次。</p>
          <p v-if="schedule.lastOutcome" class="boundary">上次结果：{{ schedule.lastOutcome }}</p>
          <ScheduleObservationPanel :key="selected.id" :source-id="selected.id"
                                    :policy-sha256="schedule.sha256" :refresh-key="scheduleRefreshKey"
                                    @inspect="id => { inspectionDrawer = false; inspectScheduledRun(id) }" />
        </details>
      </el-form>
      <template #footer>
        <div class="actions">
          <el-button v-if="schedule?.suspended" :disabled="busy" @click="resumeSchedule">恢复巡检</el-button>
          <el-button type="primary" :loading="busy" :disabled="!schedule" @click="saveSchedule">保存计划</el-button>
        </div>
      </template>
    </el-drawer>
    <el-drawer v-model="importDrawer" title="导入爬虫" direction="ltr" size="min(420px, 94vw)" class="import-drawer">
      <div class="import-actions">
        <AtPluginInputPanel :key="connectionRevision" :disabled="busy" :configured="atConnection?.configured"
          @imported="importedSource" @configure="connectionSettings?.show('at')" />
        <PluginInputPanel label="导入文件" :disabled="busy" @imported="importedSource" />
      </div>
      <details class="more-imports">
        <summary>目录、源码与恢复包</summary>
        <div class="import-actions">
          <el-button :disabled="busy" :icon="EditPen" @click="newSource">粘贴源码</el-button>
          <DirectoryImportPanel :disabled="busy" :sources="sources" @imported="importedSource" />
          <NasDirectoryPanel :disabled="busy" :sources="sources" @selected="importedSource" />
          <SourceImportPanel :disabled="busy" @imported="importedSource" />
        </div>
      </details>
    </el-drawer>
    <el-dialog v-model="editVisible" :title="creating ? '添加受管 Python 源' : '保存新的源码版本'" width="min(860px, 94vw)">
      <el-form label-position="top">
        <template v-if="creating">
          <el-form-item label="名称"><el-input v-model="name" maxlength="200" /></el-form-item>
          <el-form-item label="文件名"><el-input v-model="filename" maxlength="200" /></el-form-item>
          <details><summary>高级选项</summary>
            <el-form-item label="稳定业务 ID（可选）"><el-input v-model="externalId" maxlength="200" /></el-form-item>
          </details>
        </template>
        <el-form-item label="导入 UTF-8 Python 文件"><input type="file" accept=".py" @change="readFile" /></el-form-item>
        <el-form-item label="源码"><el-input v-model="content" type="textarea" :rows="16" spellcheck="false" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveSource">保存版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.maintenance-workbench { color: var(--el-text-color-primary); background: var(--el-bg-color-page);
  max-width: 1760px; margin: 0 auto; padding: 12px; }
* { box-sizing: border-box; letter-spacing: 0; }
header, .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
h1 { font-size: 24px; margin: 2px 0 0; } h2 { font-size: 18px; } h3 { font-size: 15px; }
.brand { display: flex; align-items: center; gap: 14px; min-width: 0; }
.brand > .el-icon { color: #25725b; flex-shrink: 0; }
.eyebrow { color: #757c82; font-size: 12px; margin: 0; }
.workspace { margin-top: 24px; display: grid; grid-template-columns: minmax(320px, .9fr) minmax(0, 1.2fr); gap: 20px; align-items: start; }
section { min-width: 0; }
aside, .maintenance, .welcome { background: var(--el-bg-color); padding: 22px; min-width: 0; }
.source-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.source-heading h2 { margin: 0; font-size: 16px; }
.source-heading > span { color: #737a80; font-size: 13px; }
.smart-inspection { width: 100%; margin-bottom: 14px; }
.inspection-drawer :deep(.el-select), .inspection-drawer :deep(.el-date-editor) { width: 100%; min-width: 0; }
.inspection-drawer :deep(.el-alert) { margin-bottom: 12px; }
.schedule-next { font-size: 13px; line-height: 1.7; color: #3f656e; }
.schedule-advanced { border-top: 1px solid #e4e8ea; padding-top: 14px; margin-top: 18px; }
.schedule-advanced > summary { margin-bottom: 16px; }
.import-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.import-actions .el-button + .el-button { margin-left: 0; }
.import-actions > .el-button { width: 100%; justify-content: flex-start; }
.catalog-filters { display: grid; grid-template-columns: minmax(0, 1fr) 130px; gap: 10px; }
.source-search { margin: 0; }
.source-list { max-height: calc(100vh - 260px); min-height: 240px; overflow-y: auto; margin-top: 0; }
.catalog-columns, .source { display: grid; grid-template-columns: minmax(0, 1fr) 40px 96px; gap: 10px; align-items: center; }
.catalog-columns { padding: 16px 12px 10px; color: #737a80; font-size: 12px; border-bottom: 1px solid #e4e8ea; }
.source-identity { min-width: 0; }
.source-version { color: #737a80; font-size: 12px; }
.source-health { font-size: 12px; color: #737a80; }
.source-health.verified { color: #287454; }
.source-health.needs_repair, .source-health.credentials { color: #ad5039; }
.source-health.blocked, .source-health.incomplete { color: #94721d; }
.source-health.running { color: #236e83; }
.more-imports { border-top: 1px solid #e8ebed; padding-top: 16px; margin-top: 18px; }
.more-imports .import-actions { margin-top: 14px; }
.header-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.header-actions .el-button + .el-button { margin-left: 0; }
.source { font: inherit; text-align: left; width: 100%; background: transparent;
  border: 1px solid transparent; padding: 12px; margin: 4px 0; border-radius: 4px; cursor: pointer; overflow-wrap: anywhere; }
.source:hover { background: #f5f7f8; }
.source.active { border-color: #bdd5d4; background: #edf5f2; }
.source.source-attention { background: #fff3f2; border-left-color: #bd302d; }
.source.source-attention.active { border-color: #bd302d; background: #ffedeb; }
.source.source-attention strong, .source.source-attention .source-health { color: #b42326; }
.source:focus-visible { outline: 2px solid #236e83; outline-offset: -2px; }
.source strong, .source small { display: block; }
.source strong { font-size: 14px; font-weight: 600; margin-bottom: 5px; }
.source small, .boundary { color: #737a80; font-size: 13px; line-height: 1.65; }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.actions .el-button + .el-button { margin-left: 0; }
.sha, code { font-size: 11px; overflow-wrap: anywhere; }.toolbar { margin-bottom: 22px; flex-wrap: wrap; }
.source-title { min-width: 0; overflow-wrap: anywhere; }
.source-title h2 { margin: 0 0 6px; }
.source-title > span { font-size: 12px; color: #737a80; }
.check-overview { border-block: 1px solid #e8ebed; padding: 20px 0; margin-bottom: 20px; }
.check-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.check-heading h3 { display: flex; align-items: center; gap: 9px; margin: 0; }
.step-number { color: #25725b; border: 1px solid #c3d9cf; width: 24px; height: 24px;
  display: inline-flex; align-items: center; justify-content: center; border-radius: 4px; font-size: 12px; }
.check-result { font-size: 16px; font-weight: 600; line-height: 1.6; margin: 18px 0 6px; overflow-wrap: anywhere; }
.check-meta { color: #737a80; font-size: 12px; line-height: 1.6; }
.issue-list { font-size: 13px; padding-left: 18px; line-height: 1.7; color: #9b442e; overflow-wrap: anywhere; }
.work-tabs > .el-tabs__header { margin-bottom: 16px; }
.advanced-workspace { border-top: 1px solid #e8ebed; padding-top: 20px; margin-top: 24px; }
.advanced-content { padding-top: 18px; }
.diagnostic-actions { margin-bottom: 18px; }
.demo-tools { margin-top: 24px; }
.demo-tools .actions { margin-top: 12px; }
.run-controls { display: flex; gap: 12px; margin: 12px 0 22px; flex-wrap: wrap; }
.schedule-approval { height: auto; align-items: flex-start; }
.schedule-approval .el-checkbox__label { white-space: normal; line-height: 1.5; }
.schedule-approval .el-checkbox__input { margin-top: 3px; }
pre { font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow-y: auto; background: #f5f7fa; padding: 12px; }
.rule { border-bottom: 1px solid #e5ebef; padding: 12px 0 20px; }
summary { cursor: pointer; font-size: 13px; color: #626b71; line-height: 1.7; }
summary:focus-visible { outline: 2px solid #236e83; outline-offset: 4px; }
.welcome { padding: 80px 24px; color: #737a80; text-align: center; }
.welcome .el-icon { color: #87a69b; }
.el-button { max-width: 100%; }
.el-button :deep(span) { white-space: normal; overflow-wrap: anywhere; }
.el-checkbox { max-width: 100%; }
.el-checkbox :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .maintenance-workbench { padding: 0; } .workspace { grid-template-columns: minmax(0, 1fr); gap: 16px; margin-top: 20px; }
  header { flex-wrap: wrap; gap: 12px; } h1 { font-size: 22px; }
  aside, .maintenance, .welcome { padding: 18px; } .run-controls .el-select { max-width: 100%; }
  .source-list { max-height: 260px; min-height: 0; }
  .import-actions > .el-button { width: auto; flex: 1 1 120px; justify-content: center; }
  .source-heading { margin-bottom: 14px; }
  .toolbar { gap: 12px; }
  .source-title { flex: 1 1 180px; }
  .el-tabs__item { padding: 0 12px; }
  .el-dialog { --el-dialog-padding-primary: 14px; }
}
</style>
