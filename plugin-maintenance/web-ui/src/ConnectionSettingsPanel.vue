<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Refresh, Setting } from '@element-plus/icons-vue'
import { request } from './api'
import ModelBudgetPanel from './ModelBudgetPanel.vue'

interface Settings {
  at: { configured: boolean; origin: string; instanceId: string; revision: string; authMode: string
    allowHttp: boolean; credentialConfigured: boolean; embeddedAvailable: boolean; useEmbedded: boolean }
  model: { enabled: boolean; endpoint: string; baseUrl: string; model: string; keyEnv: string
    apiKeyConfigured: boolean; timeoutMillis: number; maxCompletionTokens: number
    maxTokenParameter: string; jsonMode: string; includeStoreParameter: boolean }
  limits?: Record<string, number | string | null>
}
interface RunnerConfiguration {
  blockedReason: string
  routeLabel?: string
  snapshot: { enabled: boolean; transport: string; instanceId: string; imageId: string; bundleSha256: string }
}
interface RunnerConnection { connected: boolean; ready: boolean; sourceExecuted: boolean; code: string }
const props = defineProps<{ disabled?: boolean; sourceId?: string }>()
const emit = defineEmits<{ changed: [section: string] }>()
const visible = ref(false)
const tab = ref('at')
const settings = ref<Settings>()
const busy = ref(false)
const error = ref('')
const credential = ref('')
const apiKey = ref('')
const clearCredential = ref(false)
const clearApiKey = ref(false)
const addressMode = ref('base')
const runner = ref<RunnerConfiguration>()
const runnerConnection = ref<RunnerConnection>()
const limitsBusy = ref(false)
const limitsRevision = ref(0)
const limitScopes = [
  { title: '全局', calls: 'dailyCallLimit', input: 'dailyInputBytes', output: 'dailyOutputTokens', cost: 'dailyCostUsd' },
  { title: '每个爬虫', calls: 'sourceDailyCallLimit', input: 'sourceDailyInputBytes', output: 'sourceDailyOutputTokens', cost: 'sourceDailyCostUsd' },
  { title: '当前模型', calls: 'modelDailyCallLimit', input: 'modelDailyInputBytes', output: 'modelDailyOutputTokens', cost: 'modelDailyCostUsd' }
]

async function saveLimits() {
  if (busy.value || limitsBusy.value || !settings.value?.limits) return
  limitsBusy.value = true; error.value = ''
  try {
    const limits = { ...settings.value.limits }
    for (const field of ['dailyCostUsd', 'sourceDailyCostUsd', 'modelDailyCostUsd', 'inputPriceUsdPerMillion', 'outputPriceUsdPerMillion'])
      if (limits[field] === '') limits[field] = null
    const saved = await request<Settings>('/settings/connections/model-limits', limits, 'PUT')
    settings.value.limits = saved.limits
    limitsRevision.value++
    emit('changed', 'model')
    ElMessage.success('用量限额已保存，历史账本保持不变')
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '限额保存失败' }
  finally { limitsBusy.value = false }
}

function forgetSecrets() { credential.value = ''; apiKey.value = ''; clearCredential.value = false; clearApiKey.value = false }
function runnerTabChanged(name: string | number) { if (name === 'runner') void loadRunner() }
function runnerPath(path: string) {
  return props.sourceId ? `${path}?sourceId=${encodeURIComponent(props.sourceId)}` : path
}
async function loadRunner() {
  if (busy.value) return
  busy.value = true; error.value = ''
  runner.value = undefined; runnerConnection.value = undefined
  try { runner.value = await request<RunnerConfiguration>(runnerPath('/managed-source-runtime')) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '执行器读取失败' }
  finally { busy.value = false }
}
async function checkRunner() {
  if (busy.value || !runner.value || runner.value.blockedReason) return
  busy.value = true; error.value = ''; runnerConnection.value = undefined
  try { runnerConnection.value = await request<RunnerConnection>(runnerPath('/managed-source-runtime/connection')) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '执行器连接失败' }
  finally { busy.value = false }
}
async function show(section = 'at') {
  tab.value = section
  visible.value = true
  busy.value = true
  error.value = ''
  settings.value = undefined
  forgetSecrets()
  try {
    settings.value = await request<Settings>('/settings/connections')
    addressMode.value = settings.value.model.endpoint ? 'endpoint' : 'base'
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '配置读取失败' }
  finally { busy.value = false }
  if (section === 'runner' && settings.value) await loadRunner()
}
async function save() {
  if (busy.value || !settings.value || !['at', 'model'].includes(tab.value)) return
  busy.value = true; error.value = ''
  try {
    if (tab.value === 'at') {
      settings.value = await request<Settings>('/settings/connections/at', {
        ...settings.value.at, credential: credential.value, clearCredential: clearCredential.value
      }, 'PUT')
    } else {
      const model = settings.value.model
      settings.value = await request<Settings>('/settings/connections/model', {
        ...model, endpoint: addressMode.value === 'endpoint' ? model.endpoint : '',
        baseUrl: addressMode.value === 'base' ? model.baseUrl : '',
        keyEnv: apiKey.value ? '' : model.keyEnv, apiKey: apiKey.value, clearApiKey: clearApiKey.value
      }, 'PUT')
    }
    forgetSecrets()
    emit('changed', tab.value)
    ElMessage.success('连接设置已保存并生效')
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '保存失败' }
  finally { busy.value = false }
}
defineExpose({ show })
</script>

<template>
  <el-button :icon="Setting" :disabled="disabled" @click="show('model')">模型与设置</el-button>
  <el-dialog v-model="visible" title="连接设置" width="min(700px, 94vw)"
             :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"
             @closed="forgetSecrets">
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-tabs v-model="tab" @tab-change="runnerTabChanged">
      <el-tab-pane label="外置 AT 连接" name="at">
        <el-form v-if="settings" label-position="top" :disabled="busy">
          <el-form-item v-if="settings.at.embeddedAvailable" label="维护对象">
            <el-radio-group v-model="settings.at.useEmbedded">
              <el-radio-button :value="true">当前 AT（内部通讯）</el-radio-button>
              <el-radio-button :value="false">外置 AT</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-tag v-if="settings.at.useEmbedded" type="success">当前 AT · 宿主直接读取</el-tag>
          <template v-if="!settings.at.useEmbedded">
          <el-form-item label="AT 地址">
            <el-input v-model="settings.at.origin" placeholder="http://192.168.1.10:4567" maxlength="2048" />
          </el-form-item>
          <details>
            <summary>高级连接选项</summary>
            <div class="connection-fields">
            <el-form-item label="实例标识">
              <el-input v-model="settings.at.instanceId" placeholder="production-at" maxlength="100" />
            </el-form-item>
            <el-form-item label="版本声明">
              <el-input v-model="settings.at.revision" placeholder="unverified" maxlength="200" />
            </el-form-item>
            </div>
          </details>
          <el-form-item label="认证方式">
            <el-radio-group v-model="settings.at.authMode">
              <el-radio-button value="api_key">API Key</el-radio-button>
              <el-radio-button value="session">会话 Token</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="settings.at.authMode === 'api_key' ? 'AT API Key' : 'AT 会话 Token'">
            <el-input v-model="credential" type="password" show-password autocomplete="new-password"
                      :placeholder="settings.at.credentialConfigured ? '已保存，留空保持不变' : '未配置'"
                      maxlength="8192" />
          </el-form-item>
          <div class="connection-options">
            <el-checkbox v-model="settings.at.allowHttp">允许 HTTP</el-checkbox>
            <el-checkbox v-if="settings.at.credentialConfigured" v-model="clearCredential">清除已保存凭据</el-checkbox>
          </div>
          <el-tag :type="settings.at.configured ? 'success' : 'info'">
            {{ settings.at.configured ? 'AT 已配置 · 只读' : 'AT 未配置' }}
          </el-tag>
          </template>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="模型中转站" name="model">
        <el-form v-if="settings" label-position="top" :disabled="busy">
          <el-form-item label="启用模型"><el-switch v-model="settings.model.enabled" /></el-form-item>
          <el-form-item label="地址类型">
            <el-radio-group v-model="addressMode">
              <el-radio-button value="base">Base URL</el-radio-button>
              <el-radio-button value="endpoint">完整接口地址</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="addressMode === 'base'" label="Base URL">
            <el-input v-model="settings.model.baseUrl" placeholder="https://relay.example/v1" maxlength="2048" />
          </el-form-item>
          <el-form-item v-else label="Chat Completions 地址">
            <el-input v-model="settings.model.endpoint" placeholder="https://relay.example/v1/chat/completions" maxlength="2048" />
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input v-model="settings.model.model" placeholder="中转站模型别名" maxlength="120" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="apiKey" type="password" show-password autocomplete="new-password"
                      :placeholder="settings.model.apiKeyConfigured ? '已保存，留空保持不变' : '未配置'"
                      maxlength="8192" />
          </el-form-item>
          <el-checkbox v-if="settings.model.apiKeyConfigured" v-model="clearApiKey">清除已保存 API Key</el-checkbox>
          <el-collapse>
            <el-collapse-item title="请求兼容选项" name="dialect">
              <el-form-item label="密钥环境变量（可选）">
                <el-input v-model="settings.model.keyEnv" placeholder="OPENAI_API_KEY" maxlength="101" />
              </el-form-item>
              <div class="connection-fields">
                <el-form-item label="请求超时（毫秒）">
                  <el-input-number v-model="settings.model.timeoutMillis" :min="100" :max="120000" :step="1000" />
                </el-form-item>
                <el-form-item label="最大输出 Token">
                  <el-input-number v-model="settings.model.maxCompletionTokens" :min="128" :max="16384" :step="128" />
                </el-form-item>
                <el-form-item label="Token 参数">
                  <el-select v-model="settings.model.maxTokenParameter">
                    <el-option value="max_completion_tokens" label="max_completion_tokens" />
                    <el-option value="max_tokens" label="max_tokens" />
                  </el-select>
                </el-form-item>
                <el-form-item label="JSON 模式">
                  <el-select v-model="settings.model.jsonMode">
                    <el-option value="json_object" label="json_object" />
                    <el-option value="none" label="不发送 response_format" />
                  </el-select>
                </el-form-item>
              </div>
              <el-checkbox v-model="settings.model.includeStoreParameter">发送 store=false</el-checkbox>
            </el-collapse-item>
          </el-collapse>
        </el-form>
        <section v-if="settings?.limits" class="model-limits">
          <h3>每日用量限额</h3>
          <div class="limit-heading"><span>范围</span><span>调用次数</span><span>请求字节</span><span>输出 Token</span></div>
          <div v-for="scope in limitScopes" :key="scope.calls" class="limit-row">
            <strong>{{ scope.title }}</strong>
            <el-input-number v-model="settings.limits[scope.calls] as number" :min="0" :max="100" :disabled="busy || limitsBusy" :controls="false" :aria-label="`${scope.title}每日调用次数，0不限`" />
            <el-input-number v-model="settings.limits[scope.input] as number" :min="0" :max="26214400" :disabled="busy || limitsBusy" :controls="false" :aria-label="`${scope.title}每日请求字节，0不限`" />
            <el-input-number v-model="settings.limits[scope.output] as number" :min="0" :max="1638400" :disabled="busy || limitsBusy" :controls="false" :aria-label="`${scope.title}每日输出Token，0不限`" />
          </div>
          <p class="limits-note">0 为不限；未设置每日额度，开发测试不设限。</p>
          <details>
            <summary>费用限额与计价</summary>
            <el-form label-position="top" :disabled="busy || limitsBusy" class="connection-fields">
              <el-form-item v-for="scope in limitScopes" :key="scope.cost" :label="`${scope.title}每日费用（USD，留空不限）`">
                <el-input v-model="settings.limits[scope.cost] as string" clearable @clear="settings!.limits![scope.cost] = null" />
              </el-form-item>
              <el-form-item label="输入每百万 Token 单价（USD）"><el-input v-model="settings.limits.inputPriceUsdPerMillion as string" clearable @clear="settings!.limits!.inputPriceUsdPerMillion = null" /></el-form-item>
              <el-form-item label="输出每百万 Token 单价（USD）"><el-input v-model="settings.limits.outputPriceUsdPerMillion as string" clearable @clear="settings!.limits!.outputPriceUsdPerMillion = null" /></el-form-item>
              <el-form-item label="输入计量合同">
                <el-select v-model="settings.limits.inputTokenBound">
                  <el-option value="" label="未声明" />
                  <el-option value="request_utf8_bytes_v1" label="请求 UTF-8 字节上界" />
                </el-select>
              </el-form-item>
            </el-form>
          </details>
          <el-button :icon="Check" :loading="limitsBusy" :disabled="busy" @click="saveLimits">保存限额</el-button>
        </section>
        <ModelBudgetPanel v-if="visible && tab === 'model'" :key="`${sourceId}:${limitsRevision}`" :source-id="sourceId" />
      </el-tab-pane>
      <el-tab-pane label="隔离执行" name="runner">
        <el-descriptions v-if="runner" :column="1" border>
          <el-descriptions-item v-if="runner.routeLabel" label="当前通道">
            {{ runner.routeLabel }}
          </el-descriptions-item>
          <el-descriptions-item label="执行器">
            <el-tag :type="runner.blockedReason ? 'info' : 'success'">
              {{ runner.blockedReason ? '未启用' : '已配置' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="连接方式">
            {{ runner.snapshot.transport === 'loopback_tunnel' ? 'NAS 安全隧道' : '本地隔离通道' }}
          </el-descriptions-item>
          <el-descriptions-item label="连接状态">
            {{ !runnerConnection ? '未检查' : runnerConnection.ready ? '身份匹配，可接收任务'
              : runnerConnection.connected ? '已连接，运行身份不匹配' : '未连接' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="runnerConnection?.code" :title="runnerConnection.code" type="warning" :closable="false" />
        <div class="connection-options runner-actions">
          <el-button :icon="Refresh" :loading="busy" :disabled="!runner || !!runner.blockedReason"
                     @click="checkRunner">检查连接</el-button>
        </div>
        <details v-if="runner?.snapshot.instanceId">
          <summary>运行身份</summary>
          <dl class="runner-identity">
            <dt>实例</dt><dd>{{ runner.snapshot.instanceId }}</dd>
            <dt>镜像</dt><dd>{{ runner.snapshot.imageId }}</dd>
            <dt>运行包</dt><dd>{{ runner.snapshot.bundleSha256 }}</dd>
          </dl>
        </details>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">关闭</el-button>
      <el-button v-if="tab !== 'runner'" :icon="Check" type="primary" :loading="busy"
                 :disabled="!settings" @click="save">保存连接</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.connection-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.connection-options { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.connection-fields .el-select, .connection-fields .el-input-number { width: 100%; min-width: 0; }
.el-form { padding-top: 8px; }
.el-collapse { margin-top: 16px; }
.runner-actions { margin-top: 16px; }
.runner-identity dd { margin: 4px 0 12px; overflow-wrap: anywhere; }
.model-limits { margin-top: 20px; border-top: 1px solid #e8ebed; padding-top: 8px; }
.limit-heading, .limit-row { display: grid; grid-template-columns: 72px repeat(3, minmax(0, 1fr)); gap: 8px; align-items: center; margin: 8px 0; }
.limit-heading, .limits-note { font-size: 12px; color: #6d7479; }
.limit-row strong { font-size: 13px; }
.limit-row .el-input-number { width: 100%; min-width: 0; }
.model-limits > .el-button { margin-top: 12px; }
@media (max-width: 540px) { .connection-fields { grid-template-columns: minmax(0, 1fr); } }
</style>
