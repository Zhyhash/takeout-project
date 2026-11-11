import test from 'node:test'
import assert from 'node:assert/strict'
import {normalizeShopSettingsPayload} from '../src/merchant/settings/shopSettingsPayload.js'

const baseline = {
  merchantName: '青禾小馆',
  address: '上海市静安区 12 号',
  description: '家常菜',
  pictureURL: 'https://example.test/shop.png',
  openingTime: '09:00:00',
  closingTime: '21:30:00',
}

test('omits untouched profile fields and trims only changed text', () => {
  const result = normalizeShopSettingsPayload({
    baseline,
    draft: { ...baseline, address: '  上海市静安区 13 号  ' },
  })
  assert.deepEqual(result.errors, {})
  assert.deepEqual(result.payload, { address: '上海市静安区 13 号' })
})

test('allows intentional clearing of optional public text without sending unrelated blanks', () => {
  const result = normalizeShopSettingsPayload({
    baseline,
    draft: { ...baseline, description: '', pictureURL: '' },
  })
  assert.deepEqual(result.errors, {})
  assert.deepEqual(result.payload, { description: '', pictureURL: '' })
})

test('requires explicit phone selection and validates its format', () => {
  const untouched = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, phoneDraft: '' })
  assert.deepEqual(untouched.payload, {})
  assert.equal(untouched.errors.form, '请先修改一项资料，或明确选择修改手机号、经纬度。')

  const invalid = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyPhone: true, phoneDraft: '123' })
  assert.equal(invalid.errors.phone, '手机号格式不正确，请输入有效的中国大陆手机号。')

  const valid = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyPhone: true, phoneDraft: ' +8613800138000 ' })
  assert.deepEqual(valid.errors, {})
  assert.deepEqual(valid.payload, { phone: '+8613800138000' })
})

test('requires a complete explicit coordinate pair with DTO-compatible bounds and precision', () => {
  const incomplete = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyCoordinates: true, longitudeDraft: '121.4' })
  assert.equal(incomplete.errors.coordinates, '经度和纬度必须同时填写。')

  const tooPrecise = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyCoordinates: true, longitudeDraft: '121.12345678', latitudeDraft: '31.2' })
  assert.equal(tooPrecise.errors.coordinates, '经纬度请输入有效数字，最多保留7位小数。')

  const outOfRange = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyCoordinates: true, longitudeDraft: '181', latitudeDraft: '31.2' })
  assert.equal(outOfRange.errors.coordinates, '经度范围为 -180 至 180。')

  const valid = normalizeShopSettingsPayload({ baseline, draft: { ...baseline }, modifyCoordinates: true, longitudeDraft: '-180', latitudeDraft: '85.0511287' })
  assert.deepEqual(valid.errors, {})
  assert.deepEqual(valid.payload, { longitude: -180, latitude: 85.0511287 })
})

test('normalizes changed LocalTime values to include seconds', () => {
  const result = normalizeShopSettingsPayload({ baseline, draft: { ...baseline, openingTime: '08:45' } })
  assert.deepEqual(result.errors, {})
  assert.deepEqual(result.payload, { openingTime: '08:45:00' })
})
