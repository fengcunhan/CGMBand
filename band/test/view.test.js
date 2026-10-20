import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  COLORS,
  STALE_TREND_OPACITY,
  VALUE_FONT_LARGE,
  VALUE_FONT_MEDIUM,
  VALUE_FONT_WIDE
} from '../src/lib/constants.js'
import { LINK_STATE, initialLinkState, linkState } from '../src/lib/link-state.js'
import { trendIcon } from '../src/lib/trend.js'
import { STATUS_TEXT, buildView, describeLink, subLine } from '../src/lib/view.js'

const NOW = 1792398021000
const MINUTE = 60000
const RANGE = [3.9, 7.8]
const OPEN = linkState(LINK_STATE.OPEN, null)
const CLOSED = linkState(LINK_STATE.CLOSED, 1006)
const CONNECTING = initialLinkState()

function reading(overrides) {
  return Object.assign(
    {
      value: '6.6',
      unit: 'mmol/L',
      mmol: 6.6,
      time: '16:33',
      ts: NOW - 3 * MINUTE,
      trend: 'FORTY_FIVE_UP',
      delta: 0.2,
      connected: true
    },
    overrides
  )
}

function assertGreyed(view) {
  assert.equal(view.valueColor, '#9CA3AF')
  assert.equal(view.trendOpacity, STALE_TREND_OPACITY)
}

test('fresh in-range reading: green value, arrow, unit, delta · age', () => {
  const view = buildView(reading(), RANGE, NOW, OPEN)
  assert.equal(view.hasData, true)
  assert.equal(view.valueText, '6.6')
  assert.equal(view.valueColor, '#22A06B')
  assert.equal(view.trendIcon, '/common/trend/up45.png')
  assert.equal(view.trendOpacity, 1)
  assert.equal(view.unitText, 'mmol/L')
  assert.equal(view.subText, '+0.2 · 3分钟前')
  assert.equal(view.linkDotColor, COLORS.inRange)
})

test('value colour follows the target range', () => {
  assert.equal(buildView(reading({ mmol: 8.4 }), RANGE, NOW, OPEN).valueColor, '#F7931E')
  assert.equal(buildView(reading({ mmol: 3.5 }), RANGE, NOW, OPEN).valueColor, '#E53935')
  assert.equal(buildView(reading({ mmol: 8.4 }), [4.4, 10.0], NOW, OPEN).valueColor, '#22A06B')
})

test('LO / HI (mmol null) take their level from the text, like the phone', () => {
  const lo = buildView(reading({ value: 'LO', mmol: null }), RANGE, NOW, OPEN)
  assert.equal(lo.valueText, 'LO')
  assert.equal(lo.valueColor, '#E53935')
  assert.equal(lo.trendOpacity, 1)
  assert.equal(buildView(reading({ value: 'HI', mmol: null }), RANGE, NOW, OPEN).valueColor, '#F7931E')
  assert.equal(buildView(reading({ value: ' low', mmol: null }), RANGE, NOW, OPEN).valueColor, '#E53935')
})

test('other unknown values (mmol null) are grey', () => {
  assertGreyed(buildView(reading({ value: '--', mmol: null }), RANGE, NOW, OPEN))
  assertGreyed(buildView(reading({ value: 'ERR', mmol: null }), RANGE, NOW, OPEN))
})

test('LO that is stale is still grey', () => {
  assertGreyed(buildView(reading({ value: 'LO', mmol: null, ts: NOW - 30 * MINUTE }), RANGE, NOW, OPEN))
})

test('older than 10 minutes: grey + 数据已过期 (millisecond precision)', () => {
  const edge = buildView(reading({ ts: NOW - 10 * MINUTE }), RANGE, NOW, OPEN)
  assert.equal(edge.valueColor, '#22A06B')
  const stale = buildView(reading({ ts: NOW - 10 * MINUTE - 1 }), RANGE, NOW, OPEN)
  assertGreyed(stale)
  assert.equal(stale.subText, STATUS_TEXT.STALE)
})

test('ts null: grey + 时间未知 with the CGM clock text when present', () => {
  const view = buildView(reading({ ts: null }), RANGE, NOW, OPEN)
  assertGreyed(view)
  assert.equal(view.subText, '16:33 · 时间未知')
  assert.equal(subLine(reading({ ts: null, time: '' }), NOW, OPEN), '时间未知')
})

test('sensor disconnected: grey + 传感器未连接, even after the data ages', () => {
  const view = buildView(reading({ connected: false }), RANGE, NOW, OPEN)
  assertGreyed(view)
  assert.equal(view.subText, '传感器未连接')
  assert.equal(subLine(reading({ connected: false, ts: NOW - 30 * MINUTE }), NOW, OPEN), '传感器未连接')
  assert.equal(subLine(reading({ connected: false, ts: null }), NOW, OPEN), '传感器未连接')
  // Phone link down + old data: the link is the more fundamental cause.
  assert.equal(subLine(reading({ connected: false, ts: NOW - 30 * MINUTE }), NOW, CLOSED), '手机未连接')
})

test('no data: placeholder value and 等待手机数据…', () => {
  const view = buildView(null, RANGE, NOW, CONNECTING)
  assert.equal(view.hasData, false)
  assert.equal(view.valueText, '--')
  assert.equal(view.valueColor, '#9CA3AF')
  assert.equal(view.trendIcon, '')
  assert.equal(view.unitText, '')
  assert.equal(view.subText, '等待手机数据…')
})

test('phone link down replaces the sub-line only when data is not fresh', () => {
  assert.equal(subLine(null, NOW, CLOSED), '手机未连接')
  assert.equal(subLine(reading({ ts: NOW - 30 * MINUTE }), NOW, CLOSED), '手机未连接')
  assert.equal(subLine(reading({ ts: null }), NOW, CLOSED), '手机未连接')
  assert.equal(subLine(reading(), NOW, CLOSED), '+0.2 · 3分钟前')
  assert.equal(subLine(null, NOW, linkState(LINK_STATE.ERROR, 1001)), '手机端App未安装')
  assert.equal(subLine(reading({ ts: null }), NOW, CONNECTING), '16:33 · 时间未知')
})

test('sub-line without delta or just now', () => {
  assert.equal(subLine(reading({ delta: null }), NOW, OPEN), '3分钟前')
  assert.equal(subLine(reading({ ts: NOW - 20000 }), NOW, OPEN), '+0.2 · 刚刚')
  assert.equal(subLine(reading({ ts: NOW - 125 * MINUTE }), NOW, OPEN), '数据已过期')
})

test('describeLink texts and dot colours', () => {
  assert.deepEqual(describeLink(OPEN), { text: '手机已连接', dotColor: '#22A06B', disconnected: false })
  assert.equal(describeLink(CLOSED).text, '手机未连接')
  assert.equal(describeLink(CLOSED).dotColor, '#9CA3AF')
  assert.equal(describeLink(linkState(LINK_STATE.ERROR, 77)).text, '手机连接异常 77')
  assert.equal(describeLink(linkState(LINK_STATE.ERROR, null)).text, '手机连接异常')
  assert.equal(describeLink(linkState(LINK_STATE.ERROR, 1001)).dotColor, '#F7931E')
  assert.equal(describeLink(null).text, '正在连接手机…')
  assert.equal(describeLink(CONNECTING).disconnected, false)
})

test('every trend except NONE maps to an image', () => {
  const trends = ['DOUBLE_UP', 'SINGLE_UP', 'FORTY_FIVE_UP', 'FLAT', 'FORTY_FIVE_DOWN', 'SINGLE_DOWN', 'DOUBLE_DOWN']
  trends.forEach((t) => assert.match(trendIcon(t), /^\/common\/trend\/[a-z0-9]+\.png$/))
  assert.equal(trendIcon('NONE'), '')
})

test('value font size: as large as fits next to the arrow', () => {
  const size = (value) => buildView(reading({ value }), RANGE, NOW, OPEN).valueFontSize
  assert.equal(size('8.9'), VALUE_FONT_LARGE)
  assert.equal(size('250'), VALUE_FONT_LARGE)
  assert.equal(size('LO'), VALUE_FONT_LARGE)
  assert.equal(size('13.9'), VALUE_FONT_WIDE)
  assert.equal(size('HIGH'), VALUE_FONT_MEDIUM)
  assert.equal(size('SENSOR'), VALUE_FONT_MEDIUM)
  assert.equal(buildView(null, RANGE, NOW, OPEN).valueFontSize, VALUE_FONT_LARGE)
})

test('buildView does not mutate its inputs', () => {
  const src = Object.freeze(reading())
  const range = Object.freeze(RANGE.slice())
  assert.doesNotThrow(() => buildView(src, range, NOW, Object.freeze(OPEN)))
})
