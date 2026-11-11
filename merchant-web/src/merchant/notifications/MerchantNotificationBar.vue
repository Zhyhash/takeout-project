<template>
  <aside class="notification-bar" aria-label="新订单提醒">
    <div class="notification-status"><span class="connection-dot" :class="{ live: state.connection === 'live' }" /><span>{{ connectionText }}</span></div>
    <div class="notification-actions"><RouterLink v-if="state.unseenIds.length" to="/merchant/orders" class="new-order-link">{{ state.unseenIds.length }} 笔新订单待查看 →</RouterLink><span v-if="state.error || state.ackError" class="notification-error" role="status">{{ state.error || state.ackError }}</span><el-button text size="small" :loading="state.syncing" @click="refreshNotifications">同步订单</el-button></div>
  </aside>
</template>
<script setup>
import {computed, onBeforeUnmount, watch} from 'vue'
import {getSession} from '../../auth.js'
import {notificationState as state, refreshNotifications, startMerchantNotifications} from './state.js'

let stop
watch(() => getSession('merchant')?.token, () => { stop?.(); stop = startMerchantNotifications() }, { immediate: true })
onBeforeUnmount(() => stop?.())
const connectionText = computed(() => ({ live: '新单实时提醒已连接', connecting: '正在连接新单提醒', retrying: '提醒连接重试中，定期同步仍可用', waiting: '定期同步订单中', paused: '页面隐藏，提醒已暂停', expired: '登录已失效', idle: '提醒已停止' }[state.connection]))
</script>
<style scoped>
.notification-bar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:26px;padding:12px 16px;background:#edf3e9;border:1px solid #dce7d6;border-radius:12px;color:#5c735f;font-size:12px}.notification-status,.notification-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.connection-dot{width:7px;height:7px;flex:0 0 7px;border-radius:50%;background:#b69d60}.connection-dot.live{background:#40835e}.new-order-link{color:#19664f;font-weight:700;text-decoration:none}.notification-error{color:#9e6437;font-size:11px}@media(max-width:700px){.notification-bar{align-items:flex-start;flex-direction:column;gap:5px;padding:12px}.notification-actions{gap:8px}}
</style>
