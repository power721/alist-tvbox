<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { Download, FullScreen, Refresh, Upload, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { request } from './api'

interface LogEvent { id: string; time: string; stage: string; status: string; code: string; message: string }
interface ProcessLog {
  format: string; sourceName: string; runId?: string; active: boolean; status?: string; authority: string
  events: LogEvent[]; importId?: string
  sessions?: { runId: string; createdAt: string; mode: string; status: string }[]
  analysis?: { eventCount: number; problemCount: number; problems: LogEvent[] }
}
const props = defineProps<{ sourceId?: string; runId?: string }>()
const emit = defineEmits<{ refreshed: [] }>()
const log = ref<ProcessLog>()
const imported = ref<ProcessLog>()
const selectedRunId = ref('')
const visible = ref(false)
const following = ref(true)
const busy = ref(false)
const error = ref('')
const logBody = ref<HTMLElement>()
const dialogBody = ref<HTMLElement>()
const upload = ref<HTMLInputElement>()
const display = computed(() => imported.value ?? log.value)
const stages: Record<string, string> = { inspection: '巡检', repair: '修复', model: '模型', validation: '复测', workflow: '流程' }
const statuses: Record<string, string> = {
  SUBMITTED: '已发送', CREATED: '已保存', QUEUED: '排队', RUNNING: '执行中', COMPLETED: '完成',
  SUCCEEDED: '通过', CHECKED: '已检查', CHECKING: '联网巡检中', REPAIRING: '修复中',
  STOPPED: '暂停', BLOCKED: '受阻', FAILED: '失败', REJECTED: '拒绝', CANCELLED: '已取消',
  AWAITING_PROPOSAL: '等待模型建议', VALIDATING: '代码验证中', VALIDATING_RUNTIME: '联网复测中',
  RUNTIME_OBSERVED: '已取得运行结果', AWAITING_RUNTIME_VALIDATION: '待联网复测',
  pass: '通过', fail: '失败', blocked: '受阻', not_tested: '未测'
}
let disposed = false
let revision = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function refresh() {
  if (timer) clearTimeout(timer)
  if (!props.sourceId || busy.value || disposed) return
  const current = revision
  busy.value = true
  try {
    const suffix = selectedRunId.value ? `?runId=${encodeURIComponent(selectedRunId.value)}` : ''
    const result = await request<ProcessLog>(`/sources/${props.sourceId}/maintenance-log${suffix}`)
    if (disposed || current !== revision) return
    const before = log.value
    log.value = result
    error.value = ''
    if (before && JSON.stringify(before.events) !== JSON.stringify(result.events)) emit('refreshed')
    if (following.value && !imported.value) {
      await nextTick()
      for (const element of [logBody.value, dialogBody.value]) if (element) element.scrollTop = element.scrollHeight
    }
  } catch (reason) {
    if (!disposed && current === revision) error.value = reason instanceof Error ? reason.message : '过程日志读取失败'
  } finally {
    busy.value = false
    if (!disposed && props.sourceId) timer = setTimeout(refresh, current !== revision ? 0 : log.value?.active ? 1500 : 6000)
  }
}

function show(runId?: string) {
  imported.value = undefined
  if (runId) selectedRunId.value = runId
  visible.value = true
  void refresh()
}
function download() {
  if (!display.value) return
  const blob = new Blob([JSON.stringify(display.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${display.value.sourceName || '爬虫'}_维修日志_${display.value.runId || display.value.importId || '未执行'}.json`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
async function importLog(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    if (file.size > 2_097_152) throw new Error('日志文件超过 2 MiB')
    const body: unknown = JSON.parse(await file.text())
    imported.value = await request<ProcessLog>('/maintenance-log-imports', body)
    error.value = ''
    visible.value = true
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '日志导入失败' }
}
function returnToLive() { imported.value = undefined; void refresh() }
watch(() => [props.sourceId, props.runId], () => {
  revision++
  selectedRunId.value = props.runId ?? ''
  log.value = undefined
  imported.value = undefined
  error.value = ''
  if (timer) clearTimeout(timer)
  void refresh()
}, { immediate: true })
onUnmounted(() => { disposed = true; revision++; if (timer) clearTimeout(timer) })
defineExpose({ show })
</script>

<template>
  <section class="process-log" aria-label="探测与修复日志">
    <div class="process-heading">
      <h3>探测与修复日志 <span v-if="log?.active" class="live-indicator">进行中</span></h3>
      <div class="actions">
        <el-tooltip content="导入日志分析"><el-button :icon="Upload" aria-label="导入日志分析" @click="upload?.click()" /></el-tooltip>
        <el-tooltip content="导出本次日志"><el-button :icon="Download" :disabled="!log?.runId" aria-label="导出本次日志" @click="returnToLive(); download()" /></el-tooltip>
        <el-tooltip content="打开过程窗口"><el-button :icon="FullScreen" aria-label="打开过程窗口" @click="show()" /></el-tooltip>
      </div>
    </div>
    <input ref="upload" class="log-upload" type="file" accept=".json,application/json" aria-label="维修日志文件" @change="importLog" />
    <p v-if="error" class="log-error" role="alert">{{ error }}</p>
    <div ref="logBody" class="log-lines" aria-live="polite" aria-relevant="additions text">
      <p v-if="!log?.events.length" class="log-empty">暂无执行记录</p>
      <div v-for="entry in log?.events.slice(-40)" :key="entry.id" class="log-line" :class="entry.status.toLowerCase()">
        <time>{{ new Date(entry.time).toLocaleTimeString() }}</time>
        <span>{{ stages[entry.stage] || entry.stage }}</span>
        <p>{{ entry.message }} · {{ statuses[entry.status] || entry.status }}<code v-if="entry.code"> {{ entry.code }}</code></p>
      </div>
    </div>
  </section>
  <el-dialog v-model="visible" :title="`${display?.sourceName || '爬虫'} · ${imported ? '日志分析' : '维修过程'}`"
             width="min(780px, 94vw)" draggable :modal="false" :close-on-click-modal="false" class="process-window">
    <div class="process-controls">
      <el-select v-if="!imported && log?.sessions?.length" v-model="selectedRunId" aria-label="维修记录" @change="refresh">
        <el-option v-for="session in log.sessions" :key="session.runId" :value="session.runId"
                   :label="`${new Date(session.createdAt).toLocaleString()} · ${statuses[session.status] || session.status}`" />
      </el-select>
      <el-tag v-if="display?.active" type="warning">{{ statuses[display.status || ''] || display.status }}</el-tag>
      <el-tag v-if="imported" type="info">外部日志 · 不作为验收凭证</el-tag>
      <el-tooltip :content="following ? '暂停自动滚动' : '跟随最新日志'">
        <el-button :icon="following ? VideoPause : VideoPlay" :aria-label="following ? '暂停自动滚动' : '跟随最新日志'" @click="following = !following" />
      </el-tooltip>
    </div>
    <p v-if="error" class="log-error" role="alert">{{ error }}</p>
    <div ref="dialogBody" class="log-lines expanded" aria-live="polite">
      <p v-if="!display?.events.length" class="log-empty">暂无执行记录</p>
      <div v-for="entry in display?.events" :key="entry.id" class="log-line" :class="entry.status.toLowerCase()">
        <time>{{ new Date(entry.time).toLocaleTimeString() }}</time>
        <span>{{ stages[entry.stage] || entry.stage }}</span>
        <p>{{ entry.message }} · {{ statuses[entry.status] || entry.status }}<code v-if="entry.code"> {{ entry.code }}</code></p>
      </div>
    </div>
    <div v-if="display?.analysis" class="log-analysis">
      <strong>{{ display.analysis.eventCount }} 条过程记录 · {{ display.analysis.problemCount }} 项异常 / 阻断</strong>
      <p v-for="entry in display.analysis.problems" :key="entry.id">{{ stages[entry.stage] || entry.stage }}：{{ entry.message }} <code>{{ entry.code }}</code></p>
    </div>
    <template #footer>
      <el-button v-if="imported" :icon="Refresh" @click="returnToLive">返回当前过程</el-button>
      <el-button :icon="Upload" @click="upload?.click()">导入分析</el-button>
      <el-button :icon="Download" :disabled="!display?.events.length" @click="download">导出日志</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.process-log { padding: 12px 0 18px; border-bottom: 1px solid #e0e4e5; margin-bottom: 14px; }
.process-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.process-heading h3 { margin: 0; font-size: 14px; }
.process-heading .actions { gap: 6px; }
.process-heading .el-button { padding: 7px; }
.live-indicator { font-size: 12px; font-weight: 400; color: #287454; margin-left: 8px; }
.log-lines { margin-top: 10px; height: 190px; overflow: auto; background: #f6f7f8; border: 1px solid #e1e5e7; padding: 8px 10px; }
.log-lines.expanded { height: min(46vh, 420px); }
.log-line { display: grid; grid-template-columns: 68px 34px minmax(0, 1fr); gap: 8px; font-size: 12px; line-height: 1.8; padding: 4px 0; }
.log-line time, .log-line > span { color: #72797f; }
.log-line p { margin: 0; overflow-wrap: anywhere; }
.log-line.failed p, .log-line.fail p, .log-line.stopped p, .log-line.blocked p { color: #a34b33; }
.log-empty { color: #737a80; font-size: 13px; }
.process-controls { display: flex; flex-wrap: wrap; gap: 8px; }
.process-controls .el-select { flex: 1 1 220px; min-width: 0; }
.log-error { color: #ac3f3f; overflow-wrap: anywhere; }
.log-upload { display: none; }
.log-analysis { margin-top: 14px; font-size: 13px; max-height: 130px; overflow: auto; }
.log-analysis p { margin: 6px 0; overflow-wrap: anywhere; }
@media (max-width: 500px) { .log-line { grid-template-columns: 62px 28px minmax(0, 1fr); gap: 5px; } }
</style>
