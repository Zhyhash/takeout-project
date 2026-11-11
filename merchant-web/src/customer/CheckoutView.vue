<template>
  <section class="customer-page">
    <div class="checkout-topline"><el-button text @click="back">← 返回购物车</el-button><el-tag v-if="pendingIntent" type="warning" effect="plain">有一次下单尚未确认</el-tag></div>
    <header class="page-heading checkout-heading"><div><p class="eyebrow">ONE MORE STEP</p><h1>{{ pendingIntent ? '继续确认这次下单' : '填写收货信息' }}</h1><p class="muted">请确认收货信息，订单提交后会再次核对商品状态。</p></div></header>

    <div v-if="!session" class="surface checkout-message"><h2>请先登录</h2><p>登录后才能安全保存和提交订单。</p><el-button type="primary" @click="goLogin">前往登录</el-button></div>
    <div v-else-if="intentBlocked" class="surface checkout-message"><h2>无法读取上次下单信息</h2><p>为避免重复创建订单，请先核对订单列表。只有确认没有对应订单后，才能解除这条不可读记录。</p><div class="recovery-actions"><el-button type="primary" plain @click="reviewOrders">查看我的订单</el-button><el-button v-if="reviewedUnreadable" type="warning" :loading="archiving" @click="abandonUnreadable">我已核对并继续</el-button></div></div>
    <div v-else-if="pendingIntent?.orderId" class="surface checkout-message"><h2>这笔订单已经创建</h2><p>已保存订单编号，结算页不会再次提交。请前往详情继续查看或支付。</p><el-button type="primary" @click="openKnownOrder()">查看订单详情</el-button></div>
    <div v-else-if="rejection" class="surface rejection-message"><h2>订单没有创建</h2><p>{{ rejection }}</p><div class="recovery-actions"><el-button type="primary" @click="editAfterConfirmedFailure">确认并修改信息</el-button><el-button text @click="retryOrder">保留原信息再试一次</el-button></div></div>
    <el-alert v-else-if="cartError && !pendingIntent" :title="cartError" type="error" show-icon :closable="false" class="checkout-alert"><el-button text type="primary" @click="loadCart">重试</el-button></el-alert>
    <div v-else class="checkout-grid">
      <section class="surface checkout-form-surface">
        <div v-if="pendingIntent" class="pending-note"><strong>上次提交结果还未确认。</strong><span>再次提交会沿用完全相同的收货信息，继续核对这笔订单。若要放弃，请先核对订单列表。</span><div class="recovery-actions"><el-button size="small" plain @click="reviewOrders">核对订单后再修改</el-button><el-button v-if="reviewedIntentId === pendingIntent.requestId" size="small" type="warning" :loading="archiving" @click="abandonReviewedIntent">我已核对，放弃这次请求</el-button></div></div>
        <div v-if="recoveryIntent && !pendingIntent" class="pending-note recovery-note"><strong>你有一条已保存的未确认下单信息。</strong><span>可以恢复原信息继续核对，避免重试时重复下单。</span><el-button size="small" plain :loading="archiving" @click="restorePreviousIntent">恢复原下单信息</el-button></div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item label="收货人" prop="receiverName"><el-input v-model="form.receiverName" placeholder="怎么称呼你" :disabled="Boolean(pendingIntent) || submitting" maxlength="20" /></el-form-item>
          <el-form-item label="联系电话" prop="receiverPhone"><el-input v-model="form.receiverPhone" type="tel" inputmode="tel" autocomplete="tel" placeholder="用于配送联系" :disabled="Boolean(pendingIntent) || submitting" maxlength="20" /></el-form-item>
          <el-form-item label="收货地址" prop="receiverAddress"><el-input v-model="form.receiverAddress" type="textarea" :rows="3" placeholder="填写街道、小区和门牌号" :disabled="Boolean(pendingIntent) || submitting" maxlength="255" show-word-limit /></el-form-item>
          <fieldset class="delivery-coordinates">
            <legend>收货地点坐标（手动填写）</legend>
            <p>填写收货地址对应的经纬度，用于计算配送距离和费用。测试时可填入示例，再按需修改。</p>
            <el-button size="small" plain :disabled="Boolean(pendingIntent) || submitting" @click="fillExampleCoordinates">填入杭州示例坐标</el-button>
            <div class="coordinate-fields">
              <el-form-item label="经度" prop="longitude"><el-input v-model="form.longitude" type="number" inputmode="decimal" min="-180" max="180" step="any" placeholder="例如 120.1536" :disabled="Boolean(pendingIntent) || submitting" /></el-form-item>
              <el-form-item label="纬度" prop="latitude"><el-input v-model="form.latitude" type="number" inputmode="decimal" min="-90" max="90" step="any" placeholder="例如 30.2812" :disabled="Boolean(pendingIntent) || submitting" /></el-form-item>
            </div>
            <small>示例：经度 120.1536，纬度 30.2812（杭州）。请勿用于实际配送。</small>
            <el-alert v-if="pendingIntent && (pendingIntent.payload.longitude == null || pendingIntent.payload.latitude == null)" title="这条旧请求未提供坐标。请先核对订单列表，再通过上方“我已核对，放弃这次请求”恢复填写，补齐坐标后重新提交。" type="warning" :closable="false" show-icon />
          </fieldset>
          <el-form-item label="订单备注（选填）" prop="remark"><el-input v-model="form.remark" type="textarea" :rows="2" placeholder="口味偏好、配送提示等" :disabled="Boolean(pendingIntent) || submitting" maxlength="200" show-word-limit /></el-form-item>
        </el-form>
        <el-alert v-if="submitError" :title="submitError" type="error" show-icon :closable="false" class="submit-error"><template #default><p>如果提交结果暂时无法确认，请保留这次下单信息并重试，系统会继续核对同一笔订单。</p></template></el-alert>
      </section>

      <aside class="surface checkout-summary">
        <p class="eyebrow">ORDER REVIEW</p><h2>订单确认</h2>
        <div v-if="pendingIntent" class="frozen-summary"><div class="summary-line"><span>收货人</span><strong>{{ pendingIntent.payload.receiverName }}</strong></div><div class="summary-line"><span>联系电话</span><strong>{{ pendingIntent.payload.receiverPhone }}</strong></div><div class="summary-line address-line"><span>收货地址</span><strong>{{ pendingIntent.payload.receiverAddress }}</strong></div><div class="summary-line"><span>经度 / 纬度</span><strong>{{ pendingIntent.payload.longitude ?? '未提供' }} / {{ pendingIntent.payload.latitude ?? '未提供' }}</strong></div></div>
        <div v-else-if="cart" class="checkout-items">
          <div v-for="item in cart.items || []" :key="item.id" class="checkout-item"><img :src="item.productImage || defaultImage" :alt="item.productName" @error="useFallback" /><div><strong>{{ item.productName }}</strong><span>× {{ item.quantity }}</span></div><b>¥{{ amount(item.subtotal ?? Number(item.price) * Number(item.quantity)) }}</b></div>
          <el-alert v-if="cart.canBuy !== true" :title="cart.invalidReason || '购物车中有商品暂时无法购买。'" type="warning" show-icon :closable="false" />
        </div>
        <div v-else-if="loadingCart" class="checkout-loading"><el-skeleton animated :rows="4" /></div>
        <div class="summary-total"><span>商品金额</span><strong>¥{{ amount(pendingIntent ? lockedTotal : cart?.totalAmount) }}</strong></div>
        <p class="simulation-note">提交后进入订单详情，再确认模拟支付；不会扣除真实款项。</p>
        <el-button type="primary" size="large" class="submit-order" :loading="submitting" :disabled="archiving || (!pendingIntent && (!cart?.items?.length || cart?.canBuy !== true || loadingCart))" @click="submitOrder">{{ pendingIntent ? '使用原信息重试' : '提交订单' }}</el-button>
      </aside>
    </div>
  </section>
</template>

<script setup>
import {computed, onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {useRoute, useRouter} from 'vue-router'
import {createOrder, getCart} from '../api/customerApi.js'
import {getSession} from '../auth.js'
import {
  deliveryCoordinateError,
  EXAMPLE_DELIVERY_COORDINATES,
  normalizeDeliveryCoordinates
} from './checkoutCoordinates.js'
import {
  archiveCheckoutIntent,
  beginCheckoutAttempt,
  createCheckoutSubmitLock,
  isDefinitelyRejectedCreate,
  markCheckoutAttemptUncertain,
  readArchivedCheckoutIntent,
  readCheckoutIntent,
  recordCreatedOrder,
  restoreArchivedCheckoutIntent,
  settleKnownCheckoutRejection,
  writeCheckoutIntent
} from './checkoutIntent.js'

const defaultImage = '/images/default-product.svg'
const route = useRoute()
const router = useRouter()
const session = computed(() => getSession('customer'))
const formRef = ref(null)
const form = reactive({ receiverName: '', receiverPhone: '', receiverAddress: '', longitude: '', latitude: '', remark: '' })
const cart = ref(null)
const pendingIntent = ref(null)
const lockedTotal = ref(null)
const intentBlocked = ref(false)
const loadingCart = ref(false)
const cartError = ref('')
const submitting = ref(false)
const submitError = ref('')
const rejection = ref('')
const recoveryIntent = ref(null)
const reviewedIntentId = ref(null)
const reviewedUnreadable = ref(false)
const archiving = ref(false)
const submitLock = createCheckoutSubmitLock()
let mounted = false
let componentGeneration = 0
let cartRequestSequence = 0

const rules = {
  receiverName: [{ required: true, whitespace: true, message: '请填写收货人姓名', trigger: 'blur' }, { max: 20, message: '收货人姓名最多20个字符', trigger: 'blur' }],
  receiverPhone: [
    { required: true, whitespace: true, message: '请填写联系电话', trigger: 'blur' },
    { pattern: /^(?:(?:\+|00)86)?1[3-9]\d{9}$/, message: '请输入有效的中国大陆手机号', trigger: 'blur' },
  ],
  receiverAddress: [{ required: true, whitespace: true, message: '请填写详细收货地址', trigger: 'blur' }, { max: 255, message: '地址最多255个字符', trigger: 'blur' }],
  longitude: [{ required: true, validator: coordinateValidator('longitude'), trigger: 'blur' }],
  latitude: [{ required: true, validator: coordinateValidator('latitude'), trigger: 'blur' }],
  remark: [{ max: 200, message: '备注最多200个字符', trigger: 'blur' }],
}

function coordinateValidator(axis) {
  return (_rule, value, callback) => {
    const error = deliveryCoordinateError(value, axis)
    callback(error ? new Error(error) : undefined)
  }
}

function fillExampleCoordinates() {
  if (pendingIntent.value || submitting.value) return
  Object.assign(form, EXAMPLE_DELIVERY_COORDINATES)
  formRef.value?.clearValidate(['longitude', 'latitude'])
}

onMounted(() => {
  mounted = true
  hydrateSession()
})

watch(() => session.value?.token, (token, previousToken) => {
  if (token === previousToken) return
  componentGeneration += 1
  cartRequestSequence += 1
  submitLock.reset()
  cart.value = null
  pendingIntent.value = null
  lockedTotal.value = null
  intentBlocked.value = false
  loadingCart.value = false
  cartError.value = ''
  submitting.value = false
  submitError.value = ''
  rejection.value = ''
  recoveryIntent.value = null
  reviewedIntentId.value = null
  reviewedUnreadable.value = false
  archiving.value = false
  Object.assign(form, { receiverName: '', receiverPhone: '', receiverAddress: '', longitude: '', latitude: '', remark: '' })
  if (mounted) hydrateSession()
})

onUnmounted(() => { mounted = false; componentGeneration += 1; cartRequestSequence += 1 })

function hydrateSession() {
  if (!session.value) return
  const epoch = componentGeneration
  const token = session.value?.token
  if (!token) return
  const saved = readCheckoutIntent(session.value.id)
  pendingIntent.value = saved
  if (pendingIntent.value?.unreadable) {
    pendingIntent.value = null
    intentBlocked.value = true
    reviewedUnreadable.value = route.query.reviewedUnreadable === '1'
    return
  }
  if (pendingIntent.value) {
    Object.assign(form, { longitude: '', latitude: '' }, pendingIntent.value.payload, { remark: pendingIntent.value.payload.remark ?? '' })
    lockedTotal.value = pendingIntent.value.totalAmount ?? null
    if (pendingIntent.value.orderId) {
      void openKnownOrder(epoch, token)
      return
    }
    reviewedIntentId.value = route.query.reviewedIntent === pendingIntent.value.requestId ? pendingIntent.value.requestId : null
    return
  }
  recoveryIntent.value = readArchivedCheckoutIntent(session.value.id)
  void loadCart()
}

function isCurrent(epoch, token) {
  return mounted && epoch === componentGeneration && token === session.value?.token
}

async function loadCart() {
  if (!session.value || pendingIntent.value) return
  const sequence = ++cartRequestSequence
  const epoch = componentGeneration
  const token = session.value.token
  loadingCart.value = true
  cartError.value = ''
  try {
    const result = await getCart()
    if (isCurrent(epoch, token) && sequence === cartRequestSequence) cart.value = result
  } catch (cause) {
    if (isCurrent(epoch, token) && sequence === cartRequestSequence) cartError.value = cause.message || '无法读取购物车，请稍后重试。'
  } finally {
    if (isCurrent(epoch, token) && sequence === cartRequestSequence) loadingCart.value = false
  }
}

async function submitOrder() {
  if (!session.value || intentBlocked.value) return
  const lockLease = submitLock.acquire()
  if (!lockLease) return
  const epoch = componentGeneration
  const token = session.value.token
  submitting.value = true
  submitError.value = ''
  let intent = pendingIntent.value
  try {
    if (!intent) {
      if (cart.value?.canBuy !== true || !cart.value?.items?.length) {
        ElMessage.warning(cart.value?.invalidReason || '购物车中有商品暂时无法购买。')
        return
      }
      const valid = await formRef.value?.validate().catch(() => false)
      if (!isCurrent(epoch, token) || !valid) return
      const payload = {
        receiverName: form.receiverName.trim(),
        receiverPhone: form.receiverPhone.trim(),
        receiverAddress: form.receiverAddress.trim(),
        ...normalizeDeliveryCoordinates(form),
        remark: form.remark === '' ? null : form.remark.trim(),
      }
      intent = writeCheckoutIntent(session.value.id, payload, cart.value?.totalAmount ?? null)
      pendingIntent.value = intent
      lockedTotal.value = cart.value?.totalAmount
    }
    if (intent.orderId) {
      await openKnownOrder(epoch, token)
      return
    }
    intent = beginCheckoutAttempt(session.value.id, intent)
    pendingIntent.value = intent
    const created = await createOrder(intent.payload)
    if (!isCurrent(epoch, token)) return
    const orderId = created?.orderId
    if (orderId === undefined || orderId === null) throw new Error('订单已提交，但响应中没有订单编号。请在订单列表中核对后再重试。')
    // Persist the returned id before navigation. An existing intent with this id can never POST again.
    try {
      pendingIntent.value = recordCreatedOrder(session.value.id, intent, orderId)
    } catch (storageError) {
      pendingIntent.value = { ...intent, orderId: String(orderId) }
      submitError.value = `${storageError.message || '无法保存订单编号。'} 已创建的订单仍会打开；请不要返回结算页重提。`
    }
    if (!isCurrent(epoch, token)) return
    ElMessage.success(`订单 ${created.orderNo || orderId} 已创建，请核对订单详情并继续。`)
    await router.replace({ name: 'order-detail', params: { orderId }, query: { from: 'checkout' } })
  } catch (cause) {
    if (!isCurrent(epoch, token)) return
    if (intent && isDefinitelyRejectedCreate(cause, intent)) {
      try {
        pendingIntent.value = settleKnownCheckoutRejection(session.value.id, intent)
        rejection.value = `${cause.message || '本次下单未成功。'} 订单未创建。`
        submitError.value = ''
      } catch {
        pendingIntent.value = markCheckoutAttemptUncertain(session.value.id, intent)
        submitError.value = '无法保存这次下单状态。请先核对订单列表，再保留原信息重试。'
      }
    } else {
      if (intent) pendingIntent.value = markCheckoutAttemptUncertain(session.value.id, intent)
      submitError.value = `${cause.message || '提交结果尚未确认。'} 请保留原信息再次提交。`
    }
  } finally {
    if (isCurrent(epoch, token)) submitting.value = false
    submitLock.release(lockLease)
  }
}

async function openKnownOrder(epoch = componentGeneration, token = session.value?.token) {
  if (!isCurrent(epoch, token) || !pendingIntent.value?.orderId) return
  await router.replace({ name: 'order-detail', params: { orderId: pendingIntent.value.orderId }, query: { from: 'checkout' } })
}

function reviewOrders() {
  if (pendingIntent.value?.requestId) {
    router.push({ name: 'orders', query: { reviewCheckout: pendingIntent.value.requestId } })
  } else {
    router.push({ name: 'orders', query: { reviewUnreadable: '1' } })
  }
}

async function editAfterConfirmedFailure() {
  if (!pendingIntent.value || archiving.value) return
  const epoch = componentGeneration
  const token = session.value?.token
  try {
    await ElMessageBox.confirm('已确认这次订单没有创建。原收货信息会保存起来；你可以修改信息后重新下单，也可以稍后恢复原信息。', '确认订单未创建', { confirmButtonText: '确认并修改', cancelButtonText: '保留原信息', type: 'warning' })
    if (!isCurrent(epoch, token)) return
    archiving.value = true
    recoveryIntent.value = archiveCheckoutIntent(session.value.id, pendingIntent.value, 'server-rejected')
    pendingIntent.value = null
    rejection.value = ''
    submitError.value = ''
    await loadCart()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause.message || '无法安全保存原请求，仍保留在结算页。')
  } finally { if (isCurrent(epoch, token)) archiving.value = false }
}

async function retryOrder() {
  if (rejection.value) { rejection.value = ''; await submitOrder() }
}

async function abandonReviewedIntent() {
  if (!pendingIntent.value || reviewedIntentId.value !== pendingIntent.value.requestId || archiving.value) return
  const epoch = componentGeneration
  const token = session.value?.token
  try {
    await ElMessageBox.confirm('如果订单列表中没有这笔订单，你可以放弃本次提交并恢复填写。原收货信息会保存在恢复区；若订单稍后出现，仍可恢复原信息继续核对。', '放弃并恢复填写', { confirmButtonText: '我已核对，放弃本次提交', cancelButtonText: '返回重试原信息', type: 'warning' })
    if (!isCurrent(epoch, token)) return
    archiving.value = true
    recoveryIntent.value = archiveCheckoutIntent(session.value.id, pendingIntent.value, 'customer-reviewed')
    pendingIntent.value = null
    reviewedIntentId.value = null
    await loadCart()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause.message || '无法保存原请求，仍保留在结算页。')
  } finally { if (isCurrent(epoch, token)) archiving.value = false }
}

async function abandonUnreadable() {
  if (!reviewedUnreadable.value || archiving.value || !session.value) return
  const epoch = componentGeneration
  const token = session.value.token
  try {
    await ElMessageBox.confirm('结算页无法读取上一条下单记录。请确认你已检查订单列表，并愿意继续承担重复下单风险。损坏的原始记录会放入恢复区。', '解除结算保护', { confirmButtonText: '我已核对，继续结算', cancelButtonText: '先保留保护', type: 'warning' })
    if (!isCurrent(epoch, token)) return
    archiving.value = true
    archiveCheckoutIntent(session.value.id, null, 'customer-reviewed-unreadable')
    intentBlocked.value = false
    reviewedUnreadable.value = false
    recoveryIntent.value = readArchivedCheckoutIntent(session.value.id)
    await loadCart()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause.message || '无法安全解除结算保护。')
  } finally { if (isCurrent(epoch, token)) archiving.value = false }
}

async function restorePreviousIntent() {
  if (!session.value || archiving.value) return
  const epoch = componentGeneration
  const token = session.value.token
  archiving.value = true
  try {
    if (!isCurrent(epoch, token)) return
    pendingIntent.value = restoreArchivedCheckoutIntent(session.value.id)
    recoveryIntent.value = null
    Object.assign(form, { longitude: '', latitude: '' }, pendingIntent.value.payload, { remark: pendingIntent.value.payload.remark ?? '' })
    lockedTotal.value = pendingIntent.value.totalAmount ?? null
    if (pendingIntent.value.orderId) await openKnownOrder()
  } catch (cause) { ElMessage.error(cause.message || '无法恢复原请求。') }
  finally { if (isCurrent(epoch, token)) archiving.value = false }
}

function back() {
  if (pendingIntent.value) {
    router.push({ name: 'orders' })
    return
  }
  router.push({ name: 'cart' })
}

function goLogin() { router.push({ name: 'customer-login', query: { redirect: route.fullPath } }) }
function amount(value) { const n = Number(value); return value !== null && value !== undefined && Number.isFinite(n) ? n.toFixed(2) : '—' }
function useFallback(event) { if (!event.target.src.endsWith(defaultImage)) event.target.src = defaultImage }
</script>

<style scoped>
.customer-page { max-width:1120px; margin:0 auto; padding:26px clamp(15px,4vw,40px) 48px; }
.checkout-topline { display:flex; align-items:center; justify-content:space-between; min-height:35px; }
.checkout-heading { margin:16px 0 22px; }
.checkout-heading .eyebrow { margin:0 0 5px; }
.checkout-heading h1 { margin:0 0 8px; font-size:clamp(29px,4vw,39px); letter-spacing:-.035em; }
.checkout-heading .muted { margin:0; }
.checkout-grid { display:grid; grid-template-columns:minmax(0,1.15fr) minmax(300px,.85fr); gap:18px; align-items:start; }
.checkout-form-surface,.checkout-summary { padding:clamp(17px,3vw,28px); }
.checkout-summary { position:sticky; top:20px; }
.checkout-summary .eyebrow { margin:0 0 5px; }
.checkout-summary h2 { margin:0 0 16px; font-size:20px; }
.pending-note { display:grid; gap:5px; margin-bottom:20px; padding:13px 15px; border-radius:10px; background:#fbf4e6; color:#745521; font-size:13px; line-height:1.55; }
.recovery-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:5px; }
.recovery-note { background:#eef4e9; color:#3f6347; }
.rejection-message { max-width:680px; margin:28px auto; padding:26px; text-align:center; }
.rejection-message h2 { margin:0 0 8px; }
.rejection-message p { margin:0 auto 15px; max-width:560px; color:#748077; line-height:1.6; }
.checkout-items { display:grid; gap:13px; }
.checkout-item { display:grid; grid-template-columns:48px minmax(0,1fr) auto; gap:11px; align-items:center; }
.checkout-item img { width:48px; height:48px; object-fit:cover; border-radius:9px; background:#eff1e9; }
.checkout-item>div { display:grid; gap:4px; min-width:0; }
.checkout-item strong { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; font-size:13px; }
.checkout-item span { color:#829087; font-size:12px; }
.checkout-item>b { font-size:13px; white-space:nowrap; }
.summary-total { display:flex; justify-content:space-between; align-items:center; border-top:1px solid #e7ebe3; padding-top:16px; margin-top:17px; font-size:14px; }
.summary-total strong { font-size:21px; }
.simulation-note { margin:12px 0; color:#7c887f; font-size:12px; line-height:1.5; }
.submit-order { width:100%; }
.submit-error { margin-top:15px; }
.submit-error p { margin:3px 0 0; font-size:12px; line-height:1.5; }
.frozen-summary { display:grid; gap:11px; }
.summary-line { display:flex; justify-content:space-between; gap:14px; font-size:13px; }
.summary-line span { color:#7c887f; flex-shrink:0; }
.summary-line strong { text-align:right; overflow-wrap:anywhere; }
.address-line { align-items:flex-start; }
.checkout-loading { padding:10px 0 20px; }
.checkout-message { max-width:560px; margin:34px auto; padding:28px; text-align:center; }
.checkout-message p { color:#748077; }
.checkout-alert { margin-bottom:18px; }
.delivery-coordinates { min-width:0; margin:0 0 22px; padding:16px; border:1px solid #dce5da; border-radius:12px; background:#f7f9f3; }
.delivery-coordinates legend { padding:0 6px; font-size:14px; font-weight:650; color:#244e3c; }
.delivery-coordinates p,.delivery-coordinates small { display:block; color:#748077; font-size:12px; line-height:1.6; }
.delivery-coordinates p { margin:0 0 10px; }
.delivery-coordinates small { margin-top:0; }
.delivery-coordinates .el-alert { margin-top:12px; }
.coordinate-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; margin-top:16px; }
@media(max-width:420px) { .coordinate-fields { grid-template-columns:1fr; gap:0; } }
@media(max-width:760px) { .checkout-grid { grid-template-columns:1fr; } .checkout-summary { position:static; order:-1; } }
</style>
