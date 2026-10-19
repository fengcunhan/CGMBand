// Wires link-core to the real system.interconnect singleton. The connection
// itself is created and maintained by the system; we only register callbacks.

import interconnect from '@system.interconnect'
import { bindLink, unavailableLink } from './link-core.js'

export function attachLink(handlers) {
  let conn = null
  try {
    conn = interconnect.instance()
  } catch (err) {
    console.error('interconnect.instance() failed', err && err.message)
  }
  return conn ? bindLink(conn, handlers) : unavailableLink(handlers)
}
