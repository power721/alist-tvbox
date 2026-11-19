<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { Aim } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ sourceId: string; runId: string; artifactSha256: string; disabled?: boolean }>()
const emit = defineEmits<{ 'busy-change': [value: boolean] }>()
interface Report {
  networkLocation: string; cleanupConfirmed: boolean; failures: string[]
  targetPage?: { status: string; responseStatus?: number }
  domEvidence?: { frames: { controls: { text: string; visible: boolean }[] }[] }
}
const ready = ref(false), busy = ref(false), error = ref(''), report = ref<Report>()
let revision = 0
const statuses: Record<string, string> = { content_observed: '已取得原页内容', http_error: '原页返回错误',
  unavailable: '未取得有效原页', network_failed: '原页网络失败', challenge_pending: '原页存在访问挑战',
  challenge_unresolved: '原页挑战尚未解除', empty_document: '原页为空', incomplete: '原页响应不完整', redirect: '原页发生跳转' }
async function capture() {
  if (busy.value || props.disabled || !ready.value) return
  const current = revision
  busy.value = true
  error.value = ''
  emit('busy-change', true)
  try {
    const value = await request<Report>(`/sources/${props.sourceId}/page-diagnostics?runId=${props.runId}`, {
      expectedArtifactSha256: props.artifactSha256, pageUrl: ''
    })
    if (current === revision) report.value = value
  } catch (reason) { if (current === revision) error.value = reason instanceof Error ? reason.message : '原页诊断失败' }
  finally { if (current === revision) busy.value = false; emit('busy-change', false) }
}
watch(() => [props.sourceId, props.runId, props.artifactSha256], async () => {
  const current = ++revision
  report.value = undefined
  error.value = ''
  busy.value = false
  ready.value = false
  try {
    const state = await request<{ ready: boolean }>('/reverse-analysis/readiness')
    if (current !== revision) return
    ready.value = state.ready
    const run = await request<{ context?: { auxiliaryEvidence?: { pageDiagnostics?: Report } } }>(`/probe-runs/${props.runId}`)
    if (current === revision) report.value = run.context?.auxiliaryEvidence?.pageDiagnostics
  } catch { /* The optional diagnosis does not block ordinary maintenance. */ }
}, { immediate: true })
onUnmounted(() => { revision++ })
</script>

<template>
  <details class="page-diagnostics">
    <summary>原页访问诊断</summary>
    <el-button :icon="Aim" :loading="busy" :disabled="disabled || busy || !ready" @click="capture">{{ report ? '查看已存证据' : '诊断原页' }}</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p>{{ statuses[report.targetPage?.status || ''] || '原页状态待确认' }}<span v-if="report.targetPage?.responseStatus"> · HTTP {{ report.targetPage.responseStatus }}</span>
        · {{ report.networkLocation === 'source_runner_host' ? 'NAS 执行端' : '本机' }}</p>
      <p v-if="report.failures?.length">{{ report.failures.join('、') }}</p>
    </template>
  </details>
</template>

<style scoped>
.page-diagnostics { margin: 12px 0; padding: 12px 0; border-block: 1px solid #e4e7ed; }
.el-button { margin-top: 12px; }
p { overflow-wrap: anywhere; line-height: 1.6; }
</style>
