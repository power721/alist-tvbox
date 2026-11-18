<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { request } from './api'

const props = defineProps<{ sourceId?: string }>()
interface Budget {
  budgetDay: string | null; usedCalls: number; reservedInputBytes: number; reservedOutputTokens: number
  reservedCostUsd: string | null
}
interface SourceBudget extends Budget {
  dailyCallLimit: number; dailyInputBytes: number; dailyOutputTokens: number; dailyCostUsd: string | null
}
interface NamedBudget extends SourceBudget {
  modelName: string; reportedUsageCalls: number; pricedCalls: number; callsWithoutUsage: number
  usageCallsWithoutPrice: number; promptTokens: number | null; completionTokens: number | null
  totalTokens: number | null; estimatedCostUsd: string | null
}
interface GlobalBudget extends Budget {
  dailyCostUsd: string | null; costBreachDay: string | null
}
const source = ref<SourceBudget>()
const global = ref<GlobalBudget>()
const named = ref<NamedBudget>()
const busy = ref(false)
function limit(value: number) { return value === 0 ? '不限' : value }
let disposed = false
async function refresh() {
  busy.value = true
  try {
    const [s, g, n] = await Promise.all([
      props.sourceId ? request<SourceBudget>(`/sources/${props.sourceId}/model-budget`) : Promise.resolve(undefined), request<GlobalBudget>('/model/budget'),
      request<NamedBudget>('/model/named-budget')
    ])
    if (!disposed) { source.value = s; global.value = g; named.value = n }
  } catch (e) { if (!disposed) ElMessage.error(e instanceof Error ? e.message : '模型预算读取失败') }
  finally { busy.value = false }
}
onMounted(refresh)
onUnmounted(() => { disposed = true })
</script>

<template>
  <details>
    <summary>模型调用用量</summary>
    <template v-if="global || named">
    <template v-if="source">
      <p>该源已记账日期：{{ source.budgetDay || '尚无记账周期' }}；日期变化后的首次新调用才切换预算日。</p>
      <p>已预占调用 {{ source.usedCalls }}/{{ limit(source.dailyCallLimit) }} 次，
        请求 {{ source.reservedInputBytes }}/{{ limit(source.dailyInputBytes) }} 字节，
        最大输出 {{ source.reservedOutputTokens }}/{{ limit(source.dailyOutputTokens) }} tokens；
        潜在金额 {{ source.reservedCostUsd ?? '未知' }}/{{ source.dailyCostUsd ?? '不限' }} USD。</p>
    </template>
      <p v-if="global">全局已记账日期 {{ global.budgetDay || '尚无记账周期' }}：
        {{ global.usedCalls }} 次、{{ global.reservedInputBytes }} 请求字节、
        {{ global.reservedOutputTokens }} 最大输出 tokens、潜在金额
        {{ global.reservedCostUsd ?? '未知' }}/{{ global.dailyCostUsd ?? '不限' }} USD。</p>
      <p v-if="global?.costBreachDay">服务方用量曾超出 {{ global.costBreachDay }} 的预占合同；
        已阻断该 UTC 日期及以前日期的新调用。</p>
      <p v-if="named">当前模型 {{ named.modelName || '未配置' }}；记账日 {{ named.budgetDay || '尚无记账周期' }}：
        {{ named.usedCalls }}/{{ limit(named.dailyCallLimit) }} 次，
        {{ named.reservedInputBytes }}/{{ limit(named.dailyInputBytes) }} 请求字节，
        {{ named.reservedOutputTokens }}/{{ limit(named.dailyOutputTokens) }} 最大输出 tokens。
        潜在金额 {{ named.reservedCostUsd ?? '未知' }}/{{ named.dailyCostUsd ?? '不限' }} USD。
        同名模型跨接入地址共用用量记录。</p>
      <template v-if="named">
        <p>服务方已报告 {{ named.reportedUsageCalls }} 次用量：
          输入 {{ named.promptTokens ?? '未知' }}、输出 {{ named.completionTokens ?? '未知' }}、
          合计 {{ named.totalTokens ?? '未知' }} tokens；另有 {{ named.callsWithoutUsage }} 次未取得用量。</p>
        <p>已定价 {{ named.pricedCalls }} 次，参考金额 USD {{ named.estimatedCostUsd ?? '未知' }}；
          {{ named.usageCallsWithoutPrice }} 次已报告用量但无冻结费率。已知小计不包含未知用量或未定价调用。</p>
        <p>参考金额来自服务方自报用量；潜在金额按管理员确认的输入计量合同、最大输出和冻结费率预占。
          本地上限不能约束服务方实际扣费。启用金额上限需完整费率和计量合同，未知历史金额不会按零放行。</p>
      </template>
      <p>请求字节和最大输出预占不等于实际输入 token 或费用。这里展示已保存的 UTC 周期，旧周期数据不表示今天已用。</p>
    </template>
    <el-button :loading="busy" @click="refresh">刷新用量</el-button>
  </details>
</template>
