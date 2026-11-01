import { computed, reactive } from 'vue'

export const ROLES = ['customer', 'merchant', 'rider']
const storageKey = (role) => `takeout.${role}.web.session`
const cleanups = new Map()

function loadSession(role) {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey(role)) || 'null')
    if (saved && typeof saved.token === 'string' && saved.token.trim()
        && /^[1-9]\d*$/.test(String(saved.id))) return saved
  } catch { /* Storage can be disabled by the browser. */ }
  return null
}

export const roleSessions = reactive(Object.fromEntries(ROLES.map((role) => [role, loadSession(role)])))
// Preserve the original merchant component contract.
export const session = computed(() => roleSessions.merchant)
export const getSession = (role) => roleSessions[role] || null

export function setRoleSession(role, next) {
  if (!ROLES.includes(role) || !next?.token || !/^[1-9]\d*$/.test(String(next.id))) {
    throw new Error('登录服务返回的身份信息不完整，请重试')
  }
  roleSessions[role] = { id: next.id, token: next.token, name: next.name || next.nickname || '' }
  try { sessionStorage.setItem(storageKey(role), JSON.stringify(roleSessions[role])) } catch { /* Memory session remains usable. */ }
}

export function clearRoleSession(role, expectedToken) {
  if (expectedToken !== undefined && roleSessions[role]?.token !== expectedToken) return
  roleSessions[role] = null
  try { sessionStorage.removeItem(storageKey(role)) } catch { /* No persistent session to clear. */ }
}

export const setSession = (next) => setRoleSession('merchant', next)
export const clearSession = () => clearRoleSession('merchant')

export function registerSessionCleanup(role, callback) {
  if (!cleanups.has(role)) cleanups.set(role, new Set())
  cleanups.get(role).add(callback)
  return () => cleanups.get(role)?.delete(callback)
}

export async function logoutRole(role) {
  const token = getSession(role)?.token
  for (const cleanup of [...(cleanups.get(role) || [])]) {
    if (await cleanup() === false) return false
  }
  clearRoleSession(role, token)
  return true
}
