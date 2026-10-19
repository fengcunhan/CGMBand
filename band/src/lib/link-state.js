// Phone-link state values shared by link.js (producer) and view.js (consumer).

export const LINK_STATE = Object.freeze({
  CONNECTING: 'connecting',
  OPEN: 'open',
  CLOSED: 'closed',
  ERROR: 'error'
})

export function linkState(state, code) {
  return { state, code: typeof code === 'number' ? code : null }
}

export function initialLinkState() {
  return linkState(LINK_STATE.CONNECTING, null)
}
