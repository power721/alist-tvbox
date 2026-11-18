<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from './api'

const props = defineProps<{ sourceId: string; artifactSha256: string }>()
interface MediaRun {
  id: string; artifactSha256: string; status: string; errorCode: string | null
  budget: unknown; issuedRequests: number
  freshness: string; staleInputs: string[]; result: {
    verdict: string; boundary: string; usage: { requests: number; retainedBodyBytes: number }
    nodes: { id: string; status: string; reason: string }[]
  } | null
}
interface MediaBudget {
  budgetDay: string | null; usedProbes: number; reservedRequests: number
  reservedBodyBytes: number; nextDueAt: string | null
}
const runs = ref<MediaRun[]>([])
const player = ref('')
const busy = ref(false)
const policy = ref<{ blockedReason: string; snapshot: unknown; budgetPolicy: unknown }>()
const globalBudget = ref<MediaBudget>()
const sourceBudget = ref<MediaBudget>()
const expanded = ref('')
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
async function refresh() {
  const [items, configuration, global, source] = await Promise.all([
    request<MediaRun[]>(`/sources/${props.sourceId}/media-probes`),
    request<{ blockedReason: string; snapshot: unknown; budgetPolicy: unknown }>('/media-policy'),
    request<MediaBudget>('/media-budget'),
    request<MediaBudget>(`/sources/${props.sourceId}/media-budget`)
  ])
  if (disposed) return
  runs.value = items
  policy.value = configuration
  globalBudget.value = global
  sourceBudget.value = source
  if (timer) clearTimeout(timer)
  if (items.some(item => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(item.status))) {
    timer = setTimeout(() => {
      refresh().catch(error => { if (!disposed) ElMessage.error(error.message) })
    }, 1500)
  }
}
async function action(work: () => Promise<void>) {
  busy.value = true
  try { await work() } catch (error) { ElMessage.error(error instanceof Error ? error.message : '媒体诊断失败') }
  finally { busy.value = false }
}
async function start() {
  let input: unknown
  try { input = JSON.parse(player.value) } catch { ElMessage.error('请输入有效的 player JSON'); return }
  await action(async () => {
    await request(`/sources/${props.sourceId}/media-probes`, {
      expectedArtifactSha256: props.artifactSha256, player: input
    })
    player.value = ''
    await refresh()
  })
}
async function cancel(id: string) {
  await action(async () => { await request(`/media-probes/${id}/cancel`, {}); await refresh() })
}
onMounted(() => action(refresh))
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer); player.value = '' })
</script>

<template>
  <div class="media-panel">
    <el-alert type="info" :closable="false" title="媒体诊断：人工提供的 player 输出">
      <p>本页检查有限媒体样本。输入尚未与受管源的实际取链调用绑定，结果不计入源健康、修复晋级或发布资格。</p>
      <p>地址与请求头仅在内存中使用，不保存到数据库或导出包；重启后不会自动重发。</p>
    </el-alert>
    <el-alert v-if="policy?.blockedReason" type="warning" :closable="false"
      :title="`当前网络执行不可用：${policy.blockedReason}`" />
    <p v-if="globalBudget">全局记账日 {{ globalBudget.budgetDay || '尚未预占' }}：
      {{ globalBudget.usedProbes }} 次任务，{{ globalBudget.reservedRequests }} 次预占请求，
      {{ globalBudget.reservedBodyBytes }} 字节预占响应体容量。</p>
    <p v-if="sourceBudget">本源记账日 {{ sourceBudget.budgetDay || '尚未预占' }}：
      {{ sourceBudget.usedProbes }} 次任务；下次最早接纳时间 {{ sourceBudget.nextDueAt || '尚未设置' }}。
      取消、失败和重启不退还额度；响应体容量不是网络流量。</p>
    <el-input v-model="player" type="textarea" :rows="5" :maxlength="32768" autocomplete="off"
      :spellcheck="false" placeholder='粘贴获准测试的直连 player JSON：{"parse":0,"jx":0,"playUrl":"","url":"…","header":{}}' />
    <div>
      <el-button type="primary" :loading="busy" :disabled="!player.trim() || !!policy?.blockedReason" @click="start">提交媒体诊断</el-button>
      <el-button :disabled="busy" @click="action(refresh)">刷新</el-button>
    </div>
    <details><summary>网络策略与限额</summary>
      <pre>{{ JSON.stringify(policy?.snapshot, null, 2) }}</pre>
      <pre>{{ JSON.stringify(policy?.budgetPolicy, null, 2) }}</pre>
    </details>
    <el-empty v-if="!runs.length" description="暂无媒体诊断记录" />
    <div v-for="run in runs" :key="run.id" class="media-run">
      <p><strong>{{ run.status }}</strong> · {{ run.result?.verdict || run.errorCode || '等待执行' }}
        <el-tag v-if="run.freshness !== 'current'" type="warning">输入已变化：{{ run.staleInputs.join('、') }}</el-tag>
      </p>
      <p>源码 {{ run.artifactSha256.slice(0, 12) }} · 人工输入辅助证据</p>
      <p>已记账请求许可 {{ run.issuedRequests }} 次；预算记录 {{ run.budget ? '已冻结' : '历史未知' }}</p>
      <el-button v-if="['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status)" :disabled="busy"
        @click="cancel(run.id)">取消</el-button>
      <el-button @click="expanded = expanded === run.id ? '' : run.id">查看证据</el-button>
      <a :href="`/api/plugin-maintenance/media-probes/${run.id}/export`">下载证据包</a>
      <template v-if="run.result">
        <p>请求 {{ run.result.usage.requests }} 次；保留响应体 {{ run.result.usage.retainedBodyBytes }} 字节</p>
        <el-table :data="run.result.nodes" size="small">
          <el-table-column prop="id" label="节点" width="80" />
          <el-table-column prop="status" label="结果" width="130" />
          <el-table-column prop="reason" label="判据" />
        </el-table>
      </template>
      <pre v-if="expanded === run.id">{{ JSON.stringify(run, null, 2) }}</pre>
    </div>
  </div>
</template>

<style scoped>
.media-panel { display: grid; gap: 16px; }
.media-run { border-top: 1px solid var(--el-border-color); padding-top: 12px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
a { margin-left: 12px; }
</style>
