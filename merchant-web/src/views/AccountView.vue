<template>
  <section class="account-page">
    <header class="page-heading">
      <div><p class="eyebrow">ACCOUNT / {{ role.toUpperCase() }}</p><h1>账户入口</h1><p class="muted">当前浏览器中的登录身份。</p></div>
    </header>

    <article v-if="account" class="surface account-card">
      <div class="account-mark" aria-hidden="true">{{ roleMark }}</div>
      <div class="account-copy">
        <p class="eyebrow">{{ account.roleLabel }}账号</p>
        <h2>{{ account.name || `${account.roleLabel} #${account.id}` }}</h2>
        <dl class="account-facts">
          <div><dt>账号编号</dt><dd>{{ account.id }}</dd></div>
          <div><dt>当前身份</dt><dd>{{ account.roleLabel }}</dd></div>
        </dl>
      </div>
      <div class="account-actions">
        <el-button type="primary" plain :disabled="loggingOut" @click="goHome">返回{{ account.roleLabel }}首页</el-button>
        <el-button type="danger" :loading="loggingOut" :disabled="loggingOut" @click="logout">退出当前账号</el-button>
      </div>
    </article>

    <div v-else class="surface page-state">
      <div class="state-symbol" aria-hidden="true">?</div>
      <h2>当前没有可用的本地登录身份</h2>
      <p>账户页只读取本地登录信息。请重新登录后查看。</p>
      <el-button type="primary" @click="goLogin">前往登录</el-button>
    </div>

    <p class="account-footnote">这里只显示当前登录账号的基本信息。</p>
  </section>
</template>

<script setup>
import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {useRouter} from 'vue-router'
import {readLocalAccount} from '../api/accountApi.js'
import {getSession, logoutRole} from '../auth.js'
import {roleHome} from '../router.js'

const props = defineProps({ role: { type: String, required: true } })
const router = useRouter()
const loggingOut = ref(false)
const account = computed(() => readLocalAccount(props.role, getSession(props.role)))
const roleMark = computed(() => ({ customer: '食', merchant: '厨', rider: '送' })[props.role] || '人')
let generation = 0

watch(() => props.role, () => { generation++; loggingOut.value = false })
onBeforeUnmount(() => { generation++ })

function goHome() { void router.push(roleHome[props.role] || '/') }
function goLogin() { void router.push({ name: `${props.role}-login` }) }

async function logout() {
  if (loggingOut.value || !account.value) return
  loggingOut.value = true
  const currentGeneration = generation
  const role = props.role
  const token = getSession(role)?.token
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能使用此身份。', '退出当前账号', {
      confirmButtonText: '确认退出', cancelButtonText: '继续使用', type: 'warning',
    })
    if (currentGeneration !== generation || token !== getSession(role)?.token) return
    let cleared = false
    try { cleared = await logoutRole(role) } catch { cleared = false }
    if (currentGeneration !== generation) return
    const remainingToken = getSession(role)?.token
    if (remainingToken && remainingToken !== token) return
    if (!cleared) {
      if (remainingToken !== token) return
      ElMessage.error('退出前的本地清理尚未完成，当前仍保持登录。请稍后重试。')
      return
    }
    if (remainingToken) return
    await router.replace({ name: `${role}-login` })
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close' && currentGeneration === generation && token === getSession(role)?.token) {
      ElMessage.error(cause?.message || '暂时无法退出，请重试。')
    }
  } finally {
    if (currentGeneration === generation) loggingOut.value = false
  }
}
</script>

<style scoped>
.account-page{max-width:980px;margin:0 auto}.account-card{display:grid;grid-template-columns:84px minmax(0,1fr) auto;gap:22px;align-items:center}.account-mark{width:78px;height:78px;display:grid;place-items:center;border-radius:22px;background:#e9f1e8;color:#19664f;font:38px Georgia,serif}.account-copy{min-width:0}.account-copy .eyebrow{margin-bottom:3px}.account-copy h2{font-size:24px;margin:0 0 18px}.account-facts{display:flex;gap:30px;flex-wrap:wrap;margin:0}.account-facts div{display:grid;gap:4px}.account-facts dt{color:var(--color-muted);font-size:12px}.account-facts dd{margin:0;font-size:14px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.account-actions{display:grid;gap:10px;justify-items:stretch}.account-footnote{color:var(--color-muted);font-size:12px;line-height:1.8;margin:16px 4px}.page-state{margin-top:8px}
@media(max-width:700px){.account-card{grid-template-columns:58px minmax(0,1fr);gap:16px;padding:20px}.account-mark{width:56px;height:56px;border-radius:17px;font-size:29px}.account-copy h2{font-size:20px;margin-bottom:14px}.account-actions{grid-column:1/-1;grid-template-columns:1fr 1fr}.account-facts{gap:20px}}
@media(max-width:390px){.account-actions{grid-template-columns:1fr}}
</style>
