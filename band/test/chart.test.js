import { test } from 'node:test'
import assert from 'node:assert/strict'

import { CHART_LAYOUT } from '../src/lib/chart-layout.js'
import {
  makePiece,
  makeYScale,
  mergePieces,
  rasterizeRuns,
  splitRuns,
  yAxisMax
} from '../src/lib/chart-geometry.js'
import { buildChart, gridValues, rangeLabelCentres } from '../src/lib/chart.js'
import { formatClock } from '../src/lib/glucose.js'

const MINUTE = 60000
const NOW = new Date(2026, 9, 20, 16, 30).getTime()
const RANGE = [3.9, 7.8]
const L = CHART_LAYOUT

function history(points, ref) {
  return { ref: ref === undefined ? NOW : ref, points }
}

// Every 3 minutes over the whole 4 h window at a constant value.
function steady(value10) {
  return Array.from({ length: 81 }, (_, i) => [240 - i * 3, value10])
}

function ctx(range) {
  return {
    x: (t) => t,
    y: makeYScale(12, L.plotHeight),
    range: range || RANGE,
    layout: L
  }
}

test('yAxisMax: at least 12, otherwise next multiple of 3 (incl. range high)', () => {
  assert.equal(yAxisMax([5, 6.6], RANGE, L), 12)
  assert.equal(yAxisMax([12], RANGE, L), 12)
  assert.equal(yAxisMax([12.1], RANGE, L), 15)
  assert.equal(yAxisMax([16.2], RANGE, L), 18)
  assert.equal(yAxisMax([], RANGE, L), 12)
  assert.equal(yAxisMax([5], [4, 13.9], L), 15)
})

test('makeYScale maps 0 to the bottom and yMax to the top, clamping outliers', () => {
  const y = makeYScale(12, 150)
  assert.equal(y(0), 150)
  assert.equal(y(12), 0)
  assert.equal(y(6), 75)
  assert.equal(y(20), 0)
  assert.equal(y(-1), 150)
})

test('splitRuns breaks the line on gaps longer than 15 minutes', () => {
  const s = (minute) => ({ t: minute * MINUTE, v: 6 })
  const runs = splitRuns([s(0), s(3), s(18), s(34), s(37)], 15 * MINUTE)
  assert.deepEqual(runs.map((run) => run.map((p) => p.t / MINUTE)), [[0, 3, 18], [34, 37]])
  assert.deepEqual(splitRuns([], 15 * MINUTE), [])
})

test('pieces are coloured by the level of their midpoint', () => {
  assert.equal(makePiece(0, 6, 4, 6, ctx()).c, '#E5E7EB')
  assert.equal(makePiece(0, 9, 4, 9, ctx()).c, '#F7931E')
  assert.equal(makePiece(0, 3, 4, 3, ctx()).c, '#E53935')
  // Crossing 7.8: midpoint 7.75 is still in range.
  assert.equal(makePiece(0, 7.6, 4, 7.9, ctx()).c, '#E5E7EB')
  assert.equal(makePiece(0, 7.7, 4, 8.1, ctx()).c, '#F7931E')
  // Custom range changes the colouring.
  assert.equal(makePiece(0, 9, 4, 9, ctx([4.4, 10])).c, '#E5E7EB')
})

test('pieces are clipped to the plot and dropped when fully outside', () => {
  assert.equal(makePiece(-8, 6, -4, 6, ctx()), null)
  assert.equal(makePiece(L.plotWidth + 1, 6, L.plotWidth + 5, 6, ctx()), null)
  const clipped = makePiece(-2, 6, 2, 6, ctx())
  assert.equal(clipped.l, 0)
  assert.equal(clipped.w, 2)
  const flat = makePiece(10, 6, 14, 6, ctx())
  assert.equal(flat.h, L.lineWidth)
})

test('long segments are split into narrow pieces that tile without gaps', () => {
  const samples = [{ t: 0, v: 4 }, { t: 30, v: 9 }]
  const pieces = rasterizeRuns([samples], ctx())
  assert.equal(pieces.length, Math.ceil(30 / L.maxPieceWidth))
  pieces.slice(1).forEach((piece, i) => {
    assert.equal(piece.l, pieces[i].l + pieces[i].w)
    assert.ok(piece.w <= L.maxPieceWidth)
  })
  assert.equal(pieces[0].c, '#E5E7EB')
  assert.equal(pieces[pieces.length - 1].c, '#F7931E')
})

test('isolated readings are drawn as a small square', () => {
  const pieces = rasterizeRuns([[{ t: 100, v: 6 }]], ctx())
  assert.equal(pieces.length, 1)
  assert.equal(pieces[0].w, L.pointSize)
  assert.equal(pieces[0].h, L.pointSize)
  const atTop = rasterizeRuns([[{ t: 100, v: 12 }]], ctx())[0]
  assert.equal(atTop.t, 0)
})

test('mergePieces joins contiguous pieces with identical style only', () => {
  const a = { l: 0, t: 10, w: 4, h: 3, c: '#E5E7EB' }
  const b = { l: 4, t: 10, w: 4, h: 3, c: '#E5E7EB' }
  const c = { l: 8, t: 11, w: 4, h: 3, c: '#E5E7EB' }
  const d = { l: 20, t: 11, w: 4, h: 3, c: '#E5E7EB' }
  assert.deepEqual(mergePieces([a, b, c, d]), [
    { l: 0, t: 10, w: 8, h: 3, c: '#E5E7EB' },
    c,
    d
  ])
  assert.equal(a.w, 4)
  assert.deepEqual(mergePieces([]), [])
})

test('buildChart: steady in-range history collapses into one bar', () => {
  const chart = buildChart(history(steady(60)), RANGE, 'mmol/L', NOW)
  assert.equal(chart.empty, false)
  assert.equal(chart.yMax, 12)
  assert.ok(chart.pieces.length <= 2, `got ${chart.pieces.length} pieces`)
  const covered = chart.pieces.reduce((sum, p) => sum + p.w, 0)
  assert.ok(covered >= L.plotWidth - 1)
  assert.ok(chart.pieces.every((p) => p.c === '#E5E7EB'))
  assert.ok(chart.pieces.every((p) => p.l >= 0 && p.l + p.w <= L.plotWidth))
  const ids = new Set(chart.pieces.map((p) => p.id))
  assert.equal(ids.size, chart.pieces.length)
  assert.equal(chart.hasDot, true)
  assert.equal(chart.dot.color, '#22A06B')
  assert.equal(chart.dot.left, L.plotWidth - L.dotSize / 2)
})

test('buildChart: gaps produce separate line runs', () => {
  const chart = buildChart(history([[120, 60], [117, 60], [60, 60], [57, 60]]), RANGE, '', NOW)
  const rightEdges = chart.pieces.map((p) => p.l + p.w)
  const maxStep = Math.max.apply(null, chart.pieces.slice(1).map((p, i) => p.l - rightEdges[i]))
  assert.ok(maxStep > 30, 'expected a visible gap between the two runs')
})

test('buildChart: points older than the window are excluded', () => {
  const stale = buildChart(history([[30, 60], [27, 60]], NOW - 300 * MINUTE), RANGE, '', NOW)
  assert.equal(stale.empty, true)
  assert.deepEqual(stale.pieces, [])
  assert.equal(stale.hasDot, false)
})

test('buildChart: empty history shows the empty state but keeps band and labels', () => {
  const chart = buildChart({ ref: null, points: [] }, [4.4, 10], 'mmol/L', NOW)
  assert.equal(chart.empty, true)
  assert.deepEqual(chart.pieces, [])
  assert.equal(chart.labels.high.text, '10.0')
  assert.equal(chart.labels.low.text, '4.4')
  assert.equal(buildChart(null, RANGE, '', NOW).empty, true)
})

test('buildChart: y axis grows with high values', () => {
  const chart = buildChart(history([[6, 120], [3, 150], [0, 162]]), RANGE, '', NOW)
  assert.equal(chart.yMax, 18)
  assert.equal(chart.dot.color, '#F7931E')
})

test('buildChart: target band, grid and labels sit at their y positions', () => {
  const chart = buildChart(history(steady(60)), RANGE, 'mmol/L', NOW)
  const y = makeYScale(12, L.plotHeight)
  assert.equal(chart.band.top, Math.round(y(7.8)))
  assert.equal(chart.band.top + chart.band.height, Math.round(y(3.9)))
  assert.equal(chart.grid.length, L.gridLines + 1)
  assert.equal(chart.labels.high.color, '#F7931E')
  assert.equal(chart.labels.low.color, '#E53935')
  assert.equal(chart.labels.high.text, '7.8')
  assert.equal(chart.labels.low.text, '3.9')
  assert.equal(chart.labels.high.top, Math.round(L.plotTop + y(7.8) - L.labelHeight / 2))
  assert.equal(chart.labels.high.left, L.plotLeft + L.plotWidth + L.labelGap)
})

test('range labels never overlap and convert for mg/dL', () => {
  assert.deepEqual(rangeLabelCentres(40, 90, 22), [40, 90])
  assert.deepEqual(rangeLabelCentres(60, 70, 22), [54, 76])
  const chart = buildChart(history(steady(60)), RANGE, 'mg/dL', NOW)
  assert.equal(chart.labels.high.text, '141')
  assert.equal(chart.labels.low.text, '70')
})

test('time labels: start, middle and 现在', () => {
  const chart = buildChart(null, RANGE, '', NOW)
  assert.deepEqual(chart.times.map((t) => t.text), [
    formatClock(NOW - 240 * MINUTE),
    formatClock(NOW - 120 * MINUTE),
    '现在'
  ])
  assert.deepEqual(chart.times.map((t) => t.text).slice(0, 2), ['12:30', '14:30'])
  assert.deepEqual(chart.times.map((t) => t.align), ['left', 'center', 'right'])
})

test('newest point ahead of the band clock is clamped to the right edge', () => {
  const chart = buildChart(history([[3, 60], [0, 60]], NOW + 2 * MINUTE), RANGE, '', NOW)
  assert.equal(chart.dot.left, L.plotWidth - L.dotSize / 2)
  assert.ok(chart.pieces.every((p) => p.l + p.w <= L.plotWidth))
})

test('worst case (zigzag every 3 minutes) stays within the element budget', () => {
  const zigzag = Array.from({ length: 81 }, (_, i) => [240 - i * 3, i % 2 ? 40 : 120])
  const chart = buildChart(history(zigzag), RANGE, '', NOW)
  assert.ok(chart.pieces.length <= 130, `got ${chart.pieces.length} pieces`)
  const many = Array.from({ length: 100 }, (_, i) => [240 - i * 2, 60 + (i % 7)])
  assert.ok(buildChart(history(many), RANGE, '', NOW).pieces.length <= 130)
})

test('gridValues: multiples of 3, at most 4 intervals', () => {
  assert.deepEqual(gridValues(12, L), [0, 3, 6, 9, 12])
  assert.deepEqual(gridValues(15, L), [0, 6, 12])
  assert.deepEqual(gridValues(18, L), [0, 6, 12, 18])
  assert.deepEqual(gridValues(24, L), [0, 6, 12, 18, 24])
  assert.deepEqual(gridValues(27, L), [0, 9, 18, 27])
})
