// Pure state transitions for pages/index. Every function returns a new state
// object (or the same one when nothing changes); inputs are never mutated.

import { DEFAULT_RANGE } from './constants.js'
import { sameHistory } from './history.js'
import { LINK_STATE, initialLinkState, linkState } from './link-state.js'

export const SOURCE = Object.freeze({
  CACHE: 'cache',
  LIVE: 'live'
})

const COMPARED_FIELDS = Object.freeze([
  'value', 'unit', 'mmol', 'time', 'ts', 'trend', 'delta', 'connected'
])

export function initialPageState() {
  return {
    reading: null,
    source: null,
    link: initialLinkState(),
    history: null,
    range: DEFAULT_RANGE.slice(),
    historySource: null
  }
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
  return {
    state: Object.assign({}, state, { reading, source: SOURCE.LIVE, link: openLink(state) }),
    persist: !sameReading(state.reading, reading)
  }
}

// A valid message proves the phone link works.
function openLink(state) {
  return state.link.state === LINK_STATE.OPEN ? state.link : linkState(LINK_STATE.OPEN, null)
}

/** The persisted form of history + range: { ref, points, range }. */
export function storedHistory(state) {
  return {
    ref: state.history ? state.history.ref : null,
    points: state.history ? state.history.points : [],
    range: state.range
  }
}

function applyHistory(state, parsed, source) {
  return Object.assign({}, state, {
    history: { ref: parsed.ref, points: parsed.points },
    range: parsed.range || state.range,
    historySource: source
  })
}

/** Apply history restored from storage; never overrides live history. */
export function withCachedHistory(state, cached) {
  if (!cached || state.historySource === SOURCE.LIVE) {
    return state
  }
  return applyHistory(state, cached, SOURCE.CACHE)
}

/**
 * Apply a "history" message (always authoritative: it carries the phone's
 * complete recent history). A missing/invalid range keeps the current one.
 * Returns { state, persist }: persist when history/range changed, and always
 * for the first live history of a session so a stale cached range is replaced.
 */
export function withLiveHistory(state, parsed) {
  if (!parsed) {
    return { state, persist: false }
  }
  const next = Object.assign(applyHistory(state, parsed, SOURCE.LIVE), { link: openLink(state) })
  return {
    state: next,
    persist: state.historySource !== SOURCE.LIVE ||
      !sameHistory(storedHistory(state), storedHistory(next))
  }
}

/** Returns the same state object when the link did not change (no re-render). */
export function withLink(state, link) {
  const same = state.link.state === link.state && state.link.code === link.code
  return same ? state : Object.assign({}, state, { link })
}
