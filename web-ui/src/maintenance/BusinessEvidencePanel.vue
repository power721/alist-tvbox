<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { DataAnalysis, FolderOpened, View } from '@element-plus/icons-vue'
import { readBusinessComparison, type BusinessComparison, type CountKey } from './businessSnapshot'
import { evidenceFields, type KnowledgeContext } from './evidence'

defineProps<{ disabled?: boolean; catalog?: KnowledgeContext }>()
const visible = ref(false)
const drawer = ref(false)
const picker = ref<HTMLInputElement>()
const comparison = ref<BusinessComparison>()
const sha = ref('')
const error = ref('')
const busy = ref(false)
const side = ref<'baseline' | 'candidate'>('candidate')
const stage = ref('home')
const stages: { id: string; title: string; node: string; metrics: { key: CountKey; label: string }[] }[] = [
  { id: 'home', title: '首页', node: 'C01', metrics: [
    { key: 'homeClasses', label: '分类入口' }, { key: 'homeItems', label: '卡片' }, { key: 'homeCovers', label: '封面字段' }] },
  { id: 'category', title: '分类', node: 'C03', metrics: [
    { key: 'categoryItems', label: '第一页卡片' }, { key: 'page2Items', label: '第二页卡片' },
    { key: 'categoryCovers', label: '封面字段' }] },
  { id: 'detail', title: '详情', node: 'C07', metrics: [
    { key: 'detailItems', label: '详情卡片' }, { key: 'detailCovers', label: '封面字段' },
    { key: 'playGroups', label: '播放组' }, { key: 'playEpisodes', label: '选集' }] },
  { id: 'contracts', title: '离线合同', node: 'S06', metrics: [
    { key: 'fixtureChecks', label: '夹具断言' }, { key: 'compatChecks', label: 'ATVP 门禁' },
    { key: 'dualRuntimeChecks', label: '双运行时门禁' }] }
]
const selected = computed(() => comparison.value?.[side.value])
const definition = computed(() => stages.find(row => row.id === stage.value)!)
const fields = computed(() => evidenceFields(selected.value ? {
  counts: Object.fromEntries(definition.value.metrics.map(row => [row.key, selected.value!.counts[row.key]])),
  http: selected.value.http.filter(row => row.page === stage.value),
  deniedCodes: selected.value.deniedCodes, limits: selected.value.limits, controls: selected.value.controls,
  ...(stage.value === 'contracts' ? { fixturePassed: selected.value.fixturePassed } : {})
} : undefined))
let disposed = false
async function load(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value) return
  comparison.value = undefined
  sha.value = ''
  drawer.value = false
  error.value = ''
  busy.value = true
  try {
    const result = await readBusinessComparison(file)
    if (!disposed) { comparison.value = result.comparison; sha.value = result.fileSha256 }
  } catch (reason) {
    if (!disposed) error.value = reason instanceof Error ? reason.message : '业务快照读取失败'
  } finally { if (!disposed) busy.value = false }
}
function inspect(id: string) { stage.value = id; drawer.value = true }
onUnmounted(() => { disposed = true })
</script>

<template>
  <el-button :icon="DataAnalysis" :disabled="disabled" @click="visible = true">业务对照快照</el-button>
  <el-dialog v-model="visible" title="T4 业务对照快照" width="min(1080px, 96vw)"
             class="business-evidence-dialog" :close-on-click-modal="false">
    <div class="business-actions">
      <input ref="picker" type="file" accept=".json,application/json" aria-label="选择业务对照快照"
             class="business-picker" :disabled="busy" @change="load" />
      <el-button :icon="FolderOpened" :loading="busy" @click="picker?.click()">打开快照</el-button>
      <el-tag type="info">本地文件 · 未签名</el-tag>
      <el-tag type="warning">源输出未受信任 · 不授予发布资格</el-tag>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="comparison">
      <el-table :data="stages" row-key="id" class="business-stage-table">
        <el-table-column prop="title" label="环节" width="100" />
        <el-table-column label="原版观察" min-width="190">
          <template #default="{ row }">
            <div v-for="metric in row.metrics" :key="metric.key">{{ metric.label }}：
              {{ comparison.baseline.counts[metric.key as CountKey] ?? '未观察' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="候选观察" min-width="190">
          <template #default="{ row }">
            <div v-for="metric in row.metrics" :key="metric.key">{{ metric.label }}：
              {{ comparison.candidate.counts[metric.key as CountKey] ?? '未观察' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数据" width="72" fixed="right">
          <template #default="{ row }"><el-tooltip content="拆解环节数据">
            <el-button :icon="View" circle :aria-label="`拆解业务${row.title}`" @click="inspect(row.id)" />
          </el-tooltip></template>
        </el-table-column>
      </el-table>
      <dl class="business-identity">
        <dt>原版源码</dt><dd><code>{{ comparison.baseline.sourceSha256 }}</code></dd>
        <dt>候选源码</dt><dd><code>{{ comparison.candidate.sourceSha256 }}</code></dd>
        <dt>快照 SHA-256</dt><dd><code>{{ sha }}</code></dd>
        <dt>媒体 / 客户端</dt><dd>未验证</dd>
        <dt>模型 / 发布</dt><dd>未调用 · 未发布</dd>
      </dl>
    </template>
    <el-empty v-else-if="!busy && !error" description="尚未打开业务对照快照" />
  </el-dialog>
  <el-drawer v-model="drawer" :title="`业务数据 · ${definition.title}`" size="min(760px, 100vw)"
             append-to-body class="business-data-drawer">
    <el-radio-group v-model="side" aria-label="业务快照版本" class="business-actions">
      <el-radio-button value="baseline">原版</el-radio-button>
      <el-radio-button value="candidate">候选</el-radio-button>
    </el-radio-group>
    <template v-if="selected">
      <el-tag type="warning">历史实验 · 源输出未受信任</el-tag>
      <dl class="business-identity">
        <dt>观察时间</dt><dd>{{ new Date(selected.observedAt).toLocaleString() }}</dd>
        <dt>源码</dt><dd><code>{{ selected.sourceSha256 }}</code></dd>
        <dt>镜像</dt><dd><code>{{ selected.imageId }}</code></dd>
        <dt>运行时</dt><dd><code>{{ selected.runtimeSha256 }}</code></dd>
        <dt>任务</dt><dd><code>{{ selected.jobId }}</code></dd>
        <dt>容器</dt><dd><code>{{ selected.containerId }}</code></dd>
        <dt>原始回执</dt><dd><code>{{ selected.receiptSha256 }}</code></dd>
      </dl>
      <el-tabs>
        <el-tab-pane label="关键数据">
          <el-tree :data="fields" node-key="id" default-expand-all :expand-on-click-node="false"
                   class="business-data-tree" />
        </el-tab-pane>
        <el-tab-pane label="本机经验">
          <article v-for="rule in catalog?.rules.filter(item => item.nodes?.includes(definition.node)) ?? []"
                   :key="rule.id" class="rule">
            <h3>{{ rule.title }}</h3><p>{{ rule.guidance }}</p>
            <p>{{ rule.citation.path }} · L{{ rule.citation.startLine }}–{{ rule.citation.endLine }}</p>
            <code>{{ rule.citation.sha256 }}</code>
          </article>
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-drawer>
</template>

<style scoped>
.business-picker { display: none; }
.business-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 16px; }
.business-identity { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 10px; font-size: 12px; margin: 20px 0; }
dt { color: #526c79; } dd { margin: 0; }
dd, code, .rule { overflow-wrap: anywhere; }
.rule { border-top: 1px solid #dce4e8; padding: 12px 0; }
.rule h3 { font-size: 14px; }
.business-data-tree :deep(.el-tree-node__content) { height: auto; min-height: 30px; align-items: flex-start; padding-block: 4px; }
.business-data-tree :deep(.el-tree-node__label) { white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; line-height: 1.7; font-family: Consolas, "Microsoft YaHei", monospace; font-size: 12px; }
@media (max-width: 600px) { .business-identity { grid-template-columns: 76px minmax(0, 1fr); gap: 8px; } }
</style>
