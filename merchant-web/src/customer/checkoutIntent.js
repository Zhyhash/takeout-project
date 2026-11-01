const STORAGE_PREFIX = 'takeout.customer.checkout.v1:'
const ARCHIVE_PREFIX = 'takeout.customer.checkout-recovery.v1:'

const storageFor = (userId) => `${STORAGE_PREFIX}${userId}`
const archiveFor = (userId) => `${ARCHIVE_PREFIX}${userId}`

function requestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`
}

function parseIntent(raw, recoverInterruptedAttempt = true) {
  const value = JSON.parse(raw)
  if (typeof value?.requestId !== 'string' || !value.requestId
      || !value.payload || value.payload.requestId !== value.requestId) return null
  return recoverInterruptedAttempt && value.inFlight ? { ...value, inFlight: false, uncertain: true } : value
}

export function readCheckoutIntent(userId) {
  if (!userId) return null
  try {
    const raw = localStorage.getItem(storageFor(userId))
    if (!raw) return null
    return parseIntent(raw) || { unreadable: true }
  } catch {
    return { unreadable: true }
  }
}

export function writeCheckoutIntent(userId, payload, totalAmount = null) {
  if (!userId) throw new Error('登录信息已失效，请重新登录。')
  const previous = readCheckoutIntent(userId)
  if (previous) throw new Error('已有一笔下单尚未确认，请先恢复或核对上一笔。')
  const id = requestId()
  const intent = {
    requestId: id,
    createdAt: new Date().toISOString(),
    totalAmount,
    attempts: 0,
    inFlight: false,
    uncertain: false,
    payload: { ...payload, requestId: id },
  }
  localStorage.setItem(storageFor(userId), JSON.stringify(intent))
  return intent
}

export function beginCheckoutAttempt(userId, intent) {
  const current = readCheckoutIntent(userId)
  if (current?.unreadable || current?.requestId !== intent?.requestId || current?.orderId) {
    throw new Error('下单记录已变化，请刷新页面并核对订单列表。')
  }
  const next = { ...current, attempts: Number(current.attempts || 0) + 1, inFlight: true }
  localStorage.setItem(storageFor(userId), JSON.stringify(next))
  return next
}

export function settleKnownCheckoutRejection(userId, intent) {
  let current
  try {
    const raw = localStorage.getItem(storageFor(userId))
    current = raw ? parseIntent(raw, false) : null
  } catch { current = null }
  if (current?.unreadable || current?.requestId !== intent?.requestId) {
    throw new Error('下单记录已变化，请刷新页面并核对订单列表。')
  }
  const next = { ...current, inFlight: false }
  localStorage.setItem(storageFor(userId), JSON.stringify(next))
  return next
}

export function markCheckoutAttemptUncertain(userId, intent) {
  const current = readCheckoutIntent(userId)
  if (current?.unreadable || current?.requestId !== intent?.requestId) {
    return { ...intent, inFlight: true, uncertain: true }
  }
  const next = { ...current, inFlight: false, uncertain: true }
  try { localStorage.setItem(storageFor(userId), JSON.stringify(next)) } catch { return next }
  return next
}

export function recordCreatedOrder(userId, intent, orderId) {
  if (!userId || !intent?.requestId || orderId === null || orderId === undefined) {
    throw new Error('无法保存已创建订单的编号。')
  }
  const current = readCheckoutIntent(userId)
  if (current?.unreadable || current?.requestId !== intent.requestId) {
    throw new Error('下单记录已变化，请从订单列表核对已创建的订单。')
  }
  if (current.orderId && String(current.orderId) !== String(orderId)) {
    throw new Error('本次下单记录与返回的订单编号不一致，请从订单列表核对。')
  }
  const updated = { ...current, orderId: String(orderId), inFlight: false }
  localStorage.setItem(storageFor(userId), JSON.stringify(updated))
  return updated
}

export function clearCheckoutIntent(userId) {
  if (!userId) return
  localStorage.removeItem(storageFor(userId))
}

export function archiveCheckoutIntent(userId, intent, reason = 'customer-reviewed') {
  if (!userId) throw new Error('登录信息已失效，请重新登录。')
  const primaryKey = storageFor(userId)
  const raw = localStorage.getItem(primaryKey)
  if (!raw) throw new Error('找不到待核对的下单记录，请刷新页面后重试。')
  const parsed = (() => { try { return parseIntent(raw) } catch { return null } })()
  if (intent?.requestId && parsed?.requestId !== intent.requestId) {
    throw new Error('下单记录已变化，请刷新后再处理。')
  }
  const record = {
    archivedAt: new Date().toISOString(),
    reason,
    intent: parsed,
    raw: parsed ? undefined : raw,
  }
  localStorage.setItem(archiveFor(userId), JSON.stringify(record))
  localStorage.removeItem(primaryKey)
  return record
}

export function readArchivedCheckoutIntent(userId) {
  if (!userId) return null
  try {
    const raw = localStorage.getItem(archiveFor(userId))
    if (!raw) return null
    const record = JSON.parse(raw)
    return record?.intent?.requestId ? record : null
  } catch {
    return null
  }
}

export function restoreArchivedCheckoutIntent(userId) {
  const archived = readArchivedCheckoutIntent(userId)
  if (!archived?.intent) throw new Error('这条恢复记录无法读取，请先核对订单列表。')
  if (readCheckoutIntent(userId)) throw new Error('已有下单记录，请先处理当前记录。')
  localStorage.setItem(storageFor(userId), JSON.stringify(archived.intent))
  localStorage.removeItem(archiveFor(userId))
  return archived.intent
}

export function isDefinitelyRejectedCreate(error, intent = null) {
  const code = Number(error?.code)
  const message = String(error?.message || '')
  if (code === 1001) return false
  if (intent?.uncertain) return false
  if (error?.status === 400 && error?.code == null) return true
  if (code === 2001) return true
  if (code === 400) return !/(request\s*id|请求标识|下单参数)/i.test(message)
  if (code !== 500) return false
  return [
    /^商家不存在$/,
    /^商品总金额超过最大结算数/,
    /^计价失败[:：]/,
    /^创建订单失败$/,
    /^商品不存在$/,
    /^库存版本缺失/,
  ].some((pattern) => pattern.test(message.trim()))
}

export function createCheckoutSubmitLock() {
  let lease = null
  return {
    acquire() {
      if (lease) return null
      lease = Symbol('checkout-submit')
      return lease
    },
    release(currentLease) { if (lease === currentLease) lease = null },
    reset() { lease = null },
  }
}
