const roles = new Set(['customer', 'merchant', 'rider'])
const loginNamePattern = /^[A-Za-z0-9_-]+$/
const phonePattern = /^(?:(?:\+|00)86)?1[3-9]\d{9}$/

function trimmed(value) {
  return typeof value === 'string' ? value.trim() : ''
}

/** Builds exactly the fields accepted by each role's registration DTO. */
export function registrationPayload(role, values) {
  if (!roles.has(role)) throw new TypeError('未知的注册身份')
  const base = {
    password: values.password,
    confirmPassword: values.confirmPassword,
    phone: trimmed(values.phone),
  }

  if (role === 'customer') return { username: trimmed(values.username), ...base }
  if (role === 'rider') return { name: trimmed(values.name), ...base }
  return {
    username: trimmed(values.username),
    ...base,
    merchantName: trimmed(values.merchantName),
    address: trimmed(values.address),
    pictureURL: trimmed(values.pictureURL),
    description: trimmed(values.description),
  }
}

/** Mirrors the DTO constraints so the UI can explain invalid values early. */
export function registrationErrors(role, values) {
  if (!roles.has(role)) return { role: '未知的注册身份' }
  const errors = {}
  const nameField = role === 'rider' ? 'name' : 'username'
  const name = trimmed(values[nameField])
  const nameLabel = role === 'rider' ? '骑手名称' : '登录名'

  if (!name) errors[nameField] = `${nameLabel}不能为空`
  else if (name.length > 50) errors[nameField] = `${nameLabel}不能超过50个字符`
  else if (!loginNamePattern.test(name)) errors[nameField] = `${nameLabel}只能包含英文字母、数字、下划线和连字符`

  if (typeof values.password !== 'string' || values.password.length < 8 || values.password.length > 20) {
    errors.password = '密码长度必须为8到20位'
  }
  if (typeof values.confirmPassword !== 'string' || values.confirmPassword.length < 8 || values.confirmPassword.length > 20) {
    errors.confirmPassword = '确认密码长度必须为8到20位'
  } else if (values.confirmPassword !== values.password) {
    errors.confirmPassword = '两次输入的密码不一致'
  }

  const phone = trimmed(values.phone)
  if (!phone) errors.phone = '手机号不能为空'
  else if (!phonePattern.test(phone)) errors.phone = '请输入有效的中国大陆手机号'

  if (role === 'merchant') {
    const merchantName = trimmed(values.merchantName)
    if (!merchantName) errors.merchantName = '商家名称不能为空'
    else if (merchantName.length > 255) errors.merchantName = '商家名称不能超过255个字符'
    for (const field of ['address', 'pictureURL', 'description']) {
      if (trimmed(values[field]).length > 255) errors[field] = '内容不能超过255个字符'
    }
  }

  return errors
}
