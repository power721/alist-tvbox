<script setup lang="ts">
import { computed, ref } from 'vue'
import { Key } from '@element-plus/icons-vue'
import { request } from './api'

export interface CredentialSummary {
  required: boolean; configured: boolean; status: string; origin: string
  cookieConfigured: boolean; authorizationConfigured: boolean; revision: string; reason: string | null
}
const props = defineProps<{ sourceId: string; summary?: CredentialSummary; disabled: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const visible = ref(false)
const busy = ref(false)
const error = ref('')
const current = ref<CredentialSummary>()
const required = ref(false)
const origin = ref('')
const cookie = ref('')
const authorization = ref('')
const clearCookie = ref(false)
const clearAuthorization = ref(false)
const blocked = computed(() => ['missing', 'rejected'].includes(props.summary?.status ?? ''))
function forget() { cookie.value = ''; authorization.value = ''; clearCookie.value = false; clearAuthorization.value = false }
async function show() {
  error.value = ''
  forget()
  visible.value = true
  busy.value = true
  try {
    current.value = await request<CredentialSummary>(`/sources/${props.sourceId}/credentials`)
    required.value = current.value.required
    origin.value = current.value.origin
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败' }
  finally { busy.value = false }
}
async function save() {
  if (!current.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await request(`/sources/${props.sourceId}/credentials`, {
      expectedRevision: current.value.revision, required: required.value, origin: origin.value,
      cookie: cookie.value, clearCookie: clearCookie.value,
      authorization: authorization.value, clearAuthorization: clearAuthorization.value
    }, 'PUT')
    forget()
    visible.value = false
    emit('changed')
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败' }
  finally { busy.value = false }
}
</script>

<template>
  <div class="source-credentials">
    <span v-if="blocked" class="credential-warning" role="status">
      {{ summary?.status === 'rejected' ? '凭据已被站点拒绝，请更新' : '待补充凭据' }}
    </span>
    <span v-else-if="summary?.configured" role="status">凭据已配置</span>
    <el-button :icon="Key" :disabled="disabled" @click="show">{{ blocked ? '补充凭据' : '站点凭据' }}</el-button>
    <el-dialog v-model="visible" title="站点凭据" width="min(540px, 94vw)" destroy-on-close
               :close-on-click-modal="!busy" :show-close="!busy" @closed="forget">
      <el-form label-position="top" :disabled="busy">
        <el-form-item label="此爬虫必须登录"><el-switch v-model="required" /></el-form-item>
        <el-form-item label="站点来源"><el-input v-model="origin" placeholder="https://example.com" autocomplete="off" /></el-form-item>
        <el-form-item label="Cookie">
          <el-input v-model="cookie" type="password" show-password autocomplete="new-password"
                    :disabled="clearCookie" :placeholder="current?.cookieConfigured ? '已保存，留空保持不变' : '未配置'" />
          <el-checkbox v-if="current?.cookieConfigured" v-model="clearCookie">清除 Cookie</el-checkbox>
        </el-form-item>
        <el-form-item label="Authorization / Token">
          <el-input v-model="authorization" type="password" show-password autocomplete="new-password"
                    :disabled="clearAuthorization"
                    :placeholder="current?.authorizationConfigured ? '已保存，留空保持不变' : 'Bearer token 或完整 Authorization 值'" />
          <el-checkbox v-if="current?.authorizationConfigured" v-model="clearAuthorization">清除 Token</el-checkbox>
        </el-form-item>
      </el-form>
      <p v-if="error" role="alert" class="credential-warning">{{ error }}</p>
      <template #footer>
        <el-button :disabled="busy" @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="busy || !current" @click="save">保存凭据</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.source-credentials { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 13px; }
.credential-warning { color: #b45309; overflow-wrap: anywhere; }
</style>
