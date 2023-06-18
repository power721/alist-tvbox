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
        <el-input v-model="storage.accessToken"/>
      </el-form-item>
      <el-form-item prop="updateTime" label="更新时间">
        <el-input :model-value="formatTime(storage.accessTokenTime)" readonly/>
      </el-form-item>
      <el-form-item prop="openToken" label="开放token">
        <el-input v-model="storage.openToken" type="textarea" rows="3"/>
      </el-form-item>
      <el-form-item prop="updateTime" label="更新时间">
        <el-input :model-value="formatTime(storage.openTokenTime)" readonly/>
      </el-form-item>
      <el-form-item prop="folderId" label="转存文件夹ID">
        <el-input v-model="storage.folderId"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="updateStorage">更新</el-button>
      </el-form-item>
    </el-form>

    <el-divider v-if="showLogin"/>

    <el-form label-width="120px" v-if="showLogin">
      <el-form-item label="签到时间">
        <el-input :model-value="formatTime(checkinTime)" readonly/>
      </el-form-item>
      <el-checkbox v-model="force" label="强制签到" />
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
const force = ref(false)
const checkinTime = ref('')
const login = ref({
  username: '',
  password: '',
  enabled: false
})

const storage = ref({
  accessToken: '',
  openToken: '',
  folderId: '',
  accessTokenTime: '',
  openTokenTime: '',
})

const form = ref({
  token: '',
  enabledToken: false
})

const formatTime = (value: string) => {
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
      ElMessage.success('成功关闭安全订阅')
    })
  }
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
  axios.post('/checkin?force=' + force.value).then(({data}) => {
    checkinTime.value = data.checkinTime
    force.value = false
    ElMessage.success('签到成功, 本月累计' + data.signInCount + '天')
  })
}

onMounted(() => {
  axios.get('/token').then(({data}) => {
    form.value.token = data
    form.value.enabledToken = data != ''
  })
  axios.get("/profiles").then(({data}) => {
    showLogin.value = data.includes('xiaoya')
    if (showLogin.value) {
      axios.get('/login').then(({data}) => {
        login.value = data
      })
      axios.get('/storage').then(({data}) => {
        storage.value = data
      })
      axios.get('/checkin').then(({data}) => {
        checkinTime.value = data
      })
    }
  })
})
</script>

<style>
#config {
  max-width: 1080px;
}
</style>
