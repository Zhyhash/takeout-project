<template>
  <section class="customer-page">
    <header class="page-heading cart-heading">
      <div><p class="eyebrow">YOUR BAG</p><h1>购物车</h1><p class="muted">检查好喜欢的菜，再去结算。</p></div>
      <el-button text @click="router.push({ name: 'shops' })">← 继续逛店</el-button>
    </header>

    <div v-if="!session" class="surface empty-cart">
      <span aria-hidden="true">🛍</span><h2>登录后查看购物车</h2><p>购物车和下单记录会保存在你的账号里。</p>
      <el-button type="primary" @click="goLogin">登录并继续</el-button>
    </div>

    <template v-else>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="cart-alert"><el-button text type="primary" @click="loadCart">重新加载</el-button></el-alert>
      <div v-loading="loading" class="cart-layout" :aria-busy="loading">
        <section class="cart-items surface">
          <div class="cart-section-heading"><div><p class="eyebrow">YOUR ORDER</p><h2>本次想吃</h2></div><span v-if="items.length" class="cart-count">{{ items.reduce((sum, item) => sum + Number(item.quantity || 0), 0) }} 件</span></div>
          <div v-if="items.length" class="cart-list">
            <article v-for="item in items" :key="item.id" class="cart-item">
              <img class="product-image" :src="item.productImage || defaultImage" :alt="item.productName || '商品图片'" @error="useFallback" />
              <div class="cart-item-copy"><h3>{{ item.productName || '店内商品' }}</h3><p :class="{ unavailable: item.available === false }">{{ item.available === false ? (item.disableReason || '暂时无法购买') : '为你现做' }}</p><strong class="money">¥{{ amount(item.price) }}</strong></div>
              <div class="cart-item-controls"><div class="quantity-controls">
                <el-button circle size="small" :disabled="Boolean(busyIds[item.id]) || !quantityEditable(item)" :loading="busyIds[item.id] && busyDirection[item.id] === -1" :aria-label="`减少${item.productName}数量`" @click="change(item, -1)">−</el-button>
                <span>{{ item.quantity }}</span>
                <el-button circle size="small" :disabled="Boolean(busyIds[item.id]) || !quantityEditable(item)" :loading="busyIds[item.id] && busyDirection[item.id] === 1" :aria-label="`增加${item.productName}数量`" @click="change(item, 1)">+</el-button>
              </div><span class="line-subtotal">¥{{ amount(item.subtotal ?? Number(item.price) * Number(item.quantity)) }}</span><el-button text type="danger" :disabled="Boolean(busyIds[item.id]) || clearing" @click="remove(item)">移除</el-button></div>
            </article>
          </div>
          <div v-else-if="!loading && !error" class="empty-cart-content"><span aria-hidden="true">🍵</span><h3>购物车还空着</h3><p>去店铺里挑几样喜欢的吧。</p><el-button type="primary" plain @click="router.push({ name: 'shops' })">看看有哪些店</el-button></div>
          <div v-if="items.length" class="cart-footer-actions">
            <span class="muted">调整数量或移除不想要的菜。</span>
            <el-button text type="danger" :loading="clearing" :disabled="clearing || Object.keys(busyIds).length > 0" @click="confirmClear">清空购物车</el-button>
          </div>
        </section>

        <aside v-if="items.length" class="cart-summary surface">
          <p class="eyebrow">ORDER SUMMARY</p><h2>订单小计</h2>
          <div class="summary-row"><span>商品金额</span><strong>¥{{ amount(cart?.totalAmount ?? calculatedTotal) }}</strong></div>
          <el-alert v-if="cart?.canBuy === false" :title="cart.invalidReason || '有商品暂时无法购买，请调整购物车后再试。'" type="warning" show-icon :closable="false" class="summary-warning" />
          <el-button type="primary" size="large" class="checkout-button" :disabled="cart?.canBuy !== true || loading || Object.keys(busyIds).length > 0" @click="checkout">去结算 <span aria-hidden="true">→</span></el-button>
          <p class="summary-note">价格和库存会在下单时再次确认。</p>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { changeCartItem, clearCart, getCart, removeCartItems } from '../api/customerApi.js'
import { getSession } from '../auth.js'

const defaultImage = '/images/default-product.svg'
const route = useRoute()
const router = useRouter()
const session = computed(() => getSession('customer'))
const cart = ref(null)
const items = computed(() => cart.value?.items ?? [])
const loading = ref(false)
const error = ref('')
const clearing = ref(false)
const busyIds = ref({})
const busyDirection = ref({})
let requestSequence = 0
let mounted = false
const calculatedTotal = computed(() => items.value.reduce((sum, item) => sum + Number(item.subtotal ?? Number(item.price) * Number(item.quantity)), 0))

onMounted(() => { mounted = true; if (session.value) void loadCart() })
onUnmounted(() => { mounted = false; requestSequence += 1 })
watch(() => session.value?.token, (token, previousToken) => {
  if (token === previousToken) return
  requestSequence += 1
  cart.value = null
  error.value = ''
  loading.value = false
  busyIds.value = {}
  busyDirection.value = {}
  clearing.value = false
  if (mounted && token) void loadCart()
})

async function loadCart() {
  if (!session.value || !mounted) return
  const sequence = ++requestSequence
  const token = session.value.token
  loading.value = true
  error.value = ''
  try {
    const data = await getCart()
    if (mounted && sequence === requestSequence && token === session.value?.token) cart.value = data ?? { items: [], totalAmount: 0, canBuy: true }
  } catch (cause) {
    if (mounted && sequence === requestSequence && token === session.value?.token) { error.value = cause.message || '购物车加载失败。'; cart.value = null }
  } finally {
    if (mounted && sequence === requestSequence && token === session.value?.token) loading.value = false
  }
}

function quantityEditable(item) {
  return item.available !== false && !clearing.value
}

async function change(item, direction) {
  if (busyIds.value[item.id] || clearing.value || !quantityEditable(item)) return
  const token = session.value?.token
  busyIds.value = { ...busyIds.value, [item.id]: true }
  busyDirection.value = { ...busyDirection.value, [item.id]: direction }
  try {
    await changeCartItem(item.id, direction)
    if (!mounted || token !== session.value?.token) return
    await loadCart()
  } catch (cause) {
    if (mounted && token === session.value?.token) {
      await loadCart()
      if (mounted && token === session.value?.token) ElMessage.error(`${cause.message || '商品数量更新结果未确认'}；请核对最新购物车后再操作。`)
    }
  } finally {
    if (mounted && token === session.value?.token) {
      const next = { ...busyIds.value }; delete next[item.id]; busyIds.value = next
      const directions = { ...busyDirection.value }; delete directions[item.id]; busyDirection.value = directions
    }
  }
}

async function remove(item) {
  if (busyIds.value[item.id] || clearing.value) return
  const token = session.value?.token
  busyIds.value = { ...busyIds.value, [item.id]: true }
  try { await removeCartItems([item.id]); if (!mounted || token !== session.value?.token) return; await loadCart() }
  catch (cause) {
    if (mounted && token === session.value?.token) {
      await loadCart()
      if (mounted && token === session.value?.token) ElMessage.error(cause.message || '移除结果未确认，请核对最新购物车。')
    }
  }
  finally { if (mounted && token === session.value?.token) { const next = { ...busyIds.value }; delete next[item.id]; busyIds.value = next } }
}

async function confirmClear() {
  if (clearing.value || Object.keys(busyIds.value).length) return
  const token = session.value?.token
  if (!mounted || !token) return
  clearing.value = true
  try {
    await ElMessageBox.confirm('清空后，购物车中的商品会全部移除。', '清空购物车', { confirmButtonText: '确认清空', cancelButtonText: '再想想', type: 'warning' })
    if (!mounted || token !== session.value?.token) return
    await clearCart()
    if (!mounted || token !== session.value?.token) return
    await loadCart()
    if (mounted && token === session.value?.token) ElMessage.success('购物车已清空。')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    if (mounted && token === session.value?.token) {
      await loadCart()
      if (mounted && token === session.value?.token) ElMessage.error(cause.message || '清空结果未确认，请核对最新购物车。')
    }
  }
  finally { if (mounted && token === session.value?.token) clearing.value = false }
}

function checkout() {
  if (!mounted || clearing.value || loading.value || error.value || !items.value.length || cart.value?.canBuy !== true || Object.keys(busyIds.value).length) return
  router.push({ name: 'checkout' })
}

function goLogin() { router.push({ name: 'customer-login', query: { redirect: route.fullPath } }) }
function amount(value) { const n = Number(value); return Number.isFinite(n) ? n.toFixed(2) : '—' }
function useFallback(event) { if (!event.target.src.endsWith(defaultImage)) event.target.src = defaultImage }
</script>

<style scoped>
.customer-page { max-width:1160px; margin:0 auto; padding:clamp(25px,5vw,54px) clamp(15px,4vw,40px) 48px; }
.cart-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:22px; }
.cart-heading h1 { font-size:clamp(30px,4vw,40px); letter-spacing:-.035em; margin:0 0 7px; }
.cart-heading .muted { margin:0; }
.cart-layout { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(260px,.8fr); align-items:start; gap:18px; }
.cart-items,.cart-summary { padding:clamp(17px,3vw,26px); }
.cart-section-heading { display:flex; align-items:flex-end; justify-content:space-between; margin-bottom:12px; }
.cart-section-heading .eyebrow,.cart-summary .eyebrow { margin:0 0 5px; }
.cart-section-heading h2,.cart-summary h2 { margin:0; font-size:20px; }
.cart-count { color:#748077; font-size:13px; }
.cart-list { display:grid; }
.cart-item { display:grid; grid-template-columns:84px minmax(0,1fr) auto; align-items:center; gap:15px; padding:17px 0; border-bottom:1px solid #edf0e9; }
.cart-item:last-child { border-bottom:0; }
.cart-item .product-image { width:84px; height:84px; border-radius:13px; object-fit:cover; background:#f0f1ea; }
.cart-item-copy { min-width:0; }
.cart-item-copy h3 { margin:0 0 5px; font-size:15px; }
.cart-item-copy p { margin:0 0 8px; color:#789073; font-size:12px; }
.cart-item-copy p.unavailable { color:#ad654e; }
.cart-item-copy .money { font-size:15px; }
.cart-item-controls { display:grid; justify-items:end; gap:9px; }
.quantity-controls { display:flex; align-items:center; gap:7px; }
.quantity-controls>span { min-width:18px; text-align:center; font-size:13px; font-weight:700; }
.line-subtotal { font-size:12px; font-weight:700; color:#56675d; }
.cart-footer-actions { border-top:1px solid #edf0e9; display:flex; align-items:center; justify-content:space-between; gap:10px; padding-top:14px; }
.cart-footer-actions .muted,.summary-note { font-size:12px; }
.cart-summary { position:sticky; top:20px; }
.summary-row { display:flex; align-items:center; justify-content:space-between; padding:14px 0; border-bottom:1px solid #edf0e9; font-size:13px; }
.summary-row.muted { border:0; padding-bottom:18px; }
.summary-warning { margin:3px 0 14px; }
.checkout-button { width:100%; }
.summary-note { text-align:center; color:#879289; margin:13px 0 0; }
.empty-cart { max-width:660px; margin:38px auto 0; padding:36px; text-align:center; }
.empty-cart>span,.empty-cart-content>span { font-size:40px; }
.empty-cart h2 { margin:13px 0 6px; }
.empty-cart p,.empty-cart-content p { color:#78847c; margin:0 0 17px; }
.empty-cart-content { text-align:center; padding:38px 16px; }
.empty-cart-content h3 { margin:10px 0 6px; }
.cart-alert { margin-bottom:16px; }
@media(max-width:760px) { .cart-layout { grid-template-columns:1fr; } .cart-summary { position:static; order:-1; } }
@media(max-width:470px) { .customer-page { padding:24px 13px 36px; } .cart-heading { align-items:flex-start; } .cart-item { grid-template-columns:66px minmax(0,1fr) auto; gap:10px; } .cart-item .product-image { width:66px; height:66px; } .cart-footer-actions { align-items:flex-start; flex-direction:column; } .empty-cart { padding:27px 16px; margin-top:18px; } }
</style>
