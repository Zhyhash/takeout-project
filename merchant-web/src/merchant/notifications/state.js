import {reactive} from 'vue'
import {clearRoleSession, getSession} from '../../auth.js'
import {getUnseenOrders, markOrdersSeen} from '../../api/merchantApi.js'
import {openMerchantEventStream} from './stream.js'

export const notificationState = reactive({
  connection: 'idle', unseenIds: [], syncing: false, error: '', ackError: '', revision: 0, acknowledged: [],
})
let context = null
const valid = (ctx) => context === ctx && getSession('merchant')?.token === ctx.token
const visible = () => document.visibilityState !== 'hidden'

export function startMerchantNotifications() {
  stopMerchantNotifications()
  const account = getSession('merchant')
  if (!account) return () => {}
  const ctx = { token: account.token, id: account.id, pending: new Set(), refresh: null, trailing: false, acknowledged: new Set() }
  context = ctx
  Object.assign(notificationState, { connection: 'connecting', unseenIds: [], error: '', ackError: '', acknowledged: [] })
  ctx.onVisibility = () => {
    closeTransport(ctx)
    if (visible()) {
      void refreshNotifications(); connectTransport(ctx)
      if (ctx.pending.size && !ctx.ackTimer && !ctx.acking) ctx.ackTimer = setTimeout(() => flushSeen(ctx), 150)
    }
    else notificationState.connection = 'paused'
  }
  document.addEventListener('visibilitychange', ctx.onVisibility)
  ctx.poll = setInterval(() => { if (visible()) void refreshNotifications() }, 30000)
  if (visible()) { void refreshNotifications(); connectTransport(ctx) }
  else notificationState.connection = 'paused'
  return () => { if (context === ctx) stopMerchantNotifications() }
}

function closeTransport(ctx) {
  ctx.transportGeneration = (ctx.transportGeneration || 0) + 1
  ctx.stream?.close(); ctx.stream = null
  ctx.lockAbort?.abort(); ctx.lockAbort = null
  ctx.releaseLock?.(); ctx.releaseLock = null
}

function connectTransport(ctx) {
  const generation = ctx.transportGeneration || 0
  const current = () => valid(ctx) && visible() && generation === (ctx.transportGeneration || 0)
  const start = () => {
    if (!current()) return
    ctx.stream = openMerchantEventStream({
      token: ctx.token,
      onState: (status) => { if (current()) notificationState.connection = status },
      onConnect: () => { if (current()) void refreshNotifications() },
      onEvent: (event) => { if (current() && event.type === 'NEW_ORDER') void refreshNotifications() },
      onAuthError: () => { if (valid(ctx)) clearRoleSession('merchant', ctx.token) },
    })
  }
  if (!navigator.locks?.request) { start(); return }
  notificationState.connection = 'waiting'
  ctx.lockAbort = new AbortController()
  // Only one tab owns the process's single SSE connection; other tabs keep polling.
  void navigator.locks.request(`tokeout.merchant.events.${ctx.id}`, { signal: ctx.lockAbort.signal }, async () => {
    if (!current()) return
    const released = new Promise((resolve) => { ctx.releaseLock = resolve })
    start()
    await released
  }).catch(() => { /* Aborted on hide, role change or unmount. */ })
}

export function stopMerchantNotifications() {
  const ctx = context
  if (!ctx) return
  context = null
  closeTransport(ctx)
  clearInterval(ctx.poll)
  clearTimeout(ctx.ackTimer)
  document.removeEventListener('visibilitychange', ctx.onVisibility)
  Object.assign(notificationState, { connection: 'idle', unseenIds: [], syncing: false, error: '', ackError: '', acknowledged: [] })
}

export async function refreshNotifications() {
  const ctx = context
  if (!ctx || !valid(ctx) || !visible()) return
  if (ctx.refresh) { ctx.trailing = true; return ctx.refresh }
  notificationState.syncing = true
  ctx.refresh = (async () => {
    try {
      const ids = await getUnseenOrders({ expectedToken: ctx.token })
      if (!valid(ctx)) return
      if (!Array.isArray(ids)) throw new Error('新单提醒返回了无法识别的数据')
      notificationState.unseenIds = ids.filter((id) => !ctx.acknowledged.has(String(id)))
      notificationState.error = ''
      notificationState.revision++
    } catch (error) {
      if (valid(ctx)) {
        notificationState.error = error.message || '新单同步失败，请重试'
        notificationState.revision++
      }
    } finally {
      if (valid(ctx)) {
        notificationState.syncing = false
        ctx.refresh = null
        if (ctx.trailing) { ctx.trailing = false; void refreshNotifications() }
      }
    }
  })()
  return ctx.refresh
}

export function queueOrderSeen(id) {
  const ctx = context
  if (!ctx || !valid(ctx) || !visible() || ctx.acknowledged.has(String(id))) return
  ctx.pending.add(id)
  if (!ctx.ackTimer && !ctx.acking) ctx.ackTimer = setTimeout(() => flushSeen(ctx), 150)
}

async function flushSeen(ctx) {
  ctx.ackTimer = null
  if (!valid(ctx) || !visible() || !ctx.pending.size || ctx.acking) return
  const ids = [...ctx.pending]
  ctx.pending.clear()
  ctx.acking = true
  try {
    await markOrdersSeen(ids, { expectedToken: ctx.token })
    if (!valid(ctx)) return
    ids.forEach((id) => ctx.acknowledged.add(String(id)))
    notificationState.acknowledged = [...ctx.acknowledged]
    notificationState.unseenIds = notificationState.unseenIds.filter((id) => !ctx.acknowledged.has(String(id)))
    notificationState.ackError = ''
  } catch (error) {
    if (valid(ctx)) {
      ids.forEach((id) => ctx.pending.add(id))
      notificationState.ackError = '新单标记确认未完成，将稍后重试'
    }
  } finally {
    ctx.acking = false
    if (valid(ctx) && ctx.pending.size) ctx.ackTimer = setTimeout(() => flushSeen(ctx), 5000)
  }
}
