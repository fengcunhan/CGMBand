import { test } from 'node:test'
import assert from 'node:assert/strict'

import { LINK_STATE, linkState } from '../src/lib/link-state.js'
import {
  SOURCE,
  initialPageState,
  sameReading,
  storedHistory,
  withCachedHistory,
  withCachedReading,
  withLink,
  withLiveHistory,
  withLiveReading
} from '../src/lib/page-state.js'

function reading(ts, overrides) {
  return Object.assign(
    { value: '6.6', unit: 'mmol/L', mmol: 6.6, time: '16:33', ts, trend: 'FLAT', delta: 0.1, connected: true },
    overrides
  )
}

test('initial state has no reading/history, default range, connecting', () => {
  const state = initialPageState()
  assert.equal(state.reading, null)
  assert.equal(state.source, null)
  assert.equal(state.link.state, LINK_STATE.CONNECTING)
  assert.equal(state.history, null)
  assert.deepEqual(state.range, [3.9, 7.8])
})

test('cached reading is applied only when nothing is shown yet', () => {
  const cached = reading(100)
  const state = withCachedReading(initialPageState(), cached)
  assert.equal(state.reading, cached)
  assert.equal(state.source, SOURCE.CACHE)

  const live = withLiveReading(initialPageState(), reading(200)).state
  assert.equal(withCachedReading(live, cached), live)
  assert.equal(withCachedReading(state, null), state)
})

test('live reading replaces cache even if older, and is persisted', () => {
  const cachedState = withCachedReading(initialPageState(), reading(500))
  const result = withLiveReading(cachedState, reading(100))
  assert.equal(result.state.reading.ts, 100)
  assert.equal(result.state.source, SOURCE.LIVE)
  assert.equal(result.persist, true)
})

test('older live reading does not replace a newer live one', () => {
  const first = withLiveReading(initialPageState(), reading(200)).state
  const result = withLiveReading(first, reading(100))
  assert.equal(result.state, first)
  assert.equal(result.persist, false)
})

test('same-ts update (e.g. connected flag) is accepted', () => {
  const first = withLiveReading(initialPageState(), reading(200)).state
  const result = withLiveReading(first, reading(200, { connected: false }))
  assert.equal(result.state.reading.connected, false)
  assert.equal(result.persist, true)
})

test('identical re-sent reading is accepted but not persisted again', () => {
  const first = withLiveReading(initialPageState(), reading(200)).state
  const resent = reading(200)
  const result = withLiveReading(first, resent)
  assert.equal(result.persist, false)
  assert.equal(result.state.reading, resent)
})

test('readings without ts (time unknown) are always accepted and persisted', () => {
  const first = withLiveReading(initialPageState(), reading(200)).state
  const result = withLiveReading(first, reading(null))
  assert.equal(result.state.reading.ts, null)
  assert.equal(result.persist, true)
  const after = withLiveReading(result.state, reading(100))
  assert.equal(after.state.reading.ts, 100)
})

test('a valid message forces the link to OPEN', () => {
  const closed = withLink(initialPageState(), linkState(LINK_STATE.CLOSED, 1006))
  const result = withLiveReading(closed, reading(1))
  assert.equal(result.state.link.state, LINK_STATE.OPEN)
  const stillOpen = withLiveReading(result.state, reading(2))
  assert.equal(stillOpen.state.link, result.state.link)
})

test('null reading leaves state untouched', () => {
  const state = initialPageState()
  assert.deepEqual(withLiveReading(state, null), { state, persist: false })
})

test('transitions never mutate the previous state', () => {
  const state = Object.freeze(initialPageState())
  withLink(state, linkState(LINK_STATE.OPEN, null))
  withCachedReading(state, reading(1))
  withLiveReading(state, reading(2))
  assert.equal(state.reading, null)
})

test('sameReading compares displayed fields only', () => {
  assert.equal(sameReading(null, reading(1)), false)
  assert.equal(sameReading(reading(1), reading(1, { extra: 5 })), true)
  assert.equal(sameReading(reading(1), reading(1, { value: '7.0' })), false)
})

function historyPayload(points, range) {
  return { ref: points.length ? 1000 : null, points, range: range === undefined ? [3.9, 7.8] : range }
}

test('cached history applies until live history arrives', () => {
  const cached = withCachedHistory(initialPageState(), historyPayload([[0, 61]], [4.4, 10]))
  assert.deepEqual(cached.history, { ref: 1000, points: [[0, 61]] })
  assert.deepEqual(cached.range, [4.4, 10])
  assert.equal(cached.historySource, SOURCE.CACHE)

  const live = withLiveHistory(cached, historyPayload([[0, 62]])).state
  assert.equal(withCachedHistory(live, historyPayload([[0, 50]])), live)
  assert.equal(withCachedHistory(cached, null), cached)
})

test('live history replaces history and range, persists only on change', () => {
  const first = withLiveHistory(initialPageState(), historyPayload([[3, 62], [0, 61]]))
  assert.equal(first.persist, true)
  assert.deepEqual(first.state.history.points, [[3, 62], [0, 61]])
  assert.equal(first.state.historySource, SOURCE.LIVE)
  assert.equal(first.state.link.state, LINK_STATE.OPEN)

  const again = withLiveHistory(first.state, historyPayload([[3, 62], [0, 61]]))
  assert.equal(again.persist, false)

  // First live history of a session is persisted even if equal to defaults,
  // so a stale cached range in storage gets replaced.
  const cachedCustom = withCachedHistory(initialPageState(), historyPayload([], [5, 9]))
  const liveDefault = withLiveHistory(cachedCustom, historyPayload([], [3.9, 7.8]))
  assert.equal(liveDefault.persist, true)
  assert.deepEqual(withLiveHistory(initialPageState(), historyPayload([])).persist, true)

  const rangeOnly = withLiveHistory(first.state, historyPayload([[3, 62], [0, 61]], [4.4, 10]))
  assert.equal(rangeOnly.persist, true)
  assert.deepEqual(rangeOnly.state.range, [4.4, 10])
})

test('empty history clears the chart but still applies the range', () => {
  const withData = withLiveHistory(initialPageState(), historyPayload([[0, 61]])).state
  const cleared = withLiveHistory(withData, historyPayload([], [5, 9]))
  assert.equal(cleared.persist, true)
  assert.deepEqual(cleared.state.history, { ref: null, points: [] })
  assert.deepEqual(cleared.state.range, [5, 9])
})

test('invalid range (null) keeps the current one', () => {
  const custom = withLiveHistory(initialPageState(), historyPayload([[0, 61]], [4.4, 10])).state
  const next = withLiveHistory(custom, historyPayload([[0, 62]], null)).state
  assert.deepEqual(next.range, [4.4, 10])
  assert.equal(withLiveHistory(custom, null).state, custom)
})

test('storedHistory is the persisted shape', () => {
  assert.deepEqual(storedHistory(initialPageState()), { ref: null, points: [], range: [3.9, 7.8] })
  const state = withLiveHistory(initialPageState(), historyPayload([[0, 61]])).state
  assert.deepEqual(storedHistory(state), { ref: 1000, points: [[0, 61]], range: [3.9, 7.8] })
})

test('history transitions never mutate the previous state', () => {
  const state = Object.freeze(initialPageState())
  withCachedHistory(state, historyPayload([[0, 61]]))
  withLiveHistory(state, historyPayload([[0, 61]], [4, 9]))
  assert.equal(state.history, null)
  assert.deepEqual(state.range, [3.9, 7.8])
})

test('withLink returns the same state when nothing changed', () => {
  const state = withLink(initialPageState(), linkState(LINK_STATE.OPEN, null))
  assert.equal(withLink(state, linkState(LINK_STATE.OPEN, null)), state)
  assert.notEqual(withLink(state, linkState(LINK_STATE.CLOSED, null)), state)
  const closed = withLink(state, linkState(LINK_STATE.CLOSED, 1006))
  assert.notEqual(withLink(closed, linkState(LINK_STATE.CLOSED, null)), closed)
})
