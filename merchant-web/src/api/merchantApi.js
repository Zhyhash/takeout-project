import http from './http.js'

export const getUnseenOrders = (config = {}) => http.get('/merchant/orders/unseen', config)
export const markOrdersSeen = (ids, config = {}) => http.patch('/merchant/orders/seen', ids, config)

export function merchantLogin(username, password) {
  return http.post('/merchant/login', { username, password })
}

// type 取值由后端 MerchantOrderListType 决定：PENDING / PROCESSING / HISTORY。
// 后端只支持按类型筛选，没有按单一状态查询的参数。
export function listMerchantOrders(type, pageNum, pageSize) {
  return http.get('/merchant/orders', { params: { type, pageNum, pageSize } })
}

export function getOrderDetail(orderId) {
  return http.get(`/merchant/orders/${orderId}`)
}

export function acceptOrder(orderId) {
  return http.patch(`/merchant/orders/${orderId}/accept`)
}

export function rejectOrder(orderId) {
  return http.patch(`/merchant/orders/${orderId}/reject`)
}

export function completePreparation(orderId) {
  return http.patch(`/merchant/orders/${orderId}/ready`)
}
