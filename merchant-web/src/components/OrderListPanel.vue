<template>
  <section class="order-workbench" aria-labelledby="workbench-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">每一份美味，都值得用心</p>
        <h1 id="workbench-title">订单工作台<span class="heading-dot">.</span></h1>
        <p class="page-description">接好每一单，让美味准时出发。</p>
      </div>
      <div class="heading-note" aria-hidden="true">
        <svg viewBox="0 0 64 64" fill="none">
          <path d="M12 43h40M17 40a15 15 0 0 1 30 0M30 22h4M32 22v3M8 48h48M22 10c-4 5 4 5 0 10M33 7c-4 5 4 5 0 10M44 11c-4 5 4 5 0 10" />
        </svg>
        <span>专注出餐<br /><strong>从容接单</strong></span>
      </div>
    </div>

    <div class="board-navigation">
      <el-tabs v-model="activeType" class="order-tabs" @tab-change="onTabChange">
        <el-tab-pane name="PENDING">
          <template #label><span class="tab-label"><i class="tab-dot pending-dot" />待接单</span></template>
        </el-tab-pane>
        <el-tab-pane name="PROCESSING">
          <template #label><span class="tab-label"><i class="tab-dot processing-dot" />制作中</span></template>
        </el-tab-pane>
        <el-tab-pane name="HISTORY">
          <template #label><span class="tab-label"><i class="tab-dot history-dot" />后续订单</span></template>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div class="list-toolbar">
      <div class="list-context">
        <h2>{{ activeType === 'PENDING' ? '等待接单' : activeType === 'PROCESSING' ? '正在用心制作' : '后续订单' }}</h2>
        <span v-if="!listLoading && !listError" class="order-count">{{ total }} 单</span>
        <span class="list-hint">{{ typeLabel }}</span>
      </div>
      <el-button class="refresh-button" :loading="listLoading" @click="fetchList">
        <svg v-if="!listLoading" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M15.5 7A6 6 0 1 0 16 12M15.5 3.5V7H12" /></svg>
        刷新
      </el-button>
    </div>

    <el-alert
      v-if="listError"
      type="error"
      :title="listError"
      description="点击上方“刷新”重试；若提示登录失效，请重新登录。"
      show-icon
      :closable="false"
      class="list-error"
    />

    <div
      v-if="activeType !== 'HISTORY'"
      v-loading="listLoading"
      class="card-board"
      :aria-busy="listLoading"
    >
      <div v-if="orders.length" class="order-grid">
        <article
          v-for="order in orders"
          :key="order.orderId"
          class="order-card"
          :class="{ 'order-card-pending': order.status === 2 }"
        >
          <div class="card-status-line">
            <el-tag :type="statusTagType(order.status)" effect="plain">{{ order.statusDesc }}</el-tag>
            <NewOrderBadge :order-id="order.orderId" :unseen="order.status === 2 && order.merchantSeen === false" />
            <span class="wait-time">
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7" /><path d="M10 6v4l2.5 1.5" /></svg>
              {{ order.status === 2 ? '已等待' : '下单已过' }} {{ elapsedTime(order.createTime) }}
            </span>
          </div>
          <div class="card-content">
            <div class="order-number"><span>订单号</span><strong>{{ order.orderNo }}</strong></div>
            <div class="product-block">
              <span class="section-caption">商品 · 数量</span>
              <h3 class="product-summary">{{ summaryParts(order.productSummary).name }}<span v-if="summaryParts(order.productSummary).quantity" class="product-quantity">{{ summaryParts(order.productSummary).quantity }}</span></h3>
            </div>
            <div class="customer-line"><span class="customer-icon" aria-hidden="true">客</span><span>{{ order.receiverName || '—' }}</span><span class="customer-label">收货人</span></div>
            <div class="order-time"><span>下单时间</span><time :datetime="order.createTime">{{ formatTime(order.createTime) }}</time></div>
          </div>
          <div class="card-footer">
            <div class="order-amount"><span>实付金额</span><strong><small>¥</small>{{ amountText(order.totalAmount) }}</strong></div>
            <div class="card-actions">
              <el-button class="detail-button" :disabled="isBusy(order.orderId)" @click="openDetail(order.orderId)">详情</el-button>
              <el-button
                v-if="order.status === 2"
                type="primary"
                class="primary-action"
                :loading="isBusy(order.orderId)"
                :disabled="listLoading"
                @click="accept(order)"
              >接单</el-button>
              <el-button
                v-if="order.status === 2"
                type="danger"
                plain
                :loading="isBusy(order.orderId)"
                :disabled="listLoading"
                @click="reject(order)"
              >拒单</el-button>
              <el-button
                v-else-if="order.status === 5"
                type="success"
                class="primary-action"
                :loading="isBusy(order.orderId)"
                :disabled="listLoading"
                @click="ready(order)"
              >出餐</el-button>
            </div>
          </div>
        </article>
      </div>
      <div v-else class="empty-board" role="status">
        <span class="empty-symbol" aria-hidden="true">
          <svg viewBox="0 0 48 48" fill="none"><path d="M14 10h20v30H14zM20 10V7h8v3M19 19h10M19 25h10M19 31h6" /></svg>
        </span>
        <h3>{{ listLoading ? '正在加载订单' : listError ? '订单暂时无法加载' : activeType === 'PENDING' ? '暂时没有待接订单' : '当前没有制作中的订单' }}</h3>
        <p>{{ listLoading ? '请稍候，美味正在路上。' : listError ? '请点击刷新重试。' : '可以稍作休息，点击刷新查看最新订单。' }}</p>
      </div>
    </div>

    <div v-else class="history-surface">
      <p class="table-scroll-hint">左右滑动，查看完整订单 →</p>
      <el-table
      v-loading="listLoading"
      class="history-table"
      :data="orders"
      :empty-text="emptyText"
      row-key="orderId"
      size="small"
    >
      <el-table-column prop="orderNo" label="订单号" min-width="170" />
      <el-table-column
        prop="productSummary"
        label="商品"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column prop="receiverName" label="收货人" width="110" />
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }">¥{{ amountText(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="180">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.statusDesc }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" min-width="204" class-name="time-column">
        <template #default="{ row }"><time class="history-time" :datetime="row.createTime">{{ formatTime(row.createTime) }}</time></template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 2"
            type="primary"
            size="small"
            :loading="isBusy(row.orderId)"
            :disabled="listLoading"
            @click="accept(row)"
          >
            接单
          </el-button>
          <el-button
            v-if="row.status === 2"
            type="danger"
            plain
            size="small"
            :loading="isBusy(row.orderId)"
            :disabled="listLoading"
            @click="reject(row)"
          >
            拒单
          </el-button>
          <el-button
            v-else-if="row.status === 5"
            type="success"
            size="small"
            :loading="isBusy(row.orderId)"
            :disabled="listLoading"
            @click="ready(row)"
          >
            出餐
          </el-button>
          <el-button
            size="small"
            text
            type="primary"
            :disabled="isBusy(row.orderId)"
            @click="openDetail(row.orderId)"
          >
            详情
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <div class="pagination-wrap">
      <span class="pagination-note">{{ activeType === 'HISTORY' ? '每一单，都有迹可循。' : '从接单到出餐，用心做好每一步。' }}</span>
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        :pager-count="5"
        layout="total, sizes, prev, pager, next"
        @current-change="fetchList"
        @size-change="onSizeChange"
      />
    </div>

    <OrderDetailDrawer ref="drawerRef" @order-updated="fetchList" />
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { session } from '../auth.js'
import { acceptOrder, completePreparation, listMerchantOrders, rejectOrder } from '../api/merchantApi.js'
import { amountText, formatTime, statusTagType } from '../display.js'
import OrderDetailDrawer from './OrderDetailDrawer.vue'
import NewOrderBadge from '../merchant/notifications/NewOrderBadge.vue'
import { notificationState } from '../merchant/notifications/state.js'

const TYPE_LABELS = {
  PENDING: '已支付、等待商家接单',
  PROCESSING: '商家已接单、正在备餐',
  HISTORY: '已出餐、配送中、已完成或已取消的订单',
}

const activeType = ref('PENDING')
const orders = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const listLoading = ref(false)
const listError = ref('')
const busyMap = reactive({})
const drawerRef = ref(null)
watch(() => notificationState.revision, () => {
  if (session.value?.token && document.visibilityState !== 'hidden') void fetchList()
})

// 仅更新等待时长的展示，不轮询接口，也不参与订单状态判断。
const displayNow = ref(Date.now())
let displayTimer
let listRequestId = 0
onMounted(() => {
  displayTimer = setInterval(() => { displayNow.value = Date.now() }, 30000)
})
onUnmounted(() => {
  clearInterval(displayTimer)
  listRequestId += 1
})

watch(() => session.value?.token ?? null, (token) => {
  listRequestId += 1
  if (token) {
    void fetchList()
  } else {
    listLoading.value = false
    orders.value = []
    total.value = 0
  }
}, { immediate: true })

function elapsedTime(value) {
  if (!value) return '—'
  const createdAt = new Date(String(value).replace(' ', 'T')).getTime()
  if (!Number.isFinite(createdAt) || createdAt > displayNow.value) return '—'
  const minutes = Math.floor((displayNow.value - createdAt) / 60000)
  if (minutes < 1) return '不足 1 分钟'
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分钟`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function summaryParts(value) {
  // 后端多件摘要以“等 N 件商品”结尾，单件摘要只有商品名；空摘要不推断数量。
  const summary = String(value || '暂无商品摘要')
  const parts = summary.match(/^(.*)( 等 \d+ 件商品)$/s)
  return parts ? { name: parts[1], quantity: parts[2] } : { name: summary, quantity: value ? '1 件商品' : '' }
}

const typeLabel = computed(() => TYPE_LABELS[activeType.value] || '')
const emptyText = computed(() =>
  listLoading.value ? '加载中…' : `${TYPE_LABELS[activeType.value] || '订单'}：暂无数据`,
)

function isBusy(orderId) {
  return Boolean(busyMap[orderId])
}

function onTabChange() {
  pageNum.value = 1
  fetchList()
}

function onSizeChange() {
  // 改变每页条数后回到第一页；若页码因此变化，current-change 已触发加载。
  if (pageNum.value === 1) {
    fetchList()
  } else {
    pageNum.value = 1
  }
}

async function fetchList() {
  const requestId = ++listRequestId
  const token = session.value?.token ?? null
  listLoading.value = true
  listError.value = ''
  try {
    let query = { type: activeType.value, pageNum: pageNum.value, pageSize: pageSize.value }
    let data
    while (true) {
      data = await listMerchantOrders(query.type, query.pageNum, query.pageSize)
      if (requestId !== listRequestId || !token || session.value?.token !== token) return false
      // If the last row on a page moved to another state, continue one page back under this request.
      if (data?.list?.length || query.pageNum <= 1) break
      pageNum.value = query.pageNum - 1
      query = { ...query, pageNum: pageNum.value }
    }
    orders.value = data?.list ?? []
    total.value = Number(data?.total ?? 0)
    return true
  } catch (error) {
    if (requestId === listRequestId && token && session.value?.token === token) {
      listError.value = error.message || '订单列表加载失败'
    }
  } finally {
    if (requestId === listRequestId) listLoading.value = false
  }
}

function openDetail(orderId) {
  drawerRef.value?.show(orderId)
}

async function runAction(row, action, successText, { confirmReject = false } = {}) {
  if (busyMap[row.orderId]) return
  const token = session.value?.token ?? null
  if (!token) return
  busyMap[row.orderId] = true
  try {
    if (confirmReject) {
      await ElMessageBox.confirm(
        '拒单后，订单将被取消并按当前规则模拟退款；此操作无法撤回。',
        '确认拒绝这笔订单？',
        { confirmButtonText: '确认拒单', cancelButtonText: '再想想', type: 'warning' },
      )
    }
    if (session.value?.token !== token) return
    await action(row.orderId)
    if (session.value?.token !== token) return
    ElMessage.success(successText)
    await fetchList()
    drawerRef.value?.reloadIfShowing(row.orderId)
  } catch (error) {
    // The request could have reached the server before a network timeout. Reconcile the
    // list, then ask the merchant to inspect its authoritative status instead of resending.
    if (error !== 'cancel' && error !== 'close' && session.value?.token === token) {
      await fetchList()
      drawerRef.value?.reloadIfShowing(row.orderId)
      ElMessage.error(`${error.message || '操作结果未确认'}；已刷新订单，请核对状态后再操作。`)
    }
  } finally {
    busyMap[row.orderId] = false
  }
}

function accept(row) {
  runAction(row, acceptOrder, '接单成功')
}

function ready(row) {
  runAction(row, completePreparation, '出餐完成')
}

function reject(row) {
  runAction(row, rejectOrder, '订单已拒绝，系统已按规则模拟退款', { confirmReject: true })
}
</script>

<style scoped>
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 30px;
}
.eyebrow {
  margin: 0 0 10px;
  color: var(--color-muted);
  font-size: 12px;
  letter-spacing: 2px;
}
h1 {
  margin: 0;
  color: var(--color-ink);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 1px;
}
.heading-dot {
  color: #ba8b46;
  margin-left: 4px;
}
.page-description {
  margin: 10px 0 0;
  color: var(--color-muted);
  font-size: 13px;
}
.heading-note {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-right: 8px;
  color: #82917d;
  font-size: 12px;
  line-height: 1.9;
  letter-spacing: 2px;
}
.heading-note strong {
  color: #526b57;
  font-weight: 500;
}
.heading-note svg {
  width: 68px;
  height: 68px;
  stroke: #8c9c80;
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.board-navigation {
  margin-bottom: 26px;
  border-bottom: 1px solid var(--color-border);
}
.order-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.order-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.order-tabs :deep(.el-tabs__item) {
  height: 56px;
  padding: 0 30px;
  color: var(--color-muted);
  font-size: 15px;
}
.order-tabs :deep(.el-tabs__item.is-active) {
  color: var(--color-primary);
  font-weight: 700;
}
.order-tabs :deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 3px 3px 0 0;
}
.tab-label {
  display: flex;
  align-items: center;
  gap: 9px;
}
.tab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.pending-dot { background: #d6a14e; }
.processing-dot { background: #398b6f; }
.history-dot { background: #9ba59c; }
.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.list-context {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.list-context h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.order-count {
  padding: 3px 9px;
  color: var(--color-primary);
  background: #e7eee3;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.list-hint {
  margin-left: 4px;
  color: var(--color-muted);
  font-size: 12px;
}
.refresh-button {
  min-height: 36px;
  background: #fff;
  flex-shrink: 0;
}
.refresh-button svg {
  width: 16px;
  height: 16px;
  margin-right: 6px;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.list-error {
  margin-bottom: 20px;
}
.card-board {
  min-height: 220px;
  border-radius: 14px;
}
.order-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
}
.order-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 3px 12px #20372f04;
  transition: box-shadow .18s ease, border-color .18s ease;
}
.order-card:hover {
  border-color: #c5d4c7;
  box-shadow: 0 7px 20px #20372f09;
}
.card-status-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 54px;
  padding: 13px 20px;
  background: #f0f6f1;
  border-bottom: 1px solid #e5eee5;
}
.order-card-pending .card-status-line {
  background: #fff9ec;
  border-bottom-color: #f4ead6;
}
.card-status-line .el-tag {
  height: auto;
  min-height: 24px;
  padding: 4px 8px;
  white-space: normal;
  line-height: 1.4;
}
.wait-time {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #62796b;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.order-card-pending .wait-time {
  color: #966725;
}
.wait-time svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
  flex-shrink: 0;
}
.card-content {
  flex: 1;
  padding: 20px 20px 17px;
}
.order-number {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  font-size: 12px;
}
.order-number > span, .section-caption, .order-time > span {
  color: var(--color-muted);
}
.order-number strong {
  color: #617368;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.product-block {
  min-height: 110px;
  padding: 23px 0 20px;
}
.section-caption {
  font-size: 11px;
  letter-spacing: 1px;
}
.product-summary {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.product-quantity {
  display: block;
  color: #8a6b3b;
  font-size: 14px;
  font-weight: 600;
  white-space: pre-wrap;
}
.customer-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  color: #53645b;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.customer-icon {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #f2f4ee;
  color: #85907e;
  font-size: 10px;
  flex-shrink: 0;
}
.customer-label {
  margin-left: auto;
  color: #849086;
  font-size: 11px;
  flex-shrink: 0;
}
.order-time {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 10px;
  font-size: 12px;
  line-height: 1.7;
}
.order-time time {
  color: #64746a;
  font-variant-numeric: tabular-nums;
}
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 17px 20px;
  border-top: 1px dashed var(--color-border);
}
.order-amount > span {
  display: block;
  margin-bottom: 5px;
  color: var(--color-muted);
  font-size: 10px;
}
.order-amount strong {
  font-size: 23px;
  line-height: 1.1;
  letter-spacing: -.5px;
  font-variant-numeric: tabular-nums;
}
.order-amount small {
  margin-right: 3px;
  font-size: 14px;
}
.card-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.card-actions .el-button + .el-button {
  margin-left: 0;
}
.card-actions .el-button {
  min-height: 38px;
}
.detail-button {
  padding-right: 12px;
  padding-left: 12px;
}
.primary-action {
  min-width: 78px;
}
.empty-board {
  display: flex;
  min-height: 340px;
  padding: 48px 20px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1px dashed #d6dfd1;
  border-radius: 14px;
  background: #ffffff70;
  text-align: center;
}
.empty-symbol {
  width: 66px;
  height: 66px;
  display: grid;
  place-items: center;
  border-radius: 20px;
  background: #edf1e7;
}
.empty-symbol svg {
  width: 42px;
  height: 42px;
  stroke: #81927a;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.empty-board h3 {
  margin: 20px 0 0;
  font-size: 16px;
  font-weight: 500;
}
.empty-board p {
  margin: 10px 0 0;
  color: var(--color-muted);
  font-size: 12px;
  line-height: 1.8;
}
.history-surface {
  min-width: 0;
  overflow: hidden;
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: #fff;
}
.history-table {
  width: 100%;
  --el-table-header-bg-color: #f7f8f4;
  --el-table-header-text-color: #718078;
  --el-table-row-hover-bg-color: #f5f8f2;
}
.history-table :deep(th.el-table__cell) {
  height: 48px;
  font-size: 12px;
  font-weight: 500;
}
.history-table :deep(td.el-table__cell) {
  height: 64px;
}
.history-table :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  padding: 4px 7px;
  white-space: normal;
  line-height: 1.5;
}
.table-scroll-hint {
  display: none;
}
.history-table :deep(.time-column .cell) {
  overflow: visible;
  text-overflow: clip;
}
.history-time {
  display: inline-block;
  white-space: nowrap;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.pagination-wrap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
}
.pagination-note {
  color: #87917f;
  font-size: 11px;
  letter-spacing: 1px;
}
.pagination-wrap .el-pagination {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px 0;
}
@media (max-width: 1170px) {
  .order-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .pagination-note {
    display: none;
  }
  .pagination-wrap {
    justify-content: flex-end;
  }
}
@media (max-width: 760px) {
  .page-heading {
    margin-bottom: 18px;
  }
  h1 {
    font-size: 27px;
  }
  .heading-note {
    display: none;
  }
  .board-navigation {
    margin-bottom: 22px;
  }
  .order-tabs :deep(.el-tabs__item) {
    padding: 0 24px;
    font-size: 14px;
  }
  .list-context {
    gap: 8px;
  }
  .list-hint {
    width: 100%;
    margin-left: 0;
    line-height: 1.7;
  }
  .order-grid {
    gap: 14px;
  }
  .card-content {
    padding: 18px 16px 16px;
  }
  .card-status-line, .card-footer {
    padding-right: 16px;
    padding-left: 16px;
  }
  .history-surface {
    padding: 4px;
  }
  .table-scroll-hint {
    display: block;
    margin: 10px 10px 14px;
    color: var(--color-muted);
    font-size: 11px;
  }
  .pagination-wrap :deep(.el-pagination__total) {
    margin-right: 8px;
  }
  .pagination-wrap :deep(.el-pagination__sizes) {
    margin-right: 0;
  }
  .pagination-wrap :deep(.el-pagination__sizes .el-select) {
    width: 106px;
  }
}
@media (max-width: 600px) {
  .order-tabs :deep(.el-tabs__item) {
    padding: 0 16px;
    font-size: 13px;
  }
  .tab-label {
    gap: 7px;
  }
  .order-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .product-block {
    min-height: 100px;
    padding-top: 20px;
  }
  .pagination-wrap {
    margin-top: 22px;
  }
  .pagination-wrap .el-pagination {
    max-width: 100%;
    justify-content: center;
  }
  .order-time time {
    white-space: normal;
    overflow-wrap: anywhere;
  }
}
</style>
