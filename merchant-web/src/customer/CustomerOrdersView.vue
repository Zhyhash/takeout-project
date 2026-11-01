<template>
  <section class="customer-page">
    <header class="page-heading orders-heading">
      <div><p class="eyebrow">YOUR ORDERS</p><h1>我的订单</h1><p class="muted">每一单的进度，都可以在这里查看。</p></div>
      <el-button text @click="router.push({ name: 'shops' })">← 继续逛店</el-button>
    </header>

    <el-alert v-if="reviewCheckout || reviewUnreadable" type="warning" show-icon :closable="false" class="review-alert">
      <template #title>请先核对这次下单是否已出现在订单列表中</template>
      <template #default><p>目前无法直接根据这次提交记录定位订单。确认列表中没有对应订单后，才能在结算页放弃本次提交；若不确定，请保留原信息并重试。</p><el-button type="warning" plain :disabled="loading || Boolean(error)" @click="returnToReviewedCheckout">我已核对订单，返回结算</el-button></template>
    </el-alert>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="orders-alert"><el-button text type="primary" @click="loadOrders">重新加载</el-button></el-alert>

    <div v-loading="loading" class="orders-list" aria-live="polite" :aria-busy="loading">
      <article v-for="order in orders" :key="order.id" class="order-card surface">
        <div class="order-card-top"><div><span class="order-store-mark" aria-hidden="true">食</span><strong>{{ order.merchantName || '店铺订单' }}</strong></div><el-tag :type="statusTag(order.status)" effect="plain">{{ order.statusDesc || '订单状态' }}</el-tag></div>
        <button class="order-summary" @click="openOrder(order.id)">
          <span class="order-summary-main"><strong>{{ order.productSummary || '查看商品详情' }}</strong><small>订单号 {{ order.orderNo || order.id }}</small></span>
          <span class="order-price"><strong>¥{{ amount(order.totalAmount) }}</strong><small>{{ formatTime(order.createTime) }}</small></span>
        </button>
        <div class="order-card-bottom"><span class="order-time">{{ formatTime(order.createTime) }}</span><el-button size="small" plain @click="openOrder(order.id)">查看订单 <span aria-hidden="true">→</span></el-button></div>
      </article>

      <div v-if="!loading && !orders.length && !error" class="surface orders-empty">
        <span aria-hidden="true">📦</span><h2>还没有订单</h2><p class="muted">选一家喜欢的店，开启第一单吧。</p><el-button type="primary" @click="router.push({ name: 'shops' })">发现好店</el-button>
      </div>
    </div>

    <footer v-if="total > 0" class="orders-pagination"><span class="muted">共 {{ total }} 笔订单</span><el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev, pager, next" :pager-count="5" @current-change="loadOrders" /></footer>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listOrders } from '../api/customerApi.js'
import { getSession } from '../auth.js'

const route = useRoute()
const router = useRouter()
const session = computed(() => getSession('customer'))
const orders = ref([])
const pageNum = ref(1)
const pageSize = 10
const total = ref(0)
const loading = ref(false)
const error = ref('')
let requestSequence = 0
let mounted = false
const reviewCheckout = computed(() => typeof route.query.reviewCheckout === 'string' ? route.query.reviewCheckout : '')
const reviewUnreadable = computed(() => route.query.reviewUnreadable === '1')

onMounted(() => { mounted = true; if (session.value) void loadOrders() })
onUnmounted(() => { mounted = false; requestSequence += 1 })
watch(() => session.value?.token, (token, previousToken) => {
  if (token === previousToken) return
  requestSequence += 1
  orders.value = []
  total.value = 0
  error.value = ''
  loading.value = false
  pageNum.value = 1
  if (mounted && token) void loadOrders()
})

async function loadOrders() {
  if (!session.value) return goLogin()
  const sequence = ++requestSequence
  const token = session.value.token
  loading.value = true
  error.value = ''
  try {
    const data = await listOrders(pageNum.value, pageSize)
    if (!mounted || sequence !== requestSequence || token !== session.value?.token) return
    orders.value = data?.list ?? []
    total.value = Number(data?.total ?? 0)
  } catch (cause) {
    if (mounted && sequence === requestSequence && token === session.value?.token) { orders.value = []; error.value = cause.message || '订单列表加载失败。' }
  } finally {
    if (mounted && sequence === requestSequence && token === session.value?.token) loading.value = false
  }
}

function returnToReviewedCheckout() {
  if (loading.value || error.value) return
  if (reviewCheckout.value) router.push({ name: 'checkout', query: { reviewedIntent: reviewCheckout.value } })
  else router.push({ name: 'checkout', query: { reviewedUnreadable: '1' } })
}

function openOrder(id) { router.push({ name: 'order-detail', params: { orderId: id }, query: { from: 'orders' } }) }
function goLogin() { router.push({ name: 'customer-login', query: { redirect: route.fullPath } }) }
function amount(value) { const n = Number(value); return Number.isFinite(n) ? n.toFixed(2) : '0.00' }
function formatTime(value) {
  if (!value) return '下单时间以详情为准'
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(0, 16) : text
}
function statusTag(status) {
  if ([2, 5, 7].includes(status)) return 'warning'
  if ([3, 6, 8].includes(status)) return 'success'
  if (status === 4) return 'info'
  return 'primary'
}
</script>

<style scoped>
.customer-page { max-width:960px; margin:0 auto; padding:clamp(25px,5vw,54px) clamp(15px,4vw,40px) 48px; }
.orders-heading { display:flex; align-items:center; justify-content:space-between; gap:15px; margin-bottom:22px; }
.orders-heading h1 { margin:0 0 7px; font-size:clamp(30px,4vw,40px); letter-spacing:-.035em; }
.orders-heading .muted { margin:0; }
.orders-alert { margin-bottom:16px; }
.review-alert { margin:0 0 16px; }
.review-alert p { max-width:680px; margin:4px 0 11px; line-height:1.6; }
.orders-list { display:grid; gap:14px; min-height:200px; }
.order-card { padding:18px 20px; }
.order-card-top { display:flex; align-items:center; justify-content:space-between; gap:10px; padding-bottom:14px; border-bottom:1px solid #edf0e9; }
.order-card-top>div { display:flex; align-items:center; gap:9px; font-size:14px; }
.order-store-mark { display:grid; place-items:center; width:30px; height:30px; border-radius:9px; color:#35634c; background:#edf2e8; font-size:13px; }
.order-summary { width:100%; display:flex; align-items:flex-start; justify-content:space-between; gap:14px; padding:17px 0 14px; color:inherit; border:0; background:none; text-align:left; cursor:pointer; }
.order-summary-main,.order-price { display:grid; gap:7px; }
.order-summary-main { min-width:0; }
.order-summary-main strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:14px; }
.order-summary-main small,.order-price small,.order-time { color:#869188; font-size:12px; }
.order-price { flex-shrink:0; justify-items:end; }
.order-price strong { font-size:17px; }
.order-card-bottom { display:flex; align-items:center; justify-content:space-between; gap:12px; border-top:1px solid #edf0e9; padding-top:12px; }
.orders-empty { text-align:center; padding:42px 20px; }
.orders-empty>span { font-size:40px; }
.orders-empty h2 { margin:10px 0 6px; }
.orders-empty p { margin:0 0 18px; }
.orders-pagination { display:flex; justify-content:space-between; align-items:center; gap:14px; padding-top:20px; }
@media(max-width:520px) { .customer-page { padding:24px 13px 36px; } .order-card { padding:15px; } .order-summary-main strong { white-space:normal; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; } .orders-pagination { align-items:flex-start; flex-direction:column; } }
</style>
