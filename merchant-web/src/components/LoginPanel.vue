<template>
  <div class="login-wrap">
    <main class="login-shell">
      <section class="brand-panel" aria-labelledby="brand-heading">
        <div class="brand-lockup">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 40 40" fill="none">
              <rect x="2" y="2" width="36" height="36" rx="12" fill="currentColor" />
              <circle cx="20" cy="20" r="10" stroke="#EAF1E9" stroke-width="1.5" />
              <circle cx="20" cy="20" r="6.5" stroke="#EAF1E9" stroke-width="1.5" />
              <path d="M25.5 11.5c3.8.2 5.6 2.1 5.7 5.8-3.7-.1-5.6-2-5.7-5.8Z" fill="#C8D7BD" />
            </svg>
          </span>
          <span class="brand-name">商家工作台</span>
        </div>

        <div class="brand-copy">
          <p class="brand-eyebrow">好好做餐，也好好接单</p>
          <h1 id="brand-heading">每一份订单，<br /><span>都从容做好。</span></h1>
          <p class="brand-description">在这里查看订单、准备餐食，让每一餐都准时送达。</p>
        </div>

        <div class="order-art" aria-hidden="true">
          <svg viewBox="0 0 360 220" fill="none">
            <ellipse cx="183" cy="190" rx="132" ry="15" fill="#173E34" fill-opacity=".28" />
            <rect x="42" y="39" width="276" height="150" rx="22" fill="#F7F5ED" />
            <rect x="59" y="56" width="52" height="52" rx="16" fill="#E7EDE3" />
            <circle cx="85" cy="82" r="14" stroke="#54846B" stroke-width="2" />
            <circle cx="85" cy="82" r="8" stroke="#93AD91" stroke-width="2" />
            <path d="M80 82c3-5 8-5 11 0-3 5-8 5-11 0Z" fill="#C9A46C" />
            <rect x="126" y="63" width="112" height="9" rx="4.5" fill="#344F43" />
            <rect x="126" y="81" width="78" height="7" rx="3.5" fill="#C9D2C7" />
            <rect x="259" y="64" width="42" height="22" rx="11" fill="#E4EFE4" />
            <circle cx="271" cy="75" r="3" fill="#478263" />
            <rect x="59" y="126" width="242" height="1" fill="#E2E7DE" />
            <rect x="60" y="145" width="88" height="7" rx="3.5" fill="#829287" />
            <rect x="260" y="142" width="40" height="13" rx="6.5" fill="#DDE9DC" />
            <path d="M275 148.5l3 3 6-6" stroke="#397451" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M31 51c10-12 21-15 31-11-5 11-15 15-31 11Z" fill="#AEC6A8" fill-opacity=".85" />
            <path d="M310 155c9-11 19-13 28-9-4 10-14 13-28 9Z" fill="#AEC6A8" fill-opacity=".75" />
          </svg>
        </div>
      </section>

      <section class="form-panel" aria-labelledby="login-heading">
        <div class="form-inner">
          <div class="form-heading">
            <p class="form-eyebrow">欢迎回来</p>
            <h2 id="login-heading">登录商家工作台</h2>
            <p class="form-description">使用商家账号继续处理订单。</p>
          </div>

          <el-alert
            v-if="errorMessage"
            :title="errorMessage"
            type="error"
            show-icon
            :closable="false"
            class="login-error"
          />
          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            @submit.prevent
          >
            <el-form-item label="用户名" prop="username">
              <el-input
                v-model="form.username"
                placeholder="商家登录名"
                :disabled="submitting"
                maxlength="50"
              />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="8-20 位密码"
                :disabled="submitting"
                maxlength="20"
                @keyup.enter="submit"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="login-button"
              :loading="submitting"
              @click="submit"
            >
              登录工作台
            </el-button>
          </el-form>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import {reactive, ref} from 'vue'
import {merchantLogin} from '../api/merchantApi.js'
import {setSession} from '../auth.js'

const formRef = ref(null)
const submitting = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', password: '' })

// 与后端 MerchantLoginDTO 的校验规则保持一致，仅用于减少明显无效的请求。
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]{1,50}$/,
      message: '仅限英文字母、数字、下划线和连字符，长度 1-50',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 20, message: '密码长度必须在 8 到 20 位之间', trigger: 'blur' },
  ],
}

async function submit() {
  if (submitting.value) return
  let valid = true
  try {
    await formRef.value.validate()
  } catch {
    valid = false
  }
  if (!valid) return

  submitting.value = true
  errorMessage.value = ''
  try {
    const data = await merchantLogin(form.username.trim(), form.password)
    setSession({ id: data.id, token: data.token })
  } catch (error) {
    // 登录失败（密码错误 code=500、限流 code=403、参数错误 code=400）都留在登录页提示。
    errorMessage.value = error.message || '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 28px;
  color: var(--color-ink, #20372f);
  background:
    radial-gradient(ellipse at 12% 16%, rgba(217, 226, 208, 0.5), transparent 36%),
    var(--color-page, #f5f6f1);
}
.login-shell {
  display: grid;
  grid-template-columns: minmax(0, 1.06fr) minmax(380px, 0.94fr);
  width: min(1080px, 100%);
  min-height: 590px;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--color-border, #e2e8df);
  border-radius: 28px;
  box-shadow: 0 24px 70px rgba(36, 61, 48, 0.1);
}
.brand-panel {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  padding: 40px 48px 30px;
  color: #f7f8f2;
  background:
    radial-gradient(circle at 89% 13%, rgba(190, 211, 181, 0.14), transparent 28%),
    linear-gradient(145deg, #1e6b53 0%, #145442 55%, #124737 100%);
}
.brand-panel::after {
  position: absolute;
  right: -105px;
  bottom: -155px;
  width: 360px;
  height: 360px;
  border: 1px solid rgba(237, 243, 232, 0.1);
  border-radius: 50%;
  box-shadow: 0 0 0 28px rgba(237, 243, 232, 0.035), 0 0 0 58px rgba(237, 243, 232, 0.025);
  content: '';
  pointer-events: none;
}
.brand-lockup {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 11px;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0.04em;
}
.brand-mark,
.brand-mark svg {
  display: block;
  width: 40px;
  height: 40px;
}
.brand-mark {
  color: #d8e4d1;
}
.brand-copy {
  position: relative;
  z-index: 1;
  margin-top: 72px;
}
.brand-eyebrow,
.form-eyebrow {
  margin: 0 0 15px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.12em;
}
.brand-eyebrow {
  color: #c1d8bf;
}
.brand-copy h1 {
  margin: 0;
  font-size: clamp(32px, 3.5vw, 43px);
  font-weight: 650;
  letter-spacing: -0.045em;
  line-height: 1.32;
}
.brand-copy h1 span {
  color: #d1dfc8;
}
.brand-description {
  max-width: 310px;
  margin: 19px 0 0;
  color: rgba(243, 247, 239, 0.76);
  font-size: 14px;
  line-height: 1.8;
}
.order-art {
  position: relative;
  z-index: 1;
  width: min(360px, 100%);
  margin: auto auto 0;
  padding-top: 20px;
}
.order-art svg {
  display: block;
  width: 100%;
  height: auto;
}
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding: 56px clamp(32px, 6vw, 76px);
  background: #fff;
}
.form-inner {
  width: min(100%, 360px);
}
.form-heading {
  margin-bottom: 34px;
}
.form-eyebrow {
  margin-bottom: 9px;
  color: var(--color-primary, #19664f);
  letter-spacing: 0.04em;
}
.form-heading h2 {
  margin: 0;
  color: var(--color-ink, #20372f);
  font-size: 27px;
  font-weight: 650;
  letter-spacing: -0.035em;
}
.form-description {
  margin: 10px 0 0;
  color: var(--color-muted, #718078);
  font-size: 14px;
  line-height: 1.6;
}
.login-error {
  margin-bottom: 20px;
  border-radius: 10px;
}
.form-inner :deep(.el-form-item) {
  margin-bottom: 23px;
}
.form-inner :deep(.el-form-item__label) {
  padding-bottom: 8px;
  color: #42574c;
  font-size: 13px;
  font-weight: 600;
}
.form-inner :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 1px 14px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px var(--color-border, #e2e8df) inset;
  transition: box-shadow 0.18s ease;
}
.form-inner :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b8c9bb inset;
}
.form-inner :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--color-primary, #19664f) inset;
}
.login-button {
  width: 100%;
  height: 48px;
  margin-top: 5px;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  box-shadow: 0 7px 16px rgba(25, 102, 79, 0.15);
}
.login-button:focus-visible {
  outline: 3px solid rgba(25, 102, 79, 0.24);
  outline-offset: 3px;
}
@media (max-width: 760px) {
  .login-wrap {
    align-items: flex-start;
    padding: 20px 16px;
  }
  .login-shell {
    grid-template-columns: 1fr;
    min-height: 0;
    max-width: 520px;
    border-radius: 22px;
  }
  .brand-panel {
    min-height: 0;
    padding: 24px 26px 20px;
  }
  .brand-mark,
  .brand-mark svg {
    width: 34px;
    height: 34px;
  }
  .brand-copy {
    margin-top: 26px;
  }
  .brand-eyebrow {
    margin-bottom: 8px;
    font-size: 11px;
  }
  .brand-copy h1 {
    font-size: 28px;
  }
  .brand-description {
    max-width: 360px;
    margin-top: 10px;
    font-size: 13px;
  }
  .order-art {
    display: none;
  }
  .form-panel {
    padding: 34px 26px 38px;
  }
  .form-heading {
    margin-bottom: 26px;
  }
}
@media (max-width: 380px) {
  .login-wrap {
    padding: 12px;
  }
  .brand-panel {
    padding: 21px 20px 18px;
  }
  .form-panel {
    padding: 28px 20px 32px;
  }
  .form-heading h2 {
    font-size: 24px;
  }
}
</style>
