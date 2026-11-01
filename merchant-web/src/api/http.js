import axios from 'axios'
import { clearRoleSession, getSession } from '../auth.js'

const clients = new Map()
export function apiError(kind, message, code = null, status = null) {
  return Object.assign(new Error(message), { kind, code, status, isApiError: true })
}

export function createRoleHttp(role) {
  if (clients.has(role)) return clients.get(role)
  const client = axios.create({ timeout: 15000 })
  client.interceptors.request.use((config) => {
    if (config.expectedToken !== undefined && config.expectedToken !== getSession(role)?.token) {
      return Promise.reject(apiError('stale', '登录身份已变化，请重新加载页面'))
    }
    config.sessionToken = config.skipAuth ? null : getSession(role)?.token || null
    if (config.sessionToken) config.headers.Authorization = `Bearer ${config.sessionToken}`
    return config
  })

  const isStale = (config) => config?.sessionToken && config.sessionToken !== getSession(role)?.token
  const fail = (config, code, message, status) => {
    if (isStale(config)) return Promise.reject(apiError('stale', '登录身份已变化，请重新加载页面'))
    if (code === 401 || status === 401) {
      if (!config?.skipAuth) clearRoleSession(role, config?.sessionToken)
      return Promise.reject(apiError('auth', message || '登录已失效，请重新登录', code, status))
    }
    return Promise.reject(apiError('server', message || '请求失败，请稍后重试', code, status))
  }

  client.interceptors.response.use((response) => {
    if (isStale(response.config)) return Promise.reject(apiError('stale', '登录身份已变化，请重新加载页面'))
    const body = response.data
    if (!body || typeof body.code !== 'number') return Promise.reject(apiError('response', '服务器返回了无法识别的响应'))
    if (body.code === 200) return body.data
    return fail(response.config, body.code, body.message, response.status)
  }, (error) => {
    if (error.isApiError) return Promise.reject(error)
    if (isStale(error.config)) return Promise.reject(apiError('stale', '登录身份已变化，请重新加载页面'))
    if (error.response) return fail(error.config, error.response.data?.code, error.response.data?.message, error.response.status)
    if (axios.isCancel(error)) return Promise.reject(apiError('cancelled', '请求已取消'))
    return Promise.reject(apiError('network', error.code === 'ECONNABORTED'
      ? '请求超时，操作结果可能尚未确认，请刷新后重试' : '网络异常，请检查连接后重试'))
  })
  clients.set(role, client)
  return client
}

export default createRoleHttp('merchant')
