// Phone <-> band message protocol (JSON strings over system.interconnect).
// Pure functions only: every call returns a new object, inputs are never mutated.

export const MESSAGE_TYPE = Object.freeze({
  BG: 'bg',
  SYNC: 'sync'
})

export const TRENDS = Object.freeze([
  'DOUBLE_UP',
  'SINGLE_UP',
  'FORTY_FIVE_UP',
  'FLAT',
  'FORTY_FIVE_DOWN',
  'SINGLE_DOWN',
  'DOUBLE_DOWN',
  'NONE'
])

const PLACEHOLDER_VALUE = '--'
const MAX_TEXT_LENGTH = 16
// Longest value string that still fits the 336px screen next to the arrow.
const MAX_VALUE_LENGTH = 6

export function createSyncRequest() {
  return { type: MESSAGE_TYPE.SYNC }
}

function toFiniteNumber(raw) {
  if (typeof raw === 'number') {
    return isFinite(raw) ? raw : null
  }
  if (typeof raw === 'string' && raw.trim() !== '') {
    const parsed = Number(raw)
    return isFinite(parsed) ? parsed : null
  }
  return null
}

function toText(raw, fallback, maxLength) {
  if (typeof raw === 'number' && isFinite(raw)) {
    return String(raw).slice(0, maxLength)
  }
  if (typeof raw !== 'string') {
    return fallback
  }
  const trimmed = raw.trim().slice(0, maxLength)
  return trimmed === '' ? fallback : trimmed
}

function toTrend(raw) {
  return TRENDS.indexOf(raw) >= 0 ? raw : 'NONE'
}

/**
 * Parse the raw interconnect payload (normally a JSON string) into an object.
 * Returns null for anything that is not a JSON object.
 */
export function parseMessage(raw) {
  if (raw && typeof raw === 'object') {
    return raw
  }
  if (typeof raw !== 'string' || raw === '') {
    return null
  }
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch (err) {
    console.error('interconnect: invalid JSON payload', err && err.message)
    return null
  }
}

/**
 * Normalise a reading-like object (a "bg" message or a stored reading).
 * `ts` is kept exactly as received: null means "measurement time unknown".
 * Returns a new plain object, or null if the input is unusable.
 */
export function toReading(src) {
  if (!src || typeof src !== 'object') {
    return null
  }
  return {
    value: toText(src.value, PLACEHOLDER_VALUE, MAX_VALUE_LENGTH),
    unit: toText(src.unit, '', MAX_TEXT_LENGTH),
    mmol: toFiniteNumber(src.mmol),
    time: toText(src.time, '', MAX_TEXT_LENGTH),
    ts: toFiniteNumber(src.ts),
    trend: toTrend(src.trend),
    delta: toFiniteNumber(src.delta),
    connected: src.connected !== false
  }
}

/**
 * Extract a reading from an incoming interconnect payload.
 * Unknown message types and malformed payloads yield null.
 */
export function readingFromMessage(raw) {
  const message = parseMessage(raw)
  if (!message || message.type !== MESSAGE_TYPE.BG) {
    return null
  }
  return toReading(message)
}

/** Decode a reading previously persisted with JSON.stringify(reading). */
export function decodeStoredReading(raw) {
  if (typeof raw !== 'string' || raw === '') {
    return null
  }
  try {
    return toReading(JSON.parse(raw))
  } catch (err) {
    console.error('storage: cached reading is corrupt', err && err.message)
    return null
  }
}
