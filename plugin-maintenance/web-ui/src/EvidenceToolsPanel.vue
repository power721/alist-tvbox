<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { DocumentAdd, VideoCamera, Search, MagicStick } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { request } from './api'
import { evidenceFields, type EvidenceChange } from './evidence'

const props = defineProps<{
  sourceId: string; artifactSha256: string; runId: string; repairId?: string; canPropose?: boolean; disabled?: boolean
}>()
const emit = defineEmits<{ changed: [change: EvidenceChange]; 'busy-change': [busy: boolean] }>()
type Mode = 'replay' | 'selector' | 'browser'
const mode = ref<Mode>('replay')
const drafts = ref<Record<Mode, string>>({ replay: '', selector: '', browser: '' })
const results = ref<Partial<Record<Mode, unknown>>>({})
const frozenReplay = ref('')
const input = computed({ get: () => drafts.value[mode.value], set: value => { drafts.value[mode.value] = value } })
const result = computed(() => results.value[mode.value])
const busy = ref(false)
const allowResponseAbort = ref(false)
let revision = 0
const fields = computed(() => evidenceFields(result.value))
const disabled = computed(() => !!props.disabled || !props.runId || busy.value)
const canSubmit = computed(() => !disabled.value && !!input.value
  && (mode.value !== 'selector' || !!props.repairId && !!props.canPropose))
const options = [{ label: '字段回放', value: 'replay' }, { label: '选择器候选', value: 'selector' },
  { label: '浏览器取证', value: 'browser' }]
function replayTemplate() {
  return {
    expectedArtifactSha256: props.artifactSha256,
    rule: { rowSelector: '', fields: {
      vod_id: { selector: '', attribute: 'data-id', transforms: ['trim'] },
      vod_name: { selector: '', attribute: 'text', transforms: ['trim', 'collapseWhitespace'] }
    }, requiredFields: ['vod_id', 'vod_name'] },
    pages: [{ role: 'original', html: '', htmlSha256: '', expected: [] },
      { role: 'holdout', html: '', htmlSha256: '', expected: [] }],
    dependencySha256: {}
  }
}
function selectorTemplate() {
  const replay = JSON.parse(frozenReplay.value || drafts.value.replay || JSON.stringify(replayTemplate()))
  return { replay, field: 'vod_name', oldSelector: replay.rule?.fields?.vod_name?.selector ?? '' }
}
function template() {
  try {
    input.value = JSON.stringify(mode.value === 'replay' ? replayTemplate() : mode.value === 'selector'
      ? selectorTemplate()
      : { jobId: props.runId, expectedArtifactSha256: props.artifactSha256, frameId: '',
        frameUrlSha256: '', selector: '', requireFocus: false, responseUrls: [], maxBodyBytes: 32768 }, null, 2)
  } catch { ElMessage.error('请先修正字段回放 JSON') }
}
async function submit() {
  if (!canSubmit.value) return
  const current = revision
  const submittedMode = mode.value
  const identity = { sourceId: props.sourceId, artifactSha256: props.artifactSha256, runId: props.runId }
  busy.value = true
  emit('busy-change', true)
  try {
    const body = JSON.parse(input.value)
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('辅助证据 JSON 必须为对象')
    if (submittedMode === 'browser') body.allowResponseAbort = allowResponseAbort.value
    const path = submittedMode === 'selector' ? `/repairs/${props.repairId}/selector-proposals`
      : submittedMode === 'browser' ? `/sources/${props.sourceId}/browser-evidence`
      : `/sources/${props.sourceId}/extraction/replay?runId=${encodeURIComponent(props.runId)}`
    const value = await request(path, body)
    if (current !== revision) return
    results.value[submittedMode] = value
    if (submittedMode === 'replay') {
      frozenReplay.value = JSON.stringify(body)
      drafts.value.selector = ''
      delete results.value.selector
    }
    emit('changed', { ...identity, evidence: value, kind: submittedMode === 'replay' ? 'extractionReplay'
      : submittedMode === 'browser' ? 'browserEvidence' : 'selectorProposal' })
  } catch (error) {
    if (current === revision) ElMessage.error(error instanceof Error ? error.message : '辅助证据操作失败')
  }
  finally { busy.value = false; emit('busy-change', false) }
}
watch(() => [props.sourceId, props.artifactSha256, props.runId], () => {
  revision++
  drafts.value = { replay: '', selector: '', browser: '' }
  results.value = {}
  frozenReplay.value = ''
  allowResponseAbort.value = false
}, { flush: 'sync' })
watch(() => props.repairId, () => {
  revision++
  delete results.value.selector
}, { flush: 'sync' })
watch(mode, () => {
  revision++
  allowResponseAbort.value = false
  if (mode.value === 'selector' && !drafts.value.selector) {
    try { drafts.value.selector = JSON.stringify(selectorTemplate(), null, 2) } catch { /* Retain an incomplete replay draft. */ }
  }
}, { flush: 'sync' })
onUnmounted(() => { revision++ })
</script>

<template>
  <details class="evidence-tools">
    <summary>辅助证据与规则候选</summary>
    <div class="tools">
      <el-segmented v-model="mode" :options="options" :disabled="disabled" aria-label="辅助证据类型" />
      <el-tooltip content="填入输入模板"><el-button :icon="DocumentAdd" circle :disabled="disabled"
        aria-label="填入输入模板" @click="template" /></el-tooltip>
    </div>
    <el-checkbox v-if="mode === 'browser'" v-model="allowResponseAbort" :disabled="disabled">
      允许中止所选网络响应
    </el-checkbox>
    <el-input v-model="input" type="textarea" :rows="12" :disabled="disabled" aria-label="辅助证据 JSON" />
    <el-button :icon="mode === 'browser' ? VideoCamera : mode === 'selector' ? MagicStick : Search"
      :loading="busy" :disabled="!canSubmit" @click="submit">
      {{ mode === 'selector' ? '保存规则候选' : mode === 'browser' ? '采集目标证据' : '验证并冻结回放' }}
    </el-button>
    <el-tree v-if="fields.length" :data="fields" node-key="id" :expand-on-click-node="false"
      class="result-tree" />
  </details>
</template>

<style scoped>
.evidence-tools { border-block: 1px solid #dce4e8; padding: 14px 0; margin: 16px 0; }
.tools { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.el-textarea { margin-bottom: 10px; }
.result-tree { margin-top: 14px; }
.result-tree :deep(.el-tree-node__content) { height: auto; min-height: 28px; }
.result-tree :deep(.el-tree-node__label) { white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; }
</style>
