// Normalisation of the "history" message and its persisted form. Pure.
//
// Wire format: {"type":"history","ref":<epoch ms|null>,"range":[low,high],
//               "points":[[minutesBeforeRef, mmolTimes10], ...]}  (oldest -> newest)
// Normalised:  { ref, points: [[m, v10], ...], range: [low, high] | null }
//   - ref is null exactly when there are no usable points ("no history yet")
//   - range is null when missing/invalid, meaning "keep the current range"

import {
  HISTORY_MAX_POINTS,
  HISTORY_MAX_VALUE10,
  HISTORY_MIN_VALUE10,
  HISTORY_WINDOW_MINUTES
} from './chart-layout.js'

// Target-range bounds in mmol/L, identical to the phone's TargetRange.of:
// 0 < low < high <= 33.3.
const RANGE_MAX = 33.3

function isNumber(value) {
  return typeof value === 'number' && isFinite(value)
}

/** [low, high] in mmol/L, or null if not a sane, strictly increasing pair. */
export function toRange(raw) {
  if (!Array.isArray(raw) || raw.length !== 2) {
    return null
  }
  const low = raw[0]
  const high = raw[1]
  if (!isNumber(low) || !isNumber(high)) {
    return null
  }
  return low > 0 && high <= RANGE_MAX && low < high ? [low, high] : null
}

function toPoint(raw) {
  if (!Array.isArray(raw) || raw.length < 2 || !isNumber(raw[0]) || !isNumber(raw[1])) {
    return null
  }
  const minutes = Math.round(raw[0])
  const value10 = Math.round(raw[1])
  const inWindow = minutes >= 0 && minutes <= HISTORY_WINDOW_MINUTES
  const plausible = value10 >= HISTORY_MIN_VALUE10 && value10 <= HISTORY_MAX_VALUE10
  return inWindow && plausible ? [minutes, value10] : null
}

/**
 * Valid points sorted oldest -> newest (minutes descending), capped to the
 * newest HISTORY_MAX_POINTS. Malformed entries are dropped individually.
 */
export function toPoints(raw) {
  if (!Array.isArray(raw)) {
    return []
  }
  const valid = raw.map(toPoint).filter((point) => point !== null)
  const sorted = valid.slice().sort((a, b) => b[0] - a[0])
  return sorted.slice(-HISTORY_MAX_POINTS)
}

/** Normalise a history message or stored history object. null if unusable. */
export function toHistory(src) {
  if (!src || typeof src !== 'object') {
    return null
  }
  const ref = isNumber(src.ref) ? src.ref : null
  const points = ref === null ? [] : toPoints(src.points)
  return {
    ref: points.length > 0 ? ref : null,
    points,
    range: toRange(src.range)
  }
}

/** Absolute-time samples: [{ t: epoch ms, v: mmol/L }], oldest -> newest. */
export function toSamples(history, msPerMinute) {
  if (!history || history.ref === null) {
    return []
  }
  return history.points.map((point) => ({
    t: history.ref - point[0] * msPerMinute,
    v: point[1] / 10
  }))
}

export function sameHistory(a, b) {
  if (!a || !b) {
    return false
  }
  return JSON.stringify(a) === JSON.stringify(b)
}
