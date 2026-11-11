<template>
  <section class="customer-page">
    <div class="shop-backline">
      <el-button text @click="router.push({ name: 'shops' })">← 所有店铺</el-button>
      <el-button text @click="router.push({ name: 'cart' })">购物车</el-button>
    </div>

    <div v-if="loading && !shop" class="surface shop-loading"><el-skeleton animated :rows="5" /></div>
    <el-alert v-else-if="error && !shop" :title="error" type="error" show-icon :closable="false">
      <el-button text type="primary" @click="loadShop">重新加载</el-button>
    </el-alert>

    <template v-if="shop">
      <header class="store-hero surface">
        <img class="store-photo" :src="shop.pictureURL || defaultImage" :alt="shop.merchantName || '店铺图片'" @error="useFallback" />
        <div class="store-copy">
          <div class="store-badges"><span class="store-open" :class="{ closed: shop.status !== 0 }">{{ shop.statusDesc || (shop.status === 0 ? '营业中' : '已打烊') }}</span><span class="store-hours">{{ hoursText }}</span></div>
          <h1>{{ shop.merchantName || '美味小店' }}</h1>
          <p>{{ shop.description || '为你认真准备每一餐。' }}</p>
          <span class="store-address">⌖ {{ shop.address || '店铺地址以商家信息为准' }}</span>
        </div>
      </header>

      <div v-if="error" class="store-inline-error"><el-alert :title="error" type="error" show-icon :closable="false" /></div>

      <div class="menu-layout">
        <aside class="menu-index surface" aria-label="菜品分类">
          <span class="menu-index-label">MENU</span>
          <button v-for="category in categories" :key="category.categoryId" type="button" @click="scrollToCategory(category.categoryId)">{{ category.categoryName }}</button>
        </aside>

        <div class="menu-content" v-loading="loading">
          <section v-for="category in categories" :id="`category-${category.categoryId}`" :key="category.categoryId" class="menu-category surface">
            <div class="category-heading"><span class="category-marker" /><div><h2>{{ category.categoryName }}</h2><p>{{ category.products?.length || 0 }} 道好味道</p></div></div>
            <article v-for="product in category.products || []" :key="product.id" class="menu-product">
              <img class="product-image" :src="product.imageUrl || defaultImage" :alt="product.productName" loading="lazy" @error="useFallback" />
              <div class="product-copy">
                <h3>{{ product.productName || '店内精选' }}</h3>
                <span class="product-stock" :class="{ unavailable: product.inStock !== true }">{{ product.inStock === true ? '新鲜供应' : (product.statusDesc === '已经下架' ? '已经下架' : '暂时售罄') }}</span>
                <strong class="money">¥{{ formatAmount(product.price) }}</strong>
                <el-button class="product-detail-link" text type="primary" @click="openProductDetails(product)">查看详情</el-button>
              </div>
              <el-button circle type="primary" :disabled="addingId === product.id || !canAdd(product)" :loading="addingId === product.id" :aria-label="`将${product.productName}加入购物车`" @click="addProduct(product)">+</el-button>
            </article>
            <div v-if="!category.products?.length" class="category-empty">这组菜单还在准备中。</div>
          </section>

          <div v-if="!categories.length && !loading" class="surface empty-menu"><span aria-hidden="true">🥣</span><h2>菜单正在准备中</h2><p>这家店暂时没有可展示的商品。</p></div>
        </div>
      </div>

      <div class="floating-cart">
        <div><span class="floating-cart-caption">想好了？</span><strong>去购物车结算</strong></div>
        <el-button type="primary" @click="router.push({ name: 'cart' })">查看购物车 <span aria-hidden="true">→</span></el-button>
      </div>
    </template>

    <el-dialog v-model="detailVisible" :title="detailProduct?.productName || '商品详情'" width="520px" :append-to-body="true">
      <template v-if="detailProduct">
        <div class="detail-content">
          <img class="detail-image" :src="detailProduct.imageUrl || defaultImage" :alt="detailProduct.productName || '商品图片'" @error="useFallback" />
          <div class="detail-copy">
            <span class="product-stock" :class="{ unavailable: detailProduct.inStock !== true }">{{ detailProduct.inStock === true ? '新鲜供应' : (detailProduct.statusDesc || '暂时售罄') }}</span>
            <strong class="money">¥{{ formatAmount(detailProduct.price) }}</strong>
            <p v-if="recordState === 'anonymous'" class="history-note">登录顾客账号后，打开商品时会尝试同步到浏览记录。</p>
            <p v-else-if="recordState !== 'submitted'" class="history-note">打开商品时会尝试同步到浏览记录；服务暂不可用时可能不会保存。</p>
            <p v-if="recordState === 'pending'" class="history-note" role="status">正在同步本次浏览请求…</p>
            <p v-if="recordState === 'submitted'" class="history-note">已尝试同步本次浏览，稍后可在浏览记录中查看。</p>
            <el-alert v-if="recordState === 'failed'" title="本次浏览记录暂时无法同步。" type="warning" :closable="false" show-icon />
            <el-button v-if="recordState === 'failed'" text type="primary" :loading="recordBusy" @click="retryRecordView">重试记录</el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import {computed, onUnmounted, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {useRoute, useRouter} from 'vue-router'
import {addCartItem, clearCart, getShop, recordProductView} from '../api/customerApi.js'
import {getSession} from '../auth.js'

const defaultImage = '/images/default-product.svg'
const route = useRoute()
const router = useRouter()
const shop = ref(null)
const loading = ref(false)
const error = ref('')
const addingId = ref(null)
const detailVisible = ref(false)
const detailProduct = ref(null)
const recordState = ref('idle')
const recordBusy = ref(false)
let requestSequence = 0
let componentGeneration = 0
let recordSequence = 0
const categories = computed(() => shop.value?.categories ?? [])
const hoursText = computed(() => {
  const { openingTime, closingTime } = shop.value || {}
  return openingTime && closingTime ? `${String(openingTime).slice(0, 5)} – ${String(closingTime).slice(0, 5)}` : '以店铺实际营业时间为准'
})

watch(() => route.params.shopId, () => {
  detailVisible.value = false
  detailProduct.value = null
  recordState.value = 'idle'
  recordBusy.value = false
  recordSequence += 1
  void loadShop()
}, { immediate: true })
watch(() => getSession('customer')?.token, () => {
  recordSequence += 1
  recordBusy.value = false
  if (detailVisible.value) recordState.value = getSession('customer') ? 'idle' : 'anonymous'
})
onUnmounted(() => { requestSequence += 1; recordSequence += 1; componentGeneration += 1 })

async function loadShop() {
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  shop.value = null
  try {
    const result = await getShop(route.params.shopId)
    if (sequence === requestSequence) shop.value = result
  } catch (cause) {
    if (sequence === requestSequence) error.value = cause.message || '店铺信息暂时无法加载。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function canAdd(product) {
  return shop.value?.status === 0 && product.status === 0 && product.inStock === true && addingId.value === null
}

function scrollToCategory(categoryId) {
  document.getElementById(`category-${categoryId}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function openProductDetails(product) {
  detailProduct.value = product
  detailVisible.value = true
  const session = getSession('customer')
  if (!session) {
    recordState.value = 'anonymous'
    return
  }
  void submitProductView(product, session)
}

function retryRecordView() {
  const session = getSession('customer')
  if (detailProduct.value && session && !recordBusy.value) void submitProductView(detailProduct.value, session)
}

async function submitProductView(product, session) {
  const sequence = ++recordSequence
  const generation = componentGeneration
  const shopId = String(route.params.shopId)
  const productId = String(product.id)
  const current = () => generation === componentGeneration && sequence === recordSequence
    && shopId === String(route.params.shopId) && String(detailProduct.value?.id) === productId
    && session.token === getSession('customer')?.token
  recordBusy.value = true
  recordState.value = 'pending'
  try {
    await recordProductView(product.id)
    if (current()) recordState.value = 'submitted'
  } catch {
    if (current()) recordState.value = 'failed'
  } finally {
    if (current()) recordBusy.value = false
  }
}

async function addProduct(product) {
  if (addingId.value !== null || !canAdd(product)) return
  const session = getSession('customer')
  if (!session) {
    await router.push({ name: 'customer-login', query: { redirect: route.fullPath } })
    return
  }
  const generation = componentGeneration
  const shopId = String(route.params.shopId)
  const current = () => generation === componentGeneration && shopId === String(route.params.shopId)
    && session.token === getSession('customer')?.token
  addingId.value = product.id
  try {
    await addCartItem(product.id)
    if (!current()) return
    ElMessage.success(`${product.productName} 已加入购物车`)
  } catch (cause) {
    if (!current()) return
    if (cause.code === 500 && cause.message === '只能加入同一家店的商品') {
      try {
        await ElMessageBox.confirm('购物车里已有另一家店的商品。清空当前购物车并改买这家店吗？', '更换购物车店铺', {
          confirmButtonText: '清空并加入', cancelButtonText: '保留当前购物车', type: 'warning',
        })
        if (!current()) return
        await clearCart()
        if (!current()) return
        await addCartItem(product.id)
        if (current()) ElMessage.success(`${product.productName} 已加入购物车`)
      } catch (switchError) {
        if (current() && switchError !== 'cancel' && switchError !== 'close') ElMessage.error(switchError.message || '更换店铺失败，请刷新购物车后重试。')
      }
    } else if (current()) {
      ElMessage.error(cause.message || '暂时无法加入购物车。')
    }
  } finally {
    if (current()) addingId.value = null
  }
}

function formatAmount(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? amount.toFixed(2) : '—'
}

function useFallback(event) {
  if (event.target.src.endsWith(defaultImage)) return
  event.target.src = defaultImage
}
</script>

<style scoped>
.customer-page { max-width:1160px; margin:0 auto; padding:20px clamp(14px,4vw,40px) 110px; }
.shop-backline { display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; }
.store-hero { display:grid; grid-template-columns:minmax(180px,280px) 1fr; gap:24px; padding:18px; overflow:hidden; margin-bottom:24px; }
.store-photo { display:block; width:100%; height:190px; object-fit:cover; border-radius:14px; background:#eff1e9; }
.store-copy { align-self:center; min-width:0; }
.store-badges { display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin-bottom:12px; }
.store-open { border-radius:999px; background:#eaf3e7; color:#276047; font-weight:700; font-size:12px; padding:6px 10px; }
.store-open.closed { background:#f1eee7; color:#746e63; }
.store-hours { color:#738077; font-size:13px; }
.store-copy h1 { margin:0 0 8px; font-size:clamp(25px,4vw,36px); }
.store-copy p { color:#6c7a70; margin:0 0 14px; line-height:1.6; }
.store-address { color:#59695e; font-size:13px; }
.store-inline-error { margin-bottom:18px; }
.menu-layout { display:grid; grid-template-columns:170px minmax(0,1fr); gap:18px; align-items:start; }
.menu-index { position:sticky; top:18px; display:grid; gap:4px; padding:12px; }
.menu-index-label { color:#849087; font-size:10px; font-weight:800; letter-spacing:.14em; padding:6px 10px; }
.menu-index button { color:#53645b; text-align:left; border:0; background:transparent; padding:10px; border-radius:8px; font-size:13px; cursor:pointer; }
.menu-index button:hover { background:#edf4ed; color:#19664f; }
.menu-content { display:grid; gap:16px; min-height:220px; }
.menu-category { padding:20px; scroll-margin-top:18px; }
.category-heading { display:flex; gap:12px; align-items:center; margin-bottom:5px; }
.category-marker { width:4px; height:32px; border-radius:4px; background:#5b876c; }
.category-heading h2 { margin:0; font-size:19px; }
.category-heading p { margin:4px 0 0; color:#8a958d; font-size:12px; }
.menu-product { display:grid; grid-template-columns:92px minmax(0,1fr) auto; gap:14px; align-items:center; padding:17px 0; border-bottom:1px solid #edf0e9; }
.menu-product:last-child { border-bottom:0; padding-bottom:4px; }
.menu-product .product-image { width:92px; height:92px; border-radius:13px; object-fit:cover; background:#f0f1ea; }
.product-copy { min-width:0; display:grid; gap:5px; }
.product-copy h3 { margin:0; font-size:16px; }
.product-stock { color:#738c70; font-size:12px; }
.product-stock.unavailable { color:#a18365; }
.product-copy .money { font-size:18px; }
.product-detail-link { justify-self:start; padding:0; min-height:24px; }
.category-empty { color:#89948b; padding:18px 0 6px; font-size:13px; }
.empty-menu { text-align:center; padding:45px 24px; color:#69776c; }
.empty-menu>span { font-size:38px; }
.empty-menu h2 { color:#23382f; margin:12px 0 5px; }
.empty-menu p { margin:0; }
.floating-cart { position:fixed; z-index:4; left:50%; bottom:18px; transform:translateX(-50%); width:min(760px, calc(100% - 32px)); border-radius:18px; padding:12px 14px 12px 20px; display:flex; align-items:center; justify-content:space-between; gap:12px; color:white; background:#193f34; box-shadow:0 12px 36px #152c2440; }
.floating-cart>div { display:grid; gap:3px; }
.floating-cart-caption { color:#bed4c6; font-size:11px; }
.floating-cart strong { font-size:15px; }
.floating-cart :deep(.el-button) { border:0; color:#1c473a; background:#eaf0df; font-weight:700; }
.shop-loading { padding:24px; }
.detail-content { display:grid; grid-template-columns:minmax(150px, 210px) minmax(0,1fr); gap:20px; align-items:start; }
.detail-image { width:100%; aspect-ratio:1; object-fit:cover; border-radius:14px; background:#f0f1ea; }
.detail-copy { display:grid; gap:12px; align-content:start; }
.detail-copy .money { font-size:23px; }
.history-note { color:#738077; font-size:12px; line-height:1.75; margin:0; }
@media(max-width:480px) { .detail-content { grid-template-columns:1fr; } .detail-image { max-height:230px; } }
@media(max-width:700px) { .store-hero { grid-template-columns:1fr; gap:14px; padding:13px; } .store-photo { height:190px; } .menu-layout { grid-template-columns:1fr; gap:12px; } .menu-index { position:static; display:flex; overflow-x:auto; gap:3px; padding:8px; } .menu-index-label { flex-shrink:0; padding:9px; } .menu-index button { flex-shrink:0; white-space:nowrap; } .menu-category { padding:15px; } .menu-product { grid-template-columns:78px minmax(0,1fr) auto; gap:10px; } .menu-product .product-image { width:78px; height:78px; } }
@media(prefers-reduced-motion:reduce) { * { scroll-behavior:auto!important; } }
</style>
