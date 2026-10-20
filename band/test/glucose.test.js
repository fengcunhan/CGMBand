import { test } from 'node:test'
import assert from 'node:assert/strict'

import { COLORS } from '../src/lib/constants.js'
import {
  LEVEL,
  formatAgo,
  formatClock,
  formatDelta,
  formatRangeValue,
  levelColor,
  levelOf,
  levelOfReading,
  lineColor,
  minutesSince,
  valueColor
} from '../src/lib/glucose.js'

const RANGE = [3.9, 7.8]

test('levelOf is inclusive of the range bounds', () => {
  assert.equal(levelOf(3.8, RANGE), LEVEL.LOW)
  assert.equal(levelOf(3.9, RANGE), LEVEL.IN_RANGE)
  assert.equal(levelOf(7.8, RANGE), LEVEL.IN_RANGE)
  assert.equal(levelOf(7.9, RANGE), LEVEL.HIGH)
  assert.equal(levelOf(null, RANGE), null)
  assert.equal(levelOf(NaN, RANGE), null)
})

test('levelOf follows a custom range', () => {
  const custom = [4.4, 10.0]
  assert.equal(levelOf(4.0, custom), LEVEL.LOW)
  assert.equal(levelOf(9.5, custom), LEVEL.IN_RANGE)
  assert.equal(levelOf(10.1, custom), LEVEL.HIGH)
})

test('valueColor uses the phone palette', () => {
  assert.equal(valueColor(6.6, RANGE), '#22A06B')
  assert.equal(valueColor(9.0, RANGE), '#F7931E')
  assert.equal(valueColor(3.0, RANGE), '#E53935')
  assert.equal(valueColor(null, RANGE), COLORS.muted)
})

test('lineColor: light grey in range, orange above, red below', () => {
  assert.equal(lineColor(6.6, RANGE), '#E5E7EB')
  assert.equal(lineColor(9.0, RANGE), '#F7931E')
  assert.equal(lineColor(3.0, RANGE), '#E53935')
})

test('formatDelta in mmol/L', () => {
  assert.equal(formatDelta(0.2, 'mmol/L'), '+0.2')
  assert.equal(formatDelta(-0.3, 'mmol/L'), '-0.3')
  assert.equal(formatDelta(1, 'mmol/L'), '+1.0')
  assert.equal(formatDelta(0, 'mmol/L'), '±0.0')
  assert.equal(formatDelta(-0.04, 'mmol/L'), '±0.0')
})

test('formatDelta converts to whole mg/dL when unit is mg/dL', () => {
  assert.equal(formatDelta(0.2, 'mg/dL'), '+4')
  assert.equal(formatDelta(-0.5, 'mg/dL'), '-9')
  assert.equal(formatDelta(-0.01, 'mg/dL'), '±0')
  assert.equal(formatDelta(null, 'mmol/L'), '')
})

test('formatRangeValue: one decimal in mmol/L, whole numbers in mg/dL', () => {
  assert.equal(formatRangeValue(7.8, 'mmol/L'), '7.8')
  assert.equal(formatRangeValue(10, 'mmol/L'), '10.0')
  assert.equal(formatRangeValue(3.9, ''), '3.9')
  assert.equal(formatRangeValue(7.8, 'mg/dL'), '141')
  assert.equal(formatRangeValue(3.9, 'mg/dL'), '70')
})

test('minutesSince floors and clamps', () => {
  const now = 1000000000000
  assert.equal(minutesSince(now - 59999, now), 0)
  assert.equal(minutesSince(now - 60000, now), 1)
  assert.equal(minutesSince(now + 5 * 60000, now), 0)
  assert.equal(minutesSince(null, now), null)
})

test('formatAgo: 刚刚 / N分钟前 / N小时前', () => {
  assert.equal(formatAgo(null), '')
  assert.equal(formatAgo(0), '刚刚')
  assert.equal(formatAgo(3), '3分钟前')
  assert.equal(formatAgo(59), '59分钟前')
  assert.equal(formatAgo(60), '1小时前')
  assert.equal(formatAgo(60 * 30), '30小时前')
})

test('formatClock gives zero-padded local HH:mm', () => {
  assert.equal(formatClock(new Date(2026, 9, 20, 7, 5).getTime()), '07:05')
  assert.equal(formatClock(new Date(2026, 9, 20, 23, 59).getTime()), '23:59')
})

test('levelOfReading falls back to LO/HI text when mmol is null', () => {
  assert.equal(levelOfReading({ value: '6.6', mmol: 6.6 }, RANGE), LEVEL.IN_RANGE)
  assert.equal(levelOfReading({ value: 'LO', mmol: null }, RANGE), LEVEL.LOW)
  assert.equal(levelOfReading({ value: 'Low', mmol: null }, RANGE), LEVEL.LOW)
  assert.equal(levelOfReading({ value: 'HI', mmol: null }, RANGE), LEVEL.HIGH)
  assert.equal(levelOfReading({ value: '--', mmol: null }, RANGE), null)
  assert.equal(levelColor(null), COLORS.muted)
})
