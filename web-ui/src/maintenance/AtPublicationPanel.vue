<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Upload, Refresh, Switch, Check } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ candidateId: string; candidateSha256: string; disabled?: boolean }>()
interface Publication {
  id: string; mode: string; state: string; originalPluginId: number | null; publishedPluginId: number | null
  pluginName: string; version: string; errorCode: string | null
}
interface Target {
  available: boolean; blockedReason: string | null; defaultMode: string; bindingSha256: string | null
  originalPluginId: number | null; originalPluginName: string | null
  delivery: { pluginName: string; version: string; filename: string }
  publications: Publication[]
}
interface Pending {
  expectedCandidateSha256: string; expectedBindingSha256: string; mode: string; requestKey: string
  authorized: boolean; deliveryName: string
}
interface Runtime {
  state: string; reason: string | null; activationAvailable: boolean
  target: { configurationSha256: string; runtime: { workerGeneration: string | null }
    activations: { id: string; state: string }[] } | null
}
const visible = ref(false)
const busy = ref(false)
const target = ref<Target>()
const mode = ref('incremental')
const authorized = ref(false)
const deliveryName = ref('')
const pending = ref<Pending>()
const result = ref<Publication>()
const runtime = ref<Runtime>()
const runtimeBusy = ref(false)
const interruptPlayback = ref(false)
const pendingActivation = ref<Record<string, unknown>>()
const error = ref('')
const options = computed(() => [{ label: '增量新增', value: 'incremental' },
  { label: '替换原插件', value: 'replace', disabled: target.value?.originalPluginId == null }])
const reasons: Record<string, string> = {
  AT_PUBLISH_NEW_SOURCE_INCREMENTAL_ONLY: '新来源仅支持增量新增',
  AT_PUBLISH_CONNECTION_CHANGED: 'AT 连接已变化，请重新核对来源绑定',
  AT_PUBLISH_REAL_VALIDATION_REQUIRED: '候选尚未通过平台真实复测',
  AT_PUBLISH_ORIGINAL_CHANGED: 'AT 原插件已变化，请读取新版本后再维护',
  AT_PUBLISH_WRITE_PERMISSION_REQUIRED: '当前 AT 凭据没有发布权限',
  AT_PUBLISH_INCREMENTAL_ID_CONFLICT: '候选声明了原插件的固定 ID，请改用替换，或在平台生成独立 ID 的副本并复测',
  AT_PUBLISH_REGISTRATION_PENDING: '文件已提交，AT 尚未确认插件登记；请核对同一请求',
  AT_PUBLISH_NAME_CHANGED: '插件名称已变化，请重新读取后再修改',
  AT_PUBLISH_CHINESE_NAME_REQUIRED: '请填写站点中文名或域名音译名',
  AT_RUNTIME_INTERFACE_UNAVAILABLE: '此 AT 没有受控换版接口，已发布文件的实际装载状态尚未确认',
  AT_DEPLOYMENT_DISABLED: '此 AT 尚未启用受控换版',
  AT_PLAYBACK_INTERRUPTION_CONFIRMATION_REQUIRED: '旧版本已发起播放；确认允许中断后才能换版',
  AT_WORKER_CALL_BUSY: '插件仍有请求执行中，请稍后核对',
  AT_WORKER_GENERATION_CHANGED: '运行版本已变化，请重新核对',
  AT_DEPLOYMENT_PLUGIN_CHANGED: '插件源码或配置已变化，请重新核对'
}
const runtimeLabels: Record<string, string> = { ACTIVE: '已生效', RELOAD_REQUIRED: '旧版本仍在运行',
  NOT_LOADED: '尚未装载', RECOVERY_REQUIRED: '装载未完成，需继续处理',
  SAVED_VERSION_CHANGED: 'AT 中的文件已变化', UNOBSERVED: '生效状态未确认' }
const hasChineseName = computed(() => /\p{Script=Han}/u.test(
  deliveryName.value.trim().replace(/^(?:影视源|视频源)[_\s-]*/u, '')))
const canPublish = computed(() => !props.disabled && !busy.value && target.value?.available === true
  && (mode.value === 'incremental' || target.value.originalPluginId != null)
  && authorized.value && hasChineseName.value && result.value?.state !== 'PUBLISHED')
let revision = 0
let disposed = false
async function open() {
  if (busy.value || props.disabled) return
  const current = revision
  busy.value = true; error.value = ''; visible.value = true
  try {
    const value = await request<Target>(`/repair-candidates/${props.candidateId}/at-publications`)
    if (disposed || current !== revision) return
    target.value = value
    if (!pending.value) mode.value = value.defaultMode
    result.value = value.publications.find(item => item.state === 'PUBLISHED' && item.mode === mode.value)
    deliveryName.value = value.delivery.pluginName
    if (result.value) void inspectRuntime()
  } catch (reason) {
    if (!disposed && current === revision) error.value = reason instanceof Error ? reason.message : '读取发布条件失败'
  } finally { if (!disposed && current === revision) busy.value = false }
}
async function publish() {
  if (!canPublish.value || !target.value?.bindingSha256) return
  const current = revision
  const candidateId = props.candidateId
  busy.value = true; error.value = ''
  try {
    if (mode.value === 'replace' && !pending.value) {
      await ElMessageBox.confirm(`替换 AT 插件“${target.value.originalPluginName}”，保留原插件 ID。`, '确认替换', {
        type: 'warning', confirmButtonText: '替换原插件', cancelButtonText: '取消'
      })
    }
    if (disposed || current !== revision) return
    pending.value ??= { expectedCandidateSha256: props.candidateSha256,
      expectedBindingSha256: target.value.bindingSha256, mode: mode.value,
      requestKey: crypto.randomUUID(), authorized: true, deliveryName: deliveryName.value.trim() }
    const value = await request<Publication>(`/repair-candidates/${candidateId}/at-publications`, pending.value)
    if (disposed || current !== revision) return
    result.value = value
    if (value.state === 'PUBLISHED') {
      if (!target.value.publications.some(item => item.id === value.id)) target.value.publications.unshift(value)
      pending.value = undefined
      void inspectRuntime()
    }
  } catch (reason) {
    if (!disposed && current === revision && reason !== 'cancel' && reason !== 'close') {
      error.value = reason instanceof Error ? reason.message : '发布结果待核对'
      if (['AT_PUBLISH_INCREMENTAL_ID_CONFLICT', 'AT_PUBLISH_ORIGINAL_CHANGED',
        'AT_PUBLISH_BINDING_CHANGED', 'AT_PUBLISH_CANDIDATE_CHANGED'].includes(error.value))
        pending.value = undefined
    }
  } finally { if (!disposed && current === revision) busy.value = false }
}
async function saveName() {
  if (!result.value || !authorized.value || busy.value || runtimeBusy.value
    || !hasChineseName.value) return
  const current = revision
  busy.value = true; error.value = ''
  try {
    const value = await request<Publication>(
      `/repair-candidates/${props.candidateId}/at-publications/${result.value.id}/name`,
      { expectedCandidateSha256: props.candidateSha256, expectedPluginName: result.value.pluginName,
        deliveryName: deliveryName.value.trim(), authorized: true })
    if (disposed || current !== revision) return
    result.value = value
    const refreshed = await request<Target>(`/repair-candidates/${props.candidateId}/at-publications`)
    if (!disposed && current === revision) target.value = refreshed
  } catch (reason) {
    if (!disposed && current === revision) error.value = reason instanceof Error ? reason.message : '修改名称失败'
  } finally { if (!disposed && current === revision) busy.value = false }
}
async function inspectRuntime() {
  if (!result.value?.id || runtimeBusy.value) return
  const current = revision
  runtimeBusy.value = true; error.value = ''
  try {
    const value = await request<Runtime>(`/repair-candidates/${props.candidateId}/at-publications/${result.value.id}/runtime`)
    if (!disposed && current === revision) runtime.value = value
  } catch (reason) {
    if (!disposed && current === revision) error.value = reason instanceof Error ? reason.message : '读取生效状态失败'
  } finally { if (!disposed && current === revision) runtimeBusy.value = false }
}
async function activate() {
  if (!result.value?.id || !runtime.value?.activationAvailable || !runtime.value.target
    || runtimeBusy.value || busy.value || !authorized.value) return
  const current = revision
  const url = `/repair-candidates/${props.candidateId}/at-publications/${result.value.id}/activate`
  runtimeBusy.value = true; error.value = ''
  try {
    if (interruptPlayback.value && !pendingActivation.value)
      await ElMessageBox.confirm('允许中断此插件已发起的播放，并切换到已发布版本。', '确认换版', {
        type: 'warning', confirmButtonText: '切换版本', cancelButtonText: '取消'
      })
    if (disposed || current !== revision) return
    const recovering = runtime.value.target.activations.find(item => ['PREPARED', 'RECOVERY_REQUIRED'].includes(item.state))
    pendingActivation.value ??= { expectedConfigurationSha256: runtime.value.target.configurationSha256,
      expectedWorkerGeneration: runtime.value.target.runtime.workerGeneration, requestKey: crypto.randomUUID(),
      activationId: recovering?.id ?? null, interruptIssuedPlayback: interruptPlayback.value, authorized: true }
    const value = await request<Runtime>(url, pendingActivation.value)
    if (disposed || current !== revision) return
    runtime.value = value; pendingActivation.value = undefined
  } catch (reason) {
    if (!disposed && current === revision && reason !== 'cancel' && reason !== 'close') {
      error.value = reason instanceof Error ? reason.message : '换版结果待核对'
      if (['AT_WORKER_GENERATION_CHANGED', 'AT_DEPLOYMENT_PLUGIN_CHANGED',
        'AT_ACTIVATION_NOT_RESUMABLE'].includes(error.value)) pendingActivation.value = undefined
    }
  } finally { if (!disposed && current === revision) runtimeBusy.value = false }
}
watch(mode, () => {
  if (!pending.value) {
    result.value = target.value?.publications.find(item => item.state === 'PUBLISHED' && item.mode === mode.value)
    runtime.value = undefined; pendingActivation.value = undefined
    if (result.value) void inspectRuntime()
  }
})
watch(() => [props.candidateId, props.candidateSha256], () => {
  revision++; busy.value = false; visible.value = false; target.value = undefined
  pending.value = undefined; result.value = undefined; authorized.value = false; error.value = ''
  deliveryName.value = ''
  runtime.value = undefined; runtimeBusy.value = false; pendingActivation.value = undefined; interruptPlayback.value = false
}, { flush: 'sync' })
onUnmounted(() => { disposed = true; revision++ })
</script>

<template>
  <el-button :icon="Upload" :disabled="disabled || busy" @click="open">发布到 AT</el-button>
  <el-dialog v-model="visible" title="发布到 AT" width="min(560px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy && !runtimeBusy" :show-close="!busy && !runtimeBusy">
    <template v-if="target">
      <p>{{ target.delivery.pluginName }} · v{{ target.delivery.version }}</p>
      <el-form-item label="插件名称">
        <el-input v-model="deliveryName" maxlength="60" :disabled="busy || runtimeBusy || !!pending" />
        <el-button v-if="result?.state === 'PUBLISHED'" :icon="Check" :loading="busy"
          :disabled="!authorized || busy || runtimeBusy || !hasChineseName"
          @click="saveName">保存名称</el-button>
      </el-form-item>
      <p>{{ target.originalPluginId == null ? '新来源' : '原插件：' + target.originalPluginName }}
        <span v-if="target.originalPluginId"> · ID {{ target.originalPluginId }}</span></p>
      <el-segmented v-model="mode" :options="options" :disabled="busy || runtimeBusy || !!pending || !!pendingActivation || !target.available" block />
      <el-alert :title="target.originalPluginId == null ? '新增独立插件，不修改已有插件' : mode === 'incremental' ? '保留原插件，新增独立版本' : '覆盖原插件文件，保留原插件 ID'"
        :type="mode === 'incremental' ? 'info' : 'warning'" :closable="false" class="notice" />
      <el-checkbox v-model="authorized" :disabled="busy || !!pending || !target.available">
        允许将此已验证版本发布到当前 AT
      </el-checkbox>
      <el-alert v-if="!target.available" :title="reasons[target.blockedReason || ''] || target.blockedReason || '暂不可发布'"
        type="warning" :closable="false" />
      <p v-if="result?.state === 'PUBLISHED'" role="status">
        文件已发布：{{ result.pluginName }} · 插件 ID {{ result.publishedPluginId }}
      </p>
      <template v-if="result?.state === 'PUBLISHED'">
        <p role="status">生效状态：{{ runtime ? runtimeLabels[runtime.state] || runtime.state : '待核对' }}
          <el-button :icon="Refresh" text :loading="runtimeBusy" :disabled="busy || runtimeBusy" @click="inspectRuntime">核对</el-button>
        </p>
        <el-alert v-if="runtime?.reason" :title="reasons[runtime.reason] || runtime.reason" type="warning" :closable="false" />
        <template v-if="runtime?.activationAvailable">
          <el-checkbox v-model="interruptPlayback" :disabled="busy || runtimeBusy || !!pendingActivation">允许中断此插件已发起的播放</el-checkbox>
          <el-button :icon="Switch" :loading="runtimeBusy" :disabled="!authorized || busy || runtimeBusy" @click="activate">
            {{ pendingActivation ? '核对并继续同一换版' : runtime.state === 'RECOVERY_REQUIRED' ? '继续装载' : '切换并装载' }}
          </el-button>
        </template>
      </template>
      <details v-if="target.publications.length"><summary>发布记录</summary>
        <p v-for="item in target.publications" :key="item.id">
          {{ item.mode === 'replace' ? '替换' : '增量' }} ·
          {{ item.state === 'PUBLISHED' ? '已发布' : '待核对' }} · {{ item.pluginName }}
        </p>
      </details>
    </template>
    <el-alert v-if="error" :title="reasons[error] || error" type="error" :closable="false" />
    <template #footer>
      <el-button :disabled="busy || runtimeBusy" @click="visible = false">关闭</el-button>
      <el-button type="primary" :icon="Upload" :disabled="!canPublish" :loading="busy" @click="publish">
        {{ pending ? '核对并继续同一发布' : mode === 'replace' ? '替换原插件' : '增量发布' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.notice { margin: 16px 0 8px; }
.el-checkbox { height: auto; margin: 12px 0; white-space: normal; }
.el-checkbox :deep(.el-checkbox__label) { white-space: normal; }
p { overflow-wrap: anywhere; }
</style>
