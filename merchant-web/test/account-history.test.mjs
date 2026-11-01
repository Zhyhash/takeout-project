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
let accountApi
let customerApi
let registration

before(async () => {
  vite = await createServer({
    configFile: resolve(root, 'vite.config.js'),
    root,
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true },
  })
  auth = await vite.ssrLoadModule('/src/auth.js')
  createRoleHttp = (await vite.ssrLoadModule('/src/api/http.js')).createRoleHttp
  accountApi = await vite.ssrLoadModule('/src/api/accountApi.js')
  customerApi = await vite.ssrLoadModule('/src/api/customerApi.js')
  registration = await vite.ssrLoadModule('/src/accounts/registration.js')
})

after(async () => {
  await vite?.close()
  if (originalStorage === undefined) delete globalThis.sessionStorage
  else globalThis.sessionStorage = originalStorage
})

function jsonAdapter(body, onRequest = () => {}) {
  return async (config) => {
    onRequest(config)
    return { data: body, status: 200, statusText: 'OK', headers: {}, config, request: {} }
  }
}

const common = { password: 'safe-password-123', confirmPassword: 'safe-password-123', phone: '13800138000' }

test('registration payloads match the three server DTOs', () => {
  const customer = registration.registrationPayload('customer', { ...common, username: 'buyer_1', merchantName: 'ignored' })
  const merchant = registration.registrationPayload('merchant', { ...common, username: 'shop_1', merchantName: '青禾小馆', address: '杭州', pictureURL: 'https://example.test/shop.png', description: '家常菜' })
  const rider = registration.registrationPayload('rider', { ...common, name: 'rider_1', username: 'ignored' })

  assert.deepEqual(Object.keys(customer).sort(), ['confirmPassword', 'password', 'phone', 'username'])
  assert.deepEqual(Object.keys(merchant).sort(), ['address', 'confirmPassword', 'description', 'merchantName', 'password', 'phone', 'pictureURL', 'username'])
  assert.deepEqual(Object.keys(rider).sort(), ['confirmPassword', 'name', 'password', 'phone'])
  assert.equal(customer.username, 'buyer_1')
  assert.equal(merchant.merchantName, '青禾小馆')
  assert.equal(rider.name, 'rider_1')
})

test('registration validation follows required fields, phone format, lengths, and password matching', () => {
  for (const role of ['customer', 'merchant', 'rider']) {
    const values = role === 'rider' ? { ...common, name: 'valid_rider' } : { ...common, username: 'valid_user', ...(role === 'merchant' ? { merchantName: '好店' } : {}) }
    assert.deepEqual(registration.registrationErrors(role, values), {})
  }

  assert.equal(registration.registrationErrors('customer', { ...common, username: 'bad name' }).username, '登录名只能包含英文字母、数字、下划线和连字符')
  assert.equal(registration.registrationErrors('customer', { ...common, username: 'ok', confirmPassword: 'different-123' }).confirmPassword, '两次输入的密码不一致')
  assert.equal(registration.registrationErrors('merchant', { ...common, username: 'ok', merchantName: '' }).merchantName, '商家名称不能为空')
  assert.equal(registration.registrationErrors('rider', { ...common, name: 'ok', phone: '123' }).phone, '请输入有效的中国大陆手机号')
  assert.throws(() => registration.registrationPayload('admin', {}), /未知的注册身份/)
})

test('registration calls the role endpoint without auto-login or credential persistence', async () => {
  storageValues.clear()
  const values = {
    customer: { ...common, username: 'buyer_1' },
    merchant: { ...common, username: 'shop_1', merchantName: '青禾小馆' },
    rider: { ...common, name: 'rider_1' },
  }
  const expected = {
    customer: { endpoint: '/user/register', keys: ['confirmPassword', 'password', 'phone', 'username'] },
    merchant: { endpoint: '/merchant/register', keys: ['address', 'confirmPassword', 'description', 'merchantName', 'password', 'phone', 'pictureURL', 'username'] },
    rider: { endpoint: '/rider/register', keys: ['confirmPassword', 'name', 'password', 'phone'] },
  }

  for (const [index, role] of ['customer', 'merchant', 'rider'].entries()) {
    auth.setRoleSession(role, { id: index + 1, token: `existing-${role}-token`, name: `${role} account` })
    const before = auth.getSession(role)
    const seen = []
    createRoleHttp(role).defaults.adapter = jsonAdapter({ code: 200, data: 'success' }, (config) => {
      const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
      seen.push({ endpoint: config.url, body, auth: config.headers.get('Authorization'), sessionToken: config.sessionToken })
    })

    await accountApi.registerAccount(role, values[role])
    assert.equal(seen.length, 1)
    assert.equal(seen[0].endpoint, expected[role].endpoint)
    assert.deepEqual(Object.keys(seen[0].body).sort(), expected[role].keys)
    assert.equal(seen[0].auth, undefined)
    assert.equal(seen[0].sessionToken, null)
    assert.equal(auth.getSession(role).token, before.token)
    assert.equal(auth.getSession(role).id, before.id)
  }

  assert.equal(JSON.stringify([...storageValues.entries()]).includes(common.password), false)
})

test('local account summary exposes identity fields without token or password', () => {
  assert.deepEqual(accountApi.readLocalAccount('customer', { id: 12, name: '小禾', token: 'secret-token', password: 'secret-password' }), {
    id: 12, name: '小禾', role: 'customer', roleLabel: '顾客',
  })
  assert.equal(accountApi.readLocalAccount('unknown', { id: 1, name: 'x' }), null)
})

test('browse history API calls the customer endpoints only', async () => {
  auth.setRoleSession('customer', { id: 55, token: 'history-customer-token' })
  const requests = []
  createRoleHttp('customer').defaults.adapter = jsonAdapter({ code: 200, data: [] }, (config) => {
    requests.push({ method: config.method.toUpperCase(), url: config.url, auth: config.headers.get('Authorization') })
  })

  await customerApi.getBrowseHistory()
  await customerApi.recordProductView(17)
  await customerApi.clearBrowseHistory()

  assert.deepEqual(requests.map(({ method, url }) => [method, url]), [
    ['GET', '/user/browse-history'],
    ['POST', '/user/browse-history/17'],
    ['DELETE', '/user/browse-history'],
  ])
  assert.ok(requests.every((request) => request.auth === 'Bearer history-customer-token'))
})
