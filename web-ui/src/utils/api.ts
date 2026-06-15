import axios, { AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'

// 扩展 axios 配置类型
declare module 'axios' {
  export interface AxiosRequestConfig {
    metadata?: {
      startTime: number
    }
  }
}

/**
 * 创建带超时和错误处理的 axios 实例
 */
const createApiClient = (): AxiosInstance => {
  const api = axios.create({
    timeout: 15000, // 15秒超时
    headers: {
      'Content-Type': 'application/json'
    }
  })

  // 请求拦截器 - 记录开始时间
  api.interceptors.request.use(
    config => {
      config.metadata = { startTime: Date.now() }
      return config
    },
    error => Promise.reject(error)
  )

  // 响应拦截器 - 记录耗时和错误处理
  api.interceptors.response.use(
    response => {
      const duration = Date.now() - (response.config.metadata?.startTime || 0)

      // 慢请求警告（超过 5 秒）
      if (duration > 5000) {
        console.warn(`⚠️ 慢请求: ${response.config.url} 耗时 ${duration}ms`)
      }

      return response
    },
    error => {
      // 超时错误
      if (error.code === 'ECONNABORTED') {
        ElMessage.error('请求超时，请稍后重试')
        console.error('超时:', error.config?.url)
      }
      // 网关超时
      else if (error.response?.status === 504) {
        ElMessage.error('服务器响应超时')
      }
      // 服务不可用
      else if (error.response?.status === 503) {
        ElMessage.error('服务暂时不可用，请稍后重试')
      }
      // 网络连接失败
      else if (!error.response) {
        ElMessage.error('网络连接失败')
      }

      return Promise.reject(error)
    }
  )

  return api
}

export default createApiClient()
