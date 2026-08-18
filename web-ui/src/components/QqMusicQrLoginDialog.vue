<template>
  <el-dialog
    :model-value="modelValue"
    title="QQ 音乐扫码登录"
    width="360px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @closed="stopPolling"
  >
    <div class="qqmusic-qr">
      <el-radio-group v-model="loginType" @change="startLogin">
        <el-radio-button value="qq">QQ</el-radio-button>
        <el-radio-button value="wx">微信</el-radio-button>
      </el-radio-group>

      <div class="qqmusic-qr-image">
        <img v-if="imageBase64" :src="'data:image/png;base64,' + imageBase64" alt="二维码"/>
        <el-icon v-else-if="loading" class="is-loading" :size="48">
          <Loading/>
        </el-icon>
        <span v-else class="qqmusic-qr-placeholder">二维码加载失败</span>
      </div>

      <div class="qqmusic-qr-status" :class="'is-' + status">{{ message }}</div>

      <div class="qqmusic-qr-actions">
        <el-button link type="primary" :disabled="loading" @click="startLogin">刷新二维码</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import axios from 'axios'
import {ElMessage} from 'element-plus'
import {Loading} from '@element-plus/icons-vue'

defineOptions({name: 'QqMusicQrLoginDialog'})

const props = defineProps<{
  modelValue: boolean
  source: { id: string, name: string, enabled: boolean } | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const loginType = ref('qq')
const imageBase64 = ref('')
const status = ref('idle')
const message = ref('正在获取二维码…')
const loading = ref(false)
let sessionKey = ''
let timer = 0
let count = 0

const startLogin = () => {
  stopPolling()
  imageBase64.value = ''
  status.value = 'idle'
  message.value = '正在获取二维码…'
  loading.value = true
  axios.post('/api/qqmusic/login?type=' + loginType.value, null).then(({data}) => {
    loading.value = false
    sessionKey = data.key
    imageBase64.value = data.image
    status.value = 'waiting'
    message.value = loginType.value === 'wx' ? '请使用微信扫码登录' : '请使用QQ扫码登录'
    count = 180
    timer = setInterval(check, 1500)
  }).catch(() => {
    loading.value = false
    status.value = 'failed'
    message.value = '获取二维码失败，请重试'
  })
}

const check = () => {
  if (!sessionKey) {
    return
  }
  if (count-- <= 0) {
    onExpired()
    return
  }
  axios.get('/api/qqmusic/check?key=' + sessionKey).then(({data}) => {
    if (data.status === 'waiting' || data.status === 'scanned') {
      status.value = data.status
      message.value = data.message
      return
    }
    if (data.status === 'success') {
      saveCredential(data.extend)
      return
    }
    stopPolling()
    status.value = data.status
    message.value = data.message || '登录失败，请重试'
    imageBase64.value = ''
  }).catch(() => {
    // 网络抖动不打断轮询，等下一轮
  })
}

const onExpired = () => {
  stopPolling()
  status.value = 'expired'
  message.value = '二维码已失效，请刷新后重试'
  imageBase64.value = ''
}

const saveCredential = (extend: string) => {
  stopPolling()
  if (!props.source || !extend) {
    status.value = 'failed'
    message.value = '登录成功但保存失败，请重试'
    return
  }
  axios.put('/api/subscription-sources/' + props.source.id, {
    name: props.source.name,
    enabled: props.source.enabled,
    extend
  }).then(() => {
    ElMessage.success('QQ 音乐扫码登录成功，已写入扩展配置')
    emit('success')
    emit('update:modelValue', false)
  }).catch(() => {
    status.value = 'failed'
    message.value = '登录成功但保存失败，请重试'
  })
}

const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = 0
  }
}

watch(() => props.modelValue, value => {
  if (value) {
    startLogin()
  }
})
</script>

<style scoped>
.qqmusic-qr {
  align-items: center;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qqmusic-qr-image {
  align-items: center;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  display: flex;
  height: 240px;
  justify-content: center;
  width: 240px;
}

.qqmusic-qr-image img {
  height: 220px;
  width: 220px;
}

.qqmusic-qr-placeholder {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}

.qqmusic-qr-status {
  color: var(--el-text-color-regular);
  font-size: 14px;
  min-height: 20px;
}

.qqmusic-qr-status.is-success {
  color: var(--el-color-success);
}

.qqmusic-qr-status.is-failed,
.qqmusic-qr-status.is-expired {
  color: var(--el-color-danger);
}

.qqmusic-qr-actions {
  display: flex;
  justify-content: center;
}
</style>
