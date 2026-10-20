// Persistence of the latest reading and history, independent of
// @system.storage so it can be unit-tested with a fake. store.js wires it to
// the real module.

import { HISTORY_STORAGE_KEY, STORAGE_KEY } from './constants.js'
import { decodeStoredHistory, decodeStoredReading } from './protocol.js'

/** A single JSON value stored under `key`, decoded with `decode` on load. */
export function createSlot(storageApi, key, decode) {
  /** Calls `onLoaded(valueOrNull)` exactly once. */
  function load(onLoaded) {
    let done = false
    const finish = (value) => {
      if (!done) {
        done = true
        onLoaded(value)
      }
    }
    try {
      storageApi.get({
        key,
        success: (raw) => finish(decode(raw)),
        fail: (data, code) => {
          console.error(`storage.get(${key}) failed, code = ${code}`)
          finish(null)
        }
      })
    } catch (err) {
      console.error(`storage.get(${key}) threw`, err && err.message)
      finish(null)
    }
  }

  function save(value) {
    try {
      storageApi.set({
        key,
        value: JSON.stringify(value),
        fail: (data, code) => console.error(`storage.set(${key}) failed, code = ${code}`)
      })
    } catch (err) {
      console.error(`storage.set(${key}) threw`, err && err.message)
    }
  }

  return { load, save }
}

export function createStore(storageApi) {
  const reading = createSlot(storageApi, STORAGE_KEY, decodeStoredReading)
  const history = createSlot(storageApi, HISTORY_STORAGE_KEY, decodeStoredHistory)
  return {
    loadReading: reading.load,
    saveReading: reading.save,
    loadHistory: history.load,
    saveHistory: history.save
  }
}
