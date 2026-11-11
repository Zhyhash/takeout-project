<template>
  <section class="history-page">
    <header class="page-heading">
      <div><p class="eyebrow">CUSTOMER / HISTORY</p><h1>浏览记录</h1><p class="muted">这里展示最近打开过的商品。</p></div>
      <div class="action-row">
        <el-button text type="primary" :disabled="loading || clearing" @click="router.push({ name: 'shops' })">返回店铺列表</el-button>
        <el-button :disabled="loading || clearing" :loading="loading" @click="loadHistory">刷新</el-button>
        <el-button type="danger" plain :disabled="loading || clearing || !products.length" :loading="clearing" @click="clearHistory">清空记录</el-button>
      </div>
    </header>

    <el-alert class="history-note" title="最多展示最近50个商品。浏览记录可能暂时无法同步，未显示时可稍后刷新。" type="info" show-icon :closable="false" />
    <el-alert v-if="error" class="history-error" :title="error" type="error" show-icon :closable="false">
      <el-button text type="primary" :disabled="loading || clearing" @click="loadHistory">重新加载</el-button>
    </el-alert>

    <div v-loading="loading" class="history-body">
      <div v-if="!error && !loading && products.length === 0" class="surface page-state">
        <div class="state-symbol" aria-hidden="true">⌕</div>
        <h2>还没有浏览记录</h2>
        <p>打开店铺里的商品详情后，记录会在服务可用时尝试保存。</p>
        <el-button type="primary" plain @click="router.push({ name: 'shops' })">去逛逛店铺</el-button>
      </div>

      <div v-else-if="products.length" class="history-grid">
        <article v-for="product in products" :key="product.id" class="surface history-card">
          <img class="history-image" :src="product.imageUrl || defaultImage" :alt="product.productName || '商品图片'" loading="lazy" @error="useFallback" />
          <div class="history-copy">
            <h2>{{ product.productName || '商品' }}</h2>
            <span class="stock-state" :class="{ unavailable: product.inStock !== true }">{{ product.inStock === true ? '有货' : (product.statusDesc || '暂不可购买') }}</span>
            <strong class="money">¥{{ formatAmount(product.price) }}</strong>
          </div>
          <span class="history-disclaimer">商品信息可能已更新</span>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup>
import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {useRouter} from 'vue-router'
import {clearBrowseHistory, getBrowseHistory} from '../api/customerApi.js'
import {getSession} from '../auth.js'

const router = useRouter()
const defaultImage = '/images/default-product.svg'
const products = ref([])
const loading = ref(false)
const clearing = ref(false)
const error = ref('')
let generation = 0
let requestSequence = 0
let mounted = false

onMounted(() => { mounted = true; void loadHistory() })
onBeforeUnmount(() => { mounted = false; generation++; requestSequence++ })
watch(() => getSession('customer')?.token, () => {
  generation++
  requestSequence++
  products.value = []
  error.value = ''
  loading.value = false
  clearing.value = false
  if (mounted) void loadHistory()
})

function current(token, currentGeneration, sequence) {
  return mounted && currentGeneration === generation && sequence === requestSequence && token === getSession('customer')?.token
}

async function loadHistory() {
  if (loading.value || clearing.value) return
  const token = getSession('customer')?.token
  if (!token) { error.value = '请登录顾客账号后查看浏览记录。'; products.value = []; return }
  const currentGeneration = generation
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const result = await getBrowseHistory()
    if (!current(token, currentGeneration, sequence)) return
    if (!Array.isArray(result)) throw new Error('浏览记录返回的数据不完整，请稍后重试。')
    products.value = result
  } catch (cause) {
    if (current(token, currentGeneration, sequence)) error.value = cause.message || '浏览记录暂时无法加载，请重试。'
  } finally {
    if (currentGeneration === generation && sequence === requestSequence) loading.value = false
  }
}

async function clearHistory() {
  if (clearing.value || loading.value || products.value.length === 0) return
  const token = getSession('customer')?.token
  if (!token) { error.value = '登录状态已失效，请重新登录。'; return }
  clearing.value = true
  const currentGeneration = generation
  const sequence = ++requestSequence
  try {
    await ElMessageBox.confirm('清空后将移除当前顾客的全部浏览记录。', '清空浏览记录', {
      confirmButtonText: '确认清空', cancelButtonText: '保留记录', type: 'warning',
    })
    if (!current(token, currentGeneration, sequence)) return
    await clearBrowseHistory()
    if (!current(token, currentGeneration, sequence)) return
    products.value = []
    error.value = ''
    ElMessage.success('浏览记录已清空')
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close' && current(token, currentGeneration, sequence)) {
      error.value = cause.message || '清空失败，请稍后重试。'
    }
  } finally {
    if (currentGeneration === generation && sequence === requestSequence) clearing.value = false
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
.history-page{max-width:1160px;margin:0 auto}.history-note{margin:-7px 0 18px}.history-error{margin-bottom:16px}.history-body{min-height:210px}.history-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.history-card{padding:16px;display:grid;grid-template-columns:98px minmax(0,1fr);gap:14px;align-items:center}.history-image{width:98px;height:98px;object-fit:cover;border-radius:13px;background:#eff1e9}.history-copy{min-width:0;display:grid;gap:7px}.history-copy h2{font-size:16px;margin:0;line-height:1.5}.stock-state{font-size:12px;color:#527b5f}.stock-state.unavailable{color:#9b7658}.history-copy .money{font-size:17px}.history-disclaimer{grid-column:2;color:var(--color-muted);font-size:11px}.history-card,.history-card *{overflow-wrap:anywhere}
@media(max-width:760px){.history-grid{grid-template-columns:1fr}.history-card{grid-template-columns:78px minmax(0,1fr);gap:12px;padding:14px}.history-image{width:78px;height:78px}}
</style>
