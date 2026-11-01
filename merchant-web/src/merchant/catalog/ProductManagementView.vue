<template>
  <div class="catalog-page">
    <header class="page-heading catalog-heading">
      <div>
        <p class="eyebrow">MERCHANT · CATALOG</p>
        <h1>商品管理</h1>
        <p class="muted">维护商品资料、库存与销售状态。已删除商品只能在本次打开的管理会话中恢复。</p>
      </div>
      <div class="heading-actions">
        <RouterLink class="catalog-tab" :to="{ name: 'merchant-products' }" aria-current="page">商品</RouterLink>
        <RouterLink class="catalog-tab" :to="{ name: 'merchant-categories' }">分类</RouterLink>
        <el-button type="primary" @click="openCreate">新增商品</el-button>
      </div>
    </header>

    <section class="catalog-overview" aria-label="商品概览">
      <article class="overview-card">
        <span class="overview-icon">▤</span>
        <div><span class="overview-label">商品总数</span><strong>{{ total }}</strong><small>按当前筛选条件统计</small></div>
      </article>
      <article class="overview-card overview-card-soft">
        <span class="overview-icon">◉</span>
        <div><span class="overview-label">当前页在售</span><strong>{{ currentPageCounts.onSale }}</strong><small>正在销售的商品</small></div>
      </article>
      <article class="overview-card overview-card-warm">
        <span class="overview-icon">◌</span>
        <div><span class="overview-label">当前页待售</span><strong>{{ currentPageCounts.offSale + currentPageCounts.soldOut }}</strong><small>含下架与售罄商品</small></div>
      </article>
    </section>

    <section class="surface filter-surface" aria-label="商品筛选">
      <div class="filter-title"><div><h2>商品列表</h2><p class="muted">按名称、分类或销售状态查找</p></div><el-button text :loading="loading" @click="reloadAll">刷新</el-button></div>
      <form class="filters" @submit.prevent="applyFilters">
        <el-input v-model="keywordInput" class="keyword-input" clearable maxlength="60" placeholder="搜索商品名称" aria-label="搜索商品名称" @clear="applyFilters">
          <template #prefix><span aria-hidden="true">⌕</span></template>
        </el-input>
        <el-select v-model="statusInput" clearable placeholder="全部状态" aria-label="按销售状态筛选">
          <el-option label="正在销售" :value="0" /><el-option label="已经下架" :value="1" /><el-option label="已售罄" :value="2" />
        </el-select>
        <el-select v-model="categoryInput" clearable filterable placeholder="全部分类" aria-label="按商品分类筛选">
          <el-option v-for="category in categories" :key="category.id" :label="category.categoryName" :value="String(category.id)" />
        </el-select>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="clearFilters">重置</el-button>
      </form>
      <el-alert v-if="categoryLoadError" class="inline-alert" type="warning" :closable="false" show-icon :title="categoryLoadError">
        <el-button text @click="loadCategories">重新加载分类</el-button>
      </el-alert>
      <el-alert v-if="loadError" class="inline-alert" type="error" :closable="false" show-icon :title="loadError">
        <el-button text @click="loadProducts">重试</el-button>
      </el-alert>
      <el-alert v-if="deletedItems.length" class="recovery-alert" type="info" :closable="false" show-icon>
        <template #title>本次会话删除的商品</template>
        <p>以下是本次页面会话刚删除的 {{ deletedItems.length }} 件商品，可按需恢复；离开会话后不会保留这份记录。</p>
        <div class="recovery-list">
          <div v-for="item in deletedItems" :key="item.id" class="recovery-item">
            <span><strong>{{ item.productName }}</strong><small>商品编号 {{ item.id }}</small></span>
            <el-button size="small" :loading="rowBusy[item.id] === 'restore'" @click="restoreProduct(item)">恢复商品</el-button>
          </div>
        </div>
      </el-alert>
    </section>

    <section class="surface table-surface" aria-label="商品结果">
      <div class="table-meta"><span>共 {{ total }} 件商品</span><span v-if="filters.keyword || filters.categoryId || filters.status !== null" class="muted">筛选结果</span></div>
      <p class="table-scroll-hint">左右滑动查看完整信息与操作</p>
      <el-table :data="products" v-loading="loading" row-key="id" empty-text="没有找到符合条件的商品" class="product-table">
        <el-table-column label="商品" min-width="280">
          <template #default="{ row }">
            <div class="product-cell">
              <img class="product-thumb" :src="row.imageUrl || defaultImage" :alt="`${row.productName}商品图片`" @error="useFallbackImage" />
              <div class="product-copy"><strong>{{ row.productName }}</strong><span>{{ row.categoryName || '未分类' }}</span><small>{{ row.description || '暂无商品描述' }}</small></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="售价" width="110" align="right">
          <template #default="{ row }"><span class="money">¥{{ formatPrice(row.price) }}</span></template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="90" align="center" />
        <el-table-column label="销售状态" width="120" align="center">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" effect="light">{{ statusText(row) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" min-width="250">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :disabled="!!rowBusy[row.id] || !!confirmLock" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 0" link type="warning" :loading="rowBusy[row.id] === 'shelf'" :disabled="!!rowBusy[row.id] || !!confirmLock" @click="changeShelf(row, false)">下架</el-button>
              <el-button v-else-if="row.status === 1 || (row.status === 2 && Number(row.stock) > 0)" link type="success" :loading="rowBusy[row.id] === 'shelf'" :disabled="!!rowBusy[row.id] || !!confirmLock" @click="changeShelf(row, true)">上架</el-button>
              <el-button v-else link type="primary" :disabled="!!rowBusy[row.id] || !!confirmLock" @click="openEdit(row, true)">补充库存</el-button>
              <el-button link type="danger" :loading="rowBusy[row.id] === 'delete'" :disabled="!!rowBusy[row.id] || !!confirmLock" @click="removeProduct(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <span class="muted">商品按编号倒序排列</span>
        <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next, jumper" @current-change="loadProducts" @size-change="changePageSize" />
      </div>
    </section>

    <el-dialog v-model="createVisible" title="新增商品" width="min(620px, 96vw)" destroy-on-close :close-on-click-modal="!createSubmitting && (!createNeedsReconcile || createRetryAcknowledged)" :close-on-press-escape="!createSubmitting && (!createNeedsReconcile || createRetryAcknowledged)" :before-close="beforeCloseCreate" @closed="resetCreateDraft">
      <p class="dialog-intro">填写商品基础资料。新建商品默认下架，创建完成后可在列表中上架。</p>
      <el-alert v-if="createNeedsReconcile" class="inline-alert" type="warning" :closable="false" show-icon>
        <template #title>创建结果尚未核对</template>
        <p>创建请求曾返回异常，商品可能已经创建。请先刷新并查看精确同名商品，再决定如何处理。</p>
        <el-button text :loading="createReconcileLoading" @click="reconcileCreate">刷新并核对商品列表</el-button>
      <div v-if="createReconcileError" class="reconcile-error">{{ createReconcileError }}</div>
      <div v-if="createReconcileChecked" class="reconcile-results">
          <p v-if="createReconcileProducts.length">找到 {{ createReconcileProducts.length }} 件精确同名商品；请从下方结果打开对应商品核对。</p>
          <p v-else>名称检索最多返回100条，本次结果没有精确同名商品。请结合商品列表结果核对后再决定是否重试。</p>
          <div v-for="product in createReconcileProducts" :key="product.id" class="reconcile-item"><span><strong>{{ product.productName }}</strong><small>编号 {{ product.id }} · ¥{{ formatPrice(product.price) }} · {{ statusText(product) }}</small></span><el-button size="small" @click="createVisible = false; openEdit(product)">查看商品</el-button></div>
        </div>
        <el-checkbox v-if="createReconcileChecked && !createReconcileProducts.length" v-model="createRetryAcknowledged">我已查看这次检索结果，仍要手动重试创建</el-checkbox>
      </el-alert>
      <el-form label-position="top" class="product-form" :disabled="createSubmitting || createNeedsReconcile" @submit.prevent="submitCreate">
        <el-form-item label="商品名称" :error="createErrors.productName"><el-input v-model="createDraft.productName" maxlength="15" show-word-limit placeholder="例如：招牌牛肉饭" @input="clearCreateError('productName')" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="售价（元）" :error="createErrors.price"><el-input v-model="createDraft.price" type="number" min="0.01" max="99999999.99" step="0.01" placeholder="0.00" @input="clearCreateError('price')" /></el-form-item>
          <el-form-item label="初始库存" :error="createErrors.stock"><el-input v-model="createDraft.stock" type="number" inputmode="numeric" min="0" max="2147483647" step="1" @input="clearCreateError('stock')" /><small class="field-hint">请输入非负整数</small></el-form-item>
        </div>
        <el-form-item label="商品分类" :error="createErrors.categoryId"><el-select v-model="createDraft.categoryId" filterable placeholder="选择分类" :loading="categoriesLoading" @change="clearCreateError('categoryId')"><el-option v-for="category in categories" :key="category.id" :label="category.categoryName" :value="String(category.id)" /></el-select><small v-if="!categories.length && !categoriesLoading" class="field-hint">还没有可选分类，请先创建分类。</small></el-form-item>
        <el-form-item label="商品描述" :error="createErrors.description"><el-input v-model="createDraft.description" type="textarea" :rows="3" maxlength="100" show-word-limit placeholder="补充口味、分量等信息" @input="clearCreateError('description')" /></el-form-item>
        <el-form-item label="商品图片">
          <div class="image-field"><div class="image-preview"><img v-if="createPreview" :src="createPreview" alt="待上传的商品图片预览" /><span v-else>暂无图片</span></div><div class="image-controls"><input type="file" accept="image/jpeg,image/png" aria-label="选择 JPEG 或 PNG 商品图片" :disabled="createSubmitting" @change="handleImageChange($event, 'create')" /><small class="field-hint">支持 JPEG、PNG，最大 5MB</small><el-button v-if="createDraft.imageFile" text :disabled="createSubmitting" @click="clearImage('create')">移除图片</el-button></div></div>
          <p v-if="createImageError" class="image-error">{{ createImageError }}</p>
        </el-form-item>
      </el-form>
      <template #footer><div class="dialog-footer"><el-button :disabled="createSubmitting" @click="closeCreate">取消</el-button><el-button type="primary" :loading="createSubmitting" :disabled="createNeedsReconcile && (!createReconcileChecked || createReconcileProducts.length > 0 || !createRetryAcknowledged)" @click="submitCreate">创建商品</el-button></div></template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑商品" width="min(620px, 96vw)" destroy-on-close :close-on-click-modal="!editSaving && !editLoading && !confirmLock" :close-on-press-escape="!editSaving && !editLoading && !confirmLock" :before-close="beforeCloseEdit" @closed="resetEditDialog">
      <div v-if="editLoading" class="dialog-loading"><el-skeleton :rows="5" animated /></div>
      <template v-else-if="editDraft && editOriginal">
        <p class="dialog-intro">修改商品资料。库存默认保持不变。</p>
        <el-alert v-if="editLoadError" class="inline-alert" type="error" :closable="false" show-icon :title="editLoadError"><el-button text @click="reloadEditDetail">重新加载商品</el-button></el-alert>
        <el-alert v-if="editLatest" class="inline-alert" type="warning" :closable="false" show-icon>
          <template #title>保存失败，已读取当前资料</template>
          <p>草稿仍保留。当前售价 ¥{{ formatPrice(editLatest.price) }}，库存 {{ editLatest.stock }}，状态为“{{ editLatest.statusDesc || statusText(editLatest) }}”。你可以放弃草稿重新加载，或采用最新版本并继续手动保存。</p>
          <div class="alert-actions"><el-button size="small" @click="replaceEditDraftWithLatest">重新加载并放弃草稿</el-button><el-button size="small" type="primary" @click="rebaseEditDraftOnLatest">保留草稿并采用最新版本</el-button></div>
        </el-alert>
        <el-form label-position="top" class="product-form" :disabled="editSaving || editLoading || !!confirmLock" @submit.prevent="submitEdit">
          <el-form-item label="商品名称" :error="editErrors.productName"><el-input v-model="editDraft.productName" maxlength="15" show-word-limit @input="clearEditError('productName')" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="售价（元）" :error="editErrors.price"><el-input v-model="editDraft.price" type="number" min="0" step="0.01" @input="clearEditError('price')" /><small class="field-hint">现有价格不会因未修改而受限；改价后需在 0 至 99999.99 元内</small></el-form-item>
            <el-form-item label="当前库存"><el-input v-model="editDraft.stock" type="number" inputmode="numeric" min="0" max="10000" step="1" :disabled="!editDraft.resetStock" @input="clearEditError('stock')"/><small class="field-hint">勾选下方选项后可重设，范围为 0 至 10000</small></el-form-item>
          </div>
          <el-form-item label="库存操作" :error="editErrors.stock"><el-checkbox v-model="editDraft.resetStock">我明确要重设库存基准</el-checkbox><small class="field-hint">启用后会要求二次确认；可重设为与当前相同的数值。</small></el-form-item>
          <el-form-item label="商品分类"><div class="category-current">当前分类：<strong>{{ editOriginal.categoryName || '未知分类' }}</strong><small>留空会保留当前分类。</small></div><el-select v-model="editDraft.categoryIdSelection" clearable filterable placeholder="选择新分类（留空则不修改）" :loading="categoriesLoading"><el-option v-for="category in categories" :key="category.id" :label="category.categoryName" :value="String(category.id)" /></el-select></el-form-item>
          <el-form-item label="商品描述" :error="editErrors.description"><el-input v-model="editDraft.description" type="textarea" :rows="3" maxlength="100" show-word-limit @input="clearEditError('description')" /></el-form-item>
          <el-form-item label="商品图片"><div class="image-field"><div class="image-preview"><img v-if="editPreview" :src="editPreview" alt="商品图片预览" /><span v-else>暂无图片</span></div><div class="image-controls"><input type="file" accept="image/jpeg,image/png" aria-label="选择 JPEG 或 PNG 商品图片" :disabled="editSaving || editLoading || !!confirmLock" @change="handleImageChange($event, 'edit')" /><small class="field-hint">支持 JPEG、PNG，最大 5MB。选择新图后会先上传，再保存商品。</small><el-button v-if="editDraft.imageFile" text :disabled="editSaving || editLoading || !!confirmLock" @click="clearImage('edit')">撤销新图片</el-button></div></div><p v-if="editImageError" class="image-error">{{ editImageError }}</p></el-form-item>
          <div class="edit-meta"><span>编号 {{ editingProduct?.id }}</span><span>当前状态：{{ editOriginal.statusDesc || statusText(editOriginal) }}</span><span>版本 {{ editDraft.version }}</span></div>
        </el-form>
      </template>
      <template #footer><div class="dialog-footer"><el-button :disabled="editSaving" @click="editVisible = false">关闭</el-button><el-button v-if="editDraft && editOriginal" type="primary" :loading="editSaving" :disabled="editLoading || !!confirmLock" @click="submitEdit">保存修改</el-button></div></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSession } from '../../auth.js'
import {
  createMerchantProduct, deleteMerchantProduct, getMerchantProductForEdit,
  listMerchantCategories, listMerchantProducts, restoreMerchantProduct,
  setMerchantProductShelf, updateMerchantProduct, uploadMerchantProductImage,
} from '../../api/catalogApi.js'
import { knownDeletedProducts } from './catalogSessionState.js'
import {
  buildCreateProductPayload, buildUpdateProductPayload, createEditProductDraft,
  createEmptyProductDraft, samePrice, validateProductDraft, validateProductImage,
} from './productDraft.js'

const defaultImage = '/images/default-product.svg'
const products = ref([]), categories = ref([]), loading = ref(false), categoriesLoading = ref(false)
const loadError = ref(''), categoryLoadError = ref(''), total = ref(0), pageNum = ref(1), pageSize = ref(10)
const keywordInput = ref(''), statusInput = ref(null), categoryInput = ref('')
const filters = reactive({ keyword: '', status: null, categoryId: '' })
const rowBusy = reactive({}), confirmLock = ref('')
const deletedItems = computed(() => [...knownDeletedProducts.values()].filter((item) => String(item.merchantId) === String(getSession('merchant')?.id)))
const currentPageCounts = computed(() => products.value.reduce((counts, product) => {
  if (product.status === 0) counts.onSale += 1
  else if (product.status === 1) counts.offSale += 1
  else if (product.status === 2) counts.soldOut += 1
  return counts
}, { onSale: 0, offSale: 0, soldOut: 0 }))

let active = true, productRequest = 0, categoryRequest = 0, editRequest = 0
const sessionStillMatches = (token) => active && getSession('merchant')?.token === token

async function loadProducts() {
  const token = getSession('merchant')?.token
  const requestId = ++productRequest
  loading.value = true
  loadError.value = ''
  try {
    const response = await listMerchantProducts({
      pageNum: pageNum.value, pageSize: pageSize.value,
      status: filters.status, categoryId: filters.categoryId || undefined,
      keyword: filters.keyword || undefined,
    })
    if (!sessionStillMatches(token) || requestId !== productRequest) return
    products.value = Array.isArray(response?.list) ? response.list : []
    total.value = Number(response?.total ?? products.value.length) || 0
    pageNum.value = Number(response?.pageNum ?? pageNum.value)
  } catch (error) {
    if (!sessionStillMatches(token) || requestId !== productRequest) return
    loadError.value = error.message || '商品列表加载失败'
  } finally {
    if (sessionStillMatches(token) && requestId === productRequest) loading.value = false
  }
}

async function loadCategories() {
  const token = getSession('merchant')?.token
  const requestId = ++categoryRequest
  categoriesLoading.value = true
  categoryLoadError.value = ''
  try {
    const response = await listMerchantCategories()
    if (!sessionStillMatches(token) || requestId !== categoryRequest) return
    categories.value = Array.isArray(response) ? response : []
  } catch (error) {
    if (!sessionStillMatches(token) || requestId !== categoryRequest) return
    categoryLoadError.value = error.message || '分类列表加载失败'
  } finally {
    if (sessionStillMatches(token) && requestId === categoryRequest) categoriesLoading.value = false
  }
}

function reloadAll() { void loadProducts(); void loadCategories() }
function applyFilters() {
  filters.keyword = keywordInput.value.trim()
  filters.status = statusInput.value === '' || statusInput.value == null ? null : Number(statusInput.value)
  filters.categoryId = categoryInput.value || ''
  pageNum.value = 1
  void loadProducts()
}
function clearFilters() {
  keywordInput.value = ''; statusInput.value = null; categoryInput.value = ''
  filters.keyword = ''; filters.status = null; filters.categoryId = ''
  pageNum.value = 1
  void loadProducts()
}
function changePageSize() { pageNum.value = 1; void loadProducts() }
function statusText(product) { return product?.statusDesc || ({ 0: '正在销售', 1: '已经下架', 2: '已售罄' }[product?.status] || '未知状态') }
function statusType(status) { return ({ 0: 'success', 1: 'info', 2: 'warning' }[status] || 'info') }
function formatPrice(value) { const amount = Number(value); return Number.isFinite(amount) ? amount.toFixed(2) : '—' }
function useFallbackImage(event) {
  const image = event.currentTarget
  const source = image.getAttribute('src') || ''
  if (source === defaultImage || image.dataset.fallbackFor === source) return
  image.dataset.fallbackFor = source
  image.src = defaultImage
}
function setRowBusy(id, action) { rowBusy[id] = action }
function clearRowBusy(id) { delete rowBusy[id] }
function isCancel(error) { return error === 'cancel' || error === 'close' || error?.action === 'cancel' || error?.action === 'close' }

async function runConfirmed(key, title, message, operation) {
  if (confirmLock.value) return
  const token = getSession('merchant')?.token
  confirmLock.value = key
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消', closeOnClickModal: false, distinguishCancelAndClose: true })
    if (!sessionStillMatches(token)) return
    await operation(token)
  } catch (error) {
    if (!isCancel(error) && sessionStillMatches(token)) ElMessage.error(error.message || '操作失败，已保留当前页面数据')
  } finally {
    if (active) confirmLock.value = ''
  }
}

async function changeShelf(product, onShelf) {
  const actionText = onShelf ? '上架' : '下架'
  setRowBusy(product.id, 'shelf')
  await runConfirmed(`shelf-${product.id}`, `确认${actionText}`, `确定要${actionText}“${product.productName}”吗？`, async (token) => {
    try {
      const updated = await setMerchantProductShelf(product.id, onShelf)
      if (!sessionStillMatches(token)) return
      ElMessage.success(`${actionText}状态已更新`)
      products.value = products.value.map((item) => item.id === product.id ? { ...item, ...(updated || {}) } : item)
    } catch (error) {
      if (!sessionStillMatches(token)) return
      ElMessage.error(error.message || `${actionText}未完成，已刷新商品状态`)
      await loadProducts()
    }
  })
  clearRowBusy(product.id)
}

async function removeProduct(product) {
  setRowBusy(product.id, 'delete')
  await runConfirmed(`delete-${product.id}`, '删除商品', `删除“${product.productName}”后会从商品列表隐藏。可以在本次页面会话中恢复。`, async (token) => {
    try {
      await deleteMerchantProduct(product.id)
      if (!sessionStillMatches(token)) return
      const merchantId = getSession('merchant')?.id
      knownDeletedProducts.set(`${merchantId}:${product.id}`, { id: product.id, merchantId, productName: product.productName })
      ElMessage.success('商品已删除，可在当前会话的恢复区域找回')
      await loadProducts()
    } catch (error) {
      if (!sessionStillMatches(token)) return
      ElMessage.error(error.message || '删除未完成，已刷新商品列表')
      await loadProducts()
    }
  })
  clearRowBusy(product.id)
}

async function restoreProduct(product) {
  setRowBusy(product.id, 'restore')
  await runConfirmed(`restore-${product.id}`, '恢复商品', `恢复“${product.productName}”后会以已下架状态重新出现在商品列表。`, async (token) => {
    try {
      await restoreMerchantProduct(product.id)
      if (!sessionStillMatches(token)) return
      const merchantId = getSession('merchant')?.id
      knownDeletedProducts.delete(`${merchantId}:${product.id}`)
      ElMessage.success('商品已恢复，当前为下架状态')
      await loadProducts()
    } catch (error) {
      if (!sessionStillMatches(token)) return
      ElMessage.error(error.message || '恢复未完成，请稍后核对商品状态')
    }
  })
  clearRowBusy(product.id)
}

const createVisible = ref(false), createSubmitting = ref(false), createDraft = reactive(createEmptyProductDraft())
const createErrors = reactive({}), createImageError = ref(''), createPreview = ref('')
const createNeedsReconcile = ref(false), createRetryAcknowledged = ref(false)
const createReconcileName = ref(''), createReconcileLoading = ref(false), createReconcileChecked = ref(false)
const createReconcileProducts = ref([]), createReconcileError = ref('')
function openCreate() {
  if (categories.value.length === 0 && !categoriesLoading.value) void loadCategories()
  createVisible.value = true
}
function beforeCloseCreate(done) {
  if (createSubmitting.value) return
  if (createNeedsReconcile.value && !createReconcileChecked.value) {
    ElMessage.warning('请先刷新并查看商品名称检索结果')
    return
  }
  if (createNeedsReconcile.value && !createReconcileProducts.value.length && !createRetryAcknowledged.value) {
    ElMessage.warning('请确认已查看检索结果后再关闭')
    return
  }
  done()
}
function closeCreate() {
  if (createSubmitting.value) return
  if (createNeedsReconcile.value && !createReconcileChecked.value) {
    ElMessage.warning('请先刷新并查看商品名称检索结果')
    return
  }
  if (createNeedsReconcile.value && !createReconcileProducts.value.length && !createRetryAcknowledged.value) {
    ElMessage.warning('请确认已查看检索结果后再关闭')
    return
  }
  createVisible.value = false
}
function clearCreateError(key) { delete createErrors[key] }
function checkDraft(draft, mode, destination, original = null) {
  Object.keys(destination).forEach((key) => delete destination[key])
  Object.assign(destination, validateProductDraft(draft, mode, original))
  return Object.keys(destination).length === 0
}
function revokePreview(previewRef) {
  if (previewRef.value.startsWith('blob:')) URL.revokeObjectURL(previewRef.value)
  previewRef.value = ''
}
function handleImageChange(event, mode) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const error = validateProductImage(file)
  if (mode === 'create') createImageError.value = error
  else editImageError.value = error
  const draft = mode === 'create' ? createDraft : editDraft.value
  const previewRef = mode === 'create' ? createPreview : editPreview
  if (!draft) return
  if (error) {
    draft.imageFile = null
    revokePreview(previewRef)
    if (mode === 'edit') previewRef.value = draft.imageUrl || ''
    return
  }
  revokePreview(previewRef)
  draft.imageFile = file
  previewRef.value = URL.createObjectURL(file)
}
function clearImage(mode) {
  const draft = mode === 'create' ? createDraft : editDraft.value
  if (!draft) return
  draft.imageFile = null
  if (mode === 'create') createImageError.value = ''
  else editImageError.value = ''
  revokePreview(mode === 'create' ? createPreview : editPreview)
  if (mode === 'edit') editPreview.value = draft.imageUrl || ''
}

async function submitCreate() {
  if (createSubmitting.value || (createNeedsReconcile.value && !createRetryAcknowledged.value)) return
  if (!checkDraft(createDraft, 'create', createErrors)) return
  if (createDraft.imageFile) {
    const imageError = validateProductImage(createDraft.imageFile)
    if (imageError) { createImageError.value = imageError; return }
  }
  const token = getSession('merchant')?.token
  const draftSnapshot = { ...createDraft }
  const imageFile = draftSnapshot.imageFile
  const submittedName = String(draftSnapshot.productName).trim()
  createSubmitting.value = true
  createNeedsReconcile.value = false
  createRetryAcknowledged.value = false
  createReconcileChecked.value = false
  createReconcileProducts.value = []
  createReconcileError.value = ''
  let createRequestSent = false
  try {
    let imageUrl = ''
    if (imageFile) imageUrl = await uploadMerchantProductImage(imageFile)
    if (!sessionStillMatches(token)) return
    createRequestSent = true
    await createMerchantProduct(buildCreateProductPayload(draftSnapshot, imageUrl))
    if (!sessionStillMatches(token)) return
    ElMessage.success('商品已创建，当前为下架状态')
    createVisible.value = false
    keywordInput.value = ''; statusInput.value = null; categoryInput.value = ''
    filters.keyword = ''; filters.status = null; filters.categoryId = ''
    pageNum.value = 1
    await loadProducts()
  } catch (error) {
    if (!sessionStillMatches(token)) return
    ElMessage.error(error.message || '商品创建未完成')
    if (createRequestSent) {
      createNeedsReconcile.value = true
      createReconcileName.value = submittedName
    } else if (imageFile && error.kind !== 'stale') createImageError.value = `图片上传失败：${error.message || '请重试'}`
  } finally {
    if (sessionStillMatches(token)) createSubmitting.value = false
  }
}

function resetCreateDraft() {
  Object.assign(createDraft, createEmptyProductDraft())
  Object.keys(createErrors).forEach((key) => delete createErrors[key])
  createImageError.value = ''; createNeedsReconcile.value = false; createRetryAcknowledged.value = false
  createReconcileName.value = ''; createReconcileChecked.value = false; createReconcileProducts.value = []; createReconcileError.value = ''
  revokePreview(createPreview)
}

async function reconcileCreate() {
  const name = createReconcileName.value || createDraft.productName.trim()
  if (!name || createReconcileLoading.value) return
  const token = getSession('merchant')?.token
  createReconcileLoading.value = true; createReconcileError.value = ''; createReconcileChecked.value = false
  createRetryAcknowledged.value = false; createReconcileProducts.value = []
  try {
    const response = await listMerchantProducts({ pageNum: 1, pageSize: 100, keyword: name })
    if (!sessionStillMatches(token)) return
    const matches = Array.isArray(response?.list) ? response.list : []
    createReconcileProducts.value = matches.filter((product) => String(product.productName ?? '').trim() === name)
    createReconcileChecked.value = true
  } catch (error) {
    if (!sessionStillMatches(token)) return
    createReconcileError.value = error.message || '商品列表核对失败，请稍后刷新'
  } finally {
    if (sessionStillMatches(token)) createReconcileLoading.value = false
  }
}

const editVisible = ref(false), editLoading = ref(false), editSaving = ref(false), editLoadError = ref('')
const editingProduct = ref(null), editOriginal = ref(null), editDraft = ref(null), editLatest = ref(null)
const editErrors = reactive({}), editImageError = ref(''), editPreview = ref('')
function clearEditError(key) { delete editErrors[key] }

async function openEdit(product, resetInventory = false) {
  if (categories.value.length === 0 && !categoriesLoading.value) void loadCategories()
  const token = getSession('merchant')?.token
  const requestId = ++editRequest
  editVisible.value = true; editLoading.value = true; editLoadError.value = ''
  editingProduct.value = product; editOriginal.value = null; editDraft.value = null; editLatest.value = null
  try {
    const detail = await getMerchantProductForEdit(product.id)
    if (!sessionStillMatches(token) || requestId !== editRequest) return
    editOriginal.value = { ...detail }
    editDraft.value = reactive(createEditProductDraft(detail))
    if (resetInventory) editDraft.value.resetStock = true
    editPreview.value = detail.imageUrl || ''
  } catch (error) {
    if (!sessionStillMatches(token) || requestId !== editRequest) return
    editLoadError.value = error.message || '商品资料加载失败'
  } finally {
    if (sessionStillMatches(token) && requestId === editRequest) editLoading.value = false
  }
}

async function reloadEditDetail() {
  if (!editingProduct.value) return
  const token = getSession('merchant')?.token
  const requestId = ++editRequest
  editLoading.value = true; editLoadError.value = ''; editLatest.value = null
  try {
    const detail = await getMerchantProductForEdit(editingProduct.value.id)
    if (!sessionStillMatches(token) || requestId !== editRequest) return
    editOriginal.value = { ...detail }
    editDraft.value = reactive(createEditProductDraft(detail))
    editPreview.value = detail.imageUrl || ''
    Object.keys(editErrors).forEach((key) => delete editErrors[key])
    editImageError.value = ''
  } catch (error) {
    if (!sessionStillMatches(token) || requestId !== editRequest) return
    editLoadError.value = error.message || '商品资料重新加载失败'
  } finally {
    if (sessionStillMatches(token) && requestId === editRequest) editLoading.value = false
  }
}

function beforeCloseEdit(done) {
  if (editSaving.value || confirmLock.value) {
    ElMessage.warning('操作完成前不能关闭商品编辑窗口')
    return
  }
  done()
}

async function refreshLatestAfterEditFailure(token, productId) {
  const requestId = ++editRequest
  try {
    const latest = await getMerchantProductForEdit(productId)
    if (!sessionStillMatches(token) || requestId !== editRequest || String(editingProduct.value?.id) !== String(productId)) return
    editLatest.value = { ...latest }
  } catch (refreshError) {
    if (sessionStillMatches(token) && requestId === editRequest) {
      editLoadError.value = `草稿已保留，但当前商品资料暂时无法读取：${refreshError.message || '请稍后重试'}`
    }
  }
}

function replaceEditDraftWithLatest() {
  if (!editLatest.value) return
  const detail = { ...editLatest.value }
  editOriginal.value = detail
  editDraft.value = reactive(createEditProductDraft(detail))
  editPreview.value = detail.imageUrl || ''
  editLatest.value = null
  Object.keys(editErrors).forEach((key) => delete editErrors[key])
  editImageError.value = ''
}

function rebaseEditDraftOnLatest() {
  if (!editLatest.value || !editDraft.value || !editOriginal.value) return
  const previousOriginal = editOriginal.value
  const latest = { ...editLatest.value }
  const draft = editDraft.value
  const dirty = {
    productName: String(draft.productName ?? '').trim() !== String(previousOriginal.productName ?? '').trim(),
    description: String(draft.description ?? '').trim() !== String(previousOriginal.description ?? '').trim(),
    price: !samePrice(draft.price, previousOriginal.price),
    image: !!draft.imageFile,
    stock: !!draft.resetStock,
  }
  editOriginal.value = latest
  if (!dirty.productName) draft.productName = latest.productName ?? ''
  if (!dirty.description) draft.description = latest.description ?? ''
  if (!dirty.price) draft.price = String(latest.price ?? '')
  if (!dirty.image) { draft.imageUrl = latest.imageUrl || ''; editPreview.value = latest.imageUrl || '' }
  if (!dirty.stock) draft.stock = latest.stock ?? 0
  draft.version = latest.version
  editLatest.value = null
  ElMessage.info('已保留草稿并更新版本；检查内容后再手动保存')
}

async function performEditSave(token) {
  const draft = editDraft.value ? { ...editDraft.value } : null
  const original = editOriginal.value ? { ...editOriginal.value } : null
  const productId = editingProduct.value?.id
  if (!draft || !original || !productId || editSaving.value) return
  if (!checkDraft(draft, 'edit', editErrors, original)) return
  const imageFile = draft.imageFile
  if (imageFile) {
    const imageError = validateProductImage(imageFile)
    if (imageError) { editImageError.value = imageError; return }
  }
  editSaving.value = true
  editLoadError.value = ''
  try {
    let uploadedImageUrl = ''
    if (imageFile) uploadedImageUrl = await uploadMerchantProductImage(imageFile)
    if (!sessionStillMatches(token)) return
    const payload = buildUpdateProductPayload(draft, original, {
      categoryId: draft.categoryIdSelection,
      uploadedImageUrl,
      resetStock: draft.resetStock,
    })
    if (Object.keys(payload).length === 1) {
      ElMessage.info('没有需要保存的修改')
      return
    }
    await updateMerchantProduct(productId, payload)
    if (!sessionStillMatches(token)) return
    ElMessage.success('商品资料已更新')
    editVisible.value = false
    await loadProducts()
  } catch (error) {
    if (!sessionStillMatches(token)) return
    ElMessage.error(error.message || '保存失败，草稿仍保留')
    await refreshLatestAfterEditFailure(token, productId)
  } finally {
    if (sessionStillMatches(token)) editSaving.value = false
  }
}

async function submitEdit() {
  if (editSaving.value || confirmLock.value || !editDraft.value || !editOriginal.value) return
  if (!checkDraft(editDraft.value, 'edit', editErrors, editOriginal.value)) return
  const token = getSession('merchant')?.token
  if (!editDraft.value.resetStock) return performEditSave(token)
  await runConfirmed(`stock-${editingProduct.value?.id}`, '确认重设库存', `将库存重设为 ${editDraft.value.stock}。即使数值与当前库存相同，这也会重新设定库存基准。确定继续吗？`, async (confirmedToken) => performEditSave(confirmedToken))
}

function resetEditDialog() {
  editRequest += 1
  editOriginal.value = null; editDraft.value = null; editingProduct.value = null; editLatest.value = null
  editLoading.value = false; editLoadError.value = ''; editImageError.value = ''
  Object.keys(editErrors).forEach((key) => delete editErrors[key])
  revokePreview(editPreview)
}

onMounted(() => { void loadProducts(); void loadCategories() })
onBeforeUnmount(() => {
  active = false
  productRequest += 1; categoryRequest += 1; editRequest += 1
  revokePreview(createPreview); revokePreview(editPreview)
})
</script>

<style scoped>
.catalog-page{display:grid;gap:22px}.catalog-heading{align-items:center;margin-bottom:0}.heading-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.catalog-tab{padding:8px 13px;border-radius:8px;color:#66756d;text-decoration:none;font-size:13px;font-weight:650}.catalog-tab[aria-current="page"]{background:#e8f0e9;color:#195d48}.catalog-overview{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.overview-card{display:flex;align-items:center;gap:16px;padding:20px 23px;background:#fff;border:1px solid #e2e8df;border-radius:14px;box-shadow:0 7px 20px #334f3b08}.overview-card-soft{background:#f4f8f3}.overview-card-warm{background:#fffaf0}.overview-icon{width:43px;height:43px;display:grid;place-items:center;border-radius:12px;background:#e7f0e8;color:#1b674f;font:25px Georgia,serif}.overview-card-warm .overview-icon{background:#f8ead0;color:#a16314}.overview-card strong,.overview-label,.overview-card small{display:block}.overview-label{color:#69786f;font-size:12px}.overview-card strong{font-size:24px;line-height:1.35;color:#20372f;font-variant-numeric:tabular-nums}.overview-card small{margin-top:2px;color:#829087;font-size:11px}.filter-surface{padding:23px}.filter-title{display:flex;justify-content:space-between;align-items:flex-start}.filter-title h2{margin:0;font-size:18px}.filter-title p{margin:3px 0 0;font-size:12px}.filters{display:grid;grid-template-columns:minmax(220px,1.5fr) minmax(145px,.8fr) minmax(160px,1fr) auto auto;gap:10px;margin-top:18px}.inline-alert{margin-top:14px}.recovery-alert{margin-top:16px}.recovery-alert p{margin:3px 0 10px}.recovery-list{display:grid;gap:8px}.recovery-item{display:flex;justify-content:space-between;align-items:center;gap:15px;padding:10px 12px;border:1px solid #d7e2df;border-radius:9px;background:#ffffffa8}.recovery-item span,.recovery-item small{display:block}.recovery-item strong{font-size:13px}.recovery-item small{color:#738178;font-size:11px}.reconcile-results{display:grid;gap:7px;margin:9px 0 3px}.reconcile-results p,.reconcile-error{font-size:12px;line-height:1.65;color:#627267}.reconcile-item{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 10px;border:1px solid #d8e5dd;border-radius:8px;background:#ffffffa8}.reconcile-item span,.reconcile-item small{display:block}.reconcile-item strong{font-size:12px}.reconcile-item small{color:#76847b;font-size:10px}.table-surface{padding:0;overflow:hidden}.table-meta{display:flex;gap:12px;padding:19px 22px 13px;color:#637269;font-size:12px}.product-table{width:100%}.product-cell{display:flex;align-items:center;gap:13px;min-width:230px}.product-thumb{width:58px;height:58px;flex:none;object-fit:cover;border-radius:10px;background:#eff3eb}.product-copy{min-width:0;display:grid;gap:3px}.product-copy strong,.product-copy span,.product-copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.product-copy strong{font-size:13px;color:#263a32}.product-copy span{font-size:11px;color:#527361}.product-copy small{font-size:11px;color:#89958d}.row-actions{display:flex;align-items:center;gap:4px;white-space:nowrap}.table-footer{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:15px 20px;border-top:1px solid #eef0eb;font-size:12px}.dialog-intro{margin:-8px 0 18px;color:#738178;font-size:13px}.product-form{display:grid;gap:0}.product-form :deep(.el-form-item){margin-bottom:13px}.product-form :deep(.el-select),.product-form :deep(.el-input-number){width:100%}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.field-hint{display:block;color:#859189;font-size:11px;line-height:1.55;margin-top:4px}.image-field{display:flex;align-items:center;gap:15px}.image-preview{width:84px;height:84px;border-radius:11px;background:#f0f3ec;display:grid;place-items:center;overflow:hidden;color:#859189;font-size:11px;flex:none}.image-preview img{width:100%;height:100%;object-fit:cover}.image-controls{display:grid;gap:4px}.image-error{color:#bc5147;font-size:12px;margin:5px 0 0}.dialog-footer{display:flex;justify-content:flex-end;gap:10px}.category-current{display:grid;gap:2px;margin-bottom:8px;color:#53645b;font-size:12px}.category-current strong{color:#294439}.category-current small{font-size:11px;color:#87938b}.edit-meta{display:flex;gap:15px;flex-wrap:wrap;padding:13px 14px;border-radius:9px;background:#f5f7f2;color:#7b887f;font-size:11px}.alert-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}.dialog-loading{padding:12px 0 24px}
@media(max-width:900px){.filters{grid-template-columns:repeat(2,minmax(0,1fr))}.keyword-input{grid-column:span 2}.filters .el-button{width:100%}.table-footer{align-items:flex-start;flex-direction:column}.overview-card{padding:17px}.overview-icon{width:37px;height:37px}}
@media(max-width:650px){.catalog-overview{grid-template-columns:1fr}.overview-card{padding:14px 17px}.overview-card strong{font-size:21px}.catalog-heading{align-items:flex-start}.heading-actions{width:100%}.heading-actions .el-button{margin-left:auto}.filters{grid-template-columns:1fr}.keyword-input{grid-column:auto}.filter-surface{padding:18px}.table-meta{padding:16px 17px 10px}.form-grid{grid-template-columns:1fr;gap:0}.image-field{align-items:flex-start}.edit-meta{gap:8px 12px}.table-footer{padding:14px}.row-actions{gap:0}.recovery-item{align-items:flex-start}}
.product-form{grid-template-columns:minmax(0,1fr)}.product-form :deep(.el-form-item__content){min-width:0}.image-field{width:100%;min-width:0}.image-controls{flex:1;min-width:0;grid-template-columns:minmax(0,1fr)}.image-controls input{width:100%;max-width:100%}.product-form :deep(.el-checkbox){height:auto;white-space:normal;align-items:flex-start}.product-form :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.table-scroll-hint{display:none}
@media(max-width:650px){.table-scroll-hint{display:block;margin:0 17px 12px;color:#738178;font-size:11px}}
</style>
