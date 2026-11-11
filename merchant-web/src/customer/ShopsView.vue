<template>
  <section class="customer-page">
    <header class="page-heading customer-heading">
      <div>
        <p class="eyebrow">TOKEOUT · 每一餐，都有好味道</p>
        <h1>今天想吃点什么？</h1>
        <p class="muted">逛逛营业好店，选一份喜欢的。</p>
      </div>
      <div class="customer-nav">
        <el-button text @click="openOrders">我的订单</el-button>
        <el-button type="primary" plain @click="openCart">购物车</el-button>
      </div>
    </header>

    <div class="shop-toolbar surface">
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索店铺名称"
        class="shop-search"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><span aria-hidden="true">⌕</span></template>
      </el-input>
      <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
      <el-select v-model="status" class="status-select" @change="search">
        <el-option label="营业中" :value="0" />
        <el-option label="已打烊" :value="1" />
      </el-select>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="shop-alert">
      <el-button text type="primary" @click="loadShops">重新加载</el-button>
    </el-alert>

    <div v-loading="loading" class="shop-grid" aria-live="polite" :aria-busy="loading">
      <button v-for="shop in shops" :key="shop.id" class="shop-card surface" @click="openShop(shop.id)">
        <div class="shop-cover">
          <img :src="shop.pictureURL || defaultImage" :alt="`${shop.merchantName} 店铺图片`" @error="useFallback" />
          <span class="shop-status" :class="{ closed: shop.status !== 0 }">{{ shop.statusDesc || (shop.status === 0 ? '营业中' : '已打烊') }}</span>
        </div>
        <div class="shop-card-body">
          <h2>{{ shop.merchantName || '美味小店' }}</h2>
          <p class="shop-address">{{ shop.address || '欢迎进店看看' }}</p>
          <p class="shop-description">{{ shop.description || '为你认真准备每一餐。' }}</p>
          <span class="shop-link">进店看看 <span aria-hidden="true">→</span></span>
        </div>
      </button>
      <div v-if="!loading && !shops.length && !error" class="shop-empty surface">
        <span class="empty-illustration" aria-hidden="true">🍽</span>
        <h2>{{ keyword ? '没有找到这家店' : (status === 0 ? '暂时没有营业中的店铺' : '暂时没有已打烊的店铺') }}</h2>
        <p class="muted">{{ keyword ? '换个关键词再试试。' : '稍后再来看看，新的美味正在准备。' }}</p>
      </div>
    </div>

    <footer v-if="!loading && total > 0" class="shop-pagination">
      <span class="muted">共 {{ total }} 家店</span>
      <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev, pager, next" :pager-count="5" @current-change="loadShops" />
    </footer>
  </section>
</template>

<script setup>
import {onUnmounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {listShops} from '../api/customerApi.js'

const defaultImage = '/images/default-product.svg'
const route = useRoute()
const router = useRouter()
const keyword = ref('')
const status = ref(0)
const pageNum = ref(1)
const pageSize = 12
const total = ref(0)
const shops = ref([])
const loading = ref(false)
const error = ref('')
let requestSequence = 0

watch(() => route.query.q, (value) => {
  keyword.value = typeof value === 'string' ? value : ''
  pageNum.value = 1
  void loadShops()
}, { immediate: true })
onUnmounted(() => { requestSequence += 1 })

async function loadShops() {
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const data = await listShops({ name: keyword.value.trim() || undefined, pageNum: pageNum.value, size: pageSize, status: status.value ?? undefined })
    if (sequence !== requestSequence) return
    shops.value = data?.list ?? []
    total.value = Number(data?.total ?? 0)
  } catch (cause) {
    if (sequence !== requestSequence) return
    error.value = cause.message || '店铺列表加载失败，请稍后重试。'
    shops.value = []
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function search() {
  pageNum.value = 1
  void loadShops()
}

function openShop(id) { router.push({ name: 'shop', params: { shopId: id } }) }
function openCart() { router.push({ name: 'cart' }) }
function openOrders() { router.push({ name: 'orders' }) }
function useFallback(event) {
  if (event.target.src.endsWith(defaultImage)) return
  event.target.src = defaultImage
}
</script>

<style scoped>
.customer-page { max-width: 1160px; margin: 0 auto; padding: clamp(24px, 5vw, 58px) clamp(16px, 4vw, 40px) 48px; }
.customer-heading { display:flex; align-items:flex-end; justify-content:space-between; gap:20px; margin-bottom:28px; }
.customer-heading h1 { margin:0 0 9px; font-size:clamp(30px, 5vw, 46px); letter-spacing:-.04em; }
.customer-heading .muted { margin:0; }
.customer-nav { display:flex; gap:8px; flex-shrink:0; }
.shop-toolbar { display:flex; align-items:center; gap:10px; padding:12px; margin-bottom:22px; }
.shop-search { max-width:460px; }
.status-select { width:130px; margin-left:auto; }
.shop-alert { margin-bottom:18px; }
.shop-grid { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:18px; min-height:240px; }
.shop-card { overflow:hidden; padding:0; text-align:left; cursor:pointer; border:1px solid #e6e9df; transition:transform .18s ease, box-shadow .18s ease; color:inherit; }
.shop-card:hover { transform:translateY(-3px); box-shadow:0 15px 36px #25463812; }
.shop-cover { position:relative; height:180px; overflow:hidden; background:#eff1e9; }
.shop-cover img { width:100%; height:100%; object-fit:cover; }
.shop-status { position:absolute; left:14px; top:14px; border-radius:999px; background:#e8f4e8; color:#286449; padding:6px 11px; font-size:12px; font-weight:700; }
.shop-status.closed { background:#f3efe8; color:#766d60; }
.shop-card-body { padding:17px 18px 18px; }
.shop-card-body h2 { margin:0 0 7px; font-size:19px; }
.shop-address,.shop-description { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; font-size:13px; }
.shop-address { color:#54665b; margin:0 0 5px; }
.shop-description { color:#849087; margin:0 0 16px; }
.shop-link { display:flex; align-items:center; justify-content:space-between; color:#19664f; font-size:13px; font-weight:700; }
.shop-empty { grid-column:1/-1; display:grid; place-content:center; justify-items:center; min-height:260px; text-align:center; }
.shop-empty h2 { margin:8px 0; }
.shop-empty p { margin:0; }
.empty-illustration { font-size:40px; }
.shop-pagination { display:flex; justify-content:space-between; align-items:center; gap:16px; padding-top:22px; }
@media (max-width:800px) { .shop-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .shop-cover { height:155px; } }
@media (max-width:540px) { .customer-page { padding:24px 14px 36px; } .customer-heading { align-items:flex-start; flex-direction:column; } .customer-nav { width:100%; } .shop-toolbar { flex-wrap:wrap; } .shop-search { max-width:none; flex:1 1 100%; } .status-select { margin-left:0; } .shop-grid { gap:12px; grid-template-columns:1fr; } .shop-cover { height:190px; } .shop-pagination { align-items:flex-start; flex-direction:column; } }
@media (prefers-reduced-motion:reduce) { .shop-card { transition:none; } }
</style>
