// Chart maths: y-axis scale, gap splitting and rasterising the polyline into
// small axis-aligned rectangles (the band has no canvas, so the line is drawn
// with absolutely positioned divs). Pure functions; coordinates are px
// relative to the plot area's top-left corner.

import { lineColor } from './glucose.js'

/** Top of the y axis: max(minYMax, ceil(max(values, range high) / step) * step). */
export function yAxisMax(values, range, layout) {
  const candidates = values.concat(range ? [range[1]] : [])
  const highest = candidates.length > 0 ? Math.max.apply(null, candidates) : 0
  return Math.max(layout.minYMax, Math.ceil(highest / layout.yStep) * layout.yStep)
}

/** Value (mmol/L) -> y px; 0 at the bottom of the plot, yMax at the top. */
export function makeYScale(yMax, plotHeight) {
  return (value) => plotHeight * (1 - Math.min(Math.max(value, 0), yMax) / yMax)
}

/** Split samples ({t, v}, oldest -> newest) wherever the gap exceeds gapMs. */
export function splitRuns(samples, gapMs) {
  const runs = []
  samples.forEach((sample, index) => {
    if (index === 0 || sample.t - samples[index - 1].t > gapMs) {
      runs.push([sample])
    } else {
      runs[runs.length - 1].push(sample)
    }
  })
  return runs
}

function clampSpan(start, end, max) {
  return [Math.max(0, start), Math.min(max, end)]
}

/**
 * One rectangle covering the line between (xa, va) and (xb, vb), `lineWidth`
 * thick, coloured by the level of its midpoint. null if fully outside.
 */
export function makePiece(xa, va, xb, vb, ctx) {
  const layout = ctx.layout
  if (xb < 0 || xa > layout.plotWidth) {
    return null
  }
  const half = layout.lineWidth / 2
  const ya = ctx.y(va)
  const yb = ctx.y(vb)
  const xs = clampSpan(Math.round(xa), Math.round(xb), layout.plotWidth)
  const ys = clampSpan(
    Math.round(Math.min(ya, yb) - half),
    Math.round(Math.max(ya, yb) + half),
    layout.plotHeight
  )
  const left = Math.min(xs[0], layout.plotWidth - 1)
  return {
    l: left,
    t: ys[0],
    w: Math.max(1, xs[1] - left),
    h: Math.max(1, ys[1] - ys[0]),
    c: lineColor((va + vb) / 2, ctx.range)
  }
}

function segmentPieces(a, b, ctx) {
  const xa = ctx.x(a.t)
  const xb = ctx.x(b.t)
  const count = Math.max(1, Math.ceil((xb - xa) / ctx.layout.maxPieceWidth))
  const pieces = []
  for (let k = 0; k < count; k += 1) {
    const f0 = k / count
    const f1 = (k + 1) / count
    pieces.push(makePiece(
      xa + (xb - xa) * f0, a.v + (b.v - a.v) * f0,
      xa + (xb - xa) * f1, a.v + (b.v - a.v) * f1,
      ctx
    ))
  }
  return pieces
}

function isolatedPoint(sample, ctx) {
  const size = ctx.layout.pointSize
  const x = ctx.x(sample.t)
  const piece = makePiece(x - size / 2, sample.v, x + size / 2, sample.v, ctx)
  if (!piece) {
    return null
  }
  const top = Math.round(ctx.y(sample.v) - size / 2)
  return Object.assign({}, piece, {
    t: Math.min(Math.max(top, 0), ctx.layout.plotHeight - size),
    h: size
  })
}

/**
 * Rasterise runs into pieces. ctx = { x(t), y(v), range, layout }.
 * Single-sample runs become a small square so isolated readings stay visible.
 */
export function rasterizeRuns(runs, ctx) {
  const pieces = []
  runs.forEach((run) => {
    if (run.length === 1) {
      pieces.push(isolatedPoint(run[0], ctx))
      return
    }
    for (let i = 1; i < run.length; i += 1) {
      segmentPieces(run[i - 1], run[i], ctx).forEach((piece) => pieces.push(piece))
    }
  })
  return pieces.filter((piece) => piece !== null)
}

/**
 * Merge horizontally adjacent pieces with identical top, height and colour
 * (flat stretches), to keep the number of divs on the band low.
 */
export function mergePieces(pieces) {
  const merged = []
  pieces.forEach((piece) => {
    const last = merged[merged.length - 1]
    const joins = last && last.t === piece.t && last.h === piece.h &&
      last.c === piece.c && last.l + last.w === piece.l
    if (joins) {
      merged[merged.length - 1] = Object.assign({}, last, { w: last.w + piece.w })
    } else {
      merged.push(piece)
    }
  })
  return merged
}
