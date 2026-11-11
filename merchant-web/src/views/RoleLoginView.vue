<template>
  <div class="role-login">
    <section class="login-story"><p class="eyebrow">TOKEOUT / {{ role.toUpperCase() }}</p><span class="story-mark" aria-hidden="true">{{ copy.mark }}</span><h1>{{ copy.title }}</h1><p>{{ copy.description }}</p><router-link :to="role === 'customer' ? '/customer/shops' : '/'">{{ role === 'customer' ? '先逛逛店铺' : '返回选择入口' }} →</router-link></section>
    <section class="login-form-panel">
      <p class="eyebrow">欢迎回来</p><h2>{{ roleLabels[role] }}登录</h2><p class="muted">使用{{ roleLabels[role] }}账号继续。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="登录名" prop="username"><el-input v-model="form.username" autocomplete="username" maxlength="50" placeholder="英文字母、数字、下划线或连字符" :disabled="busy" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" autocomplete="current-password" show-password maxlength="20" placeholder="8–20 位密码" :disabled="busy" /></el-form-item>
        <el-button type="primary" native-type="submit" size="large" :loading="busy" class="submit-login">登录{{ role === 'customer' ? '' : '工作台' }}</el-button>
      </el-form>
      <p class="login-hint">登录凭证仅保存在当前标签页。<RouterLink :to="{name: `${role}-register`, query: {redirect: route.query.redirect}}" class="text-link">还没有账号？去注册</RouterLink></p>
    </section>
  </div>
</template>
<script setup>
import {computed, onBeforeUnmount, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {createRoleHttp} from '../api/http.js'
import {setRoleSession} from '../auth.js'
import {roleLabels, safeRedirect} from '../router.js'

const props = defineProps({ role: { type: String, required: true } })
const route = useRoute(), router = useRouter()
const form = reactive({ username: '', password: '' }), formRef = ref(), busy = ref(false), error = ref('')
if (typeof route.query.username === 'string' && /^[A-Za-z0-9_-]{1,50}$/.test(route.query.username)) form.username = route.query.username
const stories = {
  customer: { mark: '食', title: '这一餐，吃点喜欢的。', description: '逛逛店铺，选好餐品，把热乎的交给我们。' },
  merchant: { mark: '厨', title: '每一份订单，从容做好。', description: '接好订单、用心备餐，让每一餐准时出发。' },
  rider: { mark: '送', title: '从这里出发，把美味送到。', description: '查看配送任务，安排好手上的每一单。' },
}
const copy = computed(() => stories[props.role])
const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }, { pattern: /^[A-Za-z0-9_-]{1,50}$/, message: '登录名仅支持英文字母、数字、下划线和连字符', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 8, max: 20, message: '密码长度为 8–20 位', trigger: 'blur' }],
}
let generation = 0
watch(() => props.role, () => { generation++; form.username = ''; form.password = ''; busy.value = false; error.value = ''; formRef.value?.clearValidate() })
onBeforeUnmount(() => { generation++; form.password = '' })
async function submit() {
  if (busy.value) return
  const current = generation, role = props.role
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || current !== generation || busy.value) return
  busy.value = true; error.value = ''
  try {
    const credentials = role === 'rider' ? { name: form.username, password: form.password } : { username: form.username, password: form.password }
    const data = await createRoleHttp(role).post(`/${role === 'customer' ? 'user' : role}/login`, credentials, { skipAuth: true })
    if (current !== generation) return
    setRoleSession(role, data)
    form.password = ''
    await router.replace(safeRedirect(route.query.redirect, role))
  } catch (cause) { if (current === generation) error.value = cause.message || '登录失败，请重试' }
  finally { if (current === generation) busy.value = false }
}
</script>
<style scoped>
.role-login{max-width:1040px;margin:30px auto;display:grid;grid-template-columns:1fr 1fr;background:white;border:1px solid var(--color-border);border-radius:24px;overflow:hidden;box-shadow:0 20px 70px #20372f09}.login-story{background:#174c3d;color:#f8f8f2;padding:44px;display:flex;flex-direction:column;align-items:flex-start}.login-story .eyebrow{color:#b5cdbb}.story-mark{display:grid;place-items:center;width:90px;height:90px;border:1px solid #c9dcc44d;border-radius:50%;font:45px serif;margin:50px 0 26px;color:#d8e2c7}.login-story h1{font-size:33px;line-height:1.6;margin:0 0 16px}.login-story p:not(.eyebrow){color:#c6d8cd;line-height:1.9;font-size:14px}.login-story a{color:#eff3e7;font-size:13px;text-decoration:none;margin-top:34px}.login-form-panel{padding:60px 42px}.login-form-panel h2{font-size:29px;margin:12px 0}.login-form-panel>.muted{font-size:14px;margin-bottom:30px}.login-form-panel .el-alert{margin-bottom:20px}.submit-login{width:100%;margin-top:10px}.login-hint{color:var(--color-muted);font-size:12px;line-height:1.8;margin-top:24px}@media(max-width:680px){.role-login{grid-template-columns:1fr;margin:8px auto;border-radius:18px}.login-story{padding:26px}.story-mark{display:none}.login-story h1{font-size:25px;margin:14px 0 5px}.login-story p:not(.eyebrow){margin:5px 0}.login-story a{margin-top:12px}.login-form-panel{padding:28px 24px}.login-form-panel h2{font-size:25px}}
</style>
