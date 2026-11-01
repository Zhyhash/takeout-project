<template>
  <section class="shop-settings">
    <header class="page-heading">
      <div>
        <p class="eyebrow">SHOP PROFILE</p>
        <h1>店铺资料与营业设置</h1>
        <p class="muted">维护顾客可见的店铺信息，并单独管理营业状态。</p>
      </div>
      <el-tag v-if="shop && !statusUnknown" :type="shop.status === 0 ? 'success' : 'info'" effect="light" size="large">
        {{ shop.status === 0 ? '营业中' : '已打烊' }}
      </el-tag>
    </header>

    <div v-if="!session" class="surface settings-state">
      <div class="state-symbol" aria-hidden="true">⌁</div>
      <h2>请先登录商家账号</h2>
      <p class="muted">登录后才能查看和维护店铺资料。</p>
      <el-button type="primary" @click="goLogin">前往登录</el-button>
    </div>

    <div v-else-if="loading" class="surface settings-state">
      <el-skeleton animated :rows="7" />
    </div>

    <div v-else-if="loadError" class="surface settings-state">
      <div class="state-symbol" aria-hidden="true">!</div>
      <h2>暂时无法读取店铺资料</h2>
      <p class="muted">{{ loadError }}</p>
      <el-button type="primary" :loading="busy === 'refreshing'" @click="refreshShop">重新读取</el-button>
    </div>

    <template v-else-if="shop">
      <el-alert
        v-if="statusUnknown"
        class="settings-alert"
        type="warning"
        show-icon
        :closable="false"
        title="暂时无法确认店铺营业状态"
        description="请刷新店铺资料后再进行资料保存或营业状态操作。"
      ><el-button text @click="refreshShop">重新读取店铺资料</el-button></el-alert>

      <div class="settings-layout">
        <section class="surface profile-panel" aria-labelledby="profile-heading">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">PUBLIC PROFILE</p>
              <h2 id="profile-heading">顾客可见资料</h2>
            </div>
            <span class="panel-hint">仅保存实际修改的字段</span>
          </div>

          <el-alert
            v-if="shop.status === 0"
            class="settings-alert"
            type="info"
            show-icon
            :closable="false"
            title="营业中不能修改店铺资料"
            description="请通过右侧营业状态操作手动打烊，再修改资料。页面不会替你自动打烊。"
          />

          <div class="identity-row">
            <div class="shop-mark" aria-label="店铺图片预览">
              <img v-if="imagePreview && !imageBroken" :src="imagePreview" alt="店铺图片预览" @error="imageBroken = true" />
              <span v-else aria-hidden="true">{{ draft.merchantName?.trim()?.slice(0, 1) || '店' }}</span>
            </div>
            <div>
              <strong>{{ draft.merchantName || '店铺名称' }}</strong>
              <p class="muted">图片使用安全的 HTTP(S) 图片地址或站内绝对路径预览。</p>
            </div>
          </div>

          <el-form label-position="top" class="profile-form" @submit.prevent>
            <div class="profile-grid">
              <el-form-item label="店铺名称">
                <el-input v-model="draft.merchantName" maxlength="255" show-word-limit :disabled="!canEditProfile" placeholder="请输入店铺名称" />
              </el-form-item>
              <el-form-item label="店铺地址">
                <el-input v-model="draft.address" maxlength="255" show-word-limit :disabled="!canEditProfile" placeholder="请输入顾客可见地址" />
              </el-form-item>
              <el-form-item label="店铺简介" class="wide-field">
                <el-input v-model="draft.description" type="textarea" :rows="3" maxlength="255" show-word-limit :disabled="!canEditProfile" placeholder="简单介绍店铺特色（选填）" />
              </el-form-item>
              <el-form-item label="店铺图片 URL" class="wide-field">
                <el-input v-model="draft.pictureURL" maxlength="255" show-word-limit :disabled="!canEditProfile" placeholder="https://… 或 /images/shop.png" />
                <p v-if="draft.pictureURL && !imagePreview" class="field-note field-error">仅预览 HTTP(S) 地址或以 / 开头的站内路径。</p>
                <p v-else class="field-note">填写图片地址后，可以在上方查看预览。</p>
              </el-form-item>
              <el-form-item label="开门时间">
                <el-time-picker v-model="draft.openingTime" format="HH:mm" value-format="HH:mm" :disabled="!canEditProfile" placeholder="选择开门时间" />
              </el-form-item>
              <el-form-item label="打烊时间">
                <el-time-picker v-model="draft.closingTime" format="HH:mm" value-format="HH:mm" :disabled="!canEditProfile" placeholder="选择打烊时间" />
              </el-form-item>
            </div>
          </el-form>

          <el-alert v-if="saveError" class="settings-alert" type="error" show-icon :closable="false" :title="saveError" />
          <div class="panel-actions">
            <span class="muted">{{ shop.status === 1 ? '资料修改仅在打烊状态下开放。' : '当前营业状态下资料为只读。' }}</span>
            <el-button type="primary" :loading="busy === 'saving-profile'" :disabled="!canEditProfile || Boolean(busy)" @click="saveProfile">保存资料</el-button>
          </div>
        </section>

        <aside class="settings-side">
          <section class="surface status-panel" aria-labelledby="status-heading">
            <p class="eyebrow">BUSINESS HOURS</p>
            <h2 id="status-heading">营业状态</h2>
            <div class="status-summary" :class="shop.status === 0 ? 'is-open' : shop.status === 1 ? 'is-closed' : 'is-unknown'">
              <span class="status-dot" aria-hidden="true"></span>
              <div><strong>{{ shop.status === 0 ? '营业中' : shop.status === 1 ? '已打烊' : '状态未知' }}</strong><span>{{ shop.status === 0 ? '顾客可以浏览店铺并尝试下单。' : shop.status === 1 ? '顾客会看到店铺当前已打烊。' : '请重新读取店铺资料以确认当前状态。' }}</span></div>
            </div>
            <div class="hours-line"><span>营业时间</span><strong>{{ timeLabel(shop.openingTime) }} — {{ timeLabel(shop.closingTime) }}</strong></div>
            <el-alert v-if="statusNotice" class="settings-alert" type="warning" show-icon :closable="false" :title="statusNotice" />
            <el-button
              class="status-action"
              :type="shop.status === 0 ? 'warning' : 'primary'"
              :loading="busy === 'changing-status' || busy === 'confirming-status' || busy === 'refreshing'"
              :disabled="Boolean(busy)"
              @click="statusUnknown ? refreshShop() : changeBusinessStatus()"
            >{{ statusUnknown ? '刷新店铺状态' : shop.status === 0 ? '手动打烊' : '开始营业' }}</el-button>
            <p class="field-note">切换营业状态会立即影响顾客看到的店铺状态，请在确认框中核对操作。</p>
          </section>

          <section class="surface sensitive-panel" aria-labelledby="contact-heading">
            <p class="eyebrow">PRIVATE SETTINGS</p>
            <h2 id="contact-heading">手机号与门店坐标</h2>
            <p class="muted">此页面无法读取已保存的手机号与坐标。需要修改时，请主动输入完整的新值。</p>

            <div class="private-field">
              <div class="private-field-heading"><strong>联系手机号</strong><span class="private-value">{{ hidden.phoneKnown ? (hidden.phone || '本次响应未提供手机号') : '尚未读取' }}</span></div>
              <el-checkbox v-model="modifyPhone" :disabled="!canEditProfile || Boolean(busy)">主动修改手机号</el-checkbox>
              <el-form-item v-if="modifyPhone" label="新手机号" class="private-input">
                <el-input v-model="phoneDraft" type="tel" inputmode="tel" autocomplete="tel" maxlength="20" placeholder="支持 13800138000、+8613800138000" :disabled="!canEditProfile || Boolean(busy)" />
              </el-form-item>
            </div>

            <div class="private-field">
              <div class="private-field-heading"><strong>门店经纬度</strong><span class="private-value">{{ hidden.coordinatesKnown ? coordinateLabel : '尚未读取' }}</span></div>
              <el-checkbox v-model="modifyCoordinates" :disabled="!canEditProfile || Boolean(busy)">主动设置经纬度</el-checkbox>
              <div v-if="modifyCoordinates" class="coordinate-grid">
                <el-form-item label="经度" class="private-input">
                  <el-input v-model="longitudeDraft" inputmode="decimal" placeholder="-180 至 180" :disabled="!canEditProfile || Boolean(busy)" />
                </el-form-item>
                <el-form-item label="纬度" class="private-input">
                  <el-input v-model="latitudeDraft" inputmode="decimal" placeholder="-85.05112878 至 85.05112878" :disabled="!canEditProfile || Boolean(busy)" />
                </el-form-item>
                <p class="field-note coordinate-note">经度与纬度必须同时填写，最多保留7位小数。</p>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { getSession } from '../../auth.js'
import { getPublicShopSettings, updateShopBusinessStatus, updateShopSettings } from '../../api/shopSettingsApi.js'
import { normalizeShopSettingsPayload } from './shopSettingsPayload.js'

const router = useRouter()
const session = computed(() => getSession('merchant'))
const sessionKey = computed(() => session.value ? `${session.value.id}:${session.value.token}` : '')
const shop = ref(null)
const baseline = ref(null)
const loading = ref(false)
const loadError = ref('')
const saveError = ref('')
const statusNotice = ref('')
const statusUnknown = ref(false)
const busy = ref('')
const imageBroken = ref(false)
const modifyPhone = ref(false)
const modifyCoordinates = ref(false)
const phoneDraft = ref('')
const longitudeDraft = ref('')
const latitudeDraft = ref('')
const hidden = reactive({ phoneKnown: false, phone: '', coordinatesKnown: false, longitude: null, latitude: null })
const draft = reactive({ merchantName: '', address: '', description: '', pictureURL: '', openingTime: '', closingTime: '' })

let mounted = false
const generation = ref(0)
const publicRequestSequence = ref(0)

const canEditProfile = computed(() => Boolean(shop.value && shop.value.status === 1 && !statusUnknown.value && !busy.value))
const imagePreview = computed(() => safeShopImageUrl(draft.pictureURL))
const coordinateLabel = computed(() => hidden.longitude != null && hidden.latitude != null
  ? `${hidden.longitude}, ${hidden.latitude}` : '本次响应未提供完整坐标')

function safeShopImageUrl(value) {
  const candidate = String(value ?? '').trim()
  if (!candidate || /[\u0000-\u001f\\]/.test(candidate)) return ''
  if (/^https?:\/\//i.test(candidate)) {
    try {
      const parsed = new URL(candidate)
      return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : ''
    } catch { return '' }
  }
  if (candidate.startsWith('/') && !candidate.startsWith('//')) return candidate
  return ''
}

function statusCode(value) {
  if (value === 0 || value === '0') return 0
  if (value === 1 || value === '1') return 1
  return null
}

function displayTime(value) {
  const candidate = String(value ?? '').trim()
  return /^([01]\d|2[0-3]):[0-5]\d/.test(candidate) ? candidate.slice(0, 5) : ''
}

function timeLabel(value) {
  const formatted = displayTime(value)
  return formatted || '未设置'
}

function publicDraft(data) {
  return {
    merchantName: String(data?.merchantName ?? ''),
    address: String(data?.address ?? ''),
    description: String(data?.description ?? ''),
    pictureURL: String(data?.pictureURL ?? ''),
    openingTime: displayTime(data?.openingTime),
    closingTime: displayTime(data?.closingTime),
  }
}

function captureContext() {
  const current = getSession('merchant')
  if (!current) return null
  return { id: String(current.id), token: current.token, generation: generation.value }
}

function isContextCurrent(context) {
  if (!mounted || !context || generation.value !== context.generation) return false
  const current = getSession('merchant')
  return Boolean(current && String(current.id) === context.id && current.token === context.token)
}

function resetHiddenSnapshot() {
  hidden.phoneKnown = false
  hidden.phone = ''
  hidden.coordinatesKnown = false
  hidden.longitude = null
  hidden.latitude = null
}

function resetSessionState() {
  publicRequestSequence.value += 1
  busy.value = ''
  shop.value = null
  baseline.value = null
  loading.value = false
  loadError.value = ''
  saveError.value = ''
  statusNotice.value = ''
  statusUnknown.value = false
  imageBroken.value = false
  modifyPhone.value = false
  modifyCoordinates.value = false
  phoneDraft.value = ''
  longitudeDraft.value = ''
  latitudeDraft.value = ''
  resetHiddenSnapshot()
  Object.assign(draft, { merchantName: '', address: '', description: '', pictureURL: '', openingTime: '', closingTime: '' })
}

function applyPublicShop(data, preserveDraft) {
  const values = publicDraft(data)
  shop.value = { ...data, status: statusCode(data?.status) }
  baseline.value = { ...values }
  statusUnknown.value = shop.value.status === null
  if (!preserveDraft) Object.assign(draft, values)
  imageBroken.value = false
}

async function loadPublicShop({ silent = false, preserveDraft = false } = {}) {
  const context = captureContext()
  if (!context) return null
  const sequence = ++publicRequestSequence.value
  if (!silent) {
    loading.value = true
    loadError.value = ''
  }
  try {
    const data = await getPublicShopSettings(context.id)
    if (!isContextCurrent(context) || sequence !== publicRequestSequence.value) return null
    if (!data || typeof data !== 'object') throw new Error('服务器返回的店铺资料格式不正确。')
    applyPublicShop(data, preserveDraft)
    return data
  } catch (cause) {
    if (isContextCurrent(context) && sequence === publicRequestSequence.value && !silent) {
      loadError.value = cause?.message || '店铺资料读取失败，请重试。'
    }
    throw cause
  } finally {
    if (isContextCurrent(context) && sequence === publicRequestSequence.value && !silent) loading.value = false
  }
}

async function refreshShop() {
  if (busy.value) return
  const context = captureContext()
  if (!context) return
  busy.value = 'refreshing'
  try {
    await loadPublicShop()
    if (isContextCurrent(context) && shop.value) ElMessage.success('店铺公开资料已刷新。')
  } catch { /* The page shows the read error. */ }
  finally { if (isContextCurrent(context)) busy.value = '' }
}

function applyUpdateResponse(response) {
  const next = { ...(shop.value || {}) }
  for (const field of ['merchantName', 'address', 'description', 'pictureURL', 'openingTime', 'closingTime', 'status', 'statusDesc']) {
    if (Object.hasOwn(response, field)) next[field] = response[field]
  }
  next.status = statusCode(next.status)
  shop.value = next
  const values = publicDraft(next)
  baseline.value = { ...values }
  Object.assign(draft, values)
  statusUnknown.value = next.status === null
  if (Object.hasOwn(response, 'phone')) {
    hidden.phoneKnown = true
    hidden.phone = response.phone == null ? '' : String(response.phone)
  }
  if (Object.hasOwn(response, 'longitude') && Object.hasOwn(response, 'latitude')) {
    hidden.coordinatesKnown = true
    hidden.longitude = response.longitude
    hidden.latitude = response.latitude
  }
  modifyPhone.value = false
  modifyCoordinates.value = false
  phoneDraft.value = ''
  longitudeDraft.value = ''
  latitudeDraft.value = ''
  saveError.value = ''
  statusNotice.value = ''
  imageBroken.value = false
}

async function saveProfile() {
  if (busy.value || !canEditProfile.value || !baseline.value) return
  const { payload, errors } = normalizeShopSettingsPayload({
    baseline: baseline.value,
    draft,
    modifyPhone: modifyPhone.value,
    phoneDraft: phoneDraft.value,
    modifyCoordinates: modifyCoordinates.value,
    longitudeDraft: longitudeDraft.value,
    latitudeDraft: latitudeDraft.value,
  })
  if (Object.keys(errors).length) {
    ElMessage.warning(Object.values(errors)[0])
    return
  }
  const context = captureContext()
  if (!context) return
  busy.value = 'saving-profile'
  saveError.value = ''
  try {
    const response = await updateShopSettings(payload)
    if (!isContextCurrent(context)) return
    if (!response || typeof response !== 'object') {
      throw new Error('服务器未返回可核对的店铺资料。')
    }
    applyUpdateResponse(response)
    ElMessage.success('店铺资料已保存。手机号和坐标仅根据本次保存响应展示。')
  } catch (cause) {
    if (!isContextCurrent(context)) return
    if (modifyPhone.value) {
      hidden.phoneKnown = false
      hidden.phone = ''
    }
    if (modifyCoordinates.value) {
      hidden.coordinatesKnown = false
      hidden.longitude = null
      hidden.latitude = null
    }
    const definitelyRejected = cause?.kind === 'server' && (cause.status === 200 || cause.status === 400)
    const prefix = definitelyRejected ? '资料未保存' : '保存结果暂时无法确认'
    saveError.value = `${prefix}：${cause?.message || '请求异常'}。已尝试刷新公开资料；请核对后再决定是否重试。`
    try { await loadPublicShop({ silent: true, preserveDraft: true }) } catch { /* Keep the entered values when the public read also fails. */ }
  } finally {
    if (isContextCurrent(context)) busy.value = ''
  }
}

function setLocalStatus(status) {
  if (!shop.value) return
  shop.value = { ...shop.value, status, statusDesc: status === 0 ? '营业中' : '已打烊' }
  statusUnknown.value = false
}

async function changeBusinessStatus() {
  if (busy.value || statusUnknown.value || !shop.value || ![0, 1].includes(shop.value.status)) return
  const context = captureContext()
  if (!context) return
  const targetStatus = shop.value.status === 0 ? 1 : 0
  const closing = targetStatus === 1
  busy.value = 'confirming-status'
  try {
    await ElMessageBox.confirm(
      closing
        ? '打烊后，顾客可能无法继续从店铺下单。请先确认已处理手头订单，再手动打烊。'
        : '开始营业后，顾客可能再次看到店铺并提交订单。请确认店铺已准备好营业。',
      closing ? '确认打烊' : '确认开始营业',
      { confirmButtonText: closing ? '确认打烊' : '确认营业', cancelButtonText: '再检查一下', type: closing ? 'warning' : 'info' },
    )
  } catch (cause) {
    if (isContextCurrent(context)) busy.value = ''
    if (cause !== 'cancel' && cause !== 'close' && isContextCurrent(context)) ElMessage.error(cause?.message || '确认框未能打开。')
    return
  }
  if (!isContextCurrent(context)) return
  busy.value = 'changing-status'
  statusNotice.value = ''
  try {
    await updateShopBusinessStatus(targetStatus)
    if (!isContextCurrent(context)) return
    setLocalStatus(targetStatus)
    try {
      const refreshed = await loadPublicShop({ silent: true, preserveDraft: true })
      if (isContextCurrent(context) && statusCode(refreshed?.status) === targetStatus) {
        ElMessage.success(closing ? '店铺已打烊。' : '店铺已开始营业。')
      } else if (isContextCurrent(context)) {
        setLocalStatus(targetStatus)
        statusNotice.value = '营业状态请求已成功返回，但公开详情没有提供可复核的状态。'
        ElMessage.warning(statusNotice.value)
      }
    } catch {
      if (isContextCurrent(context)) {
        setLocalStatus(targetStatus)
        statusNotice.value = '营业状态请求已成功返回，但暂时无法重新读取公开详情。'
        ElMessage.warning(statusNotice.value)
      }
    }
  } catch (cause) {
    if (!isContextCurrent(context)) return
    try {
      const refreshed = await loadPublicShop({ silent: true, preserveDraft: true })
      if (!isContextCurrent(context)) return
      const actualStatus = statusCode(refreshed?.status)
      if (actualStatus === targetStatus) {
        ElMessage.success(closing ? '店铺已打烊，状态已通过公开详情确认。' : '店铺已开始营业，状态已通过公开详情确认。')
      } else if (actualStatus !== null) {
        statusNotice.value = `本次操作未能确认；已读取到店铺当前状态：${actualStatus === 0 ? '营业中' : '已打烊'}。`
        ElMessage.warning(statusNotice.value)
      } else {
        statusUnknown.value = true
        statusNotice.value = '营业状态操作结果未确认，公开详情也没有返回状态；请刷新后再操作。'
        ElMessage.error(statusNotice.value)
      }
    } catch {
      if (!isContextCurrent(context)) return
      statusUnknown.value = true
      statusNotice.value = `营业状态操作结果未确认：${cause?.message || '请求异常'}。重新读取店铺状态后才能继续操作。`
      ElMessage.error(statusNotice.value)
    }
  } finally {
    if (isContextCurrent(context)) busy.value = ''
  }
}

function goLogin() {
  void router.push({ name: 'merchant-login', query: { redirect: '/merchant/settings' } })
}

onMounted(() => {
  mounted = true
  if (session.value) void loadPublicShop().catch(() => {})
})

watch(sessionKey, (next, previous) => {
  if (next === previous) return
  generation.value += 1
  resetSessionState()
  if (mounted && next) void loadPublicShop().catch(() => {})
})

watch(() => draft.pictureURL, () => { imageBroken.value = false })

onUnmounted(() => {
  mounted = false
  generation.value += 1
  publicRequestSequence.value += 1
  busy.value = ''
})
</script>

<style scoped>
.shop-settings { display: grid; gap: 22px; }
.shop-settings > .page-heading { margin-bottom: 0; }
.shop-settings .page-heading h1 { margin-bottom: 6px; }
.settings-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(300px, .85fr); gap: 20px; align-items: start; }
.profile-panel, .status-panel, .sensitive-panel { min-width: 0; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
.panel-heading h2, .status-panel h2, .sensitive-panel h2 { font-size: 20px; line-height: 1.4; margin: 0; }
.panel-heading .eyebrow, .status-panel .eyebrow, .sensitive-panel .eyebrow { margin-bottom: 5px; }
.panel-hint { color: #718078; font-size: 12px; }
.identity-row { display: flex; align-items: center; gap: 16px; padding: 16px; margin-bottom: 22px; border: 1px solid #e5ebe2; background: #fafbf8; border-radius: 13px; }
.identity-row strong { font-size: 16px; }
.identity-row p { margin: 4px 0 0; font-size: 12px; }
.shop-mark { width: 72px; height: 72px; flex: 0 0 72px; display: grid; place-items: center; overflow: hidden; border-radius: 15px; color: #19664f; background: #e9f0e7; font-size: 28px; font-weight: 700; }
.shop-mark img { width: 100%; height: 100%; object-fit: cover; }
.profile-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 20px; }
.wide-field { grid-column: 1 / -1; }
.profile-form :deep(.el-form-item), .private-input :deep(.el-form-item) { min-width: 0; }
.profile-form :deep(.el-input), .profile-form :deep(.el-textarea), .profile-form :deep(.el-time-editor) { width: 100%; }
.field-note { margin: 7px 0 0; color: #718078; font-size: 12px; line-height: 1.55; }
.field-error { color: #a5433e; }
.settings-alert { margin: 0 0 16px; }
.panel-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding-top: 16px; border-top: 1px solid #edf0e9; }
.panel-actions .muted { font-size: 12px; }
.settings-side { display: grid; gap: 20px; }
.status-summary { display: flex; align-items: flex-start; gap: 12px; margin: 20px 0; padding: 16px; border-radius: 12px; }
.status-summary.is-open { background: #edf5f0; color: #19664f; }
.status-summary.is-closed { background: #f3f4f0; color: #596960; }
.status-summary.is-unknown { background: #fff6e5; color: #8d5d15; }
.status-dot { width: 10px; height: 10px; flex: 0 0 10px; margin-top: 7px; border-radius: 50%; background: currentColor; }
.status-summary strong, .status-summary span:not(.status-dot) { display: block; }
.status-summary strong { font-size: 15px; }
.status-summary div span { margin-top: 3px; font-size: 12px; line-height: 1.55; opacity: .85; }
.hours-line { display: flex; justify-content: space-between; gap: 12px; padding: 0 1px 17px; color: #718078; font-size: 13px; }
.hours-line strong { color: #20372f; font-size: 13px; font-variant-numeric: tabular-nums; }
.status-action { width: 100%; }
.sensitive-panel > .muted { margin: 10px 0 17px; font-size: 12px; line-height: 1.7; }
.private-field { padding: 15px 0; border-top: 1px solid #edf0e9; }
.private-field-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 10px; }
.private-field-heading strong { font-size: 13px; }
.private-value { color: #718078; font-size: 11px; text-align: right; overflow-wrap: anywhere; }
.private-input { margin: 12px 0 0; }
.private-input :deep(.el-form-item__label) { font-size: 12px; }
.coordinate-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 12px; }
.coordinate-note { grid-column: 1 / -1; margin-top: -5px; }
.settings-state { text-align: center; max-width: 760px; margin: 0 auto; }
.settings-state .state-symbol { margin: 0 auto 12px; }
.settings-state h2 { margin: 0 0 8px; font-size: 20px; }
.settings-state p { margin: 0 0 18px; }

@media (max-width: 960px) {
  .settings-layout { grid-template-columns: minmax(0, 1.2fr) minmax(270px, .9fr); gap: 15px; }
  .profile-panel, .status-panel, .sensitive-panel { padding: 21px; }
}
@media (max-width: 760px) {
  .settings-layout { grid-template-columns: 1fr; }
  .panel-heading { align-items: flex-start; flex-direction: column; gap: 5px; }
  .panel-actions { align-items: stretch; flex-direction: column; }
  .panel-actions .el-button { width: 100%; }
}
@media (max-width: 480px) {
  .profile-grid, .coordinate-grid { grid-template-columns: 1fr; }
  .wide-field, .coordinate-note { grid-column: auto; }
  .identity-row { align-items: flex-start; padding: 13px; }
  .shop-mark { width: 60px; height: 60px; flex-basis: 60px; }
  .hours-line { flex-direction: column; gap: 3px; }
  .private-field-heading { align-items: flex-start; flex-direction: column; gap: 3px; }
  .private-value { text-align: left; }
}
</style>
