import assert from 'node:assert/strict'
import {afterEach, beforeEach, test} from 'node:test'
import {
    archiveCheckoutIntent,
    beginCheckoutAttempt,
    createCheckoutSubmitLock,
    isDefinitelyRejectedCreate,
    readArchivedCheckoutIntent,
    readCheckoutIntent,
    recordCreatedOrder,
    restoreArchivedCheckoutIntent,
    settleKnownCheckoutRejection,
    writeCheckoutIntent,
} from './checkoutIntent.js'

let storageDescriptor
let values

beforeEach(() => {
  storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  values = new Map()
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key) => values.has(key) ? values.get(key) : null,
      setItem: (key, value) => values.set(key, String(value)),
      removeItem: (key) => values.delete(key),
    },
  })
})

afterEach(() => {
  if (storageDescriptor) Object.defineProperty(globalThis, 'localStorage', storageDescriptor)
  else delete globalThis.localStorage
})

test('checkout creates one stable, per-user request intent', () => {
  const first = writeCheckoutIntent(21, { receiverName: '小林', remark: null }, 42)
  assert.equal(first.payload.requestId, first.requestId)
  assert.equal(readCheckoutIntent(21).requestId, first.requestId)
  assert.equal(readCheckoutIntent(22), null)
  assert.throws(() => writeCheckoutIntent(21, { receiverName: '小林' }), /尚未确认/)
})

test('an acknowledged order id is persisted before checkout can retry', () => {
  const intent = writeCheckoutIntent(21, { receiverName: '小林' })
  const inFlight = beginCheckoutAttempt(21, intent)
  const saved = recordCreatedOrder(21, inFlight, 884)
  assert.equal(saved.orderId, '884')
  assert.equal(readCheckoutIntent(21).orderId, '884')
})

test('an explicitly reviewed pending intent can be archived and restored with the same key', () => {
  const intent = writeCheckoutIntent(21, { receiverName: '小林', receiverAddress: '街道 1 号' })
  archiveCheckoutIntent(21, intent, 'customer-reviewed')
  assert.equal(readCheckoutIntent(21), null)
  assert.equal(readArchivedCheckoutIntent(21).intent.requestId, intent.requestId)
  const restored = restoreArchivedCheckoutIntent(21)
  assert.equal(restored.requestId, intent.requestId)
  assert.deepEqual(restored.payload, intent.payload)
})

test('only known pre-creation rejections are eligible for a new request id', () => {
  assert.equal(isDefinitelyRejectedCreate({ code: 1001, message: '操作过于频繁' }), false)
  assert.equal(isDefinitelyRejectedCreate({ code: 2001, message: '购物车为空' }), true)
  assert.equal(isDefinitelyRejectedCreate({ code: 2001, message: '购物车为空' }, { uncertain: true }), false)
  assert.equal(isDefinitelyRejectedCreate({ code: 400, message: '手机号格式不正确' }), true)
  assert.equal(isDefinitelyRejectedCreate({ code: 400, message: '同一 requestId 不能携带不同下单参数' }), false)
  assert.equal(isDefinitelyRejectedCreate({ code: 500, message: '创建订单失败' }), true)
  assert.equal(isDefinitelyRejectedCreate({ code: 500, message: '创建订单失败' }, { uncertain: true }), false)
  assert.equal(isDefinitelyRejectedCreate({ code: 5000, message: '服务异常' }), false)
  assert.equal(isDefinitelyRejectedCreate({ kind: 'network', message: '请求超时' }), false)
})

test('an interrupted in-flight request becomes uncertain after reload', () => {
  const intent = writeCheckoutIntent(21, { receiverName: '小林' })
  beginCheckoutAttempt(21, intent)
  const recovered = readCheckoutIntent(21)
  assert.equal(recovered.uncertain, true)
  assert.equal(recovered.inFlight, false)
  assert.equal(isDefinitelyRejectedCreate({ code: 2001 }, recovered), false)
})

test('a first-attempt definitive rejection settles in-flight state without poisoning future retries', () => {
  const intent = writeCheckoutIntent(21, { receiverName: '小林' })
  const inFlight = beginCheckoutAttempt(21, intent)
  const settled = settleKnownCheckoutRejection(21, inFlight)
  assert.equal(settled.inFlight, false)
  assert.equal(settled.uncertain, false)
  assert.equal(isDefinitelyRejectedCreate({ code: 2001 }, settled), true)
})

test('the submit lock closes the double-click gap while async validation is pending', () => {
  const lock = createCheckoutSubmitLock()
  const first = lock.acquire()
  assert.ok(first)
  assert.equal(lock.acquire(), null)
  lock.release(first)
  assert.ok(lock.acquire())
})
