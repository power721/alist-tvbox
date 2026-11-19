<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentChecked, FolderOpened, Upload } from '@element-plus/icons-vue'
import { request } from './api'
import PluginInputEvidence from './PluginInputEvidence.vue'
import type { PluginInputSummary } from './pluginInput'

withDefaults(defineProps<{ disabled?: boolean; label?: string }>(), { label: '导入插件包' })
const emit = defineEmits<{ imported: [sourceId: string] }>()
const visible = ref(false)
const busy = ref(false)
const picker = ref<HTMLInputElement>()
const authorized = ref(false)
const inputBase64 = ref('')
const inputName = ref('')
const name = ref('')
const filename = ref('source.py')
const preview = ref<PluginInputSummary>()
const saved = ref<PluginInputSummary>()
const error = ref('')
const pending = ref<{ inputBase64: string; authorized: boolean; expectedEvidenceSha256: string
  requestKey: string; name: string; filename: string }>()
const canSave = computed(() => !busy.value && authorized.value && preview.value && !saved.value
  && name.value.trim().length > 0 && name.value.trim().length <= 200
  && filename.value.length <= 200 && /^[^/\\]+\.py$/.test(filename.value))
let disposed = false

function fileBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
    reader.onerror = () => reject(new Error('插件包读取失败'))
    reader.onabort = () => reject(new Error('插件包读取中断'))
    reader.readAsDataURL(file)
  })
}
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value || pending.value) return
  busy.value = true
  inputBase64.value = ''; inputName.value = ''; preview.value = undefined; saved.value = undefined
  authorized.value = false; error.value = ''
  try {
    if (!file.size || file.size > 1_048_576) throw new Error('插件输入须为 1 字节至 1 MiB')
    const body = await fileBase64(file)
    if (disposed) return
    inputBase64.value = body
    inputName.value = file.name
    name.value = file.name.replace(/\.[^.]*$/, '')
    filename.value = `${name.value}.py`
  } catch (reason) { if (!disposed) error.value = reason instanceof Error ? reason.message : '读取失败' }
  finally { if (!disposed) busy.value = false }
}
async function inspect() {
  if (busy.value || !authorized.value || !inputBase64.value || pending.value) return
  busy.value = true; error.value = ''; preview.value = undefined
  try {
    const result = await request<PluginInputSummary>('/plugin-inputs/preview',
      { inputBase64: inputBase64.value, authorized: authorized.value })
    if (!disposed) preview.value = result
  } catch (reason) { if (!disposed) error.value = reason instanceof Error ? reason.message : '校验失败' }
  finally { if (!disposed) busy.value = false }
}
async function save() {
  if (!canSave.value || !preview.value) return
  pending.value ??= { inputBase64: inputBase64.value, authorized: authorized.value,
    expectedEvidenceSha256: preview.value.evidenceSha256, requestKey: crypto.randomUUID(),
    name: name.value.trim(), filename: filename.value }
  busy.value = true; error.value = ''
  try {
    const result = await request<PluginInputSummary>('/plugin-inputs', pending.value)
    if (disposed) return
    if (!result.sourceId || result.evidenceSha256 !== pending.value.expectedEvidenceSha256
      || result.artifactSha256 !== preview.value.artifactSha256) throw new Error('保存回执身份不一致')
    saved.value = result
    emit('imported', result.sourceId)
    ElMessage.success('插件明文与输入身份已保存，源码未执行')
  } catch (reason) {
    if (!disposed) error.value = `${reason instanceof Error ? reason.message : '保存结果未知'}；可核对同一请求，勿另建重复导入。`
  } finally { if (!disposed) busy.value = false }
}
function beforeClose(done: () => void) { if (!busy.value) done() }
function resetSaved() {
  if (!saved.value || busy.value) return
  pending.value = undefined; saved.value = undefined; preview.value = undefined
  inputBase64.value = ''; inputName.value = ''; name.value = ''; filename.value = 'source.py'
  authorized.value = false; error.value = ''
}
onUnmounted(() => { disposed = true })
</script>

<template>
  <el-button :icon="Upload" :disabled="disabled || busy" @click="visible = true">{{ label }}</el-button>
  <el-dialog v-model="visible" title="导入插件包" width="min(820px, 94vw)"
             :before-close="beforeClose" :close-on-click-modal="false"
             :close-on-press-escape="!busy" :show-close="!busy">
    <div class="plugin-input-actions">
      <input ref="picker" class="plugin-input-picker" type="file" accept=".txt,.package,.py"
             aria-label="选择插件包" :disabled="busy || !!pending" @change="readFile" />
      <el-button :icon="FolderOpened" :disabled="busy || !!pending" @click="picker?.click()">选择文件</el-button>
      <span>{{ inputName || '未选择文件' }}</span>
    </div>
    <el-checkbox v-model="authorized" :disabled="busy || !!pending">
      我有权读取并维护此文件（加密插件将尝试解密）
    </el-checkbox>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="plugin-input-error" />
    <div class="plugin-input-actions">
      <el-button :icon="DocumentChecked" :loading="busy && !preview"
                 :disabled="busy || !authorized || !inputBase64 || !!pending" @click="inspect">校验输入</el-button>
    </div>
    <template v-if="preview">
      <PluginInputEvidence :input="saved ?? preview" :current-sha256="preview.artifactSha256" />
      <p class="sha">原包 {{ preview.evidence.inputSha256 }}</p>
      <p class="sha">明文 {{ preview.artifactSha256 }}</p>
      <el-form label-position="top">
        <el-form-item label="受管源名称"><el-input v-model="name" maxlength="200" :disabled="busy || !!pending" /></el-form-item>
        <el-form-item label="明文文件名"><el-input v-model="filename" maxlength="200" :disabled="busy || !!pending" /></el-form-item>
      </el-form>
      <el-tag v-if="saved" type="success">已保存 · 未执行</el-tag>
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">关闭</el-button>
      <el-button v-if="saved" :icon="FolderOpened" @click="resetSaved">继续导入</el-button>
      <el-button v-if="saved" type="primary" @click="emit('imported', saved.sourceId!); visible = false">打开受管源</el-button>
      <el-button v-else :icon="Upload" type="primary" :disabled="!canSave" :loading="busy && !!preview" @click="save">
        {{ pending ? '核对同一保存请求' : '确认导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.plugin-input-picker { display: none; }
.plugin-input-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 0; }
.plugin-input-actions span { overflow-wrap: anywhere; }
.plugin-input-error { margin: 12px 0; }
</style>
