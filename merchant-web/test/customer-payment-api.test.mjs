import assert from 'node:assert/strict'
import {dirname, resolve} from 'node:path'
import {Readable} from 'node:stream'
import {after, before, test} from 'node:test'
import {fileURLToPath} from 'node:url'
import {createServer} from 'vite'
import {handleRequest} from './api-fixture.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const customerToken = 'qa-customer-token'
const storageValues = new Map()
const originalStorage = globalThis.sessionStorage
globalThis.sessionStorage = {
  getItem(key) { return storageValues.get(key) ?? null },
  setItem(key, value) { storageValues.set(key, String(value)) },
  removeItem(key) { storageValues.delete(key) },
}

let vite
let auth
let customerApi
let customerHttp

before(async () => {
  vite = await createServer({
    configFile: resolve(root, 'vite.config.js'),
    root,
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true, hmr: false },
  })
  auth = await vite.ssrLoadModule('/src/auth.js')
  const { createRoleHttp } = await vite.ssrLoadModule('/src/api/http.js')
  customerHttp = createRoleHttp('customer')
  customerApi = await vite.ssrLoadModule('/src/api/customerApi.js')
  auth.setRoleSession('customer', { id: 11, token: customerToken })
})

after(async () => {
  await vite?.close()
  if (originalStorage === undefined) delete globalThis.sessionStorage
  else globalThis.sessionStorage = originalStorage
})

function adapterResponse(config, data) {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

async function callFixture(method, path, { token = customerToken, body } = {}) {
  const request = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  request.url = path
  request.method = method
  request.headers = { authorization: `Bearer ${token}` }

  let result
  await handleRequest(request, {
    writeHead(status, headers) {
      assert.equal(status, 200)
      assert.equal(headers['cache-control'], 'no-store')
    },
    end(json) { result = JSON.parse(json) },
  })
  return result
}

async function fixtureData(method, path, options) {
  const { code, message, data } = await callFixture(method, path, options)
  assert.equal(code, 200, message)
  return data
}

async function createCustomerOrder(requestId) {
  await fixtureData('POST', '/cart/items', { body: { productId: 1001 } })
  return fixtureData('POST', '/order', {
    body: {
      requestId,
      receiverName: '测试顾客',
      receiverPhone: '13800000000',
      receiverAddress: '杭州市拱墅区示范小区 1 幢',
      longitude: 120.1536,
      latitude: 30.2812,
    },
  })
}

test('customer payment API sends encoded IDs, customer auth, expected token, and signal', async () => {
  const orderId = '42/a?x=1'
  const signal = new AbortController().signal
  const config = { expectedToken: customerToken, signal }
  auth.setRoleSession('merchant', { id: 22, token: 'qa-merchant-token' })
  auth.setRoleSession('rider', { id: 33, token: 'qa-rider-token' })
  const observed = []
  customerHttp.defaults.adapter = async (request) => {
    observed.push(request)
    return adapterResponse(request, { code: 200, data: { orderId } })
  }

  assert.deepEqual(await customerApi.createOrderPayment(orderId, config), { orderId })
  assert.deepEqual(await customerApi.getOrderPayment(orderId, config), { orderId })
  assert.deepEqual(await customerApi.syncOrderPayment(orderId, config), { orderId })

  assert.deepEqual(observed.map(({ method, url }) => [method, url]), [
    ['post', '/order/42%2Fa%3Fx%3D1/payment'],
    ['get', '/order/42%2Fa%3Fx%3D1/payment'],
    ['post', '/order/42%2Fa%3Fx%3D1/payment/sync'],
  ])
  for (const request of observed) {
    assert.equal(request.headers.get('Authorization'), `Bearer ${customerToken}`)
    assert.equal(request.expectedToken, customerToken)
    assert.equal(request.signal, signal)
    assert.equal(request.data, undefined)
  }
})

test('a stale expected customer token blocks the payment request before the adapter', async () => {
  let calls = 0
  customerHttp.defaults.adapter = async (config) => {
    calls += 1
    return adapterResponse(config, { code: 200, data: {} })
  }

  await assert.rejects(
    customerApi.createOrderPayment(701, { expectedToken: 'previous-customer-token' }),
    { kind: 'stale' },
  )
  assert.equal(calls, 0)
})

test('payment API rejects HTTP 200 business failures and transport 503 responses', async () => {
  customerHttp.defaults.adapter = async (config) => adapterResponse(config, {
    code: 500,
    message: '订单尚未创建 Stripe 支付请求',
  })
  await assert.rejects(customerApi.syncOrderPayment(703), {
    kind: 'server', code: 500, status: 200, message: '订单尚未创建 Stripe 支付请求',
  })

  customerHttp.defaults.adapter = async (config) => {
    throw Object.assign(new Error('Service Unavailable'), {
      config,
      response: { status: 503, data: { code: 500, message: '支付服务暂不可用' }, config },
    })
  }
  await assert.rejects(customerApi.createOrderPayment(703), {
    kind: 'server', code: 500, status: 503, message: '支付服务暂不可用',
  })
})

test('QA fixture reuses one Intent while honoring the shared recovery backoff', async (t) => {
  const initialTime = Date.parse('2026-11-21T00:00:00Z')
  t.mock.timers.enable({ apis: ['Date'], now: initialTime })
  const { orderId } = await createCustomerOrder('qa-payment-api-order')
  const orderPath = `/order/${orderId}`
  const paymentPath = `${orderPath}/payment`
  const syncPath = `${paymentPath}/sync`

  const empty = await fixtureData('GET', paymentPath)
  assert.deepEqual(empty, {
    orderId, orderStatus: 0,
    paymentIntentId: null, amountMinor: null, paymentStatus: null, clientSecret: null,
    publishableKey: 'pk_test_qa_fixture', currency: 'cny',
  })

  const missing = await callFixture('POST', syncPath)
  assert.equal(missing.code, 500)
  assert.equal(missing.data, null)

  const created = await fixtureData('POST', paymentPath)
  assert.equal(created.orderId, orderId)
  assert.equal(created.orderStatus, 1)
  assert.equal(created.paymentStatus, 'requires_payment_method')
  assert.equal(created.amountMinor, 3350)
  assert.match(created.paymentIntentId, /^pi_qa_fixture_/)
  assert.match(created.clientSecret, /_secret_qa_fixture$/)
  const createdOrder = await fixtureData('GET', orderPath)
  assert.equal(createdOrder.originalAmount, 28.5)
  assert.equal(createdOrder.totalAmount, 33.5)

  const inBackoff = await fixtureData('POST', paymentPath)
  assert.equal(inBackoff.orderStatus, 1)
  assert.equal(inBackoff.paymentIntentId, created.paymentIntentId)
  assert.equal(inBackoff.paymentStatus, created.paymentStatus)
  assert.equal(inBackoff.clientSecret, null)
  assert.equal((await fixtureData('GET', orderPath)).status, 1)

  const readOnly = { ...created, clientSecret: null }
  assert.deepEqual(await fixtureData('GET', paymentPath), readOnly)

  t.mock.timers.setTime(initialTime + 14_999)
  assert.equal((await fixtureData('POST', paymentPath)).clientSecret, null)

  t.mock.timers.setTime(initialTime + 15_000)
  assert.deepEqual(await fixtureData('POST', syncPath), readOnly)
  assert.deepEqual(await fixtureData('GET', paymentPath), readOnly)
  assert.equal((await fixtureData('POST', paymentPath)).clientSecret, null)

  t.mock.timers.setTime(initialTime + 44_999)
  assert.equal((await fixtureData('POST', paymentPath)).clientSecret, null)
  t.mock.timers.setTime(initialTime + 45_000)
  const attempts = await Promise.all([fixtureData('POST', paymentPath), fixtureData('POST', paymentPath)])
  for (const attempt of attempts) {
    assert.equal(attempt.paymentIntentId, created.paymentIntentId)
    assert.equal(attempt.paymentStatus, 'requires_payment_method')
  }
  const issued = attempts.filter(attempt => attempt.clientSecret !== null)
  assert.equal(issued.length, 1)
  assert.match(issued[0].clientSecret, /_secret_qa_fixture$/)
})

test('QA fixture distinguishes unbound, processing, requires-action, and confirmed payments', async () => {
  const unbound = await fixtureData('GET', '/order/703/payment')
  assert.equal(unbound.orderStatus, 1)
  assert.equal(unbound.amountMinor, 3350)
  assert.equal(unbound.paymentIntentId, null)
  assert.equal(unbound.paymentStatus, null)
  assert.equal(unbound.clientSecret, null)
  assert.deepEqual(await fixtureData('GET', '/order/703/payment'), unbound)

  const recovered = await fixtureData('POST', '/order/703/payment/sync')
  assert.match(recovered.paymentIntentId, /^pi_qa_fixture_/)
  assert.equal(recovered.orderStatus, 1)
  assert.equal(recovered.paymentStatus, 'requires_payment_method')
  assert.equal(recovered.clientSecret, null)

  const processingPath = '/order/704/payment'
  const processing = await fixtureData('GET', processingPath)
  assert.equal(processing.orderStatus, 1)
  assert.equal(processing.paymentStatus, 'processing')
  assert.equal(processing.clientSecret, null)
  const processingAttempt = await fixtureData('POST', processingPath)
  assert.equal(processingAttempt.paymentIntentId, processing.paymentIntentId)
  assert.equal(processingAttempt.paymentStatus, 'processing')
  assert.match(processingAttempt.clientSecret, /_secret_qa_fixture$/)
  assert.equal((await fixtureData('GET', processingPath)).clientSecret, null)
  assert.equal((await fixtureData('POST', processingPath)).clientSecret, null)

  const requiresActionPath = '/order/705/payment'
  const requiresAction = await fixtureData('GET', requiresActionPath)
  assert.equal(requiresAction.paymentStatus, 'requires_action')
  assert.equal(requiresAction.clientSecret, null)
  const actionAttempt = await fixtureData('POST', requiresActionPath)
  assert.equal(actionAttempt.paymentIntentId, requiresAction.paymentIntentId)
  assert.equal(actionAttempt.paymentStatus, 'requires_action')
  assert.match(actionAttempt.clientSecret, /_secret_qa_fixture$/)
  assert.equal((await fixtureData('GET', requiresActionPath)).clientSecret, null)

  const paid = await fixtureData('GET', '/order/701/payment')
  assert.equal(paid.orderStatus, 2)
  assert.equal(paid.paymentStatus, 'succeeded')
  assert.equal(paid.clientSecret, null)
})

test('QA fixture enforces the payment window for WAIT_PAY and PAYING orders', async (t) => {
  const initialTime = Date.parse('2026-11-21T00:00:00Z')
  t.mock.timers.enable({ apis: ['Date'], now: initialTime })
  const expired = await createCustomerOrder('qa-payment-expired-wait-pay')
  const beforeDeadline = await createCustomerOrder('qa-payment-before-deadline')
  const paying = await createCustomerOrder('qa-payment-expired-paying')
  const expiredPath = `/order/${expired.orderId}`
  const beforeDeadlinePath = `/order/${beforeDeadline.orderId}/payment`
  const payingPath = `/order/${paying.orderId}/payment`

  const initialPayment = await fixtureData('POST', payingPath)
  assert.equal(initialPayment.orderStatus, 1)
  assert.match(initialPayment.clientSecret, /_secret_qa_fixture$/)

  const deadline = initialTime + 30 * 60_000
  t.mock.timers.setTime(deadline - 1)
  const allowedBeforeDeadline = await fixtureData('POST', beforeDeadlinePath)
  assert.equal(allowedBeforeDeadline.orderStatus, 1)
  assert.match(allowedBeforeDeadline.clientSecret, /_secret_qa_fixture$/)

  t.mock.timers.setTime(deadline)
  const expiredWaitPay = await callFixture('POST', `${expiredPath}/payment`)
  assert.equal(expiredWaitPay.code, 500)
  assert.equal((await fixtureData('GET', expiredPath)).status, 0)
  const expiredPayment = await fixtureData('GET', `${expiredPath}/payment`)
  assert.equal(expiredPayment.paymentIntentId, null)
  assert.equal(expiredPayment.amountMinor, null)

  const expiredPayingLocal = await fixtureData('GET', payingPath)
  assert.equal(expiredPayingLocal.orderStatus, 1)
  assert.equal(expiredPayingLocal.paymentIntentId, initialPayment.paymentIntentId)
  assert.equal(expiredPayingLocal.clientSecret, null)

  const expiredPayingAttempt = await fixtureData('POST', payingPath)
  assert.equal(expiredPayingAttempt.orderStatus, 1)
  assert.equal(expiredPayingAttempt.paymentIntentId, initialPayment.paymentIntentId)
  assert.equal(expiredPayingAttempt.clientSecret, null)
})

test('QA fixture checks the customer role and ownership on all payment endpoints', async () => {
  const credentials = { username: 'payment_api_other_customer', password: 'qaPassword123' }
  await fixtureData('POST', '/user/register', {
    token: '',
    body: { ...credentials, confirmPassword: credentials.password, phone: '13800000991' },
  })
  const { token } = await fixtureData('POST', '/user/login', { token: '', body: credentials })

  for (const [method, path] of [
    ['GET', '/order/701/payment'],
    ['POST', '/order/701/payment'],
    ['POST', '/order/701/payment/sync'],
  ]) {
    const wrongCustomer = await callFixture(method, path, { token })
    assert.equal(wrongCustomer.code, 500)
    assert.equal(wrongCustomer.message, '订单不存在或无权操作')
    assert.equal(wrongCustomer.data, null)

    const wrongRole = await callFixture(method, path, { token: 'qa-merchant-token' })
    assert.equal(wrongRole.code, 401)
    assert.equal(wrongRole.data, null)
  }
})
