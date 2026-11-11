// Local browser-QA fixture only. This does not change the application API client or add a mock mode.
// Start it only with TAKEOUT_UI_QA_FIXTURE=1; it listens on loopback port 18080.
import {createServer} from 'node:http'

const PORT = 18080
const tokenByRole = {
  customer: 'qa-customer-token',
  merchant: 'qa-merchant-token',
  rider: 'qa-rider-token',
}
const userByRole = { customer: 11, merchant: 22, rider: 33 }
const identitiesByToken = new Map(Object.entries(tokenByRole).map(([role, token]) => [token, { role, id: userByRole[role] }]))
const registeredAccounts = { customer: [], merchant: [], rider: [] }
const merchantShopByAccount = new Map([[22, 10]])
const nextAccountId = { value: 100 }
const nextShopId = { value: 100 }
const nextCategoryId = { value: 200 }
const nextProductId = { value: 2000 }
const now = () => new Date().toISOString().slice(0, 19)

const shops = [
  {
    id: 10, merchantId: 22, merchantName: '青禾小馆', pictureURL: '', address: '杭州市拱墅区青禾路 18 号',
    description: '当季食材，认真做一餐。', status: 0, statusDesc: '营业中',
    openingTime: '09:00:00', closingTime: '21:30:00',
  },
  {
    id: 11, merchantId: 23, merchantName: '街角面铺', pictureURL: '', address: '杭州市拱墅区小河路 6 号',
    description: '汤面现做，趁热享用。', status: 0, statusDesc: '营业中',
    openingTime: '10:00:00', closingTime: '22:00:00',
  },
  {
    id: 12, merchantId: 24, merchantName: '晚风甜点', pictureURL: '', address: '杭州市拱墅区运河街 9 号',
    description: '稍后再来，正在打烊。', status: 1, statusDesc: '已打烊',
    openingTime: '12:00:00', closingTime: '19:00:00',
  },
]
const merchantProfiles = new Map([[22, {
  merchantId: 22, shopId: 10, merchantName: '青禾小馆', address: '杭州市拱墅区青禾路 18 号',
  pictureURL: '', description: '当季食材，认真做一餐。', phone: '13800000022',
  longitude: 120.1536, latitude: 30.2812, status: 0, openingTime: '09:00:00', closingTime: '21:30:00',
}]])
const categories = [
  { id: 101, merchantId: 22, categoryName: '人气主食', isDefault: true, active: true },
  { id: 102, merchantId: 22, categoryName: '小食饮品', isDefault: false, active: true },
  { id: 111, merchantId: 23, categoryName: '今日推荐', isDefault: true, active: true },
]
const products = [
  { id: 1001, merchantId: 22, categoryId: 101, productName: '招牌鸡腿饭', description: '香煎鸡腿搭配当季蔬菜。', price: 28.5, stock: 24, status: 0, imageUrl: '', version: 0, stockVersion: 0, deleted: false },
  { id: 1002, merchantId: 22, categoryId: 101, productName: '清爽时蔬饭', description: '清爽时蔬与米饭。', price: 22, stock: 18, status: 0, imageUrl: '', version: 0, stockVersion: 0, deleted: false },
  { id: 1101, merchantId: 23, categoryId: 111, productName: '番茄牛腩面', description: '番茄牛腩汤面。', price: 31, stock: 12, status: 0, imageUrl: '', version: 0, stockVersion: 0, deleted: false },
]
const uploadedImages = new Map()
const merchantEventClients = new Map()
const MAX_PRODUCT_IMAGE_SIZE = 5 * 1024 * 1024

let nextCartId = 1
let nextOrderId = 1000
let nextTaskId = 500
let uploadedImageSequence = 1
let cartItems = []
const browseHistoryByCustomer = new Map()
let riderLocation = null
let orders = [
  {
    id: 701, orderNo: 'QA-701', merchantId: 10, merchantAccountId: 22, merchantName: '青禾小馆', customerId: 11,
    receiverName: '测试顾客', receiverPhone: '13800000000', receiverAddress: '杭州市拱墅区示范小区 1 幢',
    originalAmount: 28.5, discountAmount: 0, totalAmount: 28.5, status: 2,
    statusDesc: '已支付', createTime: now(), payTime: now(), remark: '', merchantSeen: false,
    items: [{ productId: 1001, productName: '招牌鸡腿饭', productPicture: '', productPrice: 28.5, quantity: 1, subtotal: 28.5 }],
  },
  {
    id: 702, orderNo: 'QA-702', merchantId: 10, merchantAccountId: 22, merchantName: '青禾小馆', customerId: 11,
    receiverName: '测试顾客', receiverPhone: '13800000000', receiverAddress: '杭州市拱墅区示范小区 2 幢',
    originalAmount: 22, discountAmount: 0, totalAmount: 22, status: 7,
    statusDesc: '骑手正在配送', createTime: now(), payTime: now(), remark: '', merchantSeen: true,
    items: [{ productId: 1002, productName: '清爽时蔬饭', productPicture: '', productPrice: 22, quantity: 1, subtotal: 22 }],
  },
]
let tasks = [makeTask(77, orders[1], { status: 1, riderId: 33 })]

function makeTask(taskId, order, { status = 0, riderId = null } = {}) {
  return {
    taskId,
    orderId: order.id,
    merchantName: order.merchantName,
    merchantAddress: shops.find((shop) => shop.id === order.merchantId)?.address || '示范店铺取货点',
    merchantPhone: '0571-55550000',
    receiverAddress: order.receiverAddress,
    receiverPhone: order.receiverPhone,
    receiverName: order.receiverName,
    deliveryReward: 5,
    status,
    statusDesc: status === 0 ? '等待骑手取餐' : status === 1 ? '骑手正在配送' : '骑手已经完成配送',
    acceptedTime: status === 0 ? null : now(),
    deliveredTime: status === 2 ? now() : null,
    createTime: order.createTime,
    riderId,
  }
}

function send(response, code, data, message = code === 200 ? '成功' : '请求失败') {
  response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  response.end(JSON.stringify({ code, message, data }))
}

function fail(response, message, code = 500) {
  send(response, code, null, message)
}

function page(items, query) {
  const pageNum = Math.max(1, Number(query.get('pageNum')) || 1)
  const pageSize = Math.max(1, Math.min(100, Number(query.get('pageSize') || query.get('size')) || 10))
  const start = (pageNum - 1) * pageSize
  return { list: items.slice(start, start + pageSize), total: items.length, pageNum, pageSize, pages: Math.ceil(items.length / pageSize) }
}

async function readJson(request) {
  const { buffer, tooLarge } = await readRequestBuffer(request, 1024 * 1024)
  if (tooLarge) return null
  if (!buffer.length) return {}
  try { return JSON.parse(buffer.toString('utf8')) }
  catch { return null }
}

async function readRequestBuffer(request, maxBytes) {
  const chunks = []
  let size = 0, tooLarge = false
  for await (const chunk of request) {
    size += chunk.length
    if (size > maxBytes) {
      tooLarge = true
      chunks.length = 0
    } else if (!tooLarge) chunks.push(chunk)
  }
  return { buffer: tooLarge ? Buffer.alloc(0) : Buffer.concat(chunks), tooLarge, size }
}

function parseMultipartFile(buffer, contentType) {
  const boundaryMatch = contentType.match(/boundary=(?:"([^"]+)"|([^;]+))/i)
  const boundary = boundaryMatch?.[1] || boundaryMatch?.[2]?.trim()
  if (!boundary) return null
  const marker = Buffer.from(`--${boundary}`)
  const separator = Buffer.from(`\r\n--${boundary}`)
  let markerAt = buffer.indexOf(marker)
  while (markerAt >= 0) {
    const headerStart = markerAt + marker.length + 2
    if (buffer.subarray(markerAt + marker.length, markerAt + marker.length + 2).toString() === '--') return null
    const headerEnd = buffer.indexOf(Buffer.from('\r\n\r\n'), headerStart)
    if (headerEnd < 0) return null
    const nextPart = buffer.indexOf(separator, headerEnd + 4)
    if (nextPart < 0) return null
    const headers = buffer.subarray(headerStart, headerEnd).toString('utf8')
    const disposition = headers.match(/content-disposition:\s*form-data;([^\r\n]+)/i)?.[1] || ''
    const fieldName = disposition.match(/(?:^|;)\s*name="([^"]*)"/i)?.[1]
    const filename = disposition.match(/(?:^|;)\s*filename="([^"]*)"/i)?.[1]
    if (fieldName === 'file' && filename !== undefined) {
      const partType = headers.match(/content-type:\s*([^\r\n]+)/i)?.[1]?.trim().toLowerCase() || ''
      return { filename: filename.replace(/[\\/]/g, '_'), contentType: partType, buffer: buffer.subarray(headerEnd + 4, nextPart) }
    }
    markerAt = buffer.indexOf(marker, nextPart + 2)
  }
  return null
}

function currentRole(request) {
  const value = request.headers.authorization || ''
  return value.startsWith('Bearer ') ? identitiesByToken.get(value.slice(7))?.role || null : null
}

function currentIdentity(request) {
  const value = request.headers.authorization || ''
  return value.startsWith('Bearer ') ? identitiesByToken.get(value.slice(7)) || null : null
}

function accountForRole(role, name) {
  return registeredAccounts[role].find((account) => account.name === name) || null
}

function registrationError(role, body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return '注册请求格式无效'
  const name = role === 'rider' ? body.name : body.username
  if (typeof name !== 'string' || !/^[A-Za-z0-9_-]{1,50}$/.test(name)) return '登录名格式不正确'
  if (typeof body.password !== 'string' || body.password.length < 8 || body.password.length > 20) return '密码长度必须为8到20位'
  if (typeof body.confirmPassword !== 'string' || body.confirmPassword.length < 8 || body.confirmPassword.length > 20 || body.confirmPassword !== body.password) return '两次输入的密码不一致'
  if (typeof body.phone !== 'string' || !/^(?:(?:\+|00)86)?1[3-9]\d{9}$/.test(body.phone.trim())) return '手机号格式不正确'
  if (role === 'merchant' && (typeof body.merchantName !== 'string' || !body.merchantName.trim() || body.merchantName.length > 255)) return '商家名称不能为空且不能超过255个字符'
  if (role === 'merchant' && ['address', 'pictureURL', 'description'].some((field) => body[field] != null && (typeof body[field] !== 'string' || body[field].length > 255))) return '店铺资料不能超过255个字符'
  if (accountForRole(role, name)) return '登录名已经存在'
  if (registeredAccounts[role].some((account) => account.phone === body.phone.trim())) return '手机号已经存在'
  if (role === 'merchant' && [...merchantProfiles.values()].some((profile) => profile.phone === body.phone.trim())) return '手机号已经存在'
  return ''
}

function registerAccount(role, body) {
  const error = registrationError(role, body)
  if (error) return { error }
  const id = nextAccountId.value++
  const name = role === 'rider' ? body.name.trim() : body.username.trim()
  const account = {
    role, id, name, phone: body.phone.trim(), password: body.password,
    token: `qa-${role}-registered-${id}`,
  }
  registeredAccounts[role].push(account)
  identitiesByToken.set(account.token, { role, id })
  if (role === 'merchant') {
    const shopId = nextShopId.value++
    const profile = {
      merchantId: id, shopId, merchantName: body.merchantName.trim(), address: body.address?.trim() || '',
      pictureURL: body.pictureURL?.trim() || '', description: body.description?.trim() || '', phone: body.phone.trim(),
      longitude: null, latitude: null, status: 1, openingTime: null, closingTime: null,
    }
    merchantShopByAccount.set(id, shopId)
    merchantProfiles.set(id, profile)
    shops.push({
      id: shopId, merchantId: id, merchantName: profile.merchantName, address: profile.address,
      pictureURL: profile.pictureURL, description: profile.description, status: 1, statusDesc: '已打烊',
      openingTime: null, closingTime: null,
    })
    categories.push({ id: nextCategoryId.value++, merchantId: id, categoryName: '默认分类', isDefault: true, active: true })
  }
  return { account }
}

function getProduct(productId) {
  return products.find((product) => product.id === Number(productId)) || null
}

function statusDescription(status) {
  return ({ 0: '正在销售', 1: '已经下架', 2: '已售罄' })[status] || '未知状态'
}

function publicProductVO(product) {
  return {
    id: product.id, productName: product.productName, imageUrl: product.imageUrl,
    inStock: product.stock > 0, status: product.status,
    statusDesc: statusDescription(product.status), price: product.price,
  }
}

function merchantProductVO(product) {
  return {
    id: product.id,
    categoryName: categories.find((category) => category.id === product.categoryId)?.categoryName || '未知分类',
    productName: product.productName, price: product.price, stock: product.stock,
    status: product.status, statusDesc: statusDescription(product.status),
    description: product.description, imageUrl: product.imageUrl, version: product.version,
  }
}

function publicShopVO(shop) {
  const grouped = new Map()
  for (const product of products) {
    if (product.merchantId !== shop.merchantId || product.deleted || ![0, 2].includes(product.status)) continue
    const category = categories.find((item) => item.id === product.categoryId)
    const group = grouped.get(product.categoryId) || {
      categoryId: product.categoryId, categoryName: category?.categoryName || '未知分类', products: [],
    }
    group.products.push(publicProductVO(product))
    grouped.set(product.categoryId, group)
  }
  return {
    id: shop.id, merchantName: shop.merchantName, pictureURL: shop.pictureURL,
    description: shop.description, address: shop.address, openingTime: shop.openingTime,
    closingTime: shop.closingTime, status: shop.status, statusDesc: shop.statusDesc,
    categories: [...grouped.values()],
  }
}

function findPublicShopOrMerchantAlias(rawId) {
  const id = Number(rawId)
  const byShopId = shops.find((shop) => shop.id === id)
  if (byShopId) return byShopId
  const shopId = merchantShopByAccount.get(id)
  return shopId == null ? null : shops.find((shop) => shop.id === shopId) || null
}

function profileForMerchant(merchantId) {
  return merchantProfiles.get(Number(merchantId)) || null
}

function syncProfileToShop(profile) {
  const shop = shops.find((item) => item.id === profile.shopId)
  if (!shop) return
  for (const field of ['merchantName', 'address', 'pictureURL', 'description', 'openingTime', 'closingTime', 'status']) shop[field] = profile[field]
  shop.statusDesc = profile.status === 0 ? '营业中' : '已打烊'
}

function updateProfile(profile, body) {
  const allowed = ['merchantName', 'address', 'longitude', 'latitude', 'phone', 'description', 'pictureURL', 'openingTime', 'closingTime']
  const fields = Object.keys(body || {}).filter((field) => allowed.includes(field) && body[field] !== null && body[field] !== undefined)
  if (!fields.length) return '至少提供一个需要更新的字段'
  for (const field of ['merchantName', 'address', 'phone', 'description', 'pictureURL']) {
    if (Object.hasOwn(body, field) && typeof body[field] !== 'string') return `${field}格式不正确`
  }
  if (Object.hasOwn(body, 'merchantName') && (!body.merchantName.trim() || body.merchantName.length > 255)) return '商家名称不能为空白且不能超过255个字符'
  if (Object.hasOwn(body, 'address') && (!body.address.trim() || body.address.length > 255)) return '店铺地址不能为空白且不能超过255个字符'
  if (Object.hasOwn(body, 'description') && body.description.length > 255) return '店铺简介长度不能超过255个字符'
  if (Object.hasOwn(body, 'pictureURL') && body.pictureURL.length > 255) return '图片URL长度不能超过255个字符'
  if (Object.hasOwn(body, 'phone') && (!body.phone.trim() || body.phone.length > 20 || !/^(?:(?:\+|00)86)?1[3-9]\d{9}$/.test(body.phone))) return '手机格式不正确，请重新输入'
  if ((body.longitude == null) !== (body.latitude == null)) return '经纬度必须同时提供'
  if (body.longitude != null || body.latitude != null) {
    const longitude = Number(body.longitude), latitude = Number(body.latitude)
    const decimalsOk = (value) => /^-?(?:0|[1-9]\d*)(?:\.\d{1,7})?$/.test(String(value))
    if (!decimalsOk(body.longitude) || !decimalsOk(body.latitude)) return '经纬度最多保留7位小数'
    if (longitude < -180 || longitude > 180) return '经度必须在-180到180之间'
    if (latitude < -85.05112878 || latitude > 85.05112878) return '纬度超出Redis GEO支持范围'
  }
  for (const field of ['openingTime', 'closingTime']) {
    if (Object.hasOwn(body, field) && body[field] != null && !/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(String(body[field]))) return `${field}格式不正确`
  }
  if (Object.hasOwn(body, 'phone') && [...merchantProfiles.values()].some((item) => item.merchantId !== profile.merchantId && item.phone === body.phone)) return '手机号已被其他商家使用'
  for (const field of fields) profile[field] = field === 'longitude' || field === 'latitude' ? Number(body[field]) : body[field]
  syncProfileToShop(profile)
  return ''
}

function notifyMerchantOrder(merchantId) {
  const client = merchantEventClients.get(Number(merchantId))
  if (client && !client.response.destroyed) client.response.write('event: NEW_ORDER\ndata: refresh\n\n')
}

function customerOrderListItem(order) {
  const products = order.items.map((item) => item.productName).join('、')
  return {
    id: order.id, orderNo: order.orderNo, merchantName: order.merchantName,
    totalAmount: order.totalAmount, productSummary: products,
    status: order.status, statusDesc: order.statusDesc, createTime: order.createTime,
  }
}

function merchantOrderListItem(order) {
  const products = order.items.map((item) => item.productName).join('、')
  return {
    orderId: order.id, orderNo: order.orderNo, receiverName: order.receiverName,
    totalAmount: order.totalAmount, productSummary: products,
    status: order.status, statusDesc: order.statusDesc, createTime: order.createTime,
    merchantSeen: order.merchantSeen,
  }
}

function customerOrderDetail(order) {
  const { merchantAccountId, requestId, merchantSeen, ...detail } = order
  return {
    ...detail,
    items: detail.items.map((item) => ({ ...item })),
  }
}

function merchantOrderDetail(order) {
  return {
    orderId: order.id,
    orderNo: order.orderNo,
    receiverName: order.receiverName,
    receiverPhone: order.receiverPhone,
    receiverAddress: order.receiverAddress,
    originalAmount: order.originalAmount,
    discountAmount: order.discountAmount,
    totalAmount: order.totalAmount,
    status: order.status,
    statusDesc: order.statusDesc,
    createTime: order.createTime,
    payTime: order.payTime,
    remark: order.remark,
    items: order.items.map((item) => ({ ...item })),
  }
}

function taskListItem(task) {
  const { taskId, merchantName, merchantAddress, receiverAddress, deliveryReward, status, statusDesc, createTime } = task
  return { taskId, merchantName, merchantAddress, receiverAddress, deliveryReward, status, statusDesc, createTime, pickupDistanceKm: riderLocation ? 0.8 : null }
}

function setOrderStatus(order, status) {
  const text = ['待支付', '正在支付', '已支付', '已完成', '已取消', '商家正在制作菜品', '菜品制作完成，等待骑手接单', '骑手正在配送', '骑手已经送达']
  order.status = status
  order.statusDesc = text[status] || '未知状态'
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url || '/', 'http://127.0.0.1')
  const path = url.pathname.replace(/\/$/, '') || '/'
  const method = request.method || 'GET'
  const contentType = String(request.headers['content-type'] || '')
  let body = {}, multipartFile = null, multipartTooLarge = false
  if (['POST', 'PUT', 'PATCH'].includes(method)) {
    if (/^multipart\/form-data\b/i.test(contentType)) {
      const parsed = await readRequestBuffer(request, MAX_PRODUCT_IMAGE_SIZE + 128 * 1024)
      multipartTooLarge = parsed.tooLarge
      if (!parsed.tooLarge) multipartFile = parseMultipartFile(parsed.buffer, contentType)
    } else body = await readJson(request)
  }

  if (method === 'OPTIONS') {
    response.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS', 'access-control-allow-headers': 'Authorization,Content-Type' })
    response.end()
    return
  }

  if (method === 'GET' && path === '/images/default-product.svg') {
    response.writeHead(200, { 'content-type': 'image/svg+xml; charset=utf-8', 'cache-control': 'no-store' })
    response.end('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 120"><rect width="160" height="120" rx="16" fill="#eef2e9"/><circle cx="80" cy="52" r="27" fill="#d9e5d5"/><path d="M42 99c7-17 22-25 38-25s31 8 38 25" fill="#c9d9c7"/><path d="M63 52h34M80 35v34" stroke="#6c8a71" stroke-width="5" stroke-linecap="round"/></svg>')
    return
  }

  if (method === 'GET' && path.startsWith('/uploads/products/')) {
    const image = uploadedImages.get(path)
    if (!image) return fail(response, '商品图片不存在', 404)
    response.writeHead(200, { 'content-type': image.contentType, 'cache-control': 'no-store' })
    response.end(image.buffer)
    return
  }

  const loginRole = path === '/user/login' ? 'customer'
    : path === '/merchant/login' ? 'merchant'
      : path === '/rider/login' ? 'rider' : null
  if (method === 'POST' && loginRole) {
    if (body === null) return fail(response, '登录请求格式无效', 400)
    const accountName = loginRole === 'rider' ? body.name : body.username
    const registered = typeof accountName === 'string' ? accountForRole(loginRole, accountName) : null
    if (registered && registered.password !== body.password) return fail(response, '登录名或密码不正确', 401)
    const id = registered?.id ?? userByRole[loginRole]
    const token = registered?.token ?? tokenByRole[loginRole]
    const data = loginRole === 'rider'
      ? { id, name: registered?.name || body.name || 'test_rider', token }
      : loginRole === 'customer'
        ? { id, nickname: registered?.name || body.username || 'test_customer', token }
        : { id, token }
    return send(response, 200, data)
  }

  const registrationRole = method === 'POST' && path === '/user/register' ? 'customer'
    : method === 'POST' && path === '/merchant/register' ? 'merchant'
      : method === 'POST' && path === '/rider/register' ? 'rider' : null
  if (registrationRole) {
    const result = registerAccount(registrationRole, body)
    if (result.error) return fail(response, result.error, 400)
    return send(response, 200, 'success')
  }

  const role = currentRole(request)
  const requireRole = (expected) => {
    if (role === expected) return true
    fail(response, '登录已失效或角色不匹配', 401)
    return false
  }

  if (method === 'GET' && path === '/merchant/events') {
    if (!requireRole('merchant')) return
    const identity = currentIdentity(request)
    const previous = merchantEventClients.get(identity.id)
    if (previous) {
      clearInterval(previous.heartbeat)
      if (!previous.response.destroyed) previous.response.end()
    }
    const client = { response, heartbeat: null }
    merchantEventClients.set(identity.id, client)
    response.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    response.write('event: CONNECTED\ndata: connected\n\n')
    client.heartbeat = setInterval(() => {
      if (!response.destroyed) response.write(': heartbeat\n\n')
    }, 15000)
    client.heartbeat.unref?.()
    response.on('close', () => {
      clearInterval(client.heartbeat)
      if (merchantEventClients.get(identity.id) === client) merchantEventClients.delete(identity.id)
    })
    return
  }

  if (path === '/merchant/info' && method === 'PUT') {
    if (!requireRole('merchant')) return
    if (body === null || typeof body !== 'object' || Array.isArray(body)) return fail(response, '店铺资料格式无效', 400)
    const profile = profileForMerchant(currentIdentity(request).id)
    if (!profile) return fail(response, '商户不存在', 404)
    if (profile.status !== 1) return fail(response, '商家信息更新失败：当前状态不允许修改信息', 409)
    const error = updateProfile(profile, body)
    if (error) return fail(response, error, 400)
    return send(response, 200, {
      merchantName: profile.merchantName, address: profile.address,
      longitude: profile.longitude, latitude: profile.latitude, phone: profile.phone,
      description: profile.description, pictureURL: profile.pictureURL,
      status: profile.status, statusDesc: profile.status === 0 ? '营业中' : '已打烊',
      openingTime: profile.openingTime, closingTime: profile.closingTime,
    })
  }
  if (path === '/merchant/info' && method === 'PATCH') {
    if (!requireRole('merchant')) return
    const profile = profileForMerchant(currentIdentity(request).id)
    if (!profile) return fail(response, '商户不存在', 404)
    const status = url.searchParams.get('status')
    if (!['0', '1'].includes(status)) return fail(response, '商家营业状态不合法', 400)
    profile.status = Number(status)
    syncProfileToShop(profile)
    return send(response, 200, 'success')
  }

  if (method === 'GET' && path === '/api/customer/shops') {
    const name = (url.searchParams.get('name') || '').toLocaleLowerCase()
    const status = url.searchParams.has('status') ? Number(url.searchParams.get('status')) : 0
    const matches = shops.filter((shop) => shop.status === status && shop.merchantName.toLocaleLowerCase().includes(name))
      .map((shop) => ({
        id: shop.id, merchantName: shop.merchantName, pictureURL: shop.pictureURL,
        address: shop.address, description: shop.description, status: shop.status, statusDesc: shop.statusDesc,
      }))
    return send(response, 200, page(matches, url.searchParams))
  }
  if (method === 'GET' && path.startsWith('/api/customer/shops/')) {
    const shop = findPublicShopOrMerchantAlias(path.split('/').at(-1))
    return shop ? send(response, 200, publicShopVO(shop)) : fail(response, '店铺不存在')
  }

  if (path === '/user/browse-history' || path.startsWith('/user/browse-history/')) {
    if (!requireRole('customer')) return
    const customerId = currentIdentity(request).id
    const history = browseHistoryByCustomer.get(customerId) || []
    if (method === 'GET' && path === '/user/browse-history') {
      const recent = history.map(getProduct).filter(Boolean).map(publicProductVO)
      return send(response, 200, recent)
    }
    if (method === 'DELETE' && path === '/user/browse-history') {
      browseHistoryByCustomer.delete(customerId)
      return send(response, 200, '清空成功')
    }
    const match = path.match(/^\/user\/browse-history\/(\d+)$/)
    if (method === 'POST' && match) {
      const product = getProduct(match[1])
      if (!product) return fail(response, '商品不存在', 404)
      const next = [product.id, ...history.filter((id) => id !== product.id)].slice(0, 50)
      browseHistoryByCustomer.set(customerId, next)
      return send(response, 200, publicProductVO(product))
    }
  }

  if (path.startsWith('/cart/items')) {
    if (!requireRole('customer')) return
    if (method === 'GET' && path === '/cart/items') {
      const items = cartItems.map((line) => {
        const product = getProduct(line.productId)
        if (!product) return null
        const shop = shops.find((item) => item.merchantId === product.merchantId)
        const available = !product.deleted && product.status === 0 && product.stock >= line.quantity && shop?.status === 0
        return {
          id: line.id, productId: product.id, productName: product.productName,
          productImage: product.imageUrl, price: product.price, quantity: line.quantity,
          subtotal: Number((product.price * line.quantity).toFixed(2)), available,
          disableReason: available ? null : product.deleted ? '商品已删除' : shop?.status !== 0 ? '店铺已打烊' : product.stock < line.quantity ? '库存不足' : '商品暂不可售',
        }
      })
      const visibleItems = items.filter(Boolean)
      const totalAmount = Number(visibleItems.reduce((sum, item) => sum + item.subtotal, 0).toFixed(2))
      const canBuy = visibleItems.length > 0 && visibleItems.every((item) => item.available)
      return send(response, 200, { items: visibleItems, totalAmount, canBuy, invalidReason: canBuy ? '' : visibleItems.length ? '购物车中有商品暂时无法购买。' : '购物车还是空的，去店铺里选几样喜欢的吧。' })
    }
    if (method === 'POST' && path === '/cart/items') {
      const product = getProduct(body?.productId)
      if (!product || product.deleted) return fail(response, '商品不存在')
      const shop = shops.find((item) => item.merchantId === product.merchantId)
      if (shop?.status !== 0) return fail(response, '店铺当前已打烊')
      if (product.status !== 0 || product.stock <= 0) return fail(response, '商品当前暂不可售')
      const existingShop = cartItems.length ? getProduct(cartItems[0].productId)?.merchantId : null
      if (existingShop && existingShop !== product.merchantId) return fail(response, '只能加入同一家店的商品')
      const line = cartItems.find((item) => item.productId === product.id)
      if (line && line.quantity >= product.stock) return fail(response, '商品库存不足')
      if (line) line.quantity += 1
      else cartItems.push({ id: nextCartId++, productId: product.id, quantity: 1 })
      return send(response, 200, 'success')
    }
    if (method === 'PATCH' && path === '/cart/items') {
      const line = cartItems.find((item) => item.id === Number(body?.cartItemId))
      const change = Number(body?.quantityChange)
      if (!line || ![-1, 1].includes(change)) return fail(response, '购物车商品或数量不合法', 400)
      const product = getProduct(line.productId)
      if (change > 0 && (!product || product.deleted || product.stock < line.quantity + 1)) return fail(response, '商品库存不足')
      line.quantity += change
      if (line.quantity <= 0) cartItems = cartItems.filter((item) => item !== line)
      return send(response, 200, 'success')
    }
    if (method === 'DELETE' && path === '/cart/items/all') {
      cartItems = []
      return send(response, 200, 'success')
    }
    if (method === 'DELETE' && path === '/cart/items') {
      const ids = new Set((url.searchParams.get('ids') || '').split(',').map(Number))
      cartItems = cartItems.filter((item) => !ids.has(item.id))
      return send(response, 200, 'success')
    }
  }

  if (path === '/order' || path.startsWith('/order/')) {
    if (!requireRole('customer')) return
    const customerId = currentIdentity(request).id
    if (method === 'POST' && path === '/order') {
      if (body === null) return fail(response, '下单请求格式无效', 400)
      if (body.longitude == null || body.latitude == null) return fail(response, '请提供收货地点经纬度', 400)
      if (typeof body.longitude !== 'number' || typeof body.latitude !== 'number' || !Number.isFinite(body.longitude) || !Number.isFinite(body.latitude)) return fail(response, '收货地点经纬度格式无效', 400)
      if (Math.abs(body.longitude) > 180 || Math.abs(body.latitude) > 90) return fail(response, '收货地点经纬度超出有效范围', 400)
      const duplicate = orders.find((order) => order.customerId === customerId && order.requestId === body.requestId)
      if (duplicate) return send(response, 200, { orderId: duplicate.id, orderNo: duplicate.orderNo })
      if (!cartItems.length) return fail(response, '购物车为空，无法创建订单', 2001)
      const firstProduct = getProduct(cartItems[0].productId)
      if (!firstProduct || firstProduct.deleted) return fail(response, '创建订单失败：商品信息已变化')
      const shop = shops.find((item) => item.merchantId === firstProduct.merchantId)
      if (!shop || shop.status !== 0) return fail(response, '创建订单失败：店铺当前已打烊')
      for (const line of cartItems) {
        const product = getProduct(line.productId)
        if (!product || product.deleted || product.merchantId !== firstProduct.merchantId || product.status !== 0 || product.stock < line.quantity) {
          return fail(response, '创建订单失败：商品状态或库存已变化')
        }
      }
      const items = cartItems.map((line) => {
        const product = getProduct(line.productId)
        return { productId: product.id, productName: product.productName, productPicture: product.imageUrl, productPrice: product.price, quantity: line.quantity, subtotal: Number((product.price * line.quantity).toFixed(2)) }
      })
      const total = Number(items.reduce((sum, item) => sum + item.subtotal, 0).toFixed(2))
      const order = {
        id: nextOrderId++, orderNo: `QA-${nextOrderId - 1}`, merchantId: shop.id,
        merchantAccountId: firstProduct.merchantId, merchantName: shop.merchantName, customerId,
        receiverName: body.receiverName, receiverPhone: body.receiverPhone, receiverAddress: body.receiverAddress,
        longitude: body.longitude, latitude: body.latitude,
        originalAmount: total, discountAmount: 0, totalAmount: total, status: 0,
        statusDesc: '待支付', createTime: now(), payTime: null, remark: body.remark || '',
        merchantSeen: false, requestId: body.requestId, items,
      }
      for (const line of cartItems) {
        const product = getProduct(line.productId)
        product.stock -= line.quantity
        product.version += 1
        if (product.stock === 0) product.status = 2
      }
      orders.unshift(order)
      cartItems = []
      return send(response, 200, { orderId: order.id, orderNo: order.orderNo })
    }
    if (method === 'GET' && path === '/order') {
      const own = orders.filter((order) => order.customerId === customerId)
      return send(response, 200, page(own.map(customerOrderListItem), url.searchParams))
    }
    const id = Number(path.split('/')[2])
    const order = orders.find((item) => item.id === id && item.customerId === customerId)
    if (!order) return fail(response, '订单不存在')
    if (method === 'GET' && path === `/order/${id}`) return send(response, 200, customerOrderDetail(order))
    if (method === 'PATCH' && path === `/order/${id}/pay`) {
      if (order.status !== 0) return fail(response, '当前订单状态不能支付')
      order.payTime = now(); setOrderStatus(order, 2)
      notifyMerchantOrder(order.merchantAccountId || 22)
      return send(response, 200, '支付成功')
    }
    if (method === 'PATCH' && path === `/order/${id}/cancel`) {
      if (order.status !== 0) return fail(response, '当前订单状态不能取消')
      setOrderStatus(order, 4)
      return send(response, 200, '取消成功')
    }
    if (method === 'PATCH' && path === `/order/${id}/confirm`) {
      if (order.status !== 8) return fail(response, '当前订单状态不能确认收货')
      setOrderStatus(order, 3)
      return send(response, 200, '确认收货成功')
    }
  }

  if (path === '/category' || path.startsWith('/category/')) {
    if (!requireRole('merchant')) return
    const merchantId = currentIdentity(request).id
    const ownCategories = () => categories.filter((category) => category.merchantId === merchantId && category.active)

    if (path === '/category' && method === 'GET') {
      return send(response, 200, ownCategories().map(({ id, categoryName }) => ({ id, categoryName })))
    }
    if (path === '/category' && method === 'POST') {
      const categoryName = String(url.searchParams.get('categoryName') || '').trim()
      if (!categoryName) return fail(response, '分类名称不能为空', 400)
      if (categoryName.length > 15) return fail(response, '分类名称长度不能超过15个字符', 400)
      if (ownCategories().some((category) => category.categoryName === categoryName)) return fail(response, '已经有重复分类，无法创建')
      const created = { id: nextCategoryId.value++, merchantId, categoryName, isDefault: false, active: true }
      categories.push(created)
      return send(response, 200, { id: created.id, categoryName, statusDesc: '正常使用' })
    }
    const categoryMatch = path.match(/^\/category\/(\d+)$/)
    if (categoryMatch) {
      const category = categories.find((item) => item.id === Number(categoryMatch[1]) && item.merchantId === merchantId && item.active)
      if (!category) return fail(response, '分类种类不存在或不属于商家')
      if (method === 'GET') return send(response, 200, { id: category.id, categoryName: category.categoryName })
      if (method === 'DELETE') {
        if (category.isDefault) return fail(response, '默认分类不能删除')
        const defaultCategory = ownCategories().find((item) => item.isDefault)
        if (!defaultCategory) return fail(response, '默认分类不存在')
        for (const product of products) {
          if (product.merchantId === merchantId && product.categoryId === category.id) {
            product.categoryId = defaultCategory.id
            product.version += 1
          }
        }
        category.active = false
        return send(response, 200, '删除成功')
      }
    }

    if (path === '/category/products' && method === 'GET') {
      const status = url.searchParams.has('status') ? Number(url.searchParams.get('status')) : null
      const categoryId = url.searchParams.has('categoryId') ? Number(url.searchParams.get('categoryId')) : null
      const keyword = (url.searchParams.get('keyword') || '').trim().toLocaleLowerCase()
      const rows = products.filter((product) => product.merchantId === merchantId && !product.deleted
        && (status === null || product.status === status)
        && (categoryId === null || product.categoryId === categoryId)
        && product.productName.toLocaleLowerCase().includes(keyword))
        .sort((left, right) => right.id - left.id)
        .map(merchantProductVO)
      return send(response, 200, page(rows, url.searchParams))
    }
    if (path === '/category/products' && method === 'POST') {
      if (!body || typeof body !== 'object' || Array.isArray(body)) return fail(response, '商品请求格式无效', 400)
      const categoryId = Number(body.categoryId)
      const category = ownCategories().find((item) => item.id === categoryId)
      const priceText = String(body.price ?? '')
      const pricePattern = /^\d{1,8}(?:\.\d{1,2})?$/
      const stock = Number(body.stock)
      const productName = typeof body.productName === 'string' ? body.productName.trim() : ''
      const description = body.description == null ? '' : String(body.description)
      if (!productName || productName.length > 15) return fail(response, '商品名称不能为空且不能超过15个字符', 400)
      if (!pricePattern.test(priceText) || Number(priceText) <= 0) return fail(response, '商品价格必须大于0，最多8位整数和2位小数', 400)
      if (body.stock == null || !Number.isInteger(stock) || stock < 0) return fail(response, '库存数量不能为负数', 400)
      if (!category) return fail(response, '种类不存在')
      if (description.length > 100) return fail(response, '商品描述长度不能超过100个字符', 400)
      if (String(body.imageUrl || '').length > 255) return fail(response, '商品图片URL长度不能超过255个字符', 400)
      if (products.some((item) => item.merchantId === merchantId && !item.deleted && item.productName === productName)) return fail(response, '当前店铺已存在同名商品')
      const created = {
        id: nextProductId.value++, merchantId, categoryId, productName, description,
        price: Number(priceText), stock, status: 1,
        imageUrl: String(body.imageUrl || '/images/default-product.svg').trim(), version: 0, stockVersion: 0, deleted: false,
      }
      products.push(created)
      return send(response, 200, merchantProductVO(created))
    }

    if (path === '/category/products/image' && method === 'POST') {
      if (multipartTooLarge) return fail(response, '图片大小不得超过5MB', 400)
      if (!multipartFile) return fail(response, '图片文件不能为空', 400)
      if (multipartFile.buffer.length === 0) return send(response, 200, null)
      if (multipartFile.buffer.length > MAX_PRODUCT_IMAGE_SIZE) return fail(response, '图片大小不得超过5MB', 400)
      const extension = multipartFile.filename.split('.').at(-1)?.toLowerCase() || ''
      const allowed = (multipartFile.contentType === 'image/jpeg' && ['jpg', 'jpeg'].includes(extension))
        || (multipartFile.contentType === 'image/png' && extension === 'png')
      if (!allowed) return fail(response, '不支持的类型', 400)
      const imageUrl = `/uploads/products/qa-${uploadedImageSequence++}.${extension}`
      uploadedImages.set(imageUrl, { buffer: multipartFile.buffer, contentType: multipartFile.contentType })
      return send(response, 200, imageUrl)
    }

    const productMatch = path.match(/^\/category\/products\/(\d+)(?:\/(edit|on-shelf|off-shelf))?$/)
    if (productMatch) {
      const id = Number(productMatch[1])
      const action = productMatch[2] || ''
      const product = products.find((item) => item.id === id && item.merchantId === merchantId && !item.deleted)
      if (!product) return fail(response, '商品不存在或不属于当前商家')
      if (method === 'GET' && action === 'edit') return send(response, 200, merchantProductVO(product))
      if (method === 'GET' && !action) return send(response, 200, publicProductVO(product))
      if (method === 'PATCH' && !action) {
        if (!body || typeof body !== 'object' || Array.isArray(body)) return fail(response, '商品修改请求格式无效', 400)
        const version = Number(body.version)
        if (body.version == null || !Number.isInteger(version) || version < 0) return fail(response, '商品版本号不能为空', 400)
        if (version !== product.version) return fail(response, '商品信息已更新，请重新读取后再修改', 409)
        if (Object.hasOwn(body, 'productName')) {
          if (typeof body.productName !== 'string' || !body.productName.trim() || body.productName.length > 15) return fail(response, '商品名称不得为空且不能超过15个字符', 400)
          const nextName = body.productName.trim()
          if (products.some((item) => item.id !== product.id && item.merchantId === merchantId && !item.deleted && item.productName === nextName)) return fail(response, '当前店铺已存在同名商品')
          product.productName = nextName
        }
        if (Object.hasOwn(body, 'description')) {
          if (body.description != null && String(body.description).length > 100) return fail(response, '商品描述长度不能超过100个字符', 400)
          if (body.description != null) product.description = String(body.description)
        }
        if (Object.hasOwn(body, 'price') && body.price != null) {
          const priceText = String(body.price)
          if (!/^\d{1,8}(?:\.\d{1,2})?$/.test(priceText) || Number(priceText) > 99999.99) return fail(response, '商品价格不能超过99999.99元且最多保留2位小数', 400)
          product.price = Number(priceText)
        }
        if (Object.hasOwn(body, 'categoryId') && body.categoryId != null) {
          const category = ownCategories().find((item) => item.id === Number(body.categoryId))
          if (!category) return fail(response, '分类不存在或不可用')
          product.categoryId = category.id
        }
        if (Object.hasOwn(body, 'imageUrl') && body.imageUrl != null) {
          if (String(body.imageUrl).length > 255) return fail(response, '商品图片URL长度不能超过255个字符', 400)
          product.imageUrl = String(body.imageUrl).trim() || '/images/default-product.svg'
        }
        if (Object.hasOwn(body, 'stock') && body.stock != null) {
          const stock = Number(body.stock)
          if (!Number.isInteger(stock) || stock < 0 || stock > 10000) return fail(response, '库存重设范围为0至10000', 400)
          product.stock = stock
          product.stockVersion += 1
          if (product.status !== 1) product.status = stock > 0 ? 0 : 2
        }
        product.version += 1
        return send(response, 200, merchantProductVO(product))
      }
      if (method === 'PATCH' && action === 'on-shelf') {
        if (product.status !== 0 && product.status !== 1 && product.status !== 2) return fail(response, '当前状态不允许上架')
        const targetStatus = product.stock > 0 ? 0 : 2
        if (product.status !== targetStatus) { product.status = targetStatus; product.version += 1 }
        return send(response, 200, merchantProductVO(product))
      }
      if (method === 'PATCH' && action === 'off-shelf') {
        if (![0, 1, 2].includes(product.status)) return fail(response, '当前状态不允许下架')
        if (product.status !== 1) { product.status = 1; product.version += 1 }
        return send(response, 200, merchantProductVO(product))
      }
      if (method === 'DELETE' && !action) {
        product.deleted = true
        return send(response, 200, '删除成功')
      }
    }
  }

  if (method === 'POST' && path.startsWith('/merchant/restore/')) {
    if (!requireRole('merchant')) return
    const merchantId = currentIdentity(request).id
    const id = Number(path.split('/').at(-1))
    const product = products.find((item) => item.id === id && item.merchantId === merchantId && item.deleted)
    if (!product) return fail(response, '商品不存在、未删除或不属于当前商家')
    if (products.some((item) => item !== product && item.merchantId === merchantId && !item.deleted && item.productName === product.productName)) return fail(response, '当前店铺已存在同名商品')
    product.deleted = false
    product.status = 1
    product.version += 1
    return send(response, 200, null)
  }

  if (path === '/merchant/orders' || path.startsWith('/merchant/orders/')) {
    if (!requireRole('merchant')) return
    const merchantId = currentIdentity(request).id
    if (method === 'GET' && path === '/merchant/orders/unseen') {
      const ids = orders.filter((order) => order.merchantAccountId === merchantId && order.status === 2 && !order.merchantSeen).map((order) => order.id)
      return send(response, 200, ids)
    }
    if (method === 'PATCH' && path === '/merchant/orders/seen') {
      if (!Array.isArray(body)) return fail(response, '订单编号列表格式无效', 400)
      const requestedIds = new Set(body.map(Number).filter(Number.isInteger))
      for (const order of orders) {
        if (order.merchantAccountId === merchantId && requestedIds.has(order.id)) order.merchantSeen = true
      }
      return send(response, 200, 'success')
    }
    if (method === 'GET' && path === '/merchant/orders') {
      const type = url.searchParams.get('type')
      const statuses = type === 'PENDING' ? [2] : type === 'PROCESSING' ? [5] : type === 'HISTORY' ? [6, 7, 8, 3] : []
      const result = orders.filter((order) => order.merchantAccountId === merchantId && statuses.includes(order.status)).map(merchantOrderListItem)
      return send(response, 200, page(result, url.searchParams))
    }
    const match = path.match(/^\/merchant\/orders\/(\d+)(?:\/(accept|reject|ready))?$/)
    if (!match) return fail(response, '订单路径无效', 404)
    const [, rawId, action] = match
    const order = orders.find((item) => item.id === Number(rawId) && item.merchantAccountId === merchantId)
    if (!order) return fail(response, '订单不存在或不属于当前商家')
    if (method === 'GET' && !action) return send(response, 200, merchantOrderDetail(order))
    if (method !== 'PATCH' || !action) return fail(response, '不支持的请求', 404)
    if (action === 'accept' && order.status === 2) setOrderStatus(order, 5)
    else if (action === 'reject' && order.status === 2) setOrderStatus(order, 4)
    else if (action === 'ready' && order.status === 5) {
      setOrderStatus(order, 6)
      if (!tasks.some((task) => task.orderId === order.id)) tasks.unshift(makeTask(nextTaskId++, order))
    } else return fail(response, '当前订单状态已变化，请刷新后再试')
    return send(response, 200, 'success')
  }

  if (path.startsWith('/rider/location')) {
    if (!requireRole('rider')) return
    if (method === 'PUT' && path === '/rider/location') {
      const longitude = Number(body?.longitude), latitude = Number(body?.latitude)
      if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return fail(response, '位置参数无效', 400)
      riderLocation = { longitude, latitude }
      return send(response, 200, '位置更新成功')
    }
    if (method === 'GET' && path === '/rider/location') return send(response, 200, riderLocation)
    if (method === 'DELETE' && path === '/rider/location') {
      riderLocation = null
      return send(response, 200, '位置删除成功')
    }
  }

  if (path.startsWith('/rider/delivery-tasks')) {
    if (!requireRole('rider')) return
    const riderId = currentIdentity(request).id
    if (method === 'GET' && path === '/rider/delivery-tasks/available') {
      if (!riderLocation) return fail(response, '请先上报当前位置', 400)
      return send(response, 200, tasks.filter((task) => task.status === 0).map(taskListItem))
    }
    if (method === 'GET' && path === '/rider/delivery-tasks/current') {
      return send(response, 200, tasks.filter((task) => task.status === 1 && task.riderId === riderId).map(taskListItem))
    }
    const match = path.match(/^\/rider\/delivery-tasks\/(\d+)(?:\/(claim|complete))?$/)
    if (!match) return fail(response, '配送任务路径无效', 404)
    const [, rawId, action] = match
    const task = tasks.find((item) => item.taskId === Number(rawId))
    if (!task) return fail(response, '配送任务不存在')
    if (method === 'GET' && !action) {
      const { riderId, ...detail } = task
      return send(response, 200, detail)
    }
    if (method !== 'PATCH' || !action) return fail(response, '不支持的请求', 404)
    if (action === 'claim' && task.status === 0) {
      task.status = 1; task.statusDesc = '骑手正在配送'; task.riderId = riderId; task.acceptedTime = now()
      setOrderStatus(orders.find((order) => order.id === task.orderId), 7)
      return send(response, 200, '抢单成功')
    }
    if (action === 'complete' && task.status === 1 && task.riderId === riderId) {
      task.status = 2; task.statusDesc = '骑手已经完成配送'; task.deliveredTime = now()
      setOrderStatus(orders.find((order) => order.id === task.orderId), 8)
      return send(response, 200, '配送完成')
    }
    return fail(response, '任务状态已变化，请刷新后再试')
  }

  return fail(response, '本地 QA fixture 未实现此请求', 404)
})

if (process.env.TAKEOUT_UI_QA_FIXTURE === '1') {
  server.on('error', (error) => {
    console.error(`UI QA fixture could not listen on 127.0.0.1:${PORT}: ${error.message}`)
    process.exitCode = 1
  })
  server.listen(PORT, '127.0.0.1', () => {
    console.log(`UI QA fixture ready at http://127.0.0.1:${PORT}`)
  })
}
