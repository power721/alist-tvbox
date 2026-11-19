<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadFile, request } from './api'

defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ imported: [sourceId: string] }>()
interface Preview {
  archiveSha256: string; originalSourceId: string; originalName: string; artifactSha256: string
  profileSha256: string; profile: unknown; fileCount: number; archiveBytes: number; expandedBytes: number
  configRebindRequired: boolean; reportPresent: boolean; authority: string; boundary: string
  archiveKind: string; candidateSha256: string | null
}
interface Imported {
  id: string; sourceId: string; archiveSha256: string; artifactSha256: string
  originalProfileSha256: string; restoredProfileSha256: string; configRebindRequired: boolean
  createdAt: string; authority: string
}
const visible = ref(false)
const busy = ref(false)
const archiveBase64 = ref('')
const archiveName = ref('')
const preview = ref<Preview>()
const imported = ref<Imported>()
const name = ref('')
const filename = ref('restored.py')
const requestKey = ref<string>()
const canRestore = computed(() => !busy.value && preview.value && !imported.value
  && name.value.trim() && /^[^/\\]+\.py$/.test(filename.value))
let selection = 0
let disposed = false
watch([name, filename], () => { requestKey.value = undefined }, { flush: 'sync' })

function fileBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
    reader.onerror = () => reject(new Error('ZIP 文件读取失败'))
    reader.onabort = () => reject(new Error('ZIP 文件读取已中断'))
    reader.readAsDataURL(file)
  })
}
async function readArchive(event: Event) {
  const current = ++selection
  const file = (event.target as HTMLInputElement).files?.[0]
  archiveBase64.value = ''; archiveName.value = ''
  preview.value = undefined; imported.value = undefined; requestKey.value = undefined
  name.value = ''; filename.value = 'restored.py'
  if (!file) return
  busy.value = true
  try {
    if (file.size > 4 * 1_048_576) throw new Error('ZIP 限制为 4 MiB')
    const body = await fileBase64(file)
    if (disposed || current !== selection) return
    archiveBase64.value = body
    archiveName.value = file.name
  } catch (error) {
    if (!disposed && current === selection) ElMessage.error(error instanceof Error ? error.message : 'ZIP 读取失败')
  } finally { if (!disposed && current === selection) busy.value = false }
}
async function previewArchive() {
  if (!archiveBase64.value || busy.value) return
  const current = selection
  busy.value = true
  try {
    const result = await request<Preview>('/source-imports/preview', { archiveBase64: archiveBase64.value })
    if (disposed || current !== selection) return
    const firstPreview = !preview.value
    preview.value = result
    if (firstPreview) name.value = result.originalName
  } catch (error) {
    if (!disposed && current === selection) ElMessage.error(error instanceof Error ? error.message : 'ZIP 校验失败')
  } finally { if (!disposed && current === selection) busy.value = false }
}
async function restoreArchive() {
  const checked = preview.value
  if (!checked || !canRestore.value) return
  if (!requestKey.value) {
    requestKey.value = Array.from(crypto.getRandomValues(new Uint8Array(16)),
      byte => byte.toString(16).padStart(2, '0')).join('')
  }
  const current = selection
  const input = { archiveBase64: archiveBase64.value, expectedArchiveSha256: checked.archiveSha256,
    requestKey: requestKey.value, name: name.value, filename: filename.value }
  busy.value = true
  try {
    const result = await request<Imported>('/source-imports', input)
    if (disposed || current !== selection) return
    imported.value = result
    emit('imported', result.sourceId)
    ElMessage.success('已恢复为新本地源，请重新绑定配置并建立探测证据')
  } catch (error) {
    if (!disposed && current === selection) ElMessage.error(error instanceof Error ? error.message : '恢复失败')
  } finally { if (!disposed && current === selection) busy.value = false }
}
function beforeClose(done: () => void) { if (!busy.value) done() }
onUnmounted(() => { disposed = true; selection++ })
</script>

<template>
  <el-button :disabled="disabled || busy" @click="visible = true">导入维护 ZIP</el-button>
  <el-dialog v-model="visible" title="导入源码或候选维护包" width="min(840px, 94vw)"
             :before-close="beforeClose" :close-on-click-modal="false" :close-on-press-escape="!busy"
             :show-close="!busy">
    <p>校验源码证据包后，显式恢复为新的本地源。原 ZIP 与历史报告保留归档，历史结果不作为当前环境的通过证据。</p>
    <p class="boundary">恢复过程不执行源码，不启用 AT 绑定、巡检或模型授权；配置需重新绑定。</p>
    <el-form label-position="top">
      <el-form-item label="选择源码证据 ZIP（最多 4 MiB）">
        <input type="file" accept=".zip,application/zip" :disabled="busy" @change="readArchive" />
      </el-form-item>
      <p v-if="archiveName">{{ archiveName }}</p>
      <el-button :disabled="!archiveBase64 || busy || !!imported" :loading="busy && !preview"
                 @click="previewArchive">校验并预览</el-button>
      <template v-if="preview">
        <p>{{ preview.boundary }}</p>
        <p>文件数 {{ preview.fileCount }} · ZIP {{ preview.archiveBytes }} 字节 · 展开 {{ preview.expandedBytes }} 字节</p>
        <p class="sha">原源 ID {{ preview.originalSourceId }}</p>
        <p class="sha">包 SHA-256 {{ preview.archiveSha256 }}</p>
        <p class="sha">源码 SHA-256 {{ preview.artifactSha256 }}</p>
        <p v-if="preview.candidateSha256" class="sha">待复用候选 {{ preview.candidateSha256 }}</p>
        <p class="sha">原画像 SHA-256 {{ preview.profileSha256 }}</p>
        <p>{{ preview.reportPresent ? '含历史探测报告，仅随原包归档。' : '包内未提供探测报告。' }}</p>
        <p v-if="preview.configRebindRequired">原包存在配置指纹；原 EXT 与指纹盐不随包恢复，请重新提供配置。</p>
        <details><summary>包内能力声明</summary><pre>{{ JSON.stringify(preview.profile, null, 2) }}</pre></details>
        <el-form-item label="新本地源名称">
          <el-input v-model="name" maxlength="200" :disabled="busy || !!imported" />
        </el-form-item>
        <el-form-item label="源码文件名">
          <el-input v-model="filename" maxlength="200" :disabled="busy || !!imported" />
        </el-form-item>
      </template>
    </el-form>
    <template v-if="imported">
      <el-alert title="源码已保存；请按新环境配置重新探测。" type="success" :closable="false" />
      <p class="sha">新本地源 {{ imported.sourceId }}</p>
      <p class="sha">恢复后的画像 {{ imported.restoredProfileSha256 }}</p>
      <el-link type="primary" @click="downloadFile(`/source-imports/${encodeURIComponent(imported.id)}/archive`, '导入原包.zip').catch(error => ElMessage.error(error.message))">
        下载保存的原 ZIP
      </el-link>
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="!canRestore" :loading="busy && !!preview"
                 @click="restoreArchive">确认恢复为新本地源</el-button>
    </template>
  </el-dialog>
</template>
