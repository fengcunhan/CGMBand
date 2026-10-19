import { test } from 'node:test'
import assert from 'node:assert/strict'

import { LINK_STATE, linkState } from '../src/lib/link-state.js'
import {
  SOURCE,
  initialPageState,
  sameReading,
  withCachedReading,
  withLink,
  withLiveReading
} from '../src/lib/page-state.js'

function reading(ts, overrides) {
  return Object.assign(
    { value: '6.6', unit: 'mmol/L', mmol: 6.6, time: '16:33', ts, trend: 'FLAT', delta: 0.1, connected: true },
    overrides
  )
}

test('initial state has no reading and is connecting', () => {
  const state = initialPageState()
  assert.equal(state.reading, null)
  assert.equal(state.source, null)
  assert.equal(state.link.state, LINK_STATE.CONNECTING)
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
