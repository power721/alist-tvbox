<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, DocumentChecked, Refresh, Upload } from '@element-plus/icons-vue'
import { request } from './api'
import PluginInputEvidence from './PluginInputEvidence.vue'
import type { PluginInputSummary } from './pluginInput'

defineProps<{ disabled?: boolean; configured?: boolean }>()
const emit = defineEmits<{ imported: [sourceId: string]; configure: [] }>()
interface Observation {
  sha256: string
  observedAt: string
  excludedPluginIds?: number[]
  plugins: { id: number; name: string; enabled: boolean; t4Visible: boolean; kind: string }[]
}
interface Pending {
  observationSha256: string; pluginId: number; authorized: boolean
  expectedEvidenceSha256: string; requestKey: string; name: string; filename: string
}
const visible = ref(false)
const busy = ref(false)
const observation = ref<Observation>()
const pluginId = ref<number>()
const authorized = ref(false)
const name = ref('')
const filename = ref('source.py')
const preview = ref<PluginInputSummary>()
const saved = ref<PluginInputSummary>()
const pending = ref<Pending>()
const error = ref('')
const plugins = computed(() => observation.value?.plugins.filter(item =>
  item.enabled && item.t4Visible && ['python', 'secspider'].includes(item.kind)) ?? [])
const excluded = computed(() => observation.value?.excludedPluginIds ?? [])
const canSave = computed(() => !busy.value && !saved.value && !!preview.value && authorized.value
  && !excluded.value.includes(pluginId.value ?? -1)
  && name.value.trim().length > 0 && name.value.trim().length <= 200
  && filename.value.length <= 200 && /^[^/\\]+\.py$/.test(filename.value))
let disposed = false
let revision = 0

watch(pluginId, () => {
  revision++
  preview.value = undefined; saved.value = undefined; authorized.value = false; error.value = ''
  name.value = plugins.value.find(item => item.id === pluginId.value)?.name ?? ''
}, { flush: 'sync' })
async function observe() {
  if (busy.value || pending.value) return
  busy.value = true; error.value = ''; preview.value = undefined; saved.value = undefined
  observation.value = undefined; pluginId.value = undefined
  try {
    const result = await request<Observation>('/at/observations', {})
    if (!disposed) observation.value = result
  } catch (reason) { if (!disposed) error.value = reason instanceof Error ? reason.message : '读取失败' }
  finally { if (!disposed) busy.value = false }
}
async function inspect() {
  if (busy.value || pending.value || !authorized.value || !observation.value || !pluginId.value
    || excluded.value.includes(pluginId.value)) return
  busy.value = true; error.value = ''; preview.value = undefined
  const identity = revision
  try {
    const result = await request<PluginInputSummary>('/at/plugin-inputs/preview', {
      observationSha256: observation.value.sha256, pluginId: pluginId.value, authorized: true })
    if (!disposed && identity === revision) preview.value = result
  } catch (reason) {
    if (!disposed) error.value = reason instanceof Error && reason.message === 'AT_PLUGIN_MAINTENANCE_EXCLUDED'
      ? '此插件不参与维护' : reason instanceof Error ? reason.message : '校验失败'
  }
  finally { if (!disposed) busy.value = false }
}
async function save() {
  if (!canSave.value || !preview.value || !observation.value || !pluginId.value) return
  pending.value ??= { observationSha256: observation.value.sha256, pluginId: pluginId.value,
    authorized: true, expectedEvidenceSha256: preview.value.evidenceSha256,
    requestKey: crypto.randomUUID(), name: name.value.trim(), filename: filename.value }
  busy.value = true; error.value = ''
  try {
    const result = await request<PluginInputSummary>('/at/plugin-inputs', pending.value)
    if (disposed) return
    if (!result.sourceId || result.evidenceSha256 !== pending.value.expectedEvidenceSha256
      || result.artifactSha256 !== preview.value.artifactSha256
      || result.evidence.atSource?.observationSha256 !== pending.value.observationSha256
      || result.evidence.atSource.pluginId !== pending.value.pluginId) throw new Error('导入回执身份不一致')
    saved.value = result
    emit('imported', result.sourceId)
    ElMessage.success('源与 AT 本地绑定已保存')
  } catch (reason) {
    if (!disposed) {
      const code = reason instanceof Error ? reason.message : '保存结果未知'
      if (['PLUGIN_INPUT_PREVIEW_CHANGED', 'AT_SOURCE_NOT_READABLE', 'AT_OBSERVATION_CHANGED',
        'AT_OBSERVATION_EXPIRED', 'AT_PLUGIN_ALREADY_BOUND', 'AT_PLUGIN_MAINTENANCE_EXCLUDED'].includes(code)) {
        pending.value = undefined; preview.value = undefined
        error.value = code === 'AT_PLUGIN_MAINTENANCE_EXCLUDED' ? '此插件不参与维护' : code
      } else error.value = `${code}；请核对同一请求。`
    }
  } finally { if (!disposed) busy.value = false }
}
function resetSaved() {
  if (!saved.value || busy.value) return
  pending.value = undefined; saved.value = undefined; preview.value = undefined
  observation.value = undefined; pluginId.value = undefined; authorized.value = false
  error.value = ''; filename.value = 'source.py'
}
function beforeClose(done: () => void) { if (!busy.value) done() }
onUnmounted(() => { disposed = true })
</script>

<template>
  <el-button :icon="Connection" :disabled="disabled || busy"
             @click="configured ? visible = true : emit('configure')">从 AT 导入</el-button>
  <el-dialog v-model="visible" title="从 AT 导入" width="min(820px, 94vw)"
             :before-close="beforeClose" :close-on-click-modal="false"
             :close-on-press-escape="!busy" :show-close="!busy">
    <div class="at-input-actions">
      <el-button :icon="Refresh" :disabled="busy || !!pending" @click="observe">读取插件列表</el-button>
      <span v-if="observation">{{ new Date(observation.observedAt).toLocaleString() }}</span>
    </div>
    <el-select v-model="pluginId" filterable placeholder="选择已启用的 T4 插件"
               :disabled="busy || !!pending" class="at-input-selector">
      <el-option v-for="plugin in plugins" :key="plugin.id" :value="plugin.id"
                 :disabled="excluded.includes(plugin.id)"
                 :label="`${plugin.name} · ID ${plugin.id}${plugin.kind === 'secspider' ? ' · 加密' : ''}${excluded.includes(plugin.id) ? ' · 不参与维护' : ''}`" />
    </el-select>
    <el-checkbox v-model="authorized" :disabled="busy || !!pending">
      我有权只读获取并在本机维护此插件
    </el-checkbox>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="at-input-actions">
      <el-button :icon="DocumentChecked" :loading="busy && !preview"
                 :disabled="busy || !!pending || !authorized || !pluginId || excluded.includes(pluginId)" @click="inspect">只读预览</el-button>
    </div>
    <template v-if="preview">
      <PluginInputEvidence :input="saved ?? preview" :current-sha256="preview.artifactSha256" />
      <p class="sha">正文 {{ preview.evidence.inputSha256 }}</p>
      <el-form label-position="top">
        <el-form-item label="受管源名称"><el-input v-model="name" maxlength="200" :disabled="busy || !!pending" /></el-form-item>
        <el-form-item label="明文文件名"><el-input v-model="filename" maxlength="200" :disabled="busy || !!pending" /></el-form-item>
      </el-form>
      <el-tag v-if="saved" type="success">已导入并绑定 · 未执行</el-tag>
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">关闭</el-button>
      <el-button v-if="saved" :icon="Refresh" @click="resetSaved">继续导入</el-button>
      <el-button v-if="saved" type="primary" @click="emit('imported', saved.sourceId!); visible = false">打开受管源</el-button>
      <el-button v-else :icon="Upload" type="primary" :disabled="!canSave" :loading="busy && !!preview" @click="save">
        {{ pending ? '核对同一导入请求' : '确认导入并绑定' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.at-input-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0; }
.at-input-selector { width: 100%; }
.el-checkbox { margin: 12px 0; height: auto; white-space: normal; }
</style>
