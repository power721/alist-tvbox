<template>
  <div id="config">
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

    <el-divider v-if="showLogin"/>

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

    <el-divider v-if="showLogin"/>

    <el-form :model="storage" label-width="120px" v-if="showLogin">
      <el-form-item prop="accessToken" label="阿里token">
        <el-input v-model="storage.refreshToken" maxlength="128" placeholder="长度32位"/>
        <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive.html" target="_blank">获取阿里token</a><br/>
        <a href="https://aliyuntoken.vercel.app/" class="hint" target="_blank">获取阿里token</a>
        <span class="hint">更新时间： {{formatTime(storage.refreshTokenTime)}}</span>
      </el-form-item>
      <el-form-item prop="openToken" label="开放token">
        <el-input v-model="storage.openToken" type="textarea" rows="3" minlength="256" placeholder="长度280位"/>
        <a href="https://alist.nn.ci/zh/guide/drivers/aliyundrive_open.html" target="_blank">获取开放token</a>
        <span class="hint">创建时间： {{formatTime(iat)}}</span>
        <span class="hint">更新时间： {{formatTime(storage.openTokenTime)}}</span>
        <span class="hint">过期时间： {{formatTime(exp)}}</span>
      </el-form-item>
      <el-form-item prop="folderId" label="转存文件夹ID">
        <el-input v-model="storage.folderId" placeholder="长度40位"/>
        <a href="https://www.aliyundrive.com/drive" target="_blank">阿里云盘</a>
      </el-form-item>
      <el-form-item label="加载我的云盘" v-if="storage.openToken">
        <el-switch
          v-model="showMyAli"
          @change="updateMyAli"
          inline-prompt
          active-text="加载"
          inactive-text="关闭"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="updateStorage">更新</el-button>
      </el-form-item>
    </el-form>

    <el-divider v-if="showLogin"/>

    <el-form label-width="120px" v-if="showLogin">
      <el-form-item label="自动签到">
        <el-switch
          v-model="autoCheckin"
          @change="updateAutoCheckin"
          inline-prompt
          active-text="开启"
          inactive-text="关闭"
        />
      </el-form-item>
      <el-form-item label="自动签到时间" v-if="autoCheckin">
        <el-time-picker v-model="scheduleTime" />
        <el-button type="primary" @click="updateScheduleTime">更新</el-button>
      </el-form-item>
      <el-form-item label="上次签到时间">
        <el-input :model-value="formatTime(checkinTime)" readonly/>
      </el-form-item>
      <el-checkbox v-model="forceCheckin" label="强制签到"/>
      <el-form-item>
        <el-button type="primary" @click="checkin">签到</el-button>
      </el-form-item>
    </el-form>

  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import {ElMessage} from "element-plus";
import axios from "axios";

const showLogin = ref(false)
const forceCheckin = ref(false)
const autoCheckin = ref(false)
const showMyAli = ref(false)
const checkinTime = ref('')
const scheduleTime = ref(new Date(2023, 6, 20, 8, 0))
const iat = ref(0)
const exp = ref(0)
const login = ref({
  username: '',
  password: '',
  enabled: false
})

const storage = ref({
  refreshToken: '',
  openToken: '',
  folderId: '',
  refreshTokenTime: '',
  openTokenTime: '',
})

const form = ref({
  token: '',
  enabledToken: false
})

const formatTime = (value: string|number) => {
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

const updateAutoCheckin = () => {
  axios.post('/settings', {name: 'auto_checkin', value: autoCheckin.value}).then(() => {
    if (autoCheckin.value) {
      ElMessage.success('成功开启自动签到')
    } else {
      ElMessage.info('成功关闭自动签到')
    }
  })
}

const updateMyAli = () => {
  axios.post('/show-my-ali?enabled=' + showMyAli.value).then(() => {
    if (showMyAli.value) {
      ElMessage.success('成功加载我的阿里云盘')
    } else {
      ElMessage.info('成功关闭我的阿里云盘')
    }
  })
}

const updateLogin = () => {
  axios.post('/login', login.value).then(() => {
    ElMessage.success('保存成功')
  })
}

const updateStorage = () => {
  axios.post('/storage', storage.value).then(({data}) => {
    storage.value = data
    ElMessage.success('更新成功')
  })
}

const checkin = () => {
  axios.post('/checkin?force=' + forceCheckin.value).then(({data}) => {
    checkinTime.value = data.checkinTime
    forceCheckin.value = false
    ElMessage.success('签到成功, 本月累计' + data.signInCount + '天')
  })
}

const updateScheduleTime = () => {
  axios.post('/schedule', scheduleTime.value).then(() => {
    ElMessage.success('更新成功')
  })
}

onMounted(() => {
  axios.get("/profiles").then(({data}) => {
    showLogin.value = data.includes('xiaoya')
    if (showLogin.value) {
      axios.get('/settings').then(({data}) => {
        form.value.token = data.token
        form.value.enabledToken = data.token != ''
        scheduleTime.value = data.schedule_time || new Date(2023, 6, 20, 9, 0)
        checkinTime.value = data.checkin_time
        autoCheckin.value = data.auto_checkin === 'true'
        showMyAli.value = data.show_my_ali === 'true'
        login.value.username = data.alist_username
        login.value.password = data.alist_password
        login.value.enabled = data.alist_login === 'true'
        storage.value.refreshToken = data.refresh_token
        storage.value.openToken = data.open_token
        storage.value.folderId = data.folder_id
        storage.value.refreshTokenTime = data.refresh_token_time
        storage.value.openTokenTime = data.open_token_time
        let details = JSON.parse(atob(data.open_token.split('.')[1]))
        iat.value = details.iat * 1000
        exp.value = details.exp * 1000
      })
    }
  })
})
</script>

<style>
#config {
  max-width: 1080px;
}

.hint {
  margin-left: 16px;
}
</style>
