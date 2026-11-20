<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Connection, DocumentChecked, Film, Monitor, Search, View } from '@element-plus/icons-vue'
import { evidenceFields, mediaObservationLabel, phases, phaseSummary, requirementLabels, statusLabels,
  type EvidenceRun, type KnowledgeContext, type NodeDefinition } from './evidence'

const props = defineProps<{ run: EvidenceRun; definitions: NodeDefinition[]; catalog?: KnowledgeContext }>()
const phase = ref('')
const status = ref('')
const nodeId = ref('')
const drawer = ref(false)
const icons = [DocumentChecked, Search, Connection, Film, Monitor]
const nodes = computed(() => props.run.result?.nodes ?? [])
const summaries = computed(() => phases.map(item => ({ ...item,
  ...phaseSummary(props.definitions, nodes.value, item.id) })))
const displayed = computed(() => nodes.value.filter(node =>
  (!phase.value || node.id.startsWith(phase.value)) && (!status.value || node.status === status.value)))
const selected = computed(() => nodes.value.find(node => node.id === nodeId.value))
const definition = computed(() => props.definitions.find(node => node.id === nodeId.value))
const context = computed(() => props.run.context ?? props.catalog)
const rules = computed(() => context.value?.rules.filter(rule => rule.nodes?.includes(nodeId.value)) ?? [])
const fields = computed(() => evidenceFields(selected.value?.evidence))
const managedFields = computed(() => evidenceFields(props.run.result?.managedSourceEvidence))
const diagnosticFields = computed(() => evidenceFields(props.run.result?.managedExecutionDiagnostic))
const auxiliaryFields = computed(() => evidenceFields(props.run.context?.auxiliaryEvidence))
const businessSample = computed(() => props.run.result?.managedSourceEvidence?.executionKind === 't4_business_sample')
const businessCounts = computed(() => props.run.result?.managedSourceEvidence?.untrustedRuntimeSummary)
const playerLabels: Record<string, string> = { not_sampled: '未调用', selection_unavailable: '缺少有效选集',
  returned: '已返回', empty: '空地址', invalid_output: '返回格式无效', exception: '调用异常',
  policy_denied: '出站策略拒绝', not_requested: '未请求', prefix_returned: '已取回有界前缀',
  http_failed: 'HTTP 失败', invalid_headers: '请求头无效' }
function observedLabel(value: string | number | null | undefined) {
  return typeof value === 'string' ? playerLabels[value] ?? value : '未观察'
}
const businessRows = computed(() => [
  { id: 'C01', title: '首页分类', counts: `分类 ${businessCounts.value?.homeClasses ?? '未观察'}` },
  { id: 'C02', title: '首页推荐', counts: `卡片 ${businessCounts.value?.homeItems ?? '未观察'} · 封面字段 ${businessCounts.value?.homeCovers ?? '未观察'}` },
  { id: 'C03', title: '分类分页', counts: `第一页 ${businessCounts.value?.categoryItems ?? '未观察'} · 第二页 ${businessCounts.value?.page2Items ?? '未观察'}` },
  { id: 'C07', title: '详情', counts: `卡片 ${businessCounts.value?.detailItems ?? '未观察'}` },
  { id: 'C10', title: '线路与选集', counts: `播放组 ${businessCounts.value?.playGroups ?? '未观察'} · 选集 ${businessCounts.value?.playEpisodes ?? '未观察'}` },
  { id: 'R01', title: '原始取链', counts: observedLabel(businessCounts.value?.playerStatus) },
  { id: 'M01', title: '媒体前缀', counts: `源：${observedLabel(businessCounts.value?.mediaStatus)} · HTTP 观察：${mediaObservationLabel(props.run.result?.managedSourceEvidence?.httpObservations)}` }
])
watch(() => [props.run.id, props.run.artifactSha256, props.run.profileSha256, props.run.runtimeSha256],
  () => { drawer.value = false; nodeId.value = '' })
function inspect(id: string) { nodeId.value = id; drawer.value = true }
</script>

<template>
  <section v-if="businessSample" class="business-sample" aria-label="本次 T4 业务采样">
    <div class="evidence-identity"><strong>T4 业务采样</strong><el-tag type="warning">源输出未独立核验 · HTTP 单独观察</el-tag></div>
    <el-table :data="businessRows" row-key="id">
      <el-table-column prop="title" label="环节" width="115" />
      <el-table-column prop="counts" label="本次返回" min-width="230" />
      <el-table-column label="数据" width="74" fixed="right">
        <template #default="{ row }"><el-tooltip content="拆解本次采样">
          <el-button :icon="View" circle :aria-label="`拆解采样 ${row.id}`" @click="inspect(row.id)" />
        </el-tooltip></template>
      </el-table-column>
    </el-table>
  </section>
  <div class="phase-strip" aria-label="探测环节">
    <button v-for="(item, index) in summaries" :key="item.id" class="phase-item"
            :class="{ chosen: phase === item.id }" :aria-pressed="phase === item.id"
            @click="phase = phase === item.id ? '' : item.id">
      <span class="phase-title"><el-icon><component :is="icons[index]" /></el-icon>{{ item.title }}</span>
      <span class="phase-count">{{ item.pass }}<small> / {{ item.total }} 通过</small></span>
      <span class="phase-bar"><i class="passed" :style="{ width: `${item.total ? item.pass / item.total * 100 : 0}%` }" />
        <i class="failed" :style="{ width: `${item.total ? item.fail / item.total * 100 : 0}%` }" />
        <i class="blocked" :style="{ width: `${item.total ? item.blocked / item.total * 100 : 0}%` }" /></span>
      <span class="phase-caption">失败 {{ item.fail }} · 阻塞 {{ item.blocked }} · 未测 {{ item.not_tested }}</span>
    </button>
  </div>
  <div class="evidence-controls">
    <el-button v-if="phase" @click="phase = ''">全部环节</el-button>
    <el-select v-model="status" placeholder="全部状态" clearable aria-label="节点状态" class="status-select">
      <el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" />
    </el-select>
    <span>{{ displayed.length }} 个节点</span>
  </div>
  <el-table :data="displayed" row-key="id" stripe>
    <el-table-column prop="id" label="节点" width="76" />
    <el-table-column prop="title" label="能力" min-width="170" />
    <el-table-column label="声明" width="86">
      <template #default="{ row }">{{ requirementLabels[row.requirement] || '未绑定' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="88">
      <template #default="{ row }"><el-tag :type="row.status === 'fail' ? 'danger' : row.status === 'pass' ? 'success' : 'info'">
        {{ statusLabels[row.status] || row.status }}</el-tag></template>
    </el-table-column>
    <el-table-column prop="reason" label="原因" min-width="220" />
    <el-table-column label="数据" width="74" fixed="right">
      <template #default="{ row }"><el-tooltip content="拆解节点证据" placement="top">
        <el-button :icon="View" circle :aria-label="`拆解 ${row.id} ${row.title}`" @click="inspect(row.id)" />
      </el-tooltip></template>
    </el-table-column>
  </el-table>
  <el-drawer v-model="drawer" :title="selected ? `${selected.id} · ${selected.title}` : '节点证据'"
             size="min(720px, 100vw)" append-to-body class="evidence-drawer">
    <template v-if="selected">
      <div class="evidence-identity">
        <el-tag :type="selected.status === 'pass' ? 'success' : selected.status === 'fail' ? 'danger' : 'info'">
          {{ statusLabels[selected.status] || selected.status }}</el-tag>
        <el-tag type="info">{{ run.executionMode }}</el-tag>
        <el-tag v-if="run.freshness !== 'current'" type="warning">历史输入 · {{ run.freshness }}</el-tag>
      </div>
      <p>{{ selected.reason }}</p>
      <dl class="evidence-bindings">
        <dt>任务</dt><dd><code>{{ run.id }}</code></dd>
        <dt>源码</dt><dd><code>{{ run.artifactSha256 }}</code></dd>
        <dt>画像</dt><dd><code>{{ run.profileSha256 || '未绑定' }}</code></dd>
        <dt>运行时</dt><dd><code>{{ run.runtimeSha256 || '无运行时回执' }}</code></dd>
      </dl>
      <el-tabs>
        <el-tab-pane v-if="auxiliaryFields.length" label="字段与浏览器证据">
          <el-tree :data="auxiliaryFields" node-key="id" :expand-on-click-node="false" class="evidence-tree" />
        </el-tab-pane>
        <el-tab-pane label="关键数据">
          <el-tree v-if="fields.length" :data="fields" node-key="id" default-expand-all
                   :expand-on-click-node="false" class="evidence-tree" />
          <el-empty v-else description="该节点没有附加数据" />
        </el-tab-pane>
        <el-tab-pane v-if="run.result?.managedSourceEvidence && (nodeId === 'S06' || businessSample)" label="执行器与 HTTP">
          <el-tree :data="managedFields" node-key="id" default-expand-all
                   :expand-on-click-node="false" class="evidence-tree" />
        </el-tab-pane>
        <el-tab-pane v-if="nodeId === 'S06' && run.result?.managedExecutionDiagnostic" label="失败诊断">
          <el-tree :data="diagnosticFields" node-key="id" default-expand-all
                   :expand-on-click-node="false" class="evidence-tree" />
        </el-tab-pane>
        <el-tab-pane label="验证要求">
          <dl v-if="definition" class="evidence-contract">
            <dt>输入来源</dt><dd>{{ definition.inputs || '未提供' }}</dd>
            <dt>断言</dt><dd>{{ definition.assertions || '未提供' }}</dd>
            <dt>结论边界</dt><dd>{{ definition.boundary || '未提供' }}</dd>
          </dl>
          <el-empty v-else description="当前节点目录未收录此节点" />
        </el-tab-pane>
        <el-tab-pane :label="`本机经验 (${rules.length})`">
          <p class="sha">资料版本 {{ context?.catalogSha256 || '未提供' }}</p>
          <p class="boundary">{{ context?.boundary }}</p>
          <article v-for="rule in rules" :key="rule.id" class="rule">
            <h3>{{ rule.title }}</h3><p>{{ rule.guidance }}</p>
            <ol><li v-for="step in rule.validationSteps" :key="step">{{ step }}</li></ol>
            <p class="sha">{{ rule.citation.path }} · L{{ rule.citation.startLine }}–{{ rule.citation.endLine }}</p>
            <details><summary>引用身份与原文</summary><code>{{ rule.citation.sha256 }}</code>
              <pre>{{ rule.citation.excerpt }}</pre></details>
          </article>
          <el-empty v-if="!rules.length" description="本次资料快照未附该节点规则" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-drawer>
</template>

<style scoped>
.phase-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border-block: 1px solid #dce4e8; margin: 20px 0; }
.phase-item { min-width: 0; min-height: 140px; padding: 16px 10px; text-align: left; color: inherit; background: white; border: 0; border-bottom: 3px solid transparent; cursor: pointer; font: inherit; }
.phase-item:hover { background: #f4f8f9; }
.phase-item.chosen { border-bottom-color: #227b86; background: #eef8f7; }
.phase-title { display: flex; gap: 6px; align-items: center; font-size: 13px; font-weight: 600; }
.phase-count { display: block; margin: 12px 0; font-size: 24px; }
.phase-count small, .phase-caption { font-size: 11px; color: #5b6b74; }
.phase-bar { display: flex; height: 4px; background: #e4eaee; margin: 10px 0; }
.phase-bar i { height: 100%; }
.passed { background: #23836c; }.failed { background: #c94859; }.blocked { background: #b8882a; }
.phase-caption { display: block; overflow-wrap: anywhere; }
.evidence-controls, .evidence-identity { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 14px 0; }
.status-select { width: 150px; }
.evidence-bindings { display: grid; grid-template-columns: 62px minmax(0, 1fr); gap: 10px; font-size: 12px; }
dt { color: #526c79; } dd { margin: 0; overflow-wrap: anywhere; }
.evidence-contract dt { font-weight: 600; margin-top: 18px; }.evidence-contract dd { margin-top: 8px; line-height: 1.7; }
.evidence-tree :deep(.el-tree-node__content) { height: auto; min-height: 30px; align-items: flex-start; padding-block: 4px; }
.evidence-tree :deep(.el-tree-node__label) { white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; line-height: 1.7; font-family: Consolas, "Microsoft YaHei", monospace; font-size: 12px; }
@media (max-width: 650px) {
  .phase-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .phase-item { min-height: 136px; }
}
</style>
