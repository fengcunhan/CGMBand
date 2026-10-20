// Wires store-core to the real system.storage module.

import storage from '@system.storage'
import { createStore } from './store-core.js'

const store = createStore(storage)

export const loadReading = store.loadReading
export const saveReading = store.saveReading
export const loadHistory = store.loadHistory
export const saveHistory = store.saveHistory
