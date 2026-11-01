import { createRoleHttp } from './http.js'

const http = createRoleHttp('customer')

export function listShops({ name, pageNum = 1, size = 12, status } = {}) {
  return http.get('/api/customer/shops', { params: { name, pageNum, size, status } })
}

export function getShop(shopId) {
  return http.get(`/api/customer/shops/${encodeURIComponent(shopId)}`)
}

export function recordProductView(productId) {
  return http.post(`/user/browse-history/${encodeURIComponent(productId)}`, undefined)
}

export function getBrowseHistory() {
  return http.get('/user/browse-history')
}

export function clearBrowseHistory() {
  return http.delete('/user/browse-history')
}

export function addCartItem(productId) {
  return http.post('/cart/items', { productId })
}

export function getCart() {
  return http.get('/cart/items')
}

export function changeCartItem(cartItemId, quantityChange) {
  return http.patch('/cart/items', { cartItemId, quantityChange })
}

export function removeCartItems(ids) {
  return http.delete('/cart/items', { params: { ids: ids.join(',') } })
}

export function clearCart() {
  return http.delete('/cart/items/all')
}

export function createOrder(payload) {
  return http.post('/order', payload)
}

export function listOrders(pageNum = 1, pageSize = 10) {
  return http.get('/order', { params: { pageNum, pageSize } })
}

export function getOrder(orderId) {
  return http.get(`/order/${encodeURIComponent(orderId)}`)
}

export function cancelOrder(orderId) {
  return http.patch(`/order/${encodeURIComponent(orderId)}/cancel`)
}

export function payOrder(orderId) {
  return http.patch(`/order/${encodeURIComponent(orderId)}/pay`)
}

export function confirmOrder(orderId) {
  return http.patch(`/order/${encodeURIComponent(orderId)}/confirm`)
}
