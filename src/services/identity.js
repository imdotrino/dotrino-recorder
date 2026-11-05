// Identidad = la bóveda id.dotrino.com (@dotrino/identity), única fuente de identidad del
// ecosistema. Una sola conexión para toda la página.
import { Identity } from '@dotrino/identity'

let pending = null

export function getIdentity () {
  if (!pending) {
    pending = Identity.connect().catch((e) => {
      console.error('[recorder] identity unreachable:', e?.message || e)
      pending = null
      return null
    })
  }
  return pending
}
