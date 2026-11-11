import {createRiderApi} from '@rider-core/api.mjs'
import {createRoleHttp} from './http.js'
import {clearRoleSession} from '../auth.js'

const http = createRoleHttp('rider')
const apiBaseUrl = () => new URL('/', window.location.origin)

function normalizeLocationError(error, token) {
  if (error?.code === 401 || error?.status === 401) {
    clearRoleSession('rider', token)
    error.kind = 'auth'
  } else if (error?.code === 403 || error?.status === 403) {
    // 403 may be a transient restriction or rate limit. It does not invalidate the JWT.
    error.kind = 'server'
  }
  throw error
}

export function riderLogin(name, password) {
  return createRiderApi({ baseUrl: apiBaseUrl(), getToken: () => '' })
    .login({ name, password })
    .catch((error) => normalizeLocationError(error, null))
}

export function createRiderLocationClient(token) {
  // Bind this location lifecycle to the session that created it. A later login
  // must never let an old tracker DELETE the new rider's shared location record.
  const locationApi = createRiderApi({ baseUrl: apiBaseUrl(), getToken: () => token })
  const wrap = (operation) => (...args) => operation(...args)
    .catch((error) => normalizeLocationError(error, token))

  return {
    updateLocation: wrap(locationApi.updateLocation),
    removeLocation: wrap(locationApi.removeLocation),
    getAvailableTasks: wrap(locationApi.getAvailableTasks),
  }
}

export function getCurrentTasks() {
  return http.get('/rider/delivery-tasks/current')
}

export function getRiderTaskDetail(taskId) {
  return http.get(`/rider/delivery-tasks/${encodeURIComponent(taskId)}`)
}

export function claimRiderTask(taskId) {
  return http.patch(`/rider/delivery-tasks/${encodeURIComponent(taskId)}/claim`)
}

export function completeRiderTask(taskId) {
  return http.patch(`/rider/delivery-tasks/${encodeURIComponent(taskId)}/complete`)
}
