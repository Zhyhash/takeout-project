export const PRODUCT_IMAGE_MAX_BYTES = 5 * 1024 * 1024
export const PRODUCT_IMAGE_TYPES = new Set(['image/jpeg', 'image/png'])

const clean = (value) => String(value ?? '').trim()
const decimalText = (value) => {
  const text = clean(value)
  if (!text) return ''
  const [whole, fraction = ''] = text.split('.')
  return `${whole || '0'}${fraction ? `.${fraction.replace(/0+$/, '')}` : ''}`.replace(/\.$/, '')
}
export const samePrice = (left, right) => decimalText(left) === decimalText(right)

export function validateProductDraft(draft, mode, original = null) {
  const errors = {}
  const name = clean(draft.productName)
  if (!name) errors.productName = '请输入商品名称'
  else if (name.length > 15) errors.productName = '商品名称不能超过15个字符'

  if (clean(draft.description).length > 100) errors.description = '商品描述不能超过100个字符'

  const priceText = clean(draft.price)
  const priceChanged = mode === 'create' || !original || !samePrice(draft.price, original.price)
  const pricePattern = mode === 'create' ? /^\d{1,8}(?:\.\d{1,2})?$/ : /^\d+(?:\.\d{1,2})?$/
  const price = Number(priceText)
  if (priceChanged && (!priceText || !pricePattern.test(priceText) || !Number.isFinite(price))) {
    errors.price = mode === 'create' ? '请输入有效价格，最多8位整数和2位小数' : '请输入有效价格，最多保留2位小数'
  } else if (priceChanged && mode === 'create' && price <= 0) {
    errors.price = '新商品价格必须大于0'
  } else if (priceChanged && mode === 'edit' && (price < 0 || price > 99999.99)) {
    errors.price = '编辑价格范围为0至99999.99元'
  }

  if (mode === 'create') {
    if (draft.categoryId === '' || draft.categoryId == null || !Number.isInteger(Number(draft.categoryId)) || Number(draft.categoryId) <= 0) {
      errors.categoryId = '请选择商品分类'
    }
    if (draft.stock === '' || draft.stock == null || !Number.isInteger(Number(draft.stock)) || Number(draft.stock) < 0 || Number(draft.stock) > 2147483647) {
      errors.stock = '库存必须为0至2,147,483,647之间的整数'
    }
  } else {
    if (draft.version === '' || draft.version == null || !Number.isInteger(Number(draft.version)) || Number(draft.version) < 0) errors.version = '商品版本信息无效，请重新加载'
    if (draft.resetStock && (draft.stock === '' || draft.stock == null || !Number.isInteger(Number(draft.stock)) || Number(draft.stock) < 0 || Number(draft.stock) > 10000)) {
      errors.stock = '库存重设范围为0至10000'
    }
  }
  return errors
}

export function buildCreateProductPayload(draft, uploadedImageUrl = '') {
  const payload = {
    productName: clean(draft.productName),
    description: clean(draft.description),
    price: Number(draft.price),
    stock: Number(draft.stock),
    categoryId: Number(draft.categoryId),
  }
  if (uploadedImageUrl) payload.imageUrl = uploadedImageUrl
  return payload
}

export function buildUpdateProductPayload(draft, original, options = {}) {
  const payload = { version: Number(draft.version) }
  if (clean(draft.productName) !== clean(original.productName)) payload.productName = clean(draft.productName)
  if (clean(draft.description) !== clean(original.description)) payload.description = clean(draft.description)
  if (!samePrice(draft.price, original.price)) payload.price = Number(draft.price)
  if (options.categoryId !== undefined && options.categoryId !== null && options.categoryId !== '') {
    payload.categoryId = Number(options.categoryId)
  }
  if (options.uploadedImageUrl) payload.imageUrl = options.uploadedImageUrl
  if (options.resetStock) payload.stock = Number(draft.stock)
  return payload
}

export function validateProductImage(file) {
  if (!file) return '请选择图片文件'
  if (!PRODUCT_IMAGE_TYPES.has(file.type)) return '仅支持 JPEG 或 PNG 图片'
  if (file.size > PRODUCT_IMAGE_MAX_BYTES) return '图片大小不能超过5MB'
  return ''
}

export function createEmptyProductDraft() {
  return { productName: '', description: '', price: '', stock: 0, categoryId: '', imageFile: null }
}

export function createEditProductDraft(product) {
  return {
    productName: product.productName ?? '',
    description: product.description ?? '',
    price: String(product.price ?? ''),
    stock: product.stock ?? 0,
    version: product.version,
    resetStock: false,
    categoryIdSelection: '',
    imageFile: null,
    imageUrl: product.imageUrl ?? '',
  }
}

