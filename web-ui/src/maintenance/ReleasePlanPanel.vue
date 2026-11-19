<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Refresh, RefreshLeft, View } from '@element-plus/icons-vue'
import { downloadFile, request } from './api'
import NativeDeploymentPanel from './NativeDeploymentPanel.vue'

const props = defineProps<{ sourceId: string; artifactSha256: string }>()
const emit = defineEmits<{ 'source-changed': [sourceId: string] }>()
interface Repair { id: string; status: string; freshness: string; baselineArtifactSha256: string }
interface Candidate {
  id: string; sha256: string; roundNumber: number; status: string
  result?: { repairSuccessful?: boolean; runtimeAcceptance?: { successful?: boolean } } | null
}
interface Plan {
  id: string; candidateId: string; baselineArtifactSha256: string; candidateSha256: string
  frozenSha256: string; status: string; createdAt: string; cancelledAt: string | null
  freshness: string; staleInputs: string[]; blockers: string[]; publishEligible: boolean
  localApplyEligible?: boolean; localRollbackEligible?: boolean; sourceRevision: number
  localReceipt?: { apply?: { beforeRevision: number; afterRevision: number }; rollback?: unknown } | null
}
const repairs = ref<Repair[]>([])
const repairId = ref('')
const candidates = ref<Candidate[]>([])
const candidateId = ref('')
const plans = ref<Plan[]>([])
const busy = ref(false)
const frozen = ref<unknown>()
const frozenPlanId = ref('')
const selectedRepair = computed(() => repairs.value.find(item => item.id === repairId.value))
const selectedCandidate = computed(() => candidates.value.find(item => item.id === candidateId.value))
const hasActivePlan = computed(() => plans.value.some(item => item.status === 'AWAITING_RUNTIME_VALIDATION'))
const canCreate = computed(() => !busy.value && !hasActivePlan.value && selectedCandidate.value && selectedRepair.value
  && (selectedCandidate.value.status === 'STATIC_VALIDATED' && selectedRepair.value?.status === 'AWAITING_RUNTIME_VALIDATION'
    || selectedCandidate.value.status === 'RUNTIME_OBSERVED' && selectedRepair.value?.status === 'RUNTIME_OBSERVED'
      && selectedCandidate.value.result?.repairSuccessful === true
      && selectedCandidate.value.result?.runtimeAcceptance?.successful === true)
  && selectedRepair.value.freshness === 'current'
  && selectedRepair.value.baselineArtifactSha256 === props.artifactSha256)
const candidateLabels: Record<string, string> = {
  STATIC_VALIDATED: '静态通过', RUNTIME_OBSERVED: '已取得运行观察'
}
const planLabels: Record<string, string> = { AWAITING_RUNTIME_VALIDATION: '等待验证或本地采纳',
  CANCELLED: '已取消', LOCAL_APPLIED: '已本地采纳', LOCAL_ROLLED_BACK: '已本地回滚' }
const blockerLabels: Record<string, string> = {
  ISOLATED_RUNTIME_VALIDATION_REQUIRED: '待完成候选隔离运行与回归',
  AT_RUNTIME_VALIDATION_REQUIRED: '待完成 AT 运行验证及实际装载身份核验',
  MEDIA_VALIDATION_REQUIRED: '待完成所需媒体节点验证',
  AT_PUBLICATION_ADAPTER_UNAVAILABLE: '尚未接入 AT 持久发布与回滚执行器',
  AT_BINDING_REQUIRED: '尚未绑定 AT 插件',
  RELEASE_INPUTS_CHANGED: '冻结输入已变化，须重新验证',
  PLAN_CANCELLED: '计划已取消'
}
let disposed = false
let revision = 0
let refreshRevision = 0
let candidateRevision = 0
let actions = 0
let pending: { candidateId: string; expectedArtifactSha256: string; expectedCandidateSha256: string; requestKey: string } | undefined
async function action(work: () => Promise<void>) {
  actions++
  busy.value = true
  try { await work() } catch (e) { ElMessage.error(e instanceof Error ? e.message : '采用操作失败') }
  finally { busy.value = --actions > 0 }
}
async function loadCandidates() {
  const id = repairId.value
  const current = revision
  const sequence = ++candidateRevision
  if (!id) { candidates.value = []; candidateId.value = ''; return }
  const items = await request<Candidate[]>(`/repairs/${id}/candidates`)
  if (disposed || current !== revision || sequence !== candidateRevision || id !== repairId.value) return
  candidates.value = items.filter(item => item.status === 'STATIC_VALIDATED'
    || item.status === 'RUNTIME_OBSERVED' && item.result?.repairSuccessful === true
      && item.result?.runtimeAcceptance?.successful === true)
  if (!candidates.value.some(item => item.id === candidateId.value)) candidateId.value = candidates.value[0]?.id ?? ''
}
async function refresh() {
  const current = revision
  const sequence = ++refreshRevision
  const sourceId = props.sourceId
  const [sessions, items] = await Promise.all([
    request<Repair[]>(`/sources/${sourceId}/repairs`),
    request<Plan[]>(`/sources/${sourceId}/release-plans`)
  ])
  if (disposed || current !== revision || sequence !== refreshRevision || sourceId !== props.sourceId) return
  repairs.value = sessions
  plans.value = items
  if (!sessions.some(item => item.id === repairId.value)) repairId.value = sessions[0]?.id ?? ''
  await loadCandidates()
}
async function create() {
  const candidate = selectedCandidate.value
  if (!candidate || !canCreate.value) return
  if (!pending || pending.candidateId !== candidate.id || pending.expectedCandidateSha256 !== candidate.sha256
      || pending.expectedArtifactSha256 !== props.artifactSha256) {
    pending = { candidateId: candidate.id, expectedArtifactSha256: props.artifactSha256,
      expectedCandidateSha256: candidate.sha256,
      requestKey: Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('') }
  }
  const input = pending
  const current = revision
  const sourceId = props.sourceId
  await action(async () => {
    await request(`/sources/${sourceId}/release-plans`, input)
    if (disposed || current !== revision) return
    await refresh()
  })
}
async function cancel(plan: Plan) {
  const current = revision
  await action(async () => {
    await request(`/release-plans/${plan.id}/cancel`, {})
    if (!disposed && current === revision) { pending = undefined; await refresh() }
  })
}
async function transitionLocal(plan: Plan, rollback: boolean) {
  if (busy.value || !(rollback ? plan.localRollbackEligible : plan.localApplyEligible)) return
  const current = revision
  const sourceId = props.sourceId
  const body = { expectedFrozenSha256: plan.frozenSha256,
    expectedArtifactSha256: rollback ? plan.candidateSha256 : plan.baselineArtifactSha256,
    expectedSourceRevision: plan.sourceRevision }
  try {
    await ElMessageBox.confirm(rollback ? '恢复该计划的本地基线？生产 AT 不变。'
      : '将已通过候选设为本地有效版本？生产 AT 不变。', rollback ? '本地回滚' : '本地采纳', { type: 'warning' })
  } catch { return }
  if (disposed || current !== revision || busy.value) return
  await action(async () => {
    const result = await request<Plan>(`/release-plans/${plan.id}/${rollback ? 'local-rollback' : 'local-apply'}`, body)
    if (disposed || current !== revision) return
    pending = undefined
    refreshRevision++
    plans.value = plans.value.map(item => item.id === result.id ? result : item)
    emit('source-changed', sourceId)
    if (!disposed && current === revision) await refresh()
  })
}
async function viewFrozen(plan: Plan) {
  const current = revision
  await action(async () => {
    const value = await request<unknown>(`/release-plans/${plan.id}/frozen`)
    if (disposed || current !== revision) return
    frozen.value = value
    frozenPlanId.value = plan.id
  })
}
watch(() => [props.sourceId, props.artifactSha256], () => {
  revision++; pending = undefined
  repairs.value = []; plans.value = []; candidates.value = []
  repairId.value = ''; candidateId.value = ''; frozen.value = undefined; frozenPlanId.value = ''
  void action(refresh)
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { disposed = true; revision++ })
</script>

<template>
  <el-alert title="本地维护库 · 生产 AT 未变更"
            type="info" :closable="false" />
  <div class="actions">
    <el-select v-model="repairId" placeholder="修复记录" style="width: 240px" :disabled="busy"
               @change="action(loadCandidates)">
      <el-option v-for="(repair, index) in repairs" :key="repair.id" :value="repair.id"
                 :label="`修复记录 ${index + 1}`" />
    </el-select>
    <el-select v-model="candidateId" placeholder="暂无通过检查的修复版本" style="width: 280px" :disabled="busy">
      <el-option v-for="candidate in candidates" :key="candidate.id" :value="candidate.id"
                 :label="`第 ${candidate.roundNumber} 轮 · ${candidateLabels[candidate.status]}`" />
    </el-select>
    <el-button type="primary" :disabled="!canCreate" @click="create">准备采用此版本</el-button>
    <el-tooltip content="刷新"><el-button :icon="Refresh" :disabled="busy" aria-label="刷新" @click="action(refresh)" /></el-tooltip>
  </div>
  <p v-if="hasActivePlan">该源已有活动计划。输入变化后计划保留原快照，取消后可重新准备。</p>
  <el-card v-for="plan in plans" :key="plan.id" class="plan">
    <p>{{ planLabels[plan.status] || plan.status }} · {{ plan.createdAt }}</p>
    <el-alert v-if="plan.freshness !== 'current'" :title="`输入已变化：${plan.staleInputs.join('、')}`"
              type="warning" :closable="false" />
    <p v-if="plan.localApplyEligible">修复版本可在本地采用，生产 AT 保持不变。</p>
    <details v-if="plan.blockers.length"><summary>生产接入的待办（{{ plan.blockers.length }}）</summary>
      <ul><li v-for="blocker in plan.blockers" :key="blocker">{{ blockerLabels[blocker] || blocker }}</li></ul>
    </details>
    <div class="actions">
      <el-tooltip content="查看冻结证据"><el-button :icon="View" :disabled="busy" aria-label="查看冻结证据" @click="viewFrozen(plan)" /></el-tooltip>
      <el-button v-if="plan.localApplyEligible" :icon="Check" :disabled="busy" type="primary" @click="transitionLocal(plan, false)">本地采纳</el-button>
      <el-button v-if="plan.localRollbackEligible" :icon="RefreshLeft" :disabled="busy" @click="transitionLocal(plan, true)">本地回滚</el-button>
      <el-tooltip v-if="plan.status === 'AWAITING_RUNTIME_VALIDATION'" content="取消计划">
        <el-button :icon="Close" :disabled="busy" aria-label="取消计划" @click="cancel(plan)" />
      </el-tooltip>
      <el-link type="primary" @click="action(() => downloadFile(`/release-plans/${plan.id}/export`, '维护发布包.zip'))">下载维护包</el-link>
    </div>
    <details v-if="plan.localReceipt"><summary>本地操作回执</summary><pre>{{ JSON.stringify(plan.localReceipt, null, 2) }}</pre></details>
    <NativeDeploymentPanel :plan-id="plan.id" :frozen-sha256="plan.frozenSha256"
                           :baseline-sha256="plan.baselineArtifactSha256" :eligible="plan.localApplyEligible === true" />
    <details><summary>版本身份与证据摘要</summary>
      <p>基线 <code>{{ plan.baselineArtifactSha256 }}</code></p>
      <p>候选 <code>{{ plan.candidateSha256 }}</code></p>
      <p>冻结证据 <code>{{ plan.frozenSha256 }}</code></p>
    </details>
    <details v-if="frozenPlanId === plan.id" open><summary>计划创建时的冻结证据</summary>
      <pre>{{ JSON.stringify(frozen, null, 2) }}</pre>
    </details>
  </el-card>
  <p v-if="!plans.length" class="boundary">暂无可采用的维护计划</p>
</template>

<style scoped>
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0; }
.actions .el-select { max-width: 100%; }
.plan { margin: 16px 0; }
code, pre { overflow-wrap: anywhere; }
pre { white-space: pre-wrap; max-height: 480px; overflow: auto; }
</style>
