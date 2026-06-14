<template>
  <el-dialog
    v-model="visible"
    title="远程同步"
    width="600px"
    :close-on-click-modal="false"
  >
    <el-steps :active="currentStep" finish-status="success" align-center>
      <el-step title="连接远端" />
      <el-step title="配置同步" />
      <el-step title="同步完成" />
    </el-steps>

    <!-- 步骤 1: 连接远端 -->
    <div v-if="currentStep === 0" style="padding: 20px 0;">
      <el-alert
        title="安全提醒"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 15px;"
      >
        <template #default>
          <div style="font-size: 13px; line-height: 1.6;">
            • 同步功能会传输账号凭据、API Key 等敏感信息<br/>
            • 请确保远端服务器是您信任的实例<br/>
            • 生产环境建议使用 HTTPS 连接
          </div>
        </template>
      </el-alert>

      <el-alert
        v-if="connectionForm.url && connectionForm.url.startsWith('http://')"
        title="HTTP 警告"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 15px;"
      >
        <template #default>
          您正在使用 HTTP 连接，数据将明文传输。生产环境请使用 HTTPS。
        </template>
      </el-alert>

      <el-form :model="connectionForm" label-width="100px">
        <el-form-item label="远端地址">
          <el-input v-model="connectionForm.url" placeholder="http://192.168.1.100:4567" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="connectionForm.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="connectionForm.password" type="password" placeholder="密码" />
        </el-form-item>
      </el-form>
    </div>

    <template #footer v-if="currentStep === 0">
      <span class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="handleConnect" :loading="connecting">
          连接并继续 →
        </el-button>
      </span>
    </template>

    <!-- 步骤 2: 配置同步 -->
    <div v-if="currentStep === 1" style="padding: 20px 0;">
      <el-alert
        :title="`远端版本：${remoteInfo.appVersion}`"
        type="success"
        :closable="false"
        style="margin-bottom: 20px;"
      />

      <el-form :model="syncConfig" label-width="120px">
        <el-form-item label="同步方向">
          <el-radio-group v-model="syncConfig.direction">
            <el-radio label="push">推送到远端</el-radio>
            <el-radio label="pull">从远端拉取</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="合并策略" v-if="syncConfig.direction === 'pull'">
          <el-radio-group v-model="syncConfig.strategy">
            <el-radio label="overwrite">覆盖本地</el-radio>
            <el-radio label="merge">智能合并</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="选择模块">
          <el-checkbox-group v-model="syncConfig.modules">
            <el-checkbox label="sites">外部站点 (Sites)</el-checkbox><br/>
            <el-checkbox label="shares">网盘分享 (Shares)</el-checkbox><br/>
            <el-checkbox label="accounts">
              阿里云账号 (Accounts)
              <el-tooltip content="包含 refresh_token 等认证信息" placement="top">
                <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
              </el-tooltip>
            </el-checkbox><br/>
            <el-checkbox label="driverAccounts">
              网盘账号 (DriverAccounts)
              <el-tooltip content="包含各网盘的 Cookie、Token、密码" placement="top">
                <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
              </el-tooltip>
            </el-checkbox><br/>
            <el-checkbox label="pikpakAccounts">
              PikPak账号 (PikPakAccounts)
              <el-tooltip content="包含 PikPak 用户名和密码" placement="top">
                <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
              </el-tooltip>
            </el-checkbox><br/>
            <el-checkbox label="subscriptions">订阅配置 (Subscriptions)</el-checkbox><br/>
            <el-checkbox label="settings">
              系统设置 (Settings)
              <el-tooltip content="包含 API Key、Cookie、用户名/密码等敏感配置" placement="top">
                <el-icon style="color: #E6A23C; margin-left: 4px;"><Warning /></el-icon>
              </el-tooltip>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
    </div>

    <template #footer v-if="currentStep === 1">
      <span class="dialog-footer">
        <el-button @click="currentStep = 0">← 上一步</el-button>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="handleSync" :loading="syncing">
          执行同步
        </el-button>
      </span>
    </template>

    <!-- 步骤 3: 同步结果 -->
    <div v-if="currentStep === 2" style="padding: 20px 0;">
      <div v-for="(result, module) in syncResults" :key="module" style="margin-bottom: 15px;">
        <el-alert
          :type="result.failed > 0 ? 'error' : 'success'"
          :closable="false"
        >
          <template #title>
            <span v-if="result.failed === 0">✓ {{ getModuleName(module) }}</span>
            <span v-else>✗ {{ getModuleName(module) }}</span>
          </template>
          <div>
            <span v-if="result.imported > 0">新增 {{ result.imported }}</span>
            <span v-if="result.updated > 0">，更新 {{ result.updated }}</span>
            <span v-if="result.failed > 0">，失败 {{ result.failed }}</span>
          </div>
          <div v-if="result.errors && result.errors.length > 0" style="margin-top: 8px; color: #F56C6C;">
            <div v-for="(error, idx) in result.errors" :key="idx" style="font-size: 12px;">
              - {{ error }}
            </div>
          </div>
        </el-alert>
      </div>
    </div>

    <template #footer v-if="currentStep === 2">
      <span class="dialog-footer">
        <el-button type="primary" @click="visible = false">关闭</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'
import axios from 'axios'

interface SyncResult {
  imported: number
  updated: number
  failed: number
  errors: string[]
}

const visible = ref(false)
const currentStep = ref(0)
const connecting = ref(false)
const syncing = ref(false)

// 连接信息
const connectionForm = reactive({
  url: '',
  username: '',
  password: ''
})

// 同步配置
const syncConfig = reactive({
  direction: 'pull',
  strategy: 'merge',
  modules: ['sites', 'shares', 'accounts', 'driverAccounts', 'pikpakAccounts', 'subscriptions', 'settings']
})

// 远端信息
const remoteInfo = reactive({
  appVersion: '',
  token: ''
})

// 同步结果
const syncResults = ref<Record<string, SyncResult>>({})

// 规范化 URL：只保留协议、主机和端口
const normalizeUrl = (url: string): string => {
  try {
    const urlObj = new URL(url)
    return `${urlObj.protocol}//${urlObj.host}`
  } catch (e) {
    // 如果 URL 解析失败，返回原值
    return url
  }
}

const handleConnect = async () => {
  if (!connectionForm.url || !connectionForm.username || !connectionForm.password) {
    ElMessage.warning('请填写完整的连接信息')
    return
  }

  // 规范化 URL
  const normalizedUrl = normalizeUrl(connectionForm.url.trim())

  connecting.value = true
  try {
    const response = await axios.post('/api/sync/connect', {
      url: normalizedUrl,
      username: connectionForm.username,
      password: connectionForm.password
    })

    if (response.data.success) {
      remoteInfo.appVersion = response.data.appVersion
      remoteInfo.token = response.data.token
      currentStep.value = 1
      ElMessage.success('连接成功')
    } else {
      ElMessage.error(response.data.message || '连接失败')
    }
  } catch (error: any) {
    ElMessage.error('连接失败: ' + (error.response?.data?.message || error.message))
  } finally {
    connecting.value = false
  }
}

const handleSync = async () => {
  if (syncConfig.modules.length === 0) {
    ElMessage.warning('请至少选择一个模块')
    return
  }

  const normalizedUrl = normalizeUrl(connectionForm.url.trim())

  syncing.value = true
  try {
    const endpoint = syncConfig.direction === 'push' ? '/api/sync/push' : '/api/sync/pull'
    const payload = {
      remoteUrl: normalizedUrl,
      username: connectionForm.username,
      password: connectionForm.password,
      modules: syncConfig.modules,
      strategy: syncConfig.direction === 'pull' ? syncConfig.strategy.toUpperCase() : undefined,
      force: false
    }

    const response = await axios.post(endpoint, payload)

    if (response.data.success) {
      syncResults.value = response.data.results
      currentStep.value = 2
      ElMessage.success('同步完成')
    } else {
      const versionError = response.data.results?.version_error
      if (versionError && versionError.errors[0]?.startsWith('VERSION_MISMATCH')) {
        await handleVersionMismatch()
      } else {
        ElMessage.error('同步失败')
        syncResults.value = response.data.results
        currentStep.value = 2
      }
    }
  } catch (error: any) {
    ElMessage.error('同步失败: ' + (error.response?.data?.message || error.message))
  } finally {
    syncing.value = false
  }
}

const handleVersionMismatch = async () => {
  const confirmed = await ElMessageBox.confirm(
    '版本不一致，可能导致数据不兼容。是否强制同步？',
    '版本不匹配',
    { type: 'warning' }
  ).catch(() => false)

  if (confirmed) {
    const normalizedUrl = normalizeUrl(connectionForm.url.trim())

    syncing.value = true
    try {
      const endpoint = syncConfig.direction === 'push' ? '/api/sync/push' : '/api/sync/pull'
      const payload = {
        remoteUrl: normalizedUrl,
        username: connectionForm.username,
        password: connectionForm.password,
        modules: syncConfig.modules,
        strategy: syncConfig.direction === 'pull' ? syncConfig.strategy.toUpperCase() : undefined,
        force: true
      }

      const response = await axios.post(endpoint, payload)

      if (response.data.success) {
        syncResults.value = response.data.results
        currentStep.value = 2
        ElMessage.success('同步完成')
      } else {
        ElMessage.error('同步失败')
        syncResults.value = response.data.results
        currentStep.value = 2
      }
    } catch (error: any) {
      ElMessage.error('强制同步失败: ' + (error.response?.data?.message || error.message))
    } finally {
      syncing.value = false
    }
  }
}

const getModuleName = (module: string) => {
  const nameMap: Record<string, string> = {
    'sites': '外部站点',
    'shares': '网盘分享',
    'accounts': '阿里云账号',
    'driverAccounts': '网盘账号',
    'pikpakAccounts': 'PikPak账号',
    'subscriptions': '订阅配置',
    'plugins': '插件',
    'pluginFilters': '过滤器',
    'settings': '系统设置'
  }
  return nameMap[module] || module
}

const open = () => {
  visible.value = true
  currentStep.value = 0
}

defineExpose({ open })
</script>

<style scoped>
.el-steps {
  margin-bottom: 30px;
}
</style>
