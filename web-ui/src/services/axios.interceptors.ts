import axios, {AxiosHeaders} from 'axios'
import accountService from '@/services/account.service'
import router from '@/router'
import {ElMessage} from 'element-plus'

axios.interceptors.request.use(function (config) {
  const token = accountService.getToken()
  if (config.headers && token) {
    const headers = config.headers as AxiosHeaders
    headers.set('Authorization', token)
  }
  return config
}, function (error) {
  return Promise.reject(error)
})

axios.interceptors.response.use(function (response) {
  return response
}, function (error) {
  if (error.response && error.response.status === 401) {
    accountService.logout()
    // 避免重复跳转到登录页
    if (router.currentRoute.value.path !== '/login') {
      router.push('/login')
    }
  }
  const data = error.response?.data
  if (data?.message || data?.detail) {
    console.debug(data.message)
    ElMessage({
      showClose: true,
      grouping: true,
      message: data.message || data.detail,
      type: 'error',
    })
  }
  return Promise.reject(data)
})
