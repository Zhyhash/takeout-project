import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildCreateProductPayload,
  buildUpdateProductPayload,
  validateProductDraft,
} from '../src/merchant/catalog/productDraft.js'

const existing = {
  productName: '招牌饭',
  description: '微辣',
  price: 12345678.9,
  stock: 12,
  categoryName: '主食',
  imageUrl: '/images/rice.png',
  version: 4,
}

test('edit patch always carries version and only changed supported fields', () => {
  const payload = buildUpdateProductPayload(
    { ...existing, productName: '招牌牛肉饭', version: 5, stock: 3 },
    existing,
  )

  assert.deepEqual(payload, { version: 5, productName: '招牌牛肉饭' })
  assert.equal(Object.hasOwn(payload, 'stock'), false)
  assert.equal(Object.hasOwn(payload, 'categoryName'), false)
  assert.equal(Object.hasOwn(payload, 'categoryId'), false)
})

test('unknown current category ID is never guessed from category name', () => {
  const payload = buildUpdateProductPayload(
    { ...existing, version: 4, categoryId: '999' },
    existing,
  )

  assert.deepEqual(payload, { version: 4 })
})

test('stock is omitted by default but explicit reset is sent even when unchanged', () => {
  const draft = { ...existing, version: 4 }
  assert.deepEqual(buildUpdateProductPayload(draft, existing), { version: 4 })
  assert.deepEqual(buildUpdateProductPayload(draft, existing, { resetStock: true }), { version: 4, stock: 12 })
})

test('unchanged eight-digit legacy price remains editable while changed price follows edit DTO range', () => {
  assert.deepEqual(validateProductDraft({ ...existing, version: 4 }, 'edit', existing), {})
  assert.equal(validateProductDraft({ ...existing, price: '12345678.90', version: 4 }, 'edit', existing).price, undefined)
  assert.match(validateProductDraft({ ...existing, price: '100000.00', version: 4 }, 'edit', existing).price, /0至99999\.99/)
  assert.deepEqual(validateProductDraft({ ...existing, price: '0', version: 4 }, 'edit', existing), {})
})

test('create payload includes required fields and applies create-specific price rules', () => {
  const payload = buildCreateProductPayload({
    productName: '新品', description: '', price: '99999999.99', stock: 0, categoryId: '8',
  })
  assert.deepEqual(payload, { productName: '新品', description: '', price: 99999999.99, stock: 0, categoryId: 8 })
  assert.equal(validateProductDraft({ productName: '新品', price: '99999999.99', stock: 0, categoryId: '8' }, 'create').price, undefined)
  assert.match(validateProductDraft({ productName: '新品', price: '100000000.00', stock: 0, categoryId: '8' }, 'create').price, /8位整数/)
  assert.match(validateProductDraft({ productName: '新品', price: '0', stock: 0, categoryId: '8' }, 'create').price, /大于0/)
  assert.match(validateProductDraft({ productName: '新品', price: '1', stock: 2147483648, categoryId: '8' }, 'create').stock, /2,147,483,647/)
})

test('edit requires a real version value instead of coercing missing versions to zero', () => {
  const draft = { ...existing, price: String(existing.price) }
  for (const version of [null, undefined, '']) {
    assert.match(validateProductDraft({ ...draft, version }, 'edit', existing).version, /版本信息无效/)
  }
})
