<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { request } from './api'

const props = defineProps<{ sourceId: string; artifactSha256: string }>()
const emit = defineEmits<{ changed: [] }>()
interface State {
  sourceId: string; revision: number; baseSha256: string; headSha256: string
  effectiveSha256: string; status: string; updatedAt: string
}
interface CoordinationEvent { id: string; operation: string; after: State; createdAt: string }
interface Binding { sha256: string; connectionMatches: boolean; snapshot: { plugin: { kind: string } } }
const state = ref<State>()
const binding = ref<Binding>()
const events = ref<CoordinationEvent[]>([])
const artifacts = ref<{ sha256: string }[]>([])
const baseline = ref(props.artifactSha256)
const content = ref('')
const busy = ref(false)
const labels: Record<string, string> = {
  IN_SYNC: '上游与本地字节一致', LOCAL_AHEAD: '存在本地修改', UPSTREAM_AHEAD: '上游已更新，待采用',
  DIVERGED: '双方均已修改，需人工协调', INITIALIZE: '声明共同基线', OBSERVE: '登记上游版本',
  FAST_FORWARD: '采用上游版本', KEEP_LOCAL: '确认保留本地版本', FETCH_AT: '读取 AT 当前源码'
}
const canFetchAt = computed(() => !busy.value && binding.value?.connectionMatches
  && binding.value.snapshot.plugin.kind === 'python')
const roles = computed(() => state.value ? [
  { role: '共同比较基线', sha: state.value.baseSha256 },
  { role: '最新登记的上游', sha: state.value.headSha256 },
  { role: '本地台账有效版本', sha: state.value.effectiveSha256 }
] : [])
const path = computed(() => `/sources/${encodeURIComponent(props.sourceId)}/upstream`)
let disposed = false
let loadId = 0
let pending: { signature: string; body: Record<string, unknown> } | undefined

async function load() {
  const id = ++loadId
  const [current, history, versions, bound] = await Promise.all([
    request<State | undefined>(path.value), request<CoordinationEvent[]>(`${path.value}/events`),
    request<{ sha256: string }[]>(`/sources/${encodeURIComponent(props.sourceId)}/artifacts`),
    request<Binding | undefined>(`/sources/${encodeURIComponent(props.sourceId)}/at-binding`)
  ])
  if (disposed || id !== loadId) return
  state.value = current
  events.value = history
  artifacts.value = versions
  binding.value = bound
}
async function refresh() {
  busy.value = true
  try {
    await load()
    if (!disposed) emit('changed')
  } catch (error) {
    if (!disposed) ElMessage.error(error instanceof Error ? error.message : '读取失败')
  } finally { busy.value = false }
}
async function submit(operation: string, data: Record<string, unknown>) {
  busy.value = true
  const endpoint = path.value + '/' + operation
  const signature = JSON.stringify([endpoint, data])
  if (pending?.signature !== signature) pending = { signature, body: { ...data, requestKey: crypto.randomUUID() } }
  try {
    await request<CoordinationEvent>(endpoint, pending.body)
    pending = undefined
    if (disposed) return
    await load()
    emit('changed')
    ElMessage.success(operation === 'at-observations' ? 'AT 源码对比已保存，本地版本保持不变' : '协调记录已保存')
  } catch (error) {
    const message = error instanceof Error ? error.message : '上游协调失败'
    if (!disposed) ElMessage.error(['SOURCE_CHANGED', 'UPSTREAM_CHANGED', 'UPSTREAM_ALREADY_INITIALIZED'].includes(message)
      ? `${message}：请刷新协调状态，核对版本后再提交` : message)
  } finally { busy.value = false }
}
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 1048576) { ElMessage.error('源码不能超过 1 MiB'); return }
  try {
    const decoded = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer())
    if (!disposed) content.value = decoded
  } catch { if (!disposed) ElMessage.error('文件必须是有效 UTF-8 源码') }
}
function observe() {
  if (!state.value) return
  return submit('observations', {
    expectedRevision: state.value.revision, expectedArtifactSha256: props.artifactSha256, content: content.value
  })
}
function fetchAt() {
  if (!canFetchAt.value || !binding.value) return
  return submit('at-observations', {
    expectedRevision: state.value?.revision ?? null,
    expectedArtifactSha256: props.artifactSha256,
    expectedBindingSha256: binding.value.sha256,
    authorized: true
  })
}
function resolve(decision: string) {
  if (!state.value) return
  return submit('resolve', {
    expectedRevision: state.value.revision, expectedArtifactSha256: props.artifactSha256, decision
  })
}
watch([() => props.sourceId, () => props.artifactSha256], () => {
  load().catch(error => { if (!disposed) ElMessage.error(error instanceof Error ? error.message : '读取失败') })
})
onMounted(() => load().catch(error => {
  if (!disposed) ElMessage.error(error instanceof Error ? error.message : '读取失败')
}))
onUnmounted(() => { disposed = true; loadId++ })
</script>

<template>
  <div class="upstream">
    <el-button type="primary" :icon="Download" :disabled="!canFetchAt" @click="fetchAt">读取 AT 当前源码</el-button>
    <el-button :icon="Refresh" :disabled="busy" @click="refresh">刷新状态</el-button>
    <template v-if="!state">
      <el-collapse><el-collapse-item title="手动指定共同基线" name="baseline">
      <el-form label-position="top">
        <el-form-item label="选择管理员确认的上游共同基线">
          <el-select v-model="baseline" style="width: 100%" :disabled="busy">
            <el-option v-for="item in artifacts" :key="item.sha256" :label="item.sha256" :value="item.sha256" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :disabled="busy || !baseline"
                   @click="submit('initialize', { expectedArtifactSha256: artifactSha256, baselineSha256: baseline })">
          确认共同基线并启用协调
        </el-button>
      </el-form>
      </el-collapse-item></el-collapse>
    </template>
    <template v-else>
      <h3>{{ labels[state.status] || state.status }}</h3>
      <el-table :data="roles">
        <el-table-column prop="role" label="版本角色" width="180" />
        <el-table-column label="SHA-256"><template #default="{ row }"><code>{{ row.sha }}</code></template></el-table-column>
      </el-table>
      <p><el-link :href="`/api/plugin-maintenance${path}/export`">下载三方源码比较包</el-link></p>
      <el-collapse><el-collapse-item title="手动登记上游源码" name="manual">
      <el-form label-position="top">
        <el-form-item label="导入管理员取得的上游 UTF-8 Python 源码">
          <input type="file" accept=".py" :disabled="busy" @change="readFile" />
        </el-form-item>
        <el-form-item label="上游源码（最多 1 MiB）">
          <el-input v-model="content" type="textarea" :rows="8" :disabled="busy" spellcheck="false" />
        </el-form-item>
        <el-button :disabled="busy || !content.trim()" @click="observe">登记上游版本</el-button>
      </el-form>
      </el-collapse-item></el-collapse>
      <el-button type="primary" :disabled="busy || state.status !== 'UPSTREAM_AHEAD'" @click="resolve('FAST_FORWARD')">
        采用上游为本地台账版本
      </el-button>
      <el-button :disabled="busy || state.status !== 'DIVERGED'" @click="resolve('KEEP_LOCAL')">
        确认保留本地版本
      </el-button>
      <h3>最近协调记录</h3>
      <el-table :data="events">
        <el-table-column label="操作" width="180">
          <template #default="{ row }">{{ labels[row.operation] || row.operation }}</template>
        </el-table-column>
        <el-table-column label="操作后的状态" min-width="180">
          <template #default="{ row }">{{ labels[row.after.status] || row.after.status }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="250" />
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.upstream p { color: #52606d; line-height: 1.6; }
.upstream code { overflow-wrap: anywhere; }
.upstream h3 { margin-top: 20px; }
</style>
