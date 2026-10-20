import { test } from 'node:test'
import assert from 'node:assert/strict'

import { HISTORY_STORAGE_KEY, STORAGE_KEY } from '../src/lib/constants.js'
import { createStore } from '../src/lib/store-core.js'

function memoryStorage() {
  const data = {}
  return {
    data,
    get(opts) {
      opts.success(data[opts.key] === undefined ? '' : data[opts.key])
    },
    set(opts) {
      data[opts.key] = opts.value
    }
  }
}

const READING = {
  value: '6.6', unit: 'mmol/L', mmol: 6.6, time: '16:33', ts: 10, trend: 'FLAT', delta: 0.1, connected: true
}

function load(store) {
  let result
  let calls = 0
  store.loadReading((r) => {
    result = r
    calls += 1
  })
  return { result, calls }
}

test('save then load round-trips the reading', () => {
  const storage = memoryStorage()
  const store = createStore(storage)
  store.saveReading(READING)
  assert.equal(typeof storage.data[STORAGE_KEY], 'string')
  assert.deepEqual(load(store), { result: READING, calls: 1 })
})

test('ts null survives a save/load cycle', () => {
  const store = createStore(memoryStorage())
  const unknownTime = Object.assign({}, READING, { ts: null })
  store.saveReading(unknownTime)
  assert.deepEqual(load(store), { result: unknownTime, calls: 1 })
})

test('empty storage yields null', () => {
  assert.deepEqual(load(createStore(memoryStorage())), { result: null, calls: 1 })
})

test('corrupt entry yields null', (t) => {
  t.mock.method(console, 'error', () => {})
  const storage = memoryStorage()
  storage.data[STORAGE_KEY] = '{not json'
  assert.deepEqual(load(createStore(storage)), { result: null, calls: 1 })
})

test('storage failures and exceptions yield null exactly once', (t) => {
  t.mock.method(console, 'error', () => {})
  const failing = {
    get(opts) {
      opts.fail('err', 300)
    },
    set(opts) {
      opts.fail('err', 300)
    }
  }
  assert.deepEqual(load(createStore(failing)), { result: null, calls: 1 })
  assert.doesNotThrow(() => createStore(failing).saveReading(READING))

  const throwing = {
    get(opts) {
      opts.success('')
      throw new Error('boom')
    },
    set() {
      throw new Error('boom')
    }
  }
  assert.deepEqual(load(createStore(throwing)), { result: null, calls: 1 })
  assert.doesNotThrow(() => createStore(throwing).saveReading(READING))
})

test('history slot round-trips under its own key', () => {
  const storage = memoryStorage()
  const store = createStore(storage)
  const history = { ref: 1000, points: [[3, 62], [0, 61]], range: [4.4, 10] }
  store.saveHistory(history)
  assert.equal(typeof storage.data[HISTORY_STORAGE_KEY], 'string')
  assert.equal(storage.data[STORAGE_KEY], undefined)
  let loaded
  store.loadHistory((value) => {
    loaded = value
  })
  assert.deepEqual(loaded, history)
})
