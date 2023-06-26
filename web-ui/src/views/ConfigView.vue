<template>
  <div id="config">
    <el-row>
      <el-col :span="11">
        <el-card class="box-card" v-if="showLogin">
          <template #header>
            <div class="card-header">
              <span>AList运行状态</span>
              <div>
                <el-button type="primary" v-if="store.aListStatus===0" @click="handleAList('start')">启动</el-button>
                <el-button type="warning" v-if="store.aListStatus===2" @click="handleAList('restart')">重启</el-button>
                <el-button type="danger" v-if="store.aListStatus===2" @click="handleAList('stop')">停止</el-button>
              </div>
            </div>
          </template>
          <el-switch
            v-model="aListStarted"
            inline-prompt
            :disabled="true"
            :active-text="store.aListStatus===2?'运行中':'启动中'"
            inactive-text="停止中"
          />
          <span class="hint" v-if="aListStartTime">启动时间：{{ formatTime(aListStartTime) }}</span>
          <el-progress
            :percentage="percentage"
            :stroke-width="15"
            status="success"
            striped
            striped-flow
            :duration="duration"
            v-if="intervalId"
          />
        </el-card>

        <el-card class="box-card" v-if="showLogin&&store.aListStatus">
          <el-form :model="login" label-width="120px" v-if="showLogin">
            <el-form-item prop="token" label="强制登录AList">
              <el-switch
                v-model="login.enabled"
                inline-prompt
                active-text="开启"
                inactive-text="关闭"
              />
            </el-form-item>
            <el-form-item prop="username" label="用户名">
              <el-input v-model="login.username"/>
            </el-form-item>
            <el-form-item prop="password" label="密码">
              <el-input v-model="login.password" type="password" show-password/>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="updateLogin">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card class="box-card">
          <el-form :model="form" label-width="120px">
            <el-form-item prop="enabledToken" label="安全订阅">
              <el-switch
                v-model="form.enabledToken"
                inline-prompt
                active-text="开启"
                inactive-text="关闭"
              />
            </el-form-item>
            <el-form-item prop="token" label="安全Token" v-if="form.enabledToken">
              <el-input v-model="form.token"/>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="updateToken">更新</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="11">
        <el-card class="box-card" v-if="showLogin">
          <el-form label-width="120px">
            <el-form-item label="计划时间">
              <el-time-picker v-model="scheduleTime"/>
              <el-button type="primary" @click="updateScheduleTime">更新</el-button>
              <span class="hint">自动签到和刷新阿里Token的时间</span>
            </el-form-item>
          </el-form>

          <el-form label-width="140px">
            <el-form-item label="文件过期时间">
              <el-input-number v-model="fileExpireHour" :min="1"/>
              <span class="append">小时</span>
              <el-button type="primary" @click="updateFileExpireHour">更新</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card class="box-card" v-if="dockerVersion||appVersion">
          <template #header>
            <div class="card-header">
              <span>应用数据</span>
              <div>
                <el-button @click="dialogVisible=true">高级设置</el-button>
              </div>
            </div>
          </template>
          <div v-if="dockerVersion">小雅版本：{{ dockerVersion }}</div>
          <div v-if="appVersion">应用版本：{{ appVersion }}</div>
          <div v-if="appRemoteVersion&&appRemoteVersion>appVersion">
            最新版本：{{ appRemoteVersion }}，请升级应用。
          </div>
        </el-card>

        <el-card class="box-card" v-if="indexVersion">
          <template #header>
            <div class="card-header">索引数据</div>
          </template>
          <div>本地版本：{{ indexVersion }}</div>
          <div v-if="indexRemoteVersion&&indexRemoteVersion!=indexVersion">
            最新版本：{{ indexRemoteVersion }}，请重启更新。
          </div>
        </el-card>

        <el-card class="box-card" v-if="movieVersion">
          <template #header>
            <div class="card-header">豆瓣电影数据</div>
          </template>
          <div>本地版本：{{ movieVersion }}</div>
          <div v-if="movieRemoteVersion&&movieRemoteVersion>movieVersion">
            最新版本：{{ movieRemoteVersion }}，请重启更新。
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" title="高级功能" width="40%">
      <el-form label-width="150px">
        <el-form-item label="开放Token认证URL">
          <el-input v-model="openTokenUrl"/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="updateOpenTokenUrl">更新</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
      </span>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from "vue";
import {ElMessage} from "element-plus";
import axios from "axios";
import {onUnmounted} from "@vue/runtime-core";
import {store} from "@/services/store";

let intervalId = 0
const percentage = ref<number>(0)
const duration = computed(() => Math.floor(percentage.value / 10))

const increase = () => {
  percentage.value += 1
  if (percentage.value > 100) {
    percentage.value = 100
  }
}

const aListStarted = ref(false)
const showLogin = ref(false)
const autoCheckin = ref(false)
const dialogVisible = ref(false)
const appVersion = ref(0)
const appRemoteVersion = ref(0)
const dockerVersion = ref('')
const indexVersion = ref('')
const indexRemoteVersion = ref('')
const movieVersion = ref(0)
const movieRemoteVersion = ref(0)
const fileExpireHour = ref(24)
const aListStartTime = ref('')
const openTokenUrl = ref('')
const scheduleTime = ref(new Date(2023, 6, 20, 8, 0))
const login = ref({
  username: '',
  password: '',
  enabled: false
})

const form = ref({
  token: '',
  enabledToken: false
})

const formatTime = (value: string | number) => {
  return new Date(value).toLocaleString('zh-cn')
}

const updateToken = () => {
  if (form.value.enabledToken) {
    axios.post('/token', {token: form.value.token}).then(({data}) => {
      form.value.token = data
      ElMessage.success('成功开启安全订阅')
    })
  } else {
    axios.delete('/token').then(() => {
      form.value.token = ''
      ElMessage.info('成功关闭安全订阅')
    })
  }
}

const updateOpenTokenUrl = () => {
  axios.post('/open-token-url', {url: openTokenUrl.value}).then(({data}) => {
    form.value.token = data
    ElMessage.success('更新成功')
  })
}

const updateLogin = () => {
  axios.post('/login', login.value).then(() => {
    ElMessage.success('保存成功')
  })
}

const updateScheduleTime = () => {
  axios.post('/schedule', scheduleTime.value).then(() => {
    ElMessage.success('更新成功')
  })
}

const updateFileExpireHour = () => {
  axios.post('/settings', {name: 'file_expire_hour', value: '' + fileExpireHour.value}).then(() => {
    ElMessage.success('更新成功')
  })
}

const handleAList = (op: string) => {
  axios.post('/alist/' + op).then(() => {
    ElMessage.success('操作成功')
    setTimeout(() => getAListStatus(), 3000)
  })
}

const getAListStatus = () => {
  axios.get('/alist/status').then(({data}) => {
    increase()
    store.aListStatus = data
    aListStarted.value = data != 0
    if (data !== 1) {
      clearInterval(intervalId)
      intervalId = 0
    } else if (!intervalId) {
      percentage.value = 0
      intervalId = setInterval(getAListStatus, 1000)
    }
  })
}

onMounted(() => {
  showLogin.value = store.xiaoya
  if (store.xiaoya) {

    axios.get('/settings').then(({data}) => {
      form.value.token = data.token
      form.value.enabledToken = !!data.token
      scheduleTime.value = data.schedule_time || new Date(2023, 6, 20, 9, 0)
      aListStartTime.value = data.alist_start_time
      fileExpireHour.value = +data.file_expire_hour || 24
      movieVersion.value = +data.movie_version
      indexVersion.value = data.index_version
      dockerVersion.value = data.docker_version
      appVersion.value = +data.app_version
      openTokenUrl.value = data.open_token_url
      autoCheckin.value = data.auto_checkin === 'true'
      login.value.username = data.alist_username
      login.value.password = data.alist_password
      login.value.enabled = data.alist_login === 'true'
    })
    axios.get('/alist/status').then(({data}) => {
      store.aListStatus = data
      aListStarted.value = data != 0
      if (data === 1) {
        percentage.value = 0
        intervalId = setInterval(getAListStatus, 1000)
      }
    })
    axios.get('/versions').then(({data}) => {
      movieRemoteVersion.value = +data.movie
      indexRemoteVersion.value = data.index
      appRemoteVersion.value = +data.app
    })
  } else {
    axios.get('/token').then(({data}) => {
      form.value.token = data
      form.value.enabledToken = data != ''
    })
  }
})

onUnmounted(() => {
  clearInterval(intervalId)
})
</script>

<style>
.main {
  max-width: 1080px;
}

.el-col {
  margin-left: 24px;
}

.bottom {
  margin-bottom: 0px;
}

.append {
  margin-left: 6px;
  margin-right: 6px;
}

.hint {
  margin-left: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.box-card {
  margin-bottom: 12px;
}
</style>
