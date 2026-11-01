import { createRoleHttp } from './http.js'

const http = createRoleHttp('merchant')

export function listMerchantProducts({ pageNum = 1, pageSize = 10, status, categoryId, keyword } = {}) {
  return http.get('/category/products', {
    params: { pageNum, pageSize, status, categoryId, keyword },
  })
}

export function getMerchantProductForEdit(productId) {
  return http.get(`/category/products/${encodeURIComponent(productId)}/edit`)
}

export function createMerchantProduct(payload) {
  return http.post('/category/products', payload)
}

export function uploadMerchantProductImage(file) {
  const body = new FormData()
  body.append('file', file)
  return http.post('/category/products/image', body)
}

export function updateMerchantProduct(productId, payload) {
  return http.patch(`/category/products/${encodeURIComponent(productId)}`, payload)
}

export function deleteMerchantProduct(productId) {
  return http.delete(`/category/products/${encodeURIComponent(productId)}`)
}

export function setMerchantProductShelf(productId, onShelf) {
  const action = onShelf ? 'on-shelf' : 'off-shelf'
  return http.patch(`/category/products/${encodeURIComponent(productId)}/${action}`)
}

export function restoreMerchantProduct(productId) {
  return http.post(`/merchant/restore/${encodeURIComponent(productId)}`)
}

export function listMerchantCategories() {
  return http.get('/category')
}

export function createMerchantCategory(categoryName) {
  return http.post('/category', null, { params: { categoryName } })
}

export function deleteMerchantCategory(categoryId) {
  return http.delete(`/category/${encodeURIComponent(categoryId)}`)
}
