import assert from 'node:assert/strict'
import { after, afterEach, before, test } from 'node:test'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const originalDescriptors = new Map(['sessionStorage', 'document', 'navigator', 'fetch', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval']
  .map((name) => [name, Object.getOwnPropertyDescriptor(globalThis, name)]))
const storage = new Map()
Object.defineProperty(globalThis, 'sessionStorage', {
  configurable: true,
  value: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: (key) => storage.delete(key) },
})

let vite
let auth
let notifications
let http
let page

before(async () => {
  vite = await createServer({ configFile: resolve(root, 'vite.config.js'), root, appType: 'custom', logLevel: 'silent', server: { middlewareMode: true } })
  auth = await vite.ssrLoadModule('/src/auth.js')
  notifications = await vite.ssrLoadModule('/src/merchant/notifications/state.js')
  http = (await vite.ssrLoadModule('/src/api/http.js')).createRoleHttp('merchant')
})

afterEach(() => {
  try { notifications?.stopMerchantNotifications() } finally {
    auth?.clearRoleSession('merchant')
    restorePage()
  }
})

after(async () => {
  await vite?.close()
  restorePage()
  for (const [name, descriptor] of originalDescriptors) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
})

function restorePage() {
  if (!page) return
  for (const [name, descriptor] of page.original) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else delete globalThis[name]
  }
  page = null
}

function installPage(visibilityState = 'visible') {
  restorePage()
  const names = ['document', 'navigator', 'fetch', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval']
  const original = new Map(names.map((name) => [name, Object.getOwnPropertyDescriptor(globalThis, name)]))
  let nextId = 0
  const timeouts = new Map()
  const intervals = new Map()
  const listeners = new Map()
  const clock = {
    timeouts,
    intervals,
    setTimeout(callback, delay = 0) { const id = ++nextId; timeouts.set(id, { callback, delay }); return id },
    clearTimeout(id) { timeouts.delete(id) },
    setInterval(callback, delay = 0) { const id = ++nextId; intervals.set(id, { callback, delay }); return id },
    clearInterval(id) { intervals.delete(id) },
    fireTimeouts(delay) {
      const due = [...timeouts].filter(([, task]) => delay === undefined || task.delay === delay)
      for (const [id, task] of due) { timeouts.delete(id); task.callback() }
    },
  }
  const documentStub = {
    visibilityState,
    addEventListener(type, callback) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(callback) },
    removeEventListener(type, callback) { listeners.get(type)?.delete(callback) },
    dispatchEvent(event) { for (const callback of [...(listeners.get(event.type) || [])]) callback(event) },
    setVisibility(value) { this.visibilityState = value; this.dispatchEvent({ type: 'visibilitychange' }) },
  }
  for (const [name, value] of [
    ['document', documentStub],
    ['navigator', { locks: undefined }],
    ['fetch', (url, options) => {
      return new Promise((_resolve, reject) => {
        if (options.signal.aborted) { reject(new Error('aborted')); return }
        options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
      })
    }],
    ['setTimeout', clock.setTimeout.bind(clock)],
    ['clearTimeout', clock.clearTimeout.bind(clock)],
    ['setInterval', clock.setInterval.bind(clock)],
    ['clearInterval', clock.clearInterval.bind(clock)],
  ]) Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  page = { original, clock, document: documentStub }
  return page
}

function response(config, data) {
  return { data: { code: 200, data }, status: 200, statusText: 'OK', headers: {}, config, request: {} }
}

function setMerchant(token = 'merchant-token') {
  auth.setRoleSession('merchant', { id: 7, token, name: '测试商家' })
}

function standardAdapter(unseenIds = []) {
  const requests = []
  http.defaults.adapter = async (config) => {
    const method = config.method.toUpperCase()
    const data = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    requests.push({ method, url: config.url, data, token: config.sessionToken, expectedToken: config.expectedToken })
    return response(config, method === 'GET' ? unseenIds : 'success')
  }
  return requests
}

async function settle() {
  for (let i = 0; i < 16; i++) await Promise.resolve()
}

test('initial unseen refresh only lists IDs; it does not ACK them', async () => {
  installPage()
  setMerchant()
  const requests = standardAdapter([101, 102])

  notifications.startMerchantNotifications()
  await settle()

  assert.deepEqual(notifications.notificationState.unseenIds, [101, 102])
  assert.deepEqual(requests.map(({ method, url }) => [method, url]), [['GET', '/merchant/orders/unseen']])
})

test('ACK waits for a visible queue request and sends only the requested ID', async () => {
  installPage()
  setMerchant()
  const requests = standardAdapter([201, 202])
  notifications.startMerchantNotifications()
  await settle()

  notifications.queueOrderSeen(202)
  assert.equal(requests.some(({ method }) => method === 'PATCH'), false)
  page.clock.fireTimeouts(150)
  await settle()

  const ack = requests.find(({ method }) => method === 'PATCH')
  assert.deepEqual(ack.data, [202])
  assert.deepEqual(notifications.notificationState.unseenIds, [201])
})

test('queueing while hidden is ignored and returning to visible does not ACK unseen IDs by itself', async () => {
  installPage()
  setMerchant()
  const requests = standardAdapter([301])
  notifications.startMerchantNotifications()
  await settle()

  page.document.setVisibility('hidden')
  notifications.queueOrderSeen(301)
  page.clock.fireTimeouts(150)
  page.document.setVisibility('visible')
  await settle()

  assert.equal(requests.some(({ method }) => method === 'PATCH'), false)
})

test('an ACK timer that fires while hidden is rescheduled when the page becomes visible', async () => {
  installPage()
  setMerchant()
  const requests = standardAdapter([351])
  notifications.startMerchantNotifications()
  await settle()

  notifications.queueOrderSeen(351)
  page.document.setVisibility('hidden')
  page.clock.fireTimeouts(150)
  page.document.setVisibility('visible')
  await settle()
  page.clock.fireTimeouts(150)
  await settle()

  assert.deepEqual(requests.filter(({ method }) => method === 'PATCH').map(({ data }) => data), [[351]])
  assert.deepEqual(notifications.notificationState.unseenIds, [])
})

test('stop and token replacement discard responses from the old notification context', async () => {
  installPage()
  setMerchant('token-A')
  const pending = []
  http.defaults.adapter = (config) => new Promise((resolve) => pending.push({ config, resolve: (ids) => resolve(response(config, ids)) }))

  notifications.startMerchantNotifications()
  await settle()
  assert.equal(pending.length, 1)
  notifications.stopMerchantNotifications()
  setMerchant('token-B')
  notifications.startMerchantNotifications()
  await settle()
  assert.equal(pending.length, 2)

  pending[0].resolve([401])
  await settle()
  assert.deepEqual(notifications.notificationState.unseenIds, [])
  pending[1].resolve([402])
  await settle()
  assert.deepEqual(notifications.notificationState.unseenIds, [402])
  assert.equal(notifications.notificationState.syncing, false)
})

test('overlapping refresh requests merge to one trailing GET without concurrent duplicates', async () => {
  installPage()
  setMerchant()
  const pending = []
  let inFlight = 0
  let maxInFlight = 0
  http.defaults.adapter = (config) => {
    if (config.method.toUpperCase() !== 'GET') return Promise.resolve(response(config, 'success'))
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    return new Promise((resolve) => pending.push({ config, resolve: (ids) => { inFlight--; resolve(response(config, ids)) } }))
  }

  notifications.startMerchantNotifications()
  await settle()
  const first = notifications.refreshNotifications()
  const second = notifications.refreshNotifications()
  const third = notifications.refreshNotifications()
  await settle()
  assert.equal(pending.length, 1)
  pending[0].resolve([501])
  await settle()
  assert.equal(pending.length, 2)
  assert.equal(maxInFlight, 1)
  pending[1].resolve([501, 502])
  await Promise.all([first, second, third])
  await settle()
  assert.deepEqual(notifications.notificationState.unseenIds, [501, 502])
  assert.equal(notifications.notificationState.syncing, false)
  assert.equal(maxInFlight, 1)
})

test('a late pre-ACK unseen response does not reintroduce an ACKed ID into the visible count', async () => {
  installPage()
  setMerchant()
  let pendingGet
  const requests = []
  http.defaults.adapter = (config) => {
    const method = config.method.toUpperCase()
    if (method === 'GET') return new Promise((resolve) => { pendingGet = { config, resolve: (ids) => resolve(response(config, ids)) } })
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    requests.push({ method, body })
    return Promise.resolve(response(config, 'success'))
  }

  notifications.startMerchantNotifications()
  await settle()
  notifications.queueOrderSeen(601)
  page.clock.fireTimeouts(150)
  await settle()
  assert.deepEqual(notifications.notificationState.acknowledged, ['601'])
  pendingGet.resolve([601])
  await settle()

  assert.deepEqual(notifications.notificationState.unseenIds, [])
  assert.deepEqual(requests, [{ method: 'PATCH', body: [601] }])
})
