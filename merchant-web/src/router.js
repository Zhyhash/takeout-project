import {createRouter, createWebHashHistory} from 'vue-router'
import {getSession} from './auth.js'

export const roleHome = { customer: '/customer/shops', merchant: '/merchant/orders', rider: '/rider/tasks' }
export const roleLabels = { customer: '顾客', merchant: '商家', rider: '骑手' }
const routes = [
  { path: '/', name: 'home', component: () => import('./views/HomeView.vue'), meta: { title: '选择入口' } },
  ...Object.keys(roleHome).map((role) => ({
    path: `/${role}/login`, name: `${role}-login`, component: () => import('./views/RoleLoginView.vue'),
    props: { role }, meta: { role, login: true, title: `${roleLabels[role]}登录` },
  })),
  ...Object.keys(roleHome).map((role) => ({
    path: `/${role}/register`, name: `${role}-register`, component: () => import('./views/RoleRegisterView.vue'),
    props: { role }, meta: { role, login: true, title: `${roleLabels[role]}注册` },
  })),
  ...Object.keys(roleHome).map((role) => ({
    path: `/${role}/account`, name: `${role}-account`, component: () => import('./views/AccountView.vue'),
    props: { role }, meta: { role, auth: true, title: '我的账号' },
  })),
  { path: '/customer', redirect: roleHome.customer },
  { path: '/customer/shops', name: 'shops', component: () => import('./customer/ShopsView.vue'), meta: { role: 'customer', title: '发现店铺' } },
  { path: '/customer/shops/:shopId', name: 'shop', component: () => import('./customer/ShopView.vue'), meta: { role: 'customer', title: '店铺菜单' } },
  { path: '/customer/cart', name: 'cart', component: () => import('./customer/CartView.vue'), meta: { role: 'customer', auth: true, title: '购物车' } },
  { path: '/customer/checkout', name: 'checkout', component: () => import('./customer/CheckoutView.vue'), meta: { role: 'customer', auth: true, title: '确认订单' } },
  { path: '/customer/orders', name: 'orders', component: () => import('./customer/CustomerOrdersView.vue'), meta: { role: 'customer', auth: true, title: '我的订单' } },
  { path: '/customer/orders/:orderId', name: 'order-detail', component: () => import('./customer/CustomerOrderView.vue'), meta: { role: 'customer', auth: true, title: '订单详情' } },
  { path: '/customer/history', name: 'browse-history', component: () => import('./customer/BrowseHistoryView.vue'), meta: { role: 'customer', auth: true, title: '浏览历史' } },
  { path: '/merchant', redirect: roleHome.merchant },
  { path: '/merchant/orders', name: 'merchant-orders', component: () => import('./components/OrderListPanel.vue'), meta: { role: 'merchant', auth: true, title: '订单工作台' } },
  { path: '/merchant/products', name: 'merchant-products', component: () => import('./merchant/catalog/ProductManagementView.vue'), meta: { role: 'merchant', auth: true, title: '商品管理' } },
  { path: '/merchant/categories', name: 'merchant-categories', component: () => import('./merchant/catalog/CategoryManagementView.vue'), meta: { role: 'merchant', auth: true, title: '分类管理' } },
  { path: '/merchant/settings', name: 'merchant-settings', component: () => import('./merchant/settings/ShopSettingsView.vue'), meta: { role: 'merchant', auth: true, title: '店铺设置' } },
  { path: '/rider', redirect: roleHome.rider },
  { path: '/rider/tasks', name: 'rider-tasks', component: () => import('./rider/RiderWorkspace.vue'), meta: { role: 'rider', auth: true, title: '配送工作台' } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('./views/NotFoundView.vue'), meta: { title: '页面未找到' } },
]

export const router = createRouter({ history: createWebHashHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach((to) => {
  if (to.meta.auth && !getSession(to.meta.role)) return { name: `${to.meta.role}-login`, query: { redirect: to.fullPath } }
})
router.afterEach((to) => { document.title = `${to.meta.title || '外卖工作台'} · Tokeout` })

export function safeRedirect(value, role) {
  if (typeof value !== 'string' || !value.startsWith(`/${role}/`) || value.startsWith(`/${role}/login`)) return roleHome[role]
  const resolved = router.resolve(value)
  return resolved.meta.role === role && !resolved.meta.login ? value : roleHome[role]
}
