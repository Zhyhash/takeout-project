<template><span v-if="show" ref="badge" class="new-badge" aria-label="新订单">NEW</span></template>
<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {notificationState, queueOrderSeen} from './state.js'

const props = defineProps({ orderId: [Number, String], unseen: Boolean })
const badge = ref()
const show = computed(() => props.unseen && !notificationState.acknowledged.includes(String(props.orderId)))
let observer, dwell, inView = false, disposed = false
const cancel = () => { clearTimeout(dwell); dwell = null }
function schedule() {
  cancel()
  if (show.value && inView && document.visibilityState !== 'hidden') {
    dwell = setTimeout(() => { if (!disposed && inView && show.value && document.visibilityState !== 'hidden') queueOrderSeen(props.orderId) }, 1500)
  }
}
async function observe() {
  cancel(); observer?.disconnect()
  await nextTick()
  if (disposed || !badge.value || !show.value) return
  if (typeof IntersectionObserver !== 'function') return // Keep NEW when actual visibility cannot be established.
  observer = new IntersectionObserver(([entry]) => { inView = entry.isIntersecting && entry.intersectionRatio >= 0.5; schedule() }, { threshold: 0.5 })
  observer.observe(badge.value)
}
watch(() => [props.orderId, show.value], observe)
onMounted(() => { document.addEventListener('visibilitychange', schedule); void observe() })
onBeforeUnmount(() => { disposed = true; cancel(); observer?.disconnect(); document.removeEventListener('visibilitychange', schedule) })
</script>
<style scoped>.new-badge{display:inline-flex;align-items:center;font-size:9px;font-weight:800;letter-spacing:.7px;line-height:1;padding:5px 7px;color:#a25426;background:#fff0df;border:1px solid #f2d7b6;border-radius:5px}</style>
