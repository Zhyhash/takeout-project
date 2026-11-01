const PHONE_PATTERN = /^(?:(?:\+|00)86)?1[3-9]\d{9}$/
const COORDINATE_PATTERN = /^-?(?:0|[1-9]\d*)(?:\.\d{1,7})?$/
const MAX_FIELD_LENGTH = 255

const text = (value) => String(value ?? '').trim()
const time = (value) => text(value).slice(0, 5)

function addChangedText(payload, errors, field, currentValue, nextValue, label, { required = false, max = MAX_FIELD_LENGTH } = {}) {
  const next = text(nextValue)
  if (next === text(currentValue)) return
  if (required && !next) {
    errors[field] = `${label}不能为空。`
    return
  }
  if (next.length > max) {
    errors[field] = `${label}最多${max}个字符。`
    return
  }
  payload[field] = next
}

function addChangedTime(payload, errors, field, currentValue, nextValue, label) {
  const next = time(nextValue)
  if (next === time(currentValue)) return
  if (next && !/^([01]\d|2[0-3]):[0-5]\d$/.test(next)) {
    errors[field] = `请填写有效的${label}。`
    return
  }
  if (next) payload[field] = `${next}:00`
  else errors[field] = `${label}不能为空；如需调整，请选择时间。`
}

function addCoordinates(payload, errors, longitudeValue, latitudeValue) {
  const longitude = text(longitudeValue)
  const latitude = text(latitudeValue)
  if (!longitude || !latitude) {
    errors.coordinates = '经度和纬度必须同时填写。'
    return
  }
  if (!COORDINATE_PATTERN.test(longitude) || !COORDINATE_PATTERN.test(latitude)) {
    errors.coordinates = '经纬度请输入有效数字，最多保留7位小数。'
    return
  }

  const longitudeNumber = Number(longitude)
  const latitudeNumber = Number(latitude)
  if (longitudeNumber < -180 || longitudeNumber > 180) {
    errors.coordinates = '经度范围为 -180 至 180。'
    return
  }
  if (latitudeNumber < -85.05112878 || latitudeNumber > 85.05112878) {
    errors.coordinates = '纬度范围为 -85.05112878 至 85.05112878。'
    return
  }

  // Send JSON numbers (Jackson maps them to BigDecimal) after checking their exact
  // decimal spelling so the backend's @Digits limit cannot be exceeded.
  payload.longitude = longitudeNumber
  payload.latitude = latitudeNumber
}

/** Build the partial DTO for PUT /merchant/info without serializing untouched blanks. */
export function normalizeShopSettingsPayload({
  baseline,
  draft,
  modifyPhone = false,
  phoneDraft = '',
  modifyCoordinates = false,
  longitudeDraft = '',
  latitudeDraft = '',
} = {}) {
  const payload = {}
  const errors = {}
  if (!baseline || !draft) return { payload, errors: { form: '店铺资料尚未读取完成。' } }

  addChangedText(payload, errors, 'merchantName', baseline.merchantName, draft.merchantName, '店铺名称', { required: true })
  addChangedText(payload, errors, 'address', baseline.address, draft.address, '店铺地址', { required: true })
  addChangedText(payload, errors, 'description', baseline.description, draft.description, '店铺简介')
  addChangedText(payload, errors, 'pictureURL', baseline.pictureURL, draft.pictureURL, '图片 URL')
  addChangedTime(payload, errors, 'openingTime', baseline.openingTime, draft.openingTime, '开门时间')
  addChangedTime(payload, errors, 'closingTime', baseline.closingTime, draft.closingTime, '打烊时间')

  if (modifyPhone) {
    const phone = text(phoneDraft)
    if (!phone) errors.phone = '请输入要设置的手机号。'
    else if (phone.length > 20 || !PHONE_PATTERN.test(phone)) errors.phone = '手机号格式不正确，请输入有效的中国大陆手机号。'
    else payload.phone = phone
  }
  if (modifyCoordinates) addCoordinates(payload, errors, longitudeDraft, latitudeDraft)

  if (!Object.keys(payload).length && !Object.keys(errors).length) errors.form = '请先修改一项资料，或明确选择修改手机号、经纬度。'
  return { payload, errors }
}

export const shopSettingsValidation = Object.freeze({
  phonePattern: PHONE_PATTERN,
  coordinatePattern: COORDINATE_PATTERN,
})
