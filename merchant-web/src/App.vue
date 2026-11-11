<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content" @click.prevent="focusMain">跳到主要内容</a>
    <header class="app-topbar">
      <div class="app-topbar-inner">
        <RouterLink to="/" class="app-brand" aria-label="Tokeout 返回入口"><span class="app-brand-mark" aria-hidden="true">T</span><span>TOKEOUT<small>好好吃饭，用心送达</small></span></RouterLink>
        <nav class="role-navigation" aria-label="角色入口">
          <RouterLink v-for="(label, name) in roleLabels" :key="name" :to="roleHome[name]" :class="{ selected: role === name }">{{ label }}端</RouterLink>
        </nav>
        <div v-if="role && !route.meta.login && role !== 'rider'" class="app-account">
          <template v-if="currentSession"><RouterLink :to="`/${role}/account`" class="account-label account-link">{{ currentSession.name || `${roleLabels[role]} #${currentSession.id}` }}</RouterLink><el-button text :loading="loggingOut" @click="logout">退出</el-button></template>
          <RouterLink v-else :to="{ name: `${role}-login`, query: { redirect: route.fullPath } }" class="text-link">登录</RouterLink>
        </div>
      </div>
    </header>
    <nav v-if="role === 'customer' && !route.meta.login" class="customer-navigation" aria-label="顾客导航">
      <RouterLink to="/customer/shops" :class="{ selected: ['shops', 'shop'].includes(route.name) }">发现店铺</RouterLink>
      <RouterLink to="/customer/cart">购物车</RouterLink>
      <RouterLink to="/customer/orders" :class="{ selected: ['orders', 'order-detail'].includes(route.name) }">我的订单</RouterLink>
      <RouterLink to="/customer/history">浏览历史</RouterLink>
      <RouterLink to="/customer/account">我的账号</RouterLink>
    </nav>
    <nav v-if="role === 'merchant' && !route.meta.login" class="customer-navigation merchant-navigation" aria-label="商家导航">
      <RouterLink to="/merchant/orders">订单工作台</RouterLink><RouterLink to="/merchant/products">商品管理</RouterLink><RouterLink to="/merchant/categories">分类管理</RouterLink><RouterLink to="/merchant/settings">店铺设置</RouterLink>
    </nav>
    <main id="main-content" class="app-content" tabindex="-1">
      <MerchantNotificationBar v-if="role === 'merchant' && currentSession && !route.meta.login" />
      <el-alert v-if="routeError" type="error" :title="routeError" show-icon :closable="false"><el-button text @click="reload">重新加载页面</el-button></el-alert>
      <RouterView v-slot="{ Component, route: viewRoute }"><component :is="Component" :key="viewRoute.name" /></RouterView>
    </main>
    <footer class="app-footer"><span>TOKEOUT</span> 每一餐，都认真对待。</footer>
  </div>
</template>
<script setup>
import {computed, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {getSession, logoutRole} from './auth.js'
import {roleHome, roleLabels} from './router.js'
import MerchantNotificationBar from './merchant/notifications/MerchantNotificationBar.vue'

const route = useRoute(), router = useRouter()
const role = computed(() => route.meta.role)
const currentSession = computed(() => getSession(role.value))
const loggingOut = ref(false), routeError = ref('')
router.onError(() => { routeError.value = '页面加载失败，请检查网络后重新加载。' })
const reload = () => window.location.reload()
function focusMain() {
  const main = document.getElementById('main-content')
  main?.focus()
  main?.scrollIntoView({ block: 'start' })
}
watch(() => [route.fullPath, currentSession.value?.token], () => {
  if (route.meta.auth && !currentSession.value) {
    void router.replace({ name: `${route.meta.role}-login`, query: { redirect: route.fullPath } })
  }
})
async function logout() {
  if (loggingOut.value) return
  const currentRole = role.value
  loggingOut.value = true
  try {
    if (await logoutRole(currentRole)) await router.push({ name: `${currentRole}-login` })
  } catch (error) { ElMessage.error(error.message || '退出未完成，请重试') }
  finally { loggingOut.value = false }
}
</script>
