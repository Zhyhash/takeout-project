<template>
  <main class="register-page">
    <section class="register-story">
      <p class="eyebrow">TOKEOUT / {{ role.toUpperCase() }}</p>
      <span class="story-mark" aria-hidden="true">{{ copy.mark }}</span>
      <h1>{{ copy.title }}</h1>
      <p>{{ copy.description }}</p>
      <router-link :to="{ name: `${role}-login` }">已有账号？去登录 →</router-link>
    </section>

    <section class="register-panel">
      <p class="eyebrow">创建账号</p>
      <h2>{{ roleLabels[role] }}注册</h2>
      <p class="muted">注册完成后请使用新账号登录。</p>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="register-error" />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item :label="role === 'rider' ? '骑手名称' : '登录名'" :prop="nameField">
          <el-input v-model="form[nameField]" :autocomplete="role === 'rider' ? 'nickname' : 'username'" maxlength="50" placeholder="英文字母、数字、下划线或连字符" :disabled="busy" />
        </el-form-item>

        <template v-if="role === 'merchant'">
          <el-form-item label="商家名称" prop="merchantName">
            <el-input v-model="form.merchantName" autocomplete="organization" maxlength="255" placeholder="顾客看到的店铺名称" :disabled="busy" />
          </el-form-item>
          <el-form-item label="店铺地址（选填）" prop="address">
            <el-input v-model="form.address" maxlength="255" placeholder="可稍后补充" :disabled="busy" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" autocomplete="tel" maxlength="20" inputmode="tel" placeholder="用于账号联系" :disabled="busy" />
          </el-form-item>
          <el-form-item label="店铺图片 URL（选填）" prop="pictureURL">
            <el-input v-model="form.pictureURL" maxlength="255" placeholder="https://…" :disabled="busy" />
          </el-form-item>
          <el-form-item label="店铺简介（选填）" prop="description">
            <el-input v-model="form.description" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="简单介绍你的店铺" :disabled="busy" />
          </el-form-item>
        </template>
        <el-form-item v-else label="手机号" prop="phone">
          <el-input v-model="form.phone" autocomplete="tel" maxlength="20" inputmode="tel" placeholder="用于账号联系" :disabled="busy" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password maxlength="20" placeholder="8–20 位" :disabled="busy" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password maxlength="20" placeholder="再次输入密码" :disabled="busy" @keyup.enter="submit" />
        </el-form-item>

        <el-button type="primary" native-type="submit" size="large" class="submit-register" :loading="busy" :disabled="busy">创建{{ roleLabels[role] }}账号</el-button>
      </el-form>
      <p class="register-hint">密码只用于本次注册请求，不会保存在当前浏览器的登录状态中。</p>
    </section>
  </main>
</template>

<script setup>
import {computed, onBeforeUnmount, reactive, ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useRoute, useRouter} from 'vue-router'
import {registerAccount} from '../api/accountApi.js'
import {roleLabels} from '../router.js'

const props = defineProps({ role: { type: String, required: true } })
const router = useRouter()
const route = useRoute()
const formRef = ref()
const busy = ref(false)
const error = ref('')
const form = reactive({ username: '', name: '', password: '', confirmPassword: '', phone: '', merchantName: '', address: '', pictureURL: '', description: '' })
const stories = {
  customer: { mark: '食', title: '从喜欢的一餐开始。', description: '创建顾客账号，选好这一餐想吃的味道。' },
  merchant: { mark: '厨', title: '让好味道被更多人看见。', description: '开通商家账号后，即可登录订单工作台。' },
  rider: { mark: '送', title: '准备好开启每一段路程。', description: '创建骑手账号，登录后查看配送工作台。' },
}
const copy = computed(() => stories[props.role] || stories.customer)
const nameField = computed(() => props.role === 'rider' ? 'name' : 'username')
const nameRules = [
  { required: true, message: '请输入登录名', trigger: 'blur' },
  { pattern: /^[A-Za-z0-9_-]{1,50}$/, message: '仅支持英文字母、数字、下划线和连字符，最多50位', trigger: 'blur' },
]
const passwordRules = [
  { required: true, message: '请输入密码', trigger: 'blur' },
  { min: 8, max: 20, message: '密码长度为 8–20 位', trigger: 'blur' },
]
const rules = {
  username: nameRules,
  name: nameRules,
  merchantName: [
    { required: true, message: '请输入商家名称', trigger: 'blur' },
    { max: 255, message: '商家名称不能超过255个字符', trigger: 'blur' },
  ],
  address: [{ max: 255, message: '地址不能超过255个字符', trigger: 'blur' }],
  pictureURL: [{ max: 255, message: '图片 URL 不能超过255个字符', trigger: 'blur' }],
  description: [{ max: 255, message: '店铺简介不能超过255个字符', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^(?:(?:\+|00)86)?1[3-9]\d{9}$/, message: '请输入有效的中国大陆手机号', trigger: 'blur' },
  ],
  password: passwordRules,
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { min: 8, max: 20, message: '确认密码长度为 8–20 位', trigger: 'blur' },
    { validator: (_rule, value, callback) => value === form.password ? callback() : callback(new Error('两次输入的密码不一致')), trigger: 'blur' },
  ],
}

let generation = 0
watch(() => props.role, () => resetForm())
onBeforeUnmount(() => { generation++; form.password = ''; form.confirmPassword = '' })

function resetForm() {
  generation++
  for (const key of Object.keys(form)) form[key] = ''
  busy.value = false
  error.value = ''
  formRef.value?.clearValidate()
}

async function submit() {
  if (busy.value) return
  const current = generation
  const role = props.role
  busy.value = true
  error.value = ''
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || current !== generation) {
    if (current === generation) busy.value = false
    return
  }

  const username = form[nameField.value].trim()
  try {
    await registerAccount(role, { ...form })
    if (current !== generation) return
    form.password = ''
    form.confirmPassword = ''
    ElMessage.success('注册成功，请登录')
    await router.replace({ name: `${role}-login`, query: { username, ...(typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {}) } })
  } catch (cause) {
    if (current === generation) error.value = cause.kind === 'network'
      ? '注册结果暂时无法确认，请先尝试使用这个账号登录，再决定是否重新注册。'
      : cause.message || '注册失败，请检查信息后重试。'
  } finally {
    if (current === generation) busy.value = false
  }
}
</script>

<style scoped>
.register-page{max-width:1080px;margin:24px auto;display:grid;grid-template-columns:minmax(260px,.82fr) minmax(0,1.18fr);background:#fff;border:1px solid var(--color-border);border-radius:24px;overflow:hidden;box-shadow:0 20px 70px #20372f09}.register-story{background:#174c3d;color:#f8f8f2;padding:42px;display:flex;flex-direction:column;align-items:flex-start}.register-story .eyebrow{color:#b5cdbb}.story-mark{display:grid;place-items:center;width:86px;height:86px;border:1px solid #c9dcc44d;border-radius:50%;font:42px serif;margin:48px 0 24px;color:#d8e2c7}.register-story h1{font-size:31px;line-height:1.55;margin:0 0 12px}.register-story p:not(.eyebrow){color:#c6d8cd;line-height:1.9;font-size:14px}.register-story a{color:#eff3e7;font-size:13px;text-decoration:none;margin-top:auto;padding-top:32px}.register-panel{padding:42px 46px;max-height:min(920px,calc(100vh - 40px));overflow-y:auto}.register-panel h2{font-size:28px;margin:8px 0}.register-panel>.muted{font-size:14px;margin:0 0 25px}.register-error{margin-bottom:18px}.register-panel :deep(.el-form-item){margin-bottom:18px}.register-panel :deep(.el-form-item__label){padding-bottom:4px}.submit-register{width:100%;margin-top:7px}.register-hint{color:var(--color-muted);font-size:12px;line-height:1.8;margin-top:20px}
@media(max-width:720px){.register-page{grid-template-columns:1fr;margin:8px auto;border-radius:18px}.register-story{padding:25px 26px}.story-mark{display:none}.register-story h1{font-size:24px;margin:10px 0 4px}.register-story p:not(.eyebrow){margin:4px 0}.register-story a{margin-top:8px;padding-top:8px}.register-panel{padding:27px 24px;max-height:none}.register-panel h2{font-size:24px}}
</style>
