<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Check, MagicStick } from '@element-plus/icons-vue'
import { request, sourceContent } from './api'

interface Task { runId: string; sourceId: string; artifactSha256: string; status: string
  repairId: string | null; stopCode: string | null }
interface Connection { enabled: boolean; model: string; configurationSha256: string; autoSubmitEnabled: boolean }
interface Consent { sourceId: string; artifactSha256: string | null }
interface EvidenceOffer { available: boolean; candidateSha256: string; evidenceSha256: string
  expectedFaultSha256: string; usedRounds: number; maxRounds: number }
interface RoundState { id: string; faultSha256: string; usedRounds: number; maxRounds: number; freshness: string
  frozen: { roundLimit?: { maxRounds: number } } }
const props = defineProps<{ sourceId: string; artifactSha256: string; disabled: boolean; runtimeEnabled: boolean }>()
const emit = defineEmits<{ changed: [task: Task]; configure: [] }>()
const task = ref<Task>()
const connection = ref<Connection>()
const consent = ref<Consent>()
const busy = ref(false)
const maxRounds = ref(3)
const roundState = ref<RoundState>()
const error = ref('')
const reviewing = ref(false)
const reviewed = ref(false)
const content = ref('')
const pending = ref<{ expectedArtifactSha256: string; expectedConfigurationSha256: string; requestKey: string; maxRounds: number }>()
const pendingResume = ref<{ expectedArtifactSha256: string; expectedConfigurationSha256: string; requestKey: string }>()
const evidenceOffer = ref<EvidenceOffer>()
const confirmingContinuation = ref(false)
const pendingContinuation = ref<{ runId: string; sourceId: string; expectedArtifactSha256: string
  expectedConfigurationSha256: string; expectedFaultSha256: string; expectedCandidateSha256: string
  expectedEvidenceSha256: string; requestKey: string }>()
const active = computed(() => !!task.value && ['CHECKING', 'REPAIRING'].includes(task.value.status))
const labels: Record<string, string> = {
  CHECKING: '正在联网巡检', REPAIRING: '发现问题，自动修复及复测中',
  CHECKED: '所选联网环节通过，未触发修复', SUCCEEDED: '自动修复通过，候选未采用',
  STOPPED: '已停止', CANCELLED: '已取消'
}
const stopLabels: Record<string, string> = {
  MODEL_DAILY_BUDGET: '模型每日额度不足', MODEL_COST_DAILY_BUDGET: '模型每日费用额度不足',
  MODEL_SOURCE_DAILY_BUDGET: '本源每日模型额度不足', MODEL_SOURCE_COST_DAILY_BUDGET: '本源每日费用额度不足',
  MODEL_NAME_DAILY_BUDGET: '当前模型每日额度不足', MODEL_NAME_COST_DAILY_BUDGET: '当前模型每日费用额度不足',
  MODEL_INPUT_TOO_LARGE: '模型输入过大，可调整后继续',
  MODEL_HTTP_ERROR: '旧接口错误缺少状态，继续将发起新模型请求',
  MODEL_RATE_LIMITED: '模型限流重试已暂停，可稍后继续',
  MODEL_SERVICE_UNAVAILABLE: '模型服务暂不可用，可稍后继续',
  MODEL_CONNECTION_FAILED: '模型连接失败，可稍后继续',
  MODEL_AUTHENTICATION_REJECTED: '模型鉴权或权限被拒绝',
  MODEL_QUOTA_EXHAUSTED: '模型服务方余额或额度不足',
  MODEL_REQUEST_REJECTED: '模型请求或配置不被接受',
  MODEL_OUTCOME_UNKNOWN: '模型是否已处理请求未知，未自动重发',
  MODEL_RESPONSE_INVALID: '模型返回格式无效，可继续修复',
  MODEL_RESPONSE_JSON_INVALID: '中转响应不是完整 JSON',
  MODEL_RESPONSE_ENVELOPE_INVALID: '中转响应结构无效',
  MODEL_RESPONSE_USAGE_INVALID: '模型用量字段无效',
  MODEL_RESPONSE_OUTPUT_LIMIT_EXCEEDED: '模型返回超出本次输出上限',
  MODEL_RESPONSE_CHOICES_INVALID: '中转响应缺少单条模型结果',
  MODEL_RESPONSE_OUTPUT_TRUNCATED: '模型输出被截断',
  MODEL_RESPONSE_FINISH_INVALID: '模型未正常结束输出',
  MODEL_RESPONSE_MESSAGE_INVALID: '模型消息格式无效',
  MODEL_RESPONSE_PROPOSAL_TOO_LARGE: '模型补丁超过大小上限',
  MODEL_RESPONSE_PROPOSAL_JSON_INVALID: '模型补丁不是完整 JSON',
  MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID: '模型补丁字段或类型不符合要求',
  MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID: '模型补丁内容不符合要求',
  MODEL_RESPONSE_REFUSED: '模型拒绝了本次请求',
  MODEL_RESPONSE_OUTPUT_FILTERED: '模型服务过滤了本次输出',
  LIVE_INSPECTION_POLICY_LIMITED: '联网检查受执行策略限制',
  LIVE_INSPECTION_RESPONSE_MISSING: '未取得完整源站响应',
  LIVE_INSPECTION_NETWORK_UNAVAILABLE: '源站网络暂不可用，未判定源码故障',
  LIVE_INSPECTION_HOST_CONFIGURATION_REQUIRED: 'AT 宿主解析未配置，未启动模型修复',
  MODEL_HOST_CONFIGURATION_REQUIRED: 'AT 宿主解析未配置，模型未调用，候选与历史保留',
  RUNTIME_HOST_CONFIGURATION_REQUIRED: 'AT 宿主解析未配置，候选已保留；补齐后可复验原候选',
  LIVE_INSPECTION_HTTP_ACCESS_DENIED: '源站仅返回 401/403，未取得业务页面，未启动模型修复',
  LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE: '源站返回访问挑战页，未判定解析故障，未启动模型修复',
  MODEL_SOURCE_ACCESS_CHALLENGE: '源站返回访问挑战页，模型未调用，原失败记录保留',
  LIVE_INSPECTION_SOURCE_ACCESS_DENIED: '源站返回 Cloudflare 明确拒绝页，需授权访问路径或已验证备用域，未启动模型修复',
  MODEL_SOURCE_ACCESS_DENIED: '源站返回 Cloudflare 明确拒绝页，模型未调用，原失败记录保留',
  LIVE_INSPECTION_METADATA_ONLY: '资料源声明不提供视频播放，当前播放画像不适用，未启动模型修复',
  RUNTIME_NETWORK_UNAVAILABLE: '联网验证暂停，候选已保留，未继续调用模型',
  LIVE_INSPECTION_EVIDENCE_MISSING: '缺少联网检查证据',
  LIVE_INSPECTION_INCOMPLETE: '所选联网环节尚未完成',
  MANAGED_CLEANUP_UNCONFIRMED: '隔离任务清理尚未确认',
  SOURCE_CREDENTIALS_REQUIRED: '待补充站点凭据',
  SOURCE_AUTHENTICATION_REJECTED: '站点凭据已失效，请更新',
  SOURCE_SETUP_DNS_NO_ADDRESS: '站点域名暂无可用地址，可继续发现并验证备用域',
  SOURCE_SETUP_DNS_FAILED: '站点域名解析失败，未启动模型修复',
  SOURCE_SETUP_NONPUBLIC_DNS: '域名未解析到可用公网地址，未启动模型修复',
  ROUND_LIMIT: '已到本次轮数上限，可调整后继续',
  REPEATED_FAILURE: '出现重复失败，可查看证据并调整轮数后继续'
}
const taskLabel = computed(() => task.value?.status === 'STOPPED' && task.value.stopCode?.startsWith('SOURCE_SETUP_')
  ? '准备失败，源未执行' : labels[task.value?.status || ''] || task.value?.status || '')
const canResume = computed(() => task.value?.status === 'STOPPED'
  && (task.value.stopCode === 'SOURCE_SETUP_DNS_NO_ADDRESS' && !task.value.repairId
    || !!task.value.repairId && (roundState.value?.freshness === 'current' && !!roundState.value.frozen.roundLimit
      && roundState.value.usedRounds < roundState.value.maxRounds
      && ['ROUND_LIMIT', 'REPEATED_FAILURE'].includes(task.value.stopCode ?? '')
    || task.value.stopCode === 'RUNTIME_NETWORK_UNAVAILABLE'
    || task.value.stopCode === 'MODEL_INPUT_TOO_LARGE'
    || ['MODEL_HTTP_ERROR', 'MODEL_RATE_LIMITED', 'MODEL_SERVICE_UNAVAILABLE', 'MODEL_CONNECTION_FAILED']
      .includes(task.value.stopCode || '')
    || task.value.stopCode?.startsWith('MODEL_RESPONSE_')
      && !['MODEL_RESPONSE_REFUSED', 'MODEL_RESPONSE_OUTPUT_FILTERED'].includes(task.value.stopCode)
    || task.value.stopCode?.startsWith('MODEL_') && task.value.stopCode.endsWith('_DAILY_BUDGET'))))
let disposed = false
let poll: ReturnType<typeof setTimeout> | undefined
async function action(work: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try { await work() } catch (e) {
    if (!disposed) error.value = e instanceof Error ? e.message : '维护任务读取失败'
  } finally { if (!disposed) busy.value = false }
}
async function refresh() {
  if (poll) clearTimeout(poll)
  const [config, permission, tasks] = await Promise.all([
    request<Connection>('/model/connection'),
    request<Consent>(`/sources/${props.sourceId}/model-consent`),
    request<Task[]>(`/sources/${props.sourceId}/maintenance`)
  ])
  if (disposed) return
  connection.value = config
  consent.value = permission
  const current = tasks.find(item => item.artifactSha256 === props.artifactSha256)
  if (current && (current.runId !== task.value?.runId || current.status !== task.value?.status
    || current.repairId !== task.value?.repairId)) emit('changed', current)
  task.value = current
  evidenceOffer.value = undefined
  roundState.value = undefined
  if (current?.repairId) {
    const rounds = await request<RoundState>(`/repairs/${current.repairId}`)
    if (disposed || task.value?.runId !== current.runId) return
    roundState.value = rounds
    maxRounds.value = rounds.maxRounds
    if (current.status === 'STOPPED' && ['REPEATED_FAILURE', 'ROUND_LIMIT'].includes(current.stopCode || '')) {
      const offer = await request<EvidenceOffer>(`/repairs/${current.repairId}/evidence-continuation`)
      if (disposed || task.value?.runId !== current.runId) return
      evidenceOffer.value = offer
    }
  }
  if (active.value) poll = setTimeout(() => action(refresh), 1500)
}
async function saveRoundLimit() {
  const repair = roundState.value
  if (!repair || busy.value || props.disabled || active.value || !Number.isInteger(maxRounds.value)
    || maxRounds.value < Math.max(1, repair.usedRounds)) return
  await action(async () => {
    await request(`/repairs/${repair.id}/round-limit`, {
      expectedFaultSha256: repair.faultSha256, expectedMaxRounds: repair.maxRounds, maxRounds: maxRounds.value
    }, 'PUT')
    await refresh()
  })
}
function reviewContinuation() {
  if (busy.value || props.disabled || !props.runtimeEnabled || !task.value
    || !evidenceOffer.value?.available || !connection.value) return
  const offer = evidenceOffer.value
  if (pendingContinuation.value?.runId !== task.value.runId
    || pendingContinuation.value.expectedEvidenceSha256 !== offer.evidenceSha256) {
    pendingContinuation.value = {
      runId: task.value.runId, sourceId: props.sourceId,
      expectedArtifactSha256: props.artifactSha256,
      expectedConfigurationSha256: connection.value.configurationSha256,
      expectedFaultSha256: offer.expectedFaultSha256,
      expectedCandidateSha256: offer.candidateSha256, expectedEvidenceSha256: offer.evidenceSha256,
      requestKey: crypto.randomUUID()
    }
  }
  confirmingContinuation.value = true
}
async function continueWithEvidence() {
  const pending = pendingContinuation.value
  if (!pending || busy.value || props.disabled || !props.runtimeEnabled
    || pending.sourceId !== props.sourceId || pending.runId !== task.value?.runId
    || pending.expectedArtifactSha256 !== props.artifactSha256) return
  await action(async () => {
    const { runId, sourceId: _sourceId, ...body } = pending
    await request(`/probe-runs/${runId}/maintenance/evidence-continuation`, body)
    if (disposed) return
    pendingContinuation.value = undefined
    confirmingContinuation.value = false
    await refresh()
  })
}
async function submit() {
  pending.value ??= {
    expectedArtifactSha256: props.artifactSha256,
    expectedConfigurationSha256: connection.value!.configurationSha256,
    requestKey: crypto.randomUUID(), maxRounds: maxRounds.value
  }
  const accepted = await request<Task>(`/sources/${props.sourceId}/maintenance`, pending.value)
  if (disposed) return
  pending.value = undefined
  task.value = accepted
  emit('changed', accepted)
  await refresh()
}
async function start() {
  if (busy.value || props.disabled || !props.runtimeEnabled || active.value
    || !Number.isInteger(maxRounds.value) || maxRounds.value < 1) return
  if (!connection.value?.enabled || !connection.value.autoSubmitEnabled) { emit('configure'); return }
  await action(async () => {
    if (consent.value?.artifactSha256 !== props.artifactSha256) {
      content.value = await sourceContent(props.sourceId, props.artifactSha256)
      if (disposed) return
      reviewed.value = false
      reviewing.value = true
    } else await submit()
  })
}
async function authorize() {
  if (!reviewed.value || busy.value) return
  await action(async () => {
    consent.value = await request<Consent>(`/sources/${props.sourceId}/model-consent`, {
      expectedArtifactSha256: consent.value?.artifactSha256 ?? null, artifactSha256: props.artifactSha256
    }, 'PUT')
    if (disposed) return
    reviewing.value = false
    content.value = ''
    await submit()
  })
}
async function cancel() {
  if (!task.value || busy.value) return
  await action(async () => {
    await request(`/probe-runs/${task.value!.runId}/maintenance/cancel`, {})
    await refresh()
  })
}
async function resume() {
  if (!task.value || busy.value || !canResume.value || !connection.value) return
  await action(async () => {
    pendingResume.value ??= {
      expectedArtifactSha256: props.artifactSha256,
      expectedConfigurationSha256: connection.value!.configurationSha256,
      requestKey: crypto.randomUUID()
    }
    await request(`/probe-runs/${task.value!.runId}/maintenance/resume`, pendingResume.value)
    pendingResume.value = undefined
    await refresh()
  })
}
onMounted(() => action(refresh))
onUnmounted(() => { disposed = true; if (poll) clearTimeout(poll) })
</script>

<template>
  <div class="maintenance-workflow">
    <div class="workflow-actions">
      <label for="maintenance-round-limit">修复轮数上限</label>
      <el-input-number id="maintenance-round-limit" v-model="maxRounds" :min="Math.max(1, roundState?.usedRounds ?? 0)"
        :precision="0" :disabled="disabled || busy || active" aria-label="自动修复轮数上限" style="width: 150px" />
      <el-tooltip v-if="roundState && !active" content="保存轮数上限，不清零历史或自动调用模型">
        <el-button :icon="Check" :disabled="disabled || busy || !Number.isInteger(maxRounds) || maxRounds === roundState.maxRounds"
          aria-label="保存自动修复轮数" @click="saveRoundLimit" />
      </el-tooltip>
      <el-button :icon="MagicStick" :disabled="disabled || busy || !runtimeEnabled || active" @click="start">
        联网巡检并修复
      </el-button>
      <el-button v-if="active" :disabled="busy" @click="cancel">停止自动维护</el-button>
      <el-button v-if="canResume" :disabled="busy || disabled || !runtimeEnabled" @click="resume">{{ task?.stopCode === 'RUNTIME_NETWORK_UNAVAILABLE' ? '复测现有候选' : '继续修复' }}</el-button>
      <el-button v-if="evidenceOffer?.available" :icon="MagicStick" :disabled="busy || disabled || !runtimeEnabled" @click="reviewContinuation">依据新证据继续</el-button>
      <span v-if="task" role="status">{{ taskLabel }}{{ task.stopCode ? `：${stopLabels[task.stopCode] || task.stopCode}` : '' }}</span>
    </div>
    <p v-if="error" class="workflow-error" role="alert">{{ error }}</p>
    <el-dialog v-model="confirmingContinuation" title="追加一次自动修复" width="min(480px, 94vw)" destroy-on-close>
      <p>已有 {{ evidenceOffer?.usedRounds }} 轮记录全部保留。向模型发送新取得的响应结构，追加 1 轮修复与联网复测，不采用或发布插件。</p>
      <template #footer>
        <el-button :disabled="busy" @click="confirmingContinuation = false">取消</el-button>
        <el-button type="primary" :icon="MagicStick" :disabled="busy" @click="continueWithEvidence">追加并继续</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="reviewing" title="授权本版本源码" width="min(760px, 94vw)" destroy-on-close>
      <p>模型：{{ connection?.model }}</p>
      <el-input :model-value="content" type="textarea" :rows="12" readonly aria-label="当前版本源码" />
      <el-checkbox v-model="reviewed" class="workflow-consent">同意将当前源码与联网故障证据发送给已配置模型，并自动生成候选及隔离复测</el-checkbox>
      <template #footer>
        <el-button @click="reviewing = false">取消</el-button>
        <el-button type="primary" :disabled="busy || !reviewed" @click="authorize">授权并开始</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workflow-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.workflow-actions span { font-size: 13px; overflow-wrap: anywhere; }
.workflow-error { color: #b42318; font-size: 13px; overflow-wrap: anywhere; }
.workflow-consent { height: auto; margin-top: 12px; white-space: normal; }
.workflow-consent :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
</style>
