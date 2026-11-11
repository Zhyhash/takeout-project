import {reactive} from 'vue'
import {registerSessionCleanup} from '../../auth.js'

// The API cannot query logically deleted products. Keep only IDs deleted during
// this live browser session so the UI never advertises a persistent recycle bin.
// Namespacing by merchant also prevents another account from seeing old IDs.
export const knownDeletedProducts = reactive(new Map())

registerSessionCleanup('merchant', () => {
  knownDeletedProducts.clear()
  return true
})
