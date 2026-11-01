<template>
  <main class="rider-page">
    <header class="rider-header">
      <div class="header-actions">
        <RouterLink class="identity rider-account-link" to="/rider/account">{{ rider?.name || `骑手 #${rider?.id ?? ''}` }} · 我的账号</RouterLink>
        <el-button text :loading="logoutBusy" :disabled="logoutBusy" @click="logout">退出</el-button>
      </div>
    </header>

    <section class="intro">
      <p class="eyebrow">RIDER WORKSPACE</p>
      <h1>一程一程，把餐送好。</h1>
      <p>查看附近任务，管理正在配送的订单。</p>
    </section>

    <section class="location-card surface" aria-labelledby="location-title">
      <div class="location-copy">
        <div class="section-title-row">
          <div>
            <p class="eyebrow">定位状态</p>
            <h2 id="location-title">{{ locationHeading }}</h2>
          </div>
          <span class="live-dot" :class="{ live: tracking }" aria-label="定位状态指示" />
        </div>
        <p class="muted location-description">{{ locationDescription }}</p>
        <p v-if="positionError" class="inline-error" role="alert">{{ positionError }}</p>
        <p v-if="uploadError" class="inline-error" role="alert">{{ uploadError }}</p>
        <p v-if="cleanupError" class="inline-error" role="alert">{{ cleanupError }}</p>
        <p v-if="uploadTime" class="last-upload">最近成功上报 {{ uploadTime }}</p>
      </div>
      <div class="location-action">
        <el-button v-if="!locationActive && !cleanupPending" type="primary" size="large" @click="startLocation">
          开始定位
        </el-button>
        <el-button v-else type="primary" plain size="large" :loading="stopping" @click="stopLocation">
          {{ cleanupPending ? '重试删除位置' : '停止定位' }}
        </el-button>
      </div>
      <div v-if="location?.position" class="coordinates">
        <span>经度 {{ location.position.longitude.toFixed(5) }}</span>
        <span>纬度 {{ location.position.latitude.toFixed(5) }}</span>
        <span v-if="Number.isFinite(location.position.accuracy)">精度约 {{ Math.round(location.position.accuracy) }} 米</span>
      </div>
      <p class="location-footnote">定位用于查询附近任务。停止定位不影响查看或完成当前配送任务。</p>
    </section>

    <nav class="work-tabs" aria-label="配送任务视图">
      <button :class="{ active: activeTab === 'available' }" @click="selectTab('available')">
        可接任务 <span>{{ tasks.length }}</span>
      </button>
      <button :class="{ active: activeTab === 'current' }" @click="selectTab('current')">
        正在配送 <span>{{ currentTasks.length }}</span>
      </button>
    </nav>

    <section v-if="activeTab === 'available'" class="task-section">
      <div class="section-heading">
        <div>
          <h2>附近可接任务</h2>
          <p class="muted">按服务端返回的顺序排列；列表也可能包含半径外的任务。</p>
        </div>
        <div class="refresh-controls">
          <label for="radius">查询半径</label>
          <el-input-number id="radius" v-model="radius" :min="0.1" :max="50" :step="0.5" :precision="1" :disabled="!tracking || refreshingTasks" />
          <span class="unit">公里</span>
          <el-button :loading="refreshingTasks" :disabled="!tracking" @click="refreshAvailable">刷新</el-button>
        </div>
      </div>

      <el-alert v-if="hallError" :title="hallError" type="error" show-icon :closable="false" class="error-alert" />
      <div v-if="!locationActive && !tasks.length" class="empty-card surface">
        <div class="empty-icon" aria-hidden="true">⌖</div>
        <h3>开始定位后查看附近任务</h3>
        <p>浏览器会请求位置权限，成功上报位置后自动加载可接任务。</p>
        <el-button type="primary" @click="startLocation">开始定位</el-button>
      </div>
      <div v-else-if="!tracking && !tasks.length" class="empty-card surface">
        <el-skeleton :rows="2" animated />
        <p class="loading-note">正在获取真实位置并加载附近任务。</p>
      </div>
      <div v-else-if="!tasks.length && !refreshingTasks" class="empty-card surface">
        <div class="empty-icon" aria-hidden="true">✓</div>
        <h3>{{ hallError ? '任务暂时无法加载' : '附近暂时没有可接任务' }}</h3>
        <p>{{ hallError ? '检查网络后重新刷新。' : '过一会儿再来看看。' }}</p>
        <el-button v-if="hallError" @click="refreshAvailable">重试</el-button>
      </div>
      <div v-else v-loading="refreshingTasks" class="task-grid">
        <article v-for="task in tasks" :key="task.taskId" class="task-card surface">
          <div class="task-topline">
            <span class="task-state"><i />{{ task.statusDesc || '待接任务' }}</span>
            <strong class="reward">{{ money(task.deliveryReward) }}</strong>
          </div>
          <h3>{{ task.merchantName || '商家' }}</h3>
          <div class="route-line">
            <span class="route-marker pickup">取</span>
            <div><small>取货地址</small><p>{{ task.merchantAddress || '商家未提供地址' }}</p></div>
          </div>
          <div class="route-line">
            <span class="route-marker dropoff">送</span>
            <div><small>送达地址</small><p>{{ task.receiverAddress || '顾客未提供地址' }}</p></div>
          </div>
          <div class="task-footer">
            <span>{{ formatPickupDistance(task.pickupDistanceKm) }}</span>
            <span>任务 #{{ task.taskId }}</span>
          </div>
          <div class="card-actions">
            <el-button @click="openDetail(task.taskId)">任务详情</el-button>
            <el-button type="primary" :loading="busyTask === task.taskId" :disabled="Boolean(busyTask)" @click="claim(task)">抢单</el-button>
          </div>
        </article>
      </div>
    </section>

    <section v-else class="task-section">
      <div class="section-heading">
        <div>
          <h2>正在配送</h2>
          <p class="muted">当前任务可以独立查看，不需要开启定位。</p>
        </div>
        <el-button :loading="loadingCurrent" @click="refreshCurrent">刷新</el-button>
      </div>
      <el-alert v-if="currentError" :title="currentError" type="error" show-icon :closable="false" class="error-alert" />
      <div v-if="!currentTasks.length && !loadingCurrent" class="empty-card surface">
        <div class="empty-icon" aria-hidden="true">—</div>
        <h3>{{ currentError ? '配送任务暂时无法加载' : '目前没有进行中的配送' }}</h3>
        <p>{{ currentError ? '检查网络后重新加载。' : '接取任务后，正在配送的订单会显示在这里。' }}</p>
        <el-button @click="refreshCurrent">重新加载</el-button>
      </div>
      <div v-else v-loading="loadingCurrent" class="task-grid">
        <article v-for="task in currentTasks" :key="task.taskId" class="task-card current-card surface">
          <div class="task-topline">
            <span class="task-state in-progress"><i />{{ task.statusDesc || '正在配送' }}</span>
            <strong class="reward">{{ money(task.deliveryReward) }}</strong>
          </div>
          <h3>{{ task.merchantName || '商家' }}</h3>
          <div class="route-line">
            <span class="route-marker pickup">取</span>
            <div><small>取货地址</small><p>{{ task.merchantAddress || '商家未提供地址' }}</p></div>
          </div>
          <div class="route-line">
            <span class="route-marker dropoff">送</span>
            <div><small>送达地址</small><p>{{ task.receiverAddress || '顾客未提供地址' }}</p></div>
          </div>
          <div class="card-actions">
            <el-button @click="openDetail(task.taskId)">查看联系方式</el-button>
            <el-button type="primary" :loading="busyTask === task.taskId" :disabled="Boolean(busyTask)" @click="finish(task)">确认送达</el-button>
          </div>
        </article>
      </div>
    </section>

    <el-drawer v-model="detailVisible" title="任务详情" size="min(480px, 94vw)" @closed="detailGeneration += 1">
      <div v-if="detailLoading" v-loading="detailLoading" class="drawer-loading" aria-label="正在加载任务详情" />
      <el-alert v-else-if="detailError" :title="detailError" type="error" show-icon :closable="false">
        <template #default><el-button text @click="openDetail(selectedTask?.taskId)">重新加载</el-button></template>
      </el-alert>
      <div v-else-if="selectedTask" class="detail-content">
        <div class="detail-hero">
          <span class="detail-kicker">DELIVERY TASK #{{ selectedTask.taskId ?? selectedTask.orderId ?? '—' }}</span>
          <h2>{{ selectedTask.merchantName || '配送任务' }}</h2>
          <el-tag type="success" effect="plain">{{ selectedTask.statusDesc || '任务详情' }}</el-tag>
          <strong class="detail-reward">{{ money(selectedTask.deliveryReward) }}</strong>
        </div>
        <div class="detail-route">
          <div class="detail-step">
            <span class="route-marker pickup">取</span>
            <div><small>前往商家取货</small><p>{{ selectedTask.merchantAddress || '商家未提供地址' }}</p></div>
          </div>
          <div class="detail-step">
            <span class="route-marker dropoff">送</span>
            <div><small>送到顾客手中</small><p>{{ selectedTask.receiverAddress || '顾客未提供地址' }}</p></div>
          </div>
        </div>
        <dl class="contact-list">
          <div><dt>商家电话</dt><dd>{{ selectedTask.merchantPhone || '暂无' }}</dd></div>
          <div><dt>收货人</dt><dd>{{ selectedTask.receiverName || '暂无' }}</dd></div>
          <div><dt>联系电话</dt><dd>{{ selectedTask.receiverPhone || '暂无' }}</dd></div>
        </dl>
          <p class="location-footnote">送达后，顾客还需确认收货。</p>
        <div class="drawer-actions">
          <el-button v-if="isCurrent(selectedTask)" type="primary" :loading="busyTask === selectedTask.taskId" :disabled="Boolean(busyTask) && busyTask !== selectedTask.taskId" @click="finish(selectedTask)">确认送达</el-button>
          <el-button v-else type="primary" :loading="busyTask === selectedTask.taskId" :disabled="Boolean(busyTask) && busyTask !== selectedTask.taskId" @click="claim(selectedTask)">抢单</el-button>
        </div>
      </div>
    </el-drawer>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createLocationTracker } from '@rider-core/location-tracker.mjs'
import { formatPickupDistance } from '@rider-core/distance-display.mjs'
import { getSession, clearRoleSession, registerSessionCleanup } from '../auth.js'
import {
  claimRiderTask, completeRiderTask, createRiderLocationClient, getCurrentTasks,
  getRiderTaskDetail,
} from '../api/riderApi.js'

const rider = computed(() => getSession('rider'))
const sessionToken = rider.value?.token || ''
const router = useRouter()
const radius = ref(5)
const activeTab = ref('available')
const trackerState = ref(null)
const tasks = computed(() => trackerState.value?.tasks ?? [])
const tracking = computed(() => trackerState.value?.phase === 'tracking')
const locationActive = computed(() => Boolean(trackerState.value?.active))
const cleanupPending = computed(() => trackerState.value?.phase === 'cleanup-error')
const stopping = computed(() => trackerState.value?.phase === 'stopping')
const location = computed(() => trackerState.value)
const positionError = computed(() => trackerState.value?.locationError?.message ?? '')
const uploadError = computed(() => trackerState.value?.uploadError?.message ?? '')
const cleanupError = computed(() => trackerState.value?.cleanupError?.message ?? '')
const uploadTime = computed(() => trackerState.value?.lastUploadedAt
  ? new Date(trackerState.value.lastUploadedAt).toLocaleTimeString()
  : '')
const locationHeading = computed(() => ({
  tracking: '位置已上报', locating: '正在获取位置', stopping: '正在停止定位', 'cleanup-error': '等待清理服务器位置',
}[trackerState.value?.phase] || '尚未开始定位'))
const locationDescription = computed(() => ({
  tracking: '浏览器正在提供真实位置，附近任务会按服务端返回顺序更新。',
  locating: '等待浏览器提供有效位置并上报。',
  stopping: '已停止本地定位，正在等待未完成请求并清理服务器位置。',
  'cleanup-error': '本地定位已经停止，服务器位置尚未确认删除。',
}[trackerState.value?.phase] || '定位需要你主动开始并授权浏览器。'))

const currentTasks = ref([])
const loadingCurrent = ref(false)
const currentError = ref('')
const refreshingTasks = computed(() => Boolean(trackerState.value?.refreshing))
const hallError = computed(() => trackerState.value?.taskError?.message || '')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailGeneration = ref(0)
const selectedTask = ref(null)
const busyTask = ref(null)
const logoutBusy = ref(false)
let currentRequest = 0
let removeLocationListener = null
let removePageHideListener = null
let unregisterCleanup = null
let disposed = false

const tracker = createLocationTracker({
  api: createRiderLocationClient(sessionToken),
  getRadius: () => Number(radius.value),
  onChange: (next) => {
    trackerState.value = next
  },
})

watch(radius, (next, previous) => {
  if (next !== previous && tracking.value && Number.isFinite(next) && next > 0) {
    void tracker.refreshTasks()
  }
})

watch(() => getSession('rider')?.token, async (token) => {
  if (token === sessionToken) return
  ++currentRequest
  ++detailGeneration.value
  detailVisible.value = false
  currentTasks.value = []
  selectedTask.value = null
  const state = tracker.getState()
  if (state.active || state.phase === 'cleanup-error' || state.phase === 'stopping') {
    await tracker.stop()
  }
  tracker.reset()
  trackerState.value = tracker.getState()
  await router.replace(token
    ? { name: 'home' }
    : { name: 'rider-login', query: { redirect: '/rider/tasks' } })
})

onMounted(() => {
  trackerState.value = tracker.getState()
  removeLocationListener = () => window.removeEventListener('pagehide', pauseLocation)
  removePageHideListener = () => document.removeEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', pauseLocation)
  document.addEventListener('visibilitychange', onVisibilityChange)
  unregisterCleanup = registerSessionCleanup('rider', cleanupForExit)
  void refreshCurrent()
})

onBeforeRouteLeave(async () => {
  if (disposed || !rider.value) return true
  const beforeLeave = tracker.getState()
  if (!beforeLeave.active && beforeLeave.phase !== 'cleanup-error' && beforeLeave.phase !== 'stopping') return true
  const removed = await tracker.stop()
  trackerState.value = tracker.getState()
  if (removed) {
    tracker.reset()
    return true
  }
  if (tracker.getState().cleanupError?.kind === 'auth') {
    clearRoleSession('rider')
    ElMessage.warning('登录凭证已失效；服务器位置删除未确认，系统会按过期策略清理。')
    tracker.reset()
    return true
  }
  try {
    await ElMessageBox.confirm(
      '定位已停止，但服务器位置未能确认删除。离开后无法在此页重试；服务端会按位置过期策略清理。',
      '位置清理未完成',
      { confirmButtonText: '仍要离开', cancelButtonText: '留在页面重试', type: 'warning' },
    )
    tracker.reset()
    return true
  } catch {
    return false
  }
})

onBeforeUnmount(() => {
  disposed = true
  removeLocationListener?.()
  removePageHideListener?.()
  unregisterCleanup?.()
  const state = tracker.getState()
  if (state.active || state.phase === 'cleanup-error' || state.phase === 'stopping') {
    void tracker.stop({ keepalive: true })
  }
})

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') pauseLocation()
}

function pauseLocation() {
  const state = tracker.getState()
  if (state.active || state.phase === 'cleanup-error' || state.phase === 'stopping') {
    void tracker.stop({ keepalive: true })
  }
}

function startLocation() {
  if (!sessionIsCurrent() || document.visibilityState === 'hidden') return
  tracker.start()
  trackerState.value = tracker.getState()
}

async function stopLocation() {
  if (!sessionIsCurrent()) return
  const removed = await tracker.stop()
  if (!sessionIsCurrent()) return
  trackerState.value = tracker.getState()
  if (removed) ElMessage.success('定位已停止，服务器位置已删除。')
  else ElMessage.error(trackerState.value?.cleanupError?.message || '服务器位置删除失败，请重试。')
}

async function refreshAvailable() {
  if (!tracking.value) {
    ElMessage.info('请先开始定位并成功上报位置。')
    return
  }
  await tracker.refreshTasks()
  if (!sessionIsCurrent()) return
  trackerState.value = tracker.getState()
}

function selectTab(tab) {
  activeTab.value = tab
  if (tab === 'current') void refreshCurrent()
}

async function refreshCurrent() {
  const requestId = ++currentRequest
  if (!sessionIsCurrent()) return null
  loadingCurrent.value = true
  currentError.value = ''
  try {
    const data = await getCurrentTasks()
    if (!sessionIsCurrent() || requestId !== currentRequest) return null
    currentTasks.value = Array.isArray(data) ? data : []
    return currentTasks.value
  } catch (error) {
    if (!sessionIsCurrent() || requestId !== currentRequest) return null
    currentTasks.value = []
    currentError.value = error?.message || '进行中任务加载失败。'
    return null
  } finally {
    if (requestId === currentRequest) loadingCurrent.value = false
  }
}

async function openDetail(taskId) {
  if (!taskId || !sessionIsCurrent()) return
  const requestId = ++detailGeneration.value
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  selectedTask.value = null
  try {
    const data = await getRiderTaskDetail(taskId)
    if (!sessionIsCurrent() || requestId !== detailGeneration.value) return
    selectedTask.value = { ...data, taskId }
  } catch (error) {
    if (!sessionIsCurrent() || requestId !== detailGeneration.value) return
    detailError.value = error?.message || '任务详情暂时无法加载。'
  } finally {
    if (requestId === detailGeneration.value) detailLoading.value = false
  }
}

function isCurrent(task) {
  const id = task?.taskId ?? task?.orderId
  return currentTasks.value.some((current) => String(current.taskId) === String(id))
}

async function claim(task) {
  const taskId = task?.taskId
  if (!taskId || busyTask.value || !sessionIsCurrent()) return
  busyTask.value = taskId
  try {
    await claimRiderTask(taskId)
    if (!sessionIsCurrent()) return
    ElMessage.success('抢单成功，任务已加入正在配送。')
    detailVisible.value = false
    await refreshCurrent()
    if (tracking.value) await tracker.refreshTasks()
  } catch (error) {
    // A competing rider may have claimed it. Refresh both server views.
    if (!sessionIsCurrent()) return
    const [currentResult] = await Promise.allSettled([
      refreshCurrent(),
      tracking.value ? tracker.refreshTasks() : Promise.resolve(),
    ])
    if (!sessionIsCurrent()) return
    const confirmed = currentResult.status === 'fulfilled'
      && currentResult.value?.some((item) => String(item.taskId) === String(taskId))
    if (confirmed) {
      detailVisible.value = false
      ElMessage.success('抢单结果已通过正在配送列表确认。')
    } else if (error?.kind === 'network' || error?.kind === 'stale') {
      ElMessage.warning('抢单结果尚未确认，请查看刷新后的任务列表。')
    } else {
      ElMessage.error(error?.message || '任务状态已变化，请刷新任务列表。')
    }
  } finally {
    busyTask.value = null
  }
}

async function finish(task) {
  const taskId = task?.taskId ?? task?.orderId
  if (!taskId || busyTask.value || !sessionIsCurrent()) return
  busyTask.value = taskId
  try {
    await ElMessageBox.confirm(
      '确认餐品已经送达收货地址？完成后任务将从进行中列表移除。',
      '确认送达',
      { confirmButtonText: '确认送达', cancelButtonText: '继续配送', type: 'success' },
    )
  } catch {
    busyTask.value = null
    return
  }
  if (!sessionIsCurrent()) {
    busyTask.value = null
    return
  }
  try {
    await completeRiderTask(taskId)
    if (!sessionIsCurrent()) return
    ElMessage.success('已确认送达。订单将等待顾客确认收货。')
    detailVisible.value = false
    await refreshCurrent()
  } catch (error) {
    if (!sessionIsCurrent()) return
    const authoritativeTasks = await refreshCurrent()
    if (!sessionIsCurrent()) return
    const stillCurrent = Array.isArray(authoritativeTasks)
      && authoritativeTasks.some((item) => String(item.taskId) === String(taskId))
    if (Array.isArray(authoritativeTasks) && !stillCurrent) {
      ElMessage.success('送达结果已通过最新配送列表确认。')
      detailVisible.value = false
    } else if (error?.kind === 'network' || error?.kind === 'stale' || authoritativeTasks === null) {
      ElMessage.warning('送达结果暂未确认，请刷新配送列表后核对任务状态。')
    } else {
      ElMessage.error(error?.message || '任务状态已变化，请检查刷新后的配送列表。')
    }
  } finally {
    busyTask.value = null
  }
}

async function cleanupForExit() {
  if (!sessionIsCurrent()) return true
  const beforeExit = tracker.getState()
  if (!beforeExit.active && beforeExit.phase !== 'cleanup-error' && beforeExit.phase !== 'stopping') {
    return true
  }
  const deleted = await tracker.stop()
  trackerState.value = tracker.getState()
  if (deleted) {
    tracker.reset()
    return true
  }
  if (tracker.getState().cleanupError?.kind === 'auth') {
    tracker.reset()
    return true
  }
  try {
    await ElMessageBox.confirm(
      '服务器位置未确认删除，仍要退出骑手账号吗？位置会在服务端过期后清理。',
      '无法确认清理位置',
      { confirmButtonText: '仍要退出', cancelButtonText: '留在工作台', type: 'warning' },
    )
    tracker.reset()
    return true
  } catch {
    return false
  }
}

async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  try {
    if (await cleanupForExit()) clearRoleSession('rider')
  } finally {
    logoutBusy.value = false
  }
}

function sessionIsCurrent() {
  return !disposed && Boolean(sessionToken) && getSession('rider')?.token === sessionToken
}

function money(value) {
  const amount = Number(value)
  return Number.isFinite(amount) ? `¥${amount.toFixed(2)}` : '奖励待确认'
}
</script>

<style scoped>
.rider-page { max-width: 1120px; min-height: 100vh; margin: 0 auto; padding: 24px 28px 56px; color: #20372f; }
.rider-header { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding-bottom: 20px; border-bottom: 1px solid #e2e8df; }
.rider-header { min-height: 48px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.identity { color: #65756b; font-size: 13px; }
.intro { padding: 35px 0 24px; }
.eyebrow { margin: 0 0 8px; color: #728074; font-size: 10px; font-weight: 700; letter-spacing: .17em; }
.intro h1 { margin: 0; font-size: clamp(26px, 4vw, 36px); letter-spacing: -.04em; }
.intro > p:last-child { margin: 10px 0 0; color: #718078; font-size: 14px; }
.surface { border: 1px solid #e2e8df; border-radius: 18px; background: #fff; box-shadow: 0 7px 24px #20372f08; }
.location-card { position: relative; display: grid; grid-template-columns: 1fr auto; gap: 12px 24px; padding: 22px 24px 17px; }
.section-title-row { display: flex; align-items: center; gap: 14px; }
.section-title-row h2, .section-heading h2 { margin: 0; font-size: 18px; }
.section-title-row .eyebrow { margin-bottom: 5px; }
.live-dot { width: 11px; height: 11px; flex: 0 0 11px; border-radius: 50%; background: #c4c9c3; }
.live-dot.live { background: #408863; box-shadow: 0 0 0 4px #e6f2e7; }
.location-copy { min-width: 0; }
.location-description { margin: 10px 0 0; }
.muted { color: #748078; font-size: 13px; line-height: 1.65; }
.location-action { display: flex; align-items: center; }
.coordinates { display: flex; flex-wrap: wrap; gap: 8px 18px; color: #607268; font-size: 12px; font-variant-numeric: tabular-nums; }
.location-footnote { grid-column: 1 / -1; margin: 4px 0 0; padding-top: 12px; border-top: 1px solid #edf0e9; color: #8a958c; font-size: 11px; line-height: 1.65; }
.last-upload { margin: 7px 0 0; color: #698273; font-size: 11px; }
.inline-error { margin: 9px 0 0; padding: 9px 11px; border-radius: 9px; background: #fff2ed; color: #98473e; font-size: 12px; line-height: 1.6; }
.work-tabs { display: flex; gap: 8px; margin: 28px 0 22px; padding: 5px; border: 1px solid #e1e8df; border-radius: 13px; background: #eff2ec; }
.work-tabs button { flex: 1; min-width: 0; padding: 11px 14px; border: 0; border-radius: 9px; background: transparent; color: #68756d; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; }
.work-tabs button.active { background: #fff; color: #19664f; box-shadow: 0 2px 8px #20372f0d; }
.work-tabs button span { display: inline-grid; min-width: 20px; height: 20px; margin-left: 5px; place-items: center; border-radius: 999px; background: #edf2ec; font-size: 11px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 17px; }
.section-heading p { margin: 6px 0 0; }
.refresh-controls { display: flex; align-items: center; gap: 8px; }
.refresh-controls label, .unit { color: #718078; font-size: 12px; }
.refresh-controls :deep(.el-input-number) { width: 112px; }
.task-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.task-card { min-width: 0; padding: 19px; }
.task-topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-state { display: inline-flex; align-items: center; gap: 7px; color: #99651d; font-size: 11px; }
.task-state i { width: 7px; height: 7px; border-radius: 50%; background: #d7a34b; }
.task-state.in-progress { color: #286d50; }
.task-state.in-progress i { background: #47926a; }
.reward { color: #19664f; font-size: 17px; font-variant-numeric: tabular-nums; }
.task-card h3 { margin: 15px 0; font-size: 16px; }
.route-line, .detail-step { display: flex; align-items: flex-start; gap: 11px; min-width: 0; }
.route-line + .route-line { margin-top: 13px; }
.route-marker { display: grid; width: 24px; height: 24px; flex: 0 0 24px; place-items: center; border: 1px solid #d9e6db; border-radius: 8px; background: #f1f6f0; color: #326e51; font-size: 11px; font-weight: 700; }
.route-marker.dropoff { border-color: #f1ded2; background: #fff5ef; color: #af6847; }
.route-line > div, .detail-step > div { min-width: 0; }
.route-line small, .detail-step small { display: block; color: #8a958c; font-size: 10px; }
.route-line p, .detail-step p { margin: 4px 0 0; color: #475a4e; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.task-footer { display: flex; justify-content: space-between; gap: 10px; margin: 16px 0 13px; padding-top: 12px; border-top: 1px solid #edf0e9; color: #87938a; font-size: 10px; }
.card-actions { display: flex; gap: 9px; margin-top: 16px; }
.card-actions :deep(.el-button) { flex: 1; min-width: 0; margin: 0; }
.empty-card { display: grid; min-height: 245px; justify-items: center; align-content: center; padding: 30px 22px; text-align: center; }
.empty-icon { display: grid; width: 42px; height: 42px; place-items: center; margin-bottom: 12px; border-radius: 14px; background: #edf4ee; color: #347357; font-size: 19px; }
.empty-card h3 { margin: 0; font-size: 15px; }
.empty-card p { max-width: 410px; margin: 8px 0 17px; color: #7b887e; font-size: 12px; line-height: 1.7; }
.loading-note { margin-bottom: 0 !important; }
.error-alert { margin: 0 0 14px; }
.detail-hero { display: flex; flex-direction: column; align-items: flex-start; padding: 16px; border: 1px solid #e5ebe2; border-radius: 14px; background: #f8f9f5; }
.detail-kicker { color: #829087; font-size: 9px; letter-spacing: .13em; }
.detail-hero h2 { margin: 10px 0; font-size: 21px; }
.detail-reward { margin-top: 15px; color: #19664f; font-size: 25px; }
.detail-route { display: grid; gap: 19px; margin: 21px 0; }
.contact-list { margin: 0; border-top: 1px solid #e7ece4; }
.contact-list div { display: flex; justify-content: space-between; gap: 14px; padding: 13px 0; border-bottom: 1px solid #edf0e9; font-size: 12px; }
.contact-list dt { color: #7f8b82; }
.contact-list dd { margin: 0; color: #34493c; text-align: right; overflow-wrap: anywhere; }
.detail-content > .location-footnote { display: block; margin-top: 18px; }
.drawer-actions { display: flex; margin-top: 22px; }
.drawer-actions :deep(.el-button) { flex: 1; }
.drawer-loading { min-height: 190px; }
@media (max-width: 720px) {
  .rider-page { padding: 17px 15px 38px; }
  .rider-header { padding-bottom: 15px; }
  .identity { max-width: 125px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
  .intro { padding: 27px 0 20px; }
  .location-card { grid-template-columns: minmax(0, 1fr) auto; gap: 9px 12px; padding: 17px; }
  .location-action :deep(.el-button) { padding: 9px 12px; }
  .task-grid { grid-template-columns: 1fr; }
  .section-heading { align-items: flex-start; flex-direction: column; }
  .refresh-controls { width: 100%; flex-wrap: wrap; }
  .refresh-controls :deep(.el-input-number) { flex: 1; }
}
@media (max-width: 390px) {
  .rider-page { padding-right: 11px; padding-left: 11px; }
  .location-card { grid-template-columns: 1fr; }
  .location-action { justify-content: flex-start; }
  .coordinates { flex-direction: column; gap: 6px; }
  .card-actions :deep(.el-button) { padding-right: 8px; padding-left: 8px; font-size: 12px; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; transition-duration: .01ms !important; }
}
</style>
