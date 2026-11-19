<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Tickets, Refresh, Check } from '@element-plus/icons-vue'
import { request } from './api'

interface Task {
  id: string; kind: string; ownerId: string; state: string; attempts: number; errorCode: string | null
  context: { candidateSha256: string; bindingSha256: string; mode: string; sourceName?: string; publishedPluginId?: number; newSource?: boolean }
}
const visible = ref(false), busy = ref(false), enabled = ref(false), error = ref('')
const tasks = ref<Task[]>([]), modes = ref<Record<string, string>>({})
const options = [{ label: '增量发布', value: 'incremental' }, { label: '替换发布', value: 'replace' }]
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '发布中', RETRY_WAIT: '等待核对重试',
  PUBLISHED: '已发布', BLOCKED: '发布受阻', SUPERSEDED: '已有更新版本' }
let timer: ReturnType<typeof setTimeout> | undefined
let revision = 0, disposed = false
async function refresh() {
  if (busy.value || disposed) return
  clearTimeout(timer)
  const current = revision
  busy.value = true; error.value = ''
  try {
    const [rows, policy] = await Promise.all([
      request<Task[]>('/automation/tasks'), request<{ enabled: boolean }>('/automation/publication-policy')])
    if (current !== revision || disposed) return
    tasks.value = rows.filter(row => row.kind === 'publication')
    enabled.value = policy.enabled
    for (const task of tasks.value) modes.value[task.id] ??= task.context.mode
  } catch (reason) {
    if (current === revision && !disposed) error.value = reason instanceof Error ? reason.message : '读取队列失败'
  } finally {
    if (current === revision && !disposed) {
      busy.value = false
      if (visible.value) timer = setTimeout(refresh, 5000)
    }
  }
}
function open() { visible.value = true; void refresh() }
function close() { visible.value = false; revision++; busy.value = false; clearTimeout(timer) }
async function saveMode(task: Task) {
  if (busy.value || task.attempts || task.state !== 'QUEUED') return
  if (task.context.newSource && modes.value[task.id] === 'replace') {
    error.value = '新来源仅支持增量新增'; return
  }
  clearTimeout(timer)
  const current = revision
  busy.value = true; error.value = ''
  try {
    const mode = modes.value[task.id] || 'incremental'
    if (mode === 'replace') await ElMessageBox.confirm('此队列项将覆盖 AT 原插件，保留原插件 ID。', '确认替换发布',
      { type: 'warning', confirmButtonText: '确认替换', cancelButtonText: '取消' })
    if (disposed || current !== revision) return
    await request(`/repair-candidates/${task.ownerId}/at-publications/queue`, {
      expectedCandidateSha256: task.context.candidateSha256, expectedBindingSha256: task.context.bindingSha256,
      mode, authorized: true })
  } catch (reason) {
    if (!disposed && current === revision && reason !== 'cancel' && reason !== 'close')
      error.value = reason instanceof Error ? reason.message : '保存队列失败'
  } finally {
    if (!disposed && current === revision) { busy.value = false; void refresh() }
  }
}
onUnmounted(() => { disposed = true; revision++; clearTimeout(timer) })
</script>

<template>
  <el-button :icon="Tickets" @click="open">发布队列</el-button>
  <el-dialog v-model="visible" title="发布队列" width="min(940px, 96vw)" @closed="close">
    <div class="queue-heading">
      <span>{{ enabled ? '自动发布已启用' : '自动发布未启用' }} · 默认增量发布</span>
      <el-button :icon="Refresh" :loading="busy" aria-label="刷新发布队列" title="刷新发布队列" @click="refresh" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="tasks" empty-text="暂无待发布或自动发布记录" max-height="480">
      <el-table-column label="来源" min-width="160">
        <template #default="{ row }">{{ row.context.sourceName || row.ownerId }}
          <small v-if="row.context.publishedPluginId"> · ID {{ row.context.publishedPluginId }}</small></template>
      </el-table-column>
      <el-table-column label="模式" width="165">
        <template #default="{ row }">
          <el-select v-model="modes[row.id]" :disabled="busy || row.attempts > 0 || row.state !== 'QUEUED'" aria-label="发布模式">
            <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value"
              :disabled="row.context.newSource && option.value === 'replace'" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="220">
        <template #default="{ row }">{{ states[row.state] || row.state }}<small v-if="row.errorCode"> · {{ row.errorCode }}</small></template>
      </el-table-column>
      <el-table-column width="72">
        <template #default="{ row }"><el-button :icon="Check" aria-label="保存发布模式" title="保存发布模式"
          :disabled="busy || row.attempts > 0 || row.state !== 'QUEUED' || modes[row.id] === row.context.mode"
          @click="saveMode(row)" /></template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.queue-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
small { overflow-wrap: anywhere; }
</style>
