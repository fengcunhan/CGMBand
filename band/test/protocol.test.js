import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  createSyncRequest,
  decodeMessage,
  decodeStoredHistory,
  decodeStoredReading,
  parseMessage,
  toReading
} from '../src/lib/protocol.js'

function readingFromMessage(raw) {
  const message = decodeMessage(raw)
  return message && message.kind === 'bg' ? message.reading : null
}

const SAMPLE = JSON.stringify({
  type: 'bg',
  value: '6.6',
  unit: 'mmol/L',
  mmol: 6.6,
  time: '16:33',
  ts: 1792398021000,
  trend: 'FLAT',
  delta: 0.1,
  connected: true
})

function quietErrors(t) {
  t.mock.method(console, 'error', () => {})
}

test('sync request has the agreed shape', () => {
  assert.deepEqual(createSyncRequest(), { type: 'sync' })
})

test('parseMessage handles strings, objects and garbage', (t) => {
  quietErrors(t)
  assert.deepEqual(parseMessage('{"a":1}'), { a: 1 })
  const obj = { a: 2 }
  assert.equal(parseMessage(obj), obj)
  assert.equal(parseMessage('not json'), null)
  assert.equal(parseMessage('42'), null)
  assert.equal(parseMessage('null'), null)
  assert.equal(parseMessage(''), null)
  assert.equal(parseMessage(undefined), null)
})

test('readingFromMessage normalises a full bg message', () => {
  assert.deepEqual(readingFromMessage(SAMPLE), {
    value: '6.6',
    unit: 'mmol/L',
    mmol: 6.6,
    time: '16:33',
    ts: 1792398021000,
    trend: 'FLAT',
    delta: 0.1,
    connected: true
  })
})

test('readingFromMessage ignores unknown types and malformed payloads', (t) => {
  quietErrors(t)
  assert.equal(readingFromMessage('{"type":"ping"}'), null)
  assert.equal(readingFromMessage('{"value":"6.6"}'), null)
  assert.equal(readingFromMessage('{oops'), null)
  assert.equal(readingFromMessage(null), null)
})

test('readingFromMessage tolerates missing / odd fields', () => {
  const reading = readingFromMessage(
    JSON.stringify({ type: 'bg', value: 'LO', mmol: null, trend: 'SIDEWAYS', delta: 'x' })
  )
  assert.equal(reading.value, 'LO')
  assert.equal(reading.unit, '')
  assert.equal(reading.mmol, null)
  assert.equal(reading.ts, null)
  assert.equal(reading.trend, 'NONE')
  assert.equal(reading.delta, null)
  assert.equal(reading.connected, true)
})

test('ts null is kept as null (time unknown), never replaced', () => {
  const reading = readingFromMessage(JSON.stringify({ type: 'bg', value: '5.0', ts: null }))
  assert.equal(reading.ts, null)
  assert.equal('receivedAt' in reading, false)
})

test('toReading accepts numeric strings, numbers and connected=false', () => {
  const reading = toReading(
    { value: 120, unit: 'mg/dL', mmol: '6.7', ts: '1792398021000', delta: -0.3, connected: false }
  )
  assert.equal(reading.value, '120')
  assert.equal(reading.mmol, 6.7)
  assert.equal(reading.ts, 1792398021000)
  assert.equal(reading.delta, -0.3)
  assert.equal(reading.connected, false)
})

test('toReading uses placeholder for empty value and rejects non-objects', () => {
  assert.equal(toReading({ value: '   ' }).value, '--')
  assert.equal(toReading(null), null)
  assert.equal(toReading('str'), null)
})

test('value is capped so it fits the screen', () => {
  assert.equal(toReading({ value: 'SENSOR WARMUP' }).value, 'SENSOR')
  assert.equal(toReading({ value: 1234567.5 }).value, '123456')
})

test('decodeStoredReading round-trips (including ts null) and tolerates corruption', (t) => {
  quietErrors(t)
  const reading = readingFromMessage(SAMPLE)
  assert.deepEqual(decodeStoredReading(JSON.stringify(reading)), reading)
  const unknownTime = Object.assign({}, reading, { ts: null })
  assert.deepEqual(decodeStoredReading(JSON.stringify(unknownTime)), unknownTime)
  assert.equal(decodeStoredReading(''), null)
  assert.equal(decodeStoredReading(undefined), null)
  assert.equal(decodeStoredReading('{broken'), null)
  assert.equal(decodeStoredReading('7'), null)
})

test('toReading does not mutate its input', () => {
  const src = Object.freeze({ type: 'bg', value: '5.0', trend: 'BAD' })
  const reading = toReading(src)
  assert.notEqual(reading, src)
  assert.equal(src.trend, 'BAD')
})

test('decodeMessage dispatches bg and history, ignores the rest', (t) => {
  quietErrors(t)
  const bg = decodeMessage(SAMPLE)
  assert.equal(bg.kind, 'bg')
  assert.equal(bg.reading.value, '6.6')

  const history = decodeMessage(
    '{"type":"history","ref":1792398000000,"range":[3.9,7.8],"points":[[240,66],[237,65],[3,62],[0,61]]}'
  )
  assert.equal(history.kind, 'history')
  assert.equal(history.history.ref, 1792398000000)
  assert.equal(history.history.points.length, 4)
  assert.deepEqual(history.history.range, [3.9, 7.8])

  const empty = decodeMessage('{"type":"history","ref":null,"range":[4.4,10.0],"points":[]}')
  assert.deepEqual(empty.history, { ref: null, points: [], range: [4.4, 10] })

  assert.equal(decodeMessage('{"type":"sync"}'), null)
  assert.equal(decodeMessage('{"type":"ping"}'), null)
  assert.equal(decodeMessage('not json'), null)
})

test('decodeStoredHistory round-trips and tolerates corruption', (t) => {
  quietErrors(t)
  const stored = { ref: 1792398000000, points: [[3, 62], [0, 61]], range: [4.4, 10] }
  assert.deepEqual(decodeStoredHistory(JSON.stringify(stored)), stored)
  assert.equal(decodeStoredHistory(''), null)
  assert.equal(decodeStoredHistory('{broken'), null)
  assert.equal(decodeStoredHistory('7'), null)
})
