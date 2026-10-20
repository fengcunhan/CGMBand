import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  sameHistory,
  toHistory,
  toPoints,
  toRange,
  toSamples
} from '../src/lib/history.js'

const REF = 1792398000000

test('spec example parses into ref, points and range', () => {
  const history = toHistory({
    type: 'history',
    ref: REF,
    range: [3.9, 7.8],
    points: [[240, 66], [237, 65], [3, 62], [0, 61]]
  })
  assert.deepEqual(history, {
    ref: REF,
    points: [[240, 66], [237, 65], [3, 62], [0, 61]],
    range: [3.9, 7.8]
  })
})

test('empty history: ref null + points [] still carries the range', () => {
  assert.deepEqual(
    toHistory({ type: 'history', ref: null, range: [4.4, 10.0], points: [] }),
    { ref: null, points: [], range: [4.4, 10] }
  )
  // ref present but nothing usable -> normalised to "no history".
  assert.deepEqual(toHistory({ ref: REF, points: [] }), { ref: null, points: [], range: null })
  // points without a ref cannot be placed in time.
  assert.deepEqual(toHistory({ ref: null, points: [[0, 60]] }).points, [])
})

test('malformed payloads and entries are tolerated', () => {
  assert.equal(toHistory(null), null)
  assert.equal(toHistory('x'), null)
  assert.deepEqual(toHistory({ ref: 'soon', points: [[0, 60]] }).points, [])
  assert.deepEqual(toPoints('nope'), [])
  assert.deepEqual(
    toPoints([[10, 60], 'bad', [5], [null, 50], [4, 'x'], [3, 0], [2, 9999], [1, 61]]),
    [[10, 60], [1, 61]]
  )
})

test('out-of-window minutes are dropped, values rounded', () => {
  assert.deepEqual(toPoints([[300, 60], [241, 60], [240, 60], [-1, 60], [0.4, 61.6]]), [[240, 60], [0, 62]])
})

test('points are sorted oldest -> newest and capped to the newest 100', () => {
  assert.deepEqual(toPoints([[0, 61], [6, 63], [3, 62]]), [[6, 63], [3, 62], [0, 61]])
  const many = Array.from({ length: 150 }, (_, i) => [i, 60])
  const capped = toPoints(many)
  assert.equal(capped.length, 100)
  assert.deepEqual(capped[0], [99, 60])
  assert.deepEqual(capped[99], [0, 60])
})

test('toRange accepts sane increasing pairs only', () => {
  assert.deepEqual(toRange([3.9, 7.8]), [3.9, 7.8])
  assert.deepEqual(toRange([4.4, 10.0]), [4.4, 10])
  assert.equal(toRange([7.8, 3.9]), null)
  assert.equal(toRange([5, 5]), null)
  assert.equal(toRange([0, 7.8]), null)
  assert.equal(toRange([-1, 7.8]), null)
  assert.deepEqual(toRange([0.5, 8]), [0.5, 8])
  assert.deepEqual(toRange([4, 33.3]), [4, 33.3])
  assert.equal(toRange([4, 33.4]), null)
  assert.equal(toRange([3.9, 99]), null)
  assert.equal(toRange([3.9]), null)
  assert.equal(toRange(['3.9', '7.8']), null)
  assert.equal(toRange(undefined), null)
})

test('toSamples converts to absolute time and mmol/L', () => {
  const samples = toSamples({ ref: REF, points: [[3, 62], [0, 61]] }, 60000)
  assert.deepEqual(samples, [{ t: REF - 180000, v: 6.2 }, { t: REF, v: 6.1 }])
  assert.deepEqual(toSamples(null, 60000), [])
  assert.deepEqual(toSamples({ ref: null, points: [] }, 60000), [])
})

test('sameHistory compares by value', () => {
  const a = { ref: REF, points: [[0, 61]], range: [3.9, 7.8] }
  assert.equal(sameHistory(a, { ref: REF, points: [[0, 61]], range: [3.9, 7.8] }), true)
  assert.equal(sameHistory(a, { ref: REF, points: [[0, 62]], range: [3.9, 7.8] }), false)
  assert.equal(sameHistory(null, a), false)
})

test('toHistory does not mutate its input', () => {
  const src = Object.freeze({
    ref: REF,
    points: Object.freeze([Object.freeze([0, 61]), Object.freeze([6, 63])]),
    range: Object.freeze([3.9, 7.8])
  })
  assert.deepEqual(toHistory(src).points, [[6, 63], [0, 61]])
  assert.deepEqual(src.points[0], [0, 61])
})
