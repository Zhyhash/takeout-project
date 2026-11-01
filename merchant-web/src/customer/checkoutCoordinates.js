export const EXAMPLE_DELIVERY_COORDINATES = Object.freeze({ longitude: '120.1536', latitude: '30.2812' })

export function deliveryCoordinateError(value, axis) {
  const longitude = axis === 'longitude'
  const label = longitude ? '经度' : '纬度'
  const limit = longitude ? 180 : 90
  const text = String(value ?? '').trim()
  if (!text) return `请填写收货地点${label}`
  if (!['number', 'string'].includes(typeof value) || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(text) || !Number.isFinite(Number(text))) {
    return `请输入有效的${label}数值`
  }
  if (Number(text) < -limit || Number(text) > limit) return `${label}范围为 -${limit} 至 ${limit}`
  return ''
}

export function normalizeDeliveryCoordinates(form) {
  for (const axis of ['longitude', 'latitude']) {
    const error = deliveryCoordinateError(form[axis], axis)
    if (error) throw new Error(error)
  }
  return { longitude: Number(form.longitude), latitude: Number(form.latitude) }
}
