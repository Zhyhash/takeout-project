<template>
  <el-drawer v-model="visible" title="订单详情" :size="'min(560px, 100vw)'" @closed="clearDetail">
    <div v-loading="loading" class="drawer-body">
      <el-alert
        v-if="loadError"
        type="error"
        :title="loadError"
        show-icon
        :closable="false"
      >
        <el-button size="small" text type="primary" @click="fetchDetail">重新加载</el-button>
      </el-alert>

      <template v-else-if="detail">
        <el-descriptions class="order-descriptions" :column="2" border size="small">
          <el-descriptions-item label="订单号" :span="2">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态" :span="2">
            <el-tag :type="statusTagType(detail.status)">{{ detail.statusDesc }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="收货人">{{ detail.receiverName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.receiverPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ detail.receiverAddress || '—' }}</el-descriptions-item>
          <el-descriptions-item label="下单时间" :span="2">{{ formatTime(detail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="支付时间" :span="2">{{ formatTime(detail.payTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.remark || '无' }}</el-descriptions-item>
        </el-descriptions>

        <section class="items-section">
          <div class="section-heading">
            <h4 class="section-title">商品明细</h4>
            <span class="item-count">{{ detail.items?.length || 0 }} 种商品</span>
          </div>
          <div v-if="detail.items?.length" class="item-list">
            <article
              v-for="(row, index) in detail.items"
              :key="`${row.productName}-${index}`"
              class="item-row"
            >
              <el-image
                class="item-image"
                :src="row.productPicture || DEFAULT_PRODUCT_IMAGE"
                fit="cover"
              >
                <template #error>
                  <img class="item-image" :src="DEFAULT_PRODUCT_IMAGE" alt="" />
                </template>
              </el-image>
              <div class="item-main">
                <span class="item-name">{{ row.productName }}</span>
                <div class="item-meta">
                  <span>单价 <strong>¥{{ amountText(row.productPrice) }}</strong></span>
                  <span>数量 <strong>× {{ row.quantity }}</strong></span>
                </div>
              </div>
              <strong class="item-subtotal">¥{{ amountText(row.subtotal) }}</strong>
            </article>
          </div>
          <div v-else class="empty-items">暂无商品明细</div>
        </section>

        <div class="amount-lines">
          <div class="amount-line">
            <span>原价</span>
            <span>¥{{ amountText(detail.originalAmount) }}</span>
          </div>
          <div class="amount-line">
            <span>优惠</span>
            <span>-¥{{ amountText(detail.discountAmount) }}</span>
          </div>
          <div class="amount-line amount-total">
            <span>实付</span>
            <span>¥{{ amountText(detail.totalAmount) }}</span>
          </div>
        </div>
      </template>
    </div>

    <template #footer>
      <template v-if="detail">
        <el-button
          v-if="detail.status === 2"
          type="primary"
          :loading="acting"
          :disabled="acting || loading"
          @click="doAccept"
        >
          接单
        </el-button>
        <el-button
          v-if="detail.status === 2"
          type="danger"
          plain
          :loading="acting"
          :disabled="acting || loading"
          @click="doReject"
        >
          拒单
        </el-button>
        <el-button
          v-else-if="detail.status === 5"
          type="success"
          :loading="acting"
          :disabled="acting || loading"
          @click="doReady"
        >
          出餐
        </el-button>
        <span v-else class="no-action-hint">当前状态无需商家操作</span>
      </template>
    </template>
  </el-drawer>
</template>

<script setup>
import { onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { session } from '../auth.js'
import { acceptOrder, completePreparation, getOrderDetail, rejectOrder } from '../api/merchantApi.js'
import { amountText, formatTime, statusTagType } from '../display.js'

// 后端公开的默认商品图（classpath 静态资源，开发/预览经 Vite 代理访问）。
const DEFAULT_PRODUCT_IMAGE = '/images/default-product.svg'

const emit = defineEmits(['order-updated'])

const visible = ref(false)
const loading = ref(false)
const loadError = ref('')
const detail = ref(null)
const acting = ref(false)
let currentOrderId = null
let detailRequestId = 0

watch(() => session.value?.token ?? null, (token) => {
  detailRequestId += 1
  if (!token) {
    currentOrderId = null
    visible.value = false
    detail.value = null
    loadError.value = ''
    loading.value = false
  }
})

onUnmounted(() => { detailRequestId += 1 })

async function fetchDetail() {
  if (!currentOrderId) return
  const orderId = currentOrderId
  const requestId = ++detailRequestId
  const token = session.value?.token ?? null
  loading.value = true
  loadError.value = ''
  try {
    const nextDetail = await getOrderDetail(orderId)
    if (requestId !== detailRequestId || currentOrderId !== orderId
        || !token || session.value?.token !== token) return
    detail.value = nextDetail
  } catch (error) {
    if (requestId === detailRequestId && currentOrderId === orderId
        && token && session.value?.token === token) {
      loadError.value = error.message || '订单详情加载失败'
    }
  } finally {
    if (requestId === detailRequestId) loading.value = false
  }
}

function show(orderId) {
  currentOrderId = orderId
  visible.value = true
  detail.value = null
  loadError.value = ''
  fetchDetail()
}

function reloadIfShowing(orderId) {
  if (visible.value && orderId === currentOrderId && !acting.value) {
    fetchDetail()
  }
}

function clearDetail() {
  detailRequestId += 1
  currentOrderId = null
  detail.value = null
  loadError.value = ''
  loading.value = false
}

async function runAction(action, successText, { confirmReject = false } = {}) {
  const orderId = currentOrderId
  if (acting.value || !orderId) return
  const token = session.value?.token ?? null
  if (!token) return
  acting.value = true
  try {
    if (confirmReject) {
      await ElMessageBox.confirm(
        '拒单后，订单将被取消并按当前规则模拟退款；此操作无法撤回。',
        '确认拒绝这笔订单？',
        { confirmButtonText: '确认拒单', cancelButtonText: '再想想', type: 'warning' },
      )
    }
    if (session.value?.token !== token) return
    await action(orderId)
    if (session.value?.token !== token) return
    ElMessage.success(successText)
    emit('order-updated')
    if (currentOrderId === orderId && visible.value) await fetchDetail()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && session.value?.token === token) {
      if (currentOrderId === orderId && visible.value) await fetchDetail()
      emit('order-updated')
      ElMessage.error(`${error.message || '操作结果未确认'}；已重新查询订单，请核对状态后再操作。`)
    }
  } finally {
    acting.value = false
  }
}

function doAccept() {
  runAction(acceptOrder, '接单成功')
}

function doReady() {
  runAction(completePreparation, '出餐完成')
}

function doReject() {
  runAction(rejectOrder, '订单已拒绝，系统已按规则模拟退款', { confirmReject: true })
}

defineExpose({ show, reloadIfShowing })
</script>

<style scoped>
.drawer-body {
  min-height: 120px;
  color: #20372f;
}
.order-descriptions {
  overflow: hidden;
  border-radius: 12px;
}
.order-descriptions :deep(.el-descriptions__label) {
  color: #718078;
  white-space: nowrap;
}
.order-descriptions :deep(.el-descriptions__content) {
  color: #20372f;
  overflow-wrap: anywhere;
}
.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 22px 0 10px;
}
.section-title {
  margin: 0;
  color: #20372f;
  font-size: 15px;
  font-weight: 700;
}
.item-count {
  color: #718078;
  font-size: 12px;
}
.item-list {
  overflow: hidden;
  border: 1px solid #e2e8df;
  border-radius: 12px;
  background: #fff;
}
.item-row {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px;
}
.item-row + .item-row {
  border-top: 1px solid #edf0eb;
}
.item-image {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  background: #f5f6f1;
  flex-shrink: 0;
}
.item-main {
  min-width: 0;
}
.item-name {
  display: block;
  color: #20372f;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  overflow-wrap: anywhere;
}
.item-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 5px;
  color: #718078;
  font-size: 12px;
}
.item-meta strong {
  color: #48584f;
  font-weight: 600;
}
.item-subtotal {
  color: #20372f;
  font-size: 14px;
  white-space: nowrap;
}
.empty-items {
  padding: 22px 12px;
  border: 1px dashed #d9e0d8;
  border-radius: 12px;
  color: #718078;
  text-align: center;
  font-size: 13px;
}
.amount-lines {
  margin: 16px 0 4px auto;
  max-width: 260px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #f5f6f1;
}
.amount-line {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  color: #718078;
  font-size: 13px;
  line-height: 25px;
}
.amount-total {
  color: #20372f;
  font-size: 15px;
  font-weight: 700;
  border-top: 1px solid #e2e8df;
  margin-top: 5px;
  padding-top: 7px;
}
.no-action-hint {
  color: #718078;
  font-size: 13px;
}

@media (max-width: 480px) {
  .item-row {
    grid-template-columns: 44px minmax(0, 1fr) auto;
    gap: 9px;
    padding: 10px;
  }
  .item-image {
    width: 44px;
    height: 44px;
  }
  .order-descriptions :deep(.el-descriptions__label) {
    padding: 8px 7px;
  }
  .order-descriptions :deep(.el-descriptions__content) {
    padding: 8px 9px;
  }
}
</style>
