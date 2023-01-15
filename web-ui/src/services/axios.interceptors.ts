import axios, {AxiosHeaders} from 'axios'
import accountService from '@/services/account.service'
import router from '@/router'
import {ElMessage} from 'element-plus'

axios.interceptors.request.use(function (config) {
  const token = accountService.getToken()
  if (config.headers && token) {
    const headers = config.headers as AxiosHeaders
    headers.set('X-ACCESS-TOKEN', token)
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
    if (error.response.data && (error.response.data.code === 40100 || error.response.data.code === 40102)) {
      router.push('/')
    }
  }
  const data = error.response.data
  console.debug(data.message)
  ElMessage({
    showClose: true,
    grouping: true,
    message: data.message,
    type: 'error',
  })
  return Promise.reject(data)
})
