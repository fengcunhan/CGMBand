// Interconnect binding logic, independent of the @system module so it can be
// unit-tested with a fake connection. link.js wires it to the real instance.

import { LINK_STATE, linkState } from './link-state.js'
import { createSyncRequest } from './protocol.js'

const READY_STATE_OPEN = 1

function noop() {}

function errorCode(res, code) {
  if (res && typeof res.code === 'number') {
    return res.code
  }
  return typeof code === 'number' ? code : null
}

/** Handles returned when no connection object is available at all. */
export function unavailableLink(handlers) {
  handlers.onLinkChange(linkState(LINK_STATE.ERROR, null))
  return { requestSync: noop, refreshState: noop, detach: noop }
}

/**
 * Register callbacks on an interconnect connection object.
 * @param {object} conn  connection from interconnect.instance()
 * @param {{onMessage: Function, onLinkChange: Function, onOpen: Function}} handlers
 *   onMessage(rawPayload)       - raw payload (normally a JSON string) from the phone
 *   onLinkChange({state, code}) - link state updates (events + getReadyState)
 *   onOpen()                    - fired only by the real `onopen` event
 * @returns {{requestSync: Function, refreshState: Function, detach: Function}}
 */
export function bindLink(conn, handlers) {
  // Incremented on every connection event so that a getReadyState answer
  // requested before a newer event can be recognised as outdated.
  let eventSeq = 0
  let detached = false

  function onEvent(callback) {
    return (res) => {
      eventSeq += 1
      if (!detached) {
        callback(res)
      }
    }
  }

  conn.onmessage = onEvent((res) => handlers.onMessage(res ? res.data : null))
  conn.onopen = onEvent(() => {
    handlers.onLinkChange(linkState(LINK_STATE.OPEN, null))
    handlers.onOpen()
  })
  conn.onclose = onEvent((res) =>
    handlers.onLinkChange(linkState(LINK_STATE.CLOSED, errorCode(res)))
  )
  conn.onerror = onEvent((res) => {
    const code = errorCode(res)
    console.error(`interconnect error, code = ${code}, msg = ${res && res.data}`)
    handlers.onLinkChange(linkState(LINK_STATE.ERROR, code))
  })

  function requestSync() {
    if (detached) {
      return
    }
    try {
      conn.send({
        data: createSyncRequest(),
        fail: (res, code) =>
          console.error(`interconnect sync send failed, code = ${errorCode(res, code)}`)
      })
    } catch (err) {
      console.error('interconnect send threw', err && err.message)
    }
  }

  function refreshState() {
    if (detached) {
      return
    }
    const seqAtRequest = eventSeq
    const isCurrent = () => !detached && eventSeq === seqAtRequest
    try {
      conn.getReadyState({
        success: (res) => {
          if (!isCurrent()) {
            return
          }
          const isOpen = !!res && res.status === READY_STATE_OPEN
          handlers.onLinkChange(
            linkState(isOpen ? LINK_STATE.OPEN : LINK_STATE.CLOSED, null)
          )
        },
        fail: (res, code) => {
          if (isCurrent()) {
            handlers.onLinkChange(linkState(LINK_STATE.CLOSED, errorCode(res, code)))
          }
        }
      })
    } catch (err) {
      console.error('interconnect getReadyState threw', err && err.message)
    }
  }

  function detach() {
    detached = true
    conn.onmessage = noop
    conn.onopen = noop
    conn.onclose = noop
    conn.onerror = noop
  }

  return { requestSync, refreshState, detach }
}
