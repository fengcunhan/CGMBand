// Shared constants for the band app. Plain values only (no runtime state).

export const STORAGE_KEY = 'latestReading'
export const HISTORY_STORAGE_KEY = 'latestHistory'

// Refresh cadence of the relative-time label and the chart x-axis.
export const REFRESH_INTERVAL_MS = 30 * 1000
export const MS_PER_MINUTE = 60 * 1000

// Data older than this many minutes is shown greyed out as expired.
export const STALE_MINUTES = 10

// mmol/L -> mg/dL conversion factor (delta label, range labels).
export const MG_DL_PER_MMOL = 18.0182

export const UNIT_MG_DL = 'mg/dL'

// Target range [low, high] in mmol/L until the phone sends its own.
export const DEFAULT_RANGE = Object.freeze([3.9, 7.8])

// Phone dark-mode palette, dynamic colours only. Static ones live in the
// pages/index CSS: background #000000, card #1A1D23, grid #2A2E35,
// target band #1C2626, secondary text #9CA3AF.
export const COLORS = Object.freeze({
  card: '#1A1D23',
  lineInRange: '#E5E7EB',
  high: '#F7931E',
  low: '#E53935',
  inRange: '#22A06B',
  muted: '#9CA3AF'
})

export const STALE_TREND_OPACITY = 0.35

// Value font sizes (px at designWidth 336), largest that still fits next to
// the arrow/unit column: up to 3 chars ("8.9", "250", "LO") -> LARGE,
// 4-char numbers ("13.9") -> WIDE, anything longer (e.g. "HIGH") -> MEDIUM.
export const VALUE_FONT_LARGE = 120
export const VALUE_FONT_WIDE = 104
export const VALUE_FONT_MEDIUM = 64
export const VALUE_SHORT_TEXT_MAX = 3
export const VALUE_WIDE_NUMBER_MAX = 4
