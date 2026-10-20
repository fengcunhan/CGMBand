// Glucose presentation helpers: level/colour against the target range, delta
// and age labels. Pure functions.

import { COLORS, MG_DL_PER_MMOL, MS_PER_MINUTE, UNIT_MG_DL } from './constants.js'

const MINUTES_PER_HOUR = 60

export const LEVEL = Object.freeze({
  LOW: 'low',
  IN_RANGE: 'in',
  HIGH: 'high'
})

function isNumber(value) {
  return typeof value === 'number' && isFinite(value)
}

/** 'low' | 'in' | 'high' relative to range [low, high] (inclusive), or null. */
export function levelOf(mmol, range) {
  if (!isNumber(mmol)) {
    return null
  }
  if (mmol < range[0]) {
    return LEVEL.LOW
  }
  return mmol > range[1] ? LEVEL.HIGH : LEVEL.IN_RANGE
}

/**
 * Level of a reading. When `mmol` is null the CGM shows text instead of a
 * number; "LO…"/"HI…" are below/above the sensor's measuring range, so they
 * are treated as low/high exactly like the phone app does. Otherwise null.
 */
export function levelOfReading(reading, range) {
  const level = levelOf(reading.mmol, range)
  if (level !== null) {
    return level
  }
  const text = String(reading.value).trim().toUpperCase()
  if (text.indexOf('LO') === 0) {
    return LEVEL.LOW
  }
  return text.indexOf('HI') === 0 ? LEVEL.HIGH : null
}

/** Value colour of a level: green in range, orange above, red below, else grey. */
export function levelColor(level) {
  if (level === LEVEL.LOW) {
    return COLORS.low
  }
  if (level === LEVEL.HIGH) {
    return COLORS.high
  }
  return level === LEVEL.IN_RANGE ? COLORS.inRange : COLORS.muted
}

/** Colour of the big value for a numeric mmol/L value. */
export function valueColor(mmol, range) {
  return levelColor(levelOf(mmol, range))
}

/** Colour of a chart line piece: light grey in range, orange above, red below. */
export function lineColor(mmol, range) {
  const level = levelOf(mmol, range)
  if (level === LEVEL.LOW) {
    return COLORS.low
  }
  return level === LEVEL.HIGH ? COLORS.high : COLORS.lineInRange
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
  if (!isNumber(delta)) {
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

/** A range bound as label text: "7.8" (mmol/L) or "140" (mg/dL). */
export function formatRangeValue(mmol, unit) {
  return unit === UNIT_MG_DL
    ? String(Math.round(mmol * MG_DL_PER_MMOL))
    : mmol.toFixed(1)
}

/** Whole minutes elapsed since `ts`, clamped at 0; null if `ts` is unknown. */
export function minutesSince(ts, now) {
  if (!isNumber(ts)) {
    return null
  }
  return Math.max(0, Math.floor((now - ts) / MS_PER_MINUTE))
}

/** "刚刚" (< 1 min), "N分钟前", "N小时前"; '' when unknown. */
export function formatAgo(minutes) {
  if (minutes === null) {
    return ''
  }
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < MINUTES_PER_HOUR) {
    return minutes + '分钟前'
  }
  return Math.floor(minutes / MINUTES_PER_HOUR) + '小时前'
}

function pad2(n) {
  return n < 10 ? '0' + n : String(n)
}

/** Local wall-clock "HH:mm" of an epoch-ms timestamp (band time zone). */
export function formatClock(ms) {
  const date = new Date(ms)
  return pad2(date.getHours()) + ':' + pad2(date.getMinutes())
}
