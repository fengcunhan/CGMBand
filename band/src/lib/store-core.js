// Persistence of the latest reading, independent of @system.storage so it can
// be unit-tested with a fake. store.js wires it to the real module.

import { STORAGE_KEY } from './constants.js'
import { decodeStoredReading } from './protocol.js'

export function createStore(storageApi) {
  /** Calls `onLoaded(readingOrNull)` exactly once. */
  function loadReading(onLoaded) {
    let done = false
    const finish = (reading) => {
      if (!done) {
        done = true
        onLoaded(reading)
      }
    }
    try {
      storageApi.get({
        key: STORAGE_KEY,
        success: (raw) => finish(decodeStoredReading(raw)),
        fail: (data, code) => {
          console.error(`storage.get failed, code = ${code}`)
          finish(null)
        }
      })
    } catch (err) {
      console.error('storage.get threw', err && err.message)
      finish(null)
    }
  }

  function saveReading(reading) {
    try {
      storageApi.set({
        key: STORAGE_KEY,
        value: JSON.stringify(reading),
        fail: (data, code) => console.error(`storage.set failed, code = ${code}`)
      })
    } catch (err) {
      console.error('storage.set threw', err && err.message)
    }
  }

  return { loadReading, saveReading }
}
