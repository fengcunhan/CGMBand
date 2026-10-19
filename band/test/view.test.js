import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  COLORS,
  STALE_TREND_OPACITY,
  VALUE_FONT_LARGE,
  VALUE_FONT_MEDIUM
} from '../src/lib/constants.js'
import { LINK_STATE, initialLinkState, linkState } from '../src/lib/link-state.js'
import { trendIcon } from '../src/lib/trend.js'
import { WARN_TEXT, buildView } from '../src/lib/view.js'

const NOW = 1792398021000
const MINUTE = 60000
const OPEN = linkState(LINK_STATE.OPEN, null)

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
  assert.equal(view.isGreyed, true)
  assert.equal(view.valueColor, COLORS.stale)
  assert.equal(view.trendOpacity, STALE_TREND_OPACITY)
}

test('no data shows the waiting state', () => {
  const view = buildView(null, NOW, initialLinkState())
  assert.equal(view.hasData, false)
  assert.equal(view.warnText, '')
  assert.equal(view.linkText, '正在连接手机…')
})

test('fresh reading is coloured and labelled', () => {
  const view = buildView(reading(), NOW, OPEN)
  assert.equal(view.hasData, true)
  assert.equal(view.valueText, '6.6')
  assert.equal(view.valueColor, COLORS.inRange)
  assert.equal(view.trendIcon, '/common/trend/up45.png')
  assert.equal(view.trendOpacity, 1)
  assert.equal(view.unitText, 'mmol/L')
  assert.equal(view.deltaText, '+0.2')
  assert.equal(view.agoText, '16:33 · 3 分钟前')
  assert.equal(view.warnText, '')
  assert.equal(view.isGreyed, false)
  assert.equal(view.sensorLost, false)
  assert.equal(view.linkText, '手机已连接')
  assert.equal(view.linkColor, COLORS.linkOk)
})

test('reading older than 10 minutes is greyed out (millisecond precision)', () => {
  const tenMin = buildView(reading({ ts: NOW - 10 * MINUTE }), NOW, OPEN)
  assert.equal(tenMin.isGreyed, false)
  assert.equal(tenMin.warnText, '')

  const justOver = buildView(reading({ ts: NOW - 10 * MINUTE - 1 }), NOW, OPEN)
  assertGreyed(justOver)
  assert.equal(justOver.warnText, WARN_TEXT.STALE)
  assert.equal(justOver.agoText, '16:33 · 10 分钟前')
})

test('ts null means time unknown: greyed, "时间未知", no age label', () => {
  const view = buildView(reading({ ts: null }), NOW, OPEN)
  assertGreyed(view)
  assert.equal(view.warnText, WARN_TEXT.TIME_UNKNOWN)
  assert.equal(view.agoText, '16:33')
  assert.equal(buildView(reading({ ts: null, time: '' }), NOW, OPEN).agoText, '')
})

test('sensor disconnected greys the value and shows the hint', () => {
  const view = buildView(reading({ connected: false }), NOW, OPEN)
  assertGreyed(view)
  assert.equal(view.sensorLost, true)
  assert.equal(view.warnText, '')
})

test('unknown mmol and trend', () => {
  const view = buildView(reading({ trend: 'NONE', mmol: null }), NOW, OPEN)
  assert.equal(view.trendIcon, '')
  assert.equal(view.valueColor, COLORS.unknown)
})

test('future ts (clock skew) is treated as just now', () => {
  const view = buildView(reading({ ts: NOW + 2 * MINUTE }), NOW, OPEN)
  assert.equal(view.isGreyed, false)
  assert.equal(view.agoText, '16:33 · 刚刚')
})

test('link state texts', () => {
  assert.equal(buildView(null, NOW, linkState(LINK_STATE.CLOSED, 1006)).linkText, '手机未连接')
  assert.equal(buildView(null, NOW, linkState(LINK_STATE.ERROR, 1001)).linkText, '手机端App未安装')
  assert.equal(buildView(null, NOW, linkState(LINK_STATE.ERROR, 77)).linkText, '手机连接异常 77')
  assert.equal(buildView(null, NOW, linkState(LINK_STATE.ERROR, null)).linkText, '手机连接异常')
  assert.equal(buildView(null, NOW, null).linkText, '正在连接手机…')
})

test('every trend except NONE maps to an image', () => {
  const trends = ['DOUBLE_UP', 'SINGLE_UP', 'FORTY_FIVE_UP', 'FLAT', 'FORTY_FIVE_DOWN', 'SINGLE_DOWN', 'DOUBLE_DOWN']
  trends.forEach((t) => assert.match(trendIcon(t), /^\/common\/trend\/[a-z0-9]+\.png$/))
  assert.equal(trendIcon('NONE'), '')
})

test('value font size shrinks only for long non-numeric text', () => {
  assert.equal(buildView(reading({ value: '13.9' }), NOW, OPEN).valueFontSize, VALUE_FONT_LARGE)
  assert.equal(buildView(reading({ value: '250' }), NOW, OPEN).valueFontSize, VALUE_FONT_LARGE)
  assert.equal(buildView(reading({ value: 'LO' }), NOW, OPEN).valueFontSize, VALUE_FONT_LARGE)
  assert.equal(buildView(reading({ value: '--' }), NOW, OPEN).valueFontSize, VALUE_FONT_LARGE)
  assert.equal(buildView(reading({ value: 'HIGH' }), NOW, OPEN).valueFontSize, VALUE_FONT_MEDIUM)
})

test('buildView does not mutate the reading', () => {
  const src = Object.freeze(reading())
  const view = buildView(src, NOW, OPEN)
  assert.notEqual(view, src)
})
