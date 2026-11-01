import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
let vite
let auth
let riderApi
let http
let previousWindow
let previousFetch

before(async () => {
  previousWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  previousFetch = globalThis.fetch
  globalThis.window = { location: { origin: 'http://takeout.test' } }

  vite = await createServer({
    configFile: fileURLToPath(new URL('../vite.config.js', import.meta.url)),
    root: projectRoot,
    server: { middlewareMode: true, watch: { ignored: ['**/node_modules/**'] } },
    appType: 'custom',
    logLevel: 'silent',
  })
  auth = await vite.ssrLoadModule('/src/auth.js')
  const httpModule = await vite.ssrLoadModule('/src/api/http.js')
  http = httpModule.createRoleHttp('rider')
  riderApi = await vite.ssrLoadModule('/src/api/riderApi.js')
})

after(async () => {
  await vite?.close()
  if (previousWindow) Object.defineProperty(globalThis, 'window', previousWindow)
  else delete globalThis.window
  globalThis.fetch = previousFetch
})

function jsonResponse(code = 200, data = null, message = 'ok') {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code, data, message }),
  }
}

test('bound location clients keep their original token and forward keepalive without an abort signal', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return jsonResponse()
  }
  auth.setRoleSession('rider', { id: 11, token: 'rider-old' })
  const client = riderApi.createRiderLocationClient('rider-old')
  auth.setRoleSession('rider', { id: 22, token: 'rider-new' })

  await client.updateLocation({ longitude: 121.5, latitude: 31.2 })
  await client.removeLocation({ keepalive: true })

  assert.equal(calls[0].options.method, 'PUT')
  assert.equal(calls[0].options.headers.Authorization, 'Bearer rider-old')
  assert.equal('signal' in calls[0].options, false, 'location PUT must not be aborted by a client timeout')
  assert.equal(calls[1].options.method, 'DELETE')
  assert.equal(calls[1].options.headers.Authorization, 'Bearer rider-old')
  assert.equal(calls[1].options.keepalive, true)
  assert.equal('signal' in calls[1].options, false)
  assert.equal(auth.getSession('rider').token, 'rider-new')
})

test('location 403 leaves the current session intact and is reported as a service error', async () => {
  globalThis.fetch = async () => jsonResponse(403, null, 'temporarily forbidden')
  auth.setRoleSession('rider', { id: 31, token: 'rider-current' })
  const client = riderApi.createRiderLocationClient('rider-current')

  await assert.rejects(client.removeLocation(), (error) => {
    assert.equal(error.code, 403)
    assert.equal(error.kind, 'server')
    return true
  })
  assert.equal(auth.getSession('rider').token, 'rider-current')
})

test('location 401 clears only the session that owned the failing client', async () => {
  globalThis.fetch = async () => jsonResponse(401, null, 'expired')
  auth.setRoleSession('rider', { id: 41, token: 'rider-old' })
  const client = riderApi.createRiderLocationClient('rider-old')

  auth.setRoleSession('rider', { id: 42, token: 'rider-new' })
  await assert.rejects(client.removeLocation())
  assert.equal(auth.getSession('rider').token, 'rider-new')

  auth.setRoleSession('rider', { id: 41, token: 'rider-old' })
  await assert.rejects(client.removeLocation(), (error) => error.code === 401)
  assert.equal(auth.getSession('rider'), null)
})

test('current, detail, claim, and complete use their expected routes with finite timeouts', async () => {
  auth.setRoleSession('rider', { id: 51, token: 'rider-api' })
  const calls = []
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return {
      data: { code: 200, data: [] },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }

  await riderApi.getCurrentTasks()
  await riderApi.getRiderTaskDetail(71)
  await riderApi.claimRiderTask(72)
  await riderApi.completeRiderTask(73)

  assert.deepEqual(calls.map((config) => [config.method, config.url]), [
    ['get', '/rider/delivery-tasks/current'],
    ['get', '/rider/delivery-tasks/71'],
    ['patch', '/rider/delivery-tasks/72/claim'],
    ['patch', '/rider/delivery-tasks/73/complete'],
  ])
  for (const config of calls) {
    assert.equal(Number.isFinite(config.timeout), true)
    assert.ok(config.timeout > 0, 'ordinary reads and task mutations should have bounded request timeouts')
  }
})
