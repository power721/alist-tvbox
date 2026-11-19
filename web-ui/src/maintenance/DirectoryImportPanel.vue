<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { FolderOpened, Refresh, Upload } from '@element-plus/icons-vue'
import { request } from './api'
import { previewDirectory, type DirectoryEntry, type DirectoryPreview } from './directory'

interface SourceIdentity { id: string; name: string; filename: string; artifactSha256: string }
const props = defineProps<{ disabled?: boolean; sources: SourceIdentity[] }>()
const emit = defineEmits<{ imported: [sourceId: string] }>()
const visible = ref(false)
const busy = ref(false)
const picker = ref<HTMLInputElement>()
const preview = ref<DirectoryPreview>()
const query = ref('')
const error = ref('')
const receipts = ref<Record<string, { id?: string; uncertain?: boolean; message?: string }>>({})
const refreshedSources = ref<SourceIdentity[]>([])
const knownSources = computed(() => [...props.sources, ...refreshedSources.value])
const filtered = computed(() => (preview.value?.entries ?? []).filter(entry =>
  entry.path.toLocaleLowerCase().includes(query.value.toLocaleLowerCase())))
const readyCount = computed(() => (preview.value?.entries ?? []).filter(canImport).length)
let generation = 0
let disposed = false

function existing(entry: DirectoryEntry) {
  return knownSources.value.find(source => source.artifactSha256 === entry.sha256)
}
function canImport(entry: DirectoryEntry): boolean {
  return entry.content !== undefined && !entry.problem && !receipts.value[entry.path] && !existing(entry)
}
function status(entry: DirectoryEntry): string {
  const receipt = receipts.value[entry.path]
  if (receipt?.id) return '已保存'
  if (receipt?.uncertain) return '待核对保存结果'
  if (entry.problem) return entry.problem
  if (existing(entry)) return '受管库已有相同内容'
  return '待确认'
}
async function readDirectory(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  const current = ++generation
  busy.value = true
  preview.value = undefined
  receipts.value = {}
  refreshedSources.value = []
  error.value = ''
  query.value = ''
  try {
    const result = await previewDirectory(files, () => disposed || current !== generation)
    if (!disposed && current === generation) preview.value = result
  } catch (reason) {
    if (!disposed && current === generation) error.value = reason instanceof Error ? reason.message : '目录读取失败'
  } finally { if (!disposed && current === generation) busy.value = false }
}
async function importEntry(entry: DirectoryEntry) {
  if (busy.value || props.disabled || !canImport(entry)) return
  const name = entry.name.trim()
  if (!name || name.length > 200) { ElMessage.error('名称须为 1–200 字符'); return }
  busy.value = true
  error.value = ''
  // A failed transport may still have committed. Never automatically replay this POST.
  receipts.value[entry.path] = { uncertain: true }
  try {
    const source = await request<SourceIdentity>('/sources', {
      name, filename: entry.filename, content: entry.content
    })
    if (!source.id || source.artifactSha256 !== entry.sha256 || source.filename !== entry.filename) {
      throw new Error('保存回执与所选文件不一致，请核对受管库')
    }
    receipts.value[entry.path] = { id: source.id }
    refreshedSources.value.push(source)
    emit('imported', source.id)
    ElMessage.success('已保存源码，尚未执行')
  } catch (reason) {
    const message = reason instanceof Error ? reason.message : '保存结果未知'
    receipts.value[entry.path] = { uncertain: true, message }
    error.value = `${entry.path}：${message}。请核对保存结果，勿重复提交。`
  } finally { busy.value = false }
}
async function reconcile() {
  busy.value = true
  error.value = ''
  try {
    refreshedSources.value = await request<SourceIdentity[]>('/sources')
    for (const entry of preview.value?.entries ?? []) {
      if (!receipts.value[entry.path]?.uncertain) continue
      const matches = refreshedSources.value.filter(source => source.artifactSha256 === entry.sha256
        && source.name === entry.name.trim() && source.filename === entry.filename)
      if (matches.length === 1) receipts.value[entry.path] = { id: matches[0]!.id }
    }
    if (Object.values(receipts.value).some(receipt => receipt.uncertain)) {
      error.value = '仍有结果无法确认的保存请求；本次预览禁止重试，请在受管库核对。'
    }
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '核对失败' }
  finally { busy.value = false }
}
function openEntry(entry: DirectoryEntry) {
  const id = receipts.value[entry.path]?.id ?? existing(entry)?.id
  if (id) { emit('imported', id); visible.value = false }
}
function beforeClose(done: () => void) { if (!busy.value) done() }
onUnmounted(() => { disposed = true; generation++ })
</script>

<template>
  <el-button :icon="FolderOpened" :disabled="disabled || busy" @click="visible = true">读取爬虫目录</el-button>
  <el-dialog v-model="visible" title="自定义爬虫目录" width="min(1120px, 96vw)"
             :before-close="beforeClose" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <div class="directory-actions">
      <input ref="picker" class="directory-picker" type="file" webkitdirectory directory multiple
             aria-label="选择爬虫目录" :disabled="busy || disabled" @change="readDirectory" />
      <el-button :icon="FolderOpened" :disabled="busy || disabled" :loading="busy && !preview"
                 @click="picker?.click()">选择目录</el-button>
      <el-button :icon="Refresh" :disabled="busy || !preview" @click="reconcile">核对保存结果</el-button>
      <el-tag type="info">本地预览</el-tag><el-tag type="warning">未执行源码</el-tag>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="preview">
      <p class="directory-summary">{{ preview.root }} · Python {{ preview.entries.length }} 个 ·
        {{ preview.totalBytes.toLocaleString() }} 字节 · 忽略 {{ preview.ignored }} 个 · 待确认 {{ readyCount }} 个</p>
      <el-input v-model="query" placeholder="筛选相对路径" clearable aria-label="筛选相对路径" />
      <el-table :data="filtered" row-key="path" height="420" class="directory-table">
        <el-table-column label="文件" min-width="220">
          <template #default="{ row }"><span class="directory-path">{{ row.path }}</span>
            <small>{{ row.bytes.toLocaleString() }} 字节</small></template>
        </el-table-column>
        <el-table-column label="源码 SHA-256" min-width="180">
          <template #default="{ row }"><code>{{ row.sha256 || '未读取' }}</code></template>
        </el-table-column>
        <el-table-column label="受管源名称" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.name" :disabled="busy || !canImport(row)" maxlength="200"
                      :aria-label="`${row.path} 的源名称`" />
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="150">
          <template #default="{ row }">{{ status(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="receipts[row.path]?.id || existing(row)" link type="primary" :disabled="busy || disabled"
                       @click="openEntry(row)">打开受管源</el-button>
            <el-button v-else :icon="Upload" :disabled="busy || disabled || !canImport(row)"
                       @click="importEntry(row)">确认导入</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-empty v-else-if="!busy" description="尚未选择目录" />
    <template #footer><el-button :disabled="busy" @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.directory-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 16px; }
.directory-actions .el-button + .el-button { margin-left: 0; }
.directory-picker { display: none; }
.directory-summary { overflow-wrap: anywhere; }
.directory-table { margin-top: 12px; }
.directory-path, small { display: block; overflow-wrap: anywhere; }
small { color: #647080; }
code { word-break: break-all; }
</style>
