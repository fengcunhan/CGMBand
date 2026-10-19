// Pure state transitions for pages/index. Every function returns a new state
// object (or the same one when nothing changes); inputs are never mutated.

import { LINK_STATE, initialLinkState, linkState } from './link-state.js'

export const SOURCE = Object.freeze({
  CACHE: 'cache',
  LIVE: 'live'
})

const COMPARED_FIELDS = Object.freeze([
  'value', 'unit', 'mmol', 'time', 'ts', 'trend', 'delta', 'connected'
])

export function initialPageState() {
  return { reading: null, source: null, link: initialLinkState() }
}

export function sameReading(a, b) {
  if (!a || !b) {
    return false
  }
  return COMPARED_FIELDS.every((field) => a[field] === b[field])
}

function isOlder(next, current) {
  return !!current && current.ts !== null && next.ts !== null && next.ts < current.ts
}

/** Apply the reading restored from storage; never overrides a live one. */
export function withCachedReading(state, cached) {
  if (!cached || state.reading) {
    return state
  }
  return Object.assign({}, state, { reading: cached, source: SOURCE.CACHE })
}

/**
 * Apply a reading received from the phone.
 * Returns { state, persist }: `persist` is true when the reading should be
 * written to storage (i.e. it was accepted and differs from the shown one).
 * Within a session an older reading (by ts) never replaces a newer live one;
 * a cached reading is always replaced. A valid message also proves the link
 * is up, so the link state is forced to OPEN.
 */
export function withLiveReading(state, reading) {
  if (!reading) {
    return { state, persist: false }
  }
  if (state.source === SOURCE.LIVE && isOlder(reading, state.reading)) {
    return { state, persist: false }
  }
  const link = state.link.state === LINK_STATE.OPEN
    ? state.link
    : linkState(LINK_STATE.OPEN, null)
  return {
    state: Object.assign({}, state, { reading, source: SOURCE.LIVE, link }),
    persist: !sameReading(state.reading, reading)
  }
}

export function withLink(state, link) {
  return Object.assign({}, state, { link })
}
