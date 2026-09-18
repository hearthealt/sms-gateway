import axios from 'axios'
import { clearSession, getValidToken } from '../utils/auth'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * 管理后台统一 http 客户端。
 *
 * 后端所有接口都返回 ApiResult<T> = { code, message, data }，
 * 这里在响应拦截器里统一解包，调用方直接拿到 data，
 * 不必每处都写 res.data.data。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  const token = getValidToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data

    // 非 ApiResult 结构（如文件流）原样返回
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code === 200) {
      return body.data
    }

    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status

    if (status === 401) {
      clearSession()
      if (router.currentRoute.value.path !== '/login') {
        ElMessage.error('登录已过期，请重新登录')
        router.push('/login')
      }
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络错误')
    }

    return Promise.reject(error)
  },
)

/**
 * 下面的包装函数把返回值类型收敛为解包后的业务数据，
 * 避免 axios 的 AxiosResponse 类型泄漏到调用方。
 */
export function get<T>(url: string, params?: object): Promise<T> {
  return http.get(url, { params }) as unknown as Promise<T>
}

export function post<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as unknown as Promise<T>
}

export function put<T>(url: string, data?: object, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config) as unknown as Promise<T>
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return http.delete(url, config) as unknown as Promise<T>
}

export default http
