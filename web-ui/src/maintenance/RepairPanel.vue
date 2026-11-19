<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, MagicStick, Refresh, Setting, View } from '@element-plus/icons-vue'
import { downloadFile, request } from './api'
import { evidenceFields, type EvidenceChange, type ManagedEvidence } from './evidence'
import ModelProposalPanel from './ModelProposalPanel.vue'
import EvidenceToolsPanel from './EvidenceToolsPanel.vue'
import AtPublicationPanel from './AtPublicationPanel.vue'
import EnhancedDiagnosticsPanel from './EnhancedDiagnosticsPanel.vue'
import PageDiagnosticsPanel from './PageDiagnosticsPanel.vue'

const props = defineProps<{ sourceId: string; artifactSha256: string; baselineRunId: string
  baselineReady?: boolean; runtimeEnabled?: boolean }>()
const emit = defineEmits<{ 'evidence-changed': [change: EvidenceChange]; 'configure-model': []; 'ready-to-adopt': []; 'activity-started': [] }>()
interface Repair {
  id: string; baselineRunId: string; baselineArtifactSha256: string; faultSha256: string
  status: string; stopCode: string | null; usedRounds: number; maxRounds: number
  freshness: string; staleInputs: string[]
  frozen: { runtimeValidationContext?: unknown; policy?: { scope?: string; targetNodes?: string[] }
    baseline?: { context?: { auxiliaryEvidence?: { extractionReplay?: unknown } }
      result?: { managedSourceEvidence?: ManagedEvidence } } }
}
interface Candidate {
  id: string; roundNumber: number; sha256: string; status: string; errorCode: string | null
  modelCallId: string | null
  sourceImportId?: string | null
  selectorEvidence?: unknown
  proposal: unknown; result: { staticValidationPassed: boolean; publishEligible: boolean
    targetObservedRecovered?: boolean; runtimeObservation?: Record<string, unknown>
    runtimeProgressObserved?: boolean
    runtimeNodeProgress?: { recovered: string[]; regressed: string[]
      comparison: { id: string; before: string; after: string; beforeReason: string; afterReason: string }[] }
    runtimeEvidence?: ManagedEvidence; observedRegressions?: string[]
    runtimeStageComparison?: { field: string; before: string | null; after: string | null }[]
    runtimeComparison?: { field: string; before: number | null; after: number | null }[]
    repairSuccessful?: boolean
    runtimeAcceptance?: { successful: boolean; firstUnresolvedNode: string | null; blockers: string[]
      nodes: { id: string; title: string; status: string; reason: string }[]
      requiredCoverage: { total: number; pass: number; fail: number; blocked: number; notTested: number } }
    contractViolations: string[]; pendingRegressionNodes: string[]
    requiredCoverage: { total: number; pass: number; fail: number; notTested: number } } | null
}
const list = ref<Repair[]>([])
const selectedId = ref('')
const selected = ref<Repair>()
const candidates = ref<Candidate[]>([])
interface ImportedCandidate {
  importId: string; archiveSha256: string; originalCandidateId: string; candidateSha256: string
  baselineArtifactSha256: string; originalModelCallId: string | null; authority: string
}
const importedCandidates = ref<ImportedCandidate[]>([])
const selectedImportId = ref('')
const reusableCandidate = computed(() => importedCandidates.value.find(item => item.importId === selectedImportId.value
  && item.baselineArtifactSha256 === props.artifactSha256))
const proposalJson = ref('')
const busy = ref(false)
const evidenceBusy = ref(false)
const runtimeDrawer = ref(false)
const inspected = ref<Candidate>()
const evidenceRepair = computed(() => selected.value?.baselineRunId === props.baselineRunId
  && selected.value?.baselineArtifactSha256 === props.artifactSha256 ? selected.value : undefined)
interface ApprovedValidation { sourceId: string; artifactSha256: string; repairId: string; candidateId: string }
const pendingValidation = ref<ApprovedValidation>()
const canRetest = computed(() => props.runtimeEnabled === true && !!evidenceRepair.value
  && evidenceRepair.value.freshness === 'current'
  && ['managed_home_candidate', 'managed_loader_candidate'].includes(evidenceRepair.value.frozen?.policy?.scope ?? ''))
function canRevalidate(candidate: Candidate) {
  const repair = selected.value
  return props.runtimeEnabled === true && !!repair && repair.baselineArtifactSha256 === props.artifactSha256
    && (['BLOCKED', 'INTERRUPTED', 'AWAITING_RUNTIME_VALIDATION'].includes(repair.status)
      || repair.status === 'STOPPED' && repair.staleInputs.some(input => ['runtime', 'inspector'].includes(input)))
    && ['managed_home_candidate', 'managed_loader_candidate'].includes(repair.frozen.policy?.scope ?? '')
    && repair.staleInputs.every(input => ['runtime', 'execution_policy', 'inspector'].includes(input))
    && candidates.value[candidates.value.length - 1]?.id === candidate.id
    && candidates.value[candidates.value.length - 1]?.sha256 === candidate.sha256
}
const baselineReady = computed(() => !!props.baselineRunId && props.baselineReady !== false)
const canProposeSelector = computed(() => !!evidenceRepair.value?.frozen.baseline?.context?.auxiliaryEvidence?.extractionReplay
  && evidenceRepair.value.status === 'AWAITING_PROPOSAL' && evidenceRepair.value.freshness === 'current')
const labels: Record<string, string> = {
  AWAITING_PROPOSAL: '等待结构化建议', VALIDATING: '正在验证候选',
  AWAITING_RUNTIME_VALIDATION: '静态通过，等待运行时验证', STOPPED: '已停止',
  BLOCKED: '执行条件不足', INTERRUPTED: '执行中断', CANCELLED: '已取消',
  CANCELLING: '正在停止并清理', QUEUED: '排队中', RUNNING: '静态验证中',
  STATIC_VALIDATED: '静态验证通过', REJECTED: '已拒绝', FAILED: '验证失败',
  VALIDATING_RUNTIME: '隔离复测中', RUNTIME_QUEUED: '等待隔离复测',
  RUNTIME_RUNNING: '隔离复测中', RUNTIME_OBSERVED: '已取得运行观察'
}
const countLabels: Record<string, string> = {
  homeClasses: '首页分类', homeItems: '首页条目', homeCovers: '首页封面',
  categoryItems: '分类条目', categoryCovers: '分类封面', page2Items: '第二页条目',
  detailItems: '详情条目', detailCovers: '详情封面', playGroups: '播放组', playEpisodes: '选集'
}
const observationLabels: Record<string, string> = {
  not_sampled: '未调用', selection_unavailable: '缺少有效选集', returned: '已返回',
  empty: '空地址', invalid_output: '返回格式无效', exception: '调用异常', policy_denied: '出站策略拒绝',
  not_requested: '未请求', prefix_returned: '已取回有界前缀', http_failed: 'HTTP 失败', invalid_headers: '请求头无效'
}
function observationLabel(value: string | null) {
  return value === null ? '未观察' : observationLabels[value] ?? value
}
function runtimeStages(candidate: Candidate) {
  if (candidate.result?.runtimeStageComparison) return candidate.result.runtimeStageComparison
  const before = selected.value?.frozen.baseline?.result?.managedSourceEvidence?.untrustedRuntimeSummary
  const after = candidate.result?.runtimeEvidence?.untrustedRuntimeSummary
  return ['playerStatus', 'mediaStatus'].map(field => ({
    field, before: typeof before?.[field] === 'string' ? before[field] as string : null,
    after: typeof after?.[field] === 'string' ? after[field] as string : null
  }))
}
const runtimeFields = computed(() => evidenceFields({
  stages: inspected.value ? runtimeStages(inspected.value) : [],
  deniedCodes: {
    baseline: selected.value?.frozen.baseline?.result?.managedSourceEvidence?.deniedCodes,
    candidate: inspected.value?.result?.runtimeEvidence?.deniedCodes
  },
  baseline: selected.value?.frozen.baseline?.result?.managedSourceEvidence,
  candidate: inspected.value?.result?.runtimeEvidence,
  acceptance: inspected.value?.result?.runtimeAcceptance,
  observedRegressions: inspected.value?.result?.observedRegressions
}))
let poll: ReturnType<typeof setTimeout> | undefined
let disposed = false
let revision = 0
let refreshRevision = 0
let actions = 0
async function action(work: () => Promise<void>) {
  actions++
  busy.value = true
  try { await work() } catch (e) { ElMessage.error(e instanceof Error ? e.message : '修复操作失败') }
  finally { busy.value = --actions > 0 }
}
async function loadSelection() {
  if (poll) clearTimeout(poll)
  const id = selectedId.value
  const current = revision
  if (selected.value?.id !== id) { runtimeDrawer.value = false; inspected.value = undefined }
  if (!id) { selected.value = undefined; candidates.value = []; return }
  const [repair, items] = await Promise.all([
    request<Repair>(`/repairs/${id}`), request<Candidate[]>(`/repairs/${id}/candidates`)
  ])
  if (disposed || current !== revision || id !== selectedId.value) return
  selected.value = repair
  list.value = list.value.map(item => item.id === id ? repair : item)
  candidates.value = items
  const approved = pendingValidation.value
  if (approved && approved.sourceId === props.sourceId && approved.artifactSha256 === props.artifactSha256
    && approved.repairId === id) {
    const candidate = items.find(item => item.id === approved.candidateId)
    if (!canRetest.value || ['STOPPED', 'BLOCKED', 'INTERRUPTED', 'CANCELLED', 'CANCELLING'].includes(repair.status)
      || (candidate && !['QUEUED', 'RUNNING', 'STATIC_VALIDATED'].includes(candidate.status))) {
      pendingValidation.value = undefined
    } else if (candidate?.status === 'STATIC_VALIDATED' && repair.status === 'AWAITING_RUNTIME_VALIDATION') {
      // Consume the explicit approval before the request; lost replies must not start another run.
      pendingValidation.value = undefined
      await validateRuntime(candidate)
      return
    }
  }
  if (['VALIDATING', 'VALIDATING_RUNTIME', 'CANCELLING'].includes(repair.status)) {
    poll = setTimeout(() => action(loadSelection), 1000)
  }
}
async function refresh() {
  const current = revision
  const pending = ++refreshRevision
  const sourceId = props.sourceId
  const [items, imported] = await Promise.all([
    request<Repair[]>(`/sources/${sourceId}/repairs`),
    request<ImportedCandidate[]>(`/sources/${sourceId}/imported-candidates`)
  ])
  if (disposed || current !== revision || pending !== refreshRevision || sourceId !== props.sourceId) return
  list.value = items
  importedCandidates.value = imported
  if (!imported.some(item => item.importId === selectedImportId.value))
    selectedImportId.value = imported.find(item => item.baselineArtifactSha256 === props.artifactSha256)?.importId ?? ''
  if (!selectedId.value) selectedId.value = items.find(item => item.baselineRunId === props.baselineRunId
    && item.baselineArtifactSha256 === props.artifactSha256)?.id ?? ''
  await loadSelection()
}
async function create() {
  if (!baselineReady.value || busy.value || evidenceBusy.value) return
  const current = revision
  await action(async () => {
    const repair = await request<Repair>(`/sources/${props.sourceId}/repairs`, {
      baselineRunId: props.baselineRunId, expectedArtifactSha256: props.artifactSha256
    })
    if (disposed || current !== revision) return
    selectedId.value = repair.id
    emit('activity-started')
    proposalJson.value = ''
    await refresh()
  })
}
function evidenceChanged(change: EvidenceChange) {
  if (change.sourceId !== props.sourceId || change.runId !== props.baselineRunId
    || change.artifactSha256 !== props.artifactSha256) return
  emit('evidence-changed', change)
  if (change.kind === 'selectorProposal') void action(refresh)
}
async function fillTemplate() {
  const repair = selected.value
  if (!repair) return
  await action(async () => {
    const fixture = await request<{ artifactSha256: string; proposal: unknown }>('/fixtures/repair')
    if (disposed || repair.id !== selectedId.value) return
    proposalJson.value = JSON.stringify(fixture.artifactSha256 === repair.baselineArtifactSha256 ? fixture.proposal : {
      schemaVersion: 1, expectedBaselineSha256: repair.baselineArtifactSha256, targetFile: 'source.py',
      diagnosis: '填写故障诊断', expectedEffect: '填写预期效果与仍待验证的范围',
      validationTargets: repair.frozen.policy?.targetNodes ?? ['S03'],
      edits: [{ oldText: '粘贴只出现一次的待替换原文', newText: '' }]
    }, null, 2)
  })
}
async function submit() {
  const id = selectedId.value
  await action(async () => {
    let proposal: unknown
    try { proposal = JSON.parse(proposalJson.value) } catch { throw new Error('建议 JSON 格式错误') }
    await request(`/repairs/${id}/proposals`, proposal)
    await refresh()
  })
}
async function reuseImported(retest = false) {
  const repair = selected.value
  const archived = reusableCandidate.value
  if (!repair || !archived || busy.value || repair.status !== 'AWAITING_PROPOSAL'
    || repair.freshness !== 'current' || repair.baselineArtifactSha256 !== props.artifactSha256
    || (retest && !canRetest.value)) return
  const current = revision
  await action(async () => {
    const candidate = await request<{ id: string; repairId: string }>(`/repairs/${repair.id}/imported-candidates`, {
      importId: archived.importId, expectedArchiveSha256: archived.archiveSha256
    })
    if (!disposed && current === revision) {
      if (retest && candidate.repairId === repair.id) pendingValidation.value = {
        sourceId: props.sourceId, artifactSha256: props.artifactSha256, repairId: repair.id, candidateId: candidate.id
      }
      await refresh()
    }
  })
}
async function submittedForValidation(approved: ApprovedValidation) {
  if (!canRetest.value || approved.sourceId !== props.sourceId || approved.artifactSha256 !== props.artifactSha256
    || approved.repairId !== selectedId.value || !approved.candidateId) return
  pendingValidation.value = approved
  await action(refresh)
}
async function cancel() {
  const id = selectedId.value
  pendingValidation.value = undefined
  await action(async () => { await request(`/repairs/${id}/cancel`, {}); await refresh() })
}
async function resume() {
  const repair = selected.value
  if (!repair) return
  await action(async () => {
    await request(`/repairs/${repair.id}/resume`, { expectedFaultSha256: repair.faultSha256 })
    await refresh()
  })
}
async function validateRuntime(candidate: Candidate) {
  if (!canRetest.value || candidate.status !== 'STATIC_VALIDATED'
    || !candidates.value.some(item => item.id === candidate.id && item.sha256 === candidate.sha256)) return
  const current = revision
  await action(async () => {
    await request(`/repair-candidates/${candidate.id}/runtime-validation`, {
      expectedCandidateSha256: candidate.sha256
    })
    if (!disposed && current === revision) await refresh()
  })
}
async function revalidateRuntime(candidate: Candidate) {
  const repair = selected.value
  if (!repair || !canRevalidate(candidate)) return
  const current = revision
  await action(async () => {
    await request(`/repair-candidates/${candidate.id}/runtime-revalidation`, {
      expectedCandidateSha256: candidate.sha256, expectedFaultSha256: repair.faultSha256
    })
    if (!disposed && current === revision) await refresh()
  })
}
watch(selectedId, () => {
  revision++
  pendingValidation.value = undefined
  if (poll) clearTimeout(poll)
  selected.value = undefined
  candidates.value = []
  runtimeDrawer.value = false
  inspected.value = undefined
}, { flush: 'sync' })
watch(() => [props.sourceId, props.artifactSha256, props.baselineRunId], () => {
  revision++
  pendingValidation.value = undefined
  if (poll) clearTimeout(poll)
  selectedId.value = ''
  selected.value = undefined
  candidates.value = []
  list.value = []
  importedCandidates.value = []
  selectedImportId.value = ''
  proposalJson.value = ''
  runtimeDrawer.value = false
  inspected.value = undefined
  void action(refresh)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { disposed = true; revision++; pendingValidation.value = undefined; if (poll) clearTimeout(poll) })
</script>

<template>
  <div class="repair-actions">
    <el-button v-if="!evidenceRepair" type="primary" :icon="MagicStick"
               :disabled="busy || evidenceBusy || !baselineReady" @click="create">开始修复</el-button>
    <el-button :icon="Setting" :disabled="busy" @click="emit('configure-model')">模型设置</el-button>
    <el-tooltip content="刷新修复进度">
      <el-button :icon="Refresh" :disabled="busy" aria-label="刷新修复进度" @click="action(refresh)" />
    </el-tooltip>
    <el-select v-if="list.length && (!selected || list.length > 1)" v-model="selectedId" placeholder="修复记录" style="width: 260px; max-width: 100%"
               @change="action(loadSelection)">
      <el-option v-for="repair in list" :key="repair.id" :value="repair.id"
                 :label="`${labels[repair.status] || repair.status} · ${repair.usedRounds}/${repair.maxRounds} 轮`" />
    </el-select>
  </div>
  <p v-if="!baselineReady && !selected" class="boundary">等待当前版本的检查报告</p>
  <p v-else-if="!selected" class="boundary">尚无修复记录</p>
  <EnhancedDiagnosticsPanel v-if="baselineRunId" :run-id="baselineRunId" :artifact-sha256="artifactSha256"
    :repair-id="evidenceRepair?.id" :disabled="busy || !baselineReady" />
  <PageDiagnosticsPanel v-if="baselineRunId" :source-id="sourceId" :run-id="baselineRunId"
    :artifact-sha256="artifactSha256" :disabled="busy || evidenceBusy || !baselineReady"
    @busy-change="value => evidenceBusy = value" />
  <template v-if="selected">
    <p>{{ labels[selected.status] || selected.status }} · 已用 {{ selected.usedRounds }}/{{ selected.maxRounds }} 轮
      <span v-if="selected.stopCode"> · {{ selected.stopCode }}</span></p>
    <el-alert v-if="selected.freshness !== 'current'" :title="`输入已变化：${selected.staleInputs.join('、')}`"
              type="warning" :closable="false" />
    <p v-if="selected.frozen.runtimeValidationContext" class="boundary">已绑定复验环境，原始巡检和历次失败记录保留。</p>
    <div v-if="reusableCandidate && selected.status === 'AWAITING_PROPOSAL'" class="repair-actions">
      <el-select v-if="importedCandidates.length > 1" v-model="selectedImportId" aria-label="已导入候选">
        <el-option v-for="item in importedCandidates" :key="item.importId" :value="item.importId"
          :label="item.candidateSha256.slice(0, 12)" :disabled="item.baselineArtifactSha256 !== artifactSha256" />
      </el-select>
      <el-button :icon="Download" :disabled="busy || selected.freshness !== 'current'"
        @click="reuseImported(canRetest)">{{ canRetest ? '复用并复测' : '复用已导入候选' }}</el-button>
    </div>
    <ModelProposalPanel :key="selected.id" :source-id="sourceId" :artifact-sha256="artifactSha256"
      :repair="selected" :disabled="busy" :runtime-available="canRetest"
      @submitted-for-validation="submittedForValidation" @changed="action(refresh)" @configure="emit('configure-model')" />
    <p v-if="pendingValidation" class="boundary" role="status">等待代码检查完成</p>
    <div class="repair-actions">
      <el-button v-if="['AWAITING_PROPOSAL', 'VALIDATING', 'VALIDATING_RUNTIME', 'CANCELLING', 'AWAITING_RUNTIME_VALIDATION'].includes(selected.status)"
                 :disabled="busy || selected.status === 'CANCELLING'" @click="cancel">取消会话</el-button>
      <el-button v-if="['BLOCKED', 'INTERRUPTED', 'CANCELLED'].includes(selected.status)"
                 :disabled="busy || selected.freshness !== 'current'" @click="resume">问题修正后恢复（保留次数）</el-button>
    </div>
    <details class="manual-proposal">
      <summary>手工补丁（高级）</summary>
      <el-button :disabled="busy" @click="fillTemplate">填入建议模板</el-button>
      <el-input v-model="proposalJson" type="textarea" :rows="12" placeholder="exact_edits_v1 JSON" />
      <el-button :disabled="busy || selected.status !== 'AWAITING_PROPOSAL' || selected.freshness !== 'current' || !proposalJson"
                 @click="submit">保存候选并检查代码</el-button>
    </details>
    <el-card v-for="candidate in candidates" :key="candidate.id" class="candidate">
      <p>第 {{ candidate.roundNumber }} 轮 · {{ labels[candidate.status] || candidate.status }}
        <span v-if="candidate.errorCode"> · {{ candidate.errorCode }}</span></p>
      <p v-if="candidate.result && !candidate.result.runtimeAcceptance">静态阶段必需项 {{ candidate.result.requiredCoverage.total }}：
        通过 {{ candidate.result.requiredCoverage.pass }}，失败 {{ candidate.result.requiredCoverage.fail }}，
        未测 {{ candidate.result.requiredCoverage.notTested }}。候选尚未获得发布资格。</p>
      <p v-if="candidate.result?.contractViolations.length">合同变化：{{ candidate.result.contractViolations.join('、') }}</p>
      <el-button v-if="candidate.status === 'STATIC_VALIDATED' && ['managed_home_candidate', 'managed_loader_candidate'].includes(selected.frozen.policy?.scope || '')"
                 :disabled="busy || !canRetest" @click="validateRuntime(candidate)">
        验证修复结果
      </el-button>
      <el-button v-if="canRevalidate(candidate)" :icon="Refresh" :disabled="busy" @click="revalidateRuntime(candidate)">
        在当前环境复测
      </el-button>
      <el-button v-if="candidate.status === 'STATIC_VALIDATED' || candidate.result?.repairSuccessful"
                 :disabled="busy" @click="emit('ready-to-adopt')">查看采用条件</el-button>
      <AtPublicationPanel v-if="candidate.result?.repairSuccessful" :candidate-id="candidate.id"
        :candidate-sha256="candidate.sha256" :disabled="busy" />
      <p v-if="candidate.result?.runtimeAcceptance" class="runtime-acceptance">
        {{ candidate.result.repairSuccessful ? '本次必需验证全部通过' : '修复仍有未完成项' }} ·
        {{ candidate.result.runtimeAcceptance.requiredCoverage.pass }}/{{ candidate.result.runtimeAcceptance.requiredCoverage.total }} 通过
      </p>
      <el-alert v-if="candidate.result?.observedRegressions?.length"
        :title="`发现功能退化：${candidate.result.observedRegressions.map(field => countLabels[field] || (field === 'playerStatus' ? '取链' : field === 'mediaStatus' ? '媒体前缀' : field)).join('、')}`"
        type="error" :closable="false" />
      <details><summary>验证详情与技术证据</summary>
      <p>候选版本 <code>{{ candidate.sha256 }}</code></p>
      <p v-if="candidate.modelCallId">模型来源 <code>{{ candidate.modelCallId }}</code></p>
      <p v-if="candidate.sourceImportId">历史候选移交 <code>{{ candidate.sourceImportId }}</code></p>
      <details v-if="candidate.selectorEvidence"><summary>冻结的字段与独立页回放</summary>
        <pre>{{ JSON.stringify(candidate.selectorEvidence, null, 2) }}</pre></details>
      <template v-if="candidate.result?.runtimeNodeProgress">
        <p>节点恢复：{{ candidate.result.runtimeNodeProgress.recovered.join('、') || '无' }}；
          节点退化：{{ candidate.result.runtimeNodeProgress.regressed.join('、') || '无' }}。
          {{ candidate.result.runtimeProgressObserved ? '已确认本轮进展' : '尚无可确认的节点进展' }}</p>
        <el-table :data="candidate.result.runtimeNodeProgress.comparison" size="small">
          <el-table-column prop="id" label="冻结节点" width="100" />
          <el-table-column label="原件" width="90">
            <template #default="{ row }">{{ ({ pass: '通过', fail: '失败', blocked: '受阻', not_tested: '未测' } as Record<string, string>)[row.before] || row.before }}</template>
          </el-table-column>
          <el-table-column label="候选" width="90">
            <template #default="{ row }">{{ ({ pass: '通过', fail: '失败', blocked: '受阻', not_tested: '未测' } as Record<string, string>)[row.after] || row.after }}</template>
          </el-table-column>
          <el-table-column prop="afterReason" label="本轮回执" min-width="160" />
        </el-table>
      </template>
      <p v-else-if="candidate.result?.runtimeObservation">
        首页恢复观察：{{ candidate.result.targetObservedRecovered ? '已观察到' : '未观察到' }}；业务与播放未验证。
      </p>
      <template v-if="candidate.result?.runtimeAcceptance">
        <p class="runtime-acceptance">
          {{ candidate.result.repairSuccessful ? '冻结画像装载合同通过' : '修复未完成' }} · 装载器必需项 {{ candidate.result.runtimeAcceptance.requiredCoverage.total }}：
          通过 {{ candidate.result.runtimeAcceptance.requiredCoverage.pass }}，
          失败 {{ candidate.result.runtimeAcceptance.requiredCoverage.fail }}，
          受阻 {{ candidate.result.runtimeAcceptance.requiredCoverage.blocked }}，
          未测 {{ candidate.result.runtimeAcceptance.requiredCoverage.notTested }}。
          首个未完成节点：{{ candidate.result.runtimeAcceptance.firstUnresolvedNode || (candidate.result.repairSuccessful ? '无' : '待核验') }}
        </p>
        <el-table :data="candidate.result.runtimeAcceptance.nodes" size="small" class="runtime-required-nodes">
          <el-table-column label="必需节点" width="125">
            <template #default="{ row }">{{ row.id }} · {{ row.title }}</template>
          </el-table-column>
          <el-table-column label="状态" width="72">
            <template #default="{ row }">{{ ({ pass: '通过', fail: '失败', blocked: '受阻', not_tested: '未测' } as Record<string, string>)[row.status] || row.status }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="阻断 / 证据" min-width="120" />
        </el-table>
      </template>
      <el-table v-if="candidate.result?.runtimeComparison" :data="candidate.result.runtimeComparison" size="small">
        <el-table-column label="源输出观察" min-width="120">
          <template #default="{ row }">{{ countLabels[row.field] || row.field }}</template>
        </el-table-column>
        <el-table-column label="原件" width="80">
          <template #default="{ row }">{{ row.before ?? '未测' }}</template>
        </el-table-column>
        <el-table-column label="候选" width="80">
          <template #default="{ row }">{{ row.after ?? '未测' }}</template>
        </el-table-column>
      </el-table>
      <template v-if="candidate.result?.runtimeEvidence">
        <el-table :data="runtimeStages(candidate)" size="small" class="runtime-stages">
          <el-table-column label="后续链路" min-width="120">
            <template #default="{ row }">{{ row.field === 'playerStatus' ? '原始取链' : '媒体前缀' }}</template>
          </el-table-column>
          <el-table-column label="原件" min-width="130">
            <template #default="{ row }">{{ observationLabel(row.before) }}</template>
          </el-table-column>
          <el-table-column label="候选" min-width="130">
            <template #default="{ row }">{{ observationLabel(row.after) }}</template>
          </el-table-column>
        </el-table>
        <p v-if="candidate.result.runtimeEvidence.deniedCodes?.length" class="runtime-denials">
          拒绝码：<code>{{ candidate.result.runtimeEvidence.deniedCodes.join('、') }}</code>
        </p>
        <el-tooltip content="拆解原件与候选的运行证据">
          <el-button :icon="View" circle :aria-label="`拆解第 ${candidate.roundNumber} 轮运行证据`"
            @click="inspected = candidate; runtimeDrawer = true" />
        </el-tooltip>
      </template>
      <details><summary>结构化补丁与对照证据</summary>
        <pre>{{ JSON.stringify({ proposal: candidate.proposal, result: candidate.result }, null, 2) }}</pre>
      </details>
      </details>
      <el-link type="primary" @click="action(() => downloadFile(`/repair-candidates/${candidate.id}/export`, '候选维护包.zip'))">下载候选维护包</el-link>
    </el-card>
  </template>
  <details class="repair-tools">
    <summary>高级修复工具</summary>
    <EvidenceToolsPanel v-if="baselineRunId" :source-id="sourceId" :artifact-sha256="artifactSha256"
      :run-id="baselineRunId" :repair-id="evidenceRepair?.id" :disabled="busy || !baselineReady"
      :can-propose="canProposeSelector" @busy-change="evidenceBusy = $event" @changed="evidenceChanged" />
    <details v-if="selected"><summary>冻结的报告、能力画像、规则资料与策略</summary>
      <pre>{{ JSON.stringify(selected.frozen, null, 2) }}</pre>
    </details>
  </details>
  <el-drawer v-model="runtimeDrawer" title="候选运行证据" size="min(760px, 100vw)" append-to-body
    class="repair-runtime-drawer">
    <el-tag type="warning">源输出观察 · 业务与播放未验证</el-tag>
    <p>候选 <code>{{ inspected?.sha256 }}</code></p>
    <el-tree :data="runtimeFields" node-key="id" default-expand-all :expand-on-click-node="false"
      class="runtime-tree" />
  </el-drawer>
</template>

<style scoped>
.repair-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0; }
.repair-actions .el-button + .el-button { margin-left: 0; }
.manual-proposal .el-textarea { margin: 12px 0; }
.repair-tools { border-top: 1px solid #e4e7ed; padding-top: 16px; }
.candidate { margin: 16px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 480px; overflow: auto; }
code { overflow-wrap: anywhere; }
details { margin: 12px 0; }
.runtime-stages { margin-top: 12px; }
.runtime-denials { overflow-wrap: anywhere; }
.runtime-required-nodes :deep(.cell) { white-space: normal; overflow-wrap: anywhere; line-height: 1.45; }
.runtime-tree :deep(.el-tree-node__content) { height: auto; min-height: 30px; align-items: flex-start; padding-block: 4px; }
.runtime-tree :deep(.el-tree-node__label) { white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; line-height: 1.7; font-size: 12px; }
</style>
