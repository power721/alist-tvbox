<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { Aim } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ repairId: string; faultSha256: string; disabled?: boolean; saved?: unknown }>()
const emit = defineEmits<{ changed: []; 'busy-change': [value: boolean] }>()
interface Report { collectionStatus: string; requests: unknown[]; scripts: unknown[]; pauses: unknown[]
  targetRequestObserved: boolean; failures: string[]; cleanupConfirmed: boolean; previousReports?: unknown[]
  networkLocation?: string }
const ready = ref(false), busy = ref(false), error = ref('')
const pageUrl = ref(''), requestPattern = ref(''), recoverScripts = ref(true)
const report = ref<Report>()
let revision = 0
let disposed = false
async function capture() {
  if (busy.value || props.disabled || !ready.value) return
  const current = revision
  busy.value = true
  emit('busy-change', true)
  error.value = ''
  try {
    const value = await request<Report>(`/repairs/${props.repairId}/reverse-analysis`, {
      expectedFaultSha256: props.faultSha256, pageUrl: pageUrl.value,
      requestPattern: requestPattern.value, recoverScripts: recoverScripts.value
    })
    if (current !== revision) return
    report.value = value
    emit('changed')
  } catch (reason) { if (current === revision) error.value = reason instanceof Error ? reason.message : '脚本分析失败' }
  finally { if (current === revision) busy.value = false; emit('busy-change', false) }
}
watch(() => [props.repairId, props.faultSha256, props.saved], () => {
  revision++
  report.value = props.saved as Report | undefined
  pageUrl.value = requestPattern.value = error.value = ''
  busy.value = false
}, { immediate: true })
onMounted(async () => {
  try { const value = await request<{ ready: boolean }>('/reverse-analysis/readiness'); if (!disposed) ready.value = value.ready }
  catch { /* The optional executor does not block ordinary repair. */ }
})
onUnmounted(() => { disposed = true; revision++ })
</script>

<template>
  <details class="reverse-analysis">
    <summary>脚本加强分析</summary>
      <el-alert v-if="!ready" title="浏览器分析执行器未启用" type="info" :closable="false" />
      <el-form label-position="top" @submit.prevent="capture">
        <el-form-item label="目标页面"><el-input v-model="pageUrl" placeholder="默认使用当前来源页面" :disabled="busy || disabled" /></el-form-item>
        <el-form-item label="目标请求（可选）"><el-input v-model="requestPattern" placeholder="请求路径或 URL" :disabled="busy || disabled" /></el-form-item>
        <el-checkbox v-model="recoverScripts" :disabled="busy || disabled">恢复相关脚本</el-checkbox>
        <el-button :icon="Aim" :loading="busy" :disabled="busy || disabled || !ready" @click="capture">采集分析证据</el-button>
      </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p>{{ report.collectionStatus === 'observed' ? '已取得联网证据' : '未取得完整联网证据' }} · 请求 {{ report.requests.length }} · 脚本 {{ report.scripts.length }} · 暂停采样 {{ report.pauses.length }}</p>
      <p v-if="report.networkLocation">联网位置：{{ report.networkLocation === 'source_runner_host' ? 'NAS 执行端' : '本机' }}</p>
      <p v-if="report.failures.length">未完成项：{{ report.failures.join('、') }}</p>
      <p v-if="report.previousReports?.length">先前取证 {{ report.previousReports.length }} 次</p>
    </template>
  </details>
</template>

<style scoped>
.reverse-analysis { margin: 12px 0; padding: 12px 0; border-block: 1px solid #e4e7ed; }
.el-form { margin-top: 12px; max-width: 720px; }
.el-checkbox { margin-right: 16px; }
p { overflow-wrap: anywhere; line-height: 1.6; }
</style>
