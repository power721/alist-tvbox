<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { Document, FolderOpened } from '@element-plus/icons-vue'
import { readInventory, inventoryStatuses, type InventoryEntry, type Inventory } from './nasInventory'

interface Source { id: string; name: string; artifactSha256: string }
const props = defineProps<{ disabled?: boolean; sources: Source[] }>()
const emit = defineEmits<{ selected: [id: string] }>()
const visible = ref(false)
const picker = ref<HTMLInputElement>()
const inventory = ref<Inventory>()
const fileSha = ref('')
const busy = ref(false)
const error = ref('')
const query = ref('')
const kind = ref('all')
const entries = computed(() => (inventory.value?.files ?? []).filter(row =>
  (kind.value === 'all' || row.kind === kind.value)
  && row.path.toLocaleLowerCase().includes(query.value.toLocaleLowerCase())))
const totals = computed(() => ({
  python: inventory.value?.files.filter(row => row.kind === 'python').length ?? 0,
  javascript: inventory.value?.files.filter(row => row.kind === 'javascript').length ?? 0,
  hashed: inventory.value?.files.filter(row => row.status === 'hashed').length ?? 0
}))
function matches(row: InventoryEntry) {
  return row.status === 'hashed' ? props.sources.filter(source => source.artifactSha256 === row.sha256) : []
}
let disposed = false
async function load(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || busy.value) return
  inventory.value = undefined
  fileSha.value = ''
  query.value = ''
  kind.value = 'all'
  error.value = ''
  busy.value = true
  try {
    const result = await readInventory(file)
    if (!disposed) { inventory.value = result.inventory; fileSha.value = result.fileSha256 }
  } catch (reason) {
    if (!disposed) error.value = reason instanceof Error ? reason.message : 'NAS 快照读取失败'
  } finally { if (!disposed) busy.value = false }
}
function openSource(id: string) { visible.value = false; emit('selected', id) }
onUnmounted(() => { disposed = true })
</script>

<template>
  <el-button :icon="Document" :disabled="disabled" @click="visible = true">NAS 目录快照</el-button>
  <el-dialog v-model="visible" title="NAS 目录快照" width="min(1080px, 96vw)"
             class="nas-directory-dialog" :close-on-click-modal="false">
    <div class="nas-actions">
      <input ref="picker" type="file" accept=".json,application/json" class="nas-inventory-picker"
             aria-label="选择 NAS 目录快照" :disabled="busy" @change="load" />
      <el-button :icon="FolderOpened" :loading="busy" @click="picker?.click()">打开快照</el-button>
      <el-tag type="info">本地文件 · 未签名</el-tag><el-tag type="warning">不代表当前装载状态</el-tag>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="inventory">
      <dl class="nas-owner">
        <dt>目录归属</dt><dd>{{ inventory.owner.target === 't4_custom' ? 'T4 自定义目录' : 'AT 静态目录' }}</dd>
        <dt>扫描时间</dt><dd>{{ new Date(inventory.observedAt).toLocaleString() }}</dd>
        <dt>容器目录</dt><dd>{{ inventory.owner.containerDirectory }}</dd>
        <dt>NAS 目录</dt><dd>{{ inventory.owner.hostDirectory }}</dd>
        <dt>容器身份</dt><dd><code>{{ inventory.owner.containerId }}</code></dd>
        <dt>镜像身份</dt><dd><code>{{ inventory.owner.imageId }}</code></dd>
      </dl>
      <p class="nas-totals">Python {{ totals.python }} · JavaScript {{ totals.javascript }} ·
        字节已核对 {{ totals.hashed }} / {{ inventory.files.length }} ·
        读取 {{ inventory.scan.sourceBytesRead.toLocaleString() }} 字节</p>
      <p class="nas-totals">遍历 {{ inventory.scan.visitedEntries }} · 忽略 {{ inventory.scan.ignoredEntries }} ·
        {{ inventory.scan.complete ? '限定范围遍历完成' : '范围不完整' }} · 源码未执行 · 内容未导出</p>
      <el-tabs>
        <el-tab-pane label="文件盘点">
          <div class="nas-filters">
            <el-radio-group v-model="kind" aria-label="NAS 源码类型">
              <el-radio-button value="all">全部</el-radio-button>
              <el-radio-button value="python">Python</el-radio-button>
              <el-radio-button value="javascript">JavaScript</el-radio-button>
            </el-radio-group>
            <el-input v-model="query" placeholder="筛选 NAS 相对路径" clearable aria-label="筛选 NAS 相对路径" />
          </div>
          <el-table :data="entries" row-key="path" height="340" class="nas-inventory-table"
                    empty-text="限定范围内未发现 .py/.js 文件">
            <el-table-column label="相对路径" min-width="220">
              <template #default="{ row }"><span class="nas-path">{{ row.path }}</span>
                <small>{{ row.bytes.toLocaleString() }} 字节 · {{ row.kind }}</small></template>
            </el-table-column>
            <el-table-column label="SHA-256" min-width="180">
              <template #default="{ row }"><code>{{ row.sha256 || '未读取' }}</code></template>
            </el-table-column>
            <el-table-column label="读取状态" min-width="130">
              <template #default="{ row }">{{ inventoryStatuses[row.status] }}</template>
            </el-table-column>
            <el-table-column label="受管库内容匹配" min-width="180">
              <template #default="{ row }">
                <el-button v-for="source in matches(row)" :key="source.id" link type="primary"
                           @click="openSource(source.id)">{{ source.name }}</el-button>
                <span v-if="!matches(row).length">无匹配记录</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="快照绑定">
          <dl class="nas-owner">
            <dt>文件 SHA-256</dt><dd><code>{{ fileSha }}</code></dd>
            <dt>扫描器 SHA-256</dt><dd><code>{{ inventory.scannerSha256 }}</code></dd>
            <dt>挂载源</dt><dd>{{ inventory.owner.mountSource }}</dd>
            <dt>挂载目标</dt><dd>{{ inventory.owner.mountDestination }}</dd>
            <dt>证据级别</dt><dd>导入文件声明，未进行在线身份复验</dd>
            <dt>生产状态</dt><dd>快照声明扫描前后容器身份不变</dd>
            <dt>模型与发布</dt><dd>未调用 · 未发布</dd>
          </dl>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-empty v-else-if="!busy && !error" description="尚未打开 NAS 目录快照" />
    <template #footer><el-button @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.nas-actions, .nas-filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px; }
.nas-inventory-picker { display: none; }
.nas-owner { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 8px 14px; font-size: 13px; }
.nas-owner dt { color: #647080; }
.nas-owner dd { margin: 0; overflow-wrap: anywhere; }
.nas-totals { font-size: 13px; overflow-wrap: anywhere; }
.nas-filters .el-input { flex: 1 1 220px; }
.nas-path, small { display: block; overflow-wrap: anywhere; }
small { color: #647080; }
code { word-break: break-all; }
@media (max-width: 600px) { .nas-owner { grid-template-columns: 76px minmax(0, 1fr); gap: 8px; } }
</style>
