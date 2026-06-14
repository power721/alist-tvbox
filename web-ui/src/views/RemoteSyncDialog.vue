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

    <div v-if="currentStep === 0" style="padding: 20px 0;">
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
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const visible = ref(false)
const currentStep = ref(0)

// 连接信息
const connectionForm = reactive({
  url: '',
  username: '',
  password: ''
})

// 同步配置
const syncConfig = reactive({
  direction: 'pull',  // 'push' | 'pull'
  strategy: 'merge',  // 'overwrite' | 'merge'
  modules: ['sites', 'shares', 'accounts', 'driverAccounts', 'pikpakAccounts', 'subscriptions', 'settings']
})

// 远端信息
const remoteInfo = reactive({
  appVersion: '',
  token: ''
})

// 同步结果
const syncResults = ref({})

const connecting = ref(false)

const handleConnect = async () => {
  if (!connectionForm.url || !connectionForm.username || !connectionForm.password) {
    ElMessage.warning('请填写完整的连接信息')
    return
  }

  connecting.value = true
  try {
    const response = await axios.post('/api/sync/connect', connectionForm)

    if (response.data.success) {
      remoteInfo.appVersion = response.data.appVersion
      remoteInfo.token = response.data.token
      currentStep.value = 1
      ElMessage.success('连接成功')
    } else {
      ElMessage.error(response.data.message || '连接失败')
    }
  } catch (error) {
    ElMessage.error('连接失败: ' + (error.response?.data?.message || error.message))
  } finally {
    connecting.value = false
  }
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
