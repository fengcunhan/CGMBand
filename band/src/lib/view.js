// Build the immutable view model rendered by pages/index. Pure function of
// (reading, now, link) so the page only has to assign `this.view = buildView(...)`.

import {
  COLORS,
  MS_PER_MINUTE,
  STALE_MINUTES,
  STALE_TREND_OPACITY,
  VALUE_FONT_LARGE,
  VALUE_FONT_MEDIUM,
  VALUE_SHORT_TEXT_MAX
} from './constants.js'
import { colorForMmol, formatAgo, formatDelta, minutesSince } from './glucose.js'
import { LINK_STATE } from './link-state.js'
import { trendIcon } from './trend.js'

export const WARN_TEXT = Object.freeze({
  STALE: '数据已过期',
  TIME_UNKNOWN: '时间未知'
})

const LINK_ERROR_TEXT = Object.freeze({
  1000: '手机连接异常',
  1001: '手机端App未安装',
  1006: '手机连接已断开'
})

function describeLink(link) {
  const state = link ? link.state : LINK_STATE.CONNECTING
  if (state === LINK_STATE.OPEN) {
    return { linkText: '手机已连接', linkColor: COLORS.linkOk }
  }
  if (state === LINK_STATE.CLOSED) {
    return { linkText: '手机未连接', linkColor: COLORS.linkIdle }
  }
  if (state === LINK_STATE.ERROR) {
    const known = LINK_ERROR_TEXT[link.code]
    const text = known || (link.code !== null ? `手机连接异常 ${link.code}` : '手机连接异常')
    return { linkText: text, linkColor: COLORS.linkError }
  }
  return { linkText: '正在连接手机…', linkColor: COLORS.linkIdle }
}

function valueFontSize(text) {
  const isNumeric = /^[0-9.,]+$/.test(text)
  return isNumeric || text.length <= VALUE_SHORT_TEXT_MAX ? VALUE_FONT_LARGE : VALUE_FONT_MEDIUM
}

function joinParts(parts) {
  return parts.filter((part) => part !== '').join(' · ')
}

/**
 * Freshness is derived from the phone's `ts` only (never from band time):
 * ts null -> time unknown; older than STALE_MINUTES (millisecond precision,
 * floored minutes would flip up to a minute late) -> stale.
 */
function freshnessWarning(ts, now) {
  if (ts === null) {
    return WARN_TEXT.TIME_UNKNOWN
  }
  return now - ts > STALE_MINUTES * MS_PER_MINUTE ? WARN_TEXT.STALE : ''
}

function emptyView(linkView) {
  return Object.assign({}, linkView, {
    hasData: false,
    valueText: '',
    valueFontSize: VALUE_FONT_LARGE,
    valueColor: COLORS.unknown,
    trendIcon: '',
    trendOpacity: 1,
    unitText: '',
    deltaText: '',
    agoText: '',
    warnText: '',
    isGreyed: false,
    sensorLost: false
  })
}

export function buildView(reading, now, link) {
  const linkView = describeLink(link)
  if (!reading) {
    return emptyView(linkView)
  }
  const warnText = freshnessWarning(reading.ts, now)
  const sensorLost = !reading.connected
  // Grey out whenever the number may no longer reflect the current glucose.
  const isGreyed = warnText !== '' || sensorLost
  return Object.assign({}, linkView, {
    hasData: true,
    valueText: reading.value,
    valueFontSize: valueFontSize(reading.value),
    valueColor: isGreyed ? COLORS.stale : colorForMmol(reading.mmol),
    trendIcon: trendIcon(reading.trend),
    trendOpacity: isGreyed ? STALE_TREND_OPACITY : 1,
    unitText: reading.unit,
    deltaText: formatDelta(reading.delta, reading.unit),
    agoText: joinParts([reading.time, formatAgo(minutesSince(reading.ts, now))]),
    warnText,
    isGreyed,
    sensorLost
  })
}
