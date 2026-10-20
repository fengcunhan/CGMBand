// Build the history-chart view model for pages/index. Pure function of
// (history, range, unit, now): x axis = band clock now-4h .. now.

import { CHART_LAYOUT, HISTORY_GAP_MINUTES, HISTORY_WINDOW_MINUTES } from './chart-layout.js'
import { COLORS, MS_PER_MINUTE } from './constants.js'
import { formatClock, formatRangeValue, valueColor } from './glucose.js'
import { makeYScale, mergePieces, rasterizeRuns, splitRuns, yAxisMax } from './chart-geometry.js'
import { toSamples } from './history.js'

const WINDOW_MS = HISTORY_WINDOW_MINUTES * MS_PER_MINUTE
const GAP_MS = HISTORY_GAP_MINUTES * MS_PER_MINUTE
const NOW_LABEL = '现在'

// Placeholder so template bindings never dereference null when hidden. A new
// plain object each time: frozen objects must not enter reactive page data.
function hiddenDot() {
  return { left: 0, top: 0, size: 0, radius: 0, color: COLORS.card }
}

/**
 * Grid values: multiples of yStep, thinned so there are at most
 * `gridLines` intervals (yMax 12 -> 0,3,6,9,12; yMax 18 -> 0,6,12,18).
 */
export function gridValues(yMax, layout) {
  const step = layout.yStep * Math.ceil(yMax / (layout.yStep * layout.gridLines))
  const values = []
  for (let value = 0; value <= yMax; value += step) {
    values.push(value)
  }
  return values
}

function gridLines(y, yMax, layout) {
  return gridValues(yMax, layout).map((value, k) => ({
    id: 'g' + k,
    top: Math.min(Math.round(y(value)), layout.plotHeight - 1)
  }))
}

/** Centres of the two range labels, pushed apart so they never overlap. */
export function rangeLabelCentres(yHigh, yLow, labelHeight) {
  if (yLow - yHigh >= labelHeight) {
    return [yHigh, yLow]
  }
  const middle = (yHigh + yLow) / 2
  return [middle - labelHeight / 2, middle + labelHeight / 2]
}

function rangeLabels(range, unit, y, layout) {
  const centres = rangeLabelCentres(y(range[1]), y(range[0]), layout.labelHeight)
  const left = layout.plotLeft + layout.plotWidth + layout.labelGap
  const make = (value, centre, color) => ({
    text: formatRangeValue(value, unit),
    color,
    left,
    top: Math.round(layout.plotTop + centre - layout.labelHeight / 2)
  })
  return {
    high: make(range[1], centres[0], COLORS.high),
    low: make(range[0], centres[1], COLORS.low)
  }
}

function timeLabels(now, layout) {
  const middleLeft = layout.plotLeft + Math.round((layout.plotWidth - layout.timeWidth) / 2)
  return [
    { id: 't0', text: formatClock(now - WINDOW_MS), left: layout.plotLeft, align: 'left' },
    { id: 't1', text: formatClock(now - WINDOW_MS / 2), left: middleLeft, align: 'center' },
    {
      id: 't2',
      text: NOW_LABEL,
      left: layout.plotLeft + layout.plotWidth - layout.timeWidth,
      align: 'right'
    }
  ]
}

function newestDot(samples, ctx, range) {
  const newest = samples[samples.length - 1]
  const size = ctx.layout.dotSize
  const x = Math.min(Math.max(ctx.x(newest.t), 0), ctx.layout.plotWidth)
  return {
    left: Math.round(x - size / 2),
    top: Math.round(ctx.y(newest.v) - size / 2),
    size,
    radius: size / 2,
    color: valueColor(newest.v, range)
  }
}

export function buildChart(history, range, unit, now, layout) {
  const geometry = layout || CHART_LAYOUT
  const start = now - WINDOW_MS
  const samples = toSamples(history, MS_PER_MINUTE).filter((s) => s.t >= start - GAP_MS)
  const visible = samples.filter((s) => s.t >= start)
  const yMax = yAxisMax(visible.map((s) => s.v), range, geometry)
  const y = makeYScale(yMax, geometry.plotHeight)
  const ctx = {
    x: (t) => (geometry.plotWidth * (t - start)) / WINDOW_MS,
    y,
    range,
    layout: geometry
  }
  const bandTop = Math.round(y(range[1]))
  const empty = visible.length === 0
  return {
    empty,
    yMax,
    plot: {
      left: geometry.plotLeft,
      top: geometry.plotTop,
      width: geometry.plotWidth,
      height: geometry.plotHeight
    },
    labelWidth: geometry.labelWidth,
    labelHeight: geometry.labelHeight,
    timeWidth: geometry.timeWidth,
    band: { top: bandTop, height: Math.max(1, Math.round(y(range[0])) - bandTop) },
    grid: gridLines(y, yMax, geometry),
    pieces: empty
      ? []
      : mergePieces(rasterizeRuns(splitRuns(samples, GAP_MS), ctx)).map((p, i) =>
        Object.assign({ id: 'p' + i }, p)),
    hasDot: !empty,
    dot: empty ? hiddenDot() : newestDot(visible, ctx, range),
    labels: rangeLabels(range, unit, y, geometry),
    times: timeLabels(now, geometry),
    timeTop: geometry.plotTop + geometry.plotHeight + geometry.timeGap
  }
}
