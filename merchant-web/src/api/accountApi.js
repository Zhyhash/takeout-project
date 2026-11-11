import {createRoleHttp} from './http.js'
import {registrationErrors, registrationPayload} from '../accounts/registration.js'

export { registrationErrors, registrationPayload }

export function registerAccount(role, values) {
  const payload = registrationPayload(role, values)
  const errors = registrationErrors(role, payload)
  if (Object.keys(errors).length) {
    const error = new Error('请检查注册信息后重试')
    error.kind = 'validation'
    error.fields = errors
    throw error
  }
  const endpoint = role === 'customer' ? '/user/register' : `/${role}/register`
  return createRoleHttp(role).post(endpoint, payload, { skipAuth: true })
}

export function readLocalAccount(role, session) {
  if (!session) return null
  const labels = { customer: '顾客', merchant: '商家', rider: '骑手' }
  if (!labels[role]) return null
  return {
    id: session.id,
    name: session.name || '',
    role,
    roleLabel: labels[role],
  }
}
