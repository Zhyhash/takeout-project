<template>
  <div class="category-page">
    <header class="page-heading category-heading">
      <div>
        <p class="eyebrow">MERCHANT · CATALOG</p>
        <h1>商品分类</h1>
        <p class="muted">组织店铺商品。删除普通分类时，分类下的商品会迁移到默认分类。</p>
      </div>
      <div class="heading-actions">
        <RouterLink class="catalog-tab" :to="{ name: 'merchant-products' }">商品</RouterLink>
        <RouterLink class="catalog-tab" :to="{ name: 'merchant-categories' }" aria-current="page">分类</RouterLink>
        <el-button type="primary" @click="openCreate">新增分类</el-button>
      </div>
    </header>

    <section class="category-intro-grid">
      <article class="intro-card intro-main">
        <div class="intro-mark" aria-hidden="true">◫</div>
        <div><span class="eyebrow">YOUR MENU, IN ORDER</span><h2>让菜单清楚一点</h2><p>分类可用于商品筛选和菜单整理。每个分类名称最多15个字符，列表显示分类编号与名称。</p></div>
      </article>
      <article class="intro-note"><strong>{{ categories.length }}</strong><span>个可用分类</span><small>当前可用分类</small></article>
    </section>

    <section class="surface category-surface">
      <div class="section-heading"><div><h2>分类列表</h2><p class="muted">默认分类是否可删除由系统规则决定；列表没有提供默认标记。</p></div><el-button text :loading="loading" @click="loadCategories">刷新</el-button></div>
      <el-alert v-if="loadError" class="category-alert" type="error" :closable="false" show-icon :title="loadError"><el-button text @click="loadCategories">重新加载</el-button></el-alert>
      <el-alert class="category-alert" type="info" :closable="false" show-icon>
        <template #title>删除规则</template>
        <p>默认分类不能删除；分类列表没有提供默认标记，删除时会按系统规则校验。删除普通分类会把该分类下商品转到默认分类。</p>
      </el-alert>
      <div v-if="loading && !categories.length" class="category-skeleton"><el-skeleton :rows="3" animated /></div>
      <el-empty v-else-if="!categories.length && !loadError" description="还没有可用分类，创建一个开始整理商品。" :image-size="88"><el-button type="primary" @click="openCreate">新增分类</el-button></el-empty>
      <div v-else class="category-grid">
        <article v-for="(category, index) in categories" :key="category.id" class="category-card">
          <div class="category-card-top"><span class="category-index">{{ String(index + 1).padStart(2, '0') }}</span><el-button circle text type="danger" :loading="busyCategory === String(category.id)" :disabled="!!busyCategory || !!confirmLock" :aria-label="`删除${category.categoryName}`" @click="removeCategory(category)">×</el-button></div>
          <div class="category-name">{{ category.categoryName }}</div>
          <div class="category-card-bottom"><span>分类编号 {{ category.id }}</span><span class="category-dot" aria-hidden="true"></span></div>
        </article>
      </div>
      <div class="category-footer"><span class="muted">共 {{ categories.length }} 个可用分类</span><RouterLink class="text-link" :to="{ name: 'merchant-products' }">返回商品管理 →</RouterLink></div>
    </section>

    <el-dialog v-model="createVisible" title="新增商品分类" width="min(460px, 94vw)" :close-on-click-modal="!creating && !createOutcomePending" :close-on-press-escape="!creating && !createOutcomePending" :before-close="beforeCloseCreate">
      <p class="dialog-intro">创建后即可在商品编辑和筛选中使用。</p>
      <el-alert v-if="createOutcomePending" class="create-alert" type="warning" :closable="false" show-icon>
        <template #title>分类创建结果需要核对</template>
        <p>{{ createOutcomeMessage }}</p>
        <el-button text :loading="loading" @click="loadCategories">刷新分类列表并核对</el-button>
      </el-alert>
      <el-form label-position="top" :disabled="creating || createOutcomePending" @submit.prevent="createCategory">
        <el-form-item label="分类名称" :error="nameError"><el-input v-model="categoryName" autofocus maxlength="15" show-word-limit placeholder="例如：热销主食" @input="nameError = ''" @keyup.enter="createCategory" /></el-form-item>
      </el-form>
      <p class="field-hint">最多15个字符。已存在的分类名称不能重复创建。</p>
      <template #footer><div class="dialog-footer"><el-button :disabled="creating || createOutcomePending" @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="createOutcomePending" @click="createCategory">创建分类</el-button></div></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSession } from '../../auth.js'
import { createMerchantCategory, deleteMerchantCategory, listMerchantCategories } from '../../api/catalogApi.js'

const categories = ref([]), loading = ref(false), loadError = ref('')
const createVisible = ref(false), creating = ref(false), categoryName = ref(''), nameError = ref('')
const createOutcomePending = ref(false), createOutcomeMessage = ref('')
let pendingCreateName = '', createFailureMessage = ''
const busyCategory = ref(''), confirmLock = ref('')
let active = true, requestSequence = 0
const sessionStillMatches = (token) => active && getSession('merchant')?.token === token

async function loadCategories() {
  const token = getSession('merchant')?.token
  const requestId = ++requestSequence
  loading.value = true; loadError.value = ''
  try {
    const result = await listMerchantCategories()
    if (!sessionStillMatches(token) || requestId !== requestSequence) return
    categories.value = Array.isArray(result) ? result : []
    if (pendingCreateName) {
      const submittedName = pendingCreateName
      pendingCreateName = ''
      createOutcomePending.value = false
      const appeared = categories.value.some((category) => String(category.categoryName ?? '').trim() === submittedName)
      nameError.value = appeared
        ? `“${submittedName}”已出现在分类列表中，创建可能已完成，请勿重复提交。`
        : createFailureMessage || '分类列表中没有发现该名称，可核对后再手动重试。'
      createOutcomeMessage.value = nameError.value
      createFailureMessage = ''
    }
    return true
  } catch (error) {
    if (!sessionStillMatches(token) || requestId !== requestSequence) return
    loadError.value = error.message || '分类列表加载失败'
    return false
  } finally {
    if (sessionStillMatches(token) && requestId === requestSequence) loading.value = false
  }
}

function openCreate() { categoryName.value = ''; nameError.value = ''; createVisible.value = true }
function validateName() {
  const name = categoryName.value.trim()
  if (!name) nameError.value = '请输入分类名称'
  else if (name.length > 15) nameError.value = '分类名称不能超过15个字符'
  else if (categories.value.some((category) => String(category.categoryName ?? '').trim() === name)) nameError.value = '分类名称已存在'
  else nameError.value = ''
  return !nameError.value
}

function beforeCloseCreate(done) {
  if (creating.value || createOutcomePending.value) {
    ElMessage.warning('请先核对分类创建结果')
    return
  }
  done()
}

async function createCategory() {
  if (creating.value || !validateName()) return
  const token = getSession('merchant')?.token
  const submittedName = categoryName.value.trim()
  creating.value = true
  try {
    await createMerchantCategory(categoryName.value.trim())
    if (!sessionStillMatches(token)) return
    ElMessage.success('分类已创建')
    createVisible.value = false
    await loadCategories()
  } catch (error) {
    if (!sessionStillMatches(token)) return
    createOutcomePending.value = true
    pendingCreateName = submittedName
    createFailureMessage = error.message || '分类创建未确认，请核对名称后再决定是否重试。'
    createOutcomeMessage.value = '正在重新读取分类列表以确认创建结果。'
    const refreshed = await loadCategories()
    if (!sessionStillMatches(token)) return
    if (!refreshed) createOutcomeMessage.value = '列表刷新失败，创建结果尚未核对。请先刷新分类列表，再决定是否重试。'
  } finally {
    if (sessionStillMatches(token)) creating.value = false
  }
}

async function removeCategory(category) {
  if (confirmLock.value || busyCategory.value) return
  const token = getSession('merchant')?.token
  const id = String(category.id)
  confirmLock.value = id
  busyCategory.value = id
  try {
    await ElMessageBox.confirm(
      `确认删除“${category.categoryName}”吗？默认分类不可删除；普通分类下的商品会转到默认分类。`,
      '删除商品分类',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', closeOnClickModal: false, distinguishCancelAndClose: true },
    )
    if (!sessionStillMatches(token)) return
    try {
      await deleteMerchantCategory(category.id)
      if (!sessionStillMatches(token)) return
      ElMessage.success('分类已删除，商品已转到默认分类')
      await loadCategories()
    } catch (error) {
      if (!sessionStillMatches(token)) return
      ElMessage.error(error.message || '分类删除失败，变更尚未确认')
      await loadCategories()
    }
  } catch (error) {
    if (!sessionStillMatches(token)) return
    if (error?.action !== 'cancel' && error?.action !== 'close' && error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '确认操作未完成')
    }
  } finally {
    if (active) { confirmLock.value = ''; busyCategory.value = '' }
  }
}

onMounted(() => { void loadCategories() })
onBeforeUnmount(() => { active = false; requestSequence += 1 })
</script>

<style scoped>
.category-page{display:grid;gap:22px}.category-heading{align-items:center;margin-bottom:0}.heading-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.catalog-tab{padding:8px 13px;border-radius:8px;color:#66756d;text-decoration:none;font-size:13px;font-weight:650}.catalog-tab[aria-current="page"]{background:#e8f0e9;color:#195d48}.category-intro-grid{display:grid;grid-template-columns:minmax(0,2fr) minmax(190px,.7fr);gap:14px}.intro-card,.intro-note{border:1px solid #e2e8df;border-radius:14px;background:#fff}.intro-main{display:flex;align-items:flex-start;gap:17px;padding:23px;background:linear-gradient(110deg,#f1f6ef,#fff 70%)}.intro-mark{width:46px;height:46px;display:grid;place-items:center;flex:none;border-radius:13px;background:#e2eee4;color:#1b674f;font:28px Georgia,serif}.intro-card .eyebrow{margin-bottom:4px;color:#56816d}.intro-card h2{margin:0 0 5px;font-size:20px}.intro-card p{margin:0;color:#76837a;font-size:12px;line-height:1.7}.intro-note{display:grid;align-content:center;justify-items:center;padding:19px;background:#fffaf0}.intro-note strong{font-size:30px;color:#7a571d;line-height:1.2}.intro-note span{margin-top:3px;font-size:13px;color:#665a43}.intro-note small{margin-top:5px;font-size:10px;color:#958a73}.category-surface{padding:23px}.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.section-heading h2{margin:0;font-size:18px}.section-heading p{margin:4px 0 0;font-size:12px}.category-alert{margin-top:15px}.category-alert p{margin:2px 0}.category-skeleton{padding:20px 0}.category-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:19px}.category-card{min-height:142px;display:flex;flex-direction:column;padding:15px 17px;border:1px solid #e5e9e1;border-radius:12px;background:#fff;transition:transform .16s ease,box-shadow .16s ease}.category-card:hover{transform:translateY(-2px);box-shadow:0 9px 22px #344c3b0d}.category-card-top{display:flex;justify-content:space-between;align-items:center}.category-index{font:600 11px Georgia,serif;letter-spacing:1px;color:#91a197}.category-card-top :deep(.el-button){font-size:20px}.category-name{margin:auto 0 11px;font-size:20px;font-weight:700;letter-spacing:.3px;color:#294439;overflow-wrap:anywhere}.category-card-bottom{display:flex;align-items:center;justify-content:space-between;color:#89958e;font-size:10px}.category-dot{width:7px;height:7px;border-radius:50%;background:#87aa91}.category-footer{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:19px;padding-top:14px;border-top:1px solid #edf0e9;font-size:12px}.dialog-intro{margin:-8px 0 18px;color:#738178;font-size:13px}.field-hint{display:block;color:#859189;font-size:11px;line-height:1.55}.dialog-footer{display:flex;justify-content:flex-end;gap:10px}
@media(max-width:850px){.category-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.category-intro-grid{grid-template-columns:1.6fr .8fr}}
@media(max-width:620px){.category-heading{align-items:flex-start}.heading-actions{width:100%}.heading-actions .el-button{margin-left:auto}.category-intro-grid{grid-template-columns:1fr}.intro-main{padding:18px}.intro-note{grid-template-columns:auto 1fr;justify-items:start;align-items:center;gap:0 10px;padding:14px 18px}.intro-note strong{grid-row:span 2;font-size:25px}.intro-note small{margin:1px 0 0}.category-grid{grid-template-columns:1fr}.category-surface{padding:18px}.category-footer{align-items:flex-start;flex-direction:column}}
@media(prefers-reduced-motion:reduce){.category-card{transition:none}}
</style>
