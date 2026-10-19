import { test } from 'node:test'
import assert from 'node:assert/strict'

import { COLORS } from '../src/lib/constants.js'
import {
  colorForMmol,
  formatAgo,
  formatDelta,
  minutesSince
} from '../src/lib/glucose.js'

test('colorForMmol follows the agreed bands', () => {
  assert.equal(colorForMmol(2.9), COLORS.urgent)
  assert.equal(colorForMmol(3.0), COLORS.warning)
  assert.equal(colorForMmol(3.8), COLORS.warning)
  assert.equal(colorForMmol(3.9), COLORS.inRange)
  assert.equal(colorForMmol(6.6), COLORS.inRange)
  assert.equal(colorForMmol(10.0), COLORS.inRange)
  assert.equal(colorForMmol(10.1), COLORS.high)
  assert.equal(colorForMmol(13.9), COLORS.high)
  assert.equal(colorForMmol(14.0), COLORS.urgent)
})

test('colorForMmol returns white for unknown values', () => {
  assert.equal(colorForMmol(null), COLORS.unknown)
  assert.equal(colorForMmol(undefined), COLORS.unknown)
  assert.equal(colorForMmol(NaN), COLORS.unknown)
})

test('formatDelta in mmol/L', () => {
  assert.equal(formatDelta(0.2, 'mmol/L'), '+0.2')
  assert.equal(formatDelta(-0.3, 'mmol/L'), '-0.3')
  assert.equal(formatDelta(1, 'mmol/L'), '+1.0')
  assert.equal(formatDelta(0, 'mmol/L'), '±0.0')
  assert.equal(formatDelta(-0.04, 'mmol/L'), '±0.0')
  assert.equal(formatDelta(0.15, ''), '+0.2')
})

test('formatDelta converts to whole mg/dL when unit is mg/dL', () => {
  assert.equal(formatDelta(0.2, 'mg/dL'), '+4')
  assert.equal(formatDelta(-0.5, 'mg/dL'), '-9')
  assert.equal(formatDelta(-0.01, 'mg/dL'), '±0')
})

test('formatDelta returns empty string when unknown', () => {
  assert.equal(formatDelta(null, 'mmol/L'), '')
  assert.equal(formatDelta(undefined, 'mmol/L'), '')
})

test('minutesSince floors and clamps', () => {
  const now = 1000000000000
  assert.equal(minutesSince(now - 59999, now), 0)
  assert.equal(minutesSince(now - 60000, now), 1)
  assert.equal(minutesSince(now - 11 * 60000, now), 11)
  assert.equal(minutesSince(now + 5 * 60000, now), 0)
  assert.equal(minutesSince(null, now), null)
})

test('formatAgo', () => {
  assert.equal(formatAgo(null), '')
  assert.equal(formatAgo(0), '刚刚')
  assert.equal(formatAgo(5), '5 分钟前')
  assert.equal(formatAgo(59), '59 分钟前')
  assert.equal(formatAgo(60), '1 小时前')
  assert.equal(formatAgo(60 * 24 * 2), '2 天前')
})
