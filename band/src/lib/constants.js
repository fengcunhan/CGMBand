// Shared constants for the band app. Plain values only (no runtime state).

export const STORAGE_KEY = 'latestReading'

// Refresh cadence of the "N 分钟前" label.
export const REFRESH_INTERVAL_MS = 30 * 1000
export const MS_PER_MINUTE = 60 * 1000

// Data older than this many minutes is shown greyed out as expired.
export const STALE_MINUTES = 10

// mmol/L -> mg/dL conversion factor (used only for the delta label).
export const MG_DL_PER_MMOL = 18.0182

export const UNIT_MG_DL = 'mg/dL'

// Glucose thresholds in mmol/L.
export const THRESHOLDS = Object.freeze({
  urgentLow: 3.0,
  low: 3.9,
  high: 10.0,
  urgentHigh: 13.9
})

export const COLORS = Object.freeze({
  urgent: '#FF453A',
  warning: '#FF9F0A',
  inRange: '#30D158',
  high: '#FFD60A',
  unknown: '#FFFFFF',
  stale: '#6E6E6E',
  secondary: '#9A9A9A',
  linkOk: '#30D158',
  linkIdle: '#8E8E93',
  linkError: '#FF9F0A'
})

export const STALE_TREND_OPACITY = 0.35

// Value font sizes (px at designWidth 336). Long non-numeric strings such as
// "HIGH" would not fit next to the trend arrow at the large size.
export const VALUE_FONT_LARGE = 110
export const VALUE_FONT_MEDIUM = 64
export const VALUE_SHORT_TEXT_MAX = 2
