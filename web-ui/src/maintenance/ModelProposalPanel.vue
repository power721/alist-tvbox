<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowDown, Check, Close, Document, Refresh, Upload, VideoPlay } from '@element-plus/icons-vue'
import { request, sourceContent } from './api'
import ReverseAnalysisPanel from './ReverseAnalysisPanel.vue'

interface Repair {
  id: string; baselineArtifactSha256: string; faultSha256: string; status: string; freshness: string
  stopCode?: string | null
  frozen: { baseline?: { context?: unknown }; reverseAnalysis?: unknown }
}
interface Connection { enabled: boolean; model: string; configurationSha256: string; maxCompletionTokens: number
  autoSubmitEnabled: boolean }
interface AutomaticRepair { status: string; stopCode: string | null; enhancedDiagnostics?: boolean }
interface Consent { sourceId: string; artifactSha256: string | null }
interface ModelCall {
  id: string; requestKey: string; attemptNumber: number; modelName: string; status: string
  errorCode: string | null; proposal: unknown; proposalSha256: string | null
  candidateId: string | null; submissionStatus: string; submissionErrorCode: string | null
  usage: unknown; enhancedDiagnostics?: boolean
  responseDiagnostics?: { stage: string; violations?: { field: string; constraint: string }[]
    httpStatus?: number; failureCategory?: string; retryScheduled?: boolean; nextRetryAt?: string
    pauseReason?: string; maxTransportAttempts?: number; transportAttempts?: { stage: string }[] } | null
}
const props = defineProps<{ sourceId: string; artifactSha256: string; repair: Repair
  disabled: boolean; runtimeAvailable?: boolean }>()
const emit = defineEmits<{ changed: []; configure: []
  'submitted-for-validation': [{ sourceId: string; artifactSha256: string; repairId: string; candidateId: string }] }>()
const connection = ref<Connection>()
const consent = ref<Consent>()
const calls = ref<ModelCall[]>([])
const automatic = ref<AutomaticRepair>()
const busy = ref(false)
const error = ref('')
const reviewVisible = ref(false)
const requestVisible = ref(false)
const reviewed = ref(false)
const content = ref('')
const enhancedDiagnostics = ref(false)
const reverseBusy = ref(false)
const pendingRequest = ref<{ expectedFaultSha256: string; expectedConfigurationSha256: string
  requestKey: string; autoSubmit: false; enhancedDiagnostics: boolean }>()
const current = computed(() => props.repair.freshness === 'current'
  && props.repair.baselineArtifactSha256 === props.artifactSha256)
const authorized = computed(() => consent.value?.artifactSha256 === props.repair.baselineArtifactSha256)
const accepting = computed(() => current.value && props.repair.status === 'AWAITING_PROPOSAL')
const canAnalyze = computed(() => current.value && (props.repair.status === 'AWAITING_PROPOSAL'
  || props.repair.status === 'STOPPED' && ['REPEATED_FAILURE', 'ROUND_LIMIT'].includes(props.repair.stopCode || '')))
const active = (call: ModelCall) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(call.status)
const canRequest = computed(() => accepting.value && authorized.value && connection.value?.enabled
  && !reverseBusy.value && automatic.value?.status !== 'ACTIVE' && !calls.value.some(active))
const labels: Record<string, string> = {
  QUEUED: '等待模型', RUNNING: '模型请求中', CANCELLING: '正在取消',
  PROPOSED: '建议待审阅', CANCELLED: '已取消', FAILED: '请求失败',
  INTERRUPTED: '已中断，外部结果可能未知', REJECTED: '请求被拒绝'
}
function proposalText(proposal: unknown, key: string) {
  if (!proposal || typeof proposal !== 'object') return ''
  const value = (proposal as Record<string, unknown>)[key]
  return typeof value === 'string' ? value : ''
}
function responseStatus(call: ModelCall) {
  const detail = call.responseDiagnostics
  if (!detail?.failureCategory && !detail?.transportAttempts?.length) return ''
  const reasons: Record<string, string> = {
    authentication: '鉴权或权限被拒绝', quota: '服务方余额或额度不足', request: '请求或模型配置不被接受',
    rate_limit: '服务方限流', temporary_service: '服务暂时不可用', connection: '连接失败',
    tls: 'TLS 连接失败', response_size: '返回内容超过上限', outcome_unknown: '请求是否已处理未知',
    redirect: '服务方返回重定向', unclassified: '未识别的接口错误'
  }
  const parts = [reasons[detail.failureCategory || ''] || '模型返回已记录']
  if (detail.httpStatus) parts.push(`HTTP ${detail.httpStatus}`)
  if (detail.transportAttempts?.length) parts.push(`已传输 ${detail.transportAttempts.length} 次`)
  if (detail.retryScheduled && call.status === 'QUEUED')
    parts.push(`等待重试，最早 ${new Date(detail.nextRetryAt!).toLocaleString('zh-CN')}`)
  else if (call.status === 'RUNNING') parts.push('请求中')
  else if (['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(call.status))
    parts.push(detail.pauseReason === 'MODEL_TRANSPORT_RETRIES_EXHAUSTED' ? '已达重试上限，暂停' : '已暂停自动重试')
  return parts.join(' · ')
}
let disposed = false
let poll: ReturnType<typeof setTimeout> | undefined
let revision = 0
async function action(work: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try { await work() } catch (e) {
    if (!disposed) error.value = e instanceof Error ? e.message : '模型操作失败'
  } finally { if (!disposed) busy.value = false }
}
async function refresh() {
  if (poll) clearTimeout(poll)
  const version = ++revision
  const sourceId = props.sourceId, artifactSha256 = props.artifactSha256, repairId = props.repair.id
  const [config, permission, items, task] = await Promise.all([
    request<Connection>('/model/connection'),
    request<Consent>(`/sources/${props.sourceId}/model-consent`),
    request<ModelCall[]>(`/repairs/${props.repair.id}/model-calls`),
    request<AutomaticRepair>(`/repairs/${props.repair.id}/automatic`)
  ])
  if (disposed || version !== revision || sourceId !== props.sourceId
    || artifactSha256 !== props.artifactSha256 || repairId !== props.repair.id) return
  connection.value = config
  consent.value = permission
  calls.value = items
  const finished = automatic.value?.status === 'ACTIVE' && task.status !== 'ACTIVE'
  automatic.value = task
  if (task.status === 'ACTIVE') enhancedDiagnostics.value = task.enhancedDiagnostics === true
  if (items.some(call => call.requestKey === pendingRequest.value?.requestKey)) pendingRequest.value = undefined
  if (items.some(active) || task.status === 'ACTIVE') poll = setTimeout(() => action(refresh), 1000)
  if (finished) emit('changed')
}
async function startAutomatic() {
  if (busy.value || props.disabled || !props.runtimeAvailable || !canRequest.value
    || !connection.value?.autoSubmitEnabled) return
  const sourceId = props.sourceId, artifactSha256 = props.artifactSha256, repairId = props.repair.id
  await action(async () => {
    await request(`/repairs/${props.repair.id}/automatic`, {
      expectedFaultSha256: props.repair.faultSha256,
      expectedConfigurationSha256: connection.value!.configurationSha256,
      requestKey: crypto.randomUUID(), autoSubmit: true, enhancedDiagnostics: enhancedDiagnostics.value
    })
    if (disposed || sourceId !== props.sourceId || artifactSha256 !== props.artifactSha256
      || repairId !== props.repair.id) return
    await refresh()
    emit('changed')
  })
}
async function cancelAutomatic() {
  await action(async () => {
    await request(`/repairs/${props.repair.id}/automatic/cancel`, {})
    await refresh()
    emit('changed')
  })
}
async function review() {
  await action(async () => {
    const sha = props.repair.baselineArtifactSha256
    const source = await sourceContent(props.sourceId, sha)
    if (disposed || sha !== props.artifactSha256 || !current.value) return
    content.value = source
    reviewed.value = false
    reviewVisible.value = true
  })
}
async function authorize() {
  if (!current.value || !reviewed.value) return
  await action(async () => {
    consent.value = await request<Consent>(`/sources/${props.sourceId}/model-consent`, {
      expectedArtifactSha256: consent.value?.artifactSha256 ?? null,
      artifactSha256: props.repair.baselineArtifactSha256
    }, 'PUT')
    reviewVisible.value = false
    content.value = ''
  })
}
async function revoke() {
  await action(async () => {
    consent.value = await request<Consent>(`/sources/${props.sourceId}/model-consent`, {
      expectedArtifactSha256: consent.value?.artifactSha256 ?? null, artifactSha256: null
    }, 'PUT')
    await refresh()
  })
}
async function generate() {
  if (!canRequest.value) return
  await action(async () => {
    // Retain the idempotency key after a lost response; retries must not create a second charge.
    pendingRequest.value ??= {
      expectedFaultSha256: props.repair.faultSha256,
      expectedConfigurationSha256: connection.value!.configurationSha256,
      requestKey: crypto.randomUUID(), autoSubmit: false, enhancedDiagnostics: enhancedDiagnostics.value
    }
    await request(`/repairs/${props.repair.id}/model-calls`, pendingRequest.value)
    if (disposed) return
    requestVisible.value = false
    await refresh()
  })
}
async function submit(call: ModelCall, retest = false) {
  if (busy.value || props.disabled || !accepting.value || !authorized.value || !call.proposalSha256
    || (retest && !props.runtimeAvailable)) return
  const sourceId = props.sourceId, artifactSha256 = props.artifactSha256, repairId = props.repair.id
  await action(async () => {
    const candidate = await request<{ id: string; repairId: string }>(
      `/model-calls/${call.id}/submit`, { expectedProposalSha256: call.proposalSha256 })
    if (disposed || sourceId !== props.sourceId || artifactSha256 !== props.artifactSha256
      || repairId !== props.repair.id || !current.value) return
    await refresh()
    if (disposed || sourceId !== props.sourceId || artifactSha256 !== props.artifactSha256
      || repairId !== props.repair.id || !current.value) return
    if (retest && candidate.id && candidate.repairId === repairId)
      emit('submitted-for-validation', { sourceId, artifactSha256, repairId, candidateId: candidate.id })
    else emit('changed')
  })
}
async function cancel(call: ModelCall) {
  await action(async () => { await request(`/model-calls/${call.id}/cancel`, {}); await refresh() })
}
watch(() => [props.artifactSha256, props.repair.freshness], () => {
  reviewVisible.value = false
  requestVisible.value = false
  reviewed.value = false
  content.value = ''
})
onMounted(() => action(refresh))
onUnmounted(() => { disposed = true; revision++; if (poll) clearTimeout(poll) })
</script>

<template>
  <section class="model-proposals" aria-label="模型修复建议">
    <div class="model-actions">
      <strong>大模型修复</strong>
      <el-tag :type="connection?.enabled ? 'success' : 'info'">
        {{ connection ? connection.enabled ? connection.model : '模型未启用' : '模型状态未读取' }}
      </el-tag>
      <el-tag :type="authorized ? 'success' : 'warning'">{{ authorized ? '源码已授权' : '等待源码授权' }}</el-tag>
      <el-tooltip content="刷新模型状态"><el-button :icon="Refresh" circle aria-label="刷新模型状态"
        :disabled="busy || disabled" @click="action(refresh)" /></el-tooltip>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <div class="model-actions">
      <el-switch v-model="enhancedDiagnostics" active-text="加强分析" aria-label="加强分析"
        :disabled="busy || disabled || automatic?.status === 'ACTIVE' || !!pendingRequest" />
      <el-button v-if="automatic?.status !== 'ACTIVE'" type="primary" :icon="VideoPlay"
        :disabled="busy || disabled || !canRequest || !connection?.autoSubmitEnabled || !runtimeAvailable"
        @click="startAutomatic">自动修复</el-button>
      <el-button v-else :icon="Close" :disabled="busy || disabled" @click="cancelAutomatic">停止自动修复</el-button>
      <el-button v-if="connection && !connection.enabled" :disabled="busy || disabled" @click="emit('configure')">配置模型中转站</el-button>
      <el-button v-if="!authorized" type="primary" :icon="Document" :disabled="busy || disabled || !current" @click="review">确认源码授权</el-button>
      <el-button :type="authorized ? 'primary' : 'default'" :icon="Upload" :disabled="busy || disabled || !canRequest"
                 @click="requestVisible = true">生成修复建议</el-button>
      <el-tooltip v-if="consent?.artifactSha256" content="撤销源码授权">
        <el-button :icon="Close" :disabled="busy || disabled" aria-label="撤销源码授权" @click="revoke" />
      </el-tooltip>
    </div>
    <ReverseAnalysisPanel :key="repair.id" :repair-id="repair.id" :fault-sha256="repair.faultSha256"
      :saved="repair.frozen.reverseAnalysis" :disabled="busy || disabled || !canAnalyze || automatic?.status === 'ACTIVE' || calls.some(active)"
      @changed="emit('changed')" @busy-change="reverseBusy = $event" />
    <p v-if="automatic && automatic.status !== 'DISABLED'" role="status">
      {{ ({ ACTIVE: '自动修复中', SUCCEEDED: '修复复测通过', STOPPED: '自动修复已停止',
        CANCELLED: '自动修复已取消', STATIC_ONLY: '代码检查通过，尚未验证运行' } as Record<string, string>)[automatic.status] || automatic.status }}
      <span v-if="automatic.stopCode"> · {{ automatic.stopCode }}</span>
    </p>
    <p v-if="pendingRequest" role="status">请求结果尚未确认；本次重试保留原请求标识。</p>
    <article v-for="call in calls" :key="call.id" class="model-call">
      <div class="model-actions">
        <strong>第 {{ call.attemptNumber }} 次 · {{ labels[call.status] || call.status }}</strong>
        <span>{{ call.modelName }}</span>
        <el-tag v-if="call.enhancedDiagnostics" type="info">加强分析</el-tag>
        <el-button v-if="active(call)" :icon="Close" :disabled="busy || disabled || call.status === 'CANCELLING'"
          @click="cancel(call)">取消模型请求</el-button>
        <template v-if="call.status === 'PROPOSED' && !call.candidateId">
          <el-button :icon="Check" :disabled="busy || disabled || !accepting || !authorized || !call.proposalSha256"
            type="primary" @click="submit(call, !!runtimeAvailable)">
            {{ runtimeAvailable ? '检查并复测修复版本' : '检查并保存修复版本' }}
          </el-button>
          <el-dropdown v-if="runtimeAvailable" trigger="click">
            <el-button :icon="ArrowDown" aria-label="候选保存选项"
              :disabled="busy || disabled || !accepting || !authorized || !call.proposalSha256" />
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item :disabled="busy || disabled" @click="submit(call)">仅检查代码并保存</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </template>
      </div>
      <p v-if="proposalText(call.proposal, 'diagnosis')">{{ proposalText(call.proposal, 'diagnosis') }}</p>
      <p v-if="proposalText(call.proposal, 'expectedEffect')">{{ proposalText(call.proposal, 'expectedEffect') }}</p>
      <p v-if="call.errorCode" role="alert">{{ call.errorCode }}</p>
      <p v-if="responseStatus(call)" role="status">{{ responseStatus(call) }}</p>
      <p v-if="call.candidateId">修复版本已保存</p>
      <p v-if="call.submissionErrorCode">{{ call.submissionErrorCode }}</p>
      <details><summary>补丁与调用详情</summary>
        <p>模型来源 <code>{{ call.id }}</code></p>
        <p v-if="call.responseDiagnostics?.transportAttempts?.length">接口传输记录</p>
        <ol v-if="call.responseDiagnostics?.transportAttempts?.length">
          <li v-for="(attempt, index) in call.responseDiagnostics.transportAttempts" :key="index"><code>{{ attempt.stage }}</code></li>
        </ol>
        <pre v-if="call.proposal">{{ JSON.stringify(call.proposal, null, 2) }}</pre>
        <div v-if="call.responseDiagnostics?.violations?.length">
          <p>模型返回校验</p>
          <ul><li v-for="(item, index) in call.responseDiagnostics.violations" :key="index">
            <code>{{ item.field }}</code>：{{ ({ NotNull: '缺少值', NotBlank: '不能为空', NotEmpty: '不能为空',
              Size: '数量或长度不符合要求', Pattern: '格式不符合要求', Min: '小于允许值', Max: '超过允许值',
              Localized: '缺少中文名称' } as Record<string, string>)[item.constraint] || item.constraint }}
          </li></ul>
        </div>
        <details v-if="call.usage"><summary>本次服务方用量</summary><pre>{{ JSON.stringify(call.usage, null, 2) }}</pre></details>
      </details>
    </article>
    <el-dialog v-model="reviewVisible" title="源码发送授权" width="min(760px, 94vw)" append-to-body
      :close-on-click-modal="false" @closed="content = ''; reviewed = false">
      <p>完整源码及冻结引用资料可能包含私密信息。授权仅绑定此源码 SHA；请求将发往管理员配置的模型端点。</p>
      <p class="model-sha"><code>{{ repair.baselineArtifactSha256 }}</code></p>
      <details open><summary>完整基线源码</summary><pre class="model-preview">{{ content }}</pre></details>
      <details><summary>冻结引用资料</summary><pre class="model-preview">{{ JSON.stringify(repair.frozen.baseline?.context, null, 2) }}</pre></details>
      <el-checkbox v-model="reviewed" class="model-consent">已审阅源码与引用资料，同意发送这些内容</el-checkbox>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :icon="Check" :disabled="busy || disabled || !current || !reviewed"
          @click="authorize">授权此源码版本</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="requestVisible" title="确认请求模型建议" width="min(580px, 94vw)" append-to-body
      :close-on-click-modal="false">
      <p>模型：{{ connection?.model }} · 最大输出 {{ connection?.maxCompletionTokens }} tokens</p>
      <p class="model-sha">源码 <code>{{ repair.baselineArtifactSha256 }}</code></p>
      <p>本次将发送已授权源码和冻结引用资料，并预占预算。取消或失败不返还预占；不会自动提交候选、复测或发布。</p>
      <template #footer>
        <el-button @click="requestVisible = false">取消</el-button>
        <el-button type="primary" :disabled="busy || disabled || !canRequest" @click="generate">确认请求</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.model-proposals { border-top: 1px solid #dcdfe6; border-bottom: 1px solid #dcdfe6; margin: 16px 0; padding: 12px 0; }
.model-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0; }
.model-actions .el-button { margin-left: 0; }
.model-call { border-top: 1px solid #e4e7ed; padding: 8px 0; }
code, .model-sha { overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow: auto; }
.model-preview { background: #f5f7fa; padding: 12px; }
.model-consent { height: auto; white-space: normal; }
.model-consent :deep(.el-checkbox__label) { white-space: normal; }
details { margin: 10px 0; }
</style>
