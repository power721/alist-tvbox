<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { request } from './api'

interface ObservationRun {
  id: string; status: string; createdAt: string; finishedAt: string | null; executionMode: string
  scheduleSha256: string | null; currentPolicy: boolean; errorCode: string | null
  outcome: string; cleanup: string; comparedRunId?: string; regressedNodes: string[]
  requiredCoverage: { total: number; pass: number; fail: number; blocked: number; not_tested: number }
  inputs: { artifactSha256: string; sourceRevision: number | null }
  maintenance?: { status: string; repairId: string | null; stopCode: string | null }
}
interface Observation {
  sourceId: string; currentPolicySha256: string | null; windowStart: string; windowEnd: string
  historyComplete: boolean; observedUtcDays: number; limit: number
  totals: Record<string, number>; currentPolicyTotals: Record<string, number>
  runs: ObservationRun[]
}
const props = defineProps<{ sourceId: string; policySha256: string | null; refreshKey: number }>()
const emit = defineEmits<{ inspect: [runId: string] }>()
const observation = ref<Observation>()
const loading = ref(false)
const error = ref('')
let revision = 0
let disposed = false
const outcomes: Record<string, string> = {
  pending: '待结束', cancelled: '已取消', execution_failed: '执行失败',
  cleanup_unconfirmed: '清理未确认', declared_checks_passed: '声明检查通过',
  declared_checks_incomplete: '声明检查未完成'
}
const cleanupLabels: Record<string, string> = {
  confirmed: '已确认', unconfirmed: '未确认', not_observed: '无回执', not_applicable: '不适用'
}
const maintenanceLabels: Record<string, string> = {
  CHECKING: '巡检中', REPAIRING: '修复复测中', CHECKED: '未发现需修复故障',
  SUCCEEDED: '修复候选已验证，未发布', STOPPED: '修复已停止', CANCELLED: '已取消'
}
async function load() {
  const generation = ++revision
  const sourceId = props.sourceId
  observation.value = undefined
  error.value = ''
  loading.value = true
  try {
    const value = await request<Observation>(`/sources/${sourceId}/schedule/observations`)
    if (disposed || generation !== revision || sourceId !== props.sourceId) return
    if (value.sourceId !== sourceId || value.currentPolicySha256 !== props.policySha256)
      throw new Error('巡检策略已变化，请刷新巡检状态')
    observation.value = value
  } catch (e) {
    if (!disposed && generation === revision && sourceId === props.sourceId)
      error.value = e instanceof Error ? e.message : '巡检历史读取失败'
  } finally {
    if (!disposed && generation === revision) loading.value = false
  }
}
watch(() => [props.sourceId, props.policySha256, props.refreshKey], load, { immediate: true })
onUnmounted(() => { disposed = true; revision++ })
</script>

<template>
  <div class="schedule-observations">
    <div class="observation-heading">
      <h3>最近 14 天巡检记录</h3>
      <el-tooltip content="刷新巡检历史">
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新巡检历史" @click="load" />
      </el-tooltip>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="observation">
      <p>已投放 {{ observation.totals.attempts }} 次 · 有完成回执 {{ observation.observedUtcDays }} 个 UTC 日期 ·
        当前策略 {{ observation.currentPolicyTotals.attempts }} 次</p>
      <p>声明检查通过 {{ observation.totals.declared_checks_passed }} ·
        未完成 {{ observation.totals.declared_checks_incomplete }} ·
        执行失败 {{ observation.totals.execution_failed }} ·
        清理未确认 {{ observation.totals.cleanup_unconfirmed }} ·
        取消 {{ observation.totals.cancelled }} · 待结束 {{ observation.totals.pending }} ·
        发生节点退化 {{ observation.totals.regressions }} 次</p>
      <el-alert v-if="!observation.historyComplete" :title="`历史超过 ${observation.limit} 条，当前汇总仅覆盖所列记录`"
                type="warning" :closable="false" />
      <el-empty v-if="!observation.runs.length" description="观察窗口内尚无自动巡检" />
      <el-table v-else :data="observation.runs" size="small" max-height="440" class="observation-table">
        <el-table-column label="投放时间" min-width="156">
          <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="策略与版本" min-width="150">
          <template #default="{ row }">
            {{ row.currentPolicy ? '当前策略' : '历史策略' }} · 版本 {{ row.inputs.sourceRevision ?? '未绑定' }}
            <div class="sha">{{ row.inputs.artifactSha256.slice(0, 12) }} · {{ row.executionMode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="170">
          <template #default="{ row }">
            {{ outcomes[row.outcome] || row.status }}
            <div>必需项 {{ row.requiredCoverage.pass }}/{{ row.requiredCoverage.total }}</div>
            <div v-if="row.errorCode">{{ row.errorCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="清理" min-width="90">
          <template #default="{ row }">{{ cleanupLabels[row.cleanup] || row.cleanup }}</template>
        </el-table-column>
        <el-table-column label="自动修复" min-width="150">
          <template #default="{ row }">
            {{ row.maintenance ? maintenanceLabels[row.maintenance.status] || row.maintenance.status : '未启用' }}
            <div v-if="row.maintenance?.stopCode">{{ row.maintenance.stopCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="节点退化" min-width="100">
          <template #default="{ row }">
            {{ row.regressedNodes.length ? row.regressedNodes.join('、') : row.comparedRunId ? '未发现' : '无可比回执' }}
          </template>
        </el-table-column>
        <el-table-column label="报告" width="75">
          <template #default="{ row }"><el-button link type="primary" @click="emit('inspect', row.id)">查看</el-button></template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.schedule-observations { margin-top: 24px; border-top: 1px solid #e5ebef; padding-top: 8px; }
.observation-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.observation-table :deep(.cell) { white-space: normal; overflow-wrap: anywhere; line-height: 1.5; }
p { font-size: 13px; line-height: 1.6; }
</style>
