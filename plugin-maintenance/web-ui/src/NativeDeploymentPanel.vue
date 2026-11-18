<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Refresh, RefreshLeft } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ planId: string; frozenSha256: string; baselineSha256: string; eligible: boolean }>()
interface Deployment {
  id: string; planId: string; state: string; loadedGeneration: string | null; errorCode: string | null
}
interface Target {
  enabled: boolean; pluginId: number; pluginSha256: string
  runtime: { loadState: string; workerGeneration: string | null }
  deployments: Deployment[]
}
const target = ref<Target>()
const result = ref<Deployment>()
const error = ref('')
const busy = ref(false)
const interruptPlayback = ref(false)
const blockingStates = ['DEPLOYED', 'PREPARED', 'SAVED', 'ROLLING_BACK', 'RECOVERY_REQUIRED']
const active = computed(() => target.value?.deployments.find(row => blockingStates.includes(row.state)))
const canDeploy = computed(() => target.value?.enabled && props.eligible && !active.value
  && target.value.pluginSha256 === props.baselineSha256
  && ['LOADED', 'NOT_LOADED'].includes(target.value.runtime.loadState))
const labels: Record<string, string> = { DEPLOYED: '已切换', ROLLED_BACK: '已恢复原版本',
  REJECTED: '切换未执行', PREPARED: '切换待恢复', SAVED: '装载待恢复',
  ROLLING_BACK: '恢复待确认', RECOVERY_REQUIRED: '需要恢复原版本' }
let disposed = false
let revision = 0
let reads = 0
let pending: { planId: string; expectedFrozenSha256: string; expectedPluginSha256: string
  expectedWorkerGeneration: string | null; interruptIssuedPlayback: boolean; requestKey: string } | undefined

async function refresh() {
  const current = revision
  const sequence = ++reads
  const value = await request<Target>(`/at-native/release-plans/${props.planId}/target`)
  if (disposed || current !== revision || sequence !== reads) return
  target.value = value
}
async function action(work: () => Promise<void>) {
  if (busy.value) return
  const current = revision
  busy.value = true
  error.value = ''
  try { await work() }
  catch (e) {
    if (disposed || current !== revision) return
    error.value = e instanceof Error ? e.message : 'AT 操作失败'
    ElMessage.error(error.value)
  } finally { busy.value = false }
}
async function toggle(event: Event) {
  if ((event.target as HTMLDetailsElement).open && !target.value) await action(refresh)
}
async function transition(rollback: boolean) {
  const snapshot = target.value
  const row = active.value
  if (busy.value || !snapshot?.enabled || (rollback ? !row : !canDeploy.value)) return
  const current = revision
  const generation = snapshot.runtime.workerGeneration
  const interrupt = interruptPlayback.value
  try {
    await ElMessageBox.confirm(rollback ? '恢复当前 AT 插件的原版本？'
      : '将此修复版本应用到当前 AT？', rollback ? '恢复原版本' : '应用到当前 AT', { type: 'warning' })
  } catch { return }
  if (disposed || current !== revision || busy.value) return
  await action(async () => {
    if (!rollback && !pending) {
      pending = { planId: props.planId, expectedFrozenSha256: props.frozenSha256,
        expectedPluginSha256: props.baselineSha256, expectedWorkerGeneration: generation,
        interruptIssuedPlayback: interrupt, requestKey: crypto.randomUUID() }
    }
    try {
      const value = await request<Deployment>(rollback
        ? `/at-native/deployments/${row!.id}/rollback`
        : `/at-native/plugins/${snapshot.pluginId}/deployments`, rollback
        ? { expectedWorkerGeneration: generation, interruptIssuedPlayback: interrupt } : pending)
      if (disposed || current !== revision) return
      result.value = value
      pending = undefined
      // Keep a confirmed result visible even if the following read fails.
      target.value = { ...snapshot, runtime: { ...snapshot.runtime, workerGeneration: value.loadedGeneration },
        deployments: [value, ...snapshot.deployments.filter(item => item.id !== value.id)] }
    } catch (e) {
      // A lost write response is resolved by reading, never by automatically resending it.
      if (!disposed && current === revision) await refresh().catch(() => {})
      throw e
    }
    if (!disposed && current === revision) await refresh()
  })
}
watch(() => props.planId, () => {
  revision++; reads++; target.value = undefined; result.value = undefined; pending = undefined
  interruptPlayback.value = false; error.value = ''
}, { flush: 'sync' })
onUnmounted(() => { disposed = true; revision++; reads++ })
</script>

<template>
  <details class="native-deployment" @toggle="toggle">
    <summary>当前 AT 部署</summary>
    <div class="native-actions">
      <el-tooltip content="刷新 AT 状态">
        <el-button :icon="Refresh" :disabled="busy" aria-label="刷新 AT 状态" @click="action(refresh)" />
      </el-tooltip>
      <template v-if="target">
        <span>插件 {{ target.pluginId }}</span>
        <el-tag v-if="!target.enabled" type="info">部署未启用</el-tag>
        <el-tag v-else-if="active" :type="active.state === 'DEPLOYED' ? 'success' : 'warning'">
          {{ labels[active.state] || active.state }}
        </el-tag>
        <el-button v-if="!active" :icon="Check" type="primary" :disabled="busy || !canDeploy" @click="transition(false)">
          应用到当前 AT
        </el-button>
        <el-button v-else :icon="RefreshLeft" :disabled="busy || !target.enabled" @click="transition(true)">
          恢复原版本
        </el-button>
      </template>
    </div>
    <el-checkbox v-if="target?.enabled" v-model="interruptPlayback" :disabled="busy">
      允许中断已生成的播放链接
    </el-checkbox>
    <p v-if="result" role="status">{{ labels[result.state] || result.state }}<span v-if="result.errorCode"> · {{ result.errorCode }}</span></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <details v-if="target"><summary>装载身份与操作记录</summary>
      <p>{{ target.runtime.loadState }} · <code>{{ target.runtime.workerGeneration || '无运行代次' }}</code></p>
      <p v-for="row in target.deployments" :key="row.id">
        {{ labels[row.state] || row.state }} · <code>{{ row.id }}</code>
        <span v-if="row.errorCode"> · {{ row.errorCode }}</span>
      </p>
    </details>
  </details>
</template>

<style scoped>
.native-deployment { margin-top: 12px; border-top: 1px solid var(--el-border-color); padding-top: 12px; }
.native-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
code, p { overflow-wrap: anywhere; }
.el-checkbox { height: auto; white-space: normal; }
</style>
