import {createRoleHttp} from './http.js'

const http = createRoleHttp('merchant')

// The merchant API has no self-profile GET endpoint. This public detail endpoint
// provides the fields that are safe to display; it does not include phone or coordinates.
export function getPublicShopSettings(shopId) {
  return http.get(`/api/customer/shops/${encodeURIComponent(shopId)}`)
}

export function updateShopSettings(payload) {
  return http.put('/merchant/info', payload)
}

export function updateShopBusinessStatus(status) {
  return http.patch('/merchant/info', null, { params: { status } })
}
