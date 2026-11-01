// Small SSE transport with header authentication. The backend does not support
// URL tokens, durable event IDs or replay; callers reconcile through REST.
export function createSseParser(onEvent) {
  let buffer = '', event = '', data = []
  function line(value) {
    if (value === '') {
      if (data.length) onEvent({ type: event || 'message', data: data.join('\n') })
      event = ''; data = []
      return
    }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const name = colon < 0 ? value : value.slice(0, colon)
    let content = colon < 0 ? '' : value.slice(colon + 1)
    if (content.startsWith(' ')) content = content.slice(1)
    if (name === 'event') event = content
    if (name === 'data') data.push(content)
  }
  return {
    push(chunk) {
      buffer += chunk
      if (buffer.length > 65536) throw new Error('提醒连接返回的数据过长')
      while (true) {
        const index = buffer.search(/[\r\n]/)
        if (index < 0 || (buffer[index] === '\r' && index === buffer.length - 1)) break
        const width = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1
        const value = buffer.slice(0, index)
        buffer = buffer.slice(index + width)
        line(value)
      }
    },
  }
}

export function openMerchantEventStream({
  token, fetchImpl = globalThis.fetch, timers = globalThis, random = Math.random,
  onConnect = () => {}, onEvent = () => {}, onState = () => {}, onAuthError = () => {},
} = {}) {
  let closed = false, controller, retryTimer, deadline, failures = 0
  const clearDeadline = () => { if (deadline !== undefined) timers.clearTimeout(deadline); deadline = undefined }
  const armDeadline = (duration) => {
    clearDeadline()
    deadline = timers.setTimeout(() => controller?.abort(), duration)
  }
  async function connect() {
    if (closed) return
    controller = new AbortController()
    onState('connecting')
    armDeadline(15000)
    let reader
    try {
      const response = await fetchImpl('/merchant/events', {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream, application/json' },
        signal: controller.signal, cache: 'no-store',
      })
      if (closed) return
      const type = response.headers.get('content-type') || ''
      if (response.status === 401 || type.includes('application/json')) {
        const payload = await response.json().catch(() => null)
        if (response.status === 401 || payload?.code === 401) {
          closed = true
          onState('expired')
          onAuthError()
          return
        }
        throw new Error(payload?.message || '实时提醒暂时无法连接')
      }
      if (!response.ok || !type.includes('text/event-stream') || !response.body?.getReader) {
        throw new Error('实时提醒暂时无法连接')
      }
      reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parser = createSseParser(onEvent)
      onState('live')
      onConnect()
      while (!closed) {
        armDeadline(75000)
        const part = await reader.read()
        if (closed) break
        if (part.done) throw new Error('实时提醒连接已断开')
        parser.push(decoder.decode(part.value, { stream: true }))
        failures = 0
      }
    } catch (error) {
      if (!closed) {
        onState('retrying', error.message)
        const delay = Math.min(30000, 1000 * 2 ** Math.min(failures++, 5)) + Math.round(random() * 300)
        retryTimer = timers.setTimeout(connect, delay)
      }
    } finally {
      clearDeadline()
      try { reader?.releaseLock() } catch { /* An aborted read can still be settling. */ }
    }
  }
  void connect()
  return {
    close() {
      closed = true
      if (retryTimer !== undefined) timers.clearTimeout(retryTimer)
      clearDeadline()
      controller?.abort()
    },
  }
}
