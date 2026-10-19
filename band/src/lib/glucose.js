// Glucose presentation helpers: colour, delta label, age label. Pure functions.

import {
  COLORS,
  MG_DL_PER_MMOL,
  MS_PER_MINUTE,
  THRESHOLDS,
  UNIT_MG_DL
} from './constants.js'

const MINUTES_PER_HOUR = 60
const MINUTES_PER_DAY = 24 * 60

export function colorForMmol(mmol) {
  if (typeof mmol !== 'number' || !isFinite(mmol)) {
    return COLORS.unknown
  }
  if (mmol < THRESHOLDS.urgentLow) {
    return COLORS.urgent
  }
  if (mmol < THRESHOLDS.low) {
    return COLORS.warning
  }
  if (mmol <= THRESHOLDS.high) {
    return COLORS.inRange
  }
  if (mmol <= THRESHOLDS.urgentHigh) {
    return COLORS.high
  }
  return COLORS.urgent
}

function withSign(rounded, text) {
  if (rounded > 0) {
    return '+' + text
  }
  if (rounded < 0) {
    return text
  }
  return '±' + text
}

/**
 * Delta is always delivered in mmol/L. Shown as "+0.2" for mmol/L,
 * or converted to whole mg/dL (e.g. "+4") when the display unit is mg/dL.
 */
export function formatDelta(delta, unit) {
  if (typeof delta !== 'number' || !isFinite(delta)) {
    return ''
  }
  if (unit === UNIT_MG_DL) {
    const mg = Math.round(delta * MG_DL_PER_MMOL)
    return withSign(mg, String(mg === 0 ? 0 : mg))
  }
  const tenths = Math.round(delta * 10)
  const text = (tenths === 0 ? 0 : tenths / 10).toFixed(1)
  return withSign(tenths, text)
}

/** Whole minutes elapsed since `ts`, clamped at 0; null if `ts` is unknown. */
export function minutesSince(ts, now) {
  if (typeof ts !== 'number' || !isFinite(ts)) {
    return null
  }
  return Math.max(0, Math.floor((now - ts) / MS_PER_MINUTE))
}

export function formatAgo(minutes) {
  if (minutes === null) {
    return ''
  }
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < MINUTES_PER_HOUR) {
    return minutes + ' 分钟前'
  }
  if (minutes < MINUTES_PER_DAY) {
    return Math.floor(minutes / MINUTES_PER_HOUR) + ' 小时前'
  }
  return Math.floor(minutes / MINUTES_PER_DAY) + ' 天前'
}
