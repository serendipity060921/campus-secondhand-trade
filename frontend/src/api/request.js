import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, removeToken } from '@/utils/auth'
import router from '@/router'

/**
 * axios 统一封装
 *
 * 后端统一响应结构：{ code, message, data, timestamp }
 * 约定：code === 200 表示业务成功。
 *
 * 使用方式（见 api/common.js）：
 *   const res = await healthApi()   // res 即后端返回的 Result 对象
 *   console.log(res.data)           // 业务数据
 */
const service = axios.create({
  // 开发环境走 vite 代理（/api -> http://127.0.0.1:8080）
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' }
})

/* ---------------- 请求拦截器：携带 Token ---------------- */
service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/* ---------------- 响应拦截器：统一处理业务码与异常 ---------------- */
service.interceptors.response.use(
  (response) => {
    const res = response.data

    // 非 JSON（如文件流）直接返回
    if (!res || typeof res !== 'object' || res.code === undefined) {
      return res
    }

    if (res.code === 200) {
      return res
    }

    // 401 / 403：登录过期或权限不足
    if (res.code === 401 || res.code === 403) {
      removeToken()
      ElMessage.error(res.message || '登录已过期，请重新登录')
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      return Promise.reject(new Error(res.message || '未授权'))
    }

    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    // 网络层错误：后端未启动 / 超时 / 404 等
    let message = '网络异常，请检查后端服务是否启动'
    if (error.code === 'ECONNABORTED') {
      message = '请求超时，请稍后重试'
    } else if (error.response) {
      const status = error.response.status
      if (status === 404) {
        message = `接口不存在：${error.config?.url}`
      } else if (status === 500) {
        message = '服务器内部错误（请查看后端控制台日志）'
      } else {
        message = `请求失败（HTTP ${status}）`
      }
    }
    console.error('[axios error]', error)
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default service
