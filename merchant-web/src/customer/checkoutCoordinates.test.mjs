import assert from 'node:assert/strict'
import { test } from 'node:test'
import { deliveryCoordinateError, normalizeDeliveryCoordinates } from './checkoutCoordinates.js'
import { beginCheckoutAttempt, readCheckoutIntent, writeCheckoutIntent } from './checkoutIntent.js'

test('coordinates reject missing and non-decimal input without coercing blanks to zero', () => {
  for (const value of ['', '  ', null, undefined]) assert.match(deliveryCoordinateError(value, 'longitude'), /请填写/)
  for (const value of ['NaN', 'Infinity', '0x10', '120,30', {}, true]) assert.match(deliveryCoordinateError(value, 'latitude'), /有效/)
  assert.throws(() => normalizeDeliveryCoordinates({ longitude: '120.1', latitude: '' }), /纬度/)
})

test('coordinate bounds match the order DTO including zero and poles', () => {
  assert.deepEqual(normalizeDeliveryCoordinates({ longitude: ' -180 ', latitude: '-90' }), { longitude: -180, latitude: -90 })
  assert.deepEqual(normalizeDeliveryCoordinates({ longitude: '180', latitude: '90' }), { longitude: 180, latitude: 90 })
  assert.deepEqual(normalizeDeliveryCoordinates({ longitude: '0', latitude: 0 }), { longitude: 0, latitude: 0 })
  for (const value of ['-180.0001', '180.0001']) assert.match(deliveryCoordinateError(value, 'longitude'), /范围/)
  for (const value of ['-90.0001', '90.0001']) assert.match(deliveryCoordinateError(value, 'latitude'), /范围/)
})

test('coordinates are numeric in the frozen request and survive reload and retry unchanged', () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const values = new Map()
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  } })
  try {
    const form = { longitude: '120.153600', latitude: '30.281200' }
    const intent = writeCheckoutIntent(21, { receiverName: '测试收货人', ...normalizeDeliveryCoordinates(form) })
    beginCheckoutAttempt(21, intent)
    form.longitude = '121.5'
    const restored = readCheckoutIntent(21)
    const retried = beginCheckoutAttempt(21, restored)
    assert.equal(retried.requestId, intent.requestId)
    assert.deepEqual(retried.payload, intent.payload)
    assert.equal(retried.payload.longitude, 120.1536)
    assert.equal(retried.payload.latitude, 30.2812)
  } finally {
    if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor)
    else delete globalThis.localStorage
  }
})
