import { test } from 'node:test'
import assert from 'node:assert/strict'

import { chartKey, sameChartKey } from '../src/lib/chart-cache.js'
import { initialPageState, withLink, withLiveHistory, withLiveReading } from '../src/lib/page-state.js'
import { LINK_STATE, linkState } from '../src/lib/link-state.js'

const INTERVAL = 30000
const NOW = 1792398000000

const READING = {
  value: '6.6', unit: 'mmol/L', mmol: 6.6, time: '16:33', ts: NOW, trend: 'FLAT', delta: 0, connected: true
}

test('same inputs within one interval -> no rebuild', () => {
  const state = initialPageState()
  assert.equal(sameChartKey(chartKey(state, NOW, INTERVAL), chartKey(state, NOW + 1000, INTERVAL)), true)
  assert.equal(sameChartKey(null, chartKey(state, NOW, INTERVAL)), false)
})

test('next interval, new history, new range or new unit -> rebuild', () => {
  const base = withLiveReading(initialPageState(), READING).state
  const key = chartKey(base, NOW, INTERVAL)
  assert.equal(sameChartKey(key, chartKey(base, NOW + INTERVAL, INTERVAL)), false)

  const withHistory = withLiveHistory(base, { ref: NOW, points: [[0, 61]], range: null }).state
  assert.equal(sameChartKey(key, chartKey(withHistory, NOW, INTERVAL)), false)

  const newRange = withLiveHistory(withHistory, { ref: NOW, points: [[0, 61]], range: [4, 9] }).state
  assert.equal(sameChartKey(chartKey(withHistory, NOW, INTERVAL), chartKey(newRange, NOW, INTERVAL)), false)

  const mgdl = withLiveReading(base, Object.assign({}, READING, { unit: 'mg/dL' })).state
  assert.equal(sameChartKey(key, chartKey(mgdl, NOW, INTERVAL)), false)
})

test('readings and link changes alone do not rebuild the chart', () => {
  const base = withLiveReading(initialPageState(), READING).state
  const key = chartKey(base, NOW, INTERVAL)
  const nextReading = withLiveReading(base, Object.assign({}, READING, { value: '6.8', mmol: 6.8 })).state
  assert.equal(sameChartKey(key, chartKey(nextReading, NOW, INTERVAL)), true)
  const closed = withLink(base, linkState(LINK_STATE.CLOSED, 1006))
  assert.equal(sameChartKey(key, chartKey(closed, NOW, INTERVAL)), true)
})
