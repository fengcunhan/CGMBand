// Decide when the chart must be rebuilt. The chart depends only on history,
// range, unit and the time bucket (the x axis shifts once per refresh
// interval), so readings and link changes alone don't rebuild ~100 divs.
// Page state is replaced immutably, so identity comparison is sufficient.

export function chartKey(pageState, now, intervalMs) {
  return {
    history: pageState.history,
    range: pageState.range,
    unit: pageState.reading ? pageState.reading.unit : '',
    bucket: Math.floor(now / intervalMs)
  }
}

export function sameChartKey(a, b) {
  return !!a && !!b &&
    a.history === b.history &&
    a.range === b.range &&
    a.unit === b.unit &&
    a.bucket === b.bucket
}
