// Geometry of the history chart (px at designWidth 336). The card itself is
// sized in pages/index (312x208 at 12px inset); everything inside it is
// positioned from these numbers so script and template cannot drift apart.

export const HISTORY_WINDOW_MINUTES = 240
export const HISTORY_GAP_MINUTES = 15
export const HISTORY_MAX_POINTS = 100

// mmol/L * 10 sanity bounds for incoming points (0.1 .. 60.0 mmol/L).
export const HISTORY_MIN_VALUE10 = 1
export const HISTORY_MAX_VALUE10 = 600

export const CHART_LAYOUT = Object.freeze({
  plotLeft: 14,
  plotTop: 16,
  plotWidth: 250,
  plotHeight: 150,
  // Right-edge range labels.
  labelGap: 6,
  labelWidth: 40,
  labelHeight: 22,
  // Time labels under the plot.
  timeGap: 8,
  timeWidth: 80,
  // Line rendering: the polyline is rasterised into rectangles no wider
  // than maxPieceWidth, each `lineWidth` thick around the line.
  lineWidth: 3,
  maxPieceWidth: 4,
  pointSize: 4,
  dotSize: 8,
  // Y axis: 0 .. max(minYMax, ceil(max(points, range high) / yStep) * yStep);
  // grid lines on multiples of yStep, at most gridLines intervals.
  minYMax: 12,
  yStep: 3,
  gridLines: 4
})
