import { test } from 'node:test'
import assert from 'node:assert/strict'

import { bindLink, unavailableLink } from '../src/lib/link-core.js'
import { LINK_STATE } from '../src/lib/link-state.js'

function fakeConn() {
  return {
    sent: [],
    readyRequests: [],
    send(opts) {
      this.sent.push(opts)
    },
    getReadyState(opts) {
      this.readyRequests.push(opts)
    }
  }
}

function recorder() {
  const log = { messages: [], links: [], opens: 0 }
  const handlers = {
    onMessage: (raw) => log.messages.push(raw),
    onLinkChange: (link) => log.links.push(link),
    onOpen: () => {
      log.opens += 1
    }
  }
  return { log, handlers }
}

function silenceConsoleError(t) {
  t.mock.method(console, 'error', () => {})
}

test('events are translated into handler calls', (t) => {
  silenceConsoleError(t)
  const conn = fakeConn()
  const { log, handlers } = recorder()
  bindLink(conn, handlers)

  conn.onmessage({ data: '{"type":"bg"}' })
  conn.onmessage(null)
  conn.onopen({ isReconnected: false })
  conn.onclose({ code: 1006, data: 'bye' })
  conn.onerror({ code: 1001, data: 'not installed' })

  assert.deepEqual(log.messages, ['{"type":"bg"}', null])
  assert.equal(log.opens, 1)
  assert.deepEqual(log.links, [
    { state: LINK_STATE.OPEN, code: null },
    { state: LINK_STATE.CLOSED, code: 1006 },
    { state: LINK_STATE.ERROR, code: 1001 }
  ])
})

test('requestSync sends the sync request and survives failures', (t) => {
  silenceConsoleError(t)
  const conn = fakeConn()
  const link = bindLink(conn, recorder().handlers)
  link.requestSync()
  assert.deepEqual(conn.sent[0].data, { type: 'sync' })
  conn.sent[0].fail({ code: 204 })

  const throwing = Object.assign(fakeConn(), {
    send() {
      throw new Error('boom')
    }
  })
  assert.doesNotThrow(() => bindLink(throwing, recorder().handlers).requestSync())
})

test('refreshState maps ready state 1/2 and failures', () => {
  const conn = fakeConn()
  const { log, handlers } = recorder()
  const link = bindLink(conn, handlers)

  link.refreshState()
  conn.readyRequests[0].success({ status: 1 })
  link.refreshState()
  conn.readyRequests[1].success({ status: 2 })
  link.refreshState()
  conn.readyRequests[2].fail({ code: 1006 })

  assert.deepEqual(log.links.map((l) => l.state), [
    LINK_STATE.OPEN,
    LINK_STATE.CLOSED,
    LINK_STATE.CLOSED
  ])
  assert.equal(log.links[2].code, 1006)
})

test('outdated getReadyState answers are ignored', () => {
  const conn = fakeConn()
  const { log, handlers } = recorder()
  const link = bindLink(conn, handlers)

  link.refreshState()
  conn.onopen({})
  conn.readyRequests[0].success({ status: 2 })
  conn.readyRequests[0].fail({ code: 1006 })

  assert.deepEqual(log.links.map((l) => l.state), [LINK_STATE.OPEN])
})

test('detach silences callbacks and further calls', () => {
  const conn = fakeConn()
  const { log, handlers } = recorder()
  const link = bindLink(conn, handlers)
  const originalOnMessage = conn.onmessage

  link.refreshState()
  link.detach()
  originalOnMessage({ data: 'late' })
  conn.onmessage({ data: 'late' })
  conn.readyRequests[0].success({ status: 1 })
  link.requestSync()
  link.refreshState()

  assert.deepEqual(log.messages, [])
  assert.deepEqual(log.links, [])
  assert.equal(conn.sent.length, 0)
  assert.equal(conn.readyRequests.length, 1)
})

test('unavailableLink reports an error and returns no-op handles', () => {
  const { log, handlers } = recorder()
  const link = unavailableLink(handlers)
  assert.deepEqual(log.links, [{ state: LINK_STATE.ERROR, code: null }])
  assert.doesNotThrow(() => {
    link.requestSync()
    link.refreshState()
    link.detach()
  })
})
