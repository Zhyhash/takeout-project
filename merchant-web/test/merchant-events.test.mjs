import assert from 'node:assert/strict'
import {test} from 'node:test'
import {createSseParser, openMerchantEventStream} from '../src/merchant/notifications/stream.js'

const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
function fakeTimers() {
  let id = 0
  const pending = new Map()
  return {
    pending,
    setTimeout(callback, delay) { const key = ++id; pending.set(key, { callback, delay }); return key },
    clearTimeout(key) { pending.delete(key) },
  }
}
const response = (body, type = 'text/event-stream', status = 200) => ({ ok: status === 200, status, body, headers: new Headers({ 'content-type': type }) })

test('SSE handles split CRLF, heartbeat comments and multiline event data', () => {
  const events = []
  const parser = createSseParser((value) => events.push(value))
  parser.push(': heartbeat\r')
  parser.push('\n\r\nevent: NEW_')
  parser.push('ORDER\r\ndata: refresh\r\ndata: again\r\n\r')
  assert.deepEqual(events, [])
  parser.push('\nevent: CONNECTED\ndata: connected\n\n')
  assert.deepEqual(events, [{ type: 'NEW_ORDER', data: 'refresh\nagain' }, { type: 'CONNECTED', data: 'connected' }])
})

test('SSE ignores event IDs and never treats an incomplete event as delivered', () => {
  const events = []
  const parser = createSseParser((value) => events.push(value))
  parser.push('id: 50\nevent: NEW_ORDER\ndata: refresh\n')
  assert.equal(events.length, 0)
  parser.push('\n')
  assert.deepEqual(events, [{ type: 'NEW_ORDER', data: 'refresh' }])
})

test('transport sends Authorization headers and accepts UTF-8 split across reads', async () => {
  const timers = fakeTimers(), events = []
  let controller, options
  const body = new ReadableStream({ start(value) { controller = value } })
  const connection = openMerchantEventStream({
    token: 'test-only-token', timers,
    fetchImpl: async (url, config) => { assert.equal(url, '/merchant/events'); options = config; return response(body) },
    onEvent: (event) => events.push(event),
  })
  await settle()
  assert.equal(options.headers.Authorization, 'Bearer test-only-token')
  assert.equal(options.headers.Accept, 'text/event-stream, application/json')
  const bytes = new TextEncoder().encode('event: NEW_ORDER\ndata: 新订单\n\n')
  controller.enqueue(bytes.slice(0, 26)); controller.enqueue(bytes.slice(26))
  await settle()
  assert.deepEqual(events, [{ type: 'NEW_ORDER', data: '新订单' }])
  assert.ok([...timers.pending.values()].some((task) => task.delay === 75000))
  connection.close(); controller.close(); await settle()
  assert.equal(timers.pending.size, 0)
  assert.equal(options.signal.aborted, true)
})

test('HTTP 200 JSON business 401 ends the stream without a reconnect loop', async () => {
  const timers = fakeTimers()
  let authErrors = 0
  openMerchantEventStream({
    token: 'test-only-token', timers,
    fetchImpl: async () => ({ ...response(null, 'application/json'), json: async () => ({ code: 401 }) }),
    onAuthError: () => { authErrors++ },
  })
  await settle()
  assert.equal(authErrors, 1)
  assert.equal(timers.pending.size, 0)
})

test('JSON 403 retries with backoff and close cancels pending reconnect', async () => {
  const timers = fakeTimers(), states = []
  let authErrors = 0
  const connection = openMerchantEventStream({
    token: 'test-only-token', timers, random: () => 0,
    fetchImpl: async () => ({ ...response(null, 'application/json'), json: async () => ({ code: 403, message: '稍后重试' }) }),
    onAuthError: () => { authErrors++ }, onState: (state) => states.push(state),
  })
  await settle()
  assert.equal(authErrors, 0)
  assert.ok(states.includes('retrying'))
  assert.ok([...timers.pending.values()].some((task) => task.delay === 1000))
  connection.close()
  assert.equal(timers.pending.size, 0)
})

test('closing a stream discards late events', async () => {
  const timers = fakeTimers(), events = []
  let controller
  const body = new ReadableStream({ start(value) { controller = value } })
  const connection = openMerchantEventStream({ token: 'test-only-token', timers, fetchImpl: async () => response(body), onEvent: (event) => events.push(event) })
  await settle()
  connection.close()
  controller.enqueue(new TextEncoder().encode('event: NEW_ORDER\ndata: refresh\n\n'))
  controller.close()
  await settle()
  assert.deepEqual(events, [])
  assert.equal(timers.pending.size, 0)
})
