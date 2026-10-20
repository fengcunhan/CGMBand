// View model of the "current glucose" section of pages/index. Pure function
// of (reading, range, now, link).

import {
  COLORS,
  MS_PER_MINUTE,
  STALE_MINUTES,
  STALE_TREND_OPACITY,
  VALUE_FONT_LARGE,
  VALUE_FONT_MEDIUM,
  VALUE_FONT_WIDE,
  VALUE_SHORT_TEXT_MAX,
  VALUE_WIDE_NUMBER_MAX
} from './constants.js'
import { formatAgo, formatDelta, levelColor, levelOfReading, minutesSince } from './glucose.js'
import { LINK_STATE } from './link-state.js'
import { trendIcon } from './trend.js'

export const STATUS_TEXT = Object.freeze({
  STALE: '数据已过期',
  TIME_UNKNOWN: '时间未知',
  SENSOR_LOST: '传感器未连接',
  WAITING: '等待手机数据…'
})

const PLACEHOLDER_VALUE = '--'

const LINK_ERROR_TEXT = Object.freeze({
  1000: '手机连接异常',
  1001: '手机端App未安装',
  1006: '手机连接已断开'
})

/** { text, dotColor, disconnected } for the phone link. */
export function describeLink(link) {
  const state = link ? link.state : LINK_STATE.CONNECTING
  if (state === LINK_STATE.OPEN) {
    return { text: '手机已连接', dotColor: COLORS.inRange, disconnected: false }
  }
  if (state === LINK_STATE.CLOSED) {
    return { text: '手机未连接', dotColor: COLORS.muted, disconnected: true }
  }
  if (state === LINK_STATE.ERROR) {
    const known = LINK_ERROR_TEXT[link.code]
    const text = known || (link.code !== null ? `手机连接异常 ${link.code}` : '手机连接异常')
    return { text, dotColor: COLORS.high, disconnected: true }
  }
  return { text: '正在连接手机…', dotColor: COLORS.muted, disconnected: false }
}

/**
 * Freshness from the phone's `ts` only (never band time): null -> time
 * unknown; older than STALE_MINUTES (millisecond precision) -> stale.
 */
export function freshnessWarning(ts, now) {
  if (ts === null) {
    return STATUS_TEXT.TIME_UNKNOWN
  }
  return now - ts > STALE_MINUTES * MS_PER_MINUTE ? STATUS_TEXT.STALE : ''
}

function joinParts(parts) {
  return parts.filter((part) => part !== '').join(' · ')
}

/**
 * Sub-line under the value: "+0.2 · 3分钟前", or a warning instead, in this
 * priority: phone link down without fresh data -> link text; sensor
 * disconnected (it stays the cause even after the data ages); time unknown
 * (with the CGM's own clock text, if any); expired.
 */
export function subLine(reading, now, link) {
  const phone = describeLink(link)
  if (!reading) {
    return phone.disconnected ? phone.text : STATUS_TEXT.WAITING
  }
  const freshness = freshnessWarning(reading.ts, now)
  if (freshness !== '' && phone.disconnected) {
    return phone.text
  }
  if (!reading.connected) {
    return STATUS_TEXT.SENSOR_LOST
  }
  if (freshness === STATUS_TEXT.TIME_UNKNOWN) {
    return joinParts([reading.time, freshness])
  }
  if (freshness !== '') {
    return freshness
  }
  return joinParts([formatDelta(reading.delta, reading.unit), formatAgo(minutesSince(reading.ts, now))])
}

/** Grey whenever the number may not reflect the current glucose. */
export function isGreyed(reading, range, now) {
  return !reading ||
    freshnessWarning(reading.ts, now) !== '' ||
    !reading.connected ||
    levelOfReading(reading, range) === null
}

export function valueFontSize(text) {
  if (text.length <= VALUE_SHORT_TEXT_MAX) {
    return VALUE_FONT_LARGE
  }
  const isNumeric = /^[0-9.,]+$/.test(text)
  return isNumeric && text.length <= VALUE_WIDE_NUMBER_MAX ? VALUE_FONT_WIDE : VALUE_FONT_MEDIUM
}

export function buildView(reading, range, now, link) {
  const greyed = isGreyed(reading, range, now)
  const valueText = reading ? reading.value : PLACEHOLDER_VALUE
  return {
    hasData: !!reading,
    valueText,
    valueFontSize: valueFontSize(valueText),
    valueColor: greyed ? COLORS.muted : levelColor(levelOfReading(reading, range)),
    trendIcon: reading ? trendIcon(reading.trend) : '',
    trendOpacity: greyed ? STALE_TREND_OPACITY : 1,
    unitText: reading ? reading.unit : '',
    subText: subLine(reading, now, link),
    linkDotColor: describeLink(link).dotColor
  }
}
