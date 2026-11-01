import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const storageValues = new Map()
const originalStorage = globalThis.sessionStorage
globalThis.sessionStorage = {
  getItem(key) { return storageValues.get(key) ?? null },
  setItem(key, value) { storageValues.set(key, String(value)) },
  removeItem(key) { storageValues.delete(key) },
}

let vite
let auth
let createRoleHttp

before(async () => {
  vite = await createServer({
    configFile: resolve(root, 'vite.config.js'),
    root,
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true },
  })
  auth = await vite.ssrLoadModule('/src/auth.js')
  const httpModule = await vite.ssrLoadModule('/src/api/http.js')
  createRoleHttp = httpModule.createRoleHttp
})

after(async () => {
  await vite?.close()
  if (originalStorage === undefined) delete globalThis.sessionStorage
  else globalThis.sessionStorage = originalStorage
})

function setSession(role, token, id = 1) {
  auth.setRoleSession(role, { id, token })
}

function jsonAdapter(body) {
  return async (config) => ({
    data: body,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
    request: {},
  })
}

test('role clients send only their own saved token', async () => {
  setSession('customer', 'qa-customer-token', 11)
  setSession('merchant', 'qa-merchant-token', 22)
  setSession('rider', 'qa-rider-token', 33)

  const seen = new Map()
  for (const role of ['customer', 'merchant', 'rider']) {
    const client = createRoleHttp(role)
    client.defaults.adapter = async (config) => {
      seen.set(role, config.headers.get('Authorization'))
      return jsonAdapter({ code: 200, data: role })(config)
    }
    assert.equal(await client.get('/qa'), role)
  }

  assert.deepEqual(Object.fromEntries(seen), {
    customer: 'Bearer qa-customer-token',
    merchant: 'Bearer qa-merchant-token',
    rider: 'Bearer qa-rider-token',
  })
})

test('an HTTP 200 body code 401 clears only the matching role session', async () => {
  setSession('customer', 'expired-customer-token', 11)
  setSession('merchant', 'still-valid-merchant-token', 22)
  const client = createRoleHttp('customer')
  client.defaults.adapter = jsonAdapter({ code: 401, message: 'expired' })

  await assert.rejects(client.get('/qa'), { kind: 'auth', code: 401 })
  assert.equal(auth.getSession('customer'), null)
  assert.equal(auth.getSession('merchant')?.token, 'still-valid-merchant-token')
})

test('an HTTP 200 body code 403 reports a restriction and keeps the session', async () => {
  setSession('merchant', 'rate-limited-merchant-token', 22)
  const client = createRoleHttp('merchant')
  client.defaults.adapter = jsonAdapter({ code: 403, message: '稍后重试' })

  await assert.rejects(client.get('/qa'), { kind: 'server', code: 403 })
  assert.equal(auth.getSession('merchant')?.token, 'rate-limited-merchant-token')
})

test('a delayed response for an old token is rejected without clearing a new login', async () => {
  setSession('customer', 'old-customer-token', 11)
  const client = createRoleHttp('customer')
  let requestConfig
  let release
  let requestStarted
  const started = new Promise((resolveRequest) => { requestStarted = resolveRequest })
  client.defaults.adapter = (config) => {
    requestConfig = config
    requestStarted()
    return new Promise((resolveResponse) => { release = resolveResponse })
  }

  const request = client.get('/qa')
  await started
  setSession('customer', 'new-customer-token', 44)
  release({
    data: { code: 401, message: 'late old response' },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: requestConfig,
    request: {},
  })

  await assert.rejects(request, { kind: 'stale' })
  assert.equal(auth.getSession('customer')?.token, 'new-customer-token')
  assert.equal(auth.getSession('customer')?.id, 44)
})

test('a cleanup hook can keep a role session alive when logout cleanup fails', async () => {
  setSession('rider', 'location-cleanup-pending-token', 33)
  const unregister = auth.registerSessionCleanup('rider', async () => false)
  try {
    assert.equal(await auth.logoutRole('rider'), false)
    assert.equal(auth.getSession('rider')?.token, 'location-cleanup-pending-token')
  } finally {
    unregister()
    auth.clearRoleSession('rider')
  }
})

test('an expected token from a previous session is rejected before sending a request', async () => {
  setSession('merchant', 'new-session-token', 22)
  const client = createRoleHttp('merchant')
  let calls = 0
  client.defaults.adapter = async (config) => { calls++; return jsonAdapter({ code: 200, data: 'success' })(config) }
  await assert.rejects(client.get('/qa', { expectedToken: 'previous-session-token' }), { kind: 'stale' })
  assert.equal(calls, 0)
  assert.equal(auth.getSession('merchant')?.token, 'new-session-token')
})
