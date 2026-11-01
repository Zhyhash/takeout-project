<template>
  <section class="customer-page">
    <div class="detail-topline"><el-button text @click="back">← 返回订单</el-button><el-button text :loading="loading" @click="loadOrder">刷新状态</el-button></div>

    <el-alert v-if="error && !order" :title="error" type="error" show-icon :closable="false" class="detail-error"><el-button text type="primary" @click="loadOrder">重新加载</el-button></el-alert>
    <div v-else-if="loading && !order" class="surface detail-loading"><el-skeleton animated :rows="7" /></div>

    <template v-if="order">
      <header class="order-hero surface">
        <div><p class="eyebrow">ORDER STATUS</p><h1>{{ order.statusDesc || '订单详情' }}</h1><p class="muted">{{ order.merchantName || '店铺订单' }} · {{ order.orderNo }}</p></div>
        <div class="status-stamp" :class="{ cancelled: order.status === 4 }" aria-hidden="true">{{ stamp }}</div>
      </header>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="detail-error" />

      <div class="detail-grid">
        <section class="surface detail-main">
          <div class="detail-section-heading"><div><p class="eyebrow">YOUR MEAL</p><h2>商品明细</h2></div><span class="muted">{{ order.items?.length || 0 }} 种商品</span></div>
          <div v-if="order.items?.length" class="detail-items">
            <article v-for="(item,index) in order.items" :key="`${item.productId || item.productName}-${index}`" class="detail-item">
              <img :src="item.productPicture || defaultImage" :alt="item.productName || '商品图片'" @error="useFallback" />
              <div><strong>{{ item.productName || '商品' }}</strong><span>¥{{ amount(item.productPrice) }} × {{ item.quantity }}</span></div>
              <b>¥{{ amount(item.subtotal) }}</b>
            </article>
          </div>
          <div class="detail-total"><span>商品金额</span><strong>¥{{ amount(order.originalAmount) }}</strong></div>
          <div v-if="Number(order.discountAmount) > 0" class="detail-total discount"><span>优惠</span><strong>−¥{{ amount(order.discountAmount) }}</strong></div>
          <div class="detail-total payable"><span>{{ [0, 1].includes(order.status) ? '待付金额' : order.status === 4 ? '订单金额' : '实付金额' }}</span><strong>¥{{ amount(order.totalAmount) }}</strong></div>
        </section>

        <aside class="detail-side">
          <section class="surface address-card"><p class="eyebrow">DELIVERY</p><h2>送到这里</h2><p class="receiver-name">{{ order.receiverName }} <span>{{ order.receiverPhone }}</span></p><p class="receiver-address">{{ order.receiverAddress }}</p></section>
          <section class="surface info-card"><p class="eyebrow">ORDER INFO</p><h2>订单信息</h2><dl><div><dt>订单编号</dt><dd>{{ order.orderNo || order.id }}</dd></div><div><dt>下单时间</dt><dd>{{ formatTime(order.createTime) }}</dd></div><div><dt>备注</dt><dd>{{ order.remark || '无' }}</dd></div></dl></section>
          <div class="surface order-actions">
            <template v-if="order.status === 0">
              <el-button type="primary" :loading="acting === 'pay'" :disabled="actionsDisabled" @click="runAction('pay')">模拟支付 ¥{{ amount(order.totalAmount) }}</el-button>
              <el-button plain :loading="acting === 'cancel'" :disabled="actionsDisabled" @click="runAction('cancel')">取消订单</el-button>
              <p>支付仅用于本地流程演示，不会扣除真实款项。</p>
            </template>
            <template v-else-if="order.status === 8"><el-button type="primary" :loading="acting === 'confirm'" :disabled="actionsDisabled" @click="runAction('confirm')">确认收到餐品</el-button><p>确认后订单将进入完成状态。</p></template>
            <template v-else><span class="action-status"><i aria-hidden="true">✓</i>{{ order.statusDesc || '状态已更新' }}</span><p>{{ actionHint }}</p></template>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { cancelOrder, confirmOrder, getOrder, payOrder } from '../api/customerApi.js'
import { clearCheckoutIntent, readCheckoutIntent } from './checkoutIntent.js'
import { getSession } from '../auth.js'

const defaultImage = '/images/default-product.svg'
const route = useRoute()
const router = useRouter()
const session = computed(() => getSession('customer'))
const order = ref(null)
const loading = ref(false)
const acting = ref('')
const error = ref('')
let requestSequence = 0
let disposed = false

const stamp = computed(() => {
  if (order.value?.status === 4) return 'CANCEL'
  if (order.value?.status === 3) return 'DONE'
  if (order.value?.status === 8) return 'ARRIVED'
  return 'ON ITS WAY'
})
const actionHint = computed(() => {
  switch (order.value?.status) {
    case 1: return '支付正在处理中，请刷新状态。'
    case 2: return '店铺已收到订单，正在准备。'
    case 5: return '店铺正在为你准备餐品。'
    case 6: return '餐品已做好，正在等待骑手接单。'
    case 7: return '骑手正在配送途中。'
    case 3: return '这份餐点的旅程已经完成，感谢你的选择。'
    case 4: return '这笔订单已取消。'
    default: return '订单进度以店铺和骑手的最新处理为准。'
  }
})
const actionsDisabled = computed(() => Boolean(acting.value || loading.value || error.value || disposed))

watch(() => route.params.orderId, () => { order.value = null; void loadOrder() }, { immediate: true })
onUnmounted(() => { disposed = true; requestSequence += 1 })
watch(() => session.value?.token, (token, previousToken) => {
  if (token === previousToken) return
  requestSequence += 1
  order.value = null
  loading.value = false
  acting.value = ''
  error.value = ''
  if (!disposed && token) void loadOrder()
})

function isCurrent(sequence, token) {
  return !disposed && sequence === requestSequence && token === session.value?.token
}

async function loadOrder() {
  const sequence = ++requestSequence
  const token = session.value?.token
  loading.value = true
  error.value = ''
  try {
    const result = await getOrder(route.params.orderId)
    if (!isCurrent(sequence, token)) return
    order.value = result
    const pending = readCheckoutIntent(session.value?.id)
    if (pending?.orderId && String(pending.orderId) === String(result?.id)
        && ![0, 1].includes(result?.status)) {
      try { clearCheckoutIntent(session.value.id) } catch { /* A saved order id still prevents duplicate creation. */ }
    }
  } catch (cause) {
    if (isCurrent(sequence, token)) error.value = cause.message || '订单详情暂时无法加载。'
  } finally {
    if (isCurrent(sequence, token)) loading.value = false
  }
}

async function runAction(action) {
  if (!order.value || actionsDisabled.value) return
  const id = order.value.id ?? route.params.orderId
  const token = session.value?.token
  const sequence = ++requestSequence
  acting.value = action
  const messages = {
    pay: ['确认模拟支付', '这一步仅用于演示，不会产生真实扣款。'],
    cancel: ['确认取消订单', '取消后无法撤销。'],
    confirm: ['确认已经收到餐品', '确认后订单将完成。'],
  }
  try {
    await ElMessageBox.confirm(messages[action][1], messages[action][0], { confirmButtonText: '确认', cancelButtonText: '再想想', type: action === 'cancel' ? 'warning' : 'info' })
    if (!isCurrent(sequence, token)) return
    const operation = { pay: payOrder, cancel: cancelOrder, confirm: confirmOrder }[action]
    await operation(id)
    if (!isCurrent(sequence, token)) return
    ElMessage.success({ pay: '模拟支付成功', cancel: '订单已取消', confirm: '已确认收货' }[action])
    await loadOrder()
  } catch (cause) {
    if (!isCurrent(sequence, token) || cause === 'cancel' || cause === 'close') return
    // An action may have committed even if its response was lost. Re-read before asking the
    // customer to repeat it; the backend accepts only the state-specific transition.
    try {
      const latest = await getOrder(id)
      if (!isCurrent(sequence, token)) return
      order.value = latest
      error.value = ''
      const pending = readCheckoutIntent(session.value?.id)
      if (pending?.orderId && String(pending.orderId) === String(latest?.id)
          && ![0, 1].includes(latest?.status)) {
        try { clearCheckoutIntent(session.value.id) } catch { /* Keep the saved order id as a duplicate guard. */ }
      }
      const completed = action === 'pay' ? ![0, 1, 4].includes(latest.status)
        : action === 'cancel' ? latest.status === 4
          : latest.status === 3
      if (completed) { ElMessage.success('订单状态已更新。'); return }
      error.value = cause.message || '订单操作失败。请刷新状态后再试。'
    } catch {
      if (!isCurrent(sequence, token)) return
      error.value = cause.message || '无法确认操作结果，请刷新订单后再试。'
    }
  } finally {
    if (!disposed && token === session.value?.token) acting.value = ''
  }
}

function back() {
  if (route.query.from === 'checkout') { router.replace({ name: 'orders' }); return }
  if (route.query.from === 'orders') { router.push({ name: 'orders' }); return }
  router.back()
}
function amount(value) { const n = Number(value); return Number.isFinite(n) ? n.toFixed(2) : '0.00' }
function formatTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
function useFallback(event) { if (!event.target.src.endsWith(defaultImage)) event.target.src = defaultImage }
</script>

<style scoped>
.customer-page { max-width:1120px; margin:0 auto; padding:25px clamp(15px,4vw,40px) 50px; }
.detail-topline { display:flex; align-items:center; justify-content:space-between; margin-bottom:13px; }
.order-hero { display:flex; align-items:center; justify-content:space-between; gap:20px; padding:clamp(20px,4vw,32px); margin-bottom:18px; overflow:hidden; }
.order-hero .eyebrow { margin:0 0 7px; }
.order-hero h1 { margin:0 0 7px; font-size:clamp(25px,4vw,34px); }
.order-hero .muted { margin:0; overflow-wrap:anywhere; }
.status-stamp { flex-shrink:0; display:grid; place-items:center; width:92px; height:92px; border:1px solid #dbe7d9; border-radius:50%; color:#528160; background:#f0f5ec; font-size:10px; font-weight:800; letter-spacing:.06em; transform:rotate(-12deg); }
.status-stamp.cancelled { color:#8a7870; background:#f4f0ec; border-color:#e4dcd4; }
.detail-grid { display:grid; grid-template-columns:minmax(0,1.3fr) minmax(285px,.7fr); gap:18px; align-items:start; }
.detail-main,.address-card,.info-card,.order-actions { padding:clamp(17px,3vw,25px); }
.detail-section-heading { display:flex; justify-content:space-between; align-items:flex-end; margin-bottom:8px; }
.detail-section-heading .eyebrow,.address-card .eyebrow,.info-card .eyebrow { margin:0 0 5px; }
.detail-section-heading h2,.address-card h2,.info-card h2 { margin:0; font-size:18px; }
.detail-items { display:grid; }
.detail-item { display:grid; grid-template-columns:62px minmax(0,1fr) auto; align-items:center; gap:12px; padding:15px 0; border-bottom:1px solid #edf0e9; }
.detail-item img { width:62px; height:62px; object-fit:cover; border-radius:10px; background:#f0f1ea; }
.detail-item>div { display:grid; gap:6px; min-width:0; }
.detail-item>div strong { font-size:14px; }
.detail-item>div span { color:#7b887f; font-size:12px; }
.detail-item>b { font-size:13px; }
.detail-total { display:flex; justify-content:space-between; align-items:center; padding-top:14px; font-size:13px; color:#637168; }
.detail-total.discount { padding-top:10px; }
.detail-total.discount strong { color:#b26b53; }
.detail-total.payable { border-top:1px solid #e8ece5; margin-top:14px; padding-top:17px; color:#24382f; }
.detail-total.payable strong { font-size:21px; }
.detail-side { display:grid; gap:14px; }
.address-card h2,.info-card h2 { margin-bottom:15px; }
.receiver-name { margin:0 0 8px; font-size:14px; font-weight:700; }
.receiver-name span { margin-left:8px; color:#77837b; font-weight:400; }
.receiver-address { margin:0; font-size:13px; line-height:1.6; color:#55665b; overflow-wrap:anywhere; }
.info-card dl { display:grid; gap:12px; margin:0; }
.info-card dl>div { display:grid; grid-template-columns:76px minmax(0,1fr); gap:8px; font-size:12px; }
.info-card dt { color:#818d84; }
.info-card dd { margin:0; color:#45584c; overflow-wrap:anywhere; text-align:right; }
.order-actions { display:grid; gap:8px; }
.order-actions>.el-button { width:100%; margin:0; }
.order-actions>p { margin:3px 0 0; color:#859188; font-size:11px; line-height:1.5; text-align:center; }
.action-status { display:flex; justify-content:center; gap:8px; align-items:center; color:#3f7650; font-weight:700; font-size:14px; }
.action-status i { display:grid; place-items:center; width:22px; height:22px; border-radius:50%; background:#ebf3e8; font-style:normal; }
.detail-error { margin-bottom:15px; }
.detail-loading { padding:22px; }
@media(max-width:760px) { .detail-grid { grid-template-columns:1fr; } .detail-side { order:-1; } .status-stamp { width:72px; height:72px; font-size:9px; } }
</style>
