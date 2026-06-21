<template>
  <div class="ui left aligned container cards-grid">
    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>网络</span>
        </div>
      </template>
      <div>IP地址： {{ info.ip }}</div>
      <div>主机名： {{ info.hostName }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>数据库</span>
        </div>
      </template>
      <div>类型： {{ info.dbType || '-' }}</div>
      <div>产品： {{ info.dbProductName || '-' }} {{ info.dbVersion }}</div>
      <div>JDBC URL： {{ info.dbUrl || '-' }}</div>
      <div>驱动： {{ info.dbDriverName || '-' }} {{ info.dbDriverVersion }}</div>
      <div>方言： {{ info.dbDialect || '-' }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>Java</span>
        </div>
      </template>
      <div>版本： {{ info.javaVendor }} {{ info.javaVersion }}</div>
      <div>JRE目录： {{ info.javaHome }}</div>
      <div>JVM名称： {{ info.jvmName }}</div>
      <div>JVM CPU： {{ info.jvmCpus }}核</div>
      <div>JVM 内存：
        <span data-tooltip="空闲内存">{{ byte2string(info.jvmUsedMemory)}}</span> /
        <span data-tooltip="总内存">{{ byte2string(info.jvmTotalMemory) }}</span>
      </div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>系统</span>
        </div>
      </template>
      <div>名称： {{ info.osName }}</div>
      <div>版本： {{ info.osVersion }}</div>
      <div>架构： {{ info.osArch }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>用户</span>
        </div>
      </template>
      <div>用户名称： {{ info.userName }}</div>
      <div>用户目录： {{ info.userHome }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>其它</span>
        </div>
      </template>
      <div>工作目录： {{ info.workDir }}</div>
      <div>时区： {{ info.timezone }}</div>
      <div>AList端口： {{ info.alistPort }}</div>
      <div>PID： {{ info.pid }}</div>
    </el-card>

    <el-card class="box-card">
      <template #header>
        <div class="card-header">
          <span>性能监控</span>
          <el-button
            type="primary"
            size="small"
            @click="loadHealthMetrics"
            :loading="healthLoading"
            style="float: right;">
            {{ healthLoading ? '刷新中...' : '刷新' }}
          </el-button>
        </div>
      </template>
      <div v-if="healthMetrics">
        <div>线程数： {{ healthMetrics.threadCount }} / 峰值 {{ healthMetrics.peakThreadCount }}</div>
        <div>
          内存使用： {{ healthMetrics.usedMemoryMB }} MB / {{ healthMetrics.maxMemoryMB }} MB
          <el-tag
            :type="healthMetrics.memoryUsagePercent > 80 ? 'danger' : healthMetrics.memoryUsagePercent > 60 ? 'warning' : 'success'"
            size="small"
            style="margin-left: 8px;">
            {{ healthMetrics.memoryUsagePercent }}%
          </el-tag>
        </div>
        <div>总请求数： {{ healthMetrics.totalRequests }}</div>
        <div style="margin-top: 10px;">
          <el-button size="small" @click="showHealthDialog = true">查看详细指标</el-button>
          <el-button size="small" @click="showThreadsDialog = true">查看线程详情</el-button>
          <el-button size="small" type="success" @click="downloadDiagnostics">
            <el-icon><Download /></el-icon>
            下载诊断信息
          </el-button>
        </div>
      </div>
      <div v-else style="color: #909399;">
        点击刷新按钮加载监控数据
      </div>
    </el-card>

    <!-- 健康指标详情对话框 -->
    <el-dialog
      v-model="showHealthDialog"
      title="系统健康指标"
      width="800px"
      :close-on-click-modal="false">
      <div v-if="healthMetrics">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="线程数">{{ healthMetrics.threadCount }}</el-descriptions-item>
          <el-descriptions-item label="峰值线程数">{{ healthMetrics.peakThreadCount }}</el-descriptions-item>
          <el-descriptions-item label="守护线程数">{{ healthMetrics.daemonThreadCount }}</el-descriptions-item>
          <el-descriptions-item label="最大内存">{{ healthMetrics.maxMemoryMB }} MB</el-descriptions-item>
          <el-descriptions-item label="已用内存">{{ healthMetrics.usedMemoryMB }} MB</el-descriptions-item>
          <el-descriptions-item label="内存使用率">
            <el-tag :type="healthMetrics.memoryUsagePercent > 80 ? 'danger' : healthMetrics.memoryUsagePercent > 60 ? 'warning' : 'success'">
              {{ healthMetrics.memoryUsagePercent }}%
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总请求数" :span="2">{{ healthMetrics.totalRequests }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">API 性能统计（按最大耗时排序）</el-divider>
        <el-table :data="healthMetrics.apiStats" max-height="400" border>
          <el-table-column prop="api" label="API" width="300" />
          <el-table-column prop="count" label="调用次数" width="100" align="center" />
          <el-table-column prop="avgDurationMs" label="平均耗时" width="100" align="center">
            <template #default="scope">
              {{ scope.row.avgDurationMs }} ms
            </template>
          </el-table-column>
          <el-table-column prop="maxDurationMs" label="最大耗时" width="100" align="center">
            <template #default="scope">
              <el-tag :type="scope.row.maxDurationMs > 5000 ? 'danger' : scope.row.maxDurationMs > 3000 ? 'warning' : ''">
                {{ scope.row.maxDurationMs }} ms
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lastDurationMs" label="最近耗时" width="100" align="center">
            <template #default="scope">
              {{ scope.row.lastDurationMs }} ms
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="showHealthDialog = false">关闭</el-button>
        <el-button type="primary" @click="copyHealthMetrics">复制 JSON</el-button>
      </template>
    </el-dialog>

    <!-- 线程详情对话框 -->
    <el-dialog
      v-model="showThreadsDialog"
      title="线程详情（CPU 占用 Top 10）"
      width="900px"
      :close-on-click-modal="false">
      <div v-if="threadsInfo">
        <el-descriptions :column="2" border style="margin-bottom: 20px;">
          <el-descriptions-item label="总线程数">{{ threadsInfo.totalThreads }}</el-descriptions-item>
          <el-descriptions-item label="显示数量">Top 10</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">线程状态统计</el-divider>
        <div style="margin-bottom: 20px;">
          <el-tag
            v-for="(count, state) in threadsInfo.threadStates"
            :key="state"
            style="margin-right: 8px; margin-bottom: 8px;"
            :type="String(state) === 'BLOCKED' || String(state) === 'WAITING' ? 'warning' : ''">
            {{ state }}: {{ count }}
          </el-tag>
        </div>

        <el-divider content-position="left">CPU 占用前 10 的线程</el-divider>
        <el-table :data="threadsInfo.topThreads" max-height="500" border>
          <el-table-column prop="id" label="线程ID" width="80" align="center" />
          <el-table-column prop="name" label="线程名称" width="200" show-overflow-tooltip />
          <el-table-column prop="state" label="状态" width="120" align="center">
            <template #default="scope">
              <el-tag
                size="small"
                :type="scope.row.state === 'RUNNABLE' ? 'success' :
                       scope.row.state === 'BLOCKED' ? 'danger' :
                       scope.row.state === 'WAITING' ? 'warning' : ''">
                {{ scope.row.state }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="cpuTimeMs" label="CPU 时间(ms)" width="120" align="center">
            <template #default="scope">
              <el-tag
                size="small"
                :type="scope.row.cpuTimeMs > 10000 ? 'danger' : scope.row.cpuTimeMs > 5000 ? 'warning' : ''">
                {{ scope.row.cpuTimeMs }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="堆栈（前3层）">
            <template #default="scope">
              <div v-if="scope.row.stackTrace && scope.row.stackTrace.length > 0" style="font-size: 12px; line-height: 1.5;">
                <div v-for="(trace, idx) in scope.row.stackTrace" :key="idx" style="color: #606266;">
                  {{ idx + 1 }}. {{ trace }}
                </div>
              </div>
              <span v-else style="color: #909399;">无堆栈信息</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="showThreadsDialog = false">关闭</el-button>
        <el-button type="primary" @click="copyThreadsInfo">复制 JSON</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from "vue"
import axios from "axios"
import {ElMessage} from "element-plus"
import {Download} from "@element-plus/icons-vue"

const info = ref<any>({})
const healthMetrics = ref<any>(null)
const threadsInfo = ref<any>(null)
const healthLoading = ref(false)
const showHealthDialog = ref(false)
const showThreadsDialog = ref(false)

const load = () => {
  axios.get('/api/system').then(({data}) => {
    info.value = data
  })
}

const loadHealthMetrics = async () => {
  healthLoading.value = true
  try {
    const {data} = await axios.get('/api/health/metrics')
    healthMetrics.value = data
    ElMessage.success('健康指标已刷新')
  } catch (error) {
    ElMessage.error('加载健康指标失败')
    console.error('加载健康指标失败:', error)
  } finally {
    healthLoading.value = false
  }
}

const loadThreadsInfo = async () => {
  try {
    const {data} = await axios.get('/api/health/threads')
    threadsInfo.value = data
  } catch (error) {
    ElMessage.error('加载线程信息失败')
    console.error('加载线程信息失败:', error)
  }
}

// 当打开线程对话框时自动加载
const showThreadsDialogChanged = async (val: boolean) => {
  if (val && !threadsInfo.value) {
    await loadThreadsInfo()
  }
}

// 监听对话框打开
const originalShowThreadsDialog = ref(false)
const watchShowThreadsDialog = () => {
  if (showThreadsDialog.value !== originalShowThreadsDialog.value) {
    originalShowThreadsDialog.value = showThreadsDialog.value
    showThreadsDialogChanged(showThreadsDialog.value)
  }
}

// 使用 watch 替代上面的复杂逻辑
import {watch} from 'vue'
watch(showThreadsDialog, async (val) => {
  if (val && !threadsInfo.value) {
    await loadThreadsInfo()
  }
})

const copyHealthMetrics = () => {
  const text = JSON.stringify(healthMetrics.value, null, 2)
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

const copyThreadsInfo = () => {
  const text = JSON.stringify(threadsInfo.value, null, 2)
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

const downloadDiagnostics = async () => {
  try {
    ElMessage.info('正在收集诊断信息...')

    // 并行加载所有数据
    const [metricsRes, threadsRes, systemRes] = await Promise.all([
      axios.get('/api/health/metrics'),
      axios.get('/api/health/threads'),
      axios.get('/api/system')
    ])

    const diagnostics = {
      timestamp: new Date().toISOString(),
      collectedAt: new Date().toLocaleString('zh-CN'),
      system: systemRes.data,
      healthMetrics: metricsRes.data,
      threads: threadsRes.data
    }

    // 生成文件名
    const filename = `diagnostics-${new Date().getTime()}.json`

    // 创建 Blob 并下载
    const blob = new Blob([JSON.stringify(diagnostics, null, 2)], {type: 'application/json'})
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)

    ElMessage.success('诊断信息已下载')
  } catch (error) {
    ElMessage.error('下载诊断信息失败')
    console.error('下载诊断信息失败:', error)
  }
}

const KB = 1024;
const MB = 1024 * KB;
const GB = 1024 * MB;
const TB = 1024 * GB;
const PB = 1024 * TB;
const EB = 1024 * PB;
const ZB = 1024 * EB;

const number2string = (num: number, fractionDigits = 2) => {
  let str = num.toFixed(fractionDigits);
  while (str.endsWith('0')) {
    str = str.substring(0, str.length - 1);
  }
  if (str.endsWith('.')) {
    str = str.substring(0, str.length - 1);
  }
  return str;
}
const byte2string = (bytes: number, unit = '') => {
  if (bytes >= EB || unit === 'EB') {
    return number2string(bytes / EB) + ' EB';
  } else if (bytes >= PB || unit === 'PB') {
    return number2string(bytes / PB) + ' PB';
  } else if (bytes >= TB || unit === 'TB') {
    return number2string(bytes / TB) + ' TB';
  } else if (bytes >= GB || unit === 'GB') {
    return number2string(bytes / GB) + ' GB';
  } else if (bytes >= MB || unit === 'MB') {
    return number2string(bytes / MB) + ' MB';
  } else if (bytes >= KB || unit === 'KB') {
    return number2string(bytes / KB) + ' KB';
  } else {
    return bytes + ' bytes';
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.cards-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
  padding: 20px;
}

.box-card {
  width: 100%;
}

.box-card :deep(.el-card__header) {
  border-bottom: none;
  padding-bottom: 6px;
}

.box-card :deep(.el-card__body) {
  padding-top: 6px;
}

@media (max-width: 768px) {
  .cards-grid {
    grid-template-columns: 1fr;
  }
}
</style>
